package com.spring.aichat.dto.chat;

import jakarta.validation.constraints.Pattern;

/**
 * [Phase 5.1] 채팅 로그 평가 요청 DTO
 *
 * rating: "LIKE" | "DISLIKE"
 * - 같은 값을 재전송하면 토글(해제)
 * - null 전송은 허용하지 않음 (해제는 토글로 처리)
 */
public record RateChatLogRequest(
    @Pattern(regexp = "LIKE|DISLIKE", message = "rating must be LIKE or DISLIKE")
    String rating
) {}