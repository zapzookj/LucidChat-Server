package com.spring.aichat.dto.admin;

import com.spring.aichat.domain.moderation.ModerationEvent;

import java.time.LocalDateTime;

public record ModerationEventResponse(
    Long id,
    Long userId,
    Long roomId,
    String source,
    int blockedAtStep,
    String category,
    String message,
    Long latencyMs,
    LocalDateTime createdAt
) {
    public static ModerationEventResponse from(ModerationEvent e) {
        return new ModerationEventResponse(
            e.getId(), e.getUserId(), e.getRoomId(), e.getSource(),
            e.getBlockedAtStep(), e.getCategory(), e.getMessage(), e.getLatencyMs(), e.getCreatedAt());
    }
}
