package com.spring.aichat.service.ugc;

import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.ugc.UgcWorld;
import com.spring.aichat.domain.ugc.UgcWorldCreationJob;
import com.spring.aichat.domain.ugc.UgcWorldCreationJobRepository;
import com.spring.aichat.domain.ugc.UgcWorldLocation;
import com.spring.aichat.domain.ugc.UgcWorldLocationRepository;
import com.spring.aichat.domain.ugc.UgcWorldRepository;
import com.spring.aichat.domain.ugc.WorldCreationJobStatus;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.ugc.UgcWorldDtos;
import com.spring.aichat.dto.ugc.WorldAssetState;
import com.spring.aichat.dto.ugc.WorldDraft;
import com.spring.aichat.dto.ugc.WorldIllustrationAssets;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.service.cache.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// (사후 편집: NotFoundException은 기존 import 사용)

/**
 * [UGC 세계관 빌더] 월드 생성 유저 액션 서비스 — 과금·소유권·상태 검증의 단일 지점
 * ({@link CharacterCreationService} 동형).
 *
 * <p>과금 규칙(2026-07-20 확정): 기본 패키지 10 / 썸네일·장소 배경 리롤 각 1 (실패 컷 재시도 무과금).
 * 실패 정책: 파이프라인 귀책 FAILED = 전액 환불 / EXPIRED·중도 포기 = 무환불.
 * 동시 1잡 정책은 <b>잡 타입별 독립</b> — 캐릭터 감정 파생 대기 중 월드 빌더 병행이 설계 전제.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UgcWorldService {

    static final int CONCEPT_MIN_LENGTH = 30;
    static final int CONCEPT_MAX_LENGTH = 1000;
    // 텍스트 상한은 W0 sanitize와 단일 소스 공유 — LLM 산출·유저 편집 어느 경로로도 우회 불가
    static final int INTRO_MAX_LENGTH = WorldConceptStructuringService.INTRO_MAX_LENGTH;
    /** lore는 시스템 프롬프트에 주입된다 — 캐릭터 정체성 희석 방지 상한 (도그푸딩 #3). */
    static final int LORE_MAX_LENGTH = WorldConceptStructuringService.LORE_MAX_LENGTH;
    static final int LOCATION_DESC_MAX_LENGTH = WorldConceptStructuringService.LOCATION_DESC_MAX_LENGTH;

    private static final List<WorldCreationJobStatus> ACTIVE_STATUSES =
        Arrays.stream(WorldCreationJobStatus.values()).filter(WorldCreationJobStatus::isActive).toList();

    private final UgcWorldCreationJobRepository jobRepository;
    private final UgcWorldRepository worldRepository;
    private final UgcWorldLocationRepository locationRepository;
    private final UserRepository userRepository;
    private final com.spring.aichat.domain.character.CharacterRepository characterRepository; // 사후 편집 심사 가드
    private final UgcPipelineProperties props;
    private final UgcModerationService moderationService;
    private final UgcWorldPipelineWorker worker;
    private final UgcWorldJobJson json;
    private final RedisCacheService cacheService;
    private final TransactionTemplate txTemplate;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  컨셉 제출 (W0)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public Long startCreation(String username, UgcWorldDtos.StartWorldRequest req) {
        String concept = req.concept() == null ? "" : req.concept().trim();
        if (concept.length() < CONCEPT_MIN_LENGTH || concept.length() > CONCEPT_MAX_LENGTH) {
            throw new BadRequestException(
                "컨셉은 %d자 이상 %d자 이하로 입력해 주세요.".formatted(CONCEPT_MIN_LENGTH, CONCEPT_MAX_LENGTH));
        }
        String name = blankToNull(req.name());
        if (name != null && name.length() > 50) {
            throw new BadRequestException("이름은 50자 이하로 입력해 주세요.");
        }
        String moodHint = blankToNull(req.moodHint());
        if (moodHint != null && moodHint.length() > 200) {
            throw new BadRequestException("무드 힌트는 200자 이하로 입력해 주세요.");
        }

        // 하드 키워드 게이트 — 에너지 차감 전 (유저 손실 없음)
        moderationService.assertRawConceptAllowed(concept);
        if (name != null) moderationService.assertRawConceptAllowed(name);
        if (moodHint != null) moderationService.assertRawConceptAllowed(moodHint);

        Long jobId = txTemplate.execute(tx -> {
            User user = findUser(username);
            if (jobRepository.existsByUserIdAndStatusIn(user.getId(), ACTIVE_STATUSES)) {
                throw new BadRequestException("이미 진행 중인 세계관 생성이 있어요. 완료하거나 정리한 뒤 다시 시도해 주세요.");
            }
            int cost = props.world().basePackage();
            user.consumeEnergy(cost); // 부족 시 InsufficientEnergyException — 차감 전 예외
            userRepository.save(user);

            UgcWorldCreationJob job = jobRepository.save(
                UgcWorldCreationJob.start(user.getId(), name, moodHint, concept, cost));
            return job.getId();
        });

        cacheService.evictUserProfile(username);
        worker.runStage0(jobId);
        log.info("[UGC-WORLD] 생성 시작: username={}, jobId={}", username, jobId);
        return jobId;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  W1 드래프트 편집 (EDIT_WAIT 전체 / REVIEW_WAIT 텍스트만)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 드래프트 수정 — null 필드는 유지. 장소 리스트는 전체 교체이며 EDIT_WAIT에서만 허용
     * (일러 시작 후에는 배경과 어긋나므로 잠금). 텍스트(이름/소개/lore/무드)는 REVIEW_WAIT에도 허용.
     */
    public void updateDraft(String username, Long jobId, UgcWorldDtos.DraftUpdateRequest req) {
        // 하드 키워드 게이트 — 수정 텍스트에도 동일 적용
        StringBuilder combined = new StringBuilder();
        append(combined, req.name());
        append(combined, req.intro());
        append(combined, req.lore());
        if (req.moodTags() != null) req.moodTags().forEach(t -> append(combined, t));
        if (req.locations() != null) {
            req.locations().forEach(l -> {
                append(combined, l.displayName());
                append(combined, l.description());
            });
        }
        moderationService.assertRawConceptAllowed(combined.toString());

        validateTextLimits(req);

        txTemplate.executeWithoutResult(tx -> {
            UgcWorldCreationJob job = lockOwnedJob(username, jobId);
            boolean editWait = job.getStatus() == WorldCreationJobStatus.EDIT_WAIT;
            boolean reviewWait = job.getStatus() == WorldCreationJobStatus.REVIEW_WAIT;
            if (!editWait && !reviewWait) {
                throw new BadRequestException("설정을 수정할 수 없는 단계입니다. (상태: " + job.getStatus() + ")");
            }
            if (req.locations() != null && !editWait) {
                throw new BadRequestException("일러스트 시작 후에는 장소를 수정할 수 없어요.");
            }

            WorldDraft draft = json.readDraft(job.getDraftWorldJson());
            List<WorldDraft.DraftLocation> locations = req.locations() != null
                ? mergeLocations(draft, req.locations())
                : draft.locations();

            WorldDraft merged = new WorldDraft(
                or(req.name(), draft.name()),
                or(req.intro(), draft.intro()),
                or(req.lore(), draft.lore()),
                req.moodTags() != null ? req.moodTags() : draft.moodTags(),
                draft.thumbnailPrompt(),
                locations);
            job.updateDraftWorld(json.writeDraft(merged));
        });
        log.info("[UGC-WORLD] 드래프트 수정: username={}, jobId={}", username, jobId);
    }

    private void validateTextLimits(UgcWorldDtos.DraftUpdateRequest req) {
        if (req.name() != null && req.name().trim().length() > 50) {
            throw new BadRequestException("이름은 50자 이하로 입력해 주세요.");
        }
        if (req.intro() != null && req.intro().length() > INTRO_MAX_LENGTH) {
            throw new BadRequestException("소개는 %d자 이하로 입력해 주세요.".formatted(INTRO_MAX_LENGTH));
        }
        if (req.lore() != null && req.lore().length() > LORE_MAX_LENGTH) {
            throw new BadRequestException("설정 본문은 %d자 이하로 입력해 주세요.".formatted(LORE_MAX_LENGTH));
        }
        if (req.moodTags() != null && (req.moodTags().size() > 10
            || req.moodTags().stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 20))) {
            throw new BadRequestException("무드 태그는 20자 이하 10개까지 입력해 주세요.");
        }
    }

    /**
     * 장소 전체 교체 병합 — 기존 키 일치 항목은 배경 프롬프트를 승계(설명 변경 시 재생성 대상으로 비움),
     * 신규 항목은 키 발급. 상한은 {@code ugc.world.max-locations}(기본 10).
     */
    private List<WorldDraft.DraftLocation> mergeLocations(WorldDraft draft,
                                                          List<UgcWorldDtos.DraftLocationRequest> requested) {
        int max = props.world().locationsMax();
        if (requested.isEmpty() || requested.size() > max) {
            throw new BadRequestException("장소는 1개 이상 %d개 이하로 구성해 주세요.".formatted(max));
        }

        Map<String, WorldDraft.DraftLocation> existing = new LinkedHashMap<>();
        draft.locations().forEach(l -> existing.put(l.locationKey(), l));

        List<WorldDraft.DraftLocation> result = new ArrayList<>(requested.size());
        Set<String> usedKeys = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            UgcWorldDtos.DraftLocationRequest r = requested.get(i);
            String displayName = r.displayName() == null ? "" : r.displayName().trim();
            if (displayName.isBlank() || displayName.length() > 100) {
                throw new BadRequestException("장소 이름은 1자 이상 100자 이하로 입력해 주세요.");
            }
            String description = r.description() == null ? null : r.description().trim();
            // [리뷰 픽스] 장소 설명 상한 — LLM 프롬프트화 입력·시스템 프롬프트 장소 풀에 직결되는 텍스트
            if (description != null && description.length() > LOCATION_DESC_MAX_LENGTH) {
                throw new BadRequestException(
                    "장소 설명은 %d자 이하로 입력해 주세요.".formatted(LOCATION_DESC_MAX_LENGTH));
            }

            WorldDraft.DraftLocation prior = r.locationKey() == null ? null : existing.get(r.locationKey().trim());
            String key;
            String backgroundPrompt;
            if (prior != null && !usedKeys.contains(prior.locationKey())) {
                key = prior.locationKey();
                // 설명이 그대로면 기존 프롬프트 승계, 바뀌면 일러 시작 시 재프롬프트화
                backgroundPrompt = Objects.equals(nz(prior.description()), nz(description))
                    ? prior.backgroundPrompt() : null;
            } else {
                key = WorldConceptStructuringService.normalizeLocationKey(r.locationKey(), i, usedKeys);
                backgroundPrompt = null;
            }
            usedKeys.add(key);
            result.add(new WorldDraft.DraftLocation(key, displayName, description, backgroundPrompt));
        }
        return result;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  W2 일러 시작 / 리롤 / 버전 선택
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 드래프트 확정 → 일러 병렬 생성 시작 (기본 패키지에 포함 — 추가 과금 없음). */
    public void startIllustration(String username, Long jobId) {
        txTemplate.executeWithoutResult(tx -> {
            UgcWorldCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, WorldCreationJobStatus.EDIT_WAIT);

            WorldDraft draft = json.readDraft(job.getDraftWorldJson());
            int max = props.world().locationsMax();
            if (draft.locations().isEmpty() || draft.locations().size() > max) {
                throw new BadRequestException("장소는 1개 이상 %d개 이하로 구성해 주세요.".formatted(max));
            }
            job.toIllustrating();
        });
        worker.runIllustration(jobId);
    }

    /** 썸네일 리롤 — READY=과금(1E) / FAILED=무료 재시도. */
    public void rerollThumbnail(String username, Long jobId) {
        rerollAsset(username, jobId, UgcWorldPipelineWorker.THUMB_TOKEN);
    }

    /** 장소 배경 리롤 — READY=과금(1E) / FAILED=무료 재시도. */
    public void rerollLocation(String username, Long jobId, String locationKey) {
        rerollAsset(username, jobId, UgcWorldPipelineWorker.LOC_TOKEN_PREFIX + requireLocationKey(locationKey));
    }

    private void rerollAsset(String username, Long jobId, String token) {
        boolean charged = Boolean.TRUE.equals(txTemplate.execute(tx -> {
            UgcWorldCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, WorldCreationJobStatus.REVIEW_WAIT);

            WorldAssetState state = requireAsset(job, token);
            // [리뷰 픽스] in-flight 컷 재리롤 차단 — 이중 과금 + 구세대/신세대 결과 경합으로
            // 결제분 산출물이 유실되는 레이스 원천 봉쇄 (더블클릭도 레이트리밋을 통과한다)
            if (state.is(WorldAssetState.GENERATING)) {
                throw new BadRequestException("이미 다시 만드는 중이에요. 완료 후 시도해 주세요.");
            }
            boolean free = state.is(WorldAssetState.FAILED);
            if (!free) {
                int cost = props.world().reroll();
                User user = findUser(username);
                user.consumeEnergy(cost);
                userRepository.save(user);
                job.chargeEnergy(cost);
            }
            worker.resetAssetForReroll(job, token);
            return !free;
        }));
        if (charged) {
            cacheService.evictUserProfile(username);
        }
        worker.runIllustrationReroll(jobId, token);
    }

    /** 썸네일 버전 골라잡기 (무과금). */
    public void selectThumbnailVersion(String username, Long jobId, int versionIndex) {
        selectVersion(username, jobId, UgcWorldPipelineWorker.THUMB_TOKEN, versionIndex);
    }

    /** 장소 배경 버전 골라잡기 (무과금). */
    public void selectLocationVersion(String username, Long jobId, String locationKey, int versionIndex) {
        selectVersion(username, jobId,
            UgcWorldPipelineWorker.LOC_TOKEN_PREFIX + requireLocationKey(locationKey), versionIndex);
    }

    private void selectVersion(String username, Long jobId, String token, int versionIndex) {
        txTemplate.executeWithoutResult(tx -> {
            UgcWorldCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, WorldCreationJobStatus.REVIEW_WAIT);

            WorldAssetState state = requireAsset(job, token);
            // [리뷰 픽스] in-flight 컷의 버전 선택 차단 — selectVersion이 GENERATING을 READY로
            // 뒤집으면 allReady()의 in-flight 차단이 무력화되어 미결 외부 잡을 든 채 confirm이 뚫린다
            if (state.is(WorldAssetState.GENERATING)) {
                throw new BadRequestException("생성 중인 컷이에요. 완료 후 버전을 선택해 주세요.");
            }
            if (state.history().isEmpty()) {
                throw new BadRequestException("선택할 수 있는 버전이 없습니다.");
            }
            if (versionIndex < 0 || versionIndex >= state.history().size()) {
                throw new BadRequestException("잘못된 버전 선택입니다.");
            }
            WorldIllustrationAssets assets = json.readAssets(job.getIllustrationAssetsJson());
            WorldAssetState selected = state.selectVersion(versionIndex);
            assets = UgcWorldPipelineWorker.THUMB_TOKEN.equals(token)
                ? assets.withThumbnail(selected)
                : assets.withLocation(token.substring(UgcWorldPipelineWorker.LOC_TOKEN_PREFIX.length()), selected);
            job.updateIllustrationAssets(json.writeAssets(assets));
        });
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  확정 / 중도 포기 / 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 검수 확정 — 썸네일 + 전 장소 배경 READY 필요 (FAILED 컷은 무료 재시도 유도). */
    public void confirm(String username, Long jobId) {
        txTemplate.executeWithoutResult(tx -> {
            UgcWorldCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, WorldCreationJobStatus.REVIEW_WAIT);

            WorldIllustrationAssets assets = json.readAssets(job.getIllustrationAssetsJson());
            if (!assets.allReady()) {
                throw new BadRequestException("아직 완성되지 않은 컷이 있어요. 실패한 컷을 다시 시도해 주세요.");
            }
            // [리뷰 픽스] 미결 외부 잡이 남은 채 BINDING 진입 금지 — 늦게 도착한 리롤 결과가
            // 바인딩 중 확정 에셋을 바꿔치기하는 레이스 차단 (정상 정착 시 스크래치는 항상 빈다)
            if (!json.readScratch(job.getExternalJobsJson()).isEmpty()) {
                throw new BadRequestException("아직 생성 중인 컷이 있어요. 완료 후 확정해 주세요.");
            }
            job.toBinding();
        });
        worker.bindWorld(jobId);
    }

    /** 중도 포기 — 무환불 정책(이미 LLM/이미지 비용 발생). */
    public void abandon(String username, Long jobId) {
        txTemplate.executeWithoutResult(tx -> {
            UgcWorldCreationJob job = lockOwnedJob(username, jobId);
            if (job.getStatus().isTerminal()) return; // 멱등
            job.fail("유저 중도 포기");
        });
        log.info("[UGC-WORLD] 중도 포기: username={}, jobId={}", username, jobId);
    }

    public UgcWorldCreationJob getOwnedJob(String username, Long jobId) {
        User user = findUser(username);
        return jobRepository.findByIdAndUserId(jobId, user.getId())
            .orElseThrow(() -> new NotFoundException("세계관 생성 잡을 찾을 수 없습니다. jobId=" + jobId));
    }

    /** 진행 중 잡 (스튜디오 진행 카드 — 타입별 동시 1잡 정책이라 0~1개). */
    public List<UgcWorldCreationJob> getActiveJobs(String username) {
        User user = findUser(username);
        return jobRepository.findByUserIdAndStatusInOrderByIdDesc(user.getId(), ACTIVE_STATUSES);
    }

    /** 내 월드 목록 (위저드 3택 '내 커스텀 월드' + 스튜디오 월드 섹션). */
    public List<UgcWorld> getMyWorlds(String username) {
        User user = findUser(username);
        return worldRepository.findByOwnerUserIdOrderByIdDesc(user.getId());
    }

    /** 월드 상세 (소유자 전용 — 타인 404 은닉). */
    public UgcWorld getOwnedWorld(String username, Long worldId) {
        User user = findUser(username);
        return worldRepository.findByIdAndOwnerUserId(worldId, user.getId())
            .orElseThrow(() -> new NotFoundException("세계관을 찾을 수 없습니다. worldId=" + worldId));
    }

    public List<UgcWorldLocation> getLocations(Long worldId) {
        return locationRepository.findByUgcWorldIdAndActiveTrueOrderByDisplayOrderAsc(worldId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2026-07-22] READY 월드 사후 편집 — 텍스트 수정(무료·판정 리셋) / 장소 추가(1E)
    //  (공유 월드 리스크는 현 단계 미고려 — 2026-07-22 종원 확정)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 설정 텍스트 수정 — 무료. 판정 이력(APPROVED/REJECTED)은 NONE으로 리셋(재검수 대상). */
    public void updateWorld(String username, Long worldId, UgcWorldDtos.UpdateWorldRequest req) {
        StringBuilder combined = new StringBuilder();
        append(combined, req.name());
        append(combined, req.intro());
        append(combined, req.lore());
        if (req.moodTags() != null) req.moodTags().forEach(t -> append(combined, t));
        moderationService.assertRawConceptAllowed(combined.toString());

        // [리뷰 픽스] 빈 이름은 조용한 무시 대신 명시 거부 — FE가 성공 토스트를 띄우는 혼선 차단
        if (req.name() != null && req.name().isBlank()) {
            throw new BadRequestException("이름은 비울 수 없어요.");
        }
        if (req.name() != null && req.name().trim().length() > 50) {
            throw new BadRequestException("이름은 50자 이하로 입력해 주세요.");
        }
        if (req.intro() != null && req.intro().length() > INTRO_MAX_LENGTH) {
            throw new BadRequestException("소개는 %d자 이하로 입력해 주세요.".formatted(INTRO_MAX_LENGTH));
        }
        if (req.lore() != null && req.lore().length() > LORE_MAX_LENGTH) {
            throw new BadRequestException("설정 본문은 %d자 이하로 입력해 주세요.".formatted(LORE_MAX_LENGTH));
        }
        if (req.moodTags() != null && (req.moodTags().size() > 10
            || req.moodTags().stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 20))) {
            throw new BadRequestException("무드 태그는 20자 이하 10개까지 입력해 주세요.");
        }

        txTemplate.executeWithoutResult(tx -> {
            UgcWorld world = ownedWorldOrThrow(username, worldId);
            requireNotUnderReview(worldId);
            // moodTags: null=유지 · 빈 배열=클리어(빈 문자열 마커 — joinMood는 빈 리스트에 null 반환)
            String moodCsv = null;
            if (req.moodTags() != null) {
                String joined = UgcWorldPipelineWorker.joinMood(req.moodTags());
                moodCsv = joined != null ? joined : "";
            }
            world.updateTexts(blankToNull(req.name()), req.intro(), req.lore(), moodCsv);
            worldRepository.save(world);
        });
        log.info("[UGC-WORLD] 사후 텍스트 수정: username={}, worldId={}", username, worldId);
    }

    /**
     * 장소 추가 — 1E(배경 1장 생성 포함). GENERATING 상태로 생성 후 워커가 프롬프트화→flux 생성.
     * 실패 시 FAILED 마킹(무료 재시도 또는 삭제+환불).
     */
    public String addLocation(String username, Long worldId, UgcWorldDtos.AddLocationRequest req) {
        String displayName = req.displayName() == null ? "" : req.displayName().trim();
        if (displayName.isBlank() || displayName.length() > 100) {
            throw new BadRequestException("장소 이름은 1자 이상 100자 이하로 입력해 주세요.");
        }
        String description = req.description() == null ? "" : req.description().trim();
        if (description.isBlank()) {
            throw new BadRequestException("장소 설명을 입력해 주세요. (배경 생성에 사용돼요)");
        }
        if (description.length() > LOCATION_DESC_MAX_LENGTH) {
            throw new BadRequestException(
                "장소 설명은 %d자 이하로 입력해 주세요.".formatted(LOCATION_DESC_MAX_LENGTH));
        }
        moderationService.assertRawConceptAllowed(displayName + " " + description);

        Long locationId = txTemplate.execute(tx -> {
            // [리뷰 픽스] 월드 row 비관적 락 — 동시 추가 2건이 상한 검사를 함께 통과하는 TOCTOU 차단
            User owner = findUser(username);
            UgcWorld locked = worldRepository.findByIdForUpdate(worldId)
                .filter(w -> w.isOwnedBy(owner.getId()))
                .orElseThrow(() -> new NotFoundException("세계관을 찾을 수 없습니다. worldId=" + worldId));
            requireNotUnderReview(locked.getId());
            List<UgcWorldLocation> existing = locationRepository.findByUgcWorldIdOrderByDisplayOrderAsc(worldId);
            long activeCount = existing.stream().filter(UgcWorldLocation::isActive).count();
            int max = props.world().locationsMax();
            if (activeCount >= max) {
                throw new BadRequestException("장소는 최대 %d개까지 만들 수 있어요.".formatted(max));
            }
            java.util.Set<String> used = existing.stream()
                .map(UgcWorldLocation::getLocationKey)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
            String key = WorldConceptStructuringService.normalizeLocationKey(displayName, existing.size(), used);
            int order = existing.stream().mapToInt(UgcWorldLocation::getDisplayOrder).max().orElse(-1) + 1;

            owner.consumeEnergy(props.world().reroll()); // 1E — 부족 시 차감 전 예외
            userRepository.save(owner);

            return locationRepository.save(
                UgcWorldLocation.createGenerating(worldId, key, displayName, description, order)).getId();
        });
        cacheService.evictUserProfile(username);
        worker.generateAddedLocationBackground(worldId, locationId);
        return locationRepository.findById(locationId)
            .map(UgcWorldLocation::getLocationKey).orElse(null);
    }

    /** 배경 생성 재시도 — 무료 (FAILED 또는 멈춘 GENERATING 복구). READY는 거부. */
    public void retryLocation(String username, Long worldId, String locationKey) {
        Long locationId = txTemplate.execute(tx -> {
            ownedWorldOrThrow(username, worldId);
            requireNotUnderReview(worldId);
            UgcWorldLocation loc = locationRepository
                .findByUgcWorldIdAndLocationKey(worldId, requireLocationKey(locationKey))
                .orElseThrow(() -> new NotFoundException("장소를 찾을 수 없습니다."));
            if (loc.is(UgcWorldLocation.READY)) {
                throw new BadRequestException("이미 완성된 장소예요.");
            }
            loc.markGenerating();
            return loc.getId();
        });
        worker.generateAddedLocationBackground(worldId, locationId);
    }

    /** 실패 장소 삭제 — 1E 환불 (생성 실패 귀책은 파이프라인). */
    public void deleteFailedLocation(String username, Long worldId, String locationKey) {
        txTemplate.executeWithoutResult(tx -> {
            ownedWorldOrThrow(username, worldId);
            requireNotUnderReview(worldId);
            UgcWorldLocation loc = locationRepository
                .findByUgcWorldIdAndLocationKey(worldId, requireLocationKey(locationKey))
                .orElseThrow(() -> new NotFoundException("장소를 찾을 수 없습니다."));
            if (!loc.is(UgcWorldLocation.FAILED)) {
                throw new BadRequestException("실패한 장소만 삭제할 수 있어요.");
            }
            locationRepository.delete(loc);
            User user = findUser(username);
            user.refundEnergy(props.world().reroll());
            userRepository.save(user);
        });
        cacheService.evictUserProfile(username);
        log.info("[UGC-WORLD] 실패 장소 삭제·환불: username={}, worldId={}, key={}", username, worldId, locationKey);
    }

    private UgcWorld ownedWorldOrThrow(String username, Long worldId) {
        User user = findUser(username);
        return worldRepository.findByIdAndOwnerUserId(worldId, user.getId())
            .orElseThrow(() -> new NotFoundException("세계관을 찾을 수 없습니다. worldId=" + worldId));
    }

    /**
     * [사후 편집 가드] 공개 심사 중인 캐릭터가 연결된 월드는 내용 변경 금지 —
     * 관리자가 상세에서 본 월드와 판정 대상이 달라지는 TOCTOU 방지 (linkWorld 차단과 동일 원칙).
     */
    private void requireNotUnderReview(Long worldId) {
        if (characterRepository.existsByUgcWorldIdAndVisibility(
            worldId, com.spring.aichat.domain.enums.CharacterVisibility.PENDING_PUBLIC)) {
            throw new BadRequestException("공개 심사 중인 캐릭터가 연결된 세계관은 수정할 수 없어요. 심사 후 변경해 주세요.");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private UgcWorldCreationJob lockOwnedJob(String username, Long jobId) {
        User user = findUser(username);
        UgcWorldCreationJob job = jobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new NotFoundException("세계관 생성 잡을 찾을 수 없습니다. jobId=" + jobId));
        if (!job.getUserId().equals(user.getId())) {
            // 소유 은닉 — 타인 잡의 존재를 노출하지 않는다
            throw new NotFoundException("세계관 생성 잡을 찾을 수 없습니다. jobId=" + jobId);
        }
        return job;
    }

    private WorldAssetState requireAsset(UgcWorldCreationJob job, String token) {
        WorldIllustrationAssets assets = json.readAssets(job.getIllustrationAssetsJson());
        WorldAssetState state = UgcWorldPipelineWorker.THUMB_TOKEN.equals(token)
            ? assets.thumbnail()
            : assets.locations().get(token.substring(UgcWorldPipelineWorker.LOC_TOKEN_PREFIX.length()));
        if (state == null) {
            throw new BadRequestException("알 수 없는 일러 컷입니다.");
        }
        return state;
    }

    private static String requireLocationKey(String locationKey) {
        if (locationKey == null || locationKey.isBlank()) {
            throw new BadRequestException("장소 키가 필요합니다.");
        }
        return locationKey.trim();
    }

    private static void requireStatus(UgcWorldCreationJob job, WorldCreationJobStatus expected) {
        if (job.getStatus() != expected) {
            throw new BadRequestException("현재 단계에서 수행할 수 없는 요청입니다. (상태: " + job.getStatus() + ")");
        }
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
    }

    private static String or(String override, String base) {
        return (override != null && !override.isBlank()) ? override.trim() : base;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static void append(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) sb.append(s).append(' ');
    }
}
