package com.spring.aichat.dto.admin;

import com.spring.aichat.domain.moderation.InjectionEvent;

import java.time.LocalDateTime;

public record InjectionEventResponse(
    Long id,
    Long userId,
    String username,
    Long roomId,
    String source,
    String severity,
    String matchedPattern,
    String message,
    LocalDateTime createdAt
) {
    public static InjectionEventResponse from(InjectionEvent e) {
        return new InjectionEventResponse(
            e.getId(), e.getUserId(), e.getUsername(), e.getRoomId(), e.getSource(),
            e.getSeverity(), e.getMatchedPattern(), e.getMessage(), e.getCreatedAt());
    }
}
