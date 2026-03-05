package com.spring.aichat.domain.enums;

/**
 * Phase 5 BM: 에너지 부스트 모드 비용 체계
 *
 * [기본 비용]
 * STORY:   2 per message
 * SANDBOX: 1 per message
 *
 * [부스트 모드 비용 (Pro 모델 사용)]
 * 비구독자: 기본의 5배 (SANDBOX=5, STORY=10) — 체험용 미끼
 * 구독자:   기본과 동일 (SANDBOX=1, STORY=2)  — 구독 핵심 혜택
 */
public enum ChatMode {
    STORY,
    SANDBOX;

    /** 기본 에너지 비용 (부스트 OFF) */
    public int getBaseCost() {
        return this == STORY ? 2 : 1;
    }

    /**
     * 실제 에너지 비용 계산
     *
     * @param boostMode     부스트 모드 활성 여부
     * @param isSubscriber  구독자 여부
     * @return 메시지당 에너지 소모량
     */
    public int getEnergyCost(boolean boostMode, boolean isSubscriber) {
        int base = getBaseCost();

        if (!boostMode) {
            return base;
        }

        // 부스트 모드 ON
        if (isSubscriber) {
            return base; // 구독자: 일반 비용으로 Pro 모델
        } else {
            return base * 5; // 비구독자: 5배 비용
        }
    }

    /** 하위 호환용 (부스트/구독 미적용 기본 비용) */
    public int getEnergyCost() {
        return getBaseCost();
    }

    public String getDisplayName() {
        return this == STORY ? "스토리 모드" : "자유 모드";
    }
}