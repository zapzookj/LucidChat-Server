package com.spring.aichat.domain.ugc;

import com.spring.aichat.domain.enums.WorldId;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [UGC v1] 캐릭터 생성 잡 — 위저드 전 구간의 단일 진실원본.
 *
 * <p>상태 전이는 {@code CharacterCreationService}(오케스트레이터)만 수행한다.
 * webhook/폴링/유저 액션은 이벤트를 공급할 뿐 직접 전이하지 않는다.
 *
 * <p>설계 관례 (V2 도메인과 동일):
 * <ul>
 *   <li>userId/characterId는 의도적 FK 미설정(Long 참조)</li>
 *   <li>구조화 데이터는 TEXT + 앱 레벨 Jackson 직렬화 (코드베이스 관례 — 스펙의 JSONB 대신)</li>
 *   <li>동시 갱신(감정 14종 webhook 경합)은 리포지토리 비관적 락({@code findByIdForUpdate})으로 직렬화</li>
 * </ul>
 *
 * <p>S3 키 필드는 전부 <b>서비스 자산 버킷 키</b>다 — RunPod/fal의 presigned URL은 저장 금지
 * (수신 즉시 복사 원칙).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "character_creation_jobs", indexes = {
    @Index(name = "idx_ccj_user", columnList = "user_id"),
    @Index(name = "idx_ccj_status", columnList = "status"),
    @Index(name = "idx_ccj_expires", columnList = "expires_at")
})
public class CharacterCreationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CreationJobStatus status = CreationJobStatus.CONCEPT_PROCESSING;

    /** 유저가 직접 지정한 이름 (선택 — null이면 Stage 0 LLM이 작명). */
    @Column(name = "requested_name", length = 50)
    private String requestedName;

    /** 유저 자유 서술 원문 — 이미지 프롬프트에 절대 직결되지 않는다(Stage 0 구조화만 통과). */
    @Column(name = "concept_input_raw", nullable = false, columnDefinition = "TEXT")
    private String conceptInputRaw;

    /** Stage 0 산출 전체(JSON — appearance_tags/scene_tags/bg_color/character/moderation). */
    @Column(name = "structured_concept_json", columnDefinition = "TEXT")
    private String structuredConceptJson;

    /** 누끼 경계 품질용 대비 배경색 (Stage 0 판정, Qwen 패스2·WF-2 두 곳에 동일 주입). */
    @Column(name = "bg_color", length = 40)
    private String bgColor;

    /** 황금샷 후보 서비스 S3 키 배열(JSON). 리롤 시 갱신. */
    @Column(name = "golden_shot_keys_json", columnDefinition = "TEXT")
    private String goldenShotKeysJson;

    @Column(name = "selected_golden_shot_key", length = 500)
    private String selectedGoldenShotKey;

    /**
     * [2026-07-20 개편] 스탠딩 후보 배열(JSON — [{key, seed, status}]).
     * 선택된 황금샷에서 서로 다른 seed로 병렬 파생된 후보들 — BASE_WAIT에서 유저 선택.
     */
    @Column(name = "base_candidates_json", columnDefinition = "TEXT")
    private String baseCandidatesJson;

    /** 스타 토폴로지 원점 — 유저가 확정한 베이스 스탠딩 키 (BASE_WAIT 선택 결과). */
    @Column(name = "base_standing_key", length = 500)
    private String baseStandingKey;

    /** Map&lt;EmotionTag, {key,status,retryCount}&gt; (JSON) — 감정별 개별 상태. */
    @Column(name = "emotion_assets_json", columnDefinition = "TEXT")
    private String emotionAssetsJson;

    /** 진행 중 외부 잡 추적(JSON — RunPod job id/fal request id). 디버깅·폴링 폴백용. */
    @Column(name = "external_jobs_json", columnDefinition = "TEXT")
    private String externalJobsJson;

    /** 감정 파생 seed 고정용 — 베이스 편집에서 확정된 Qwen seed. */
    @Column(name = "base_edit_seed")
    private Long baseEditSeed;

    /** 누적 과금 에너지 (기본 패키지 + 리롤). 만료/실패 환불 정산 기준. */
    @Column(name = "energy_charged", nullable = false)
    private int energyCharged = 0;

    /** 현재 스테이지 자동 재시도 카운트 (스테이지 진입 시 리셋). */
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "fail_reason", length = 1000)
    private String failReason;

    /** READY 시 확정되는 캐릭터 참조 (FK 미설정 관례). */
    @Column(name = "character_id")
    private Long characterId;

    /**
     * [세계관 빌더] 위저드 컨셉 스텝 3택(공식 4종 | 내 커스텀 월드 | 나중에 연결)의 공식 선택 —
     * 바인딩(Stage 4)에서 Character.worldId에 주입. requestedUgcWorldId와 배타(둘 다 null = 나중에 연결).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "requested_world_id", length = 50)
    private WorldId requestedWorldId;

    /** [세계관 빌더] 위저드 3택의 UGC 월드 선택 — 바인딩에서 Character.ugcWorldId에 주입. */
    @Column(name = "requested_ugc_world_id")
    private Long requestedUgcWorldId;

    /** *_WAIT 진입 시 설정되는 방치 만료 시각. 진행 재개 시 해제. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
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
    //  생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static CharacterCreationJob start(Long userId, String requestedName,
                                             String conceptInputRaw, int energyCharged) {
        CharacterCreationJob job = new CharacterCreationJob();
        job.userId = userId;
        job.requestedName = requestedName;
        job.conceptInputRaw = conceptInputRaw;
        job.energyCharged = energyCharged;
        job.status = CreationJobStatus.CONCEPT_PROCESSING;
        return job;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  상태 전이 (오케스트레이터 전용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void toGachaWait(String goldenShotKeysJson, int ttlHours) {
        requireActive();
        this.goldenShotKeysJson = goldenShotKeysJson;
        this.status = CreationJobStatus.GACHA_WAIT;
        this.expiresAt = LocalDateTime.now().plusHours(ttlHours);
        this.retryCount = 0;
    }

    /**
     * 황금샷 배치 리롤 — GACHA_WAIT에서 재생성 구간으로 복귀.
     * [2026-07-20 리롤 누적] 기존 후보는 보존한다 — 새 배치가 완료되면 뒤에 누적된다.
     */
    public void restartGoldenGeneration() {
        requireActive();
        this.status = CreationJobStatus.CONCEPT_PROCESSING;
        this.expiresAt = null;
        this.retryCount = 0;
    }

    public void toBaseProcessing(String selectedGoldenShotKey) {
        requireActive();
        this.selectedGoldenShotKey = selectedGoldenShotKey;
        this.baseCandidatesJson = null;
        this.status = CreationJobStatus.BASE_PROCESSING;
        this.expiresAt = null;
        this.retryCount = 0;
    }

    /** [2026-07-20 개편] 스탠딩 후보 준비 완료 — 유저 선택 대기. */
    public void toBaseWait(String baseCandidatesJson, int ttlHours) {
        requireActive();
        this.baseCandidatesJson = baseCandidatesJson;
        this.status = CreationJobStatus.BASE_WAIT;
        this.expiresAt = LocalDateTime.now().plusHours(ttlHours);
        this.retryCount = 0;
    }

    /**
     * 스탠딩 배치 리롤 — BASE_WAIT에서 재파생 구간으로 복귀.
     * [2026-07-20 리롤 누적] 기존 후보는 보존 — 새 후보가 리스트 뒤에 누적된다.
     */
    public void restartBaseGeneration() {
        requireActive();
        this.status = CreationJobStatus.BASE_PROCESSING;
        this.expiresAt = null;
        this.retryCount = 0;
    }

    public void updateBaseCandidates(String baseCandidatesJson) {
        this.baseCandidatesJson = baseCandidatesJson;
    }

    public void toEmotionsProcessing(String baseStandingKey) {
        requireActive();
        this.baseStandingKey = baseStandingKey;
        this.status = CreationJobStatus.EMOTIONS_PROCESSING;
        this.expiresAt = null;
        this.retryCount = 0;
    }

    public void toReviewWait(int ttlHours) {
        requireActive();
        this.status = CreationJobStatus.REVIEW_WAIT;
        this.expiresAt = LocalDateTime.now().plusHours(ttlHours);
        this.retryCount = 0;
    }

    public void toPostprocessing() {
        requireActive();
        this.status = CreationJobStatus.POSTPROCESSING;
        this.expiresAt = null;
        this.retryCount = 0;
    }

    public void toBinding() {
        requireActive();
        this.status = CreationJobStatus.BINDING;
        this.retryCount = 0;
    }

    public void toReady(Long characterId) {
        requireActive();
        this.characterId = characterId;
        this.status = CreationJobStatus.READY;
        this.expiresAt = null;
    }

    /** 어느 단계에서든 실패 종결 — failReason·externalJobs 보존. */
    public void fail(String reason) {
        if (status.isTerminal()) return; // 멱등
        this.failReason = truncate(reason, 1000);
        this.status = CreationJobStatus.FAILED;
        this.expiresAt = null;
    }

    /** *_WAIT 방치 만료 종결. */
    public void expire() {
        if (status.isTerminal()) return; // 멱등
        this.status = CreationJobStatus.EXPIRED;
        this.expiresAt = null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스크래치 갱신
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void applyStage0(String structuredConceptJson, String bgColor) {
        this.structuredConceptJson = structuredConceptJson;
        this.bgColor = bgColor;
    }

    public void updateGoldenShotKeys(String goldenShotKeysJson) {
        this.goldenShotKeysJson = goldenShotKeysJson;
    }

    public void updateEmotionAssets(String emotionAssetsJson) {
        this.emotionAssetsJson = emotionAssetsJson;
    }

    public void updateExternalJobs(String externalJobsJson) {
        this.externalJobsJson = externalJobsJson;
    }

    public void fixBaseEditSeed(Long seed) {
        this.baseEditSeed = seed;
    }

    /** [세계관 빌더] 위저드 3택 세계관 요청 기록 (시작 시 1회 — 둘 다 null이면 '나중에 연결'). */
    public void assignRequestedWorld(WorldId requestedWorldId, Long requestedUgcWorldId) {
        if (requestedWorldId != null && requestedUgcWorldId != null) {
            throw new IllegalArgumentException("공식 세계관과 UGC 월드는 동시 지정 불가");
        }
        this.requestedWorldId = requestedWorldId;
        this.requestedUgcWorldId = requestedUgcWorldId;
    }

    /** 리롤 등 추가 과금 누적. */
    public void chargeEnergy(int amount) {
        this.energyCharged += amount;
    }

    public int incrementRetry() {
        return ++this.retryCount;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void requireActive() {
        if (status.isTerminal()) {
            throw new IllegalStateException("종결된 잡의 상태 전이 시도: jobId=" + id + ", status=" + status);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
