package com.spring.aichat.service.payment;

import com.spring.aichat.domain.enums.OrderStatus;
import com.spring.aichat.domain.enums.ProductType;
import com.spring.aichat.domain.payment.Order;
import com.spring.aichat.domain.payment.OrderRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.config.PortOneProperties;
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
 *
 * <p>[D-4.2 · D-4.3 · 안건 6 (c)+(나) · decisions_confirmed §A #6]
 * <ul>
 *   <li>(c) 구독 상품은 <b>최근 회차만</b> 환불 — PortOne 취소 <b>전</b>에 거부한다. renew가 merchant_uid를
 *       최신 회차로 덮으므로 과거 회차 주문번호로는 회수 대상 구독을 찾을 수 없고, 종전에는 그 상태에서
 *       돈만 나가고 구독이 살아남았다. 이력 테이블 신설은 런칭 전 범위 밖.</li>
 *   <li>(나) 회수 대상을 못 찾으면(시크릿 패스·해금 포함) 환불·주문 전이는 <b>유지</b>하되 감사로그를
 *       {@code REFUND_CLAWBACK_FAILED}로 분리 기록하고 {@link ClawbackFailedException}을 던져(롤백 없음)
 *       관리자가 사실을 모른 채 지나가지 않게 한다. 종전엔 "REFUND_EXECUTE 환불 완료"로만 남았다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PortOneClient portOneClient;
    /** [C-2.l] PortOne 서버 자격증명 미주입 가드용. */
    private final PortOneProperties portOneProperties;
    private final SecretModeService secretModeService;
    private final SubscriptionService subscriptionService;
    private final RedisCacheService cacheService;
    private final AuditLogService auditLogService;

    /**
     * [D-4.3 · 안건 6 (나)] 환불은 완료됐으나 혜택 회수 대상이 없었다 — 롤백하지 않는 예외.
     * {@link #refund}의 {@code noRollbackFor}에 걸려 PortOne 취소·REFUNDED 전이·감사로그는 커밋되고,
     * 컨트롤러에는 409({@code REFUND_CLAWBACK_FAILED})로 도달한다.
     */
    public static class ClawbackFailedException extends BusinessException {
        public ClawbackFailedException(String message) {
            super(ErrorCode.REFUND_CLAWBACK_FAILED, message);
        }
    }

    @Transactional(noRollbackFor = ClawbackFailedException.class)
    public void refund(String actor, String merchantUid, String reason) {
        Order order = orderRepository.findByMerchantUidForUpdate(merchantUid)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다: " + merchantUid));

        if (!order.getStatus().isPaidMoney()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "PAID/PAID_UNDELIVERED 상태만 환불할 수 있습니다. 현재 상태: " + order.getStatus());
        }
        // [안건 4] 미지급 주문(PAID_UNDELIVERED)은 지급된 혜택이 없으므로 회수 없이 취소만 — 회차 선검증도 불요
        boolean undelivered = order.getStatus() == OrderStatus.PAID_UNDELIVERED;
        if (order.getImpUid() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "impUid 가 없어 PortOne 취소가 불가합니다.");
        }

        // [C-2.l · docs/17_assets/defect_register.md §C-2.l] PortOne 자격증명 미주입 가드.
        //   아래 catch가 모든 예외를 "PortOne 결제 취소에 실패했습니다"로 뭉개므로, 설정 누락이
        //   CS 창구에서 'PG 장애'로 오독된다. 실호출 전에 명시적으로 끊는다.
        portOneProperties.assertConfigured("refund");

        // [안건 6 (c)] 구독 과거 회차 환불 거부 — 돈이 나가기 *전*에. (아래 clawback의 미발견과 달리 이쪽은 예측 가능한 거부)
        if (!undelivered) assertRefundableRound(order);

        // 1. PortOne 결제 취소 (실패 시 중단 — 아무것도 변경되지 않음)
        try {
            portOneClient.cancelPayment(order.getImpUid(), order.getAmount(),
                reason != null && !reason.isBlank() ? reason : "관리자 환불");
        } catch (Exception e) {
            log.error("[REFUND] PortOne 취소 실패: uid={}", merchantUid, e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "PortOne 결제 취소에 실패했습니다: " + e.getMessage());
        }

        // 2. 지급 혜택 회수 + 3. 주문 상태 전이 (같은 트랜잭션) — 미지급 주문은 회수할 혜택이 없다
        boolean clawedBack = undelivered || clawback(order);
        order.markRefunded();
        orderRepository.save(order);
        cacheService.evictUserProfile(order.getUser().getUsername());

        // 4. 감사 — 회수 실패는 별도 액션으로 분리 기록한다(REFUND_EXECUTE 밑에 숨기지 않는다)
        String detail = String.format("환불 %s(%,d원) user=%s (사유: %s)%s",
            order.getProductType().name(), order.getAmount(), order.getUser().getUsername(), reason,
            undelivered ? " [미지급 주문 취소 — 회수 대상 없음]" : "");
        if (clawedBack) {
            auditLogService.record(actor, "REFUND_EXECUTE", "ORDER", merchantUid, detail);
            log.info("[REFUND] Done: uid={}, product={}, amount={}", merchantUid, order.getProductType(), order.getAmount());
            return;
        }
        auditLogService.record(actor, "REFUND_CLAWBACK_FAILED", "ORDER", merchantUid,
            detail + " — ★ 혜택 회수 대상 없음: 돈은 환불됐고 주문은 REFUNDED이나 지급 혜택이 남아 있을 수 있음. 수동 정합 필요");
        log.error("[REFUND] CLAWBACK FAILED — refunded but benefit NOT revoked: uid={}, product={}, user={}",
            merchantUid, order.getProductType(), order.getUser().getUsername());
        throw new ClawbackFailedException(
            "환불은 완료됐지만 회수할 혜택을 찾지 못했습니다(" + order.getProductType().name()
                + "). 유저 혜택 상태를 수동으로 확인해 주세요. merchantUid=" + merchantUid);
    }

    /**
     * [안건 6 (c) · 적대적 리뷰 P2] 구독 상품 회수 선검증 — 과거 회차·이월 원천 회차는 PortOne 취소 전에 거부.
     * 판정은 SubscriptionService.assertRefundableRound(스냅샷·이월 출처 기준).
     */
    private void assertRefundableRound(Order order) {
        switch (order.getProductType()) {
            case LUCID_PASS, LUCID_MIDNIGHT_PASS -> subscriptionService.assertRefundableRound(order.getMerchantUid());
            default -> { /* 에너지·시크릿은 회차 개념 없음 */ }
        }
    }

    /**
     * 상품별 혜택 역처리. 지급 로직(PaymentService.deliverProduct)을 그대로 반대로 돌린다.
     * 이미 소비된 에너지는 0 미만으로 내려가지 않도록 클램핑된다(정책: 소비분은 회수 불가).
     *
     * @return 회수 대상을 실제로 찾아 되돌렸으면 true (에너지는 항상 true — 클램핑 회수)
     */
    private boolean clawback(Order order) {
        User user = order.getUser();
        ProductType product = order.getProductType();
        return switch (product) {
            case ENERGY_T1, ENERGY_T2, ENERGY_T3 -> {
                user.deductPaidEnergy(product.getEnergyAmount());
                userRepository.save(user);
                log.info("[REFUND] Clawback energy -{}: user={}", product.getEnergyAmount(), user.getUsername());
                yield true;
            }
            case SECRET_PASS_24H -> secretModeService.revoke24hPassByMerchantUid(order.getMerchantUid());
            case SECRET_UNLOCK_PERMANENT -> secretModeService.revokePermanentUnlockByMerchantUid(order.getMerchantUid());
            case LUCID_PASS, LUCID_MIDNIGHT_PASS -> subscriptionService.clawbackRound(order.getMerchantUid());
        };
    }
}
