package com.spring.aichat.dto.chat;

import com.spring.aichat.domain.enums.EmotionTag;
import java.util.List;

/**
 * [Phase 5.5-NPC] SceneResponse.speaker 필드 추가
 * [Phase 5.5-EV]  topicConcluded, eventStatus 필드
 * [Phase 5.5-Illust] generateIllustration, newBackgroundUrl, locationTransition 필드 추가
 * [Phase 7-V2 Pivot] dialogueOptions 필드 추가 (additive)
 *   - V2 ChatStreamServiceV2.buildSendChatResponseV2가 채움
 *   - V1 호출자는 null 전달 (모든 기존 생성자 갱신됨)
 *   - V1 프론트는 미사용, V2 프론트는 디렉터 선택지 chip 노출용
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
    LocationTransition locationTransition,  // 새 장소 전환 정보 (null이면 전환 없음)

    // ── [Phase 7-V2 Pivot] ──
    List<String> dialogueOptions            // V2 디렉터 선택지 (V1은 null)
) {
    // ── 하위 호환 생성자: 17-param (V1 path, dialogueOptions 미지원) ──
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
                            StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought,
                            boolean hasInnerThought, String assistantLogId,
                            boolean topicConcluded, String eventStatus,
                            boolean generateIllustration, LocationTransition locationTransition) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            stats, bpm, dynamicRelationTag, characterThought, hasInnerThought, assistantLogId,
            topicConcluded, eventStatus, generateIllustration, locationTransition, null);
    }

    // ── 15-param 호환 생성자 (이전 patch 전) ──
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
                            StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought,
                            boolean hasInnerThought, String assistantLogId,
                            boolean topicConcluded, String eventStatus) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            stats, bpm, dynamicRelationTag, characterThought, hasInnerThought, assistantLogId,
            topicConcluded, eventStatus, false, null, null);
    }

    // ── 이전 하위 호환 생성자들 (모두 dialogueOptions=null) ──
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus) {
        this(roomId, scenes, currentAffection, relationStatus, null, null, null, null, 65, null, null, false, null, false, null, false, null, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus, PromotionEvent promotionEvent) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, null, null, null, 65, null, null, false, null, false, null, false, null, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg, null, 65, null, null, false, null, false, null, false, null, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
                            StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            stats, bpm, dynamicRelationTag, characterThought, false, null, false, null, false, null, null);
    }
    public SendChatResponse(Long roomId, List<SceneResponse> scenes, int currentAffection, String relationStatus,
                            PromotionEvent promotionEvent, EndingTrigger endingTrigger, EasterEggEvent easterEgg,
                            StatsSnapshot stats, int bpm, String dynamicRelationTag, String characterThought,
                            boolean hasInnerThought, String assistantLogId) {
        this(roomId, scenes, currentAffection, relationStatus, promotionEvent, endingTrigger, easterEgg,
            stats, bpm, dynamicRelationTag, characterThought, hasInnerThought, assistantLogId, false, null, false, null, null);
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