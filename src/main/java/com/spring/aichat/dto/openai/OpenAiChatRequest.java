package com.spring.aichat.dto.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI 호환 ChatCompletion 요청 DTO
 *
 * [Phase 4 Fix] frequency_penalty, presence_penalty 추가
 *   - frequency_penalty: 이미 등장한 토큰의 반복을 억제 (중복 대사 방지)
 *   - presence_penalty: 새로운 주제/표현을 유도 (다양성 향상)
 *   - null이면 JSON 직렬화에서 제외 (기존 동작 유지)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatRequest(
    String model,
    List<OpenAiMessage> messages,
    Double temperature,
    Boolean stream,
    @JsonProperty("frequency_penalty") Double frequencyPenalty,
    @JsonProperty("presence_penalty")  Double presencePenalty
) {
    /** 기본 생성자 — penalty 적용 (일반 대화용) */
    public OpenAiChatRequest(String model, List<OpenAiMessage> messages, Double temperature) {
        this(model, messages, temperature, false, 0.3, 0.15);
    }

    /** stream 지정 생성자 — penalty 적용 */
    public OpenAiChatRequest(String model, List<OpenAiMessage> messages, Double temperature, Boolean stream) {
        this(model, messages, temperature, stream, 0.3, 0.15);
    }

    /** penalty 미적용 팩토리 — 엔딩 타이틀 등 창의적 생성용 */
    public static OpenAiChatRequest withoutPenalty(String model, List<OpenAiMessage> messages, Double temperature) {
        return new OpenAiChatRequest(model, messages, temperature, false, null, null);
    }
}