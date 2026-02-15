package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.enums.*;
import com.spring.aichat.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "chat_rooms",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_member_character", columnNames = {"user_id", "character_id"})
    },
    indexes = {
        @Index(name = "idx_room_member", columnList = "user_id"),
        @Index(name = "idx_room_character", columnList = "character_id")
    })
/**
 * 채팅방(관계/상태 저장의 핵심 엔티티)
 * - affectionScore, statusLevel로 동적 프롬프트를 구성한다.
 *
 * [Phase 4.1] 씬 상태 영속화:
 * - currentBgmMode, currentLocation, currentOutfit, currentTimeOfDay
 * - BGM 관성 시스템: LLM에게 현재 상태를 알려주어 불필요한 전환 방지
 * - 재접속 복원: 유저가 재진입 시 마지막 씬 상태로 복원
 */
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    private Character character;

    @Column(name = "affection_score", nullable = false)
    private int affectionScore = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_level", nullable = false, length = 30)
    private RelationStatus statusLevel = RelationStatus.STRANGER;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_emotion", length = 30)
    private EmotionTag lastEmotion;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4.1] 씬 상태 영속화 필드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Enumerated(EnumType.STRING)
    @Column(name = "current_bgm_mode", length = 20)
    private BgmMode currentBgmMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_location", length = 20)
    private Location currentLocation;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_outfit", length = 20)
    private Outfit currentOutfit;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_time_of_day", length = 20)
    private TimeOfDay currentTimeOfDay;

    public ChatRoom(User user, Character character) {
        this.user = user;
        this.character = character;
        this.affectionScore = 0;
        this.statusLevel = RelationStatus.STRANGER;
        this.lastActiveAt = LocalDateTime.now();
        this.lastEmotion = EmotionTag.NEUTRAL;
        // 씬 초기값
        this.currentBgmMode = BgmMode.DAILY;
        this.currentLocation = Location.ENTRANCE;
        this.currentOutfit = Outfit.MAID;
        this.currentTimeOfDay = TimeOfDay.NIGHT;
    }

    public void touch(EmotionTag lastEmotion) {
        this.lastActiveAt = LocalDateTime.now();
        this.lastEmotion = lastEmotion;
    }

    public void applyAffectionDelta(int delta) {
        this.affectionScore = clamp(0, 100, this.affectionScore + delta);
        this.statusLevel = RelationStatusPolicy.fromScore(this.affectionScore);
    }

    private int clamp(int min, int max, int v) {
        return Math.max(min, Math.min(max, v));
    }

    public void updateAffection(int newScore) {
        this.affectionScore = newScore;
    }

    public void updateStatusLevel(RelationStatus relationStatus) {
        this.statusLevel = relationStatus;
    }

    public void updateLastActive(EmotionTag emotion) {
        this.lastActiveAt = LocalDateTime.now();
        this.lastEmotion = emotion;
    }

    public void resetAffection() {
        this.affectionScore = 0;
        this.statusLevel = RelationStatus.STRANGER;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4.1] 씬 상태 갱신
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * LLM 응답의 마지막 씬에서 non-null 필드만 갱신 (null = 유지)
     */
    public void updateSceneState(String bgmMode, String location, String outfit, String timeOfDay) {
        if (bgmMode != null) {
            try { this.currentBgmMode = BgmMode.valueOf(bgmMode); } catch (IllegalArgumentException ignored) {}
        }
        if (location != null) {
            try { this.currentLocation = Location.valueOf(location); } catch (IllegalArgumentException ignored) {}
        }
        if (outfit != null) {
            try { this.currentOutfit = Outfit.valueOf(outfit); } catch (IllegalArgumentException ignored) {}
        }
        if (timeOfDay != null) {
            try { this.currentTimeOfDay = TimeOfDay.valueOf(timeOfDay); } catch (IllegalArgumentException ignored) {}
        }
    }

    /**
     * 채팅 기록 초기화 시 씬 상태도 리셋
     */
    public void resetSceneState() {
        this.currentBgmMode = BgmMode.DAILY;
        this.currentLocation = Location.ENTRANCE;
        this.currentOutfit = Outfit.MAID;
        this.currentTimeOfDay = TimeOfDay.NIGHT;
    }
}