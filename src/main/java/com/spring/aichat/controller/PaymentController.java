package com.spring.aichat.controller;

import com.spring.aichat.dto.payment.ConfirmPaymentRequest;
import com.spring.aichat.dto.payment.PaymentResultResponse;
import com.spring.aichat.dto.payment.PrepareOrderRequest;
import com.spring.aichat.dto.payment.PrepareOrderResponse;
import com.spring.aichat.service.payment.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 결제 API
 *
 * [플로우]
 * 1. POST /api/v1/payments/ready   -> 사전 주문 생성 (merchantUid 발급)
 * 2. 클라이언트: PortOne SDK로 결제 진행
 * 3. POST /api/v1/payments/confirm -> 사후 검증 + 재화 지급
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 사전 주문 생성
     * - 결제창 열기 전 호출
     * - merchantUid, productName, amount 반환
     */
    @PostMapping("/ready")
    public ResponseEntity<PrepareOrderResponse> prepareOrder(
        @RequestBody @Valid PrepareOrderRequest request,
        Authentication authentication
    ) {
        PrepareOrderResponse response = paymentService.prepareOrder(
            authentication.getName(), request);
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 사후 검증
     * - PortOne SDK 결제 완료 후 호출
     * - imp_uid로 PortOne에 실결제 조회 -> DB 금액과 비교
     * - 위변조 감지 시 자동 환불
     */
    @PostMapping("/confirm")
    public ResponseEntity<PaymentResultResponse> confirmPayment(
        @RequestBody @Valid ConfirmPaymentRequest request,
        Authentication authentication
    ) {
        PaymentResultResponse response = paymentService.confirmPayment(
            authentication.getName(), request);
        return ResponseEntity.ok(response);
    }
}