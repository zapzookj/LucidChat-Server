package com.spring.aichat.domain.enums;

/**
 * [Phase 5.5-Theater] THEATER 모드 추가
 *
 * [모드별 에너지 비용 체계]
 * STORY:   2 per user message (유저 능동 플레이)
 * SANDBOX: 1 per user message (유저 능동 플레이, 경량)
 * THEATER: 1 per scene batch (자동 진행, 배치당 과금)
 *
 * [부스트 모드 비용 (Pro 모델 사용)]
 * 비구독자: 기본의 5배 — 체험용 미끼
 * 구독자:   기본과 동일 — 구독 핵심 혜택
 *
 * [Theater 에너지 모델]
 * - 자동 진행 Scene: 배치당 1 에너지 (Scene 5~8개 묶음)
 * - 난입(Intervention) 시: STORY와 동일 비용 적용 — 유저 능동 플레이 전환
 * - 인터미션 피로도 추가 선택: 2 에너지
 * - 분기 선택: 분기 레벨별 차등 (MINOR=0, MAJOR=1, CLIMAX=2)
 */
public enum ChatMode {
    STORY,
    SANDBOX,
    /** [Phase 5.5-Theater] 비주얼 노벨 감상 모드 — 유저가 감독/관객이 되는 축 */
    THEATER;

    /** 기본 에너지 비용 (부스트 OFF) */
    public int getBaseCost() {
        return switch (this) {
            case STORY -> 2;
            case SANDBOX -> 1;
            case THEATER -> 1; // 배치당 과금
        };
    }

    /**
     * 실제 에너지 비용 계산
     *
     * @param boostMode     부스트 모드 활성 여부
     * @param isSubscriber  구독자 여부
     * @return 메시지/배치당 에너지 소모량
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
        return switch (this) {
            case STORY -> "스토리 모드";
            case SANDBOX -> "자유 모드";
            case THEATER -> "극장 모드";
        };
    }

    /**
     * [Phase 5.5-Theater] 유저 능동 플레이 모드 여부
     * Dialogue(STORY+SANDBOX) 그룹에 속하면 true
     */
    public boolean isDialogueMode() {
        return this == STORY || this == SANDBOX;
    }

    /**
     * [Phase 5.5-Theater] 감상 모드 여부
     */
    public boolean isTheaterMode() {
        return this == THEATER;
    }
}