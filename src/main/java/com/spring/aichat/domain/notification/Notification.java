package com.spring.aichat.domain.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 유저 대상 인앱 알림 (Phase 6 · 폴링 기반, user-keyed).
 *
 * 기존 OffscreenNotification 은 방-스코프/캐릭터-작성/STORY 전용이라 티켓 답변 등에 재사용 불가.
 * 본 엔티티는 user_id 로 키잉되며 클라이언트가 폴링으로 미읽음을 가져간다.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notification_user", columnList = "user_id"),
    @Index(name = "idx_notification_unread", columnList = "user_id, read_at")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 알림 유형. 예: TICKET_REPLY, NOTICE, SYSTEM. */
    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** 딥링크 유형/식별자 (예: TICKET / {ticketId}) — 클라이언트가 상세로 이동. */
    @Column(name = "link_type", length = 40)
    private String linkType;

    @Column(name = "link_id", length = 100)
    private String linkId;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public void markRead() {
        if (this.readAt == null) this.readAt = LocalDateTime.now();
    }

    public static Notification of(Long userId, String type, String title, String body, String linkType, String linkId) {
        Notification n = new Notification();
        n.userId = userId;
        n.type = type;
        n.title = title;
        n.body = body;
        n.linkType = linkType;
        n.linkId = linkId;
        return n;
    }
}
