package com.spring.aichat.dto.admin;

import jakarta.validation.constraints.NotBlank;

/** 계정 상태 변경. status = UserStatus 이름(ACTIVE / SUSPENDED / BANNED). */
public record StatusChangeRequest(
    @NotBlank String status,
    @NotBlank String reason
) {}
