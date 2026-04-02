package com.spring.aichat.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.exception.ExternalApiException;
import com.spring.aichat.external.LlmCircuitBreaker.TtftTimeoutException;
import com.spring.aichat.service.stream.SceneStreamExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * [Phase 5.5-Perf] OpenRouter 스트리밍 호출 클라이언트
 *
 * [Phase 5.5-EV Fix 2] event_status 선행 추출 콜백 추가
 *   - onEventStatus: event_status가 추출되는 즉시 호출 (first_scene보다 먼저)
 *   - 기존 onFirstScene 콜백은 그대로 유지
 *
 * [Phase 5.5-Stability] TTFT 데드라인 워치독
 *   - sendAsync() 기반 비동기 HTTP → 워치독이 연결 단계부터 포괄
 *   - 워치독 발동 시:
 *     1) CompletableFuture.cancel() → 아직 HTTP 연결 대기 중이면 즉시 중단
 *     2) InputStream.close() → 이미 연결되어 스트림 읽기 중이면 즉시 중단
 *   - 예외 발생 → watchdogTriggered 플래그로 TtftTimeoutException 전환
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

    /** TTFT 데드라인 워치독 스케줄러 (데몬 스레드) */
    private final ScheduledExecutorService ttftWatchdog;

    public OpenRouterStreamClient(OpenAiProperties props, ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = objectMapper;
        this.baseUrl = props.baseUrl();
        this.apiKey = props.apiKey();
        this.appReferer = props.appReferer();
        this.appTitle = props.appTitle();

        this.ttftWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ttft-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    public record StreamResult(
        String fullResponse,
        String firstSceneJson,
        long ttft,
        long ttfs
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  하위 호환 오버로드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public StreamResult streamCompletion(OpenAiChatRequest request, Consumer<String> onFirstScene) throws IOException {
        return streamCompletion(request, onFirstScene, null, 0);
    }

    public StreamResult streamCompletion(OpenAiChatRequest request,
                                         Consumer<String> onFirstScene,
                                         Consumer<String> onEventStatus) throws IOException {
        return streamCompletion(request, onFirstScene, onEventStatus, 0);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Stability] 메인 스트리밍 (TTFT 데드라인 지원)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 스트리밍 호출 + event_status 선행 추출 + TTFT 데드라인
     *
     * @param request          ChatCompletion 요청
     * @param onFirstScene     첫 번째 씬 JSON 완성 시 콜백
     * @param onEventStatus    event_status 추출 시 콜백 (first_scene보다 먼저 발화)
     * @param ttftDeadlineMs   TTFT 데드라인 (ms). 0이면 데드라인 비활성.
     *                         HTTP 연결 대기 + 첫 토큰 대기 전체 구간을 포괄.
     * @throws TtftTimeoutException  TTFT 데드라인 초과 시
     * @throws ExternalApiException  기타 스트리밍 오류 시
     */
    public StreamResult streamCompletion(OpenAiChatRequest request,
                                         Consumer<String> onFirstScene,
                                         Consumer<String> onEventStatus,
                                         long ttftDeadlineMs) throws IOException {
        long startTime = System.currentTimeMillis();

        OpenAiChatRequest streamRequest = new OpenAiChatRequest(
            request.model(), request.messages(), request.temperature(),
            true, request.frequencyPenalty(), request.presencePenalty(), request.provider(), Map.of("type", "json_object")
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

        // ━━━ [Stability] 워치독 상태 변수 ━━━
        AtomicBoolean firstTokenReceived = new AtomicBoolean(false);
        AtomicBoolean watchdogTriggered = new AtomicBoolean(false);
        AtomicReference<InputStream> streamRef = new AtomicReference<>(null);
        ScheduledFuture<?> watchdogTask = null;

        try {
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            //  [Stability] 비동기 HTTP + 선제 워치독
            //
            //  기존 문제: httpClient.send() 블로킹으로 인해 OpenRouter가
            //  HTTP 200을 반환할 때까지(= Provider 연결 대기) 워치독이 시작되지 않음.
            //  → AI Studio 지연 10초 + 워치독 2초 = 12초 후에야 폴백 발생.
            //
            //  해결: sendAsync()로 전환. 워치독을 HTTP 요청 직후 즉시 스케줄링.
            //  데드라인 초과 시 이중 중단:
            //    1) future.cancel(true) → HTTP 연결 대기 중이면 즉시 취소
            //    2) InputStream.close() → 스트림 읽기 중이면 IOException 유발
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

            CompletableFuture<HttpResponse<InputStream>> asyncFuture =
                httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            // ── 워치독: HTTP 요청 직후 즉시 스케줄링 (연결 대기 포함) ──
            if (ttftDeadlineMs > 0) {
                watchdogTask = ttftWatchdog.schedule(() -> {
                    if (!firstTokenReceived.get()) {
                        watchdogTriggered.set(true);
                        long elapsed = System.currentTimeMillis() - startTime;
                        log.warn("⏱️ [CIRCUIT] TTFT 워치독 발동: {}ms 데드라인 초과 (elapsed={}ms) — 강제 중단 | model={}",
                            ttftDeadlineMs, elapsed, request.model());

                        // Phase 1: HTTP 연결 대기 중이면 future 취소
                        asyncFuture.cancel(true);

                        // Phase 2: 이미 연결 완료 후 스트림 읽기 중이면 InputStream 닫기
                        InputStream s = streamRef.get();
                        if (s != null) {
                            try { s.close(); } catch (Exception ignored) {}
                        }
                    }
                }, ttftDeadlineMs, TimeUnit.MILLISECONDS);
            }

            // ── HTTP 응답 대기 (워치독이 cancel() 시 CancellationException) ──
            HttpResponse<InputStream> response;
            try {
                response = asyncFuture.join();
            } catch (CancellationException | CompletionException e) {
                if (watchdogTriggered.get()) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.warn("⏱️ [CIRCUIT] TTFT 데드라인 초과 확정 (연결 단계): elapsed={}ms, deadline={}ms | model={}",
                        elapsed, ttftDeadlineMs, request.model());
                    throw new TtftTimeoutException(ttftDeadlineMs);
                }
                Throwable cause = (e instanceof CompletionException) ? e.getCause() : e;
                throw new ExternalApiException("OpenRouter 연결 실패: " +
                    (cause != null ? cause.getMessage() : e.getMessage()), e);
            }

            // join() 중에 워치독이 발동한 경우 이중 체크
            if (watchdogTriggered.get()) {
                throw new TtftTimeoutException(ttftDeadlineMs);
            }

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes());
                log.error("❌ [STREAM] OpenRouter returned {}: {}", response.statusCode(),
                    errorBody.substring(0, Math.min(300, errorBody.length())));
                throw new ExternalApiException(
                    "OpenRouter 스트리밍 실패 (" + response.statusCode() + "): " + errorBody);
            }

            InputStream responseBody = response.body();
            streamRef.set(responseBody); // 워치독이 스트림 단계에서도 중단 가능

            // ── SSE 스트림 읽기 ──
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;

                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    if (data.isEmpty()) continue;

                    String token = extractDeltaContent(data);
                    if (token == null || token.isEmpty()) continue;

                    // ━━━ 첫 토큰 도착: TTFT 기록 + 워치독 즉시 해제 ━━━
                    if (ttft < 0) {
                        ttft = System.currentTimeMillis() - startTime;
                        firstTokenReceived.set(true);

                        if (watchdogTask != null) {
                            watchdogTask.cancel(false);
                        }

                        log.info("⏱️ [STREAM] TTFT: {}ms | model={} | deadline={}",
                            ttft, request.model(),
                            ttftDeadlineMs > 0 ? ttftDeadlineMs + "ms" : "none");
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

        } catch (Exception e) {
            // ━━━ [Stability] 워치독 중단에 의한 예외 → TtftTimeoutException 전환 ━━━
            if (watchdogTriggered.get() && !(e instanceof TtftTimeoutException)) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("⏱️ [CIRCUIT] TTFT 데드라인 초과 확정: elapsed={}ms, deadline={}ms | model={}",
                    elapsed, ttftDeadlineMs, request.model());
                throw new TtftTimeoutException(ttftDeadlineMs);
            }
            if (e instanceof TtftTimeoutException || e instanceof ExternalApiException) throw e;
            log.error("❌ [STREAM] Streaming failed after {}ms", System.currentTimeMillis() - startTime, e);
            throw new ExternalApiException("OpenRouter 스트리밍 중 오류: " + e.getMessage(), e);

        } finally {
            // 워치독 정리
            if (watchdogTask != null && !watchdogTask.isDone()) {
                watchdogTask.cancel(false);
            }
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