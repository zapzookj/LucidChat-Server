package com.spring.aichat.external;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * OpenRouter Embedding API 호출 Client
 *
 * [Phase 3 최적화]
 * - 지수 백오프 재시도 추가 (OpenRouterClient와 동일 패턴)
 * - 동시 요청 시 간헐적 401/429 자동 복구
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingClient {

    private final RestClient openRouterRestClient;

    @Value("${spring.ai.openai.embedding-model}")
    private String embeddingModel;

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 500;
    private static final int[] RETRYABLE_STATUS_CODES = {401, 429, 500, 502, 503, 504};

    public List<Double> embed(String text) {
        Map<String, Object> request = Map.of(
            "model", embeddingModel,
            "input", text
        );

        RestClientResponseException lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    long backoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                    log.warn("🔄 [RETRY] Embedding attempt {}/{} after {}ms",
                        attempt, MAX_RETRIES, backoff);
                    Thread.sleep(backoff);
                }

                EmbeddingResponse response = openRouterRestClient.post()
                    .uri("/embeddings")
                    .body(request)
                    .retrieve()
                    .body(EmbeddingResponse.class);

                if (response == null || response.data() == null || response.data().isEmpty()) {
                    throw new RuntimeException("Embedding response is empty");
                }

                if (attempt > 0) {
                    log.info("✅ [RETRY] Embedding succeeded on attempt {}", attempt + 1);
                }

                return response.data().get(0).embedding();

            } catch (RestClientResponseException e) {
                lastException = e;
                int statusCode = e.getStatusCode().value();

                if (!isRetryable(statusCode)) {
                    log.error("❌ [RETRY] Non-retryable embedding error {}", statusCode);
                    throw new RuntimeException("Embedding API 호출 실패 (" + statusCode + ")", e);
                }

                log.warn("⚠️ [RETRY] Retryable embedding error {} on attempt {}/{}",
                    statusCode, attempt + 1, MAX_RETRIES + 1);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Embedding 재시도 중 인터럽트 발생", e);

            } catch (Exception e) {
                if (!(e instanceof RestClientResponseException)) {
                    log.error("Embedding Failed for text: {}",
                        text.substring(0, Math.min(text.length(), 20)), e);
                    throw new RuntimeException("Embedding API Call Failed", e);
                }
            }
        }

        log.error("❌ [RETRY] All embedding retries exhausted");
        throw new RuntimeException("Embedding API 호출 실패 (재시도 모두 실패)", lastException);
    }

    private boolean isRetryable(int statusCode) {
        for (int code : RETRYABLE_STATUS_CODES) {
            if (code == statusCode) return true;
        }
        return false;
    }

    // Response DTO
    record EmbeddingResponse(List<EmbeddingData> data) {}
    record EmbeddingData(
        @JsonProperty("embedding") List<Double> embedding,
        @JsonProperty("index") int index
    ) {}
}