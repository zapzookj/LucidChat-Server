package com.spring.aichat.exception;

import java.time.LocalDateTime;

/**
 * API 에러 응답 표준 포맷
 */
public record ApiErrorResponse(
    LocalDateTime timestamp,
    int status,
    ErrorCode code,
    String message,
    String path
) {
    public static ApiErrorResponse of(int status, ErrorCode code, String message, String path) {
        return new ApiErrorResponse(LocalDateTime.now(), status, code, message, path);
    }
}
