package com.spring.aichat.domain.notice;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 공지사항 (Phase 6). */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "notices", indexes = {
    @Index(name = "idx_notice_published", columnList = "published, pinned, published_at")
})
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "pinned", nullable = false)
    private boolean pinned = false;

    @Column(name = "published", nullable = false)
    private boolean published = true;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.published && this.publishedAt == null) this.publishedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (this.published && this.publishedAt == null) this.publishedAt = LocalDateTime.now();
    }

    public static Notice create(String title, String body, boolean pinned, boolean published) {
        Notice n = new Notice();
        n.title = title;
        n.body = body;
        n.pinned = pinned;
        n.published = published;
        return n;
    }

    public void update(String title, String body, boolean pinned, boolean published) {
        this.title = title;
        this.body = body;
        this.pinned = pinned;
        this.published = published;
    }
}
