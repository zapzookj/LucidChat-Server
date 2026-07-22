package com.spring.aichat.dto.ugc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * [UGC 세계관 빌더] 월드 빌더 API 계약 DTO 세트 ({@link UgcDtos} 관례 — camelCase).
 *
 * <pre>
 * POST   /api/v1/ugc/worlds                          {name?, moodHint?, concept} → 202 {jobId} (10E)
 * GET    /api/v1/ugc/worlds/jobs/{jobId}             WorldCreationJobView — 폴링 2.5s
 * PATCH  /api/v1/ugc/worlds/jobs/{jobId}/draft       DraftUpdateRequest (null=유지, locations=전체 교체)
 * POST   /api/v1/ugc/worlds/jobs/{jobId}/illustrate  EDIT_WAIT → ILLUSTRATING
 * POST   /api/v1/ugc/worlds/jobs/{jobId}/thumbnail/reroll          (READY=1E / FAILED=무료)
 * POST   /api/v1/ugc/worlds/jobs/{jobId}/thumbnail/select          {versionIndex} 무료
 * POST   /api/v1/ugc/worlds/jobs/{jobId}/locations/{KEY}/reroll    (READY=1E / FAILED=무료)
 * POST   /api/v1/ugc/worlds/jobs/{jobId}/locations/{KEY}/select    {versionIndex} 무료
 * POST   /api/v1/ugc/worlds/jobs/{jobId}/confirm     REVIEW_WAIT → BINDING (전 컷 READY 필요)
 * DELETE /api/v1/ugc/worlds/jobs/{jobId}             중도 포기(무환불)
 * GET    /api/v1/ugc/worlds/mine                     {worlds[], activeJob}
 * GET    /api/v1/ugc/worlds/{worldId}                UgcWorldView(장소 포함) — 소유자 전용(404 은닉)
 * </pre>
 */
public final class UgcWorldDtos {

    private UgcWorldDtos() {}

    // ── 요청 ──

    /** 컨셉 제출. name·moodHint는 선택. */
    public record StartWorldRequest(String name, String moodHint, String concept) {}

    /**
     * W1 드래프트 편집 — null 필드는 유지. {@code locations}는 null=유지 / 존재 시 <b>전체 교체</b>.
     * 장소 항목의 locationKey가 기존 드래프트와 일치하면 배경 프롬프트를 승계하고(설명 변경 시 재생성),
     * 없으면 신규 장소로 키를 발급한다.
     */
    public record DraftUpdateRequest(String name, String intro, String lore,
                                     List<String> moodTags, List<DraftLocationRequest> locations) {}

    public record DraftLocationRequest(String locationKey, String displayName, String description) {}

    // ── 응답 ──

    public record StartWorldResponse(Long jobId) {}

    /** 일러 컷 뷰 — status: GENERATING/READY/FAILED. versions=무료 골라잡기 후보(publicUrl). */
    public record WorldAssetView(String status, String url, List<String> versions, Integer selectedIndex) {}

    // ── [2026-07-22 READY 월드 사후 편집] ──

    /** 설정 텍스트 수정 (무료 — null 유지). 판정 이력이 있으면 NONE으로 리셋(재검수 대상). */
    public record UpdateWorldRequest(String name, String intro, String lore, List<String> moodTags) {}

    /** 장소 추가 (1E — 배경 1장 생성 포함). 설명은 배경 프롬프트 재료라 필수. */
    public record AddLocationRequest(String displayName, String description) {}

    public record AddLocationResponse(String locationKey) {}

    public record DraftLocationView(String locationKey, String displayName, String description) {}

    public record DraftView(String name, String intro, String lore,
                            List<String> moodTags, List<DraftLocationView> locations) {}

    /** 위저드 폴링 뷰 (2.5s). */
    public record WorldCreationJobView(
        Long jobId,
        String status,
        String currentStepHint,
        DraftView draft,
        WorldAssetView thumbnail,
        Map<String, WorldAssetView> locationAssets,
        int energySpent,
        int rerollCost,
        int maxLocations,
        String failReason,
        Long ugcWorldId,
        LocalDateTime expiresAt
    ) {}

    /** 완성 월드 뷰 — 목록에서는 locations=null, 상세에서만 포함. */
    public record UgcWorldView(
        Long worldId,
        String name,
        String intro,
        String lore,
        List<String> moodTags,
        String thumbnailUrl,
        String reviewStatus,
        LocalDateTime createdAt,
        List<WorldLocationView> locations
    ) {}

    /** status: READY / GENERATING(사후 추가 배경 생성 중) / FAILED(무료 재시도 또는 삭제+환불). */
    public record WorldLocationView(String locationKey, String displayName,
                                    String description, String backgroundUrl, String status) {}

    /** 스튜디오 월드 섹션 — 내 월드 + 진행 중 잡. */
    public record MineResponse(List<UgcWorldView> worlds, WorldCreationJobView activeJob) {}
}
