package com.spring.aichat.domain.memory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [Phase 5.5-Perf] 장기 기억 요약 엔티티
 *
 * Pinecone Vector DB를 대체하는 RDB 기반 장기 기억 저장소.
 *
 * [마이그레이션 근거]
 * - Lucid Chat의 RAG 데이터는 "시간순 짧은 일기장"에 불과 (수십 건)
 * - Pinecone 임베딩+검색 왕복 2~3초 → RDB 직접 조회 <5ms
 * - Redis 캐싱을 결합하면 매 요청 시 0ms (캐시 히트)
 * - Vector similarity search가 불필요 (모든 기억을 시간순으로 전부 주입)
 *
 * [인덱스]
 * - {room_id, created_at}: 방별 시간순 조회 (가장 빈번)
 * - {user_id}: 유저별 전체 기억 조회 (관리용)
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "memory_summaries",
    indexes = {
        @Index(name = "idx_memory_room_created", columnList = "room_id, created_at"),
        @Index(name = "idx_memory_user", columnList = "user_id")
    })
public class MemorySummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** LLM이 생성한 요약 텍스트 (3문장 이내) */
    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    /** 이 요약이 생성된 시점의 유저 턴 수 */
    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public MemorySummary(Long roomId, Long userId, String summary, int turnNumber) {
        this.roomId = roomId;
        this.userId = userId;
        this.summary = summary;
        this.turnNumber = turnNumber;
    }
}