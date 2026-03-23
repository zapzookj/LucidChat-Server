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
        @UniqueConstraint(name = "uk_user_character_mode", columnNames = {"user_id", "character_id", "chat_mode"})
    },
    indexes = {
        @Index(name = "idx_room_member", columnList = "user_id"),
        @Index(name = "idx_room_character", columnList = "character_id")
    })
/**
 * 채팅방 — 관계/상태 저장의 핵심 엔티티
 *
 * [Phase 5.5-P]  입체적 상태창 시스템 / 스탯 통합
 * [Phase 5.5-EV] 이벤트 시스템 강화:
 *   - topicConcluded: LLM이 판단한 주제 종료 플래그
 *   - eventActive / eventStatus: 디렉터 모드 이벤트 진행 상태
 *   - promotionWaitingForTopic: 임계값 도달 후 topic_concluded 대기
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

    @Enumerated(EnumType.STRING)
    @Column(name = "chat_mode", nullable = false, length = 20)
    private ChatMode chatMode = ChatMode.STORY;

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
    //  씬 상태 영속화
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
    //  관계 승급 이벤트
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

    /**
     * [Phase 5.5-EV] 임계값 도달 후 topic_concluded 대기 상태
     * true: 스탯이 승급 임계값에 도달했지만 아직 topic이 끝나지 않아 대기 중
     * topic_concluded=true가 오면 실제 promotionPending으로 전환
     */
    @Column(name = "promotion_waiting_for_topic", nullable = false)
    private boolean promotionWaitingForTopic = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "promotion_waiting_target", length = 30)
    private RelationStatus promotionWaitingTarget;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  엔딩 이벤트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "ending_reached", nullable = false)
    private boolean endingReached = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "ending_type", length = 10)
    private EndingType endingType;

    @Column(name = "ending_title", length = 100)
    private String endingTitle;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5] 입체적 스탯 시스템
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "stat_intimacy", nullable = false)
    private int statIntimacy = 0;

    @Column(name = "stat_affection", nullable = false)
    private int statAffection = 0;

    @Column(name = "stat_dependency", nullable = false)
    private int statDependency = 0;

    @Column(name = "stat_playfulness", nullable = false)
    private int statPlayfulness = 0;

    @Column(name = "stat_trust", nullable = false)
    private int statTrust = 0;

    @Column(name = "stat_lust", nullable = false)
    private int statLust = 0;

    @Column(name = "stat_corruption", nullable = false)
    private int statCorruption = 0;

    @Column(name = "stat_obsession", nullable = false)
    private int statObsession = 0;

    // ── 동적 관계 / 생각 / BPM ──

    @Column(name = "dynamic_relation_tag", length = 50)
    private String dynamicRelationTag;

    @Column(name = "character_thought", columnDefinition = "TEXT")
    private String characterThought;

    @Column(name = "thought_updated_at_turn", nullable = false)
    private int thoughtUpdatedAtTurn = 0;

    @Column(name = "current_bpm", nullable = false)
    private int currentBpm = 65;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-EV] 이벤트 시스템 강화
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** LLM이 마지막으로 보고한 topic_concluded 상태 */
    @Column(name = "topic_concluded", nullable = false)
    private boolean topicConcluded = false;

    /** 디렉터 모드 이벤트 진행 중 여부 */
    @Column(name = "event_active", nullable = false)
    private boolean eventActive = false;

    /** 디렉터 모드 이벤트 상태 ("ONGOING" | "RESOLVED" | null) */
    @Column(name = "event_status", length = 20)
    private String eventStatus;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  생성자
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public ChatRoom(User user, Character character, ChatMode chatMode) {
        this.user = user;
        this.character = character;
        this.chatMode = chatMode;
        this.affectionScore = 0;
        this.statusLevel = RelationStatus.STRANGER;
        this.lastActiveAt = LocalDateTime.now();
        this.lastEmotion = EmotionTag.NEUTRAL;
        this.currentBgmMode = BgmMode.DAILY;
        this.currentTimeOfDay = TimeOfDay.NIGHT;
        this.currentLocation = parseLocationOrDefault(character.getEffectiveDefaultLocation());
        this.currentOutfit = parseOutfitOrDefault(character.getEffectiveDefaultOutfit());
        this.dynamicRelationTag = "낯선 사람";
        this.currentBpm = 65;
    }

    public ChatRoom(User user, Character character) {
        this(user, character, ChatMode.STORY);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-P] 통합 호감도 접근자
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public int getAffectionScore() {
        return this.statAffection;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public boolean isStoryMode() { return this.chatMode == ChatMode.STORY; }
    public boolean isSandboxMode() { return this.chatMode == ChatMode.SANDBOX; }

    public void touch(EmotionTag lastEmotion) {
        this.lastActiveAt = LocalDateTime.now();
        this.lastEmotion = lastEmotion;
    }

    public void updateAffection(int newScore) {
        this.statAffection = clamp(-100, 100, newScore);
        this.affectionScore = this.statAffection;
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
        this.statAffection = 0;
        this.statusLevel = RelationStatus.STRANGER;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5] 스탯 시스템 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void applyNormalStatChanges(int dIntimacy, int dAffection,
                                       int dDependency, int dPlayfulness, int dTrust) {
        this.statIntimacy    = clamp(-100, 100, this.statIntimacy    + dIntimacy);
        this.statAffection   = clamp(-100, 100, this.statAffection   + dAffection);
        this.statDependency  = clamp(-100, 100, this.statDependency  + dDependency);
        this.statPlayfulness = clamp(-100, 100, this.statPlayfulness + dPlayfulness);
        this.statTrust       = clamp(-100, 100, this.statTrust       + dTrust);
        this.affectionScore = this.statAffection;
    }

    public void applySecretStatChanges(int dLust, int dCorruption, int dObsession) {
        this.statLust       = clamp(-100, 100, this.statLust       + dLust);
        this.statCorruption = clamp(-100, 100, this.statCorruption + dCorruption);
        this.statObsession  = clamp(-100, 100, this.statObsession  + dObsession);
    }

    public void applyLegacyAffectionChange(int delta) {
        if (delta == 0) return;
        this.statAffection = clamp(-100, 100, this.statAffection + delta);
        this.affectionScore = this.statAffection;
    }

    public int getMaxNormalStatValue() {
        return Math.max(statIntimacy, Math.max(statAffection,
            Math.max(statDependency, Math.max(statPlayfulness, statTrust))));
    }

    public int getMinNormalStatValue() {
        return Math.min(statIntimacy, Math.min(statAffection,
            Math.min(statDependency, Math.min(statPlayfulness, statTrust))));
    }

    public String getDominantStatName() {
        return RelationStatusPolicy.getDominantStat(
            statIntimacy, statAffection, statDependency, statPlayfulness, statTrust);
    }

    public boolean refreshRelationFromStats() {
        RelationStatus oldStatus = this.statusLevel;
        RelationStatus newStatus = RelationStatusPolicy.fromStats(
            this.statAffection,
            this.statIntimacy, this.statAffection,
            this.statDependency, this.statPlayfulness, this.statTrust
        );
        this.statusLevel = newStatus;
        String dominant = getDominantStatName();
        this.dynamicRelationTag = RelationStatusPolicy.buildDynamicRelationTag(newStatus, dominant);
        return oldStatus != newStatus;
    }

    public String checkEndingTrigger() {
        if (this.endingReached) return null;
        if (getMaxNormalStatValue() >= 100) return "HAPPY";
        if (getMinNormalStatValue() <= -100) return "BAD";
        return null;
    }

    public void updateBpm(int bpm) {
        this.currentBpm = clamp(60, 180, bpm);
    }

    public void updateCharacterThought(String thought, int currentTurnCount) {
        this.characterThought = thought;
        this.thoughtUpdatedAtTurn = currentTurnCount;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-EV] 이벤트 시스템 강화 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** LLM 응답의 topic_concluded 반영 */
    public void updateTopicConcluded(boolean concluded) {
        this.topicConcluded = concluded;
    }

    /** 디렉터 모드 이벤트 시작 */
    public void startDirectorEvent() {
        this.eventActive = true;
        this.eventStatus = "ONGOING";
        // 이벤트 시작 시 topic은 아직 진행 중
        this.topicConcluded = false;
    }

    /** 디렉터 모드 이벤트 상태 업데이트 (LLM 응답 기반) */
    public void updateEventStatus(String status) {
        if ("RESOLVED".equalsIgnoreCase(status)) {
            this.eventActive = false;
            this.eventStatus = "RESOLVED";
        } else if ("ONGOING".equalsIgnoreCase(status)) {
            this.eventStatus = "ONGOING";
        }
    }

    /** 이벤트 강제 종료 (초기화 등) */
    public void clearDirectorEvent() {
        this.eventActive = false;
        this.eventStatus = null;
    }

    // ── 승급 대기 (topic_concluded 게이팅) ──

    /**
     * [Phase 5.5-EV] 승급 임계값 도달 시 호출 — topic_concluded 대기 상태 진입
     */
    public void markPromotionWaiting(RelationStatus target) {
        this.promotionWaitingForTopic = true;
        this.promotionWaitingTarget = target;
    }

    /**
     * [Phase 5.5-EV] topic_concluded=true일 때 실제 승급 이벤트 개시
     * @return 승급이 시작되었으면 true
     */
    public boolean tryStartPromotionFromWaiting() {
        if (!this.promotionWaitingForTopic || this.promotionWaitingTarget == null) {
            return false;
        }
        // 대기 해제 → 실제 승급 이벤트 시작 (디렉터 모드)
        RelationStatus target = this.promotionWaitingTarget;
        clearPromotionWaiting();
        startPromotion(target);
        startDirectorEvent(); // 승급도 디렉터 모드로 진행
        return true;
    }

    private void clearPromotionWaiting() {
        this.promotionWaitingForTopic = false;
        this.promotionWaitingTarget = null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  씬 / 승급 / 엔딩 (기존 유지)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void updateSceneState(String bgmMode, String location, String outfit, String timeOfDay) {
        if (bgmMode != null) { try { this.currentBgmMode = BgmMode.valueOf(bgmMode); } catch (IllegalArgumentException ignored) {} }
        if (location != null) { try { this.currentLocation = Location.valueOf(location); } catch (IllegalArgumentException ignored) {} }
        if (outfit != null) { try { this.currentOutfit = Outfit.valueOf(outfit); } catch (IllegalArgumentException ignored) {} }
        if (timeOfDay != null) { try { this.currentTimeOfDay = TimeOfDay.valueOf(timeOfDay); } catch (IllegalArgumentException ignored) {} }
    }

    public void resetSceneState() {
        this.currentBgmMode = BgmMode.DAILY;
        this.currentLocation = parseLocationOrDefault(character.getEffectiveDefaultLocation());
        this.currentOutfit = parseOutfitOrDefault(character.getEffectiveDefaultOutfit());
        this.currentTimeOfDay = TimeOfDay.NIGHT;
    }

    public void startPromotion(RelationStatus targetStatus) {
        this.promotionPending = true;
        this.pendingTargetStatus = targetStatus;
        this.promotionMoodScore = 0;
        this.promotionTurnCount = 0;
    }

    /**
     * [Phase 5.5-EV] 승급 턴 진행 — mood_score를 5종 스탯 변화량 합산으로 대체
     * @param statDeltaSum 해당 턴의 5종 노말 스탯 변화량 절대값 합산
     */
    public void advancePromotionTurn(int statDeltaSum) {
        this.promotionTurnCount++;
        this.promotionMoodScore += statDeltaSum;
    }

    public void completePromotionSuccess() {
        this.statusLevel = this.pendingTargetStatus;
        clearPromotion();
        clearDirectorEvent(); // 승급 완료 → 디렉터 모드 종료
    }

    public void completePromotionFailure() {
        clearPromotion();
        clearDirectorEvent();
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

    public void saveEndingTitle(String title) { this.endingTitle = title; }

    public void resetAll() {
        resetAffection();
        resetSceneState();
        clearPromotion();
        clearPromotionWaiting();
        clearDirectorEvent();
        this.endingReached = false;
        this.endingType = null;
        this.endingTitle = null;
        this.statIntimacy = 0;
        this.statAffection = 0;
        this.statDependency = 0;
        this.statPlayfulness = 0;
        this.statTrust = 0;
        this.statLust = 0;
        this.statCorruption = 0;
        this.statObsession = 0;
        this.dynamicRelationTag = "낯선 사람";
        this.characterThought = null;
        this.thoughtUpdatedAtTurn = 0;
        this.currentBpm = 65;
        this.topicConcluded = false;
    }

    private int clamp(int min, int max, int v) { return Math.max(min, Math.min(max, v)); }
    private static Location parseLocationOrDefault(String v) { try { return Location.valueOf(v); } catch (Exception e) { return Location.ENTRANCE; } }
    private static Outfit parseOutfitOrDefault(String v) { try { return Outfit.valueOf(v); } catch (Exception e) { return Outfit.MAID; } }
}