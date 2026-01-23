package com.spring.aichat.dto.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OpenAI 호환 ChatCompletion 응답 DTO
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatResponse(
    List<Choice> choices
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {}

    public String firstContentOrThrow() {
        if (choices == null || choices.isEmpty() || choices.get(0).message() == null) {
            throw new IllegalStateException("OpenRouter 응답 choices가 비어있습니다.");
        }
        return choices.get(0).message().content();
    }
}
