package com.spring.aichat.domain.memory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [V2 Story + Theater 결함 A 통합] 캐릭터별 누적 메모리 요약
 *
 * <p>기존 {@link MemorySummary}는 {@code roomId} 단위 메모리 — V1 1:1 채팅에서는
 * 캐릭터 단위와 동치였지만, V2 Story(World당 1방, 히로인 3명)에서는 *World 단위*가 된다.
 * 따라서 *캐릭터별 누적 메모리*를 별도로 저장할 필요가 생긴다.
 *
 * <p>이 신규 테이블이 두 가지 문제를 동시에 해결:
 *
 * <pre>
 *   [V2 Story]
 *   - World 1방 안의 히로인 3명이 각자 *유저와의 누적 기억*을 가진다.
 *   - 디렉터 prompt [8] CUMULATIVE MEMORY 섹션에 캐릭터별 memory 주입.
 *
 *   [Theater 결함 A 통합 패치]
 *   - 도그푸딩 발견: Theater의 히로인이 *유저와의 과거 사건을 기억 못 함*.
 *   - 원인: Theater는 scene 단위 진행 + 별도 캐릭터 메모리 부재.
 *   - 해결: Theater도 본 테이블에 동일 패턴으로 저장 → 결함 A 자연 해결.
 * </pre>
 *
 * <p>[기존 MemorySummary와의 역할 분리]
 * - {@link MemorySummary} (room 단위): V2 Story에서는 *World-level 메모리*로 의미 재정의.
 *   세계 전반의 큰 이벤트/소문/시간 흐름을 요약. 디렉터 prompt [8]의 World 진행 요약 섹션 주입.
 * - {@code HeroineMemorySummary} (room + character 단위): *각 히로인 시점*의 사건 요약.
 *   유저와 그 캐릭터 사이의 *관계 진행*과 *공유 경험*만 요약.
 *
 * <p>[갱신 정책]
 * - 매 씬 1줄 누적은 디렉터 응답의 {@code memory_delta} 필드에서 추출 →
 *   in-memory 누적 → N=10 메시지마다 LLM 요약 → 본 테이블 영속.
 * - 압축 LLM은 저가 모델 ({@code sentimentModel} 재활용).
 *
 * <p>[조회 인덱스]
 * - 디렉터 prompt 빌딩 시 자주 조회: {@code (room_id, character_id, created_at DESC)}.
 * - 캐싱 ({@code MemoryService}의 Redis 패턴 동일 활용).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "heroine_memory_summaries",
    indexes = {
        @Index(name = "idx_heroine_memory_room_char",
            columnList = "room_id, character_id, created_at"),
        @Index(name = "idx_heroine_memory_user",
            columnList = "user_id")
    })
public class HeroineMemorySummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ChatRoom ID 또는 Theater room ID. FK 미설정 — 두 모드 공유 사용을 위해.
     * (Theater state row id와 V2 Story ChatRoom id가 분리되어 있어 다형성 회피.)
     */
    @Column(name = "room_id", nullable = false)
    private Long roomId;

    /** 어느 캐릭터 시점의 메모리인가. */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** LLM이 요약한 *그 캐릭터 시점*의 사건. 1~3 문장 권장. */
    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    /** 요약 생성 시점의 누적 턴 수. */
    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    /**
     * 소스 모드 — STORY_V2 / THEATER.
     * 같은 테이블에 두 모드 데이터가 섞이므로 필터링/디버그용.
     */
    @Column(name = "source_mode", nullable = false, length = 20)
    private String sourceMode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static HeroineMemorySummary forStory(Long roomId, Long characterId, Long userId,
                                                String summary, int turnNumber) {
        HeroineMemorySummary m = new HeroineMemorySummary();
        m.roomId = roomId;
        m.characterId = characterId;
        m.userId = userId;
        m.summary = summary;
        m.turnNumber = turnNumber;
        m.sourceMode = "STORY_V2";
        return m;
    }

    public static HeroineMemorySummary forTheater(Long roomId, Long characterId, Long userId,
                                                  String summary, int turnNumber) {
        HeroineMemorySummary m = new HeroineMemorySummary();
        m.roomId = roomId;
        m.characterId = characterId;
        m.userId = userId;
        m.summary = summary;
        m.turnNumber = turnNumber;
        m.sourceMode = "THEATER";
        return m;
    }
}