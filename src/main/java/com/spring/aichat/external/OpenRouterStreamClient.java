package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.exception.ExternalApiException;
import com.spring.aichat.service.stream.SceneStreamExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * [Phase 5.5-Perf] OpenRouter 스트리밍 호출 클라이언트
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Dual-Streaming Architecture의 첫 번째 구간:
 *  OpenRouter(LLM) → Spring Boot (토큰 스트림)
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * [역할]
 *   1. OpenRouter에 stream=true 요청 전송
 *   2. SSE 응답(data: {...})을 라인별로 파싱
 *   3. delta.content 토큰을 StringBuilder에 누적
 *   4. SceneStreamExtractor를 통해 첫 번째 씬 완성 시점 포착
 *   5. 콜백(onFirstScene)으로 첫 번째 씬 JSON 즉시 전달
 *   6. 전체 스트림 완료 후 전체 응답 문자열 반환
 *
 * [OpenRouter SSE 포맷]
 *   data: {"id":"gen-...","choices":[{"index":0,"delta":{"content":"토큰"},"finish_reason":null}]}
 *   data: {"id":"gen-...","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
 *   data: [DONE]
 *
 * [Java HttpClient 선택 근거]
 *   - Spring WebFlux(WebClient) 불필요 — 이 호출은 @Async 스레드에서 블로킹으로 실행
 *   - Java 17 내장 HttpClient는 HTTP/2, 스트리밍을 네이티브 지원
 *   - 외부 의존성 제로 (webflux 라이브러리 추가 불필요)
 */
@Component
@Slf4j
public class OpenRouterStreamClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String appReferer;
    private final String appTitle;

    public OpenRouterStreamClient(OpenAiProperties props, ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = objectMapper;
        this.baseUrl = props.baseUrl();
        this.apiKey = props.apiKey();
        this.appReferer = props.appReferer();
        this.appTitle = props.appTitle();
    }

    /**
     * 스트리밍 결과 DTO
     *
     * @param fullResponse   전체 LLM 응답 텍스트
     * @param firstSceneJson 첫 번째 씬 JSON (추출 실패 시 null)
     * @param ttft           Time To First Token (ms)
     * @param ttfs           Time To First Scene (ms)
     */
    public record StreamResult(
        String fullResponse,
        String firstSceneJson,
        long ttft,
        long ttfs
    ) {}

    /**
     * OpenRouter 스트리밍 호출 + 첫 번째 씬 추출
     *
     * @param request       ChatCompletion 요청 (stream=true가 강제 적용됨)
     * @param onFirstScene  첫 번째 씬 JSON이 완성되는 즉시 호출되는 콜백
     * @return StreamResult (전체 응답 + 첫 번째 씬)
     */
    public StreamResult streamCompletion(OpenAiChatRequest request, Consumer<String> onFirstScene) {
        long startTime = System.currentTimeMillis();

        // stream=true 강제 적용
        OpenAiChatRequest streamRequest = new OpenAiChatRequest(
            request.model(), request.messages(), request.temperature(),
            true, request.frequencyPenalty(), request.presencePenalty(), request.provider()
        );

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(streamRequest);
        } catch (Exception e) {
            throw new ExternalApiException("스트리밍 요청 직렬화 실패", e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("HTTP-Referer", appReferer)
            .header("X-Title", appTitle)
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

        StringBuilder fullBuffer = new StringBuilder(4096);
        SceneStreamExtractor extractor = new SceneStreamExtractor();
        long ttft = -1;  // Time To First Token
        long ttfs = -1;  // Time To First Scene

        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(
                httpRequest, HttpResponse.BodyHandlers.ofInputStream()
            );

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes());
                log.error("❌ [STREAM] OpenRouter returned {}: {}", response.statusCode(),
                    errorBody.substring(0, Math.min(300, errorBody.length())));
                throw new ExternalApiException(
                    "OpenRouter 스트리밍 실패 (" + response.statusCode() + "): " + errorBody);
            }

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    // SSE 포맷: "data: {...}" 또는 "data: [DONE]" 또는 빈 줄
                    if (!line.startsWith("data: ")) continue;

                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    if (data.isEmpty()) continue;

                    // delta.content 추출
                    String token = extractDeltaContent(data);
                    if (token == null || token.isEmpty()) continue;

                    // TTFT 기록
                    if (ttft < 0) {
                        ttft = System.currentTimeMillis() - startTime;
                        log.info("⏱️ [STREAM] TTFT: {}ms | model={}", ttft, request.model());
                    }

                    fullBuffer.append(token);

                    // 첫 번째 씬 추출 시도
                    if (!extractor.isFirstSceneExtracted()) {
                        String firstScene = extractor.tryExtractFirstScene(fullBuffer.toString());
                        if (firstScene != null) {
                            ttfs = System.currentTimeMillis() - startTime;
                            log.info("🎬 [STREAM] First scene extracted: {}ms | chars={}",
                                ttfs, firstScene.length());

                            // 콜백 발사 — 프론트엔드로 첫 번째 씬 전송
                            if (onFirstScene != null) {
                                try {
                                    onFirstScene.accept(firstScene);
                                } catch (Exception cbErr) {
                                    log.warn("⚠️ [STREAM] onFirstScene callback failed", cbErr);
                                }
                            }
                        }
                    }
                }
            }

            long totalTime = System.currentTimeMillis() - startTime;
            log.info("⏱️ [STREAM] Complete: {}ms | ttft={}ms | ttfs={}ms | chars={} | model={}",
                totalTime, ttft, ttfs, fullBuffer.length(), request.model());

            return new StreamResult(
                fullBuffer.toString(),
                extractor.isFirstSceneExtracted() ? null : null, // firstScene은 이미 콜백으로 전달됨
                ttft,
                ttfs
            );

        } catch (ExternalApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("❌ [STREAM] Streaming failed after {}ms", System.currentTimeMillis() - startTime, e);
            throw new ExternalApiException("OpenRouter 스트리밍 중 오류: " + e.getMessage(), e);
        }
    }

    /**
     * OpenAI SSE chunk에서 delta.content 추출
     *
     * 입력: {"id":"...","choices":[{"index":0,"delta":{"content":"토큰"},"finish_reason":null}]}
     * 출력: "토큰"
     */
    private String extractDeltaContent(String jsonChunk) {
        try {
            JsonNode root = objectMapper.readTree(jsonChunk);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) return null;

            JsonNode delta = choices.get(0).get("delta");
            if (delta == null) return null;

            JsonNode content = delta.get("content");
            if (content == null || content.isNull()) return null;

            return content.asText();
        } catch (Exception e) {
            // 파싱 실패 라인은 무시 (정상적인 SSE에서 발생하지 않음)
            return null;
        }
    }
}