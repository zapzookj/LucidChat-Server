package com.spring.aichat.dto.admin;

import jakarta.validation.constraints.NotBlank;

/** 유료 에너지 수동 조정. amount 양수=지급, 음수=차감(0 미만으로 내려가지 않음). */
public record EnergyAdjustRequest(
    int amount,
    @NotBlank String reason
) {}
