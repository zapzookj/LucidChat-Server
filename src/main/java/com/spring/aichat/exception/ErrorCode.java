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
    /**
     * [D-4.3 · 안건 6 (나)] 환불은 PortOne에서 완료됐고 주문도 REFUNDED로 기록됐으나 <b>혜택 회수 대상을 찾지 못했다</b>.
     * 트랜잭션은 롤백하지 않는다(유저 유리 원칙) — 대신 관리자가 사실을 모른 채 지나가지 않도록 예외로 올린다.
     */
    REFUND_CLAWBACK_FAILED,
    /**
     * [안건 4 (b)] 결제는 확정(PAID_UNDELIVERED)됐으나 재화 지급이 실패해 재시도 대기 중. FE는 이 코드를 '결제 실패'가
     * 아니라 '결제 완료 · 지급 대기'로 그리고 /confirm 재호출 버튼을 보여야 한다(재구매 유도 = 이중 결제).
     */
    PAYMENT_DELIVERY_PENDING,

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

    /**
     * [B-5.2] 과금되지 않은 배치를 소비하려 함 — 클라이언트는 {@code /next-batch}로
     * 그 배치를 정상 취득한 뒤 다시 시도해야 한다(FE가 자기 치유할 수 있도록 식별 가능한 코드로 둔다).
     *
     * <p>⚠ 이 코드가 **무증상 정지**가 되지 않게 하는 것이 중요하다 — FE는 이 코드를 만나면
     * {@code loadNextBatch()}로 복구하고, 그래도 실패하면 유저에게 보이는 에러를 띄워야 한다.
     * 현재 서버는 관측 모드(fail-open)라 이 코드를 던지지 않는다({@code theater.paid-batch-gate-enforced}).
     */
    UNPAID_BATCH,

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
    PREMIUM_REQUIRED,

    /**
     * [블록 B 페르소나] 시크릿 활성 요구에 페르소나 프로필 나이가 미달(미설정 포함) → 403.
     * 법적 성인인증(VERIFICATION_UNDERAGE)과 별개 — FE는 이 코드에 '나이를 수정할까요?'
     * 프로필 수정 제안 모달을 연결한다(UX는 부드럽게, 로직은 하드하게 — docs/14 #4).
     */
    PERSONA_UNDERAGE
}