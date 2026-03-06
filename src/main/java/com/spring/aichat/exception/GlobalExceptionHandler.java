package com.spring.aichat.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 처리기
 *
 * [Phase 5] RateLimitException → 429 Too Many Requests 처리 추가
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException e, HttpServletRequest req) {
        int status = switch (e.getErrorCode()) {
            case NOT_FOUND -> 404;
            case BAD_REQUEST -> 400;
            case INSUFFICIENT_ENERGY -> 402;
            case EXTERNAL_API_ERROR -> 502;
            default -> 500;
        };

        return ResponseEntity.status(status)
            .body(ApiErrorResponse.of(status, e.getErrorCode(), e.getMessage(), req.getRequestURI()));
    }

    /**
     * [Phase 5] Rate Limit 초과 → 429 + Retry-After 헤더
     *
     * 프론트엔드에서 429 응답을 받으면:
     * 1. 에러 토스트 "요청이 너무 빠릅니다" 표시
     * 2. Retry-After 헤더 값만큼 대기 후 재시도 허용
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimit(RateLimitException e, HttpServletRequest req) {
        log.warn("[RATE_LIMIT] 429 response: uri={}, message={}", req.getRequestURI(), e.getMessage());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", String.valueOf(e.getRetryAfterSeconds()));

        return ResponseEntity.status(429)
            .headers(headers)
            .body(ApiErrorResponse.of(429, ErrorCode.BAD_REQUEST, e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .orElse("Validation error");

        return ResponseEntity.badRequest()
            .body(ApiErrorResponse.of(400, ErrorCode.BAD_REQUEST, msg, req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnknown(Exception e, HttpServletRequest req) {
        log.error("Unhandled exception occurred: ", e);

        return ResponseEntity.internalServerError()
            .body(ApiErrorResponse.of(500, ErrorCode.INTERNAL_ERROR, "서버 오류가 발생했습니다.", req.getRequestURI()));
    }
}