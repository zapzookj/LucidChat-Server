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
 * [Phase 5 BM 패키지 연결 완료]
 *
 * deliverProduct()에서 모든 상품 타입에 대한 실제 지급 로직 구현:
 *   ENERGY_T1/T2/T3       -> User.chargePaidEnergy()
 *   SECRET_PASS_24H       -> SecretModeService.activate24hPass() (Redis TTL 24h)
 *   SECRET_UNLOCK_PERMANENT -> UserSecretUnlock 레코드 생성 (영구)
 *   LUCID_PASS             -> SubscriptionService.activateSubscription()
 *   LUCID_MIDNIGHT_PASS    -> SubscriptionService.activateSubscription()
 *
 * [구매 전 검증]
 * prepareOrder()에서 성인 전용 상품의 성인 인증 여부 검증
 * SECRET_UNLOCK_PERMANENT의 이중 구매 방지
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PortOneClient portOneClient;
    private final RedisCacheService cacheService;
    private final com.spring.aichat.service.payment.SecretModeService secretModeService;
    private final com.spring.aichat.service.payment.SubscriptionService subscriptionService;

    // ─────────────────────────────────────────────
    // Step 1: 사전 주문 생성
    // ─────────────────────────────────────────────

    @Transactional
    public PrepareOrderResponse prepareOrder(String username, PrepareOrderRequest request) {
        User user = findUser(username);
        ProductType product = request.productType();

        // 성인 전용 상품 검증
        if (product.isAdultOnly() && !Boolean.TRUE.equals(user.getIsAdult())) {
            throw new BusinessException(ErrorCode.VERIFICATION_UNDERAGE,
                "Adult verification required for this product");
        }

        // 시크릿 영구해금: targetCharacterId 필수 + 이중 구매 방지
        if (product == ProductType.SECRET_UNLOCK_PERMANENT) {
            if (request.targetCharacterId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "target character ID required for secret unlock");
            }
            if (secretModeService.hasPermanentUnlock(user.getId(), request.targetCharacterId())) {
                throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    "Character already unlocked");
            }
        }

        // 24시간 패스: targetCharacterId 필수
        if (product == ProductType.SECRET_PASS_24H && request.targetCharacterId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "target character ID required for secret pass");
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
        Order order = orderRepository.findByMerchantUidForUpdate(request.merchantUid())
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                "Order not found: " + request.merchantUid()));

        if (!order.getUser().getUsername().equals(username)) {
            log.warn("[PAYMENT] Ownership mismatch: uid={}, user={}", request.merchantUid(), username);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Order ownership mismatch");
        }

        return verifyAndDeliver(order, request.impUid(), "CLIENT");
    }

    // ─────────────────────────────────────────────
    // Step 3b: 웹훅 수신 (/webhook)
    // ─────────────────────────────────────────────

    @Transactional
    public void processWebhook(String impUid, String merchantUid) {
        Order order = orderRepository.findByMerchantUidForUpdate(merchantUid)
            .orElse(null);

        if (order == null) {
            log.warn("[WEBHOOK] Order not found: merchantUid={}, impUid={}", merchantUid, impUid);
            return;
        }

        try {
            verifyAndDeliver(order, impUid, "WEBHOOK");
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PAYMENT_ALREADY_PROCESSED) {
                log.info("[WEBHOOK] Already processed (idempotent): uid={}", merchantUid);
            } else {
                log.error("[WEBHOOK] Verification failed: uid={}, error={}", merchantUid, e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────
    // 공통 검증 + 지급 로직
    // ─────────────────────────────────────────────

    private PaymentResultResponse verifyAndDeliver(Order order, String impUid, String caller) {

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

        JsonNode paymentInfo = portOneClient.getPaymentInfo(impUid);
        int paidAmount = paymentInfo.path("amount").asInt();
        String portOneStatus = paymentInfo.path("status").asText();

        if (!"paid".equals(portOneStatus)) {
            order.markFailed("PortOne status: " + portOneStatus);
            orderRepository.save(order);
            log.warn("[PAYMENT:{}] Not paid: uid={}, portOneStatus={}",
                caller, order.getMerchantUid(), portOneStatus);
            return buildResult(order, "Payment not completed");
        }

        if (paidAmount != order.getAmount()) {
            log.error("[PAYMENT:{}] AMOUNT MISMATCH! uid={}, expected={}, actual={}",
                caller, order.getMerchantUid(), order.getAmount(), paidAmount);
            try {
                portOneClient.cancelPayment(impUid, paidAmount, "Amount mismatch - auto refund");
                log.info("[PAYMENT:{}] Auto-refund success: impUid={}", caller, impUid);
            } catch (Exception e) {
                log.error("[PAYMENT:{}] AUTO-REFUND FAILED! impUid={}", caller, impUid, e);
            }
            order.markFailed("Amount mismatch: expected=" + order.getAmount() + " actual=" + paidAmount);
            orderRepository.save(order);
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                "Amount mismatch detected. Auto-refund initiated.");
        }

        order.markPaid(impUid);
        deliverProduct(order);
        orderRepository.save(order);
        cacheService.evictUserProfile(order.getUser().getUsername());

        log.info("[PAYMENT:{}] Confirmed: uid={}, product={}, amount={}",
            caller, order.getMerchantUid(), order.getProductType().name(), order.getAmount());

        return buildResult(order, "Payment confirmed");
    }

    // ─────────────────────────────────────────────
    // 재화 지급 (BM 패키지 연동)
    // ─────────────────────────────────────────────

    /**
     * 상품 지급 로직
     *
     * [에너지] -> User.chargePaidEnergy()
     * [시크릿 24h] -> Redis TTL 24시간
     * [시크릿 영구] -> UserSecretUnlock 테이블 INSERT
     * [구독] -> SubscriptionService (구독 생성/연장/업그레이드)
     */
    private void deliverProduct(Order order) {
        User user = order.getUser();
        ProductType product = order.getProductType();

        switch (product) {
            case ENERGY_T1, ENERGY_T2, ENERGY_T3 -> {
                user.chargePaidEnergy(product.getEnergyAmount());
                userRepository.save(user);
                log.info("[DELIVER] Energy: user={}, +{}", user.getUsername(), product.getEnergyAmount());
            }

            case SECRET_PASS_24H -> {
                secretModeService.activate24hPass(user.getId(), order.getTargetCharacterId());
                log.info("[DELIVER] Secret 24h pass: user={}, charId={}",
                    user.getUsername(), order.getTargetCharacterId());
            }

            case SECRET_UNLOCK_PERMANENT -> {
                secretModeService.createPermanentUnlock(
                    user, order.getTargetCharacterId(), order.getMerchantUid());
                log.info("[DELIVER] Secret permanent unlock: user={}, charId={}",
                    user.getUsername(), order.getTargetCharacterId());
            }

            case LUCID_PASS, LUCID_MIDNIGHT_PASS -> {
                subscriptionService.activateSubscription(
                    user, product.toSubscriptionType(), order.getMerchantUid());
                log.info("[DELIVER] Subscription: user={}, tier={}",
                    user.getUsername(), product.toSubscriptionType());
            }
        }
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