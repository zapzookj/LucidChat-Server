package com.spring.aichat.dto.openai;

import java.util.List;

/**
 * OpenAI 호환 ChatCompletion 요청 DTO
 */
public record OpenAiChatRequest(
    String model,
    List<OpenAiMessage> messages,
    Double temperature,
    Boolean stream
) {
    // [하위 호환성 유지] 기존 생성자 호출 시 stream = false로 설정
    public OpenAiChatRequest(String model, List<OpenAiMessage> messages, Double temperature) {
        this(model, messages, temperature, false);
    }
}
