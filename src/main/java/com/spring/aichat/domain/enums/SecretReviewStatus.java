package com.spring.aichat.domain.enums;

/**
 * [UGC v1] Secret Mode 허용 심사 상태 — 공개 심사와 별개의 독립 경로
 * (2026-07-17 결정: PRIVATE 유지 캐릭터도 Secret만 단독 신청 가능).
 *
 * <p>{@code APPROVED}는 {@code Character.secretEligible=true}와 동기화된다
 * (eligible이 런타임 fast-path, 이 상태는 승인 큐 상태).
 */
public enum SecretReviewStatus {
    /** 신청 이력 없음 */
    NONE,
    /** 심사 대기 — 백오피스 큐 적재 */
    PENDING,
    /** 승인 — secretEligible=true */
    APPROVED,
    /** 반려 — reviewNote에 사유 */
    REJECTED
}
