package com.spring.aichat.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.spring.aichat.domain.enums.OrderStatus;
import com.spring.aichat.domain.enums.ProductType;
import com.spring.aichat.domain.payment.Order;
import com.spring.aichat.domain.payment.OrderRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.payment.*;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.external.PortOneClient;
import com.spring.aichat.service.cache.RedisCacheService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 결제 서비스
 *
 * [Phase 5 안정성 개선]
 *
 * 1. 비관적 락(PESSIMISTIC_WRITE)으로 동시성 중복 지급 방지
 *    - findByMerchantUidForUpdate() = SELECT ... FOR UPDATE
 *    - 동일 merchantUid 동시 요청 시 DB 행 락으로 직렬화
 *
 * 2. 웹훅 수신 (processWebhook)
 *    - PortOne이 결제 완료 시 서버로 직접 통보
 *    - 클라이언트 /confirm과 웹훅 중 먼저 도착한 쪽이 처리
 *    - 늦게 도착한 쪽은 멱등성에 의해 조용히 리턴
 *
 * 3. 멱등성(Idempotency) 설계
 *    - 이미 PAID인 주문에 대한 재요청: 예외 없이 성공 응답
 *    - FAILED/EXPIRED 주문: 처리 거부 (명확한 에러 코드)
 *    - PENDING만 실제 검증 + 지급 수행
 *
 * [검증 플로우 (confirm / webhook 공통)]
 *    1. merchantUid로 주문 조회 (FOR UPDATE - 행 락)
 *    2. 상태 체크 (PENDING만 통과, PAID는 멱등 성공, 나머지는 거부)
 *    3. PortOne API로 실결제 정보 조회
 *    4. 결제 상태 확인 ("paid" 여부)
 *    5. 금액 위변조 검증 (불일치 시 자동 환불)
 *    6. PAID 처리 + 재화 지급
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PortOneClient portOneClient;
    private final RedisCacheService cacheService;

    // ─────────────────────────────────────────────
    // Step 1: 사전 주문 생성
    // ─────────────────────────────────────────────

    @Transactional
    public PrepareOrderResponse prepareOrder(String username, PrepareOrderRequest request) {
        User user = findUser(username);
        ProductType product = request.productType();

        if (product == ProductType.SECRET_UNLOCK_PERMANENT && request.targetCharacterId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "target character ID required for secret unlock");
        }

        String merchantUid = "lucid_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Order order = Order.create(merchantUid, user, product, request.targetCharacterId());
        orderRepository.save(order);

        log.info("[PAYMENT] Order created: uid={}, product={}, amount={}",
            merchantUid, product.name(), product.getPriceKrw());

        return new PrepareOrderResponse(merchantUid, product.getDisplayName(), product.getPriceKrw());
    }

    // ─────────────────────────────────────────────
    // Step 3a: 클라이언트 사후 검증 (/confirm)
    // ─────────────────────────────────────────────

    @Transactional
    public PaymentResultResponse confirmPayment(String username, ConfirmPaymentRequest request) {
        // 비관적 락으로 주문 조회 (동시성 방어)
        Order order = orderRepository.findByMerchantUidForUpdate(request.merchantUid())
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                "Order not found: " + request.merchantUid()));

        // 소유권 검증 (웹훅에서는 스킵 — 여기서만 수행)
        if (!order.getUser().getUsername().equals(username)) {
            log.warn("[PAYMENT] Ownership mismatch: uid={}, user={}", request.merchantUid(), username);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Order ownership mismatch");
        }

        // 공통 검증 + 지급 로직 위임
        return verifyAndDeliver(order, request.impUid(), "CLIENT");
    }

    // ─────────────────────────────────────────────
    // Step 3b: 웹훅 수신 (/webhook)
    // ─────────────────────────────────────────────

    /**
     * PortOne 웹훅 처리
     *
     * [설계 원칙]
     * - 소유권 검증 불필요 (PortOne 서버가 호출하므로)
     * - 멱등성 필수 (클라이언트 /confirm과 경합 가능)
     * - 실패해도 200 응답 (PortOne이 재시도하도록)
     *   단, 내부적으로 로깅 + 알림
     *
     * @param impUid PortOne 결제 고유번호
     * @param merchantUid 우리 서버 주문번호
     */
    @Transactional
    public void processWebhook(String impUid, String merchantUid) {
        // 비관적 락으로 주문 조회
        Order order = orderRepository.findByMerchantUidForUpdate(merchantUid)
            .orElse(null);

        if (order == null) {
            log.warn("[WEBHOOK] Order not found: merchantUid={}, impUid={}", merchantUid, impUid);
            return; // 웹훅은 200 리턴해야 재시도 안 함
        }

        try {
            verifyAndDeliver(order, impUid, "WEBHOOK");
        } catch (BusinessException e) {
            // 멱등성에 의한 ALREADY_PROCESSED는 정상 케이스
            if (e.getErrorCode() == ErrorCode.PAYMENT_ALREADY_PROCESSED) {
                log.info("[WEBHOOK] Already processed (idempotent): uid={}", merchantUid);
            } else {
                log.error("[WEBHOOK] Verification failed: uid={}, error={}", merchantUid, e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────
    // 공통 검증 + 지급 로직 (핵심)
    // ─────────────────────────────────────────────

    /**
     * 결제 검증 + 재화 지급 (confirm / webhook 공통)
     *
     * [멱등성 보장]
     * - PAID: 이미 처리됨 -> 성공 응답 반환 (중복 지급 없음)
     * - FAILED/EXPIRED/REFUNDED: 처리 불가 -> 예외
     * - PENDING: 실제 검증 수행 -> PAID or FAILED
     *
     * [비관적 락에 의한 직렬화]
     * 이 메서드 진입 시점에 이미 FOR UPDATE 락이 걸려 있으므로
     * 동일 merchantUid에 대한 동시 호출은 순차 실행됨.
     * 첫 번째 스레드가 PAID로 전환 후 커밋하면,
     * 두 번째 스레드는 PAID 상태를 보고 멱등 분기로 진입.
     */
    private PaymentResultResponse verifyAndDeliver(Order order, String impUid, String caller) {

        // ── 멱등성 분기 ──
        if (order.getStatus() == OrderStatus.PAID) {
            log.info("[PAYMENT:{}] Already PAID (idempotent): uid={}", caller, order.getMerchantUid());
            return buildResult(order, "Already processed");
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("[PAYMENT:{}] Non-PENDING state: uid={}, status={}",
                caller, order.getMerchantUid(), order.getStatus());
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                "Order in terminal state: " + order.getStatus());
        }

        // ── PortOne 실결제 정보 조회 ──
        JsonNode paymentInfo = portOneClient.getPaymentInfo(impUid);
        int paidAmount = paymentInfo.path("amount").asInt();
        String portOneStatus = paymentInfo.path("status").asText();

        // ── 결제 상태 확인 ──
        if (!"paid".equals(portOneStatus)) {
            order.markFailed("PortOne status: " + portOneStatus);
            orderRepository.save(order);
            log.warn("[PAYMENT:{}] Not paid: uid={}, portOneStatus={}",
                caller, order.getMerchantUid(), portOneStatus);
            return buildResult(order, "Payment not completed");
        }

        // ── 금액 위변조 검증 (가장 중요!) ──
        if (paidAmount != order.getAmount()) {
            log.error("[PAYMENT:{}] AMOUNT MISMATCH! uid={}, expected={}, actual={}",
                caller, order.getMerchantUid(), order.getAmount(), paidAmount);

            try {
                portOneClient.cancelPayment(impUid, paidAmount, "Amount mismatch - auto refund");
                log.info("[PAYMENT:{}] Auto-refund success: impUid={}", caller, impUid);
            } catch (Exception e) {
                log.error("[PAYMENT:{}] AUTO-REFUND FAILED! MANUAL INTERVENTION NEEDED. impUid={}",
                    caller, impUid, e);
            }

            order.markFailed("Amount mismatch: expected=" + order.getAmount() + " actual=" + paidAmount);
            orderRepository.save(order);

            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                "Amount mismatch detected. Auto-refund initiated.");
        }

        // ── 검증 통과 -> PAID + 재화 지급 ──
        order.markPaid(impUid);
        deliverProduct(order);
        orderRepository.save(order);

        // 유저 캐시 무효화
        cacheService.evictUserProfile(order.getUser().getUsername());

        log.info("[PAYMENT:{}] Confirmed: uid={}, product={}, amount={}",
            caller, order.getMerchantUid(), order.getProductType().name(), order.getAmount());

        return buildResult(order, "Payment confirmed");
    }

    // ─────────────────────────────────────────────
    // 재화 지급
    // ─────────────────────────────────────────────

    private void deliverProduct(Order order) {
        User user = order.getUser();
        ProductType product = order.getProductType();

        switch (product) {
            case ENERGY_T1, ENERGY_T2, ENERGY_T3 -> {
                user.chargePaidEnergy(product.getEnergyAmount());
                log.info("[PAYMENT] Energy charged: user={}, +{}", user.getUsername(), product.getEnergyAmount());
            }
            case SECRET_PASS_24H -> {
                // TODO: Phase 5 BM - 24h secret pass activation
                log.info("[PAYMENT] Secret pass 24h: user={}", user.getUsername());
            }
            case SECRET_UNLOCK_PERMANENT -> {
                // TODO: Phase 5 BM - permanent secret unlock
                log.info("[PAYMENT] Secret unlock: user={}, charId={}",
                    user.getUsername(), order.getTargetCharacterId());
            }
            case LUCID_PASS_MONTHLY -> {
                // TODO: Phase 5 BM - subscription activation
                log.info("[PAYMENT] Lucid Pass: user={}", user.getUsername());
            }
        }
        userRepository.save(user);
    }

    // ─────────────────────────────────────────────

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
    }

    private PaymentResultResponse buildResult(Order order, String message) {
        return new PaymentResultResponse(
            order.getMerchantUid(), order.getImpUid(),
            order.getProductType(), order.getAmount(),
            order.getStatus(), message);
    }
}