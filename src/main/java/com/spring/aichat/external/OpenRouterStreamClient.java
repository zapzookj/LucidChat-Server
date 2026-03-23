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
 * [Phase 5.5-EV Fix 2] event_status 선행 추출 콜백 추가
 *   - onEventStatus: event_status가 추출되는 즉시 호출 (first_scene보다 먼저)
 *   - 기존 onFirstScene 콜백은 그대로 유지
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

    public record StreamResult(
        String fullResponse,
        String firstSceneJson,
        long ttft,
        long ttfs
    ) {}

    /**
     * 스트리밍 호출 (기존 시그니처 유지 — 하위 호환)
     */
    public StreamResult streamCompletion(OpenAiChatRequest request, Consumer<String> onFirstScene) {
        return streamCompletion(request, onFirstScene, null);
    }

    /**
     * [Fix 2] 스트리밍 호출 + event_status 선행 추출
     *
     * @param request        ChatCompletion 요청
     * @param onFirstScene   첫 번째 씬 JSON 완성 시 콜백
     * @param onEventStatus  event_status 추출 시 콜백 (first_scene보다 먼저 발화)
     */
    public StreamResult streamCompletion(OpenAiChatRequest request,
                                         Consumer<String> onFirstScene,
                                         Consumer<String> onEventStatus) {
        long startTime = System.currentTimeMillis();

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
        long ttft = -1;
        long ttfs = -1;

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
                    if (!line.startsWith("data: ")) continue;

                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    if (data.isEmpty()) continue;

                    String token = extractDeltaContent(data);
                    if (token == null || token.isEmpty()) continue;

                    if (ttft < 0) {
                        ttft = System.currentTimeMillis() - startTime;
                        log.info("⏱️ [STREAM] TTFT: {}ms | model={}", ttft, request.model());
                    }

                    fullBuffer.append(token);

                    String bufferStr = fullBuffer.toString();

                    // [Fix 2] event_status 선행 추출 (first_scene보다 먼저)
                    if (!extractor.isEventStatusExtracted() && onEventStatus != null) {
                        String eventStatus = extractor.tryExtractEventStatus(bufferStr);
                        if (eventStatus != null) {
                            try {
                                onEventStatus.accept(eventStatus);
                            } catch (Exception cbErr) {
                                log.warn("⚠️ [STREAM] onEventStatus callback failed", cbErr);
                            }
                        }
                    }

                    // 첫 번째 씬 추출
                    if (!extractor.isFirstSceneExtracted()) {
                        String firstScene = extractor.tryExtractFirstScene(bufferStr);
                        if (firstScene != null) {
                            ttfs = System.currentTimeMillis() - startTime;
                            log.info("🎬 [STREAM] First scene extracted: {}ms | chars={}",
                                ttfs, firstScene.length());

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

            return new StreamResult(fullBuffer.toString(), null, ttft, ttfs);

        } catch (ExternalApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("❌ [STREAM] Streaming failed after {}ms", System.currentTimeMillis() - startTime, e);
            throw new ExternalApiException("OpenRouter 스트리밍 중 오류: " + e.getMessage(), e);
        }
    }

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
            return null;
        }
    }
}