package com.spring.aichat.dto.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM이 뱉어낼 JSON 구조와 매핑될 클래스
 *
 * [Phase 4]  Scene에 location, time, outfit, bgmMode 필드
 * [Phase 5]  mood_score 필드 (관계 승급 이벤트 중에만 출력)
 */
public record AiJsonOutput(
    String reasoning,
    List<Scene> scenes,
    @JsonProperty("affection_change") int affectionChange,
    @JsonProperty("mood_score") Integer moodScore   // [Phase 4.2] 승급 이벤트 중 분위기 점수 (-3 ~ +5, null이면 0)
) {
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