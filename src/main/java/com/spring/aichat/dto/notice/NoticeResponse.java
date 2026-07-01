package com.spring.aichat.dto.notice;

import com.spring.aichat.domain.notice.Notice;

import java.time.LocalDateTime;

public record NoticeResponse(
    Long id,
    String title,
    String body,
    boolean pinned,
    boolean published,
    LocalDateTime publishedAt,
    LocalDateTime updatedAt
) {
    public static NoticeResponse from(Notice n) {
        return new NoticeResponse(
            n.getId(), n.getTitle(), n.getBody(), n.isPinned(), n.isPublished(),
            n.getPublishedAt(), n.getUpdatedAt());
    }
}
