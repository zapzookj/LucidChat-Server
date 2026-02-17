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
 * 채팅방 — 관계/상태 저장의 핵심 엔티티
 *
 * [Phase 4.1] 씬 상태 영속화 (bgmMode, location, outfit, timeOfDay)
 * [Phase 4.2]  관계 승급 이벤트 시스템
 *   - promotionPending:      승급 이벤트 진행 중 여부
 *   - pendingTargetStatus:   승급 목표 관계
 *   - promotionMoodScore:    누적 분위기 점수
 *   - promotionTurnCount:    이벤트 경과 턴 수
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

    public ChatRoom(User user, Character character) {
        this.user = user;
        this.character = character;
        this.affectionScore = 0;
        this.statusLevel = RelationStatus.STRANGER;
        this.lastActiveAt = LocalDateTime.now();
        this.lastEmotion = EmotionTag.NEUTRAL;
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

    /**
     * 승급 이벤트 시작
     */
    public void startPromotion(RelationStatus targetStatus) {
        this.promotionPending = true;
        this.pendingTargetStatus = targetStatus;
        this.promotionMoodScore = 0;
        this.promotionTurnCount = 0;
    }

    /**
     * 이벤트 턴 진행 — mood_score 누적
     */
    public void advancePromotionTurn(int moodScore) {
        this.promotionTurnCount++;
        this.promotionMoodScore += moodScore;
    }

    /**
     * 승급 성공 — 관계 업그레이드 + 이벤트 종료
     */
    public void completePromotionSuccess() {
        this.statusLevel = this.pendingTargetStatus;
        clearPromotion();
    }

    /**
     * 승급 실패 — 관계 유지 + 이벤트 종료
     */
    public void completePromotionFailure() {
        clearPromotion();
    }

    private void clearPromotion() {
        this.promotionPending = false;
        this.pendingTargetStatus = null;
        this.promotionMoodScore = 0;
        this.promotionTurnCount = 0;
    }

    /**
     * 엔딩 도달 마킹
     */
    public void markEndingReached(EndingType endingType) {
        this.endingReached = true;
        this.endingType = endingType;
    }

    /**
     * 엔딩 타이틀 저장
     */
    public void saveEndingTitle(String title) {
        this.endingTitle = title;
    }

    /**
     * 전체 초기화 (대화 삭제 시)
     */
    public void resetAll() {
        resetAffection();
        resetSceneState();
        clearPromotion();
        // [Phase 4.3] 엔딩 상태 초기화
        this.endingReached = false;
        this.endingType = null;
        this.endingTitle = null;
    }
}