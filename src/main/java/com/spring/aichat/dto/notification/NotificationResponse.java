package com.spring.aichat.dto.notification;

import com.spring.aichat.domain.notification.Notification;

import java.time.LocalDateTime;

public record NotificationResponse(
    Long id,
    String type,
    String title,
    String body,
    String linkType,
    String linkId,
    boolean read,
    LocalDateTime createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
            n.getId(), n.getType(), n.getTitle(), n.getBody(),
            n.getLinkType(), n.getLinkId(), n.getReadAt() != null, n.getCreatedAt());
    }
}
