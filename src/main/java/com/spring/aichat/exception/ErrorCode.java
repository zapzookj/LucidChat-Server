package com.spring.aichat.exception;

/**
 * 에러 코드 표준화
 *
 * [Phase 5] PAYMENT_*, VERIFICATION_* 추가
 */
public enum ErrorCode {
    // ── 기존 ──
    BAD_REQUEST,
    NOT_FOUND,
    INSUFFICIENT_ENERGY,
    EXTERNAL_API_ERROR,
    INTERNAL_ERROR,

    // ── Phase 5: 결제 ──
    PAYMENT_AMOUNT_MISMATCH,      // 결제 금액 위변조 감지
    PAYMENT_ALREADY_PROCESSED,    // 이미 처리된 주문
    PAYMENT_VERIFICATION_FAILED,  // PortOne 검증 실패
    PAYMENT_CANCELLED,            // 결제 취소됨
    ORDER_NOT_FOUND,              // 주문 조회 실패
    ORDER_EXPIRED,                // 주문 만료

    // ── Phase 5: 성인 인증 ──
    VERIFICATION_TOKEN_FAILED,    // NICE 토큰 발급 실패
    VERIFICATION_DECRYPT_FAILED,  // 결과 복호화 실패
    VERIFICATION_UNDERAGE,        // 만 19세 미만
    VERIFICATION_DUPLICATE_CI,    // CI 중복 (다중 계정)
    VERIFICATION_ALREADY_DONE,    // 이미 인증 완료
    VERIFICATION_EXPIRED          // 인증 세션 만료
}