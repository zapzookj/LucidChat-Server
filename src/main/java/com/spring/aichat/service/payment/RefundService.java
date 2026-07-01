package com.spring.aichat.service.payment;

import com.spring.aichat.domain.enums.OrderStatus;
import com.spring.aichat.domain.enums.ProductType;
import com.spring.aichat.domain.payment.Order;
import com.spring.aichat.domain.payment.OrderRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.external.PortOneClient;
import com.spring.aichat.service.audit.AuditLogService;
import com.spring.aichat.service.cache.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자/CS 트리거 환불 오케스트레이션 (Phase 6 Phase D).
 *
 * 기존 프리미티브를 재사용한다:
 *  - PortOneClient.cancelPayment (이미 존재, auto-refund에서 사용 중)
 *  - Order.markRefunded (상태 전이, 그동안 미호출 dead code였음)
 * 여기에 "지급 혜택 회수(clawback)"를 더한다.
 *
 * [순서 근거] PortOne 취소를 *먼저* 수행하고(실패 시 아무것도 바꾸지 않고 중단),
 * 성공 후 혜택 회수 + markRefunded 를 같은 트랜잭션에서 처리한다. 취소 성공 후 DB 작업이
 * 실패하면 트랜잭션이 롤백되어 "돈은 환불됐지만 혜택은 유지" 상태가 되는데, 이는 유저에게
 * 유리한 방향이며 로그/감사로 수동 정합을 유도한다(반대 순서는 유저가 돈도 혜택도 잃을 위험).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PortOneClient portOneClient;
    private final SecretModeService secretModeService;
    private final SubscriptionService subscriptionService;
    private final RedisCacheService cacheService;
    private final AuditLogService auditLogService;

    @Transactional
    public void refund(String actor, String merchantUid, String reason) {
        Order order = orderRepository.findByMerchantUidForUpdate(merchantUid)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다: " + merchantUid));

        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "PAID 상태만 환불할 수 있습니다. 현재 상태: " + order.getStatus());
        }
        if (order.getImpUid() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "impUid 가 없어 PortOne 취소가 불가합니다.");
        }

        // 1. PortOne 결제 취소 (실패 시 중단 — 아무것도 변경되지 않음)
        try {
            portOneClient.cancelPayment(order.getImpUid(), order.getAmount(),
                reason != null && !reason.isBlank() ? reason : "관리자 환불");
        } catch (Exception e) {
            log.error("[REFUND] PortOne 취소 실패: uid={}", merchantUid, e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "PortOne 결제 취소에 실패했습니다: " + e.getMessage());
        }

        // 2. 지급 혜택 회수 + 3. 주문 상태 전이 (같은 트랜잭션)
        clawback(order);
        order.markRefunded();
        orderRepository.save(order);
        cacheService.evictUserProfile(order.getUser().getUsername());

        // 4. 감사
        auditLogService.record(actor, "REFUND_EXECUTE", "ORDER", merchantUid,
            String.format("환불 %s(%,d원) user=%s (사유: %s)",
                order.getProductType().name(), order.getAmount(), order.getUser().getUsername(), reason));
        log.info("[REFUND] Done: uid={}, product={}, amount={}", merchantUid, order.getProductType(), order.getAmount());
    }

    /**
     * 상품별 혜택 역처리. 지급 로직(PaymentService.deliverProduct)을 그대로 반대로 돌린다.
     * 이미 소비된 에너지는 0 미만으로 내려가지 않도록 클램핑된다(정책: 소비분은 회수 불가).
     */
    private void clawback(Order order) {
        User user = order.getUser();
        ProductType product = order.getProductType();
        switch (product) {
            case ENERGY_T1, ENERGY_T2, ENERGY_T3 -> {
                user.deductPaidEnergy(product.getEnergyAmount());
                userRepository.save(user);
                log.info("[REFUND] Clawback energy -{}: user={}", product.getEnergyAmount(), user.getUsername());
            }
            case SECRET_PASS_24H -> secretModeService.revoke24hPassByMerchantUid(order.getMerchantUid());
            case SECRET_UNLOCK_PERMANENT -> secretModeService.revokePermanentUnlockByMerchantUid(order.getMerchantUid());
            case LUCID_PASS, LUCID_MIDNIGHT_PASS -> subscriptionService.deactivateByMerchantUid(order.getMerchantUid());
        }
    }
}
