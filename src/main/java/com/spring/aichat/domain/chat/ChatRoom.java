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
        // [Phase 4.5] 동일 유저 + 동일 캐릭터 + 동일 모드 조합 중복 방지
        @UniqueConstraint(name = "uk_user_character_mode", columnNames = {"user_id", "character_id", "chat_mode"})
    },
    indexes = {
        @Index(name = "idx_room_member", columnList = "user_id"),
        @Index(name = "idx_room_character", columnList = "character_id")
    })
/**
 * 채팅방 — 관계/상태 저장의 핵심 엔티티
 *
 * [Phase 4.1] 씬 상태 영속화 (bgmMode, location, outfit, timeOfDay)
 * [Phase 4.2] 관계 승급 이벤트 시스템
 * [Phase 4.5] 모드 분리 (STORY / SANDBOX)
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4.5] 채팅 모드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Enumerated(EnumType.STRING)
    @Column(name = "chat_mode", nullable = false, length = 20)
    private ChatMode chatMode = ChatMode.STORY;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
    //  [Phase 4.1] 씬 상태 영속화
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4.2] 관계 승급 이벤트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "promotion_pending", nullable = false)
    private boolean promotionPending = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "pending_target_status", length = 30)
    private RelationStatus pendingTargetStatus;

    @Column(name = "promotion_mood_score", nullable = false)
    private int promotionMoodScore = 0;

    @Column(name = "promotion_turn_count", nullable = false)
    private int promotionTurnCount = 0;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 4.3] 엔딩 이벤트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "ending_reached", nullable = false)
    private boolean endingReached = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "ending_type", length = 10)
    private EndingType endingType;

    @Column(name = "ending_title", length = 100)
    private String endingTitle;

    /**
     * [Phase 4.5] 모드를 포함한 생성자
     */
    public ChatRoom(User user, Character character, ChatMode chatMode) {
        this.user = user;
        this.character = character;
        this.chatMode = chatMode;
        this.affectionScore = 0;
        this.statusLevel = RelationStatus.STRANGER;
        this.lastActiveAt = LocalDateTime.now();
        this.lastEmotion = EmotionTag.NEUTRAL;
        this.currentBgmMode = BgmMode.DAILY;
        this.currentLocation = Location.ENTRANCE;
        this.currentOutfit = Outfit.MAID;
        this.currentTimeOfDay = TimeOfDay.NIGHT;
    }

    /**
     * 기존 호환성 유지 — 기본 모드 STORY
     */
    public ChatRoom(User user, Character character) {
        this(user, character, ChatMode.STORY);
    }

    /**
     * [Phase 4.5] 스토리 모드 여부 판별
     */
    public boolean isStoryMode() {
        return this.chatMode == ChatMode.STORY;
    }

    /**
     * [Phase 4.5] 샌드박스 모드 여부 판별
     */
    public boolean isSandboxMode() {
        return this.chatMode == ChatMode.SANDBOX;
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

    // ── 씬 상태 ──

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

    public void resetSceneState() {
        this.currentBgmMode = BgmMode.DAILY;
        this.currentLocation = Location.ENTRANCE;
        this.currentOutfit = Outfit.MAID;
        this.currentTimeOfDay = TimeOfDay.NIGHT;
    }

    // ── 관계 승급 이벤트 ──

    public void startPromotion(RelationStatus targetStatus) {
        this.promotionPending = true;
        this.pendingTargetStatus = targetStatus;
        this.promotionMoodScore = 0;
        this.promotionTurnCount = 0;
    }

    public void advancePromotionTurn(int moodScore) {
        this.promotionTurnCount++;
        this.promotionMoodScore += moodScore;
    }

    public void completePromotionSuccess() {
        this.statusLevel = this.pendingTargetStatus;
        clearPromotion();
    }

    public void completePromotionFailure() {
        clearPromotion();
    }

    private void clearPromotion() {
        this.promotionPending = false;
        this.pendingTargetStatus = null;
        this.promotionMoodScore = 0;
        this.promotionTurnCount = 0;
    }

    public void markEndingReached(EndingType endingType) {
        this.endingReached = true;
        this.endingType = endingType;
    }

    public void saveEndingTitle(String title) {
        this.endingTitle = title;
    }

    public void resetAll() {
        resetAffection();
        resetSceneState();
        clearPromotion();
        this.endingReached = false;
        this.endingType = null;
        this.endingTitle = null;
    }
}