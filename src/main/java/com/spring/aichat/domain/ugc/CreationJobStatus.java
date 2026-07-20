package com.spring.aichat.domain.ugc;

/**
 * [UGC v1] 캐릭터 생성 잡 상태 머신.
 *
 * <pre>
 * CONCEPT_PROCESSING → GACHA_WAIT → BASE_PROCESSING → BASE_WAIT → EMOTIONS_PROCESSING
 *   → REVIEW_WAIT → POSTPROCESSING → BINDING → READY
 *                                              ↘ FAILED (어느 단계든, failReason 보존)
 *                                              ↘ EXPIRED (*_WAIT 72h 방치)
 * </pre>
 *
 * <p>{@code *_WAIT} = 유저 개입 대기(과금·GPU 정지 상태). {@code *_PROCESSING/POSTPROCESSING/BINDING}
 * = 외부 잡/서버 작업 진행 중.
 */
public enum CreationJobStatus {

    /** Stage 0 — LLM 컨셉 구조화 + 모더레이션 게이트 */
    CONCEPT_PROCESSING,
    /** Stage 1 완료 — 황금샷 후보 중 유저 선택 대기 ① (썸네일·원화 확정) */
    GACHA_WAIT,
    /** Stage 2 — Qwen 2패스 ×2(서로 다른 seed) + WF-2 스탠딩 후보 생성 중 */
    BASE_PROCESSING,
    /** Stage 2 완료 — 스탠딩 후보 중 유저 선택 대기 ①-b (2026-07-20 개편: 베이스 리롤 가능화) */
    BASE_WAIT,
    /** Stage 3 — 감정 14종 병렬 파생 중 (스타 토폴로지) */
    EMOTIONS_PROCESSING,
    /** Stage 3 완료 — 15종 그리드 유저 검수 대기 ② */
    REVIEW_WAIT,
    /** Stage 4 — WF-3 누끼 + 규격화 + 서비스 S3 확정 복사 */
    POSTPROCESSING,
    /** Stage 4 — Character 등록·세계관 바인딩 */
    BINDING,
    /** 완료 — characterId 확정 */
    READY,
    /** 실패 종결 — failReason·externalJobs 보존(디버깅) */
    FAILED,
    /** *_WAIT 방치 TTL 만료 종결 */
    EXPIRED;

    public boolean isWait() {
        return this == GACHA_WAIT || this == BASE_WAIT || this == REVIEW_WAIT;
    }

    public boolean isProcessing() {
        return this == CONCEPT_PROCESSING || this == BASE_PROCESSING
            || this == EMOTIONS_PROCESSING || this == POSTPROCESSING || this == BINDING;
    }

    public boolean isTerminal() {
        return this == READY || this == FAILED || this == EXPIRED;
    }

    public boolean isActive() {
        return !isTerminal();
    }
}
