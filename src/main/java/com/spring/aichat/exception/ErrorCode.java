package com.spring.aichat.exception;

/**
 * 에러 코드 표준화
 *
 * [Phase 5] CONTENT_BLOCKED 추가 — 콘텐츠 모더레이션 차단
 */
public enum ErrorCode {
    BAD_REQUEST,
    NOT_FOUND,
    INSUFFICIENT_ENERGY,
    EXTERNAL_API_ERROR,
    INTERNAL_ERROR,

    // Phase 5: Payment
    PAYMENT_AMOUNT_MISMATCH,
    PAYMENT_ALREADY_PROCESSED,
    PAYMENT_VERIFICATION_FAILED,
    ORDER_NOT_FOUND,

    // Phase 5: Verification
    VERIFICATION_TOKEN_FAILED,
    VERIFICATION_DECRYPT_FAILED,
    VERIFICATION_UNDERAGE,
    VERIFICATION_DUPLICATE_CI,
    VERIFICATION_EXPIRED,
    VERIFICATION_ALREADY_DONE,

    FORBIDDEN, // Phase 5: Content Moderation
    CONTENT_BLOCKED,

    /**
     * [Phase 5.5 UX Polish · R4] 활성 Theater 세션 충돌 (모델 C-2).
     * 활성극이 있는데 새 극 시작 시 overwriteActive=true가 없으면 발생 → 409.
     * UI는 이 응답을 받으면 confirm 모달을 띄우고 overwriteActive=true로 재호출.
     */
    CONFLICT
}