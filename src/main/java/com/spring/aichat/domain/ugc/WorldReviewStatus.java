package com.spring.aichat.domain.ugc;

/**
 * [UGC 세계관 빌더] 월드 검수 상태.
 *
 * <p>월드 독립 공개/신청 경로는 없다 — 소유 캐릭터의 <b>공개 심사에 피기백</b>되어 판정된다
 * (2026-07-20 확정 설계). 따라서 PENDING이 없다: 큐 적재 트리거는 캐릭터의 PENDING_PUBLIC이며,
 * 월드는 판정 결과만 이 상태로 남긴다.
 *
 * <p>1 월드 : N 캐릭터(재사용 자산 + 소급 연결)이므로 캐릭터 visibility에 종속시킬 수 없어
 * 자체 상태를 가진다 — {@code SecretReviewStatus}와 동일한 원리의 독립 축.
 * PUBLIC 캐릭터에는 APPROVED 월드만 연결 가능(심사 우회 방지 게이트).
 */
public enum WorldReviewStatus {
    /** 판정 이력 없음 — PRIVATE 캐릭터 전용 사용은 자유 */
    NONE,
    /** 승인 — PUBLIC 캐릭터 연결 허용 */
    APPROVED,
    /** 반려 — reviewNote에 사유. 기존 PUBLIC 캐릭터는 유지, 신규 연결·신규 승인만 차단 */
    REJECTED
}
