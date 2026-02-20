package com.spring.aichat.dto.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM이 뱉어낼 JSON 구조와 매핑될 클래스
 *
 * [Phase 4]    Scene에 location, time, outfit, bgmMode 필드
 * [Phase 4.2]  mood_score 필드 (관계 승급 이벤트 중에만 출력)
 * [Phase 4.4]  easter_egg_trigger 필드 (이스터에그 트리거)
 */
public record AiJsonOutput(
    String reasoning,
    List<Scene> scenes,
    @JsonProperty("affection_change") int affectionChange,
    @JsonProperty("mood_score") Integer moodScore,
    @JsonProperty("easter_egg_trigger") String easterEggTrigger   // [Phase 4.4] STOCKHOLM | DRUNK | FOURTH_WALL | MACHINE_REBELLION | null
) {
    /** 하위 호환: easterEggTrigger 없는 경우 null */
    public AiJsonOutput(String reasoning, List<Scene> scenes, int affectionChange, Integer moodScore) {
        this(reasoning, scenes, affectionChange, moodScore, null);
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
}