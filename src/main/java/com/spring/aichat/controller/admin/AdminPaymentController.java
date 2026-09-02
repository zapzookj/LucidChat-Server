package com.spring.aichat.controller.admin;

import com.spring.aichat.dto.admin.AdminOrderResponse;
import com.spring.aichat.dto.admin.ReasonRequest;
import com.spring.aichat.dto.admin.RevenueSummary;
import com.spring.aichat.service.admin.AdminPaymentService;
import com.spring.aichat.service.payment.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 결제/환불 API. /api/v1/admin/** 은 ROLE_ADMIN 게이트.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/payments")
public class AdminPaymentController {

    private final AdminPaymentService adminPaymentService;
    private final RefundService refundService;
    private final com.spring.aichat.service.payment.PaymentService paymentService;
    private final com.spring.aichat.service.audit.AuditLogService auditLogService;

    /**
     * [안건 4 (b) · 적대적 리뷰 P1] 미지급(PAID_UNDELIVERED) 주문 재지급 — 스케줄러 자동 재시도(15분·24h)와 같은 경로.
     * 지급이 끝났으면 PAID, 여전히 실패면 PAID_UNDELIVERED + failed_reason 갱신으로 돌아온다(예외 아님).
     */
    @PostMapping("/orders/{merchantUid}/redeliver")
    public AdminOrderResponse redeliver(@PathVariable String merchantUid, Authentication auth) {
        boolean delivered = paymentService.redeliver(merchantUid, "ADMIN:" + auth.getName());
        auditLogService.record(auth.getName(), "PAYMENT_REDELIVER", "ORDER", merchantUid,
            delivered ? "관리자 재지급 — 지급 완료" : "관리자 재지급 시도 — 실패(PAID_UNDELIVERED 유지)");
        return adminPaymentService.getOrder(merchantUid);
    }

    @GetMapping("/orders")
    public List<AdminOrderResponse> userOrders(@RequestParam Long userId) {
        return adminPaymentService.listUserOrders(userId);
    }

    @GetMapping("/orders/{merchantUid}")
    public AdminOrderResponse order(@PathVariable String merchantUid) {
        return adminPaymentService.getOrder(merchantUid);
    }

    @PostMapping("/orders/{merchantUid}/refund")
    public AdminOrderResponse refund(@PathVariable String merchantUid,
                                     @RequestBody(required = false) ReasonRequest req,
                                     Authentication auth) {
        refundService.refund(auth.getName(), merchantUid, req != null ? req.reason() : null);
        return adminPaymentService.getOrder(merchantUid);
    }

    @GetMapping("/revenue")
    public RevenueSummary revenue(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        LocalDateTime f = (from != null ? from.atStartOfDay() : LocalDate.now().minusDays(30).atStartOfDay());
        LocalDateTime t = (to != null ? to.plusDays(1).atStartOfDay() : LocalDate.now().plusDays(1).atStartOfDay());
        return adminPaymentService.revenue(f, t);
    }
}
