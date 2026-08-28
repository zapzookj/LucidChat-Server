package com.spring.aichat.domain.theater;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.AvatarStat;
import com.spring.aichat.domain.enums.TheaterAct;
import com.spring.aichat.domain.enums.TheaterEndingType;
import com.spring.aichat.domain.enums.WorldId;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * [Phase 5.5-Theater] Theater 세션 상태 엔티티
 *
 * ChatRoom과 1:1로 연결. Theater 전용 상태(Act/Chapter, 아바타, 스탯,
 * 현재 히로인, 엔딩, 난입 스냅샷 등) 집약.
 *
 * [설계 원칙]
 * - 1 ChatRoom(THEATER) = 1 TheaterState
 * - 멀티 히로인 상태는 별도 엔티티(TheaterHeroineAffection)에서 관리
 * - 분기/세이브/감독노트는 각각 독립 테이블
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "theater_states",
    indexes = {
        @Index(name = "idx_theater_room", columnList = "room_id", unique = true)
    })
public class TheaterState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * [Phase6/Tier4 / H-21] Optimistic Lock — Theater 배치/Chapter 동시성 보호.
     *   같은 roomId에 두 동시 next-batch 호출 시 addScenes/advanceBatch 중복으로
     *   batchId 중복 생성, scenesInCurrentChapter 이중 증가하던 결함 차단.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, unique = true)
    private ChatRoom room;

    /** [2026-07-31 에픽 A] nullable로 완화 — UGC 월드 세션은 {@code ugcWorldId}가 대신 채워진다(XOR). */
    @Enumerated(EnumType.STRING)
    @Column(name = "world_id", length = 50)
    private WorldId worldId;

    /** [2026-07-31 에픽 A] UGC 월드 세션 (worldId와 앱 레벨 XOR — V18, FK 미설정 관례). */
    @Column(name = "ugc_world_id")
    private Long ugcWorldId;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  서사 진행 상태
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Enumerated(EnumType.STRING)
    @Column(name = "current_act", nullable = false, length = 30)
    private TheaterAct currentAct = TheaterAct.ACT_1_MEETING;

    @Column(name = "current_chapter", nullable = false)
    private int currentChapter = 1;

    @Column(name = "scenes_in_current_chapter", nullable = false)
    private int scenesInCurrentChapter = 0;

    @Column(name = "chapter_target_scenes", nullable = false)
    private int chapterTargetScenes = 30;

    @Column(name = "total_scene_count", nullable = false)
    private long totalSceneCount = 0;

    @Column(name = "current_heroine_id")
    private Long currentHeroineId;

    @Column(name = "current_batch_id", nullable = false)
    private int currentBatchId = 0;

    /**
     * [버그픽스 B-5.2 · docs/17_assets/defect_register.md §B-5.2] <b>과금 워터마크</b> —
     * 이번 Chapter에서 <b>실제로 에너지가 차감된</b> 최대 batchId.
     *
     * <p><b>왜 DB 컬럼인가</b> — 레지스터의 수정안은 Redis(TheaterBatchCacheService)를 제안했지만
     * 돈 판정을 휘발 저장소에 두면 Redis 유실 시 정상 유저의 소비가 전부 거부된다.
     * 반대로 관대 모드로 폴백하면 게이트가 무의미해진다. 그래서 영속 컬럼으로 둔다(V30).
     *
     * <p><b>왜 Chapter 단위인가</b> — {@code currentBatchId}가 {@link #completeChapter()}·
     * {@link #advanceToNextAct()}에서 0으로 리셋되기 때문이다. 워터마크만 이전 Chapter 값을
     * 들고 있으면 새 Chapter의 배치가 전부 '이미 지불됨'이 되어 게이트가 통째로 뚫린다.
     * 따라서 두 리셋 지점에서 <b>반드시 함께</b> -1로 되돌린다.
     *
     * <p><b>NULL의 의미(grandfather)</b> — {@code NULL}은 "배포 이전부터 진행 중이던 세션"이다.
     * 이들에겐 과금 이력이 애초에 존재하지 않으므로 게이트를 걸면 <b>전 유저 장애</b>가 된다.
     * 소비 시점에 NULL이면 통과시키고 그 자리에서 워터마크를 세운다(TheaterService.onBatchConsumed).
     * 신규 세션은 이 초기값 {@code -1}("이번 Chapter에서 아직 아무것도 지불하지 않음")로
     * 생성되므로 NULL과 <b>구분된다</b> — 둘 다 통과시키면 게이트가 무의미해진다.
     */
    @Column(name = "last_paid_batch_id")
    private Integer lastPaidBatchId = -1;

    /**
     * [Phase 5.5 UX Polish · R2] Chapter당 MAJOR 분기를 1회만 발생시키기 위한 플래그.
     *  - false: 이번 Chapter에서 MAJOR가 아직 발생하지 않음
     *  - true:  이번 Chapter의 MAJOR가 이미 사용됨 (그 후 배치들은 MINOR로 처리)
     *  - completeChapter() 시 false로 리셋
     *
     * Boolean(객체)로 둬서 기존 데이터(NULL)에 안전 — 처음엔 null = 미사용 의미.
     */
    @Column(name = "major_branch_done_in_chapter")
    private Boolean majorBranchDoneInChapter = Boolean.FALSE;

    /**
     * [Phase 5.5 UX Polish · R4] 세션 상태 (모델 C-2: 활성 1 + 아카이브 N).
     *
     *  - "ACTIVE":   진행 중인 극 (유저당 1개만)
     *  - "ARCHIVED": 새 극 시작으로 중단되어 아카이브 보존 (resume 가능)
     *  - "ENDED":   엔딩 도달로 완결 (영구 보존, resume 불가)
     *
     *  String으로 둠 — enum class 추가 부담 없이 마이그레이션 친화적.
     *  null/누락은 ACTIVE로 간주 (기존 데이터 호환).
     */
    @Column(name = "session_status", length = 20)
    private String sessionStatus = "ACTIVE";

    /**
     * [Phase 5.5 UX Polish · R4] 마지막 활성/중단 시각 — 아카이브 정렬용.
     *  - ACTIVE → ARCHIVED 전환 시: 그 시각으로 갱신
     *  - ENDED 전환 시: 엔딩 도달 시각으로 갱신
     */
    @Column(name = "session_status_changed_at")
    private LocalDateTime sessionStatusChangedAt;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  아바타 프로필
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "avatar_name", length = 50)
    private String avatarName;

    @Column(name = "avatar_profile_json", columnDefinition = "TEXT")
    private String avatarProfileJson;

    @Column(name = "avatar_persona_text", columnDefinition = "TEXT")
    private String avatarPersonaText;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  아바타 5축 스탯
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "stat_charm", nullable = false)
    private int statCharm = 0;

    @Column(name = "stat_wit", nullable = false)
    private int statWit = 0;

    @Column(name = "stat_boldness", nullable = false)
    private int statBoldness = 0;

    @Column(name = "stat_intellect", nullable = false)
    private int statIntellect = 0;

    @Column(name = "stat_empathy", nullable = false)
    private int statEmpathy = 0;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  인터미션
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "intermission_stamina", nullable = false)
    private int intermissionStamina = 5;

    @Column(name = "in_intermission", nullable = false)
    private boolean inIntermission = false;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  엔딩
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "ending_reached", nullable = false)
    private boolean endingReached = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "ending_type", length = 30)
    private TheaterEndingType endingType;

    @Column(name = "ending_title", length = 200)
    private String endingTitle;

    @Column(name = "ending_main_heroine_id")
    private Long endingMainHeroineId;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  난입 (Intervention)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "intervention_active", nullable = false)
    private boolean interventionActive = false;

    @Column(name = "intervention_checkpoint_json", columnDefinition = "TEXT")
    private String interventionCheckpointJson;

    @Column(name = "intervention_last_log_id", length = 50)
    private String interventionLastLogId;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  재생 설정
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "auto_play_enabled", nullable = false)
    private boolean autoPlayEnabled = true;

    @Column(name = "play_speed", length = 20, nullable = false)
    private String playSpeed = "NORMAL";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  메타
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** [2026-07-31 에픽 A] 공식/UGC 공용 팩토리 — WorldRef 기준. */
    public static TheaterState create(ChatRoom room, com.spring.aichat.domain.world.WorldRef ref,
                                      String avatarName, String avatarProfileJson,
                                      String avatarPersonaText, AvatarStatDistribution initialStats) {
        TheaterState s = create(room, ref.officialId(), avatarName, avatarProfileJson,
            avatarPersonaText, initialStats);
        s.ugcWorldId = ref.ugcWorldId();
        return s;
    }

    /** [에픽 A] 세션의 월드 참조 — 공식/UGC 공용. 양쪽 다 없으면(손상 row) null. */
    public com.spring.aichat.domain.world.WorldRef getWorldRef() {
        if (worldId != null) return com.spring.aichat.domain.world.WorldRef.ofOfficial(worldId);
        if (ugcWorldId != null) return com.spring.aichat.domain.world.WorldRef.ofUgc(ugcWorldId);
        return null;
    }

    /** [에픽 A] DTO용 월드 키 문자열 — enum name 또는 {@code UGCW_{id}}. 손상 row는 "(unknown)". */
    public String worldRefKey() {
        com.spring.aichat.domain.world.WorldRef ref = getWorldRef();
        return ref != null ? ref.key() : "(unknown)";
    }

    /** [에픽 A] 세션이 해당 월드 참조와 일치하는가 — enum ==/null 비교의 공용 대체. */
    public boolean matchesWorld(com.spring.aichat.domain.world.WorldRef ref) {
        com.spring.aichat.domain.world.WorldRef mine = getWorldRef();
        return mine != null && ref != null && mine.key().equals(ref.key());
    }

    public static TheaterState create(ChatRoom room, WorldId worldId, String avatarName,
                                      String avatarProfileJson, String avatarPersonaText,
                                      AvatarStatDistribution initialStats) {
        TheaterState s = new TheaterState();
        s.room = room;
        s.worldId = worldId;
        s.avatarName = avatarName;
        s.avatarProfileJson = avatarProfileJson;
        s.avatarPersonaText = avatarPersonaText;
        if (initialStats != null) {
            s.statCharm = AvatarStat.clamp(initialStats.charm());
            s.statWit = AvatarStat.clamp(initialStats.wit());
            s.statBoldness = AvatarStat.clamp(initialStats.boldness());
            s.statIntellect = AvatarStat.clamp(initialStats.intellect());
            s.statEmpathy = AvatarStat.clamp(initialStats.empathy());
        }
        return s;
    }

    public record AvatarStatDistribution(int charm, int wit, int boldness, int intellect, int empathy) {
        public int total() { return charm + wit + boldness + intellect + empathy; }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  서사 진행 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void assignChapterTargetScenes(int target) {
        this.chapterTargetScenes = Math.max(1, target);
    }

    public void addScenes(int count) {
        this.scenesInCurrentChapter += count;
        this.totalSceneCount += count;
    }

    public boolean isChapterComplete() {
        return this.scenesInCurrentChapter >= this.chapterTargetScenes;
    }

    public void advanceBatch() {
        this.currentBatchId += 1;
    }

    /**
     * [B-5.2] 과금 워터마크 전진 — {@code chargeBatchEnergy} <b>성공 직후</b>에만 호출한다.
     * 후퇴하지 않는다(Math.max 의미론): 같은 배치를 두 번 받아 가거나(난입 복귀 후 재요청 등)
     * 이미 앞선 배치를 지불한 상태에서 옛 batchId가 들어와도 워터마크가 뒤로 밀리면
     * 정상 유저의 다음 소비가 막힌다.
     */
    public void markBatchPaid(int batchId) {
        if (this.lastPaidBatchId == null || batchId > this.lastPaidBatchId) {
            this.lastPaidBatchId = batchId;
        }
    }

    /**
     * [B-5.2] grandfather 승격 — 배포 이전 세션(NULL)의 첫 소비 지점에서만 호출한다.
     * NULL인 채로 두면 게이트가 영원히 켜지지 않고, 여기서 -1로 두면 그 유저는
     * 이미 지불한 현재 배치를 소비하지 못해 막힌다. 그래서 '지금 소비하는 배치까지는
     * 지불된 것으로 본다'로 승격한다.
     */
    public void adoptPaidWatermark(int batchId) {
        this.lastPaidBatchId = batchId;
    }

    public void setCurrentHeroine(Long heroineId) {
        this.currentHeroineId = heroineId;
    }

    public void completeChapter() {
        this.scenesInCurrentChapter = 0;
        this.currentBatchId = 0;
        // [B-5.2] currentBatchId가 0으로 돌아가므로 과금 워터마크도 반드시 함께 리셋한다.
        //   안 하면 새 Chapter의 batch 0..N이 이전 Chapter의 워터마크에 덮여 전부
        //   '이미 지불됨'으로 통과한다 — 게이트를 켜 놓고 구멍만 넓히는 꼴이 된다.
        this.lastPaidBatchId = -1;
        this.currentChapter += 1;
        // [R2] 새 Chapter 시작 시 MAJOR 분기 가능 상태로 reset
        this.majorBranchDoneInChapter = Boolean.FALSE;
        // [Phase 6 도그푸딩 #2 결함 B / Patch B-4] currentHeroineId는 의도적으로 보존.
        //   Chapter 전환 직후 첫 batch는 directorEngine이 hint(BatchCache.consumeHeroineHint)
        //   또는 currentHeroineId 우선 정책으로 같은 히로인을 이어가도록 결정한다.
    }

    public void advanceToNextAct() {
        TheaterAct next = this.currentAct.next();
        if (next == null) return;
        this.currentAct = next;
        this.currentChapter = 1;
        this.scenesInCurrentChapter = 0;
        this.currentBatchId = 0;
        // [B-5.2] completeChapter와 같은 이유 — Act 전환도 currentBatchId를 0으로 되돌린다.
        //   finalizeChapter는 completeChapter → advanceToNextAct 순으로 부르므로 중복 대입이지만,
        //   이 메서드가 단독 호출되는 경로가 생겨도 워터마크가 새지 않도록 여기서도 못 박는다.
        this.lastPaidBatchId = -1;
        this.intermissionStamina = 5;
        // [R2] Act 전환 시도 동일 reset
        this.majorBranchDoneInChapter = Boolean.FALSE;
        // [Phase 6 도그푸딩 #2 결함 B / Patch B-4] currentHeroineId 보존.
        //   ACT_4_RESOLUTION 진입 시점에 confirmMainHeroineIfApplicable이 메인 히로인을
        //   확정하면 decideNextSpeakerHeroine.pickMainHeroine이 그 히로인을 화자로 선택하게 된다.
        //   currentHeroineId를 reset해 버리면 Patch B-5 (c)의 자연 전환 컨텍스트도 활용 못 함.
    }

    /**
     * [R2] MAJOR 분기 발동 마킹 — DirectorEngine.decideBranchAfterBatch가
     *      MAJOR를 결정한 직후 호출되어, 같은 Chapter에서 두 번 발동 차단.
     */
    public void markMajorBranchDoneInChapter() {
        this.majorBranchDoneInChapter = Boolean.TRUE;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5 UX Polish · R4] 세션 상태 전이
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public boolean isActive() {
        return sessionStatus == null || "ACTIVE".equals(sessionStatus);
    }

    public boolean isArchived() {
        return "ARCHIVED".equals(sessionStatus);
    }

    public boolean isEnded() {
        return "ENDED".equals(sessionStatus);
    }

    /**
     * 새 극 시작으로 인한 중단 — 아카이브로 보존 (resume 가능).
     */
    public void archiveAsInterrupted() {
        this.sessionStatus = "ARCHIVED";
        this.sessionStatusChangedAt = LocalDateTime.now();
    }

    /**
     * 엔딩 도달 — 영구 완결 상태 (resume 불가).
     */
    public void markEnded() {
        this.sessionStatus = "ENDED";
        this.sessionStatusChangedAt = LocalDateTime.now();
    }

    /**
     * 아카이브된 극을 다시 활성화 (resume).
     */
    public void resumeFromArchive() {
        this.sessionStatus = "ACTIVE";
        this.sessionStatusChangedAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스탯 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public Map<AvatarStat, Integer> snapshotStats() {
        Map<AvatarStat, Integer> map = new EnumMap<>(AvatarStat.class);
        map.put(AvatarStat.CHARM, statCharm);
        map.put(AvatarStat.WIT, statWit);
        map.put(AvatarStat.BOLDNESS, statBoldness);
        map.put(AvatarStat.INTELLECT, statIntellect);
        map.put(AvatarStat.EMPATHY, statEmpathy);
        return map;
    }

    public int getStat(AvatarStat stat) {
        return switch (stat) {
            case CHARM -> statCharm;
            case WIT -> statWit;
            case BOLDNESS -> statBoldness;
            case INTELLECT -> statIntellect;
            case EMPATHY -> statEmpathy;
        };
    }

    public void applyStatChange(AvatarStat stat, int delta) {
        int updated = getStat(stat) + delta;
        int clamped = AvatarStat.clamp(updated);
        switch (stat) {
            case CHARM -> this.statCharm = clamped;
            case WIT -> this.statWit = clamped;
            case BOLDNESS -> this.statBoldness = clamped;
            case INTELLECT -> this.statIntellect = clamped;
            case EMPATHY -> this.statEmpathy = clamped;
        }
    }

    public AvatarStat dominantStat() {
        AvatarStat best = AvatarStat.CHARM;
        int bestValue = statCharm;
        if (statWit > bestValue) { best = AvatarStat.WIT; bestValue = statWit; }
        if (statBoldness > bestValue) { best = AvatarStat.BOLDNESS; bestValue = statBoldness; }
        if (statIntellect > bestValue) { best = AvatarStat.INTELLECT; bestValue = statIntellect; }
        if (statEmpathy > bestValue) { best = AvatarStat.EMPATHY; }
        return best;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  인터미션
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void startIntermission() {
        this.inIntermission = true;
        this.intermissionStamina = 5;
    }

    public void consumeIntermissionStamina() {
        if (this.intermissionStamina > 0) this.intermissionStamina -= 1;
    }

    public void endIntermission() {
        this.inIntermission = false;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  난입
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void enterIntervention(String checkpointJson) {
        this.interventionActive = true;
        this.interventionCheckpointJson = checkpointJson;
    }

    public void recordInterventionLog(String logId) {
        this.interventionLastLogId = logId;
    }

    public void exitIntervention() {
        this.interventionActive = false;
        this.interventionCheckpointJson = null;
        this.interventionLastLogId = null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  엔딩
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void markEndingReached(TheaterEndingType type, String title, Long mainHeroineId) {
        this.endingReached = true;
        this.endingType = type;
        this.endingTitle = title;
        this.endingMainHeroineId = mainHeroineId;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  설정 / 프로필 업데이트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void updatePlaySettings(boolean autoPlayEnabled, String playSpeed) {
        this.autoPlayEnabled = autoPlayEnabled;
        if (playSpeed != null && !playSpeed.isBlank()) {
            this.playSpeed = playSpeed.toUpperCase().trim();
        }
    }

    public void updateAvatarProfile(String avatarName, String avatarProfileJson, String avatarPersonaText) {
        if (avatarName != null && !avatarName.isBlank()) this.avatarName = avatarName.trim();
        if (avatarProfileJson != null) this.avatarProfileJson = avatarProfileJson;
        if (avatarPersonaText != null) this.avatarPersonaText = avatarPersonaText;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Theater] 세이브 슬롯으로부터 상태 복원
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 로드 시 TheaterSaveLoadService에서 호출.
     * 엔딩/인터미션/난입 플래그는 모두 리셋된다.
     */
    public void restoreFromSnapshot(TheaterAct act, int chapter, int scenesInChapter,
                                    int chapterTarget, long totalScenes,
                                    Long currentHeroineId, int batchId,
                                    int charm, int wit, int boldness, int intellect, int empathy,
                                    int intermissionStamina) {
        this.currentAct = act;
        this.currentChapter = chapter;
        this.scenesInCurrentChapter = scenesInChapter;
        this.chapterTargetScenes = chapterTarget;
        this.totalSceneCount = totalScenes;
        this.currentHeroineId = currentHeroineId;
        this.currentBatchId = batchId;
        // [B-5.2] 세이브 로드 — 되돌린 지점 **이후는 지불되지 않은 것**으로 둔다(batchId - 1).
        //   ① 앞서 있으면(= 로드 전 워터마크 유지) 되돌린 구간이 통째로 무료가 된다.
        //   ② 정상 유저를 막지 않는다 — TheaterSaveLoadService.loadSlot이 batchCache.purgeRoom을
        //      부르므로 로드 직후엔 소비할 캐시 자체가 없고, 유저는 반드시 /next-batch(과금)를
        //      한 번 거쳐야 한다. 그 호출이 markBatchPaid(batchId)로 워터마크를 다시 올린다.
        //   ③ NULL(배포 이전 세션)이었더라도 여기서 non-null이 되어 게이트가 정상 가동한다.
        this.lastPaidBatchId = batchId - 1;
        this.statCharm = AvatarStat.clamp(charm);
        this.statWit = AvatarStat.clamp(wit);
        this.statBoldness = AvatarStat.clamp(boldness);
        this.statIntellect = AvatarStat.clamp(intellect);
        this.statEmpathy = AvatarStat.clamp(empathy);
        this.intermissionStamina = Math.max(0, Math.min(5, intermissionStamina));
        this.inIntermission = false;
        this.interventionActive = false;
        this.interventionCheckpointJson = null;
        this.interventionLastLogId = null;
        this.endingReached = false;
        this.endingType = null;
        this.endingTitle = null;
        this.endingMainHeroineId = null;
    }
}