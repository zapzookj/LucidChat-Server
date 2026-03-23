package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.RelationStatus;

/**
 * 호감도 점수에 따른 관계 레벨 정책
 *
 * [Phase 5.5]    다중 스탯 기반 동적 관계 판정 시스템
 * [Phase 5.5-EV] 승급 판정 기준 변경:
 *   - mood_score → 5종 스탯 변화량 합산 (totalStatDelta)
 *   - PROMOTION_SUCCESS_THRESHOLD: 매 턴 최소 평균 2의 스탯 변화 필요
 *     (5턴 × 평균 2 = 총 10 이상)
 */
public final class RelationStatusPolicy {

    private RelationStatusPolicy() {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  승급 이벤트 상수
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 승급 이벤트 지속 턴 수 (유저 개입 턴만 카운트) */
    public static final int PROMOTION_MAX_TURNS = 5;

    /**
     * [Phase 5.5-EV] 승급 성공에 필요한 최소 누적 스탯 변화량
     *
     * 기존: mood_score 5점 (1~3점/턴)
     * 변경: 5종 스탯 변화량 |합산| 누적 10 이상
     *       (매 턴 평균 2의 스탯 변화 → 적극적 교감 필요)
     *
     * 예시: 유저가 로맨틱한 대화를 하면 affection +2, intimacy +1 = 3/턴
     *       5턴 × 3 = 15 → 성공
     *       유저가 무성의하면 affection +1 정도 = 1/턴
     *       5턴 × 1 = 5 → 실패
     */
    public static final int PROMOTION_SUCCESS_THRESHOLD = 10;

    /** 승급 실패 시 호감도 감소량 */
    public static final int PROMOTION_FAILURE_PENALTY = 5;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스탯 이름 상수
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static final String STAT_INTIMACY     = "INTIMACY";
    public static final String STAT_AFFECTION    = "AFFECTION";
    public static final String STAT_DEPENDENCY   = "DEPENDENCY";
    public static final String STAT_PLAYFULNESS  = "PLAYFULNESS";
    public static final String STAT_TRUST        = "TRUST";

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

    public static RelationStatus fromStats(int affectionScore,
                                           int intimacy, int affection,
                                           int dependency, int playfulness, int trust) {
        if (affectionScore < 0) return RelationStatus.ENEMY;
        int maxStat = maxOf(intimacy, affection, dependency, playfulness, trust);
        return fromScore(maxStat);
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

    public static String getDisplayName(RelationStatus status) {
        return switch (status) {
            case STRANGER     -> "타인";
            case ACQUAINTANCE -> "지인";
            case FRIEND       -> "친구";
            case LOVER        -> "연인";
            case ENEMY        -> "적";
        };
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  동적 관계 태그 시스템
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static String getDominantStat(int intimacy, int affection,
                                         int dependency, int playfulness, int trust) {
        int max = maxOf(intimacy, affection, dependency, playfulness, trust);
        if (affection == max)    return STAT_AFFECTION;
        if (intimacy == max)     return STAT_INTIMACY;
        if (trust == max)        return STAT_TRUST;
        if (playfulness == max)  return STAT_PLAYFULNESS;
        return STAT_DEPENDENCY;
    }

    public static String buildDynamicRelationTag(RelationStatus level, String dominantStat) {
        return switch (level) {
            case STRANGER -> "낯선 사람";
            case ACQUAINTANCE -> switch (dominantStat) {
                case STAT_INTIMACY    -> "좋은 말동무";
                case STAT_PLAYFULNESS -> "만나면 즐거운 재미있는 사람";
                case STAT_AFFECTION   -> "은근히 의식하게 되는 사람";
                case STAT_DEPENDENCY  -> "자꾸만 기대게 되는 조력자";
                case STAT_TRUST       -> "믿을만한 사람";
                default -> "지인";
            };
            case FRIEND -> switch (dominantStat) {
                case STAT_INTIMACY    -> "친한 친구같은 사람";
                case STAT_PLAYFULNESS -> "티격태격하는 친구";
                case STAT_AFFECTION   -> "썸";
                case STAT_DEPENDENCY  -> "맹목적인 추종자";
                case STAT_TRUST       -> "믿고 의지하는 사람";
                default -> "친구";
            };
            case LOVER -> switch (dominantStat) {
                case STAT_INTIMACY    -> "편안하고 포근한 연인";
                case STAT_AFFECTION   -> "사랑스러운 연인";
                case STAT_DEPENDENCY  -> "당신 없이는 숨 쉴 수 없는 맹목적 반려";
                case STAT_PLAYFULNESS -> "매일이 짜릿하고 유쾌한 단짝 연인";
                case STAT_TRUST       -> "영혼의 밑바닥까지 신뢰하는 연인";
                default -> "연인";
            };
            case ENEMY -> "경계하는 상대";
        };
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  BPM 계산
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static int calculateBaseBpm(int statAffection) {
        return 65 + (int)(statAffection * 0.4);
    }

    private static int maxOf(int a, int b, int c, int d, int e) {
        return Math.max(a, Math.max(b, Math.max(c, Math.max(d, e))));
    }
}