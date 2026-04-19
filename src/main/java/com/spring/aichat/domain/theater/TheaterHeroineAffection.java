package com.spring.aichat.domain.theater;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [Phase 5.5-Theater] Theater 세션의 히로인별 호감도 관리
 *
 * 멀티 히로인 세계관에서 각 히로인의 호감도를 독립적으로 추적.
 * ChatRoom(THEATER 모드) × Character = 1 row
 *
 * [용도]
 * - 메인 히로인 수렴 판정 (Act 3 이후 최고 호감도 히로인)
 * - Chapter 종료 리포트 (호감도 변화량 + 리드 히로인 표시)
 * - 엔딩 분기 계산
 * - 장소 선택 분기에 현재 호감도 표시
 *
 * [스키마 특성]
 * - room_id + character_id 유니크 (동일 방 내 캐릭터 중복 방지)
 * - delta 추적 (최근 Chapter에서의 변화량) — 리포트 연출용
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "theater_heroine_affections",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_theater_heroine_affection",
        columnNames = {"room_id", "character_id"}
    ),
    indexes = {
        @Index(name = "idx_theater_affection_room", columnList = "room_id"),
        @Index(name = "idx_theater_affection_last_appeared", columnList = "last_appeared_at")
    })
public class TheaterHeroineAffection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    private Character character;

    /** 현재 호감도 (-100 ~ 100) */
    @Column(name = "affection", nullable = false)
    private int affection = 0;

    /** 직전 Chapter 대비 호감도 변화량 (리포트 연출용) */
    @Column(name = "last_chapter_delta", nullable = false)
    private int lastChapterDelta = 0;

    /** 현재 Chapter 내 누적 변화량 (Chapter 종료 시 lastChapterDelta로 복사) */
    @Column(name = "running_delta", nullable = false)
    private int runningDelta = 0;

    /** 이 히로인이 등장한 총 씬 수 */
    @Column(name = "total_scenes", nullable = false)
    private int totalScenes = 0;

    /** 마지막 등장 시각 (최근 등장 히로인 판정용) */
    @Column(name = "last_appeared_at")
    private LocalDateTime lastAppearedAt;

    /** 이 히로인이 메인 히로인으로 확정되었는지 (Act 3 이후 수렴) */
    @Column(name = "confirmed_main", nullable = false)
    private boolean confirmedMain = false;

    /** 이번 Chapter의 하이라이트 씬 대사 (리포트 표시용) */
    @Column(name = "chapter_highlight_quote", columnDefinition = "TEXT")
    private String chapterHighlightQuote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static TheaterHeroineAffection create(ChatRoom room, Character character) {
        TheaterHeroineAffection a = new TheaterHeroineAffection();
        a.room = room;
        a.character = character;
        return a;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  호감도 조정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public int applyDelta(int delta) {
        this.affection = Math.max(-100, Math.min(100, this.affection + delta));
        this.runningDelta += delta;
        return this.affection;
    }

    public void recordAppearance(int sceneCount) {
        this.totalScenes += sceneCount;
        this.lastAppearedAt = LocalDateTime.now();
    }

    public void updateHighlightQuote(String quote) {
        this.chapterHighlightQuote = quote;
    }

    /**
     * Chapter 종료 시 호출: runningDelta → lastChapterDelta로 복사 후 러닝 리셋
     */
    public void sealChapterDelta() {
        this.lastChapterDelta = this.runningDelta;
        this.runningDelta = 0;
    }

    public void confirmAsMain() {
        this.confirmedMain = true;
    }

    public void resetMainFlag() {
        this.confirmedMain = false;
    }
}