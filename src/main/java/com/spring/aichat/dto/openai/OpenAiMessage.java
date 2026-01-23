package com.spring.aichat.dto.openai;

/**
 * OpenAI 호환 메시지 단위
 */
public record OpenAiMessage(String role, String content) {
    public static OpenAiMessage system(String content) { return new OpenAiMessage("system", content); }
    public static OpenAiMessage user(String content) { return new OpenAiMessage("user", content); }
    public static OpenAiMessage assistant(String content) { return new OpenAiMessage("assistant", content); }
}
