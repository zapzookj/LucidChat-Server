package com.spring.aichat.dto.user;

public record UserResponse(
    long id,
    String username,
    String nickname,
    String email,
    String profileDescription,
    Boolean isSecretMode,
    int energy          // [Fix] 에너지 동기화를 위해 추가
) {
}