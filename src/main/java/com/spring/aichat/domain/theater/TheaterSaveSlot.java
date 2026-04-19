package com.spring.aichat.domain.theater;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.spring.aichat.domain.chat.ChatRoom;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [Phase 5.5-Theater] 세이브/로드 슬롯
 *
 * Theater 세션의 스냅샷을 5개 슬롯에 저장할 수 있다.
 * + Quick Save 슬롯 1개 (분기 직전 자동 저장)
 *
 * [스냅샷 구성 (snapshot_json)]
 * {
 *   "theaterState": { ... TheaterState 필드 전체 ... },
 *   "heroineAffections": [ { characterId, affection, totalScenes, ... }, ... ],
 *   "chatLogCursor": "<MongoDB 마지막 log ID>",
 *   "totalChatLogCount": 123,
 *   "savedAt": "ISO-8601"
 * }
 *
 * [스냅샷의 범위]
 * - ChatRoom 엔티티 전체를 복제하지 않고, Theater 관련 상태만 저장
 * - ChatLog는 MongoDB에 있으며 커서 시점까지 되감는 방식이 아닌 "append-only 참조"
 * - 로드 시: TheaterState 필드 복구 + 스냅샷 시점 이후 로그를 "스킵 표시"
 *   (실제 로그 삭제는 하지 않음 — 로그의 불변성 유지)
 * - 대신 프론트 렌더링 시 스냅샷 이후 로그는 "다른 타임라인"으로 취급
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "theater_save_slots",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_theater_save_slot",
        columnNames = {"room_id", "slot_number"}
    ),
    indexes = {
        @Index(name = "idx_theater_save_room", columnList = "room_id")
    })
public class TheaterSaveSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;

    /**
     * 슬롯 번호.
     * 1~5: 수동 저장 슬롯
     * 0:   Quick Save (자동)
     */
    @Column(name = "slot_number", nullable = false)
    private int slotNumber;

    /** 유저가 붙인 이름 (null이면 자동 생성: "Act 2 - Chapter 3") */
    @Column(name = "label", length = 100)
    private String label;

    /** 썸네일용 — 저장 시점의 대표 씬 대사 */
    @Column(name = "preview_text", length = 500)
    private String previewText;

    /** 저장 시점의 Act/Chapter (UI 표시용) */
    @Column(name = "act_number", nullable = false)
    private int actNumber;

    @Column(name = "chapter_number", nullable = false)
    private int chapterNumber;

    /** 저장 시점의 리드 히로인 (있다면) */
    @Column(name = "lead_heroine_id")
    private Long leadHeroineId;

    /** 전체 스냅샷 JSON */
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "LONGTEXT")
    private String snapshotJson;

    /** Quick Save 여부 */
    @Column(name = "is_quick_save", nullable = false)
    private boolean quickSave = false;

    @Column(name = "saved_at", nullable = false)
    private LocalDateTime savedAt;

    @PrePersist
    void prePersist() {
        if (savedAt == null) savedAt = LocalDateTime.now();
    }

    public static TheaterSaveSlot create(ChatRoom room, int slotNumber, String label,
                                         String previewText, int actNumber, int chapterNumber,
                                         Long leadHeroineId, String snapshotJson, boolean quickSave) {
        TheaterSaveSlot s = new TheaterSaveSlot();
        s.room = room;
        s.slotNumber = slotNumber;
        s.label = label;
        s.previewText = previewText;
        s.actNumber = actNumber;
        s.chapterNumber = chapterNumber;
        s.leadHeroineId = leadHeroineId;
        s.snapshotJson = snapshotJson;
        s.quickSave = quickSave;
        return s;
    }

    public void overwrite(String label, String previewText, int actNumber, int chapterNumber,
                          Long leadHeroineId, String snapshotJson) {
        this.label = label;
        this.previewText = previewText;
        this.actNumber = actNumber;
        this.chapterNumber = chapterNumber;
        this.leadHeroineId = leadHeroineId;
        this.snapshotJson = snapshotJson;
        this.savedAt = LocalDateTime.now();
    }
}