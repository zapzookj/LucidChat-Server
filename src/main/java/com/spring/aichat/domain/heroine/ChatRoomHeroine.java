package com.spring.aichat.domain.heroine;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.RelationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [V2 Story] 채팅방-히로인 다대다 + 캐릭터별 모든 상태 저장
 *
 * <p>V2 Story의 ChatRoom은 *World 단위*이고 1방에 히로인 3명까지 참여 가능.
 * 각 히로인별로 *독립된 관계 상태*를 가져야 한다. 이 엔티티가 그 컨테이너.
 *
 * <p>[저장 정보]
 * - 8축 스탯 (V1 ChatRoom에서 이전): normal 5축 + secret 3축
 * - 관계 단계, 동적 관계 태그
 * - BPM (실시간 + 기준)
 * - 속마음 (innerThought), 해금 여부
 * - 마지막 화자였을 때의 감정 태그
 * - 마지막 일러스트 hint (캐릭터 일러스트 생성 시 사용)
 * - 입장 시점
 *
 * <p>[Theater 결함 A 통합]
 * 캐릭터별 누적 메모리는 별도 테이블({@link com.spring.aichat.domain.memory.HeroineMemorySummary})로
 * 분리. Theater도 같은 테이블 사용 → 결함 A 자연 해결. 본 엔티티는 *세션 단위 상태*만.
 *
 * <p>[관계 단계 LLM 자율 발동 패턴]
 * - {@code statusLevel} 갱신은 {@link com.spring.aichat.domain.chat.RelationPromotionEligibility}
 *   triggered 시점에만 발생.
 * - 호감도 임계값 도달은 *자격 활성*일 뿐, statusLevel 즉시 갱신 안 함 (Q-9 이중 게이트 패턴).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "chat_room_heroines",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_room_heroine",
            columnNames = {"chat_room_id", "character_id"})
    },
    indexes = {
        @Index(name = "idx_heroine_room", columnList = "chat_room_id"),
        @Index(name = "idx_heroine_char", columnList = "character_id")
    })
