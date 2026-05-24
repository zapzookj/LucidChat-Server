package com.spring.aichat.domain.heroine;

import com.spring.aichat.domain.chat.ChatRoom;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [V2 Story] 캐릭터별 현재 위치 추적
 *
 * <p>V2의 *위치 기반 라우팅*의 핵심 데이터. 매 턴 디렉터가
 * {@code character_movements}로 위치 변경을 출력하면 갱신되며, 백엔드 라우팅은
 * {@code chars_at(currentUserLocationKey)}로 같은 공간 캐릭터를 결정한다.
 *
 * <p>[자동 초기화]
 * StoryCreateFlow에서 히로인 선택 시 {@link com.spring.aichat.domain.character.CharacterRoutine}
 * 의 확률 기반 위치로 초기화. 또는 시작 시점의 시간대(DayPart)와 캐릭터 루틴 매칭.
 *
 * <p>[locationKey 의미]
 * - 일반적: {@code WorldLocation.locationKey} 참조
 * - 자유 텍스트: 디렉터가 *루틴에 없는 임시 장소*로 이동시킨 경우 임의 키
 *   (예: "GARDEN_FOUNTAIN_NEAR"). 이 경우에도 currentUserLocationKey와의 매칭은
 *   *정확 일치 또는 백엔드 alias 룩업*으로 처리.
 *
 * <p>[같은 공간 판정]
 * {@code character.currentLocationKey.equals(room.currentUserLocationKey)} 단순 매칭.
 * 디렉터 prompt [3] PRESENT SCENE에 같은 공간 캐릭터 목록 자동 주입.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "character_presences",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_presence_room_char",
            columnNames = {"chat_room_id", "character_id"})
    },
    indexes = {
        @Index(name = "idx_presence_room_loc",
            columnList = "chat_room_id, current_location_key")
    })
public class CharacterPresence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    /** Character.id 참조. FK 미설정 — 캐릭터 삭제 시 cascade 부담 회피 (실제 삭제 안 함). */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /**
     * 현재 위치. {@code WorldLocation.locationKey} 매칭 또는 자유 텍스트.
     * {@code chatRoom.currentUserLocationKey}와 비교하여 *같은 공간 여부* 판정.
     */
    @Column(name = "current_location_key", nullable = false, length = 50)
    private String currentLocationKey;

    /** 마지막 위치 변경 시점. 루틴 재추정 또는 회상 묘사용. */
    @Column(name = "last_moved_at", nullable = false)
    private LocalDateTime lastMovedAt;

    @PrePersist
    void prePersist() {
        if (this.lastMovedAt == null) {
            this.lastMovedAt = LocalDateTime.now();
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static CharacterPresence create(ChatRoom chatRoom, Long characterId, String initialLocationKey) {
        CharacterPresence p = new CharacterPresence();
        p.chatRoom = chatRoom;
        p.characterId = characterId;
        p.currentLocationKey = initialLocationKey;
        p.lastMovedAt = LocalDateTime.now();
        return p;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  위치 이동
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void moveTo(String newLocationKey) {
        if (newLocationKey == null || newLocationKey.isBlank()) return;
        if (newLocationKey.equals(this.currentLocationKey)) return;
        this.currentLocationKey = newLocationKey;
        this.lastMovedAt = LocalDateTime.now();
    }

    public boolean isAt(String locationKey) {
        return this.currentLocationKey != null && this.currentLocationKey.equals(locationKey);
    }
}