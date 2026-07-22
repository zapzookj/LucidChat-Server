package com.spring.aichat.controller;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.ugc.CharacterCreationJob;
import com.spring.aichat.domain.ugc.CreationJobStatus;
import com.spring.aichat.dto.ugc.EmotionAssetState;
import com.spring.aichat.dto.ugc.UgcDtos;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.RateLimitException;
import com.spring.aichat.security.ApiRateLimiter;
import com.spring.aichat.service.ugc.CharacterCreationService;
import com.spring.aichat.service.ugc.UgcAssetService;
import com.spring.aichat.service.ugc.UgcCharacterService;
import com.spring.aichat.service.ugc.UgcJobJson;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [UGC v1] 스튜디오 위저드 API (§3.6 계약 + mine/explore + secret-request).
 *
 * <pre>
 * POST   /api/v1/ugc/characters                          컨셉 제출 → 202 + jobId (에너지 20 차감)
 * GET    /api/v1/ugc/characters/mine                     내 캐릭터 + 진행 잡
 * GET    /api/v1/ugc/characters/explore?cursor=&limit=   공개 UGC 탐색 피드
 * GET    /api/v1/ugc/characters/{jobId}                  잡 상태 (프런트 2~3초 폴링)
 * POST   /api/v1/ugc/characters/{jobId}/golden-shot      {selectedIndex} 선택 | {reroll:true} 리롤(2)
 * POST   /api/v1/ugc/characters/{jobId}/emotions/{tag}/reroll   개별 리롤(2 · FAILED 컷은 무료)
 * POST   /api/v1/ugc/characters/{jobId}/confirm          검수 확정 → 누끼·바인딩
 * DELETE /api/v1/ugc/characters/{jobId}                  중도 포기 (무환불)
 * POST   /api/v1/ugc/characters/{characterId}/publish-request   공개 신청/취소 {cancel}
 * POST   /api/v1/ugc/characters/{characterId}/secret-request    Secret 단독 심사 신청
 * PATCH  /api/v1/ugc/characters/{characterId}/texts      설정 텍스트 인라인 수정 (무료)
 * </pre>
 *
 * <p>전부 소유자 검증 (타 유저 잡/캐릭터는 404 은닉). 뮤테이션은 rate limit 적용.
 */
@RestController
@RequestMapping("/api/v1/ugc/characters")
@RequiredArgsConstructor
public class CharacterCreationController {

