package com.spring.aichat.controller;

import com.spring.aichat.domain.ugc.UgcWorld;
import com.spring.aichat.domain.ugc.UgcWorldCreationJob;
import com.spring.aichat.domain.ugc.WorldCreationJobStatus;
import com.spring.aichat.dto.ugc.UgcDtos;
import com.spring.aichat.dto.ugc.UgcWorldDtos;
import com.spring.aichat.dto.ugc.WorldAssetState;
import com.spring.aichat.dto.ugc.WorldDraft;
import com.spring.aichat.dto.ugc.WorldIllustrationAssets;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.ugc.UgcAssetService;
import com.spring.aichat.service.ugc.UgcWorldJobJson;
import com.spring.aichat.service.ugc.UgcWorldService;
import com.spring.aichat.config.UgcPipelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [UGC 세계관 빌더] 월드 빌더 API — 계약은 {@link UgcWorldDtos} 참조.
 * 인증·소유권·레이트리밋 관례는 {@link CharacterCreationController}와 동일:
 * 소유권은 서비스가 검증(타인 404 은닉), 비용 유발 뮤테이션만 guardRate.
 */
@RestController
@RequestMapping("/api/v1/ugc/worlds")
@RequiredArgsConstructor
public class UgcWorldController {

    private final UgcWorldService worldService;
    private final UgcWorldJobJson json;
    private final UgcAssetService assetService;
    private final UgcPipelineProperties props;
    private final ApiRateLimiter rateLimiter;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  위저드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping
    public ResponseEntity<UgcWorldDtos.StartWorldResponse> start(
        Authentication authentication, @RequestBody UgcWorldDtos.StartWorldRequest request) {
        guardRate(authentication);
        Long jobId = worldService.startCreation(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new UgcWorldDtos.StartWorldResponse(jobId));
    }

    @GetMapping("/jobs/{jobId:\\d+}")
    public ResponseEntity<UgcWorldDtos.WorldCreationJobView> getJob(
        Authentication authentication, @PathVariable Long jobId) {
        UgcWorldCreationJob job = worldService.getOwnedJob(authentication.getName(), jobId);
        return ResponseEntity.ok(toJobView(job));
    }

