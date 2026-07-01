package com.spring.aichat.dto.admin;

import jakarta.validation.constraints.NotBlank;

/** 구독 수동 부여. tier = SubscriptionType 이름(LUCID_PASS / LUCID_MIDNIGHT_PASS). */
public record SubscriptionGrantRequest(
    @NotBlank String tier,
    @NotBlank String reason
) {}
