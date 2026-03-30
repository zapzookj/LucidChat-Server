package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.EmotionTag;
import java.util.List;

/**
 * [Phase 5.5-NPC] SceneResponse.speaker 필드 추가
 * [Phase 5.5-EV]  topicConcluded, eventStatus 필드
 * [Phase 5.5-Illust] generateIllustration, newBackgroundUrl, locationTransition 필드 추가
 */
public record SendChatResponse(
    Long roomId, List<SceneResponse> scenes,
    int currentAffection, String relationStatus,
    PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
    StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought,
    boolean hasInnerThought, String assistantLogId,
    boolean topicConcluded, String eventStatus,

    // ── [Phase 5.5-Illust] ──
    boolean generateIllustration,           // LLM이 일러스트 생성을 제안
    LocationTransition locationTransition   // 새 장소 전환 정보 (null이면 전환 없음)
) {
    // ── 하위 호환 생성자: 기존 15-param ──
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
                            StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought,
                            boolean hasInnerThought, String assistantLogId,
                            boolean topicConcluded, String eventStatus) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            stats, bpm, dynamicRelationTag, characterThought, hasInnerThought, assistantLogId,
            topicConcluded, eventStatus, false, null);
    }

    // ── 이전 하위 호환 생성자들 ──
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus) {
        this(roomId, scenes, currentAffection, relationStatus, null, null, null, null, 65, null, null, false, null, false, null, false, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus, PromotionEvent promotionEvent) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, null, null, null, 65, null, null, false, null, false, null, false, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg, null, 65, null, null, false, null, false, null, false, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
                            StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            stats, bpm, dynamicRelationTag, characterThought, false, null, false, null, false, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
                            StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought,
                            boolean hasInnerThought, String assistantLogId) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            stats, bpm, dynamicRelationTag, characterThought, hasInnerThought, assistantLogId, false, null, false, null);
    }

    public record SceneResponse(
        String speaker,
        String narration, String dialogue, EmotionTag emotion,
        String location, String time, String outfit, String bgmMode
    ) {
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

    /**
     * [Phase 5.5-Illust] 장소 전환 정보
     *
     * @param isNewLocation  새로운(미방문) 장소인지
     * @param locationName   장소 이름 (LLM 제공)
     * @param backgroundUrl  배경 이미지 URL (캐시 히트 시 즉시 포함, 미스 시 null → 프론트에서 폴링)
     * @param cacheHash      캐시 미스 시 폴링용 해시 키
     * @param isGenerating   배경 생성 진행 중 여부
     */
    public record LocationTransition(
        boolean isNewLocation,
        String locationName,
        String backgroundUrl,
        String cacheHash,
        boolean isGenerating
    ) {
        public static LocationTransition cached(String locationName, String backgroundUrl) {
            return new LocationTransition(true, locationName, backgroundUrl, null, false);
        }
        public static LocationTransition generating(String locationName, String cacheHash) {
            return new LocationTransition(true, locationName, null, cacheHash, true);
        }
    }
}