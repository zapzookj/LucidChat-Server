package com.spring.aichat.dto.auth;

import java.util.Map;

/**
 * 인증 응답(로그인/회원가입 공통)
 * - accessToken + roomId를 함께 내려줘서 가입 직후 바로 채팅 가능
 */
public record AuthResponse(
    String accessToken,
    long expiresIn,
    long roomId,
    Map<String, Object> user
) {}
