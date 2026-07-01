package com.spring.aichat.dto.admin;

import com.spring.aichat.domain.audit.AuditLog;

import java.time.LocalDateTime;

public record AuditLogResponse(
    Long id,
    Long actorUserId,
    String actorUsername,
    String action,
    String targetType,
    String targetId,
    String summary,
    String detailJson,
    LocalDateTime createdAt
) {
    public static AuditLogResponse from(AuditLog a) {
        return new AuditLogResponse(
            a.getId(), a.getActorUserId(), a.getActorUsername(),
            a.getAction(), a.getTargetType(), a.getTargetId(),
            a.getSummary(), a.getDetailJson(), a.getCreatedAt()
        );
    }
}
