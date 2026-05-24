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

    public void updateSandboxAffection(int newScore) {
        requireSandbox();
        this.statAffection = clamp(-100, 100, newScore);
        this.affectionScore = this.statAffection;
    }

    public void applySandboxNormalStatChanges(int dIntimacy, int dAffection,
                                              int dDependency, int dPlayfulness, int dTrust) {
        requireSandbox();
        this.statIntimacy    = clamp(-100, 100, this.statIntimacy    + dIntimacy);
        this.statAffection   = clamp(-100, 100, this.statAffection   + dAffection);
        this.statDependency  = clamp(-100, 100, this.statDependency  + dDependency);
        this.statPlayfulness = clamp(-100, 100, this.statPlayfulness + dPlayfulness);
        this.statTrust       = clamp(-100, 100, this.statTrust       + dTrust);
        this.affectionScore  = this.statAffection;
    }

    public void applySandboxSecretStatChanges(int dLust, int dCorruption, int dObsession) {
        requireSandbox();
        this.statLust       = clamp(-100, 100, this.statLust       + dLust);
        this.statCorruption = clamp(-100, 100, this.statCorruption + dCorruption);
        this.statObsession  = clamp(-100, 100, this.statObsession  + dObsession);
    }

    public void updateSandboxStatusLevel(RelationStatus status) {
        requireSandbox();
        this.statusLevel = status;
    }

    public void updateSandboxDynamicRelationTag(String tag) {
        requireSandbox();
        this.dynamicRelationTag = tag;
    }

    public void updateSandboxCharacterThought(String thought, int currentTurnCount) {
        requireSandbox();
        this.characterThought = thought;
        this.thoughtUpdatedAtTurn = currentTurnCount;
    }

    public void updateSandboxBpm(int bpm) {
        requireSandbox();
        this.currentBpm = clamp(60, 180, bpm);
    }

    public void updateSandboxSceneState(BgmMode bgm, Location location, Outfit outfit, TimeOfDay time) {
        requireSandbox();
        if (bgm != null) this.currentBgmMode = bgm;
        if (location != null) {
            this.currentLocation = location;
            // Phase 6 hotfix: 동적 장소 활성 중에는 enum location으로 동적 배경 클리어 안 함
            if (this.currentDynamicLocationName == null) {
                clearDynamicBackground();
            }
        }
        if (outfit != null) this.currentOutfit = outfit;
        if (time != null) this.currentTimeOfDay = time;
    }

    public void updateSandboxLastIllustrationHint(String hint) {
        requireSandbox();
        if (hint != null && !hint.isBlank()) {
            this.lastIllustrationHint = hint.trim();
        }
    }

    public int getMaxNormalStatValueSandbox() {
        requireSandbox();
        return Math.max(statIntimacy, Math.max(statAffection,
            Math.max(statDependency, Math.max(statPlayfulness, statTrust))));
    }

    public int getMinNormalStatValueSandbox() {
        requireSandbox();
        return Math.min(statIntimacy, Math.min(statAffection,
            Math.min(statDependency, Math.min(statPlayfulness, statTrust))));
    }

    /**
     * Sandbox 엔딩 트리거 — 기존 V1 즉시 발동 패턴 유지 (Sandbox는 자유 채팅이라 LLM 이중 게이트 불필요).
     * STORY 모드에서는 {@link #activateEndingEligibility()} 사용.
     */
    public String checkSandboxEndingTrigger() {
        requireSandbox();
        if (this.endingReached) return null;
        if (getMaxNormalStatValueSandbox() >= 100) return "HAPPY";
        if (getMinNormalStatValueSandbox() <= -100) return "BAD";
        return null;
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