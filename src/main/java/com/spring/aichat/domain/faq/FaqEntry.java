package com.spring.aichat.domain.faq;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 편집형 QnA(FAQ) 항목 (Phase 6). 백오피스에서 CRUD, 유저 앱에서 게시된 것만 노출. */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "faq_entries", indexes = {
    @Index(name = "idx_faq_published", columnList = "published, category, display_order")
})
public class FaqEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category", nullable = false, length = 60)
    private String category;

    @Column(name = "question", nullable = false, length = 300)
    private String question;

    @Column(name = "answer", nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "published", nullable = false)
    private boolean published = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public static FaqEntry create(String category, String question, String answer, int displayOrder, boolean published) {
        FaqEntry f = new FaqEntry();
        f.category = category;
        f.question = question;
        f.answer = answer;
        f.displayOrder = displayOrder;
        f.published = published;
        return f;
    }

    public void update(String category, String question, String answer, int displayOrder, boolean published) {
        this.category = category;
        this.question = question;
        this.answer = answer;
        this.displayOrder = displayOrder;
        this.published = published;
    }
}
