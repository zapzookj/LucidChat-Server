package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.EmotionTag;

import java.util.List;

/**
 * 채팅 전송 응답 DTO
 *
 * [Phase 4]    SceneResponse에 location, time, outfit, bgmMode
 * [Phase 4.2]  PromotionEvent — 관계 승급 이벤트 정보
 * [Phase 4.3]  EndingTrigger — 엔딩 트리거 감지
 * [Phase 4.4]  EasterEggEvent — 이스터에그 트리거 + 업적 정보
 * [Phase 5.5]  StatsSnapshot — 레이더 차트 스탯
 *              bpm — 심박수
 *              dynamicRelationTag — 동적 관계 태그
 *              characterThought — 캐릭터의 생각 (갱신 시에만 non-null)
 */
public record SendChatResponse(
    Long roomId,
    List<SceneResponse> scenes,
    int currentAffection,
    String relationStatus,
    PromotionEvent promotionEvent,
    EndingTrigger endingTrigger,
    EasterEggEvent easterEgg,
    // ── [Phase 5.5] 입체적 상태창 ──
    StatsSnapshot stats,
    int bpm,
    String dynamicRelationTag,
    String characterThought       // null이면 이번 턴에 갱신 없음
) {
    /** [Phase 5.5] 전체 파라미터 생성자 (메인) — 위에 자동 생성됨 */

    /** 기존 호환용 생성자 (이벤트 없음, 엔딩 없음, 이스터에그 없음, 스탯 없음) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus) {
        this(roomId, scenes, currentAffection, relationStatus, null, null, null, null, 65, null, null);
    }

    /** [Phase 4.2] 호환용 (승급만) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus, PromotionEvent promotionEvent) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, null, null, null, 65, null, null);
    }

    /** [Phase 4.3] 호환용 (승급 + 엔딩) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus, PromotionEvent promotionEvent, EndingTrigger endingTrigger) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, null, null, 65, null, null);
    }

    /** [Phase 4.4] 호환용 (승급 + 엔딩 + 이스터에그, 스탯 없음) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg, null, 65, null, null);
    }

    public record SceneResponse(
        String narration,
        String dialogue,
        EmotionTag emotion,
        String location,
        String time,
        String outfit,
        String bgmMode
    ) {}

    /**
     * [Phase 4.2] 관계 승급 이벤트 정보
     */
    public record PromotionEvent(
        String status,
        String targetRelation,
        String targetDisplayName,
        int turnsRemaining,
        int moodScore,
        List<UnlockInfo> unlocks
    ) {}

    public record UnlockInfo(
        String type,
        String name,
        String displayName
    ) {}

    /** [Phase 4.3] 엔딩 트리거 */
    public record EndingTrigger(
        String endingType
    ) {}

    /**
     * [Phase 4.4] 이스터에그 이벤트
     */
    public record EasterEggEvent(
        String trigger,
        AchievementInfo achievement,
        boolean revertAfter
    ) {}

    public record AchievementInfo(
        String code,
        String title,
        String titleKo,
        String description,
        String icon,
        boolean isNew
    ) {}

    /**
     * [Phase 5.5] 스탯 스냅샷 — 레이더 차트 렌더링용
     *
     * 노말 모드: intimacy~trust만 사용, lust/corruption/obsession은 null
     * 시크릿 모드: 모든 필드 사용
     */
    public record StatsSnapshot(
        int intimacy,
        int affection,
        int dependency,
        int playfulness,
        int trust,
        Integer lust,           // 노말 모드에서는 null
        Integer corruption,     // 노말 모드에서는 null
        Integer obsession       // 노말 모드에서는 null
    ) {}
}