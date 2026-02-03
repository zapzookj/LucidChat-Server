package com.spring.aichat.dto.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// LLM이 뱉어낼 JSON 구조와 매핑될 클래스
public record AiJsonOutput(
    List<Scene> scenes,
    @JsonProperty("affection_change") int affectionChange
) {
    public record Scene(
        String narration,
        String dialogue,
        String emotion // Enum으로 변환하기 전엔 String으로 받음
    ) {}
}
