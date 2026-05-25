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
    CONFLICT,

    /**
     * [Phase6/Tier4 / H-22] 클라이언트가 보낸 batchId 등 세션 상태가 서버 기준과
     * 어긋남 → 409. 클라이언트는 새로고침 또는 상태 재동기화 필요.
     */
    STALE_CLIENT_STATE,

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Story V2] 신규 에러 코드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** World 마스터 조회 실패 → 404. */
    WORLD_NOT_FOUND,

    /**
     * 유저가 이미 같은 World에 V2 STORY 방을 가지고 있는데 overwriteExisting=false로
     * 신규 생성 요청 → 409. UI는 confirm 모달 후 overwriteExisting=true로 재호출.
     */
    STORY_V2_ROOM_EXISTS,

    /**
     * World에 시작 가능 장소가 시드되지 않음 → 500 (서버 결함).
     * StoryCreateFlow에서 시작 장소 폴백이 실패한 경우.
     */
    WORLD_LOCATION_MISSING,

    /**
     * 프리미엄 기능 (예: 자유 페르소나 BM) 미보유 → 402.
     */
    PREMIUM_REQUIRED
}