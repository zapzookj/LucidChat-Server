package com.spring.aichat.domain.theater;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.BranchLevel;
import com.spring.aichat.domain.enums.TheaterAct;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [Phase 5.5-Theater] 분기 선택 이력 엔티티
 *
 * 유저가 Theater 진행 중 내린 모든 분기 선택을 기록.
 *
 * [용도]
 * - 엔딩 크레딧 "당신의 선택들" 섹션
 * - 재플레이 시 이전 선택 대비 표시
 * - 감독 노트 자동 캡처
 * - 통계 수집 (어떤 선택지를 많이 고르는지 분석)
 *
 * [스키마 특성]
 * - options_json에 해당 분기의 전체 선택지를 스냅샷 저장
 *   → 프롬프트가 바뀌어도 과거 분기 재현 가능
 * - related_heroine_id: 장소 선택 분기의 경우 선택된 히로인
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "theater_branch_choices",
    indexes = {
        @Index(name = "idx_theater_branch_room", columnList = "room_id"),
        @Index(name = "idx_theater_branch_act", columnList = "room_id, act_number, chapter_number")
    })
public class TheaterBranchChoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;

    @Enumerated(EnumType.STRING)
    @Column(name = "branch_level", nullable = false, length = 20)
    private BranchLevel branchLevel;

    /** Act 번호 (1~4) */
    @Column(name = "act_number", nullable = false)
    private int actNumber;

    /** Chapter 번호 (Act 내 1부터) */
    @Column(name = "chapter_number", nullable = false)
    private int chapterNumber;

    /** 분기 발생 시점의 Scene 시퀀스 */
    @Column(name = "scene_sequence", nullable = false)
    private long sceneSequence;

    /**
     * 전체 선택지 JSON (스냅샷)
     * [
     *   { "label": "...", "detail": "...", "tone": "normal", "energyCost": 0, "statGate": {...}, "locked": false },
     *   ...
     * ]
     */
    @Column(name = "options_json", nullable = false, columnDefinition = "TEXT")
    private String optionsJson;

    /** 유저가 선택한 인덱스 (0부터) */
    @Column(name = "chosen_index", nullable = false)
    private int chosenIndex;

    /** 선택지의 라벨 (검색/표시용 non-normalized) */
    @Column(name = "chosen_label", length = 200)
    private String chosenLabel;

    /** 장소 선택/히로인 관련 분기에서 선택된 히로인 ID (nullable) */
    @Column(name = "related_heroine_id")
    private Long relatedHeroineId;

    /** 소모된 에너지 */
    @Column(name = "energy_spent", nullable = false)
    private int energySpent = 0;

    @Column(name = "chosen_at", nullable = false, updatable = false)
    private LocalDateTime chosenAt;

    @PrePersist
    void prePersist() {
        this.chosenAt = LocalDateTime.now();
    }

    public static TheaterBranchChoice record(
        ChatRoom room, BranchLevel level, TheaterAct act, int chapterNumber,
        long sceneSequence, String optionsJson, int chosenIndex, String chosenLabel,
        Long relatedHeroineId, int energySpent
    ) {
        TheaterBranchChoice c = new TheaterBranchChoice();
        c.room = room;
        c.branchLevel = level;
        c.actNumber = act.getNumber();
        c.chapterNumber = chapterNumber;
        c.sceneSequence = sceneSequence;
        c.optionsJson = optionsJson;
        c.chosenIndex = chosenIndex;
        c.chosenLabel = chosenLabel;
        c.relatedHeroineId = relatedHeroineId;
        c.energySpent = energySpent;
        return c;
    }
}