    @PatchMapping("/jobs/{jobId:\\d+}/draft")
    public ResponseEntity<Void> updateDraft(
        Authentication authentication, @PathVariable Long jobId,
        @RequestBody UgcWorldDtos.DraftUpdateRequest request) {
        worldService.updateDraft(authentication.getName(), jobId, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/jobs/{jobId:\\d+}/illustrate")
    public ResponseEntity<Void> startIllustration(Authentication authentication, @PathVariable Long jobId) {
        guardRate(authentication);
        worldService.startIllustration(authentication.getName(), jobId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/jobs/{jobId:\\d+}/thumbnail/reroll")
    public ResponseEntity<Void> rerollThumbnail(Authentication authentication, @PathVariable Long jobId) {
        guardRate(authentication);
        worldService.rerollThumbnail(authentication.getName(), jobId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/jobs/{jobId:\\d+}/thumbnail/select")
    public ResponseEntity<Void> selectThumbnailVersion(
        Authentication authentication, @PathVariable Long jobId,
        @RequestBody UgcDtos.VersionSelectRequest request) {
        worldService.selectThumbnailVersion(authentication.getName(), jobId, requireIndex(request));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/jobs/{jobId:\\d+}/locations/{locationKey}/reroll")
    public ResponseEntity<Void> rerollLocation(
        Authentication authentication, @PathVariable Long jobId, @PathVariable String locationKey) {
        guardRate(authentication);
        worldService.rerollLocation(authentication.getName(), jobId, locationKey);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/jobs/{jobId:\\d+}/locations/{locationKey}/select")
    public ResponseEntity<Void> selectLocationVersion(
        Authentication authentication, @PathVariable Long jobId, @PathVariable String locationKey,
        @RequestBody UgcDtos.VersionSelectRequest request) {
        worldService.selectLocationVersion(authentication.getName(), jobId, locationKey, requireIndex(request));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/jobs/{jobId:\\d+}/confirm")
    public ResponseEntity<Void> confirm(Authentication authentication, @PathVariable Long jobId) {
        guardRate(authentication);
        worldService.confirm(authentication.getName(), jobId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/jobs/{jobId:\\d+}")
    public ResponseEntity<Void> abandon(Authentication authentication, @PathVariable Long jobId) {
        worldService.abandon(authentication.getName(), jobId);
        return ResponseEntity.ok().build();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내 월드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/mine")
    public ResponseEntity<UgcWorldDtos.MineResponse> mine(Authentication authentication) {
        String username = authentication.getName();
        List<UgcWorldDtos.UgcWorldView> worlds = worldService.getMyWorlds(username).stream()
            .map(w -> toWorldView(w, null))
            .toList();
        List<UgcWorldCreationJob> active = worldService.getActiveJobs(username);
        UgcWorldDtos.WorldCreationJobView activeJob = active.isEmpty() ? null : toJobView(active.get(0));
        return ResponseEntity.ok(new UgcWorldDtos.MineResponse(worlds, activeJob));
    }

    @GetMapping("/{worldId:\\d+}")
    public ResponseEntity<UgcWorldDtos.UgcWorldView> getWorld(
        Authentication authentication, @PathVariable Long worldId) {
        UgcWorld world = worldService.getOwnedWorld(authentication.getName(), worldId);
        List<UgcWorldDtos.WorldLocationView> locations = worldService.getLocations(worldId).stream()
            .map(l -> new UgcWorldDtos.WorldLocationView(
                l.getLocationKey(), l.getDisplayName(), l.getDescription(),
                l.getBackgroundUrl(), l.getStatus()))
            .toList();
        return ResponseEntity.ok(toWorldView(world, locations));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2026-07-22] READY 월드 사후 편집
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 설정 텍스트 수정 (무료 — 판정 이력은 NONE 리셋). */
    @PatchMapping("/{worldId:\\d+}")
    public ResponseEntity<Void> updateWorld(
        Authentication authentication, @PathVariable Long worldId,
        @RequestBody UgcWorldDtos.UpdateWorldRequest request) {
        worldService.updateWorld(authentication.getName(), worldId, request);
        return ResponseEntity.ok().build();
    }

    /** 장소 추가 (1E — 배경 1장 생성 포함, 상한 ugc.world.max-locations). */
    @PostMapping("/{worldId:\\d+}/locations")
    public ResponseEntity<UgcWorldDtos.AddLocationResponse> addLocation(
        Authentication authentication, @PathVariable Long worldId,
        @RequestBody UgcWorldDtos.AddLocationRequest request) {
        guardRate(authentication);
        String locationKey = worldService.addLocation(authentication.getName(), worldId, request);
        return ResponseEntity.ok(new UgcWorldDtos.AddLocationResponse(locationKey));
    }

    /** 배경 생성 재시도 (무료 — FAILED/멈춘 GENERATING 복구). */
    @PostMapping("/{worldId:\\d+}/locations/{locationKey}/retry")
    public ResponseEntity<Void> retryLocation(
        Authentication authentication, @PathVariable Long worldId, @PathVariable String locationKey) {
        guardRate(authentication);
        worldService.retryLocation(authentication.getName(), worldId, locationKey);
        return ResponseEntity.ok().build();
    }

    /** 실패 장소 삭제 (1E 환불). */
    @DeleteMapping("/{worldId:\\d+}/locations/{locationKey}")
    public ResponseEntity<Void> deleteFailedLocation(
        Authentication authentication, @PathVariable Long worldId, @PathVariable String locationKey) {
        worldService.deleteFailedLocation(authentication.getName(), worldId, locationKey);
        return ResponseEntity.ok().build();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  뷰 조립
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private UgcWorldDtos.WorldCreationJobView toJobView(UgcWorldCreationJob job) {
        UgcWorldDtos.DraftView draftView = null;
        if (job.getDraftWorldJson() != null) {
            WorldDraft draft = json.readDraft(job.getDraftWorldJson());
            draftView = new UgcWorldDtos.DraftView(
                draft.name(), draft.intro(), draft.lore(), draft.moodTags(),
                draft.locations().stream()
                    .map(l -> new UgcWorldDtos.DraftLocationView(l.locationKey(), l.displayName(), l.description()))
                    .toList());
        }

        UgcWorldDtos.WorldAssetView thumbnailView = null;
        Map<String, UgcWorldDtos.WorldAssetView> locationViews = new LinkedHashMap<>();
        if (job.getIllustrationAssetsJson() != null) {
            WorldIllustrationAssets assets = json.readAssets(job.getIllustrationAssetsJson());
            if (assets.thumbnail() != null) {
                thumbnailView = toAssetView(assets.thumbnail());
            }
            assets.locations().forEach((key, state) -> locationViews.put(key, toAssetView(state)));
        }

        return new UgcWorldDtos.WorldCreationJobView(
            job.getId(),
            job.getStatus().name(),
            job.getStatus() == WorldCreationJobStatus.CONCEPT_PROCESSING ? "CONCEPT_ANALYZING" : job.getStatus().name(),
            draftView,
            thumbnailView,
            locationViews,
            job.getEnergyCharged(),
            props.world().reroll(),
            props.world().locationsMax(),
            job.getFailReason(),
            job.getUgcWorldId(),
            job.getExpiresAt());
    }

    private UgcWorldDtos.WorldAssetView toAssetView(WorldAssetState state) {
        List<String> versions = new ArrayList<>(state.history().size());
        for (String key : state.history()) {
            versions.add(assetService.publicUrl(key));
        }
        Integer selectedIndex = state.key() != null && state.history().contains(state.key())
            ? state.history().indexOf(state.key()) : null;
        // 진행 중(GENERATING)에도 직전 선택본을 계속 노출 — 스피너 아래 유지 (감정 컷 UX 동형)
        return new UgcWorldDtos.WorldAssetView(
            state.status(), assetService.publicUrl(state.key()), versions, selectedIndex);
    }

    private UgcWorldDtos.UgcWorldView toWorldView(UgcWorld world, List<UgcWorldDtos.WorldLocationView> locations) {
        return new UgcWorldDtos.UgcWorldView(
            world.getId(), world.getName(), world.getIntro(), world.getLore(),
            splitMood(world.getMoodTags()), world.getThumbnailUrl(),
            world.getReviewStatus().name(), world.getCreatedAt(), locations);
    }

    private static List<String> splitMood(String moodCsv) {
        if (moodCsv == null || moodCsv.isBlank()) return List.of();
        return List.of(moodCsv.split("\\s*,\\s*"));
    }

    private static int requireIndex(UgcDtos.VersionSelectRequest request) {
        if (request == null || request.versionIndex() == null) {
            throw new BadRequestException("versionIndex가 필요합니다.");
        }
        return request.versionIndex();
    }

    private void guardRate(Authentication authentication) {
        if (rateLimiter.checkWorldMutation(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 5);
        }
    }
}
