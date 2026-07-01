package com.spring.aichat.service.admin;

import com.spring.aichat.domain.enums.ProductType;
import com.spring.aichat.domain.payment.OrderRepository;
import com.spring.aichat.dto.admin.AdminOrderResponse;
import com.spring.aichat.dto.admin.ProductRevenue;
import com.spring.aichat.dto.admin.RevenueSummary;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 관리자 결제/주문 조회 + 매출 집계 (읽기 전용). 환불 실행은 RefundService. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPaymentService {

    private final OrderRepository orderRepository;

    public List<AdminOrderResponse> listUserOrders(Long userId) {
        return orderRepository.findByUser_IdOrderByCreatedAtDesc(userId).stream()
            .map(AdminOrderResponse::from)
            .toList();
    }

    public AdminOrderResponse getOrder(String merchantUid) {
        return orderRepository.findByMerchantUid(merchantUid)
            .map(AdminOrderResponse::from)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "주문을 찾을 수 없습니다: " + merchantUid));
    }

    public RevenueSummary revenue(LocalDateTime from, LocalDateTime to) {
        long paid = orderRepository.sumPaidBetween(from, to);
        long refunded = orderRepository.sumRefundedBetween(from, to);
        List<ProductRevenue> byProduct = orderRepository.revenueByProductBetween(from, to).stream()
            .map(row -> new ProductRevenue(
                ((ProductType) row[0]).name(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue()))
            .toList();
        return new RevenueSummary(from, to, paid, refunded, paid - refunded, byProduct);
    }
}
