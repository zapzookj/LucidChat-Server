package com.spring.aichat.domain.moderation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 콘텐츠 모더레이션 차단 이벤트 (Phase 6). 리뷰 큐 + 반복위반 집계 소스. */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "moderation_events", indexes = {
    @Index(name = "idx_mod_user", columnList = "user_id"),
    @Index(name = "idx_mod_created", columnList = "created_at")
})
public class ModerationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "source", length = 20)
    private String source;

    /** 1=키워드(Aho-Corasick), 2=OpenAI Moderation. */
    @Column(name = "blocked_at_step", nullable = false)
    private int blockedAtStep;

    @Column(name = "category", length = 80)
    private String category;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public static ModerationEvent of(Long userId, Long roomId, String source, int blockedAtStep,
                                     String category, String message, Long latencyMs) {
        ModerationEvent e = new ModerationEvent();
        e.userId = userId;
        e.roomId = roomId;
        e.source = source;
        e.blockedAtStep = blockedAtStep;
        e.category = category;
        e.message = message;
        e.latencyMs = latencyMs;
        return e;
    }
}
