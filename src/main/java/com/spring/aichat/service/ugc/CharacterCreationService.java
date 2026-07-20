package com.spring.aichat.service.ugc;

import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.ugc.CharacterCreationJob;
import com.spring.aichat.domain.ugc.CharacterCreationJobRepository;
import com.spring.aichat.domain.ugc.CreationJobStatus;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.ugc.BaseCandidate;
import com.spring.aichat.dto.ugc.EmotionAssetState;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.dto.ugc.UgcDtos;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.service.cache.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * [UGC v1] 캐릭터 생성 유저 액션 서비스 — 과금(TX-1)·소유권·상태 검증의 단일 지점.
 *
 * <p>패턴: 검증 → 과금(잔액 부족 시 차감 전 예외) → 상태 전이 → 커밋 후 워커 kickoff.
 * 과금 규칙(2026-07-17 확정): 기본 패키지 20 / 리롤 2 (실패 컷 재시도는 무과금).
 * 실패 정책: 파이프라인 귀책 FAILED = 전액 환불 / EXPIRED·중도 포기 = 무환불.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterCreationService {

    static final int CONCEPT_MIN_LENGTH = 30;
    static final int CONCEPT_MAX_LENGTH = 1000;

    private static final List<CreationJobStatus> ACTIVE_STATUSES =
        Arrays.stream(CreationJobStatus.values()).filter(CreationJobStatus::isActive).toList();

    private final CharacterCreationJobRepository jobRepository;
    private final UserRepository userRepository;
    private final UgcPipelineProperties props;
    private final UgcModerationService moderationService;
    private final UgcPipelineWorker worker;
    private final UgcJobJson json;
    private final RedisCacheService cacheService;
    private final TransactionTemplate txTemplate;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  컨셉 제출 (위저드 화면 1)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public Long startCreation(String username, String requestedName, String conceptRaw,
                              UgcDtos.AppearanceHints appearanceHints) {
        String userConcept = conceptRaw == null ? "" : conceptRaw.trim();
        if (userConcept.length() < CONCEPT_MIN_LENGTH || userConcept.length() > CONCEPT_MAX_LENGTH) {
            throw new BadRequestException(
                "컨셉은 %d자 이상 %d자 이하로 입력해 주세요.".formatted(CONCEPT_MIN_LENGTH, CONCEPT_MAX_LENGTH));
        }
        String name = (requestedName == null || requestedName.isBlank()) ? null : requestedName.trim();
        if (name != null && name.length() > 50) {
            throw new BadRequestException("이름은 50자 이하로 입력해 주세요.");
        }

        // [2026-07-20 개편] 외형 구조화 힌트를 컨셉 원문에 병합 — Stage 0가 태그에 강제 반영
        String concept = withAppearanceHints(userConcept, appearanceHints);

        // 하드 키워드 게이트 — 에너지 차감 전 (유저 손실 없음)
        moderationService.assertRawConceptAllowed(concept);
        if (name != null) {
            moderationService.assertRawConceptAllowed(name);
        }

        Long jobId = txTemplate.execute(tx -> {
            User user = findUser(username);
            if (jobRepository.existsByUserIdAndStatusIn(user.getId(), ACTIVE_STATUSES)) {
                throw new BadRequestException("이미 진행 중인 캐릭터 생성이 있어요. 완료하거나 정리한 뒤 다시 시도해 주세요.");
            }
            int cost = props.energy().basePackage();
            user.consumeEnergy(cost); // 부족 시 InsufficientEnergyException — 차감 전 예외
            userRepository.save(user);

            CharacterCreationJob job = jobRepository.save(
                CharacterCreationJob.start(user.getId(), name, concept, cost));
            return job.getId();
        });

        cacheService.evictUserProfile(username);
        worker.runStage0(jobId);
        log.info("[UGC] 생성 시작: username={}, jobId={}", username, jobId);
        return jobId;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  황금샷 선택/리롤 (위저드 화면 2 · GACHA_WAIT)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void selectGoldenShot(String username, Long jobId, int selectedIndex) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.GACHA_WAIT);

            List<String> keys = json.readKeys(job.getGoldenShotKeysJson());
            if (selectedIndex < 0 || selectedIndex >= keys.size()) {
                throw new BadRequestException("잘못된 황금샷 선택입니다.");
            }
            job.toBaseProcessing(keys.get(selectedIndex));
        });
        worker.runBaseStage(jobId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스탠딩 후보 선택/리롤 (위저드 화면 2-b · BASE_WAIT) — 2026-07-20 개편
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 스탠딩 후보 확정 — 스타 토폴로지 원점과 감정 파생 seed가 여기서 고정된다. */
    public void selectBaseStanding(String username, Long jobId, int selectedIndex) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.BASE_WAIT);

            List<BaseCandidate> candidates = json.readBaseCandidates(job.getBaseCandidatesJson());
            if (selectedIndex < 0 || selectedIndex >= candidates.size()) {
                throw new BadRequestException("잘못된 스탠딩 선택입니다.");
            }
            BaseCandidate chosen = candidates.get(selectedIndex);
            if (!chosen.is(BaseCandidate.READY)) {
                throw new BadRequestException("아직 준비되지 않은 스탠딩입니다.");
            }
            job.toEmotionsProcessing(chosen.key());
            job.fixBaseEditSeed(chosen.seed());
            worker.initEmotionAssets(job, chosen.key());
        });
        worker.runEmotionStage(jobId);
    }

    /** 스탠딩 후보 배치 리롤 (과금 — Qwen 2패스×2 + WF-2×2 재파생). */
    public void rerollBaseCandidates(String username, Long jobId) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.BASE_WAIT);

            int cost = props.energy().baseReroll();
            User user = findUser(username);
            user.consumeEnergy(cost);
            userRepository.save(user);
            job.chargeEnergy(cost);
            job.restartBaseGeneration();
        });
        cacheService.evictUserProfile(username);
        worker.runBaseStage(jobId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  프로필 초안 편집 (레이턴시 하이딩 — Stage0 이후 ~ REVIEW_WAIT까지 상시)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 비외형 설정(성격/말투/서사/첫인사 등) 초안 수정 — 이미지와 무관한 텍스트라 생성 진행 중에도 안전.
     * null 필드는 유지. 외형 태그(appearance_tags)는 이미지가 이미 생성되므로 편집 불가.
     */
    public void updateProfileDraft(String username, Long jobId, UgcDtos.UpdateProfileRequest req) {
        // 하드 키워드 게이트 — 수정 텍스트에도 동일 적용
        String combined = String.join(" ",
            List.of(nz(req.name()), nz(req.tagline()), nz(req.personality()), nz(req.tone()),
                nz(req.appearance()), nz(req.clothing()), nz(req.backstory()), nz(req.coreValues()),
                nz(req.flaws()), nz(req.speechQuirks()), nz(req.firstGreeting()), nz(req.introNarration())));
        moderationService.assertRawConceptAllowed(combined);

        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            if (job.getStatus().isTerminal()
                || job.getStatus() == CreationJobStatus.POSTPROCESSING
                || job.getStatus() == CreationJobStatus.BINDING) {
                throw new BadRequestException("설정을 수정할 수 없는 단계입니다. (상태: " + job.getStatus() + ")");
            }
            if (job.getStructuredConceptJson() == null) {
                throw new BadRequestException("아직 설정 초안을 만드는 중이에요. 잠시 후 다시 시도해 주세요.");
            }

            StructuredConcept concept = json.readConcept(job.getStructuredConceptJson());
            StructuredConcept.CharacterProfile p = concept.character();

            // 첫인사는 편집본에도 정규화 규칙 재적용 (괄호 지문 → 나레이션 채널)
            String greeting = p.firstGreeting();
            String intro = or(req.introNarration(), p.introNarration());
            if (req.firstGreeting() != null) {
                var parts = ConceptStructuringService.normalizeGreeting(req.firstGreeting());
                greeting = parts.dialogue() != null ? parts.dialogue() : greeting;
                if (req.introNarration() == null && parts.extractedNarration() != null) {
                    intro = parts.extractedNarration();
                }
            }

            StructuredConcept.CharacterProfile updated = new StructuredConcept.CharacterProfile(
                or(req.name(), p.name()), or(req.tagline(), p.tagline()), p.age(),
                or(req.role(), p.role()), or(req.personality(), p.personality()), or(req.tone(), p.tone()),
                or(req.appearance(), p.appearance()), or(req.clothing(), p.clothing()),
                or(req.backstory(), p.backstory()), or(req.coreValues(), p.coreValues()),
                or(req.flaws(), p.flaws()), or(req.speechQuirks(), p.speechQuirks()),
                greeting, intro);

            StructuredConcept merged = new StructuredConcept(
                concept.appearanceTags(), concept.personaTags(), concept.sceneTags(),
                concept.bgColor(), updated, concept.moderation());
            job.applyStage0(json.writeConcept(merged), concept.bgColor());
        });
        log.info("[UGC] 프로필 초안 수정: username={}, jobId={}", username, jobId);
    }

    private static String or(String override, String base) {
        return (override != null && !override.isBlank()) ? override.trim() : base;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 외형 구조화 힌트를 [외형 지정] 블록으로 병합 — Stage 0 시스템 프롬프트가 이 블록을 태그에 강제 반영. */
    private static String withAppearanceHints(String concept, UgcDtos.AppearanceHints h) {
        if (h == null) return concept;
        StringBuilder block = new StringBuilder();
        appendHint(block, "머리", h.hair());
        appendHint(block, "눈", h.eyes());
        appendHint(block, "체형", h.body());
        appendHint(block, "의상", h.outfit());
        appendHint(block, "액세서리", h.accessories());
        appendHint(block, "기타", h.extra());
        if (block.isEmpty()) return concept;
        return concept + "\n\n[외형 지정 — appearance_tags에 반드시 반영]\n" + block;
    }

    private static void appendHint(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append("- ").append(label).append(": ").append(value.trim()).append("\n");
    }

    public void rerollGoldenShots(String username, Long jobId) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.GACHA_WAIT);

            int cost = props.energy().goldenReroll();
            User user = findUser(username);
            user.consumeEnergy(cost);
            userRepository.save(user);
            job.chargeEnergy(cost);
            job.restartGoldenGeneration();
        });
        cacheService.evictUserProfile(username);
        worker.runGoldenReroll(jobId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  감정 리롤 / 검수 확정 (위저드 화면 4 · REVIEW_WAIT)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 감정 1컷 리롤. FAILED 컷 = 무료 재시도 / READY 컷 = 유저 발의 리롤(과금).
     * NEUTRAL은 스타 토폴로지 원점(베이스)이라 리롤 불가 — 파생 14종의 일관성이 깨진다.
     */
    public void rerollEmotion(String username, Long jobId, EmotionTag tag) {
        if (tag == EmotionTag.NEUTRAL) {
            throw new BadRequestException("기본 표정은 다시 뽑을 수 없어요.");
        }
        boolean charged = Boolean.TRUE.equals(txTemplate.execute(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.REVIEW_WAIT);

            Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
            EmotionAssetState state = emotions.get(tag);
            if (state == null) {
                throw new BadRequestException("알 수 없는 감정 컷입니다.");
            }
            boolean free = state.is(EmotionAssetState.FAILED);
            if (!free) {
                int cost = props.energy().emotionReroll();
                User user = findUser(username);
                user.consumeEnergy(cost);
                userRepository.save(user);
                job.chargeEnergy(cost);
            }
            worker.resetEmotionForReroll(job, tag);
            return !free;
        }));
        if (charged) {
            cacheService.evictUserProfile(username);
        }
        worker.runEmotionReroll(jobId, tag);
    }

    /**
     * [2026-07-20 리롤 누적] 감정 컷 버전 골라잡기 — 누적된 완성본(history) 중 하나를 선택본으로 (무과금).
     */
    public void selectEmotionVersion(String username, Long jobId, EmotionTag tag, int versionIndex) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.REVIEW_WAIT);

            Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
            EmotionAssetState state = emotions.get(tag);
            if (state == null || state.history().isEmpty()) {
                throw new BadRequestException("선택할 수 있는 버전이 없습니다.");
            }
            if (versionIndex < 0 || versionIndex >= state.history().size()) {
                throw new BadRequestException("잘못된 버전 선택입니다.");
            }
            emotions.put(tag, state.selectVersion(versionIndex));
            job.updateEmotionAssets(json.writeEmotions(emotions));
        });
    }

    /** 검수 확정 — 15컷 전부 READY여야 한다 (FAILED 컷은 무료 재시도 유도). */
    public void confirmReview(String username, Long jobId) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            requireStatus(job, CreationJobStatus.REVIEW_WAIT);

            Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
            boolean allReady = emotions.size() == EmotionTag.values().length
                && emotions.values().stream().allMatch(s -> s.is(EmotionAssetState.READY));
            if (!allReady) {
                throw new BadRequestException("아직 완성되지 않은 컷이 있어요. 실패한 컷을 다시 시도해 주세요.");
            }
            job.toPostprocessing();
        });
        worker.runCutoutStage(jobId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  중도 포기 / 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 중도 포기 — 무환불 정책(이미 GPU/LLM 비용 발생). */
    public void abandon(String username, Long jobId) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = lockOwnedJob(username, jobId);
            if (job.getStatus().isTerminal()) return; // 멱등
            job.fail("유저 중도 포기");
        });
        log.info("[UGC] 중도 포기: username={}, jobId={}", username, jobId);
    }

    public CharacterCreationJob getOwnedJob(String username, Long jobId) {
        User user = findUser(username);
        return jobRepository.findByIdAndUserId(jobId, user.getId())
            .orElseThrow(() -> new NotFoundException("생성 잡을 찾을 수 없습니다. jobId=" + jobId));
    }

    /** 진행 중 잡 (스튜디오 진행 카드 — 동시 1잡 정책이라 0~1개). */
    public List<CharacterCreationJob> getActiveJobs(String username) {
        User user = findUser(username);
        return jobRepository.findByUserIdAndStatusInOrderByIdDesc(user.getId(), ACTIVE_STATUSES);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private CharacterCreationJob lockOwnedJob(String username, Long jobId) {
        User user = findUser(username);
        CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new NotFoundException("생성 잡을 찾을 수 없습니다. jobId=" + jobId));
        if (!job.getUserId().equals(user.getId())) {
            // 소유 은닉 — 타인 잡의 존재를 노출하지 않는다
            throw new NotFoundException("생성 잡을 찾을 수 없습니다. jobId=" + jobId);
        }
        return job;
    }

    private static void requireStatus(CharacterCreationJob job, CreationJobStatus expected) {
        if (job.getStatus() != expected) {
            throw new BadRequestException("현재 단계에서 수행할 수 없는 요청입니다. (상태: " + job.getStatus() + ")");
        }
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
    }
}
