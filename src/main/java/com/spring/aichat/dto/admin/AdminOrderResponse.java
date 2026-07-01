package com.spring.aichat.dto.admin;

import com.spring.aichat.domain.payment.Order;

import java.time.LocalDateTime;

public record AdminOrderResponse(
    String merchantUid,
    String impUid,
    String productType,
    int amount,
    String status,
    Long targetCharacterId,
    LocalDateTime createdAt,
    LocalDateTime paidAt,
    String failedReason
) {
    public static AdminOrderResponse from(Order o) {
        return new AdminOrderResponse(
            o.getMerchantUid(),
            o.getImpUid(),
            o.getProductType() != null ? o.getProductType().name() : null,
            o.getAmount(),
            o.getStatus() != null ? o.getStatus().name() : null,
            o.getTargetCharacterId(),
            o.getCreatedAt(),
            o.getPaidAt(),
            o.getFailedReason()
        );
    }
}
