package com.spring.aichat.dto.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * [Phase 5.5-NPC] Scene.speaker 필드 추가
 * [Phase 5.5-EV]  topic_concluded, event_status 필드
 */
public record AiJsonOutput(
    String reasoning, List<Scene> scenes,
    @JsonProperty("affection_change") int affectionChange,
    @JsonProperty("mood_score") Integer moodScore,
    @JsonProperty("easter_egg_trigger") String easterEggTrigger,
    @JsonProperty("stat_changes") StatChanges statChanges,
    Integer bpm,
    @JsonProperty("inner_thought") String innerThought,
    @JsonProperty("topic_concluded") Boolean topicConcluded,
    @JsonProperty("event_status") String eventStatus
) {
    public AiJsonOutput(String reasoning, List<Scene> scenes, int affectionChange,
                        Integer moodScore, String easterEggTrigger,
                        StatChanges statChanges, Integer bpm, String innerThought) {
        this(reasoning, scenes, affectionChange, moodScore, easterEggTrigger, statChanges, bpm, innerThought, null, null);
    }
    public AiJsonOutput(String reasoning, List<Scene> scenes, int affectionChange,
                        Integer moodScore, String easterEggTrigger, StatChanges statChanges, Integer bpm) {
        this(reasoning, scenes, affectionChange, moodScore, easterEggTrigger, statChanges, bpm, null, null, null);
    }
    public AiJsonOutput(String reasoning, List<Scene> scenes, int affectionChange,
                        Integer moodScore, String easterEggTrigger) {
        this(reasoning, scenes, affectionChange, moodScore, easterEggTrigger, null, null, null, null, null);
    }
    public AiJsonOutput(String reasoning, List<Scene> scenes, int affectionChange, Integer moodScore) {
        this(reasoning, scenes, affectionChange, moodScore, null, null, null, null, null, null);
    }

    public boolean isTopicConcluded() { return Boolean.TRUE.equals(topicConcluded); }
    public boolean isEventOngoing()   { return "ONGOING".equalsIgnoreCase(eventStatus); }
    public boolean isEventResolved()  { return "RESOLVED".equalsIgnoreCase(eventStatus); }

    /**
     * [Phase 5.5-NPC] speaker 필드가 있는 Scene
     * - null/캐릭터이름: 메인 캐릭터 발화
     * - 다른 이름: 제3자(조연) 발화
     */
    public record Scene(
        String speaker,       // [Phase 5.5-NPC] nullable → 메인 캐릭터
        String narration, String dialogue, String emotion,
        String location, String time, String outfit,
        @JsonProperty("bgmMode") String bgmMode
    ) {
        /** 하위 호환: speaker 없는 Scene (Jackson은 추가 필드 무시) */
        public Scene(String narration, String dialogue, String emotion,
                     String location, String time, String outfit, String bgmMode) {
            this(null, narration, dialogue, emotion, location, time, outfit, bgmMode);
        }
    }

    public record StatChanges(
        Integer intimacy, Integer affection, Integer dependency,
        Integer playfulness, Integer trust,
        Integer lust, Integer corruption, Integer obsession
    ) {
        public int safeIntimacy()    { return intimacy != null ? intimacy : 0; }
        public int safeAffection()   { return affection != null ? affection : 0; }
        public int safeDependency()  { return dependency != null ? dependency : 0; }
        public int safePlayfulness() { return playfulness != null ? playfulness : 0; }
        public int safeTrust()       { return trust != null ? trust : 0; }
        public int safeLust()        { return lust != null ? lust : 0; }
        public int safeCorruption()  { return corruption != null ? corruption : 0; }
        public int safeObsession()   { return obsession != null ? obsession : 0; }
        public int totalNormalStatDelta() {
            return Math.abs(safeIntimacy()) + Math.abs(safeAffection())
                + Math.abs(safeDependency()) + Math.abs(safePlayfulness()) + Math.abs(safeTrust());
        }
    }
}