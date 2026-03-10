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
 * [Phase 4.1] 씬 상태 영속화 (bgmMode, location, outfit, timeOfDay)
 * [Phase 4.2] 관계 승급 이벤트 시스템
 * [Phase 4.5] 모드 분리 (STORY / SANDBOX)
 * [Phase 5.5] 입체적 상태창 시스템
 *             - 5개 노말 스탯 + 3개 시크릿 스탯 (0~100, 레이더 차트)
 *             - 동적 관계 태그 (최고 스탯 기반)
 *             - 캐릭터의 생각 (10턴마다 갱신)
 *             - 심박수 BPM (호감도 연동 + 대화 텐션)
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5] 입체적 스탯 시스템
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // ── 노말 모드 스탯 (0~100) ──

    /** 친밀도 — 일상 대화/공감 */
    @Column(name = "stat_intimacy", nullable = false)
    private int statIntimacy = 0;

    /** 호감도(설렘) — 플러팅/로맨틱 행동 */
    @Column(name = "stat_affection", nullable = false)
    private int statAffection = 0;

    /** 의존도 — 유저가 캐릭터를 리드/챙김 */
    @Column(name = "stat_dependency", nullable = false)
    private int statDependency = 0;

    /** 장난기 — 농담/티키타카 */
    @Column(name = "stat_playfulness", nullable = false)
    private int statPlayfulness = 0;

    /** 신뢰도 — 유저의 신뢰되는 행동 */
    @Column(name = "stat_trust", nullable = false)
    private int statTrust = 0;

    // ── 시크릿 모드 전용 스탯 (0~100) ──

    /** 음란도 — 성적 텐션/스킨십 개방성 */
    @Column(name = "stat_lust", nullable = false)
    private int statLust = 0;

    /** 타락도 — 원래 정체성에서 벗어나는 정도 */
    @Column(name = "stat_corruption", nullable = false)
    private int statCorruption = 0;

    /** 집착도 — 유저를 독점하려는 얀데레 성향 */
    @Column(name = "stat_obsession", nullable = false)
    private int statObsession = 0;

    // ── 동적 관계 태그 ──

    /** 동적 관계 태그 (예: "좋은 말동무", "썸", "사랑스러운 연인") */
    @Column(name = "dynamic_relation_tag", length = 50)
    private String dynamicRelationTag;

    // ── 캐릭터의 생각 ──

    /** 유저에 대한 캐릭터의 현재 생각 (10턴마다 갱신) */
    @Column(name = "character_thought", columnDefinition = "TEXT")
    private String characterThought;

    /** 마지막으로 생각을 갱신한 시점의 유저 턴 수 */
    @Column(name = "thought_updated_at_turn", nullable = false)
    private int thoughtUpdatedAtTurn = 0;

    // ── 심박수 ──

    /** 현재 심박수 BPM (60~180) */
    @Column(name = "current_bpm", nullable = false)
    private int currentBpm = 65;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  생성자
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [Phase 4.5] 모드를 포함한 생성자
     * [Phase 5]   캐릭터별 기본 복장/장소 적용
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
        this.currentTimeOfDay = TimeOfDay.NIGHT;

        // [Phase 5] 캐릭터별 기본 장소/복장
        this.currentLocation = parseLocationOrDefault(character.getEffectiveDefaultLocation());
        this.currentOutfit = parseOutfitOrDefault(character.getEffectiveDefaultOutfit());

        // [Phase 5.5] 초기 상태
        this.dynamicRelationTag = "낯선 사람";
        this.currentBpm = 65;
    }

    /** 기존 호환성 유지 — 기본 모드 STORY */
    public ChatRoom(User user, Character character) {
        this(user, character, ChatMode.STORY);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  모드 판별
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public boolean isStoryMode() {
        return this.chatMode == ChatMode.STORY;
    }

    public boolean isSandboxMode() {
        return this.chatMode == ChatMode.SANDBOX;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  기존 호감도/관계 메서드 (하위 호환)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void touch(EmotionTag lastEmotion) {
        this.lastActiveAt = LocalDateTime.now();
        this.lastEmotion = lastEmotion;
    }

    public void applyAffectionDelta(int delta) {
        this.affectionScore = clamp(-100, 100, this.affectionScore + delta);
        // [Phase 5.5] statusLevel은 이제 스탯 기반으로 결정하므로 여기서는 갱신하지 않음
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
    //  [Phase 5.5] 스탯 시스템 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 노말 모드 스탯 변화 적용 (각 스탯 0~100 클램프)
     */
    public void applyNormalStatChanges(int dIntimacy, int dAffection,
                                       int dDependency, int dPlayfulness, int dTrust) {
        this.statIntimacy    = clamp(0, 100, this.statIntimacy    + dIntimacy);
        this.statAffection   = clamp(0, 100, this.statAffection   + dAffection);
        this.statDependency  = clamp(0, 100, this.statDependency  + dDependency);
        this.statPlayfulness = clamp(0, 100, this.statPlayfulness + dPlayfulness);
        this.statTrust       = clamp(0, 100, this.statTrust       + dTrust);
    }

    /**
     * 시크릿 모드 스탯 변화 적용 (각 스탯 0~100 클램프)
     */
    public void applySecretStatChanges(int dLust, int dCorruption, int dObsession) {
        this.statLust       = clamp(0, 100, this.statLust       + dLust);
        this.statCorruption = clamp(0, 100, this.statCorruption + dCorruption);
        this.statObsession  = clamp(0, 100, this.statObsession  + dObsession);
    }

    /**
     * 5개 노말 스탯 중 최대값 반환
     */
    public int getMaxNormalStatValue() {
        return Math.max(statIntimacy,
            Math.max(statAffection,
                Math.max(statDependency,
                    Math.max(statPlayfulness, statTrust))));
    }

    /**
     * 최고 노말 스탯 이름 반환 (동적 관계 태그 결정용)
     */
    public String getDominantStatName() {
        return RelationStatusPolicy.getDominantStat(
            statIntimacy, statAffection, statDependency, statPlayfulness, statTrust);
    }

    /**
     * 스탯 기반으로 statusLevel + dynamicRelationTag 갱신
     *
     * @return 관계 레벨이 변경되었으면 true
     */
    public boolean refreshRelationFromStats() {
        RelationStatus oldStatus = this.statusLevel;

        // 스탯 기반 관계 판정 (affectionScore < 0 이면 ENEMY)
        RelationStatus newStatus = RelationStatusPolicy.fromStats(
            this.affectionScore,
            this.statIntimacy, this.statAffection,
            this.statDependency, this.statPlayfulness, this.statTrust
        );
        this.statusLevel = newStatus;

        // 동적 관계 태그 갱신
        String dominant = getDominantStatName();
        this.dynamicRelationTag = RelationStatusPolicy.buildDynamicRelationTag(newStatus, dominant);

        return oldStatus != newStatus;
    }

    /**
     * BPM 갱신
     */
    public void updateBpm(int bpm) {
        this.currentBpm = clamp(60, 180, bpm);
    }

    /**
     * 캐릭터의 생각 갱신
     */
    public void updateCharacterThought(String thought, int currentTurnCount) {
        this.characterThought = thought;
        this.thoughtUpdatedAtTurn = currentTurnCount;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  씬 상태
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
        this.currentLocation = parseLocationOrDefault(character.getEffectiveDefaultLocation());
        this.currentOutfit = parseOutfitOrDefault(character.getEffectiveDefaultOutfit());
        this.currentTimeOfDay = TimeOfDay.NIGHT;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  관계 승급 이벤트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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

        // [Phase 5.5] 스탯 초기화
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Private helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private int clamp(int min, int max, int v) {
        return Math.max(min, Math.min(max, v));
    }

    private static Location parseLocationOrDefault(String value) {
        try { return Location.valueOf(value); }
        catch (Exception e) { return Location.ENTRANCE; }
    }

    private static Outfit parseOutfitOrDefault(String value) {
        try { return Outfit.valueOf(value); }
        catch (Exception e) { return Outfit.MAID; }
    }
}