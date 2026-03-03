package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.RelationStatus;

/**
 * 호감도 점수에 따른 관계 레벨 정책
 *
 * [Phase 5]     관계 승급 이벤트 시스템
 * [Phase 4 Fix] 복장/장소 해금 로직을 Character 엔티티로 이관
 *               (캐릭터별 독립 세계관 지원)
 *
 * 이 클래스는 순수 정책(임계점, 표시명, 승급 상수)만 담당.
 * 복장/장소 해금 → Character.getAllowedOutfits(), Character.getAllowedLocations()
 */
public final class RelationStatusPolicy {

    private RelationStatusPolicy() {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  승급 이벤트 상수
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 승급 이벤트 지속 턴 수 */
    public static final int PROMOTION_MAX_TURNS = 5;

    /** 승급 성공에 필요한 최소 누적 mood_score */
    public static final int PROMOTION_SUCCESS_THRESHOLD = 5;

    /** 승급 실패 시 호감도 감소량 (임계점 아래로 확실히 떨어뜨림) */
    public static final int PROMOTION_FAILURE_PENALTY = 5;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  관계 레벨 판정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static RelationStatus fromScore(int score) {
        if (score < 0) return RelationStatus.ENEMY;
        if (score <= 20) return RelationStatus.STRANGER;
        if (score < 40) return RelationStatus.ACQUAINTANCE;
        if (score < 80) return RelationStatus.FRIEND;
        return RelationStatus.LOVER;
    }

    public static int getThresholdScore(RelationStatus status) {
        return switch (status) {
            case ENEMY        -> -100;
            case STRANGER     -> 0;
            case ACQUAINTANCE -> 21;
            case FRIEND       -> 40;
            case LOVER        -> 80;
        };
    }

    public static boolean isUpgrade(RelationStatus current, RelationStatus next) {
        return next.ordinal() > current.ordinal() && next != RelationStatus.ENEMY;
    }

    /**
     * 관계의 한국어 표시명
     */
    public static String getDisplayName(RelationStatus status) {
        return switch (status) {
            case STRANGER     -> "타인";
            case ACQUAINTANCE -> "지인";
            case FRIEND       -> "친구";
            case LOVER        -> "연인";
            case ENEMY        -> "적";
        };
    }
}