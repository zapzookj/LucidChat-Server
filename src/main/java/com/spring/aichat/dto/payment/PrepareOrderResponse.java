package com.spring.aichat.dto.payment;

/**
 * 사전 주문 생성 응답
 * - 프론트엔드가 PortOne SDK에 전달할 정보
 */
public record PrepareOrderResponse(
    String merchantUid,
    String productName,
    int amount
) {}