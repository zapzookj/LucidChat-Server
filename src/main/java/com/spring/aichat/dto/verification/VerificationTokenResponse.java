package com.spring.aichat.dto.verification;

/**
 * 성인 인증 토큰 발급 응답
 * - 프론트엔드가 이 값들로 NICE 인증 팝업을 호출
 * - key/iv는 절대 포함하지 않음 (서버 세션에만 보관)
 */
public record VerificationTokenResponse(
    String requestNo,
    String tokenVersionId,
    String encData,
    String integrityValue
) {}