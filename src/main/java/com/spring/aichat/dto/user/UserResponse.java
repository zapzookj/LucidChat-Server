package com.spring.aichat.dto.user;

public record UserResponse(
    long id,
    String username,
    String nickname,
    String email,
    String profileDescription
) {
}
