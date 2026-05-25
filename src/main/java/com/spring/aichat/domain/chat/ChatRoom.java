package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.enums.*;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.world.World;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 채팅방 — 관계/상태/세션 영속의 핵심 엔티티
 *
 * <p>[V2 Story 듀얼 모드 아키텍처]
 * 같은 ChatRoom 테이블이 두 가지 정체성을 갖는다:
 *
 * <pre>
 *   chatMode=SANDBOX → character FK 사용. V1 자유 채팅 (1:1)
 *                      currentLocation/Outfit/TimeOfDay enum, 8축 스탯, affectionScore 등 V1 필드 활성
 *
 *   chatMode=STORY   → world FK 사용. V2 디렉터 시점 World 탐험
 *                      currentUserLocationKey(String), DayPart enum, currentDay 등 V2 필드 활성
 *                      8축 스탯은 {@link com.spring.aichat.domain.heroine.ChatRoomHeroine}로 분리
 * </pre>
 *
 * <p>[조건부 FK XOR 제약]
 * 모드별로 정확히 한 FK만 NOT NULL:
 *   - SANDBOX: {@code character_id NOT NULL, world_id NULL}
 *   - STORY:   {@code character_id NULL, world_id NOT NULL}
 * MariaDB partial unique 미지원이라 *애플리케이션 레벨 가드*({@code LobbyService} 진입 시
 * 사전 확인 + 본 엔티티 생성자에서 모드별 분기)로 강제.
 *
 * <p>[Unique Constraint 두 개 공존]
 * - {@code uk_user_character_mode}: Sandbox 유저당 character당 1방
 * - {@code uk_user_world_mode}: Story 유저당 world당 1방
 * MariaDB는 NULL을 unique 비교에서 중복 허용 → 모드별로 한쪽만 채워져도 충돌 없음.
 *
 * <p>[V2에서 폐기된 V1 필드 — 본 엔티티에서 완전 제거]
 * - {@code promotion_*} 5필드: V1 시스템 강제 임무 패턴 폐기. V2는 LLM 자율 발동 →
 *   {@link RelationPromotionEligibility} 테이블 사용.
 * - {@code event_active, event_status, last_director_turn, active_director_*} 5필드:
 *   V1 자동 디렉터 인터루드 시스템 폐기 (도그푸딩 #1).
 *
 * <p>[V2 Story에서 캐릭터별로 이전된 V1 필드]
 * - 8축 스탯, statusLevel, dynamicRelationTag, characterThought, currentBpm, lastEmotion,
 *   lastIllustrationHint → {@link com.spring.aichat.domain.heroine.ChatRoomHeroine}
 * - Sandbox 모드는 ChatRoom에 그대로 유지 (V1 호환).
 *
 * <p>[버전 관리]
 * - {@code @Version}: TX-2 동시 stat 갱신 lost update 차단.
 *   {@code OptimisticLockingFailureException} 발생 시 호출처 retry.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "chat_rooms",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_character_mode",
            columnNames = {"user_id", "character_id", "chat_mode"}),
        @UniqueConstraint(name = "uk_user_world_mode",
            columnNames = {"user_id", "world_id", "chat_mode"})
    },
    indexes = {
        @Index(name = "idx_room_user_active", columnList = "user_id, last_active_at"),
        @Index(name = "idx_room_character", columnList = "character_id"),
        @Index(name = "idx_room_world", columnList = "world_id")
    })
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    private Long id;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "chat_mode", nullable = false, length = 20)
    private ChatMode chatMode;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [조건부 FK] 모드별로 정확히 한쪽만 NOT NULL
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** SANDBOX 모드에서 NOT NULL. STORY 모드에서 NULL. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "character_id", nullable = true)
    private Character character;

    /** STORY 모드에서 NOT NULL. SANDBOX 모드에서 NULL. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "world_id", nullable = true, referencedColumnName = "id")
    private World world;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 필드 — 두 모드 모두 사용
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_bgm_mode", length = 20)
    private BgmMode currentBgmMode;

    /** LLM이 마지막으로 보고한 topic_concluded 상태 */
    @Column(name = "topic_concluded", nullable = false)
    private boolean topicConcluded = false;

    /**
     * 이 채팅방에서의 시크릿 모드 활성 여부.
     * 두 모드 공통. STORY 모드에서는 추가로 {@code World.secretAllowed} 게이팅 적용.
     */
    @Column(name = "secret_mode_active", nullable = false)
    private boolean secretModeActive = false;

    /**
     * 이 채팅방 전용 유저 페르소나.
     * SANDBOX: 캐릭터별 페르소나. STORY: World별 페르소나 (V2).
     * null이면 {@code User.profileDescription}으로 폴백.
     */
    @Column(name = "user_persona", columnDefinition = "TEXT")
    private String userPersona;

    // ── 엔딩 (공통) ──

    @Column(name = "ending_reached", nullable = false)
    private boolean endingReached = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "ending_type", length = 10)
    private EndingType endingType;

    @Column(name = "ending_title", length = 100)
    private String endingTitle;

    // ── Phase 6 동적 배경 (공통) ──

    @Column(name = "current_dynamic_location_name", length = 100)
    private String currentDynamicLocationName;

    @Column(name = "current_dynamic_canonical_key", length = 100)
    private String currentDynamicCanonicalKey;

    @Column(name = "current_dynamic_bg_url", length = 1000)
    private String currentDynamicBgUrl;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [SANDBOX 전용] V1 1:1 채팅 필드 — STORY에서는 NULL
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "affection_score")
    private Integer affectionScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_level", length = 30)
    private RelationStatus statusLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_emotion", length = 30)
    private EmotionTag lastEmotion;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_location", length = 20)
    private Location currentLocation;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_outfit", length = 20)
    private Outfit currentOutfit;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_time_of_day", length = 20)
    private TimeOfDay currentTimeOfDay;

    // ── 5축 normal 스탯 (Sandbox 전용) ──

    @Column(name = "stat_intimacy")    private Integer statIntimacy;
    @Column(name = "stat_affection")   private Integer statAffection;
    @Column(name = "stat_dependency")  private Integer statDependency;
    @Column(name = "stat_playfulness") private Integer statPlayfulness;
    @Column(name = "stat_trust")       private Integer statTrust;

    // ── 3축 secret 스탯 (Sandbox 전용) ──

    @Column(name = "stat_lust")        private Integer statLust;
    @Column(name = "stat_corruption")  private Integer statCorruption;
    @Column(name = "stat_obsession")   private Integer statObsession;

    @Column(name = "dynamic_relation_tag", length = 50)
    private String dynamicRelationTag;

    @Column(name = "character_thought", columnDefinition = "TEXT")
    private String characterThought;

    @Column(name = "thought_updated_at_turn")
    private Integer thoughtUpdatedAtTurn;

    @Column(name = "current_bpm")
    private Integer currentBpm;

    @Column(name = "last_illustration_hint", columnDefinition = "TEXT")
    private String lastIllustrationHint;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Sandbox 전용] V1 STORY → SANDBOX 이관 — 관계 승급 이벤트 (사용자 결정: 자산 이관)
    //
    //  V1에서는 STORY 전용이었으나, V2 패치 시 STORY 정체성이 변경됨에 따라
    //  V1의 풍부한 promotion/event/director 시스템을 SANDBOX로 이관.
    //  추후 PoC로 Sandbox에서의 작동/유저 반응 검증 후 폐기 또는 다듬기 결정.
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
     * [V1 Phase 5.5-EV] 임계값 도달 후 topic_concluded 대기 상태.
     * topic_concluded=true가 오면 실제 promotionPending으로 전환.
     */
    @Column(name = "promotion_waiting_for_topic", nullable = false)
    private boolean promotionWaitingForTopic = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "promotion_waiting_target", length = 30)
    private RelationStatus promotionWaitingTarget;

    // ── [Sandbox 전용] V1 이관 — 이벤트/디렉터 시스템 ──

    /** 디렉터 모드 이벤트 진행 중 여부 */
    @Column(name = "event_active", nullable = false)
    private boolean eventActive = false;

    /** 디렉터 모드 이벤트 상태 ("ONGOING" | "RESOLVED" | null) */
    @Column(name = "event_status", length = 20)
    private String eventStatus;

    /** [V1 Phase 5.5-Director] 마지막 디렉터 개입 턴 */
    @Column(name = "last_director_turn", nullable = false)
    private long lastDirectorTurn = 0;

    /**
     * [V1 Phase 5.5-Director] 현재 활성화된 디렉터 인터루드의 actor_constraint.
     * 인터루드 나레이션을 유저에게 보여준 뒤 다음 액터 호출 시 주입. 소비 후 null.
     */
    @Column(name = "active_director_constraint", columnDefinition = "TEXT")
    private String activeDirectorConstraint;

    /**
     * [V1 Phase 5.5-Director] 현재 활성화된 디렉터 인터루드의 나레이션 원문.
     * 액터 컨텍스트에 [DIRECTOR_NARRATION]으로 주입하여 맥락 공유. 소비 후 null.
     */
    @Column(name = "active_director_narration", columnDefinition = "TEXT")
    private String activeDirectorNarration;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [STORY V2 전용] 디렉터 시점 World 탐험 필드 — SANDBOX에서는 NULL
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 유저 현재 위치 — {@code WorldLocation.locationKey} 참조 (String key) */
    @Column(name = "current_user_location_key", length = 50)
    private String currentUserLocationKey;

    /** World 안의 경과 일수 (1부터 시작). */
    @Column(name = "current_day")
    private Integer currentDay;

    /** World 안의 현재 시간대 — V2의 5단계 DayPart enum. */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_day_part", length = 20)
    private DayPart currentDayPart;

    /**
     * V2 엔딩 이중 게이트 — 호감도 임계값 도달 시 백엔드가 자동 활성.
     * true일 때만 LLM이 {@code ending_triggered=true} 발동 가능.
     */
    @Column(name = "ending_eligible", nullable = false)
    private boolean endingEligible = false;

    /** 엔딩 자격 활성 시점 (페일세이프 카운터용). */
    @Column(name = "ending_eligible_since")
    private LocalDateTime endingEligibleSince;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  생성자 — 모드별 분리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [V1 호환] Public 생성자 — V1 호출처(OnboardingService, LobbyService, TheaterLobbyService)
    //  보호. 신규 V2 코드는 factory({@link #createSandbox}, {@link #createStoryV2}) 사용 권장.
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** [V1 호환] Sandbox 또는 Theater 방 생성. STORY V2는 {@link #createStoryV2} 사용. */
    public ChatRoom(User user, Character character, ChatMode chatMode) {
        if (chatMode == ChatMode.STORY) {
            throw new IllegalArgumentException(
                "V2 STORY 방은 ChatRoom.createStoryV2(user, world, ...)로 생성하세요. " +
                    "Character FK 단독으로는 V2 STORY 방을 구성할 수 없습니다.");
        }
        this.user = user;
        this.character = character;
        this.chatMode = chatMode;
        this.lastActiveAt = LocalDateTime.now();
        this.currentBgmMode = BgmMode.DAILY;
        this.affectionScore = 0;
        this.statusLevel = RelationStatus.STRANGER;
        this.lastEmotion = EmotionTag.NEUTRAL;
        this.currentTimeOfDay = TimeOfDay.NIGHT;
        if (character != null) {
            this.currentLocation = parseLocationOrDefault(character.getEffectiveDefaultLocation());
            this.currentOutfit = parseOutfitOrDefault(character.getEffectiveDefaultOutfit());
        }
        this.statIntimacy = 0;  this.statAffection = 0;  this.statDependency = 0;
        this.statPlayfulness = 0;  this.statTrust = 0;
        this.statLust = 0;  this.statCorruption = 0;  this.statObsession = 0;
        this.dynamicRelationTag = "낯선 사람";
        this.currentBpm = 65;
        this.thoughtUpdatedAtTurn = 0;
    }

    /** [V1 호환] ChatMode 미지정 시 SANDBOX 기본값. */
    public ChatRoom(User user, Character character) {
        this(user, character, ChatMode.SANDBOX);
    }

    /**
     * SANDBOX 생성자 — V1 캐릭터 1:1 채팅.
     * V1 호환을 위해 디폴트 location/outfit/time/8축 스탯 초기화.
     */
    public static ChatRoom createSandbox(User user, Character character) {
        ChatRoom r = new ChatRoom();
        r.user = user;
        r.character = character;
        r.chatMode = ChatMode.SANDBOX;
        r.lastActiveAt = LocalDateTime.now();
        r.currentBgmMode = BgmMode.DAILY;

        // Sandbox V1 필드 초기화
        r.affectionScore = 0;
        r.statusLevel = RelationStatus.STRANGER;
        r.lastEmotion = EmotionTag.NEUTRAL;
        r.currentTimeOfDay = TimeOfDay.NIGHT;
        r.currentLocation = parseLocationOrDefault(character.getEffectiveDefaultLocation());
        r.currentOutfit = parseOutfitOrDefault(character.getEffectiveDefaultOutfit());
        r.statIntimacy = 0;  r.statAffection = 0;  r.statDependency = 0;
        r.statPlayfulness = 0;  r.statTrust = 0;
        r.statLust = 0;  r.statCorruption = 0;  r.statObsession = 0;
        r.dynamicRelationTag = "낯선 사람";
        r.currentBpm = 65;
        r.thoughtUpdatedAtTurn = 0;

        return r;
    }

    /**
     * STORY V2 생성자 — World 탐험.
     * 캐릭터별 스탯/감정은 {@link com.spring.aichat.domain.heroine.ChatRoomHeroine}에 분리.
     *
     * @param startLocationKey 유저 시작 장소 ({@code WorldLocation.locationKey})
     * @param userPersona      유저 페르소나 (CreateFlow 입력 결과)
     */
    public static ChatRoom createStoryV2(User user, World world,
                                         String startLocationKey, String userPersona) {
        ChatRoom r = new ChatRoom();
        r.user = user;
        r.world = world;
        r.chatMode = ChatMode.STORY;
        r.lastActiveAt = LocalDateTime.now();
        r.userPersona = userPersona;
        r.currentBgmMode = parseBgmModeOrDefault(world.getDefaultBgm());

        // V2 Story 필드 초기화
        r.currentUserLocationKey = startLocationKey;
        r.currentDay = 1;
        r.currentDayPart = DayPart.defaultStart();
        r.endingEligible = false;

        return r;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  헬퍼 — 모드 판정 및 안전한 접근
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public boolean isStoryMode() { return this.chatMode == ChatMode.STORY; }
    public boolean isSandboxMode() { return this.chatMode == ChatMode.SANDBOX; }

    /** SANDBOX 검증 — Story 모드에서 V1 필드 접근 시 명시적 에러. */
    private void requireSandbox() {
        if (chatMode != ChatMode.SANDBOX) {
            throw new IllegalStateException("V1 field access on non-SANDBOX room: id=" + id + ", mode=" + chatMode);
        }
    }

    /** STORY 검증 — Sandbox 모드에서 V2 필드 접근 시 명시적 에러. */
    private void requireStory() {
        if (chatMode != ChatMode.STORY) {
            throw new IllegalStateException("V2 field access on non-STORY room: id=" + id + ", mode=" + chatMode);
        }
    }

    /**
     * 유효 페르소나 반환 — 방 전용 페르소나 우선, 없으면 유저 기본값 폴백.
     * 두 모드 공통.
     */
    public String getEffectivePersona(User user) {
        if (this.userPersona != null && !this.userPersona.isBlank()) {
            return this.userPersona;
        }
        return user.getProfileDescription();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 메서드 — 두 모드 모두 사용
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
    }

    public void touch(EmotionTag emotion) {
        this.lastActiveAt = LocalDateTime.now();
        if (isSandboxMode()) {
            this.lastEmotion = emotion;
        }
        // STORY 모드의 lastEmotion은 ChatRoomHeroine에 저장
    }

    public void updateTopicConcluded(boolean concluded) {
        this.topicConcluded = concluded;
    }

    public void activateSecretMode() {
        this.secretModeActive = true;
    }

    public void deactivateSecretMode() {
        this.secretModeActive = false;
    }

    public void updateUserPersona(String persona) {
        this.userPersona = persona;
    }

    public void markEndingReached(EndingType endingType) {
        this.endingReached = true;
        this.endingType = endingType;
    }

    public void saveEndingTitle(String title) {
        this.endingTitle = title;
    }

    // ── 동적 배경 (Phase 6, 공통) ──

    public void updateDynamicBackground(String locationName, String canonicalKey, String bgUrl) {
        this.currentDynamicLocationName = locationName;
        this.currentDynamicCanonicalKey = canonicalKey;
        this.currentDynamicBgUrl = bgUrl;
    }

    /**
     * [V1 호환] canonical_key 없이 호출하는 기존 경로 (예: ChatService 캐시 히트 경로).
     * currentDynamicCanonicalKey는 의도적으로 건드리지 않음 (기존 값 보존).
     */
    public void updateDynamicBackground(String locationName, String bgUrl) {
        this.currentDynamicLocationName = locationName;
        this.currentDynamicBgUrl = bgUrl;
    }

    public void updateDynamicLocationName(String locationName, String canonicalKey) {
        this.currentDynamicLocationName = locationName;
        this.currentDynamicCanonicalKey = canonicalKey;
        this.currentDynamicBgUrl = null;  // 아직 URL 미확정
    }

    public void clearDynamicBackground() {
        this.currentDynamicLocationName = null;
        this.currentDynamicCanonicalKey = null;
        this.currentDynamicBgUrl = null;
    }

    public void updateBgmMode(BgmMode mode) {
        if (mode != null) this.currentBgmMode = mode;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  SANDBOX 전용 메서드 — V1 호환 (호출 전 isSandboxMode() 체크 권장)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void updateAffection(int newScore) {
        requireSandbox();
        this.statAffection = clamp(-100, 100, newScore);
        this.affectionScore = this.statAffection;
    }

    public void applyNormalStatChanges(int dIntimacy, int dAffection,
                                       int dDependency, int dPlayfulness, int dTrust) {
        requireSandbox();
        this.statIntimacy    = clamp(-100, 100, this.statIntimacy    + dIntimacy);
        this.statAffection   = clamp(-100, 100, this.statAffection   + dAffection);
        this.statDependency  = clamp(-100, 100, this.statDependency  + dDependency);
        this.statPlayfulness = clamp(-100, 100, this.statPlayfulness + dPlayfulness);
        this.statTrust       = clamp(-100, 100, this.statTrust       + dTrust);
        this.affectionScore  = this.statAffection;
    }

    public void applySecretStatChanges(int dLust, int dCorruption, int dObsession) {
        requireSandbox();
        this.statLust       = clamp(-100, 100, this.statLust       + dLust);
        this.statCorruption = clamp(-100, 100, this.statCorruption + dCorruption);
        this.statObsession  = clamp(-100, 100, this.statObsession  + dObsession);
    }

    public void updateStatusLevel(RelationStatus status) {
        requireSandbox();
        this.statusLevel = status;
    }

    public void updateDynamicRelationTag(String tag) {
        requireSandbox();
        this.dynamicRelationTag = tag;
    }

    public void updateCharacterThought(String thought, int currentTurnCount) {
        requireSandbox();
        this.characterThought = thought;
        this.thoughtUpdatedAtTurn = currentTurnCount;
    }

    public void updateBpm(int bpm) {
        requireSandbox();
        this.currentBpm = clamp(60, 180, bpm);
    }

    /**
     * [V1 호환] 씬 상태 갱신 — String 파라미터 4종. LLM JSON 응답의 raw String을 그대로 받아 내부에서
     * enum 변환 + try/catch. AI가 잘못된 enum 문자열을 보내도 *부분 성공* 가능.
     */
    public void updateSceneState(String bgmMode, String location, String outfit, String timeOfDay) {
        requireSandbox();
        if (bgmMode != null) {
            try { this.currentBgmMode = BgmMode.valueOf(bgmMode); }
            catch (IllegalArgumentException ignored) {}
        }
        if (location != null) {
            try {
                Location parsedLoc = Location.valueOf(location);
                this.currentLocation = parsedLoc;
                // [Phase 6 hotfix] 동적 장소가 활성인 동안에는 scene.location enum으로
                //   동적 배경을 클리어하지 않는다. CharacterPromptAssembler 지시에 따라 LLM은
                //   동적 장소 사용 중에도 "가장 가까운 static location"을 scene.location 에
                //   fallback 으로 넣으므로, 이 값을 진짜 전환으로 오인해 clearDynamicBackground()를
                //   호출하면 동적 장소가 default로 소실된다.
                //   동적 → 정적 전환은 ChatStreamService의 new_location_name 명시 경로에서만 처리.
                if (this.currentDynamicLocationName == null) {
                    clearDynamicBackground();
                }
            } catch (IllegalArgumentException ignored) {
                // AI 생성 동적 장소 — enum 매핑 불가, 무시 (동적 배경은 별도 경로로 처리)
            }
        }
        if (outfit != null) {
            try { this.currentOutfit = Outfit.valueOf(outfit); }
            catch (IllegalArgumentException ignored) {}
        }
        if (timeOfDay != null) {
            try { this.currentTimeOfDay = TimeOfDay.valueOf(timeOfDay); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    /**
     * [V2 편의] 이미 enum 변환된 값으로 호출하는 오버로드.
     * 신규 V2 호출처에서 사용 가능 — null 안전.
     */
    public void updateSceneState(BgmMode bgm, Location location, Outfit outfit, TimeOfDay time) {
        requireSandbox();
        if (bgm != null) this.currentBgmMode = bgm;
        if (location != null) {
            this.currentLocation = location;
            if (this.currentDynamicLocationName == null) {
                clearDynamicBackground();
            }
        }
        if (outfit != null) this.currentOutfit = outfit;
        if (time != null) this.currentTimeOfDay = time;
    }

    public void updateLastIllustrationHint(String hint) {
        requireSandbox();
        if (hint != null && !hint.isBlank()) {
            this.lastIllustrationHint = hint.trim();
        }
    }

    public int getMaxNormalStatValue() {
        requireSandbox();
        return Math.max(statIntimacy, Math.max(statAffection,
            Math.max(statDependency, Math.max(statPlayfulness, statTrust))));
    }

    public int getMinNormalStatValue() {
        requireSandbox();
        return Math.min(statIntimacy, Math.min(statAffection,
            Math.min(statDependency, Math.min(statPlayfulness, statTrust))));
    }

    /**
     * Sandbox 엔딩 트리거 — 기존 V1 즉시 발동 패턴 유지 (Sandbox는 자유 채팅이라 LLM 이중 게이트 불필요).
     * STORY 모드에서는 {@link #activateEndingEligibility()} 사용.
     */
    public String checkEndingTrigger() {
        requireSandbox();
        if (this.endingReached) return null;
        if (getMaxNormalStatValue() >= 100) return "HAPPY";
        if (getMinNormalStatValue() <= -100) return "BAD";
        return null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Sandbox 전용] V1 호환 — 추가 도메인 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** [V1] lastActiveAt + lastEmotion 동시 갱신. {@code touch(emotion)}과 동일 의미. */
    public void updateLastActive(EmotionTag emotion) {
        this.lastActiveAt = LocalDateTime.now();
        this.lastEmotion = emotion;
    }

    /** [V1] affection_score만 부분 리셋 — statusLevel은 STRANGER로 복귀. */
    public void resetAffection() {
        requireSandbox();
        this.affectionScore = 0;
        this.statAffection = 0;
        this.statusLevel = RelationStatus.STRANGER;
    }

    /**
     * [V1 호환] 5축 스탯 도입 이전의 legacy 호감도 변화 메서드.
     * affection 단일 축으로만 변화량을 적용. 현 코드베이스에서 일부 호출처에서 사용.
     */
    public void applyLegacyAffectionChange(int delta) {
        requireSandbox();
        if (delta == 0) return;
        this.statAffection = clamp(-100, 100, this.statAffection + delta);
        this.affectionScore = this.statAffection;
    }

    /** [V1] 현재 가장 높은 5종 노말 스탯 이름 반환 (dynamic_relation_tag 빌드용). */
    public String getDominantStatName() {
        requireSandbox();
        return RelationStatusPolicy.getDominantStat(
            statIntimacy, statAffection, statDependency, statPlayfulness, statTrust);
    }

    /**
     * [V1 Phase 5.5] 5종 스탯 기반으로 statusLevel + dynamic_relation_tag 재계산.
     * @return statusLevel이 변경되었으면 true (승급 트리거 신호)
     */
    public boolean refreshRelationFromStats() {
        requireSandbox();
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

    /** [V1] 씬 상태(BGM/장소/복장/시간)만 캐릭터 기본값으로 복원. */
    public void resetSceneState() {
        requireSandbox();
        this.currentBgmMode = BgmMode.DAILY;
        this.currentLocation = parseLocationOrDefault(character.getEffectiveDefaultLocation());
        this.currentOutfit = parseOutfitOrDefault(character.getEffectiveDefaultOutfit());
        this.currentTimeOfDay = TimeOfDay.NIGHT;
    }

    /**
     * [V1] 방의 모든 상태를 초기화 (페르소나 제외).
     * V2의 {@link #resetProgress(boolean)}와 별개 — V1 호환용으로 유지.
     * V1 ChatStreamService의 sandbox 흐름에서 호출됨.
     */
    public void resetAll() {
        requireSandbox();
        resetAffection();
        resetSceneState();
        // promotion/event/director 메서드들은 모두 private clearXxx로 정의됨 → 직접 필드 리셋
        this.promotionPending = false;
        this.pendingTargetStatus = null;
        this.promotionMoodScore = 0;
        this.promotionTurnCount = 0;
        this.promotionWaitingForTopic = false;
        this.promotionWaitingTarget = null;
        this.eventActive = false;
        this.eventStatus = null;
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
        this.secretModeActive = false;
        // userPersona는 의도적으로 리셋하지 않음 — 유저가 설정한 페르소나는 유지
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Sandbox 전용] V1 이관 — 관계 승급 이벤트 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [V1 Phase 5.5-EV] 승급 임계값 도달 시 호출 — topic_concluded 대기 상태 진입.
     */
    public void markPromotionWaiting(RelationStatus target) {
        requireSandbox();
        this.promotionWaitingForTopic = true;
        this.promotionWaitingTarget = target;
    }

    /**
     * [V1 Phase 5.5-EV] topic_concluded=true일 때 실제 승급 이벤트 개시.
     * @return 승급이 시작되었으면 true
     */
    public boolean tryStartPromotionFromWaiting() {
        requireSandbox();
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

    public void startPromotion(RelationStatus targetStatus) {
        requireSandbox();
        this.promotionPending = true;
        this.pendingTargetStatus = targetStatus;
        this.promotionMoodScore = 0;
        this.promotionTurnCount = 0;
    }

    /**
     * [V1 Phase 5.5-EV] 승급 턴 진행 — mood_score를 5종 스탯 변화량 합산으로 대체.
     * @param statDeltaSum 해당 턴의 5종 노말 스탯 변화량 절대값 합산
     */
    public void advancePromotionTurn(int statDeltaSum) {
        requireSandbox();
        this.promotionTurnCount++;
        this.promotionMoodScore += statDeltaSum;
    }

    public void completePromotionSuccess() {
        requireSandbox();
        this.statusLevel = this.pendingTargetStatus;
        clearPromotion();
        clearDirectorEvent(); // 승급 완료 → 디렉터 모드 종료
    }

    public void completePromotionFailure() {
        requireSandbox();
        clearPromotion();
        clearDirectorEvent();
    }

    private void clearPromotion() {
        this.promotionPending = false;
        this.pendingTargetStatus = null;
        this.promotionMoodScore = 0;
        this.promotionTurnCount = 0;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Sandbox 전용] V1 이관 — 이벤트 / 디렉터 인터루드 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 디렉터 모드 이벤트 시작 */
    public void startDirectorEvent() {
        requireSandbox();
        this.eventActive = true;
        this.eventStatus = "ONGOING";
        // 이벤트 시작 시 topic은 아직 진행 중
        this.topicConcluded = false;
    }

    /** 디렉터 모드 이벤트 상태 업데이트 (LLM 응답 기반) */
    public void updateEventStatus(String status) {
        requireSandbox();
        if ("RESOLVED".equalsIgnoreCase(status)) {
            this.eventActive = false;
            this.eventStatus = "RESOLVED";
        } else if ("ONGOING".equalsIgnoreCase(status)) {
            this.eventStatus = "ONGOING";
        }
    }

    /** 이벤트 강제 종료 (초기화 등) */
    public void clearDirectorEvent() {
        requireSandbox();
        this.eventActive = false;
        this.eventStatus = null;
    }

    /**
     * [V1 호환] eventActive boolean 명시적 getter.
     * Lombok {@code @Getter}로 자동 생성되지만, IDE/Lombok plugin 환경 차이 보호를 위해 명시.
     */
    public boolean isEventActive() {
        return this.eventActive;
    }

    /**
     * 디렉터 인터루드가 유저에게 표시된 후 호출.
     * 다음 액터 호출에서 constraint + narration을 컨텍스트로 주입.
     */
    public void setDirectorInterlude(String narration, String actorConstraint) {
        requireSandbox();
        this.activeDirectorNarration = narration;
        this.activeDirectorConstraint = actorConstraint;
    }

    /**
     * 디렉터 인터루드의 관찰자 모드로 이벤트 시작.
     * INTERLUDE + user_agency=OBSERVER인 경우 호출.
     */
    public void startDirectorInterlude(String narration, String actorConstraint) {
        requireSandbox();
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
        requireSandbox();
        this.activeDirectorConstraint = null;
        this.activeDirectorNarration = null;
    }

    /** 디렉터 constraint가 활성화되어 있는지 */
    public boolean hasActiveDirectorConstraint() {
        return this.activeDirectorConstraint != null && !this.activeDirectorConstraint.isBlank();
    }

    /** 마지막 디렉터 개입 턴 업데이트 */
    public void updateLastDirectorTurn(long turn) {
        requireSandbox();
        this.lastDirectorTurn = turn;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  STORY V2 전용 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void updateUserLocation(String locationKey) {
        requireStory();
        this.currentUserLocationKey = locationKey;
    }

    public void advanceTime(int days, DayPart newDayPart) {
        requireStory();
        if (days > 0) this.currentDay = (this.currentDay == null ? 1 : this.currentDay) + days;
        if (newDayPart != null) this.currentDayPart = newDayPart;
    }

    /**
     * V2 엔딩 이중 게이트 1단계 — 자격 활성. ChatRoomHeroine의 호감도 임계 도달 시
     * EndingService가 호출. 디렉터 prompt에 *자격 활성 신호*가 다음 턴부터 인젝션.
     */
    public void activateEndingEligibility() {
        requireStory();
        if (this.endingEligible) return;
        this.endingEligible = true;
        this.endingEligibleSince = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [스토리 초기화] 페르소나 옵션 reset
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 진행 데이터 초기화. 모드별로 reset 범위가 다르며, 페르소나 포함 여부는 유저 선택.
     *
     * <p>[STORY 모드 reset 범위]
     * - 본 엔티티: 위치/시간/엔딩/topic/동적배경. 페르소나는 옵션.
     * - 별도 처리 필요: ChatRoomHeroine 8축 스탯, CharacterPresence, RelationPromotionEligibility,
     *   HeroineMemorySummary, OffscreenNotification, ChatLogDocument — 서비스 레이어가 별도 정리.
     *
     * <p>[SANDBOX 모드 reset 범위]
     * - V1 호환: 8축 스탯/관계/씬상태/엔딩/동적배경 초기화.
     * - 별도 처리: MemorySummary, ChatLogDocument — 서비스 레이어가 별도 정리.
     *
     * @param includePersona true면 페르소나도 초기화 (완전히 새 세션). false면 페르소나 유지.
     */
    public void resetProgress(boolean includePersona) {
        // 공통 초기화
        this.topicConcluded = false;
        this.secretModeActive = false;
        this.endingReached = false;
        this.endingType = null;
        this.endingTitle = null;
        this.currentDynamicLocationName = null;
        this.currentDynamicCanonicalKey = null;
        this.currentDynamicBgUrl = null;
        if (includePersona) {
            this.userPersona = null;
        }

        if (isSandboxMode()) {
            resetSandboxFields();
        } else if (isStoryMode()) {
            resetStoryFields();
        }
    }

    private void resetSandboxFields() {
        this.affectionScore = 0;
        this.statusLevel = RelationStatus.STRANGER;
        this.statIntimacy = 0;  this.statAffection = 0;  this.statDependency = 0;
        this.statPlayfulness = 0;  this.statTrust = 0;
        this.statLust = 0;  this.statCorruption = 0;  this.statObsession = 0;
        this.dynamicRelationTag = "낯선 사람";
        this.characterThought = null;
        this.thoughtUpdatedAtTurn = 0;
        this.currentBpm = 65;
        this.lastEmotion = EmotionTag.NEUTRAL;
        this.lastIllustrationHint = null;
        // promotion/event/director 시스템 초기화 (V1 이관 자산)
        this.promotionPending = false;
        this.pendingTargetStatus = null;
        this.promotionMoodScore = 0;
        this.promotionTurnCount = 0;
        this.promotionWaitingForTopic = false;
        this.promotionWaitingTarget = null;
        this.eventActive = false;
        this.eventStatus = null;
        this.lastDirectorTurn = 0;
        this.activeDirectorConstraint = null;
        this.activeDirectorNarration = null;
        // 캐릭터 기본값으로 씬 상태 복원
        if (character != null) {
            this.currentLocation = parseLocationOrDefault(character.getEffectiveDefaultLocation());
            this.currentOutfit = parseOutfitOrDefault(character.getEffectiveDefaultOutfit());
        }
        this.currentTimeOfDay = TimeOfDay.NIGHT;
        this.currentBgmMode = BgmMode.DAILY;
    }

    private void resetStoryFields() {
        // 위치는 시작 장소로 복원해야 하나, 시작 장소 정보는 ChatRoom에 없으므로
        // 서비스 레이어({@code StoryService.resetRoom})가 currentUserLocationKey를 별도 갱신.
        this.currentDay = 1;
        this.currentDayPart = DayPart.defaultStart();
        this.endingEligible = false;
        this.endingEligibleSince = null;
        if (world != null) {
            this.currentBgmMode = parseBgmModeOrDefault(world.getDefaultBgm());
        }
        // currentUserLocationKey는 서비스 레이어에서 시작 장소로 재설정
    }

    /**
     * V2 Story reset 후 서비스 레이어가 시작 장소를 재설정할 때 사용.
     * {@code resetProgress(...)} 호출 직후 호출 권장.
     */
    public void restoreStartLocation(String startLocationKey) {
        requireStory();
        this.currentUserLocationKey = startLocationKey;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static int clamp(int min, int max, int v) {
        return Math.max(min, Math.min(max, v));
    }

    private static Location parseLocationOrDefault(String v) {
        try { return Location.valueOf(v); } catch (Exception e) { return Location.ENTRANCE; }
    }

    private static Outfit parseOutfitOrDefault(String v) {
        try { return Outfit.valueOf(v); } catch (Exception e) { return Outfit.MAID; }
    }

    private static BgmMode parseBgmModeOrDefault(String v) {
        try { return BgmMode.valueOf(v); } catch (Exception e) { return BgmMode.DAILY; }
    }
}