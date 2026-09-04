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
            case UNAUTHORIZED -> 401;             // [INT-5] 세션 만료·RT 불일치 — FE axios가 401에 재로그인 유도
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [C-0.3 · B-8.2 · C-2.c] 클라이언트 귀책 → 400. 종전엔 전부 Exception.class로 떨어져 500이었다.
    //
    //  왜 문제인가: ① 5xx 알람 축이 클라 귀책 요청으로 오염돼 진짜 서버 장애가 묻힌다
    //  ② FE가 '내 요청이 잘못됨'과 '서버가 죽음'을 구분할 수 없다 ③ 500 본문에 스택 유발 문구가 섞일 수 있다.
    //
    //  응답 message는 **고정 카피**로 두고 원문은 로그에만 남긴다 — 예외 메시지에 내부 규칙 문구
    //  ("공식 세계관과 UGC 월드는 동시 연결 불가" 등)가 담겨 있어 그대로 내보내면 내부 구조가 샌다.
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 잘못된 인자 — enum 파싱 실패(존재하지 않는 값), 도메인 불변식 위반 등.
     * 스택을 함께 남긴다: 서버 결함이 IAE로 나오는 경우도 있어 400으로 내려도 원인 추적은 되어야 한다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest req) {
        log.warn("[BAD_REQUEST] IllegalArgument: uri={}, msg={}", req.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.badRequest()
            .body(ApiErrorResponse.of(400, ErrorCode.BAD_REQUEST, "요청이 올바르지 않습니다.", req.getRequestURI()));
    }

    /** [C-2.c] 역직렬화 실패 — 오타 enum·깨진 JSON 본문. 종전엔 500이었다. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotReadable(
        org.springframework.http.converter.HttpMessageNotReadableException e, HttpServletRequest req) {
        log.warn("[BAD_REQUEST] malformed body: uri={}, msg={}", req.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
            .body(ApiErrorResponse.of(400, ErrorCode.BAD_REQUEST, "요청 본문을 해석할 수 없습니다.", req.getRequestURI()));
    }

    /** 같은 뿌리 — {@code @PathVariable}/{@code @RequestParam} 타입 불일치(예: id에 문자열). */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        log.warn("[BAD_REQUEST] type mismatch: uri={}, param={}", req.getRequestURI(), e.getName());
        return ResponseEntity.badRequest()
            .body(ApiErrorResponse.of(400, ErrorCode.BAD_REQUEST, "요청 값의 형식이 올바르지 않습니다.", req.getRequestURI()));
    }

    /**
     * [INT-5] JWT 디코드 실패(만료·서명 불일치) → 401.
     * {@code POST /auth/refresh}가 {@code jwtDecoder.decode}를 직접 부르므로 이 advice에 도달한다
     * (필터 체인의 인증 실패는 Spring Security 진입점이 따로 처리한다).
     * 만료된 RT로 재발급을 시도하는 것은 <b>정상 흐름</b>이지 서버 오류가 아니다.
     */
    @ExceptionHandler(org.springframework.security.oauth2.jwt.JwtException.class)
    public ResponseEntity<ApiErrorResponse> handleJwt(
        org.springframework.security.oauth2.jwt.JwtException e, HttpServletRequest req) {
        log.info("[UNAUTHORIZED] JWT 검증 실패: uri={}, msg={}", req.getRequestURI(), e.getMessage());
        return ResponseEntity.status(401)
            .body(ApiErrorResponse.of(401, ErrorCode.UNAUTHORIZED, "다시 로그인해 주세요.", req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnknown(Exception e, HttpServletRequest req) {
        log.error("Unhandled exception occurred: ", e);

        return ResponseEntity.internalServerError()
            .body(ApiErrorResponse.of(500, ErrorCode.INTERNAL_ERROR, "서버 오류가 발생했습니다.", req.getRequestURI()));
    }
}