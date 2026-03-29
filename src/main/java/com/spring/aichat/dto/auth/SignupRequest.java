package com.spring.aichat.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * [Phase 5.5-Guard] 회원가입 DTO 길이 제한 추가
 */
public record SignupRequest(
    @NotBlank
    @Size(max = 30, message = "아이디는 30자 이내로 입력해주세요.")
    String username,

    @NotBlank
    @Size(min = 4, max = 100, message = "비밀번호는 4~100자로 입력해주세요.")
    String password,

    @NotBlank
    @Size(max = 20, message = "닉네임은 20자 이내로 입력해주세요.")
    String nickname,

    @Email String email
) {}