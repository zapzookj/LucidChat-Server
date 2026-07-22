package com.spring.aichat.domain.ugc;

/**
 * [UGC 세계관 빌더] 월드 생성 잡 상태 머신 — {@link CreationJobStatus}의 축소판.
 *
 * <pre>
 * CONCEPT_PROCESSING → EDIT_WAIT → ILLUSTRATING → REVIEW_WAIT → BINDING → READY
 *                                                               ↘ FAILED (어느 단계든, failReason 보존)
 *                                                               ↘ EXPIRED (*_WAIT 72h 방치)
 * </pre>
 *
 * <ul>
 *   <li>CONCEPT_PROCESSING — W0: LLM 세계관 구조화(이름/소개/lore/무드/장소 제안 6) + 모더레이션</li>
 *   <li>EDIT_WAIT — W1: 유저 설정·장소 편집(상한 10). 일러 시작 전 무기한 편집(TTL 방치 만료만)</li>
 *   <li>ILLUSTRATING — W2: 썸네일 1 + 장소별 대표 배경 1 병렬 생성(fal flux-2)</li>
 *   <li>REVIEW_WAIT — W2 검수: 컷별 리롤(과금·누적)/버전 골라잡기(무료) 후 확정</li>
 *   <li>BINDING — W3: 에셋 승격 + UgcWorld/UgcWorldLocation 확정 저장</li>
 * </ul>
 */
public enum WorldCreationJobStatus {

    /** W0 — LLM 세계관 구조화 + 모더레이션 게이트 */
    CONCEPT_PROCESSING,
    /** W1 — 설정·장소 유저 편집 대기 (레이턴시 없음 — 유저 주도 구간) */
    EDIT_WAIT,
    /** W2 — 썸네일 + 장소 배경 병렬 생성 중 (fal flux-2) */
    ILLUSTRATING,
    /** W2 완료 — 일러 검수(리롤/버전 선택) 대기 */
    REVIEW_WAIT,
    /** W3 — 에셋 승격 + UgcWorld 확정 저장 */
    BINDING,
    /** 완료 — ugcWorldId 확정 */
    READY,
    /** 실패 종결 — failReason·externalJobs 보존(디버깅) */
    FAILED,
    /** *_WAIT 방치 TTL 만료 종결 */
    EXPIRED;

    public boolean isWait() {
        return this == EDIT_WAIT || this == REVIEW_WAIT;
    }

    public boolean isProcessing() {
        return this == CONCEPT_PROCESSING || this == ILLUSTRATING || this == BINDING;
    }

    public boolean isTerminal() {
        return this == READY || this == FAILED || this == EXPIRED;
    }

    public boolean isActive() {
        return !isTerminal();
    }
}
