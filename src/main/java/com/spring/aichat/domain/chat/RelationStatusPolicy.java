package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.RelationStatus;

import java.util.*;

/**
 * 호감도 점수에 따른 관계 레벨 정책
 *
 * [Phase 4.2] 관계 승급 이벤트 시스템
 *   - 승급 임계점 정의
 *   - 관계별 해금 콘텐츠 정의
 *   - 승급 이벤트 상수
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
     * 새 점수가 현재 관계 대비 승급에 해당하는지 판정
     * (강등은 이벤트 없이 즉시 적용)
     */
    public static boolean isUpgrade(RelationStatus current, RelationStatus next) {
        return next.ordinal() > current.ordinal() && next != RelationStatus.ENEMY;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  관계별 해금 콘텐츠 정의
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 해금 정보 DTO
     */
    public record UnlockInfo(String type, String name, String displayName) {}

    /**
     * 특정 관계로 승급할 때 새로 해금되는 콘텐츠 목록
     *
     * STRANGER → ACQUAINTANCE: DOWNTOWN + DATE + PAJAMA
     * ACQUAINTANCE → FRIEND:   BEACH + SWIMWEAR
     * FRIEND → LOVER:          BAR + NEGLIGEE
     */
    public static List<UnlockInfo> getUnlocksForRelation(RelationStatus relation) {
        return switch (relation) {
            case ACQUAINTANCE -> List.of(
                new UnlockInfo("LOCATION", "DOWNTOWN", "번화가"),
                new UnlockInfo("OUTFIT",   "DATE",     "외출복"),
                new UnlockInfo("OUTFIT",   "PAJAMA",   "잠옷")
            );
            case FRIEND -> List.of(
                new UnlockInfo("LOCATION", "BEACH",    "해변"),
                new UnlockInfo("OUTFIT",   "SWIMWEAR", "수영복")
            );
            case LOVER -> List.of(
                new UnlockInfo("LOCATION", "BAR",       "바"),
                new UnlockInfo("OUTFIT",   "NEGLIGEE",  "네글리제")
            );
            default -> List.of();
        };
    }

    /**
     * 현재 관계에서 허용되는 장소 목록 (Secret 모드는 제한 없음)
     */
    public static Set<String> getAllowedLocations(RelationStatus status) {
        Set<String> allowed = new LinkedHashSet<>();
        // STRANGER 이하: 저택 내부만
        allowed.addAll(List.of("LIVINGROOM", "BALCONY", "STUDY", "BATHROOM",
            "GARDEN", "KITCHEN", "BEDROOM", "ENTRANCE"));

        if (status.ordinal() >= RelationStatus.ACQUAINTANCE.ordinal()) {
            allowed.add("DOWNTOWN");
        }
        if (status.ordinal() >= RelationStatus.FRIEND.ordinal()) {
            allowed.add("BEACH");
        }
        if (status.ordinal() >= RelationStatus.LOVER.ordinal()) {
            allowed.add("BAR");
        }
        return allowed;
    }

    /**
     * 현재 관계에서 허용되는 복장 목록 (Secret 모드는 제한 없음)
     */
    public static Set<String> getAllowedOutfits(RelationStatus status) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add("MAID");

        if (status.ordinal() >= RelationStatus.ACQUAINTANCE.ordinal()) {
            allowed.add("DATE");
            allowed.add("PAJAMA");
        }
        if (status.ordinal() >= RelationStatus.FRIEND.ordinal()) {
            allowed.add("SWIMWEAR");
        }
        if (status.ordinal() >= RelationStatus.LOVER.ordinal()) {
            allowed.add("NEGLIGEE");
        }
        return allowed;
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