package com.spring.aichat.dto.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM이 뱉어낼 JSON 구조와 매핑될 클래스
 *
 * [Phase 4]    Scene에 location, time, outfit, bgmMode 필드
 * [Phase 4.2]  mood_score 필드 (관계 승급 이벤트 중에만 출력)
 * [Phase 4.4]  easter_egg_trigger 필드 (이스터에그 트리거)
 * [Phase 5.5]  stat_changes 필드 (5각 레이더 차트 스탯)
 *              bpm 필드 (심박수)
 */
public record AiJsonOutput(
    String reasoning,
    List<Scene> scenes,
    @JsonProperty("affection_change") int affectionChange,
    @JsonProperty("mood_score") Integer moodScore,
    @JsonProperty("easter_egg_trigger") String easterEggTrigger,
    @JsonProperty("stat_changes") StatChanges statChanges,       // [Phase 5.5]
    Integer bpm                                                   // [Phase 5.5]
) {
    /** 하위 호환: statChanges/bpm 없는 경우 */
    public AiJsonOutput(String reasoning, List<Scene> scenes, int affectionChange,
                        Integer moodScore, String easterEggTrigger) {
        this(reasoning, scenes, affectionChange, moodScore, easterEggTrigger, null, null);
    }

    /** 하위 호환: easterEggTrigger 없는 경우 */
    public AiJsonOutput(String reasoning, List<Scene> scenes, int affectionChange, Integer moodScore) {
        this(reasoning, scenes, affectionChange, moodScore, null, null, null);
    }

    public record Scene(
        String narration,
        String dialogue,
        String emotion,
        String location,
        String time,
        String outfit,
        @JsonProperty("bgmMode")
        String bgmMode
    ) {}

    /**
     * [Phase 5.5] 캐릭터 스탯 변화량
     *
     * 노말 모드: intimacy, affection, dependency, playfulness, trust (각 -3 ~ +3)
     * 시크릿 모드: 위 5개 + lust, corruption, obsession (각 -3 ~ +3)
     *
     * LLM이 대화 내용을 분석하여 각 스탯의 변화량을 출력.
     * null 필드는 0으로 처리.
     */
    public record StatChanges(
        Integer intimacy,
        Integer affection,
        Integer dependency,
        Integer playfulness,
        Integer trust,
        // ── 시크릿 모드 전용 (노말 모드에서는 null) ──
        Integer lust,
        Integer corruption,
        Integer obsession
    ) {
        /** null-safe getter: null이면 0 반환 */
        public int safeIntimacy()     { return intimacy != null ? intimacy : 0; }
        public int safeAffection()    { return affection != null ? affection : 0; }
        public int safeDependency()   { return dependency != null ? dependency : 0; }
        public int safePlayfulness()  { return playfulness != null ? playfulness : 0; }
        public int safeTrust()        { return trust != null ? trust : 0; }
        public int safeLust()         { return lust != null ? lust : 0; }
        public int safeCorruption()   { return corruption != null ? corruption : 0; }
        public int safeObsession()    { return obsession != null ? obsession : 0; }
    }
}