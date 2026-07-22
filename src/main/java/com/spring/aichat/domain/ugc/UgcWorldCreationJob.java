package com.spring.aichat.domain.ugc;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [UGC 세계관 빌더] 월드 생성 잡 — 빌더 전 구간의 단일 진실원본.
 * {@link CharacterCreationJob}의 축소판이며 설계 관례를 그대로 따른다:
 *
 * <ul>
 *   <li>userId/ugcWorldId는 의도적 FK 미설정(Long 참조)</li>
 *   <li>구조화 데이터는 TEXT + 앱 레벨 Jackson 직렬화 (JSONB 금지 관례)</li>
 *   <li>동시 갱신(장소 배경 병렬 완료 경합)은 비관적 락({@code findByIdForUpdate})으로 직렬화</li>
 *   <li>S3 키 필드는 전부 서비스 자산 버킷 키 — fal presigned/CDN URL 저장 금지(수신 즉시 복사)</li>
 * </ul>
 *
 * <p>캐릭터 잡과 <b>독립 병행</b>이 설계 전제다 — 감정 파생 대기(~7분) 중 CTA로 월드 빌더에
 * 진입하므로, 동시 1잡 정책은 잡 타입별로 각각 적용된다(교차 차단 금지).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "ugc_world_creation_jobs", indexes = {
    @Index(name = "idx_uwcj_user", columnList = "user_id"),
    @Index(name = "idx_uwcj_status", columnList = "status"),
    @Index(name = "idx_uwcj_expires", columnList = "expires_at")
})
public class UgcWorldCreationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private WorldCreationJobStatus status = WorldCreationJobStatus.CONCEPT_PROCESSING;

    /** 유저가 직접 지정한 월드 이름 (선택 — null이면 W0 LLM이 작명). */
    @Column(name = "requested_name", length = 50)
    private String requestedName;

    /** 장르/무드 힌트 (선택 — W0 구조화 프롬프트에 부착). */
    @Column(name = "mood_hint", length = 200)
    private String moodHint;

    /** 유저 자유 서술 원문 — 이미지 프롬프트에 절대 직결되지 않는다(W0 구조화만 통과). */
    @Column(name = "concept_input_raw", nullable = false, columnDefinition = "TEXT")
    private String conceptInputRaw;

    /** W0 산출 전체(JSON — StructuredWorld: 이름/소개/lore/무드/장소 제안/썸네일 프롬프트/moderation). */
    @Column(name = "structured_world_json", columnDefinition = "TEXT")
    private String structuredWorldJson;

    /**
     * W1 편집 드래프트(JSON — WorldDraft). EDIT_WAIT 진입 시 W0 산출로 시딩되고
     * 유저 PATCH가 갱신한다. 일러(W2)와 확정(W3)은 이 드래프트만 읽는다.
     */
    @Column(name = "draft_world_json", columnDefinition = "TEXT")
    private String draftWorldJson;

    /**
     * W2 일러 상태(JSON — {thumbnail: WorldAssetState, locations: Map&lt;locationKey, WorldAssetState&gt;}).
     * 리롤 누적: history에 완성본 키가 쌓이고 유저가 무료로 골라잡는다(감정 컷 패턴 동형).
     */
    @Column(name = "illustration_assets_json", columnDefinition = "TEXT")
    private String illustrationAssetsJson;

    /**
     * 진행 중 외부 잡 추적(JSON — Map&lt;token, "PENDING"|fal requestId&gt;).
     * 제출 <b>전</b> PENDING 선커밋 → 제출 후 requestId 치환(H-16 교훈 — 외부 비용 누수 방지).
     * 서버 재시작 고아 잡 복구(스테일 스윕의 status 재확인)에 사용된다.
     */
    @Column(name = "external_jobs_json", columnDefinition = "TEXT")
    private String externalJobsJson;

    /** 누적 과금 에너지 (기본 패키지 + 리롤). 만료/실패 환불 정산 기준. */
    @Column(name = "energy_charged", nullable = false)
    private int energyCharged = 0;

    /** 현재 스테이지 자동 재시도 카운트 (스테이지 진입 시 리셋). */
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "fail_reason", length = 1000)
    private String failReason;

    /** READY 시 확정되는 월드 참조 (FK 미설정 관례). */
    @Column(name = "ugc_world_id")
    private Long ugcWorldId;

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

    public static UgcWorldCreationJob start(Long userId, String requestedName, String moodHint,
                                            String conceptInputRaw, int energyCharged) {
        UgcWorldCreationJob job = new UgcWorldCreationJob();
        job.userId = userId;
        job.requestedName = requestedName;
        job.moodHint = moodHint;
        job.conceptInputRaw = conceptInputRaw;
        job.energyCharged = energyCharged;
        job.status = WorldCreationJobStatus.CONCEPT_PROCESSING;
        return job;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  상태 전이 (오케스트레이터 전용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** W0 완료 — 구조화 산출을 드래프트로 시딩하고 유저 편집 대기로. */
    public void toEditWait(String draftWorldJson, int ttlHours) {
        requireActive();
        this.draftWorldJson = draftWorldJson;
        this.status = WorldCreationJobStatus.EDIT_WAIT;
        this.expiresAt = LocalDateTime.now().plusHours(ttlHours);
        this.retryCount = 0;
    }

    /** W2 진입 — 드래프트 확정 후 일러 병렬 생성. */
    public void toIllustrating() {
        requireActive();
        this.status = WorldCreationJobStatus.ILLUSTRATING;
        this.expiresAt = null;
        this.retryCount = 0;
    }

    /** W2 전 컷 정착 — 검수(리롤/버전 선택) 대기. */
    public void toReviewWait(int ttlHours) {
        requireActive();
        this.status = WorldCreationJobStatus.REVIEW_WAIT;
        this.expiresAt = LocalDateTime.now().plusHours(ttlHours);
        this.retryCount = 0;
    }

    /** W3 — 에셋 승격 + UgcWorld 확정 저장. */
    public void toBinding() {
        requireActive();
        this.status = WorldCreationJobStatus.BINDING;
        this.expiresAt = null;
        this.retryCount = 0;
    }

    public void toReady(Long ugcWorldId) {
        requireActive();
        this.ugcWorldId = ugcWorldId;
        this.status = WorldCreationJobStatus.READY;
        this.expiresAt = null;
    }

    /** 어느 단계에서든 실패 종결 — failReason·externalJobs 보존. */
    public void fail(String reason) {
        if (status.isTerminal()) return; // 멱등
        this.failReason = truncate(reason, 1000);
        this.status = WorldCreationJobStatus.FAILED;
        this.expiresAt = null;
    }

    /** *_WAIT 방치 만료 종결. */
    public void expire() {
        if (status.isTerminal()) return; // 멱등
        this.status = WorldCreationJobStatus.EXPIRED;
        this.expiresAt = null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스크래치 갱신
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void applyStage0(String structuredWorldJson) {
        this.structuredWorldJson = structuredWorldJson;
    }

    public void updateDraftWorld(String draftWorldJson) {
        this.draftWorldJson = draftWorldJson;
    }

    public void updateIllustrationAssets(String illustrationAssetsJson) {
        this.illustrationAssetsJson = illustrationAssetsJson;
    }

    public void updateExternalJobs(String externalJobsJson) {
        this.externalJobsJson = externalJobsJson;
    }

    /** 리롤 등 추가 과금 누적. */
    public void chargeEnergy(int amount) {
        this.energyCharged += amount;
    }

    public int incrementRetry() {
        return ++this.retryCount;
    }

    /**
     * 스테일 스윕 재부착 기록 — updatedAt만 갱신해 다음 스윕 창(staleMinutes)까지
     * 같은 잡에 대한 중복 재부착을 막는다 (더티 마킹용 명시 갱신, @PreUpdate가 최종값 확정).
     */
    public void touchRecovery() {
        this.updatedAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void requireActive() {
        if (status.isTerminal()) {
            throw new IllegalStateException("종결된 월드 잡의 상태 전이 시도: jobId=" + id + ", status=" + status);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
