package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiChatResponse;
import com.spring.aichat.exception.ExternalApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
    private final RestClient openRouterRestClient;

    @Autowired
    public OpenRouterClient(ObjectMapper objectMapper, OpenAiProperties properties, RestClient openRouterRestClient) {
        this.openRouterRestClient = openRouterRestClient;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 최대 재시도 횟수 */
    private static final int MAX_RETRIES = 3;

    /** 첫 재시도 전 대기 시간 (ms) — 이후 2배씩 증가 */
    private static final long INITIAL_BACKOFF_MS = 500;

    /** 재시도 대상 HTTP 상태 코드 */
    private static final int[] RETRYABLE_STATUS_CODES = {401, 429, 500, 502, 503, 504};

    public String chatCompletion(OpenAiChatRequest request) {
        RestClientResponseException lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    long backoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1)); // 500, 1000, 2000
                    log.warn("🔄 [RETRY] OpenRouter chatCompletion attempt {}/{} after {}ms | model={}",
                        attempt, MAX_RETRIES, backoff, request.model());
                    Thread.sleep(backoff);
                }

                OpenAiChatResponse response = openRouterRestClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(OpenAiChatResponse.class);

                if (response == null) {
                    throw new ExternalApiException("OpenRouter 응답이 null입니다.");
                }

                if (attempt > 0) {
                    log.info("✅ [RETRY] OpenRouter succeeded on attempt {}", attempt + 1);
                }

                return response.firstContentOrThrow();

            } catch (RestClientResponseException e) {
                lastException = e;
                int statusCode = e.getStatusCode().value();

                if (!isRetryable(statusCode)) {
                    // 재시도 불가능한 에러 (400 Bad Request 등) → 즉시 실패
                    log.error("❌ [RETRY] Non-retryable error {}. body={}",
                        statusCode, e.getResponseBodyAsString());
                    throw new ExternalApiException("OpenRouter 호출 실패 (" + statusCode + "): "
                        + e.getResponseBodyAsString(), e);
                }

                log.warn("⚠️ [RETRY] Retryable error {} on attempt {}/{} | body={}",
                    statusCode, attempt + 1, MAX_RETRIES + 1,
                    e.getResponseBodyAsString().substring(0, Math.min(200, e.getResponseBodyAsString().length())));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExternalApiException("OpenRouter 재시도 중 인터럽트 발생", e);
            }
        }

        // 모든 재시도 소진
        log.error("❌ [RETRY] All {} retries exhausted for model={}", MAX_RETRIES + 1, request.model());
        throw new ExternalApiException(
            "OpenRouter 호출 실패 (재시도 " + (MAX_RETRIES + 1) + "회 모두 실패): "
                + (lastException != null ? lastException.getResponseBodyAsString() : "unknown"),
            lastException
        );
    }

    private boolean isRetryable(int statusCode) {
        for (int code : RETRYABLE_STATUS_CODES) {
            if (code == statusCode) return true;
        }
        return false;
    }

    /** finish_reason=length 재시도 시 예산 상한. */
    private static final int LENGTH_RETRY_MAX_TOKENS = 16384;

    /**
     * 완전한 JSON 응답을 받아 텍스트로 반환. (JSON 파싱은 호출자 책임)
     *
     * <p>[2026-07-28 잘림 대응] 사고(thinking) 모델(Gemini 3.x Pro 등)은 reasoning 토큰이
     * max_tokens 예산에 포함돼 가시 출력이 중간에 잘린다(finish_reason=length → 하류에서
     * "Unexpected end-of-input" 파싱 실패로만 보이던 원인). finish_reason을 검사해
     * length면 예산 2배(상한 {@value #LENGTH_RETRY_MAX_TOKENS})로 1회 자동 재시도한다.
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
        CompletionResult first = completeJsonOnce(model, systemPrompt, userMessage, maxTokens, temperature);
        if (!first.truncated()) {
            return first.content();
        }
        int retryBudget = Math.min(Math.max(maxTokens * 2, maxTokens + 4096), LENGTH_RETRY_MAX_TOKENS);
        log.warn("🤖 [LLM-JSON] finish_reason=length — 응답 잘림 (model={}, max_tokens={}, 사고 모델이면 reasoning이 예산 잠식). "
            + "예산 {}로 1회 재시도", model, maxTokens, retryBudget);
        CompletionResult retry = completeJsonOnce(model, systemPrompt, userMessage, retryBudget, temperature);
        if (retry.truncated()) {
            log.warn("🤖 [LLM-JSON] 재시도에도 finish_reason=length (model={}, max_tokens={}) — 잘린 응답 반환(하류 파싱 실패 예상). "
                + "모델 교체 또는 예산 상향 필요", model, retryBudget);
        }
        return retry.content();
    }

    private record CompletionResult(String content, String finishReason) {
        boolean truncated() {
            return "length".equalsIgnoreCase(finishReason);
        }
    }

    private CompletionResult completeJsonOnce(String model, String systemPrompt, String userMessage,
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

            String finishReason = choices.get(0).path("finish_reason").asText(null);
            return new CompletionResult(content.asText(), finishReason);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("🤖 [LLM-JSON] Request failed: {}", e.getMessage());
            throw new ExternalApiException("LLM 요청 실패: " + e.getMessage(), e);
        }
    }
}