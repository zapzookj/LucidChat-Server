package com.spring.aichat.domain.moderation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 프롬프트 인젝션/탈옥 탐지 이벤트 (Phase 6). */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "injection_events", indexes = {
    @Index(name = "idx_inj_user", columnList = "user_id"),
    @Index(name = "idx_inj_created", columnList = "created_at")
})
public class InjectionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", length = 50)
    private String username;

    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "source", length = 20)
    private String source;

    /** EXTRACTION / CRITICAL */
    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "matched_pattern", length = 500)
    private String matchedPattern;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public static InjectionEvent of(Long userId, String username, Long roomId, String source,
                                    String severity, String matchedPattern, String message) {
        InjectionEvent e = new InjectionEvent();
        e.userId = userId;
        e.username = username;
        e.roomId = roomId;
        e.source = source;
        e.severity = severity;
        e.matchedPattern = matchedPattern;
        e.message = message;
        return e;
    }
}
