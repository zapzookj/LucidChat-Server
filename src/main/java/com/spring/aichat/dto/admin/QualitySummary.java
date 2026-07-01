package com.spring.aichat.dto.admin;

import java.util.Map;

/** RLHF 품질 요약. byReason 은 dislikeReason 6종(OTHER 포함) 카운트. */
public record QualitySummary(
    long likes,
    long dislikes,
    Map<String, Long> byReason
) {}
