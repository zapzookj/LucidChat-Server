package com.spring.aichat.dto.user;

public record UpdateUserRequest(
    String nickname,
    String profileDescription
) {
}
