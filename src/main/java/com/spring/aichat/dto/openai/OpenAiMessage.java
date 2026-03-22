package com.spring.aichat.dto.openai;

import java.util.Map;

/**
 * OpenAI 호환 메시지 단위
 */
public record OpenAiMessage(String role, String content, Map<String, Object> cache_control) {
    public static OpenAiMessage system(String content) { return new OpenAiMessage("system", content, null); }
    public static OpenAiMessage systemCached(String content, Map<String, Object> cacheControl) { return new OpenAiMessage("system", content, cacheControl); }
    public static OpenAiMessage user(String content) { return new OpenAiMessage("user", content,null); }
    public static OpenAiMessage assistant(String content) { return new OpenAiMessage("assistant", content, null); }
}
