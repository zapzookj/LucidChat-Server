package com.spring.aichat.domain.enums;

/**
 * 주문 상태
 *
 * [상태 전이]
 * PENDING -> PAID (결제 완료, 검증 성공)
 * PENDING -> FAILED (결제 실패 or 위변조 감지)
 * PENDING -> EXPIRED (TTL 만료, 스케줄러가 정리)
 * PAID -> REFUNDED (환불 처리)
 */
public enum OrderStatus {
    PENDING,
    PAID,
    FAILED,
    EXPIRED,
    REFUNDED
}