package com.spring.aichat.dto.payment;

import com.spring.aichat.domain.enums.OrderStatus;
import com.spring.aichat.domain.enums.ProductType;

/**
 * 결제 결과 응답
 */
public record PaymentResultResponse(
    String merchantUid,
    String impUid,
    ProductType productType,
    int amount,
    OrderStatus status,
    String message
) {}