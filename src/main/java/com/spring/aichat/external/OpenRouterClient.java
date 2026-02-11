package com.spring.aichat.external;

import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiChatResponse;
import com.spring.aichat.exception.ExternalApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenRouter(OpenAI 호환) API 호출 전용 Client
 *
 * [Phase 3 최적화]
 * - 지수 백오프 재시도(Exponential Backoff Retry) 추가
 * - 동시 요청 시 간헐적으로 발생하는 401/429 등 일시적 오류 자동 복구
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenRouterClient {

    private final RestClient openRouterRestClient;

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
}