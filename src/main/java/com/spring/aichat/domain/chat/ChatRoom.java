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
 * [Phase 5.5]   입체적 상태창 시스템
 * [Phase 5.5-P] 피드백 반영 패치:
 *   - affectionScore ↔ statAffection 통합 (같은 값)
 *   - 스탯 범위: -100 ~ 100 (음수 허용)
 *   - getAffectionScore() → statAffection 반환
 *   - 엔딩 트리거: 5개 노말 스탯 중 하나라도 ±100 도달
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

    /**
     * [Phase 5.5-P] 레거시 호감도 필드 — statAffection과 항상 동기화
     * DB 컬럼은 유지 (마이그레이션 호환), 값은 statAffection과 동일
     */
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
    //  [Phase 5.5-P] 범위: -100 ~ 100 (음수 허용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // ── 노말 모드 스탯 (-100 ~ 100) ──

    @Column(name = "stat_intimacy", nullable = false)
    private int statIntimacy = 0;

    /** [Phase 5.5-P] affectionScore와 통합 — 이 필드가 곧 호감도 */
    @Column(name = "stat_affection", nullable = false)
    private int statAffection = 0;

    @Column(name = "stat_dependency", nullable = false)
    private int statDependency = 0;

    @Column(name = "stat_playfulness", nullable = false)
    private int statPlayfulness = 0;

    @Column(name = "stat_trust", nullable = false)
    private int statTrust = 0;

    // ── 시크릿 모드 전용 스탯 (-100 ~ 100) ──

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
    //  affectionScore는 항상 statAffection과 동일
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 호감도 조회 — statAffection 반환 (두 값은 항상 동기화)
     */
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

    /**
     * [Phase 5.5-P] 호감도 직접 변경 — statAffection + affectionScore 동시 갱신
     */
    public void updateAffection(int newScore) {
        this.statAffection = clamp(-100, 100, newScore);
        this.affectionScore = this.statAffection; // 동기화
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
    //  [Phase 5.5-P] 범위 -100 ~ 100, affection 동기화
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 노말 모드 스탯 변화 적용 (각 스탯 -100~100 클램프)
     * affection 변경 시 affectionScore도 자동 동기화
     */
    public void applyNormalStatChanges(int dIntimacy, int dAffection,
                                       int dDependency, int dPlayfulness, int dTrust) {
        this.statIntimacy    = clamp(-100, 100, this.statIntimacy    + dIntimacy);
        this.statAffection   = clamp(-100, 100, this.statAffection   + dAffection);
        this.statDependency  = clamp(-100, 100, this.statDependency  + dDependency);
        this.statPlayfulness = clamp(-100, 100, this.statPlayfulness + dPlayfulness);
        this.statTrust       = clamp(-100, 100, this.statTrust       + dTrust);

        // [Phase 5.5-P] 레거시 동기화
        this.affectionScore = this.statAffection;
    }

    public void applySecretStatChanges(int dLust, int dCorruption, int dObsession) {
        this.statLust       = clamp(-100, 100, this.statLust       + dLust);
        this.statCorruption = clamp(-100, 100, this.statCorruption + dCorruption);
        this.statObsession  = clamp(-100, 100, this.statObsession  + dObsession);
    }

    /**
     * [Phase 5.5-P] 레거시 affection_change를 statAffection에 적용
     * ChatService에서 기존 applyAffectionChange 대신 사용
     */
    public void applyLegacyAffectionChange(int delta) {
        if (delta == 0) return;
        this.statAffection = clamp(-100, 100, this.statAffection + delta);
        this.affectionScore = this.statAffection;
    }

    public int getMaxNormalStatValue() {
        return Math.max(statIntimacy,
            Math.max(statAffection,
                Math.max(statDependency,
                    Math.max(statPlayfulness, statTrust))));
    }

    /**
     * [Phase 5.5-P] 5개 노말 스탯 중 최솟값 (배드 엔딩 판정용)
     */
    public int getMinNormalStatValue() {
        return Math.min(statIntimacy,
            Math.min(statAffection,
                Math.min(statDependency,
                    Math.min(statPlayfulness, statTrust))));
    }

    public String getDominantStatName() {
        return RelationStatusPolicy.getDominantStat(
            statIntimacy, statAffection, statDependency, statPlayfulness, statTrust);
    }

    /**
     * 스탯 기반 statusLevel + dynamicRelationTag 갱신
     * @return 관계 레벨이 변경되었으면 true
     */
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

    /**
     * [Phase 5.5-P] 엔딩 트리거 판정
     * @return "HAPPY" | "BAD" | null
     */
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

    public void advancePromotionTurn(int moodScore) {
        this.promotionTurnCount++;
        this.promotionMoodScore += moodScore;
    }

    public void completePromotionSuccess() {
        this.statusLevel = this.pendingTargetStatus;
        clearPromotion();
    }

    public void completePromotionFailure() { clearPromotion(); }

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
    }

    private int clamp(int min, int max, int v) { return Math.max(min, Math.min(max, v)); }
    private static Location parseLocationOrDefault(String v) { try { return Location.valueOf(v); } catch (Exception e) { return Location.ENTRANCE; } }
    private static Outfit parseOutfitOrDefault(String v) { try { return Outfit.valueOf(v); } catch (Exception e) { return Outfit.MAID; } }
}