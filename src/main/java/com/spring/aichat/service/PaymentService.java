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

@Service @Slf4j @RequiredArgsConstructor
public class PaymentService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PortOneClient portOneClient;
    private final RedisCacheService cacheService;

    @Transactional
    public PrepareOrderResponse prepareOrder(String username, PrepareOrderRequest request) {
        User user = findUser(username);
        ProductType product = request.productType();

        if (product == ProductType.SECRET_UNLOCK_PERMANENT && request.targetCharacterId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "target character ID required");
        }

        String merchantUid = "lucid_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Order order = Order.create(merchantUid, user, product, request.targetCharacterId());
        orderRepository.save(order);

        log.info("[PAYMENT] Order created: uid={}, product={}, amount={}",
            merchantUid, product.name(), product.getPriceKrw());

        return new PrepareOrderResponse(merchantUid, product.getDisplayName(), product.getPriceKrw());
    }

    @Transactional
    public PaymentResultResponse confirmPayment(String username, ConfirmPaymentRequest request) {
        Order order = orderRepository.findByMerchantUid(request.merchantUid())
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "Order not found: " + request.merchantUid()));

        if (!order.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Order ownership mismatch");
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED, "Already processed: " + order.getStatus());
        }

        JsonNode paymentInfo = portOneClient.getPaymentInfo(request.impUid());
        int paidAmount = paymentInfo.path("amount").asInt();
        String portOneStatus = paymentInfo.path("status").asText();

        if (!"paid".equals(portOneStatus)) {
            order.markFailed("PortOne status: " + portOneStatus);
            orderRepository.save(order);
            return buildResult(order, "Payment not completed");
        }

        // CRITICAL: Amount tampering check
        if (paidAmount != order.getAmount()) {
            log.error("[PAYMENT] AMOUNT MISMATCH! uid={}, expected={}, actual={}",
                request.merchantUid(), order.getAmount(), paidAmount);
            try {
                portOneClient.cancelPayment(request.impUid(), paidAmount, "Amount mismatch - auto refund");
            } catch (Exception e) {
                log.error("[PAYMENT] Auto-refund FAILED! Manual intervention needed. impUid={}", request.impUid(), e);
            }
            order.markFailed("Amount mismatch: expected=" + order.getAmount() + " actual=" + paidAmount);
            orderRepository.save(order);
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "Amount mismatch. Auto-refund initiated.");
        }

        order.markPaid(request.impUid());
        deliverProduct(order);
        orderRepository.save(order);
        cacheService.evictUserProfile(username);

        log.info("[PAYMENT] Confirmed: uid={}, product={}, amount={}",
            request.merchantUid(), order.getProductType().name(), order.getAmount());

        return buildResult(order, "Payment confirmed");
    }

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
                log.info("[PAYMENT] Secret unlock: user={}, charId={}", user.getUsername(), order.getTargetCharacterId());
            }
            case LUCID_PASS_MONTHLY -> {
                // TODO: Phase 5 BM - subscription activation
                log.info("[PAYMENT] Lucid Pass: user={}", user.getUsername());
            }
        }
        userRepository.save(user);
    }

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