    private final CharacterCreationService creationService;
    private final UgcCharacterService ugcCharacterService;
    private final UgcJobJson json;
    private final UgcAssetService assetService;
    private final UgcPipelineProperties props;
    private final ApiRateLimiter rateLimiter;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  위저드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping
    public ResponseEntity<UgcDtos.StartCreationResponse> start(
        @RequestBody UgcDtos.StartCreationRequest request,
        Authentication authentication
    ) {
        guardRate(authentication);
        Long jobId = creationService.startCreation(
            authentication.getName(), request.name(), request.concept(), request.appearance(),
            request.officialWorldId(), request.ugcWorldId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(new UgcDtos.StartCreationResponse(jobId));
    }

    @GetMapping("/{jobId:\\d+}")
    public ResponseEntity<UgcDtos.CreationJobView> getJob(
        @PathVariable Long jobId,
        Authentication authentication
    ) {
        CharacterCreationJob job = creationService.getOwnedJob(authentication.getName(), jobId);
        return ResponseEntity.ok(toView(job));
    }

    @PostMapping("/{jobId:\\d+}/golden-shot")
    public ResponseEntity<Void> goldenShotAction(
        @PathVariable Long jobId,
        @RequestBody UgcDtos.GoldenShotAction action,
        Authentication authentication
    ) {
        guardRate(authentication);
        if (Boolean.TRUE.equals(action.reroll())) {
            creationService.rerollGoldenShots(authentication.getName(), jobId, action.appearance());
        } else if (action.selectedIndex() != null) {
            creationService.selectGoldenShot(authentication.getName(), jobId, action.selectedIndex());
        } else {
            throw new BadRequestException("selectedIndex 또는 reroll 중 하나가 필요합니다.");
        }
        return ResponseEntity.ok().build();
    }

    /** [2026-07-20 개편] 스탠딩 후보 선택/리롤 (BASE_WAIT). */
    @PostMapping("/{jobId:\\d+}/base-standing")
    public ResponseEntity<Void> baseStandingAction(
        @PathVariable Long jobId,
        @RequestBody UgcDtos.GoldenShotAction action,
        Authentication authentication
    ) {
        guardRate(authentication);
        if (Boolean.TRUE.equals(action.reroll())) {
            creationService.rerollBaseCandidates(authentication.getName(), jobId);
        } else if (action.selectedIndex() != null) {
            creationService.selectBaseStanding(authentication.getName(), jobId, action.selectedIndex());
        } else {
            throw new BadRequestException("selectedIndex 또는 reroll 중 하나가 필요합니다.");
        }
        return ResponseEntity.ok().build();
    }

    /** [2026-07-20 개편] 프로필 초안 편집 — 생성 진행 중 레이턴시 하이딩 (Stage0 이후 ~ REVIEW_WAIT). */
    @PatchMapping("/{jobId:\\d+}/profile")
    public ResponseEntity<Void> updateProfile(
        @PathVariable Long jobId,
        @RequestBody UgcDtos.UpdateProfileRequest request,
        Authentication authentication
    ) {
        creationService.updateProfileDraft(authentication.getName(), jobId, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{jobId:\\d+}/emotions/{tag}/reroll")
    public ResponseEntity<Void> rerollEmotion(
        @PathVariable Long jobId,
        @PathVariable String tag,
        Authentication authentication
    ) {
        guardRate(authentication);
        creationService.rerollEmotion(authentication.getName(), jobId, parseEmotion(tag));
        return ResponseEntity.ok().build();
    }

    /** [2026-07-20 리롤 누적] 감정 컷 버전 골라잡기 (무과금 — REVIEW_WAIT). */
    @PostMapping("/{jobId:\\d+}/emotions/{tag}/select")
    public ResponseEntity<Void> selectEmotionVersion(
        @PathVariable Long jobId,
        @PathVariable String tag,
        @RequestBody UgcDtos.VersionSelectRequest request,
        Authentication authentication
    ) {
        if (request.versionIndex() == null) {
            throw new BadRequestException("versionIndex가 필요합니다.");
        }
        creationService.selectEmotionVersion(
            authentication.getName(), jobId, parseEmotion(tag), request.versionIndex());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{jobId:\\d+}/confirm")
    public ResponseEntity<Void> confirm(
        @PathVariable Long jobId,
        Authentication authentication
    ) {
        guardRate(authentication);
        creationService.confirmReview(authentication.getName(), jobId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{jobId:\\d+}")
    public ResponseEntity<Void> abandon(
        @PathVariable Long jobId,
        Authentication authentication
    ) {
        creationService.abandon(authentication.getName(), jobId);
        return ResponseEntity.ok().build();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스튜디오 목록
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/mine")
    public ResponseEntity<UgcDtos.MineResponse> mine(Authentication authentication) {
        String username = authentication.getName();
        List<Character> mine = ugcCharacterService.myCharacters(username);
        Map<Long, String> worldNames = ugcCharacterService.ugcWorldNames(mine);
        List<UgcDtos.UgcCharacterView> characters = mine.stream()
            .map(c -> toCharacterView(c, worldNames))
            .toList();
        List<CharacterCreationJob> active = creationService.getActiveJobs(username);
        UgcDtos.CreationJobView activeJob = active.isEmpty() ? null : toView(active.get(0));
        return ResponseEntity.ok(new UgcDtos.MineResponse(characters, activeJob));
    }

    @GetMapping("/explore")
    public ResponseEntity<UgcDtos.ExploreResponse> explore(
        @RequestParam(required = false) Long cursor,
        @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(ugcCharacterService.explore(cursor, limit));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  바인딩된 캐릭터 조작
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @PostMapping("/{characterId:\\d+}/publish-request")
    public ResponseEntity<Void> publishRequest(
        @PathVariable Long characterId,
        @RequestBody(required = false) UgcDtos.PublishAction action,
        Authentication authentication
    ) {
        guardRate(authentication);
        boolean cancel = action != null && Boolean.TRUE.equals(action.cancel());
        ugcCharacterService.requestPublish(authentication.getName(), characterId, cancel);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{characterId:\\d+}/secret-request")
    public ResponseEntity<Void> secretRequest(
        @PathVariable Long characterId,
        Authentication authentication
    ) {
        guardRate(authentication);
        ugcCharacterService.requestSecretReview(authentication.getName(), characterId);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{characterId:\\d+}/texts")
    public ResponseEntity<Void> updateTexts(
        @PathVariable Long characterId,
        @RequestBody UgcDtos.UpdateTextsRequest request,
        Authentication authentication
    ) {
        ugcCharacterService.updateTexts(authentication.getName(), characterId, request);
        return ResponseEntity.ok().build();
    }

    /** [세계관 빌더] 세계관 연결/변경/해제 (무료 — 카드 메뉴 소급 연결). */
    @PatchMapping("/{characterId:\\d+}/world")
    public ResponseEntity<Void> linkWorld(
        @PathVariable Long characterId,
        @RequestBody(required = false) UgcDtos.WorldLinkRequest request,
        Authentication authentication
    ) {
        ugcCharacterService.linkWorld(authentication.getName(), characterId, request);
        return ResponseEntity.ok().build();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  뷰 조립
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private UgcDtos.CreationJobView toView(CharacterCreationJob job) {
        List<String> goldenKeys = json.readKeys(job.getGoldenShotKeysJson());
        List<UgcDtos.GoldenShotView> goldenShots = new java.util.ArrayList<>();
        for (int i = 0; i < goldenKeys.size(); i++) {
            goldenShots.add(new UgcDtos.GoldenShotView(i, assetService.publicUrl(goldenKeys.get(i))));
        }

        List<com.spring.aichat.dto.ugc.BaseCandidate> candidates = json.readBaseCandidates(job.getBaseCandidatesJson());
        List<UgcDtos.BaseCandidateView> candidateViews = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            var c = candidates.get(i);
            // REFINING 상태의 key는 Qwen 편집본(리파인 전) — 유저에겐 READY만 이미지 노출
            String url = c.is(com.spring.aichat.dto.ugc.BaseCandidate.READY)
                ? assetService.publicUrl(c.key()) : null;
            candidateViews.add(new UgcDtos.BaseCandidateView(i, c.status(), url));
        }

        Map<String, UgcDtos.EmotionCutView> emotionViews = new LinkedHashMap<>();
        Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
        emotions.forEach((tag, state) -> {
            List<String> versions = state.history().stream().map(assetService::publicUrl).toList();
            int selected = state.key() == null ? -1 : state.history().indexOf(state.key());
            emotionViews.put(tag.name(), new UgcDtos.EmotionCutView(
                state.status(), assetService.publicUrl(state.key()),
                versions, selected < 0 ? null : selected));
        });

        UgcDtos.ProfileView profileView = null;
        if (job.getStructuredConceptJson() != null) {
            var p = json.readConcept(job.getStructuredConceptJson()).character();
            profileView = new UgcDtos.ProfileView(
                p.name(), p.tagline(), p.age(), p.role(), p.personality(), p.tone(),
                p.appearance(), p.clothing(), p.backstory(), p.coreValues(), p.flaws(),
                p.speechQuirks(), p.firstGreeting(), p.introNarration());
        }

        return new UgcDtos.CreationJobView(
            job.getId(),
            job.getStatus().name(),
            stepHint(job),
            goldenShots,
            candidateViews,
            assetService.publicUrl(job.getBaseStandingKey()),
            emotionViews,
            profileView,
            job.getEnergyCharged(),
            new UgcDtos.RerollCosts(props.energy().goldenReroll(),
                props.energy().baseReroll(), props.energy().emotionReroll()),
            job.getFailReason(),
            job.getCharacterId(),
            job.getExpiresAt()
        );
    }

    private String stepHint(CharacterCreationJob job) {
        if (job.getStatus() == CreationJobStatus.CONCEPT_PROCESSING) {
            return job.getStructuredConceptJson() == null ? "CONCEPT_ANALYZING" : "GOLDEN_GENERATING";
        }
        return job.getStatus().name();
    }

    private UgcDtos.UgcCharacterView toCharacterView(Character c, Map<Long, String> ugcWorldNames) {
        // [세계관 빌더] 연결 상태 — 공식은 enum displayName, UGC는 배치 해석된 월드 이름
        String worldType = null;
        String worldName = null;
        if (c.getWorldId() != null) {
            worldType = "OFFICIAL";
            worldName = c.getWorldId().getDisplayName();
        } else if (c.getUgcWorldId() != null) {
            worldType = "UGC";
            worldName = ugcWorldNames.get(c.getUgcWorldId());
        }
        return new UgcDtos.UgcCharacterView(
            c.getId(), c.getName(), c.getSlug(), c.getTagline(),
            c.getThumbnailUrl(), c.getDefaultImageUrl(),
            c.getVisibility().name(), c.isSecretEligible(),
            c.getSecretReviewStatus().name(), c.getReviewNote(),
            c.getPersonality(), c.getTone(), c.getFirstGreeting(),
            worldType,
            c.getWorldId() != null ? c.getWorldId().name() : null,
            c.getUgcWorldId(),
            worldName
        );
    }

    private EmotionTag parseEmotion(String tag) {
        try {
            return EmotionTag.valueOf(tag.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("알 수 없는 감정 태그: " + tag);
        }
    }

    private void guardRate(Authentication authentication) {
        if (rateLimiter.checkUgcMutation(authentication.getName())) {
            throw new RateLimitException("요청이 너무 빠릅니다.", 5);
        }
    }
}
