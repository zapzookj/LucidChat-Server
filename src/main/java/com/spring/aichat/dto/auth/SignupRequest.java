package com.spring.aichat.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SignupRequest(
    @NotBlank String username,
    @NotBlank String password,
    @NotBlank String nickname,
    @Email String email
) {}
