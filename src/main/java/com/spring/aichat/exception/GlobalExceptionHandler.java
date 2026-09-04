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
            case FORBIDDEN -> 403;
            case CONTENT_BLOCKED -> 400;
            case CONFLICT -> 409;       // [Phase 5.5 UX Polish · R4] 활성극 충돌
            case EXTERNAL_API_ERROR -> 502;
            // [Story V2] 신규 ErrorCode 매핑
            case WORLD_NOT_FOUND -> 404;
            case STORY_V2_ROOM_EXISTS -> 409;   // ★ confirm 모달 트리거 — 누락 시 500이 되어 UI 흐름 깨짐
            case WORLD_LOCATION_MISSING -> 500; // 서버 결함 (시드 누락)
            case PREMIUM_REQUIRED -> 402;
            case PERSONA_UNDERAGE -> 403;   // [블록 B] FE 프로필 나이 수정 제안 모달 트리거
            case REFUND_CLAWBACK_FAILED -> 409;   // [D-4.3] 환불은 됐으나 혜택 회수 대상 없음 — 관리자에게 명시
            case PAYMENT_DELIVERY_PENDING -> 409; // [안건 4] 결제 확정·지급 대기 — FE '지급 다시 시도' 트리거 (5xx 알람 축 분리)
            // [E-4.4] 이미 마감된 챕터에 chapter-end 재요청 — FE가 자기 치유하는 코드다.
            case CHAPTER_ALREADY_FINALIZED -> 409;
            // ★ 아래 둘은 ErrorCode에는 있었는데 이 switch에 없어 **default로 500이 나가고 있었다**
            //   (바로 위 STORY_V2_ROOM_EXISTS 주석이 경고한 그 함정에 정작 이 둘이 걸려 있었다).
            //   FE는 status가 아니라 응답 본문의 errorCode로 분기하므로 동작은 했으나,
            //   클라이언트 귀책 충돌이 5xx로 집계돼 서버 알람 축을 오염시킨다.
            case STALE_CLIENT_STATE -> 409;       // [H-22] 클라 세션 상태가 서버 기준과 어긋남
            case UNPAID_BATCH -> 409;             // [B-5.2] 미과금 배치 소비 시도 — FE가 loadNextBatch로 자기 치유
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