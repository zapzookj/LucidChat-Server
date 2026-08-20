package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.RelationStatus;

/**
 * 호감도 점수에 따른 관계 레벨 정책
 *
 * [Phase 5.5]  다중 스탯 기반 동적 관계 판정 시스템
 * [블록 D · §G-1] 승급 '시험'(5턴 · mood_score · 실패 강등) 폐지 — 임계 도달 시 즉시 승급.
 *   관련 상수 3종(PROMOTION_MAX_TURNS / SUCCESS_THRESHOLD / FAILURE_PENALTY) 제거됨.
 */
public final class RelationStatusPolicy {

    private RelationStatusPolicy() {}

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

    /**
     * 관계 단계가 앞으로 나아갔는가.
     *
     * <p>[블록 D · §G-1 / docs/13 E-4.1] ordinal 비교를 임계 점수 비교로 교체했다.
     * {@code RelationStatus}의 선언 순서가 {@code …LOVER(3), ENEMY(4)}라 ENEMY가 맨 뒤였고,
     * 그 결과 {@code isUpgrade(ENEMY, LOVER) = (3 > 4) = false} — <b>ENEMY에서 어디로 회복해도
     * '승급 아님'</b>이었다. 임계 점수(ENEMY=-1)로 비교하면 회복이 정상 인식된다.
     */
    public static boolean isUpgrade(RelationStatus current, RelationStatus next) {
        return rank(next) > rank(current);
    }

    /**
     * ENEMY에서 벗어나는 '회복' 전이인가.
     *
     * <p>종원 확정(2026-08-20): 회복은 단계만 조용히 복원하고 <b>세리머니를 띄우지 않는다.</b>
     * 적대에서 빠져나오는 것은 새 관계 단계의 획득이 아니라 원상복귀이기 때문이다.
     */
    public static boolean isEnemyRecovery(RelationStatus current, RelationStatus next) {
        return current == RelationStatus.ENEMY && next != RelationStatus.ENEMY;
    }

    /** 서열 — 선언 ordinal이 아니라 관계 진전 순서. ENEMY는 STRANGER보다 뒤다. */
    private static int rank(RelationStatus s) {
        return switch (s) {
            case ENEMY        -> -1;
            case STRANGER     -> 0;
            case ACQUAINTANCE -> 1;
            case FRIEND       -> 2;
            case LOVER        -> 3;
        };
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

    private static int maxOf(int a, int b, int c, int d, int e) {
        return Math.max(a, Math.max(b, Math.max(c, Math.max(d, e))));
    }
}