public class ChatRoomHeroine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 동시 stat 갱신 lost update 차단.
     * 멀티 히로인 동시 응답 처리 시(LLM이 한 응답에서 여러 캐릭터 stat 변경) lock contention 회피.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    private Character character;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  8축 스탯 (normal 5축 + secret 3축)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "stat_intimacy",    nullable = false)  private int statIntimacy    = 0;
    @Column(name = "stat_affection",   nullable = false)  private int statAffection   = 0;
    @Column(name = "stat_dependency",  nullable = false)  private int statDependency  = 0;
    @Column(name = "stat_playfulness", nullable = false)  private int statPlayfulness = 0;
    @Column(name = "stat_trust",       nullable = false)  private int statTrust       = 0;

    @Column(name = "stat_lust",        nullable = false)  private int statLust        = 0;
    @Column(name = "stat_corruption",  nullable = false)  private int statCorruption  = 0;
    @Column(name = "stat_obsession",   nullable = false)  private int statObsession   = 0;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  관계 / 동적 태그 / BPM
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Enumerated(EnumType.STRING)
    @Column(name = "status_level", nullable = false, length = 30)
    private RelationStatus statusLevel = RelationStatus.STRANGER;

    @Column(name = "dynamic_relation_tag", length = 50)
    private String dynamicRelationTag;

    @Column(name = "current_bpm", nullable = false)
    private int currentBpm = 65;

    @Column(name = "base_bpm", nullable = false)
    private int baseBpm = 65;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  속마음 / 감정 / 일러스트 hint
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 가장 최근 이 히로인의 속마음 텍스트 (LLM이 출력 시 갱신). nullable. */
    @Column(name = "character_thought", columnDefinition = "TEXT")
    private String characterThought;

    /** 속마음 갱신 시점의 턴 카운트 (해금 만료 판단용). */
    @Column(name = "thought_updated_at_turn", nullable = false)
    private int thoughtUpdatedAtTurn = 0;

    /** 가장 최근 이 히로인이 화자였을 때의 감정 태그. */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_emotion", length = 30)
    private EmotionTag lastEmotion;

    /** 가장 최근 이 히로인의 일러스트 hint (Phase 6, 캐릭터 일러스트 생성 시). */
    @Column(name = "last_illustration_hint", columnDefinition = "TEXT")
    private String lastIllustrationHint;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  메타
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 이 히로인이 방에 합류한 시점 (StoryCreateFlow에서 선택된 시점). */
    @Column(name = "selected_at", nullable = false, updatable = false)
    private LocalDateTime selectedAt;

    /** 이 히로인이 마지막으로 화자였던 시점. 라우팅 디폴트(최근 화자) 결정용. */
    @Column(name = "last_spoken_at")
    private LocalDateTime lastSpokenAt;

    @PrePersist
    void prePersist() {
        if (this.selectedAt == null) {
            this.selectedAt = LocalDateTime.now();
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static ChatRoomHeroine create(ChatRoom chatRoom, Character character) {
        ChatRoomHeroine h = new ChatRoomHeroine();
        h.chatRoom = chatRoom;
        h.character = character;
        h.dynamicRelationTag = "낯선 사람";
        return h;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스탯 변경
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void applyNormalStatChanges(int dIntimacy, int dAffection,
                                       int dDependency, int dPlayfulness, int dTrust) {
        this.statIntimacy    = clamp(this.statIntimacy    + dIntimacy);
        this.statAffection   = clamp(this.statAffection   + dAffection);
        this.statDependency  = clamp(this.statDependency  + dDependency);
        this.statPlayfulness = clamp(this.statPlayfulness + dPlayfulness);
        this.statTrust       = clamp(this.statTrust       + dTrust);
    }

    public void applySecretStatChanges(int dLust, int dCorruption, int dObsession) {
        this.statLust       = clamp(this.statLust       + dLust);
        this.statCorruption = clamp(this.statCorruption + dCorruption);
        this.statObsession  = clamp(this.statObsession  + dObsession);
    }

    public int getMaxNormalStat() {
        return Math.max(statIntimacy, Math.max(statAffection,
            Math.max(statDependency, Math.max(statPlayfulness, statTrust))));
    }

    public int getMinNormalStat() {
        return Math.min(statIntimacy, Math.min(statAffection,
            Math.min(statDependency, Math.min(statPlayfulness, statTrust))));
    }

    /**
     * 호감도(=statAffection) 게터. 관계 승급 자격 판정의 기준 스탯.
     * V1 {@code ChatRoom.affectionScore}와 의미 동일.
     */
    public int getAffectionScore() {
        return this.statAffection;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  관계 단계 / 태그 갱신 — LLM 자율 발동(RelationPromotionEligibility.triggered)에서만 호출
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void promoteStatusLevel(RelationStatus newLevel) {
        this.statusLevel = newLevel;
    }

    public void updateDynamicRelationTag(String tag) {
        this.dynamicRelationTag = tag;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  속마음 / 감정 / 일러스트 hint
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void updateCharacterThought(String thought, int currentTurnCount) {
        this.characterThought = thought;
        this.thoughtUpdatedAtTurn = currentTurnCount;
    }

    public void updateLastEmotion(EmotionTag emotion) {
        if (emotion != null) this.lastEmotion = emotion;
    }

    public void updateLastIllustrationHint(String hint) {
        if (hint != null && !hint.isBlank()) {
            this.lastIllustrationHint = hint.trim();
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  BPM
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void updateBpm(int bpm) {
        this.currentBpm = Math.max(60, Math.min(180, bpm));
    }

    public void updateBaseBpm(int baseBpm) {
        this.baseBpm = Math.max(60, Math.min(180, baseBpm));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  화자 마킹 (라우팅 결정용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void markSpoken() {
        this.lastSpokenAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  reset (스토리 초기화 시 호출)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void resetProgress() {
        this.statIntimacy = 0;  this.statAffection = 0;  this.statDependency = 0;
        this.statPlayfulness = 0;  this.statTrust = 0;
        this.statLust = 0;  this.statCorruption = 0;  this.statObsession = 0;
        this.statusLevel = RelationStatus.STRANGER;
        this.dynamicRelationTag = "낯선 사람";
        this.currentBpm = 65;
        this.baseBpm = 65;
        this.characterThought = null;
        this.thoughtUpdatedAtTurn = 0;
        this.lastEmotion = null;
        this.lastIllustrationHint = null;
        this.lastSpokenAt = null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static int clamp(int v) {
        return Math.max(-100, Math.min(100, v));
    }
}