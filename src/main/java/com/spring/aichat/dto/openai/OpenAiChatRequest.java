package com.spring.aichat.dto.openai;

import java.util.List;

/**
 * OpenAI 호환 ChatCompletion 요청 DTO
 */
public record OpenAiChatRequest(
    String model,
    List<OpenAiMessage> messages,
    Double temperature
) {}
