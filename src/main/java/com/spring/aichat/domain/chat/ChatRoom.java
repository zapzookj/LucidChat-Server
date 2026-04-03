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

    /** [Phase 5.5-Director] 마지막 디렉터 개입 턴 (서버 메모리/Redis와 별개로 DB 영속화) */
    @Column(name = "last_director_turn", nullable = false)
    private long lastDirectorTurn = 0;

    /**
     * [Phase 5.5-Director] 현재 활성화된 디렉터 인터루드의 actor_constraint
     * 인터루드 나레이션을 유저에게 보여준 뒤, 다음 액터 호출 시 이 값을 주입.
     * 소비 후 null로 클리어.
     */
    @Column(name = "active_director_constraint", columnDefinition = "TEXT")
    private String activeDirectorConstraint;

    /**
     * [Phase 5.5-Director] 현재 활성화된 디렉터 인터루드의 나레이션 원문
     * 액터 컨텍스트에 [DIRECTOR_NARRATION]으로 주입하여 맥락 공유.
     * 소비 후 null로 클리어.
     */
    @Column(name = "active_director_narration", columnDefinition = "TEXT")
    private String activeDirectorNarration;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Fix] 동적 배경 영속화
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** AI 생성 배경의 장소명 (LLM이 제공한 자유텍스트, 예: "해변", "놀이공원") */
    @Column(name = "current_dynamic_location_name", length = 100)
    private String currentDynamicLocationName;

    /** AI 생성 배경의 S3 URL (캐시 히트 시 즉시 저장, 미스 시 생성 완료 후 저장) */
    @Column(name = "current_dynamic_bg_url", length = 1000)
    private String currentDynamicBgUrl;

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

    // ── [Phase 5.5-Fix] 동적 배경 영속화 메서드 ──

    /** 동적 배경 URL + 장소명 갱신 (캐시 히트 시 또는 생성 완료 시) */
    public void updateDynamicBackground(String locationName, String bgUrl) {
        this.currentDynamicLocationName = locationName;
        this.currentDynamicBgUrl = bgUrl;
    }

    /** 동적 배경 장소명만 갱신 (캐시 미스, 비동기 생성 시작 시) */
    public void updateDynamicLocationName(String locationName) {
        this.currentDynamicLocationName = locationName;
        this.currentDynamicBgUrl = null; // 아직 URL 미확정
    }

    /** 동적 배경 클리어 (enum 기반 정적 장소로 전환 시) */
    public void clearDynamicBackground() {
        this.currentDynamicLocationName = null;
        this.currentDynamicBgUrl = null;
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
        if (location != null) {
            try {
                this.currentLocation = Location.valueOf(location);
                // [Phase 5.5-Fix] enum 장소로 전환 시 AI 생성 배경 클리어
                clearDynamicBackground();
            } catch (IllegalArgumentException ignored) {
                // AI 생성 동적 장소 — enum 매핑 불가, 무시 (동적 배경은 별도 경로로 처리)
            }
        }
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Director] 디렉터 인터루드 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 디렉터 인터루드가 유저에게 표시된 후 호출.
     * 다음 액터 호출에서 constraint + narration을 컨텍스트로 주입.
     */
    public void setDirectorInterlude(String narration, String actorConstraint) {
        this.activeDirectorNarration = narration;
        this.activeDirectorConstraint = actorConstraint;
    }

    /**
     * 디렉터 인터루드의 관찰자 모드로 이벤트 시작.
     * INTERLUDE + user_agency=OBSERVER인 경우 호출.
     */
    public void startDirectorInterlude(String narration, String actorConstraint) {
        setDirectorInterlude(narration, actorConstraint);
        this.eventActive = true;
        this.eventStatus = "ONGOING";
        this.topicConcluded = false;
    }

    /**
     * 디렉터 constraint가 액터에 주입된 후 클리어.
     * 일회성 소비.
     */
    public void clearDirectorInterlude() {
        this.activeDirectorConstraint = null;
        this.activeDirectorNarration = null;
    }

    /** 디렉터 constraint가 활성화되어 있는지 */
    public boolean hasActiveDirectorConstraint() {
        return this.activeDirectorConstraint != null && !this.activeDirectorConstraint.isBlank();
    }

    /** 마지막 디렉터 개입 턴 업데이트 */
    public void updateLastDirectorTurn(long turn) {
        this.lastDirectorTurn = turn;
    }

    public void saveEndingTitle(String title) { this.endingTitle = title; }

    public void resetAll() {
        resetAffection();
        resetSceneState();
        clearPromotion();
        clearPromotionWaiting();
        clearDirectorEvent();
        clearDynamicBackground();
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
        this.lastDirectorTurn = 0;
        this.activeDirectorConstraint = null;
        this.activeDirectorNarration = null;
    }

    private int clamp(int min, int max, int v) { return Math.max(min, Math.min(max, v)); }
    private static Location parseLocationOrDefault(String v) { try { return Location.valueOf(v); } catch (Exception e) { return Location.ENTRANCE; } }
    private static Outfit parseOutfitOrDefault(String v) { try { return Outfit.valueOf(v); } catch (Exception e) { return Outfit.MAID; } }
}