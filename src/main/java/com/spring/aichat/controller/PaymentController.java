package com.spring.aichat.controller;

import com.spring.aichat.dto.payment.ConfirmPaymentRequest;
import com.spring.aichat.dto.payment.PaymentResultResponse;
import com.spring.aichat.dto.payment.PrepareOrderRequest;
import com.spring.aichat.dto.payment.PrepareOrderResponse;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.payment.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 결제 API
 *
 * [Phase 5 개선]
 *
 * 1. POST /api/v1/payments/ready    -> 사전 주문 생성
 * 2. POST /api/v1/payments/confirm  -> 클라이언트 사후 검증
 * 3. POST /api/v1/payments/webhook  -> PortOne 웹훅 수신 (NEW)
 *
 * [웹훅 설계]
 * - PortOne 서버가 결제 완료 시 직접 호출
 * - JWT 인증 불필요 (SecurityConfig에서 permitAll)
 * - 반드시 200 응답 (비200이면 PortOne이 재시도)
 * - 멱등성: /confirm과 동시에 도착해도 중복 지급 없음
 *
 * [결제 누락 방지 시나리오]
 * 유저가 결제 완료 직후 브라우저를 닫은 경우:
 *   Client /confirm -> 호출 안 됨 (브라우저 종료)
 *   PortOne /webhook -> 서버로 직접 통보 -> 검증 + 에너지 지급
 *   -> 유저는 다음 접속 시 에너지가 정상 충전되어 있음
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;
    private final ApiRateLimiter rateLimiter;

    /**
     * 사전 주문 생성
     */
    @PostMapping("/ready")
    public ResponseEntity<PrepareOrderResponse> prepareOrder(
        @RequestBody @Valid PrepareOrderRequest request,
        Authentication authentication
    ) {
        if (rateLimiter.checkPayment(authentication.getName())) {
            throw new RateLimitException("결제 요청이 너무 빠릅니다.", 5);
        }
        PrepareOrderResponse response = paymentService.prepareOrder(
            authentication.getName(), request);
        return ResponseEntity.ok(response);
    }

    /**
     * 클라이언트 사후 검증
     * - 소유권 검증 수행
     * - 비관적 락으로 동시성 방어
     */
    @PostMapping("/confirm")
    public ResponseEntity<PaymentResultResponse> confirmPayment(
        @RequestBody @Valid ConfirmPaymentRequest request,
        Authentication authentication
    ) {
        if (rateLimiter.checkPayment(authentication.getName())) {
            throw new RateLimitException("결제 검증 요청이 너무 빠릅니다.", 5);
        }
        PaymentResultResponse response = paymentService.confirmPayment(
            authentication.getName(), request);
        return ResponseEntity.ok(response);
    }

    /**
     * PortOne 웹훅 수신
     *
     * [PortOne 웹훅 페이로드]
     * {
     *   "imp_uid": "imp_xxxx",
     *   "merchant_uid": "lucid_xxxx",
     *   "status": "paid"
     * }
     *
     * [중요]
     * - 어떤 상황에서도 200 응답 (비200이면 PortOne이 최대 5회 재시도)
     * - 내부 예외는 로깅만 하고 삼킴
     * - JWT 인증 없음 (SecurityConfig에서 permitAll 설정 필요)
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, String>> handleWebhook(
        @RequestBody Map<String, Object> payload
    ) {
        String impUid = (String) payload.get("imp_uid");
        String merchantUid = (String) payload.get("merchant_uid");

        log.info("[WEBHOOK] Received: impUid={}, merchantUid={}", impUid, merchantUid);

        if (impUid == null || merchantUid == null) {
            log.warn("[WEBHOOK] Missing required fields. payload={}", payload);
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "missing fields"));
        }

        try {
            paymentService.processWebhook(impUid, merchantUid);
            log.info("[WEBHOOK] Processed successfully: merchantUid={}", merchantUid);
        } catch (Exception e) {
            // 웹훅은 절대 에러 응답하지 않음 (PortOne 재시도 방지)
            // 단, 로깅은 반드시 수행 (모니터링/알림 연동)
            log.error("[WEBHOOK] Processing failed (will not retry): merchantUid={}", merchantUid, e);
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}