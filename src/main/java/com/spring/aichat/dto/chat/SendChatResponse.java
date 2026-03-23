package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.EmotionTag;
import java.util.List;

/**
 * [Phase 5.5-NPC] SceneResponse.speaker 필드 추가
 * [Phase 5.5-EV]  topicConcluded, eventStatus 필드
 */
public record SendChatResponse(
    Long roomId, List<SceneResponse> scenes,
    int currentAffection, String relationStatus,
    PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
    StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought,
    boolean hasInnerThought, String assistantLogId,
    boolean topicConcluded, String eventStatus
) {
    // ── 하위 호환 생성자 체인 ──
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus) {
        this(roomId, scenes, currentAffection, relationStatus, null, null, null, null, 65, null, null, false, null, false, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus, PromotionEvent promotionEvent) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, null, null, null, 65, null, null, false, null, false, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg, null, 65, null, null, false, null, false, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
                            StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            stats, bpm, dynamicRelationTag, characterThought, false, null, false, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
                            StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought,
                            boolean hasInnerThought, String assistantLogId) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            stats, bpm, dynamicRelationTag, characterThought, hasInnerThought, assistantLogId, false, null);
    }

    /**
     * [Phase 5.5-NPC] speaker 필드 추가
     * - null: 메인 캐릭터
     * - 캐릭터 이름: 메인 캐릭터 (명시적)
     * - 다른 이름: 제3자 조연/엑스트라
     */
    public record SceneResponse(
        String speaker,       // [Phase 5.5-NPC] 화자 이름 (null → 메인 캐릭터)
        String narration, String dialogue, EmotionTag emotion,
        String location, String time, String outfit, String bgmMode
    ) {
        /** 하위 호환: speaker 없는 SceneResponse */
        public SceneResponse(String narration, String dialogue, EmotionTag emotion,
                             String location, String time, String outfit, String bgmMode) {
            this(null, narration, dialogue, emotion, location, time, outfit, bgmMode);
        }
    }

    public record PromotionEvent(String status, String targetRelation, String targetDisplayName,
                                 int turnsRemaining, int moodScore, List<UnlockInfo> unlocks) {}
    public record UnlockInfo(String type, String name, String displayName) {}
    public record EndingTrigger(String endingType) {}
    public record EasterEggEvent(String trigger, AchievementInfo achievement, boolean revertAfter) {}
    public record AchievementInfo(String code, String title, String titleKo, String description, String icon, boolean isNew) {}
    public record StatsSnapshot(int intimacy, int affection, int dependency, int playfulness, int trust,
                                Integer lust, Integer corruption, Integer obsession) {}
}