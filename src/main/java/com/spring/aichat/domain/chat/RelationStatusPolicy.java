package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.RelationStatus;

/**
 * 호감도 점수에 따른 관계 레벨 정책
 *
 * [Phase 5]     관계 승급 이벤트 시스템
 * [Phase 4 Fix] 복장/장소 해금 로직을 Character 엔티티로 이관
 * [Phase 5.5]   다중 스탯 기반 동적 관계 판정 시스템
 *               5개 노말 스탯 중 최대값으로 statusLevel 결정
 *               최고 스탯 + 관계 레벨 조합으로 dynamicRelationTag 생성
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
    //  [Phase 5.5] 스탯 이름 상수
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

    /**
     * [Phase 5.5] 5개 노말 스탯의 최대값으로 관계 레벨 판정
     *
     * affectionScore가 음수이면 ENEMY 우선 적용.
     * 그 외에는 5개 스탯 중 최대값의 임계치로 판정.
     */
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

    /**
     * 관계의 한국어 표시명 (기본, 동적 태그 없을 때 폴백)
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5] 동적 관계 태그 시스템
    //
    //  관계 레벨 + 최고 스탯 조합으로 입체적 관계 표현
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 5개 노말 스탯 중 가장 높은 스탯의 이름을 반환
     *
     * 동점일 경우 우선순위: AFFECTION > INTIMACY > TRUST > PLAYFULNESS > DEPENDENCY
     * (게임적으로 로맨스/친밀 경로가 우선하도록)
     */
    public static String getDominantStat(int intimacy, int affection,
                                         int dependency, int playfulness, int trust) {
        int max = maxOf(intimacy, affection, dependency, playfulness, trust);

        // 동점 시 우선순위 반영
        if (affection == max)    return STAT_AFFECTION;
        if (intimacy == max)     return STAT_INTIMACY;
        if (trust == max)        return STAT_TRUST;
        if (playfulness == max)  return STAT_PLAYFULNESS;
        return STAT_DEPENDENCY;
    }

    /**
     * 동적 관계 태그 생성
     *
     * @param level        현재 관계 레벨
     * @param dominantStat 최고 스탯 이름 (getDominantStat 결과)
     * @return 동적 관계 태그 (한국어)
     */
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
    //  [Phase 5.5] BPM 계산
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 호감도(Affection) 스탯 기반 기저 BPM 계산 */
    public static int calculateBaseBpm(int statAffection) {
        // 기저 BPM: 65 (평상시) ~ 105 (affection=100)
        return 65 + (int)(statAffection * 0.4);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Private helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static int maxOf(int a, int b, int c, int d, int e) {
        return Math.max(a, Math.max(b, Math.max(c, Math.max(d, e))));
    }
}