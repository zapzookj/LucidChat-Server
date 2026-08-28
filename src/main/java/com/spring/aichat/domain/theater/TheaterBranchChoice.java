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

    /**
     * [적대적 리뷰 P1-2 / P2-b · V29] 이 선택이 확정한 <b>오퍼의 출처 배치 id</b>.
     * 씬 분기는 분기 신호를 실은 배치 id, LOCATION은 -1(배치가 아니라 Chapter 진입에 묶인다).
     *
     * <p><b>왜 컬럼을 새로 다는가</b> — 중복 확정을 DB가 거부하게 하려면 유니크 키가 필요한데,
     * {@code (room, act, chapter, branch_level)}만으로는 안 된다. MINOR는 한 Chapter에
     * <b>정상적으로 3~4회</b> 발생하기 때문이다(TheaterDirectorEngine.decideBranchAfterBatch §4).
     * 분기 1회를 유일하게 식별하는 축은 "그 분기를 실은 배치"이므로 이 값을 키에 넣는다.
     *
     * <p><b>nullable인 이유</b> — 배포 이전 적재분은 출처 배치를 알 수 없다. PostgreSQL의
     * 유니크 인덱스는 NULL을 서로 다른 값으로 취급하므로, 레거시 행은 NULL로 두면
     * 인덱스 생성이 기존 데이터 때문에 실패하는 일이 없다(V28과 같은 취급).
     */
    @Column(name = "source_batch_id")
    private Integer sourceBatchId;

    @Column(name = "chosen_at", nullable = false, updatable = false)
    private LocalDateTime chosenAt;

    @PrePersist
    void prePersist() {
        this.chosenAt = LocalDateTime.now();
    }

    /**
     * ⚠ [CLAUDE.md §2-6] sourceBatchId를 받지 않는 구 시그니처 오버로드를 남기지 않는다.
     *   남기면 호출부가 조용히 sourceBatchId=null로 기록해 중복 확정 유니크 키가 무력화된다.
     */
    public static TheaterBranchChoice record(
        ChatRoom room, BranchLevel level, TheaterAct act, int chapterNumber,
        long sceneSequence, String optionsJson, int chosenIndex, String chosenLabel,
        Long relatedHeroineId, int energySpent, Integer sourceBatchId
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
        c.sourceBatchId = sourceBatchId;
        return c;
    }
}