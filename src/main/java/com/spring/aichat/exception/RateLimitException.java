package com.spring.aichat.exception;

/**
 * [Phase 5] API Rate Limit 초과 예외
 *
 * HTTP 429 Too Many Requests 응답과 매핑.
 * GlobalExceptionHandler에서 429 상태 코드 + Retry-After 헤더로 변환.
 */
public class RateLimitException extends RuntimeException {

    private final int retryAfterSeconds;

    public RateLimitException(String message) {
        super(message);
        this.retryAfterSeconds = 3;
    }

    public RateLimitException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}