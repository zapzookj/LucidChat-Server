package com.spring.aichat.service.ugc;

import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.ugc.CharacterCreationJob;
import com.spring.aichat.domain.ugc.CharacterCreationJobRepository;
import com.spring.aichat.domain.ugc.CreationJobStatus;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.ugc.BaseCandidate;
import com.spring.aichat.dto.ugc.EmotionAssetState;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.exception.ContentModerationException;
import com.spring.aichat.external.PoseEditClient;
import com.spring.aichat.external.UgcComfyClient;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * [UGC v1] 파이프라인 워커 — 스테이지 실행·외부 이벤트 처리의 단일 지점.
 *
 * <p>역할 분담: {@link CharacterCreationService}(유저 액션·과금 TX) → 이 워커(@Async 스테이지 실행)
 * → webhook/폴러가 {@link #onComfyEvent}로 결과 공급 → 상태 전이는 전부 잡 비관적 락 TX 안에서.
 *
 * <p>불변 설계 원칙(스펙 §2):
 * <ul>
 *   <li>스타 토폴로지 — 모든 감정은 베이스 스탠딩 1장에서 직접 파생 (체인 편집 금지)</li>
 *   <li>presigned URL 저장 금지 — 수신 즉시 서비스 S3 복사(UgcAssetService)</li>
 *   <li>실패 정책: 파이프라인 귀책 실패 = 누적 에너지 전액 환불 / 유저 방치(EXPIRED)·중도 포기 = 무환불</li>
 *   <li>감정 파생 seed는 베이스 편집 seed로 고정(캐릭터 일관성) — 유저 리롤 시에만 새 seed</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UgcPipelineWorker {

    private static final Duration PRESIGN_TTL = Duration.ofHours(2); // fal 큐 대기 커버
    /** externalJobs 스크래치 맵의 내부 키 접두어 — 폴러가 RunPod id로 오인하지 않도록 구분. */
    private static final String SCRATCH_KEY_PREFIX = "K_";
    /** [2026-07-21 리롤 외형 수정] 대기 중인 외형 지정 블록 — 서비스가 저장, 리롤 워커가 소비. */
    static final String APPEARANCE_EDIT_KEY = SCRATCH_KEY_PREFIX + "APPEARANCE_EDIT";
    /** [2026-07-20 개편] 스탠딩 후보 수 — 유저가 BASE_WAIT에서 선택. */
    static final int BASE_CANDIDATE_COUNT = 2;

    private final CharacterCreationJobRepository jobRepository;
    private final CharacterRepository characterRepository;
    private final UserRepository userRepository;
    private final UgcPipelineProperties props;
    private final OpenAiProperties openAiProps;
    private final ConceptStructuringService conceptStructuringService;
    private final UgcModerationService moderationService;
    private final UgcPromptAssembler promptAssembler;
    private final UgcWorkflowFactory workflowFactory;
    private final UgcComfyClient comfyClient;
    private final PoseEditClient poseEditClient;
    private final UgcAssetService assetService;
    private final UgcJobJson json;
    private final RedisCacheService cacheService;
    private final NotificationService notificationService;
    private final TransactionTemplate txTemplate;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Stage 0 → Stage 1 (컨셉 구조화 → 황금샷 제출)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void runStage0(Long jobId) {
        CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != CreationJobStatus.CONCEPT_PROCESSING) return;

        try {
            runWithRetries(jobId, "STAGE0", () -> {
                StructuredConcept concept = conceptStructuringService.structure(
                    job.getConceptInputRaw(), job.getRequestedName());
                moderationService.assertStructuredConceptAllowed(concept);

                String conceptJson = json.writeConcept(concept);
                mutateJob(jobId, j -> j.applyStage0(conceptJson, concept.bgColor()));
                submitGoldenShots(jobId, concept);
            });
        } catch (ContentModerationException e) {
            // LLM 판정 차단 — 이미 과금된 상태이므로 전액 환불 (게이트 원칙: 유저 에너지 손실 없음)
            failAndRefund(jobId, UgcModerationService.BLOCK_MESSAGE);
        } catch (Exception e) {
            failAndRefund(jobId, "컨셉 처리 실패: " + e.getMessage());
        }
    }

    private void submitGoldenShots(Long jobId, StructuredConcept concept) {
        String positive = promptAssembler.goldenShotPositive(
            concept.appearanceTags(), concept.personaTags(), concept.sceneTags());
        var workflow = workflowFactory.buildGoldenShot(positive, "job_" + jobId + "_golden");
        var submit = comfyClient.submit(workflow, null, webhookUrl(jobId, UgcStage.GOLDEN, null));
        recordExternalJob(jobId, UgcStage.GOLDEN.name(), submit.jobId());
        log.info("[UGC-WORKER] WF-1 submitted: jobId={}, runpod={}", jobId, submit.jobId());
    }

    /** 황금샷 배치 리롤 (과금은 서비스 계층에서 완료된 상태). */
    @Async
    public void runGoldenReroll(Long jobId) {
        CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != CreationJobStatus.CONCEPT_PROCESSING) return;
        try {
            // [2026-07-21 리롤 외형 수정] 외형 지정이 동봉된 리롤 — 외형 전용 경량 재구조화 후 제출.
            // 페르소나·서사·유저 편집분은 보존되고 외형 태그·씬·배경색·외형 서술만 바뀐다.
            String hintsBlock = json.readScratch(job.getExternalJobsJson()).get(APPEARANCE_EDIT_KEY);
            if (hintsBlock != null && !hintsBlock.isBlank()) {
                runWithRetries(jobId, "APPEARANCE_EDIT", () -> {
                    CharacterCreationJob fresh = jobRepository.findById(jobId).orElseThrow();
                    StructuredConcept current = json.readConcept(fresh.getStructuredConceptJson());
                    StructuredConcept updated = conceptStructuringService.restructureAppearance(
                        fresh.getConceptInputRaw(), current, hintsBlock);
                    moderationService.assertStructuredConceptAllowed(updated);
                    // [리뷰 픽스] LLM 콜(수 초~수십 초) 동안 커밋된 프로필 편집(레이턴시 하이딩)이
                    // 스냅샷 기반 전체 덮어쓰기로 유실되지 않도록, 락 안에서 최신본을 재조회해
                    // 외형 산출 필드만 병합한다 (deriveEmotionPromptsSafely 동일 패턴).
                    mutateJob(jobId, j -> {
                        StructuredConcept latest = json.readConcept(j.getStructuredConceptJson());
                        j.applyStage0(json.writeConcept(latest.withAppearanceFrom(updated)), updated.bgColor());
                        removeExternalJob(j, APPEARANCE_EDIT_KEY);
                    });
                    submitGoldenShots(jobId, updated);
                });
                return;
            }
            runWithRetries(jobId, "GOLDEN_REROLL",
                () -> submitGoldenShots(jobId, json.readConcept(job.getStructuredConceptJson())));
        } catch (ContentModerationException e) {
            // LLM 판정 차단 — 누적 과금 전액 환불이므로 유저 금전 손실 없음 (Stage0 차단과 동일 정책)
            failAndRefund(jobId, UgcModerationService.BLOCK_MESSAGE);
        } catch (Exception e) {
            failAndRefund(jobId, "황금샷 리롤 실패: " + e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Stage 2 (스탠딩 후보 — Qwen 2패스 ×N 병렬 → WF-2)
    //  [2026-07-20 개편] PoC 확정 설계(Qwen 패스)는 유지하되, 서로 다른 seed의 후보 N장을
    //  만들어 BASE_WAIT에서 유저가 선택·리롤할 수 있게 한다 (기존: 단일 파생·선택 불가).
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 스탠딩 후보 생성 시작 — 최초 진입·배치 리롤 공용 (BASE_PROCESSING 상태 전제).
     * [2026-07-20 리롤 누적] 기존 후보를 보존한 채 새 후보 N개를 리스트 뒤에 붙여 파생한다.
     */
    @Async
    public void runBaseStage(Long jobId) {
        CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != CreationJobStatus.BASE_PROCESSING) return;

        Integer startIndex = txTemplate.execute(tx -> {
            CharacterCreationJob locked = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (locked == null || locked.getStatus() != CreationJobStatus.BASE_PROCESSING) return null;
            List<BaseCandidate> candidates = new ArrayList<>(json.readBaseCandidates(locked.getBaseCandidatesJson()));
            int start = candidates.size();
            for (int i = 0; i < BASE_CANDIDATE_COUNT; i++) {
                candidates.add(BaseCandidate.deriving(0));
            }
            locked.updateBaseCandidates(json.writeBaseCandidates(candidates));
            return start;
        });
        if (startIndex == null) return;
        for (int i = startIndex; i < startIndex + BASE_CANDIDATE_COUNT; i++) {
            submitBaseCandidate(jobId, i);
        }
    }

    /**
     * 스탠딩 후보 1건 파생: Qwen 패스1(자세·구도) → 패스2(배경·조명 — BG_COLOR는 WF-2와 동일 값)
     * → WF-2 리파인 제출. 후보마다 seed가 랜덤이라 자세·결이 다른 후보가 나온다.
     */
    private void submitBaseCandidate(Long jobId, int index) {
        CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != CreationJobStatus.BASE_PROCESSING
            || job.getSelectedGoldenShotKey() == null) return;

        StructuredConcept concept = json.readConcept(job.getStructuredConceptJson());
        String bgColor = job.getBgColor();
        String goldenUrl = assetService.presignGet(job.getSelectedGoldenShotKey(), PRESIGN_TTL);

        poseEditClient.edit(new PoseEditClient.EditRequest(
                promptAssembler.qwenPosePrompt(concept.basePose()), promptAssembler.qwenNegative(), goldenUrl, null))
            .thenCompose(pass1 -> poseEditClient.edit(new PoseEditClient.EditRequest(
                promptAssembler.qwenBackgroundPrompt(bgColor), promptAssembler.qwenNegative(),
                pass1.imageUrl(), null)))
            .whenComplete((pass2, err) -> {
                if (err != null) {
                    log.warn("[UGC-WORKER] 스탠딩 후보 Qwen 파생 실패: jobId={}, idx={}, {}",
                        jobId, index, err.getMessage());
                    handleBaseCandidateFailure(jobId, index);
                    return;
                }
                try {
                    String editKey = assetService.storeFromUrl(pass2.imageUrl(), jobId, "base_edit" + index);
                    mutateJob(jobId, j -> {
                        List<BaseCandidate> candidates =
                            new ArrayList<>(json.readBaseCandidates(j.getBaseCandidatesJson()));
                        if (index < candidates.size()) {
                            candidates.set(index, candidates.get(index).refining(editKey, pass2.seed()));
                            j.updateBaseCandidates(json.writeBaseCandidates(candidates));
                        }
                    });
                    submitRefine(jobId, editKey, concept, EmotionTag.NEUTRAL,
                        UgcStage.BASE_REFINE, String.valueOf(index), bgColor);
                } catch (Exception e) {
                    log.warn("[UGC-WORKER] 스탠딩 후보 WF-2 제출 실패: jobId={}, idx={}, {}",
                        jobId, index, e.getMessage());
                    handleBaseCandidateFailure(jobId, index);
                }
            });
    }

    /** 스탠딩 후보 실패 — 후보 단위 재시도(무과금), 소진 시 해당 후보만 FAILED. 전 후보 실패 시 잡 실패·전액 환불. */
    private void handleBaseCandidateFailure(Long jobId, int index) {
        String verdict = txTemplate.execute(tx -> {
            CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus() != CreationJobStatus.BASE_PROCESSING) return "IGNORE";
            List<BaseCandidate> candidates = new ArrayList<>(json.readBaseCandidates(job.getBaseCandidatesJson()));
            if (index < 0 || index >= candidates.size()) return "IGNORE";

            BaseCandidate cand = candidates.get(index);
            int next = cand.retryCount() + 1;
            if (next <= props.job().emotionRetries()) {
                candidates.set(index, BaseCandidate.deriving(next));
                job.updateBaseCandidates(json.writeBaseCandidates(candidates));
                return "RETRY";
            }
            candidates.set(index, cand.failed());
            job.updateBaseCandidates(json.writeBaseCandidates(candidates));
            return checkBaseCandidatesSettled(job, candidates) ? "ALL_FAILED" : "OK";
        });
        if ("RETRY".equals(verdict)) {
            submitBaseCandidate(jobId, index);
        } else if ("ALL_FAILED".equals(verdict)) {
            failAndRefund(jobId, "스탠딩 후보 생성 실패 (전 후보 소진)");
        }
    }

    /**
     * 후보 전원 정착(READY/FAILED) 시 BASE_WAIT 전이. 반환: 전원 FAILED 여부(호출측 잡 실패 처리용).
     * 잡 락 TX 내부에서만 호출.
     */
    private boolean checkBaseCandidatesSettled(CharacterCreationJob job, List<BaseCandidate> candidates) {
        boolean settled = !candidates.isEmpty() && candidates.stream()
            .allMatch(c -> c.is(BaseCandidate.READY) || c.is(BaseCandidate.FAILED));
        if (!settled) return false;
        if (candidates.stream().anyMatch(c -> c.is(BaseCandidate.READY))) {
            job.toBaseWait(json.writeBaseCandidates(candidates), props.job().ttlHours());
            return false;
        }
        return true; // 전원 실패
    }

    /** 베이스 확정 직후 감정 상태 맵 초기화 — 서비스 계층(select TX) 전용. NEUTRAL은 베이스 자체로 즉시 READY. */
    void initEmotionAssets(CharacterCreationJob job, String baseKey) {
        Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(null);
        emotions.put(EmotionTag.NEUTRAL, EmotionAssetState.ready(baseKey));
        for (EmotionTag tag : UgcPromptAssembler.derivedEmotions()) {
            emotions.put(tag, EmotionAssetState.deriving(0));
        }
        job.updateEmotionAssets(json.writeEmotions(emotions));
    }

    /**
     * WF-2 제출 공통 — 입력 이미지를 base64로 주입 (LoadImage 파일명 = input.images[].name 동일 문자열 계약).
     */
    private void submitRefine(Long jobId, String inputKey, StructuredConcept concept,
                              EmotionTag emotion, UgcStage stage, String webhookToken, String bgColor) {
        byte[] bytes = assetService.download(inputKey);
        String suffix = emotion.name().toLowerCase()
            + (stage == UgcStage.BASE_REFINE ? "_" + webhookToken : "");
        String inputName = "job_" + jobId + "_" + suffix + "_in.png";
        String positive = promptAssembler.refinePositive(
            concept.appearanceTags(), concept.personaTags(), emotion, bgColor);
        // [2026-07-21 재구성] 감정 표정 포함 — 디테일 패스가 Qwen 표정을 중화하지 않도록
        String faceWildcard = promptAssembler.faceDetailWildcard(
            concept.appearanceTags(), concept.personaTags(), emotion);
        var workflow = workflowFactory.buildRefine(inputName, positive, faceWildcard, "job_" + jobId + "_" + suffix);
        var submit = comfyClient.submit(workflow,
            List.of(new UgcComfyClient.InputImage(inputName, Base64.getEncoder().encodeToString(bytes))),
            webhookUrl(jobId, stage, webhookToken));
        recordExternalJob(jobId, externalKey(stage, webhookToken), submit.jobId());
        log.info("[UGC-WORKER] WF-2 submitted: jobId={}, target={}, runpod={}", jobId, suffix, submit.jobId());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Stage 3 (감정 14종 병렬 파생 — 스타 토폴로지)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void runEmotionStage(Long jobId) {
        CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != CreationJobStatus.EMOTIONS_PROCESSING) return;

        // [2026-07-21 컨셉 반영 감정] 캐릭터별 동적 표정·자세 산출 — 잡에 저장(리롤 재현성).
        // 실패는 서버 상수 폴백으로 흡수 (파이프라인 비차단).
        deriveEmotionPromptsSafely(jobId);

        for (EmotionTag tag : UgcPromptAssembler.derivedEmotions()) {
            submitEmotionDerivation(jobId, tag, job.getBaseEditSeed());
        }
    }

    private void deriveEmotionPromptsSafely(Long jobId) {
        CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStructuredConceptJson() == null) return;
        StructuredConcept concept = json.readConcept(job.getStructuredConceptJson());
        if (concept.emotionPrompts() != null && !concept.emotionPrompts().isEmpty()) return; // 이미 산출(멱등)
        try {
            var prompts = conceptStructuringService.deriveEmotionPrompts(concept);
            mutateJob(jobId, j -> {
                StructuredConcept current = json.readConcept(j.getStructuredConceptJson());
                j.applyStage0(json.writeConcept(current.withEmotionPrompts(prompts)), current.bgColor());
            });
            log.info("[UGC-WORKER] 감정 연출 산출 완료: jobId={}, {}종", jobId, prompts.size());
        } catch (Exception e) {
            log.warn("[UGC-WORKER] 감정 연출 산출 실패 — 서버 상수 폴백: jobId={}, {}", jobId, e.getMessage());
        }
    }

    /** 감정 1종 개별 리롤/재시도 — 리롤은 새 seed(변화 유도), 자동 재시도도 새 seed. */
    @Async
    public void runEmotionReroll(Long jobId, EmotionTag tag) {
        submitEmotionDerivation(jobId, tag, null);
    }

    /**
     * 감정 1종 파생: Qwen(베이스에서 직접) → WF-2. fal은 SDK subscribe라 콜백 체인으로 WF-2 제출.
     */
    private void submitEmotionDerivation(Long jobId, EmotionTag tag, Long fixedSeed) {
        CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus().isTerminal() || job.getBaseStandingKey() == null) return;

        StructuredConcept concept = json.readConcept(job.getStructuredConceptJson());
        String bgColor = job.getBgColor();
        String baseUrl = assetService.presignGet(job.getBaseStandingKey(), PRESIGN_TTL);

        String personaHint = (concept.personaTags() == null || concept.personaTags().isEmpty())
            ? null : String.join(", ", concept.personaTags());
        // [2026-07-21] 캐릭터별 동적 감정 연출 (없으면 상수 폴백 — qwenEmotionPrompt 내부 처리)
        StructuredConcept.EmotionPromptOverride override = concept.emotionPromptFor(tag.name());
        poseEditClient.edit(new PoseEditClient.EditRequest(
                promptAssembler.qwenEmotionPrompt(tag, personaHint, override), promptAssembler.qwenNegative(), baseUrl, fixedSeed))
            .whenComplete((result, err) -> {
                if (err != null) {
                    log.warn("[UGC-WORKER] Qwen 감정 파생 실패: jobId={}, tag={}, {}", jobId, tag, err.getMessage());
                    handleEmotionFailure(jobId, tag);
                    return;
                }
                try {
                    String editKey = assetService.storeFromUrl(result.imageUrl(), jobId,
                        "emo_" + tag.name().toLowerCase() + "_edit");
                    mutateJob(jobId, j -> {
                        Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(j.getEmotionAssetsJson());
                        EmotionAssetState state = emotions.getOrDefault(tag, EmotionAssetState.deriving(0));
                        emotions.put(tag, state.refining());
                        j.updateEmotionAssets(json.writeEmotions(emotions));
                    });
                    submitRefine(jobId, editKey, concept, tag, UgcStage.EMOTION_REFINE, tag.name(), bgColor);
                } catch (Exception e) {
                    log.warn("[UGC-WORKER] 감정 WF-2 제출 실패: jobId={}, tag={}, {}", jobId, tag, e.getMessage());
                    handleEmotionFailure(jobId, tag);
                }
            });
    }

    /** 감정 컷 실패 — 자동 재시도(무과금, 상한 초과 시 해당 컷만 FAILED 마킹 후 진행). */
    private void handleEmotionFailure(Long jobId, EmotionTag tag) {
        boolean retry = Boolean.TRUE.equals(txTemplate.execute(status -> {
            CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus().isTerminal()) return false;

            Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
            EmotionAssetState state = emotions.getOrDefault(tag, EmotionAssetState.deriving(0));
            int next = state.retryCount() + 1;
            if (next <= props.job().emotionRetries()) {
                emotions.put(tag, state.derivingAgain(next)); // 기존 버전 유지한 채 재시도
                job.updateEmotionAssets(json.writeEmotions(emotions));
                return true;
            }
            // 소진: 이전 완성본이 있으면 그리로 복귀(리롤 실패가 기존 결과를 파괴하지 않도록), 없으면 FAILED
            emotions.put(tag, state.hasCompletedVersion() ? state.revertToReady() : state.failed());
            job.updateEmotionAssets(json.writeEmotions(emotions));
            checkEmotionsSettled(job, emotions);
            return false;
        }));
        if (retry) {
            submitEmotionDerivation(jobId, tag, null);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Stage 4 (누끼 15종 → 바인딩)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void runCutoutStage(Long jobId) {
        CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != CreationJobStatus.POSTPROCESSING) return;

        try {
            Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
            mutateJob(jobId, j -> {
                Map<EmotionTag, EmotionAssetState> m = json.readEmotions(j.getEmotionAssetsJson());
                m.replaceAll((t, s) -> s.cutting());
                j.updateEmotionAssets(json.writeEmotions(m));
            });
            for (Map.Entry<EmotionTag, EmotionAssetState> entry : emotions.entrySet()) {
                submitCutout(jobId, entry.getKey(), entry.getValue().key());
            }
        } catch (Exception e) {
            failAndRefund(jobId, "누끼 처리 실패: " + e.getMessage());
        }
    }

    private void submitCutout(Long jobId, EmotionTag tag, String refinedKey) {
        byte[] bytes = assetService.download(refinedKey);
        String inputName = "job_" + jobId + "_" + tag.name().toLowerCase() + "_cut_in.png";
        var workflow = workflowFactory.buildCutout(inputName, "job_" + jobId + "_cut_" + tag.name().toLowerCase());
        var submit = comfyClient.submit(workflow,
            List.of(new UgcComfyClient.InputImage(inputName, Base64.getEncoder().encodeToString(bytes))),
            webhookUrl(jobId, UgcStage.CUTOUT, tag.name()));
        recordExternalJob(jobId, externalKey(UgcStage.CUTOUT, tag.name()), submit.jobId());
    }

    /** Stage 4 바인딩 — Character 생성·에셋 승격·알림. */
    @Async
    public void bind(Long jobId) {
        CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != CreationJobStatus.BINDING) return;

        try {
            StructuredConcept concept = json.readConcept(job.getStructuredConceptJson());
            StructuredConcept.CharacterProfile profile = concept.character();
            Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());

            String slug = uniqueSlug(jobId);

            // 확정 에셋 승격 — 프런트 규약 characters/{slug}/{outfit}_{emotion}.png (outfit=default)
            for (Map.Entry<EmotionTag, EmotionAssetState> entry : emotions.entrySet()) {
                assetService.promoteToCharacterAsset(entry.getValue().cutoutKey(), slug,
                    "default_" + entry.getKey().name().toLowerCase() + ".png");
            }
            assetService.promoteToCharacterAsset(job.getSelectedGoldenShotKey(), slug, "thumbnail.png");

            String neutralKey = "characters/" + slug + "/default_neutral.png";
            String thumbnailKey = "characters/" + slug + "/thumbnail.png";

            Character.UgcCharacterSpec spec = new Character.UgcCharacterSpec(
                job.getUserId(),
                profile.name(),
                slug,
                promptAssembler.buildUgcBaseSystemPrompt(profile),
                openAiProps.model(),
                profile.tagline(),
                profile.personality(),
                profile.role(),
                profile.personality(),
                profile.tone(),
                profile.appearance(),
                profile.clothing(),
                profile.backstory(),
                profile.coreValues(),
                profile.flaws(),
                profile.speechQuirks(),
                profile.firstGreeting(),
                profile.introNarration(),
                assetService.publicUrl(neutralKey),
                assetService.publicUrl(thumbnailKey),
                "DEFAULT",
                // [세계관 빌더] 위저드 3택 요청 주입 — 공식은 worldId(enum), UGC는 ugcWorldId(Long).
                // 채팅 효과(lore·장소 풀)만 열리고 STORY/THEATER는 createUgc 불변식이 계속 차단한다.
                job.getRequestedWorldId(),
                job.getRequestedUgcWorldId(),
                // [2026-07-22 프로필 뷰] 몰입형 신상 + 무드 태그(persona 조인 — 200자 절삭:
                // varchar 초과가 완주한 잡을 최종 단계에서 죽이지 않도록)
                profile.height(),
                profile.likes(),
                profile.dislikes(),
                profile.hobby(),
                UgcWorldPipelineWorker.joinMood(concept.personaTags())
            );

            Long characterId = txTemplate.execute(status -> {
                Character character = characterRepository.save(Character.createUgc(spec));
                CharacterCreationJob locked = jobRepository.findByIdForUpdate(jobId).orElseThrow();
                locked.toReady(character.getId());
                return character.getId();
            });

            notificationService.notify(job.getUserId(), "UGC_CREATION_COMPLETE",
                "캐릭터가 깨어났어요",
                profile.name() + " 캐릭터가 완성되었어요. 스튜디오에서 만나보세요.",
                "UGC_CHARACTER", String.valueOf(characterId));

            log.info("[UGC-WORKER] ✅ READY: jobId={}, characterId={}, slug={}", jobId, characterId, slug);
        } catch (Exception e) {
            log.error("[UGC-WORKER] 바인딩 실패: jobId={}", jobId, e);
            failAndRefund(jobId, "캐릭터 등록 실패: " + e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  RunPod 이벤트 수신 (webhook + 폴링 폴백 공용 — 멱등)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @param token 스테이지 문맥 — BASE_REFINE: 후보 인덱스("0"/"1") · EMOTION_REFINE/CUTOUT: EmotionTag 이름 · GOLDEN: null
     */
    public void onComfyEvent(Long jobId, UgcStage stage, String token, UgcComfyClient.JobStatus status) {
        if (status.inFlight()) return;

        switch (stage) {
            case GOLDEN -> onGoldenResult(jobId, status);
            case BASE_REFINE -> onBaseRefineResult(jobId, parseIndex(token), status);
            case EMOTION_REFINE -> onEmotionRefineResult(jobId, parseEmotionToken(token), status);
            case CUTOUT -> onCutoutResult(jobId, parseEmotionToken(token), status);
        }
    }

    private static int parseIndex(String token) {
        try {
            return Integer.parseInt(token);
        } catch (Exception e) {
            return -1;
        }
    }

    private static EmotionTag parseEmotionToken(String token) {
        try {
            return EmotionTag.valueOf(token);
        } catch (Exception e) {
            return null;
        }
    }

    private void onGoldenResult(Long jobId, UgcComfyClient.JobStatus status) {
        if (!status.completed() || status.images().isEmpty()) {
            retryStageOrFail(jobId, "황금샷 생성 실패: " + status.error(),
                () -> runGoldenReroll(jobId));
            return;
        }
        // 락 밖에서 복사 (S3 왕복을 락 안에 두지 않는다) — 중복 webhook은 상태 가드로 무해
        List<String> newKeys = new ArrayList<>();
        for (int i = 0; i < status.images().size(); i++) {
            newKeys.add(assetService.storeFromUrl(status.images().get(i).data(), jobId, "golden" + i));
        }
        mutateJob(jobId, j -> {
            if (j.getStatus() != CreationJobStatus.CONCEPT_PROCESSING) return; // 멱등 가드
            // [리뷰 픽스] 리플레이 가드 — 이미 처리된 GOLDEN 이벤트(웹훅 중복 전달)가 리롤 진행 중
            // 재도착하면 상태를 GACHA_WAIT로 되돌려 진행 중인 리롤 결과를 삼킨다. 스크래치에
            // 미결 GOLDEN 키가 있을 때만 수용 (월드 트랙 세대 가드와 동일 원리).
            if (!json.readScratch(j.getExternalJobsJson()).containsKey(UgcStage.GOLDEN.name())) return;
            removeExternalJob(j, UgcStage.GOLDEN.name());
            // [2026-07-20 리롤 누적] 기존 후보 뒤에 새 배치를 붙인다 (1회차가 더 나은 케이스 보존)
            List<String> merged = new ArrayList<>(json.readKeys(j.getGoldenShotKeysJson()));
            merged.addAll(newKeys);
            j.toGachaWait(json.writeKeys(merged), props.job().ttlHours());
        });
    }

    /** [2026-07-20 개편] 스탠딩 후보 WF-2 완료 — 후보 상태 갱신, 전원 정착 시 BASE_WAIT 전이. */
    private void onBaseRefineResult(Long jobId, int index, UgcComfyClient.JobStatus status) {
        if (index < 0) return;
        if (!status.completed() || status.images().isEmpty()) {
            handleBaseCandidateFailure(jobId, index);
            return;
        }
        String refinedKey = assetService.storeFromUrl(status.images().get(0).data(), jobId, "base" + index);
        String verdict = txTemplate.execute(tx -> {
            CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus() != CreationJobStatus.BASE_PROCESSING) return "IGNORE";
            List<BaseCandidate> candidates = new ArrayList<>(json.readBaseCandidates(job.getBaseCandidatesJson()));
            if (index >= candidates.size()) return "IGNORE";

            candidates.set(index, candidates.get(index).readyWith(refinedKey));
            job.updateBaseCandidates(json.writeBaseCandidates(candidates));
            removeExternalJob(job, externalKey(UgcStage.BASE_REFINE, String.valueOf(index)));
            return checkBaseCandidatesSettled(job, candidates) ? "ALL_FAILED" : "OK";
        });
        if ("ALL_FAILED".equals(verdict)) {
            failAndRefund(jobId, "스탠딩 후보 생성 실패 (전 후보 소진)");
        }
    }

    private void onEmotionRefineResult(Long jobId, EmotionTag tag, UgcComfyClient.JobStatus status) {
        if (tag == null) return;
        if (!status.completed() || status.images().isEmpty()) {
            handleEmotionFailure(jobId, tag);
            return;
        }
        String key = assetService.storeFromUrl(status.images().get(0).data(), jobId,
            "emo_" + tag.name().toLowerCase());
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus().isTerminal()) return;

            Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
            EmotionAssetState state = emotions.getOrDefault(tag, EmotionAssetState.deriving(0));
            emotions.put(tag, state.readyWith(key));
            job.updateEmotionAssets(json.writeEmotions(emotions));
            removeExternalJob(job, externalKey(UgcStage.EMOTION_REFINE, tag.name()));
            checkEmotionsSettled(job, emotions);
        });
    }

    private void onCutoutResult(Long jobId, EmotionTag tag, UgcComfyClient.JobStatus status) {
        if (tag == null) return;
        if (!status.completed() || status.images().isEmpty()) {
            // 누끼 실패 — 컷 단위 재시도, 소진 시 파이프라인 실패(전액 환불)
            // 판정 3상태: RETRY(재제출) / EXHAUSTED(실패 종결) / IGNORE(스테일 이벤트 — 아무것도 안 함)
            String verdict = txTemplate.execute(tx -> {
                CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
                if (job == null || job.getStatus() != CreationJobStatus.POSTPROCESSING) return "IGNORE";
                Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
                EmotionAssetState state = emotions.get(tag);
                if (state == null) return "IGNORE";
                int next = state.retryCount() + 1;
                if (next > props.job().emotionRetries()) return "EXHAUSTED";
                emotions.put(tag, state.withRetry(next));
                job.updateEmotionAssets(json.writeEmotions(emotions));
                return "RETRY";
            });
            if ("RETRY".equals(verdict)) {
                CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
                if (job != null) {
                    Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
                    submitCutout(jobId, tag, emotions.get(tag).key());
                }
            } else if ("EXHAUSTED".equals(verdict)) {
                failAndRefund(jobId, "누끼 처리 실패: " + tag + " — " + status.error());
            }
            return;
        }

        String cutKey = assetService.storeFromUrl(status.images().get(0).data(), jobId,
            "cut_" + tag.name().toLowerCase());
        boolean allDone = Boolean.TRUE.equals(txTemplate.execute(tx -> {
            CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus() != CreationJobStatus.POSTPROCESSING) return false;

            Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
            EmotionAssetState state = emotions.get(tag);
            if (state == null) return false;
            emotions.put(tag, state.doneWith(cutKey));
            job.updateEmotionAssets(json.writeEmotions(emotions));
            removeExternalJob(job, externalKey(UgcStage.CUTOUT, tag.name()));

            boolean done = emotions.values().stream().allMatch(s -> s.is(EmotionAssetState.DONE));
            if (done) {
                job.toBinding();
            }
            return done;
        }));
        if (allDone) {
            bind(jobId);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  실패·만료·보상
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 파이프라인 귀책 실패 — 잡 FAILED + 누적 에너지 전액 환불.
     * (V1 ChatStreamService 보상 패턴: 별도 TX + userId 기준 유저 조회 — V2의 ID 혼용 버그 계보 아님)
     */
    public void failAndRefund(Long jobId, String reason) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus().isTerminal()) return;
            job.fail(reason);

            int refund = job.getEnergyCharged();
            if (refund > 0) {
                userRepository.findById(job.getUserId()).ifPresent(user -> {
                    user.refundEnergy(refund);
                    userRepository.save(user);
                    cacheService.evictUserProfile(user.getUsername());
                });
            }
            log.warn("[UGC-WORKER] ❌ FAILED: jobId={}, refund={}, reason={}", jobId, refund, reason);
        });
    }

    /** *_WAIT 방치 만료 — 무환불 정책 (스케줄러 호출). */
    public void expireJob(Long jobId) {
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || !job.getStatus().isWait()) return;
            job.expire();
            log.info("[UGC-WORKER] ⏰ EXPIRED: jobId={}", jobId);
        });
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 감정 15종이 전부 READY/FAILED로 정착했으면 REVIEW_WAIT 전이 (EMOTIONS_PROCESSING에서만). */
    private void checkEmotionsSettled(CharacterCreationJob job, Map<EmotionTag, EmotionAssetState> emotions) {
        if (job.getStatus() != CreationJobStatus.EMOTIONS_PROCESSING) return;
        if (emotions.size() < EmotionTag.values().length) return;
        boolean settled = emotions.values().stream()
            .allMatch(s -> s.is(EmotionAssetState.READY) || s.is(EmotionAssetState.FAILED));
        if (settled) {
            job.toReviewWait(props.job().ttlHours());
        }
    }

    /** 스테이지 단위 재시도 (무과금) — 소진 시 실패 종결. */
    private void retryStageOrFail(Long jobId, String reason, Runnable resubmit) {
        Integer attempt = txTemplate.execute(tx -> {
            CharacterCreationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus().isTerminal()) return null;
            return job.incrementRetry();
        });
        if (attempt == null) return;
        if (attempt <= props.job().autoRetries()) {
            log.warn("[UGC-WORKER] 스테이지 재시도 {}/{}: jobId={}, {}", attempt, props.job().autoRetries(), jobId, reason);
            resubmit.run();
        } else {
            failAndRefund(jobId, reason);
        }
    }

    /** 외부 호출을 수반하는 스테이지 본문의 즉시-예외 재시도 래퍼 (백오프 2s). */
    private void runWithRetries(Long jobId, String stageName, Runnable body) {
        int max = props.job().autoRetries() + 1;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                body.run();
                return;
            } catch (ContentModerationException e) {
                throw e; // 정책 차단은 재시도 대상 아님
            } catch (RuntimeException e) {
                last = e;
                log.warn("[UGC-WORKER] {} 시도 {}/{} 실패: jobId={}, {}", stageName, attempt, max, jobId, e.getMessage());
                if (attempt < max) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }

    private void mutateJob(Long jobId, Consumer<CharacterCreationJob> mutation) {
        txTemplate.executeWithoutResult(tx ->
            jobRepository.findByIdForUpdate(jobId).ifPresent(mutation));
    }

    private void recordExternalJob(Long jobId, String key, String runpodId) {
        mutateJob(jobId, j -> {
            Map<String, String> scratch = json.readScratch(j.getExternalJobsJson());
            scratch.put(key, runpodId);
            j.updateExternalJobs(json.writeScratch(scratch));
        });
    }

    private void removeExternalJob(CharacterCreationJob job, String key) {
        Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
        if (scratch.remove(key) != null) {
            job.updateExternalJobs(json.writeScratch(scratch));
        }
    }

    static String externalKey(UgcStage stage, String token) {
        return token == null ? stage.name() : stage.name() + ":" + token;
    }

    /** 폴러가 내부 스크래치(K_*) 키를 RunPod id로 오인하지 않도록 하는 판별. */
    public static boolean isExternalJobKey(String key) {
        return !key.startsWith(SCRATCH_KEY_PREFIX);
    }

    private String webhookUrl(Long jobId, UgcStage stage, String token) {
        String base = props.runpod().webhookBaseUrl();
        if (base == null || base.isBlank()) return null; // webhook 미구성 → 폴링 폴백만
        StringBuilder sb = new StringBuilder(base);
        if (base.endsWith("/")) sb.setLength(sb.length() - 1);
        sb.append("/api/v1/webhook/ugc-comfy?job=").append(jobId)
            .append("&stage=").append(stage.name());
        if (token != null) {
            sb.append("&tag=").append(token);
        }
        String secret = props.runpod().webhookSecret();
        if (secret != null && !secret.isBlank()) {
            sb.append("&secret=").append(URLEncoder.encode(secret, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String uniqueSlug(Long jobId) {
        String slug = "ugc-" + jobId;
        int suffix = 1;
        while (characterRepository.existsBySlug(slug)) {
            slug = "ugc-" + jobId + "-" + suffix++;
        }
        return slug;
    }

    /** 유저 리롤(REVIEW_WAIT) 진입점 — 기존 버전을 보존한 채 DERIVING으로 되돌린다. 서비스 계층 전용. */
    void resetEmotionForReroll(CharacterCreationJob job, EmotionTag tag) {
        Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
        EmotionAssetState state = emotions.getOrDefault(tag, EmotionAssetState.deriving(0));
        emotions.put(tag, state.derivingAgain(0));
        job.updateEmotionAssets(json.writeEmotions(emotions));
    }
}
