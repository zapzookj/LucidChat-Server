package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.exception.ExternalApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * [Phase 5.5-Theater] OpenRouter 비스트리밍 JSON 응답 클라이언트
 *
 * 기존 OpenRouterStreamClient는 SSE 스트리밍 전용이라,
 * Theater 배치 생성처럼 "한 번의 완성 JSON 응답이 필요한 경우"를 위한 별도 래퍼.
 *
 * [사용처]
 * - Theater Scene Batch 생성 (5~8 Scene JSON 완성 응답)
 * - Chapter 종료 리포트 생성
 * - 감독 노트 자동 캡처 요약
 */
@Slf4j
@Component
public class OpenRouterClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenAiProperties properties;

    @Autowired
    public OpenRouterClient(ObjectMapper objectMapper, OpenAiProperties properties) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 완전한 JSON 응답을 받아 텍스트로 반환. (JSON 파싱은 호출자 책임)
     *
     * @param model        모델명 (OpenRouter 식별자)
     * @param systemPrompt 시스템 프롬프트
     * @param userMessage  유저 메시지 (보통 "Generate now." 같은 트리거)
     * @param maxTokens    응답 최대 토큰
     * @param temperature  샘플링 온도
     * @return LLM 응답 본문 텍스트 (assistant content)
     */
    public String completeJson(String model, String systemPrompt, String userMessage,
                               int maxTokens, double temperature) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("stream", false);

        // response_format = {"type": "json_object"} — JSON 강제
        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_object");
        body.set("response_format", responseFormat);

        ArrayNode messages = objectMapper.createArrayNode();

        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        body.set("messages", messages);

        String url = properties.baseUrl() + "/chat/completions";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("HTTP-Referer", properties.appReferer())
                .header("X-Title", properties.appTitle())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("🤖 [LLM-JSON] HTTP {} | body: {}", response.statusCode(), response.body());
                throw new ExternalApiException("LLM 호출 실패 (HTTP " + response.statusCode() + ")");
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new ExternalApiException("LLM 응답에 choices가 없습니다.");
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new ExternalApiException("LLM 응답에 message.content가 없습니다.");
            }

            return content.asText();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("🤖 [LLM-JSON] Request failed: {}", e.getMessage());
            throw new ExternalApiException("LLM 요청 실패: " + e.getMessage(), e);
        }
    }
}