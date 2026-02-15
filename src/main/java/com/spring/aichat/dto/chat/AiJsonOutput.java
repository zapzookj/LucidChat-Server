package com.spring.aichat.dto.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM이 뱉어낼 JSON 구조와 매핑될 클래스
 *
 * [Phase 4] Scene에 location, time, outfit, bgmMode 필드 추가
 */
public record AiJsonOutput(
    String reasoning,
    List<Scene> scenes,
    @JsonProperty("affection_change") int affectionChange
) {
    public record Scene(
        String narration,
        String dialogue,
        String emotion,    // EmotionTag (String으로 받아 변환)
        String location,   // [Phase 4] Location enum (null이면 유지)
        String time,       // [Phase 4] TimeOfDay enum (null이면 유지)
        String outfit,     // [Phase 4] Outfit enum (null이면 유지)
        @JsonProperty("bgmMode")
        String bgmMode     // [Phase 4] BgmMode enum (null이면 유지)
    ) {}
}
