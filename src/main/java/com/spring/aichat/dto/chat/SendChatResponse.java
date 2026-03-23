package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.EmotionTag;

import java.util.List;

/**
 * 채팅 전송 응답 DTO
 *
 * [Phase 4]      SceneResponse에 location, time, outfit, bgmMode
 * [Phase 4.2]    PromotionEvent — 관계 승급 이벤트 정보
 * [Phase 4.3]    EndingTrigger — 엔딩 트리거 감지
 * [Phase 4.4]    EasterEggEvent — 이스터에그 트리거 + 업적 정보
 * [Phase 5.5]    StatsSnapshot, bpm, dynamicRelationTag, characterThought
 * [Phase 5.5-IT] hasInnerThought, assistantLogId
 * [Phase 5.5-EV] topicConcluded — 주제 종료 플래그 (이벤트 트리거/시간넘기기 활성화)
 *                eventStatus — 디렉터 모드 이벤트 진행 상태 ("ONGOING" | "RESOLVED" | null)
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
    String characterThought,
    // ── [Phase 5.5-IT] 속마음 시스템 ──
    boolean hasInnerThought,
    String assistantLogId,
    // ── [Phase 5.5-EV] 이벤트 시스템 강화 ──
    boolean topicConcluded,        // true면 이벤트 트리거/시간넘기기 활성화
    String eventStatus             // "ONGOING" | "RESOLVED" | null
) {
    /** 기존 호환용 생성자 (이벤트 없음, 엔딩 없음, 이스터에그 없음, 스탯 없음) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus) {
        this(roomId, scenes, currentAffection, relationStatus, null, null, null,
            null, 65, null, null, false, null, false, null);
    }

    /** [Phase 4.2] 호환용 (승급만) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection,
                            String relationStatus, PromotionEvent promotionEvent) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, null, null,
            null, 65, null, null, false, null, false, null);
    }

    /** [Phase 4.4] 호환용 (승급 + 엔딩 + 이스터에그) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            null, 65, null, null, false, null, false, null);
    }

    /** [Phase 5.5] 호환용 (스탯 있음, 속마음/이벤트 없음) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
                            StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            stats, bpm, dynamicRelationTag, characterThought, false, null, false, null);
    }

    /** [Phase 5.5-IT] 호환용 (속마음 있음, 이벤트 없음) */
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
                            StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought,
                            boolean hasInnerThought, String assistantLogId) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            stats, bpm, dynamicRelationTag, characterThought, hasInnerThought, assistantLogId, false, null);
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

    public record PromotionEvent(
        String status,
        String targetRelation,
        String targetDisplayName,
        int turnsRemaining,
        int moodScore,          // [Phase 5.5-EV] 이제 5종 스탯 변화량 합산값
        List<UnlockInfo> unlocks
    ) {}

    public record UnlockInfo(String type, String name, String displayName) {}

    public record EndingTrigger(String endingType) {}

    public record EasterEggEvent(
        String trigger,
        AchievementInfo achievement,
        boolean revertAfter
    ) {}

    public record AchievementInfo(
        String code, String title, String titleKo,
        String description, String icon, boolean isNew
    ) {}

    public record StatsSnapshot(
        int intimacy, int affection, int dependency,
        int playfulness, int trust,
        Integer lust, Integer corruption, Integer obsession
    ) {}
}