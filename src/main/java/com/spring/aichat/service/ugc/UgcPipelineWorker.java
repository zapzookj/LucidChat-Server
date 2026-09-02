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
    /** [2026-08-05 디자인 리롤] 황금샷 배치별 외형 스냅샷 — 선택 시 배치 정합 복원(종원 승인 UX). */
    static final String GOLDEN_SNAPSHOTS_KEY = SCRATCH_KEY_PREFIX + "GOLDEN_SNAPSHOTS";
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
    private final UgcRoutineGenerationService routineGenerationService; // [P2 STORY 개방 1단]
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
                // [2026-08-04 남캐] 성별 가이드 블록 병합 — 태그·자세·감정 연출의 산출 방향을
                // Stage0에 강제(외형 힌트 [외형 지정] 블록과 동일 패턴, 스키마 무변경)
                StructuredConcept concept = conceptStructuringService.structure(
                    withGenderDirective(job.getConceptInputRaw(), job.getGenderOrDefault()),
                    job.getRequestedName());
                moderationService.assertStructuredConceptAllowed(concept, job.getConceptInputRaw(), job.getUserId());

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

    /**
     * [2026-08-04 남캐] 잡 성별 조회 — 프롬프트 앵커(1boy)와 Male LoRA 주입의 공용 기준.
     * 서브밋 경로 전부가 이 판정을 지나므로 리롤·감정·베이스 어느 경로도 성별이 어긋나지 않는다.
     */
    private boolean isMaleJob(Long jobId) {
        return jobRepository.findById(jobId)
            .map(j -> j.getGenderOrDefault().isMale())
            .orElse(false);
    }

    /**
     * [남캐] Stage0 아트 디렉션 브리프 — 남성만 부착(여성은 기존 경로 무변).
     *
     * <p>[2026-08-04 방향 전환 — 종원 비판 수용] 규칙 누적(필수/금지 태그 목록)은 취향을 가르치지
     * 못하고 분포만 잘라 표현력 단일화로 수렴한다(1차: 남성 어휘 규정 → bara 클러스터, 2차: 미형
     * 명문화+금지 목록 → 표현 한정). 근본 원인은 Stage0의 태스크 정의('충실한 구조화 엔진')에
     * <b>미학 목표가 부재</b>한 것 — 그래서 규칙 대신 <b>디자인 목표 한 줄</b>을 부여한다
     * ('여성향 장르에서 인기 있을 모습으로 디자인'). 태그 선택은 모델의 장르 지식에 맡기고,
     * 컨셉의 명시 지시는 언제나 우선(아저씨 컨셉도 온전히 가능 — 표현력 보존).
     */
    private static String withGenderDirective(String concept, com.spring.aichat.domain.enums.CharacterGender gender) {
        if (!gender.isMale()) return concept;
        return concept + """


            [아트 디렉션 — 남성 캐릭터]
            이 캐릭터는 남성이다 (appearance_tags는 1boy, male focus 기준 — 1girl 계열 금지).
            일러스트 태그 산출의 목표는 설정의 축어적 번역이 아니라 **디자인**이다: 이 컨셉이 여성향 서브컬처 장르에서 인기 있는 남성 캐릭터로 그려진다면 어떤 모습일지 상상하고, 그 완성형을 태그로 옮겨라. 설정의 정체성(강함·지위·분위기)은 그대로 살리되, 그것을 *어떤 외형 어휘로 표현할지*는 장르 독자에게 매력적인 쪽을 골라라. base_pose와 감정 연출(emotion_prompts)도 같은 관점으로.
            태그 접지(전 필드 공통): 모든 태그는 danbooru에 실존하는 정식 태그로만 산출하라. 그럴듯한 조어는 렌더에 전달되지 않는 유령 태그다(실측 사례: soft hair, layered hair, sharp eyes, bright eyes, detailed eyes, eye highlights, warm lighting, rim lighting — 전부 비실존). 특히: ① 헤어는 색·기장 + 검증 핸들 1계열로 간결하게 구성하라 — 컨셉에 맞는 핸들을 골라라: 시스루 댄디·프레시=choppy bangs(+messy hair), 가르마·도시적=parted bangs/parted hair(+swept bangs), 자연 볼륨=messy hair/wavy hair. 형태 태그를 그 이상 겹겹이 쌓으면 구식 실루엣으로 고정된다(Phase 4·5 매트릭스 실측). 회피 실측 3건: medium hair(남성에서 어깨선 장발화), curtained hair·short hair with long locks(90년대 일본식 실루엣 — 촌스러움 원인 확정). bangs 단독은 deprecated. 눈썹은 태그를 생략하는 것이 기본이다 — thick eyebrows는 짙은 블록 눈썹으로 과장 렌더된다(A/B 실측 확정, 컨셉이 굵은 눈썹을 명시 요구할 때만 사용) ② 조명은 형용사 대신 물리 광원을 놓아라(lamp, backlighting, sidelighting, sunset 등 — 단 backlighting/sidelighting 혼용 금지) ③ 어두운 씬에서 pale skin은 회보라 송장톤으로 렌더된다(PoC 실측) — 창백함이 컨셉의 명시 요구가 아니면 쓰지 말고, 혈색은 light blush로. ④ 성별 앵커 유지(잡 14 실측): appearance_tags 체형 블록에 남성 신체 명시 태그(adult는 항상 필수)를 포함하되, 체형 어휘는 컨셉의 스펙트럼을 따르라 — 강골·기사=tall male, muscular, broad shoulders / 표준=tall male, toned / 가녀린 미소년=slender, narrow shoulders(이 경우에도 adult 유지). 모든 남캐에 같은 체형 스택을 쓰면 얼굴·체형이 동질화된다(Phase 7 실측 — 카일과 아셀이 같은 떡대로 렌더). 여성 분포가 지배적인 눈가 태그(tareme, long eyelashes 등)와 여성 편중 씬이 겹겹이 쌓이면 1boy 앵커와 Male LoRA를 압도해 얼굴·체형이 여성으로 드리프트된다. 여성 편중 씬의 대표 사례 = 아이돌 콘서트 무대 클러스터(spotlight, audience, glowstick, confetti — 잡 14·16 실측). 채택하려면 스파클 계열 태그를 빼고 남성 명시 태그를 반드시 강화하라. 그 외에도 여성 분포 태그는 꼭 필요한 것만 선별하고, 쌓일수록 남성 명시 태그로 균형을 잡아라.
            대표 컷 연출(scene_tags): 황금샷은 이 캐릭터의 **대표 화보 컷**이다 — looking at viewer(눈맞춤)를 반드시 포함하고, 표정은 컨셉의 시그니처를 따르라: 밝음·다정=light smile, smirk / 냉철·과묵=expressionless, serious(눈맞춤·gradient eyes와 결합하면 생기가 유지된다 — Phase 7 실측, 컨셉 불문 미소 강제 금지) / 오만·나른=smug, half-closed eyes. 눈 발색은 gradient eyes로 살려라. sparkling eyes는 조용한 씬에서만 선택적으로 쓰고, 무대 조명·이펙트 씬(spotlight, lens flare, confetti 등)에서는 반짝임이 중첩 과장되니 넣지 마라(잡 16 실측). 조명은 무드가 어두운 컨셉이어도 인물이 살아 보이는 포인트 광원을 넣어라. 유일한 금지 조합: 내리깐 시선+반개안+무표정+창백을 동시에 쌓아 생기를 전부 죽이는 구성(Phase 2 실측).
            단, 유저 컨셉이 특정 인상(중년의 관록, 거친 야성 등)을 명시적으로 요구하면 언제나 그 지시가 우선한다.""";
    }

    private void submitGoldenShots(Long jobId, StructuredConcept concept) {
        boolean male = isMaleJob(jobId);
        String positive = promptAssembler.goldenShotPositive(
            concept.appearanceTags(), concept.personaTags(), concept.sceneTags(), male);
        var workflow = workflowFactory.buildGoldenShot(positive, "job_" + jobId + "_golden", male);
        var submit = comfyClient.submit(workflow, null, webhookUrl(jobId, UgcStage.GOLDEN, null));
        recordExternalJob(jobId, UgcStage.GOLDEN.name(), submit.jobId());
        // [2026-08-05 디자인 리롤] 이 배치의 외형 스냅샷 기록 — startIndex = 현재 누적 키 수
        // (배치 이미지는 웹훅 완료 시점에 append되므로 제출 시점 카운트가 곧 이 배치의 시작 인덱스).
        // 재시도 재제출 시 동일 startIndex 중복 기록은 resolveSnapshot이 마지막 것을 취해 무해.
        mutateJob(jobId, j -> {
            Map<String, String> scratch = json.readScratch(j.getExternalJobsJson());
            List<UgcJobJson.GoldenSnapshot> snaps =
                new ArrayList<>(json.readGoldenSnapshots(scratch.get(GOLDEN_SNAPSHOTS_KEY)));
            int startIndex = json.readKeys(j.getGoldenShotKeysJson()).size();
            snaps.add(new UgcJobJson.GoldenSnapshot(startIndex, json.writeConcept(concept)));
            scratch.put(GOLDEN_SNAPSHOTS_KEY, json.writeGoldenSnapshots(snaps));
            j.updateExternalJobs(json.writeScratch(scratch));
        });
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
                    // [2026-08-04 디자인 리롤] 남캐 브리프를 재구조화에도 관통 — 이전엔 raw 컨셉만
                    // 넘겨 외형 수정 리롤에서 접지·성별 앵커가 소실되는 잠복 버그였다
                    StructuredConcept updated = conceptStructuringService.restructureAppearance(
                        withGenderDirective(fresh.getConceptInputRaw(), fresh.getGenderOrDefault()),
                        current, hintsBlock);
                    moderationService.assertStructuredConceptAllowed(updated, fresh.getConceptInputRaw(), fresh.getUserId());
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
            // [적대적 리뷰 P3] 스윕 창(staleMinutes)을 넘겨 살아남는 future가 없게 — '키 없는 DERIVING = 죽은 체인'을 불변식으로.
            //   타임아웃은 err 분기로 들어가 후보 실패 경로(예산 소진 시 정상 실패)를 탄다.
            .orTimeout(qwenTimeoutMinutes(), java.util.concurrent.TimeUnit.MINUTES)
            .whenComplete((pass2, err) -> {
                if (err != null) {
                    log.warn("[UGC-WORKER] 스탠딩 후보 Qwen 파생 실패: jobId={}, idx={}, {}",
                        jobId, index, err.getMessage());
                    handleBaseCandidateFailure(jobId, index);
                    return;
                }
                try {
                    // [2026-08-04 단계 과금] 터미널 재확인 — Qwen 2패스(수십 초) 사이 잡이 종결(중도
                    // 포기 등)됐으면 저장·WF-2 제출을 이어가지 않는다 (취소 후 외부 지출 누수 차단)
                    CharacterCreationJob current = jobRepository.findById(jobId).orElse(null);
                    if (current == null || current.getStatus().isTerminal()) {
                        log.info("[UGC-WORKER] 종결 잡 스탠딩 후보 진행 스킵: jobId={}, idx={}", jobId, index);
                        return;
                    }
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
        // [2026-08-04 단계 과금] 터미널 재확인 — 중도 포기(취소=정산) 후 도착한 fal 콜백 체인이
        // 외부 GPU 지출(WF-2)을 이어가지 않도록 제출 직전 차단 (웹훅 핸들러 터미널 가드와 동일 원리)
        CharacterCreationJob current = jobRepository.findById(jobId).orElse(null);
        if (current == null || current.getStatus().isTerminal()) {
            log.info("[UGC-WORKER] 종결 잡 WF-2 제출 스킵: jobId={}, stage={}, token={}", jobId, stage, webhookToken);
            return;
        }
        byte[] bytes = assetService.download(inputKey);
        String suffix = emotion.name().toLowerCase()
            + (stage == UgcStage.BASE_REFINE ? "_" + webhookToken : "");
        String inputName = "job_" + jobId + "_" + suffix + "_in.png";
        boolean male = isMaleJob(jobId);
        String positive = promptAssembler.refinePositive(
            concept.appearanceTags(), concept.personaTags(), emotion, bgColor, male);
        // [2026-07-21 재구성] 감정 표정 포함 — 디테일 패스가 Qwen 표정을 중화하지 않도록
        String faceWildcard = promptAssembler.faceDetailWildcard(
            concept.appearanceTags(), concept.personaTags(), emotion);
        var workflow = workflowFactory.buildRefine(inputName, positive, faceWildcard,
            "job_" + jobId + "_" + suffix, male);
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
            .orTimeout(qwenTimeoutMinutes(), java.util.concurrent.TimeUnit.MINUTES)   // [적대적 리뷰 P3] 스윕 창 내 강제 종료
            .whenComplete((result, err) -> {
                if (err != null) {
                    log.warn("[UGC-WORKER] Qwen 감정 파생 실패: jobId={}, tag={}, {}", jobId, tag, err.getMessage());
                    handleEmotionFailure(jobId, tag);
                    return;
                }
                try {
                    // [2026-08-04 단계 과금] 터미널 재확인 — Qwen 파생 완료 시점에 잡이 종결됐으면
                    // 저장·WF-2 제출 스킵 (취소 후 외부 지출 누수 차단 — submitRefine 가드와 이중 방어)
                    CharacterCreationJob current = jobRepository.findById(jobId).orElse(null);
                    if (current == null || current.getStatus().isTerminal()) {
                        log.info("[UGC-WORKER] 종결 잡 감정 파생 진행 스킵: jobId={}, tag={}", jobId, tag);
                        return;
                    }
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

            // [D-19 / D-3.6 최종 방어] varchar 상한 절삭 — 여기는 이미 GPU를 전부 쓴 시점이라
            // 거부가 불가능하다(거부 = 완주한 잡 폐기 + 전액 환불 = 순 0E GPU 드레인).
            // 구버전 잡 JSON(Stage 0 절삭 도입 이전 생성분)이나 미래의 새 입력 경로가 바인딩을
            // 죽이지 못하게 하는 마지막 방어선. 유저 입력 거부는 CharacterCreationService에서 한다.
            String boundName = ConceptStructuringService.normalizeShort(profile.name(), UgcTextLimits.NAME_MAX);
            if (boundName == null) boundName = profile.name();   // name은 NOT NULL — blank→null 계약 회피
            Character.UgcCharacterSpec spec = new Character.UgcCharacterSpec(
                job.getUserId(),
                boundName,
                slug,
                promptAssembler.buildUgcBaseSystemPrompt(profile),
                openAiProps.model(),
                ConceptStructuringService.normalizeShort(profile.tagline(), UgcTextLimits.TAGLINE_MAX),
                profile.personality(),
                ConceptStructuringService.normalizeShort(profile.role(), UgcTextLimits.ROLE_MAX),
                // [안건 9-D · decisions_confirmed §C] Stage 0 age 배선 — 그동안 버려져 프롬프트에
                // `- Age: null`이 실렸고, 시크릿 자격 판정도 판정 소스 자체가 없었다.
                profile.age(),
                profile.personality(),
                ConceptStructuringService.normalizeShort(profile.tone(), UgcTextLimits.TONE_MAX),
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
                // [2026-07-22 프로필 뷰] 몰입형 신상 + 무드 태그(200자 절삭:
                // varchar 초과가 완주한 잡을 최종 단계에서 죽이지 않도록)
                // [2026-07-30 폴리싱] 무드 태그는 한국어 mood_tags 우선 — 구버전 잡 JSON(미산출)만 persona 조인 폴백
                profile.height(),
                profile.likes(),
                profile.dislikes(),
                profile.hobby(),
                UgcWorldPipelineWorker.joinMood(
                    concept.moodTags() != null && !concept.moodTags().isEmpty()
                        ? concept.moodTags() : concept.personaTags()),
                profile.profileQuote(),
                // [2026-07-30 A-4/B-3] 실시간 일러 정체성 태그 영속화 — Stage0 산출 CSV 저장
                // (기존엔 job.structuredConceptJson에만 있어 실시간 일러가 airi 폴백에 갇혔음)
                joinCsv(concept.appearanceTags()),
                joinCsv(concept.personaTags()),
                concept.basePose()
            );

            Long characterId = txTemplate.execute(status -> {
                Character character = Character.createUgc(spec);
                // [2026-08-04 남캐] 위저드 선택 성별 영속 — 씬 렌더 캐스트·일러 앵커의 단일 기준
                character.updateGender(job.getGenderOrDefault());
                // [2026-08-05 난이도] 위저드 지정 난이도 주입 — 미지정(null)은 미설정 유지
                // (소비처 getDifficultyOrDefault의 null→NORMAL 폴백 계약 보존)
                if (job.getRequestedDifficultyOrNull() != null) {
                    character.updateDifficulty(job.getRequestedDifficultyOrNull());
                }
                character = characterRepository.save(character);
                CharacterCreationJob locked = jobRepository.findByIdForUpdate(jobId).orElseThrow();
                locked.toReady(character.getId());
                return character.getId();
            });

            notificationService.notify(job.getUserId(), "UGC_CREATION_COMPLETE",
                "캐릭터가 깨어났어요",
                profile.name() + " 캐릭터가 완성되었어요. 스튜디오에서 만나보세요.",
                "UGC_CHARACTER", String.valueOf(characterId));

            // [2026-07-30 P2 STORY 개방 1단] 루틴 자동생성 — 월드 연결 시 오프스크린 일과 확보
            // (STORY 개방 전까지 휴면 데이터, 비동기·비차단)
            routineGenerationService.regenerateForCharacterAsync(characterId);

            log.info("[UGC-WORKER] ✅ READY: jobId={}, characterId={}, slug={}", jobId, characterId, slug);
        } catch (Exception e) {
            log.error("[UGC-WORKER] 바인딩 실패: jobId={}", jobId, e);
            failAndRefund(jobId, "캐릭터 등록 실패: " + e.getMessage());
        }
    }

    /** [2026-07-30 A-4] 태그 리스트 → CSV (TEXT 컬럼 저장용 — null/빈 리스트는 null). */
    private static String joinCsv(java.util.List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        String joined = String.join(", ", tags);
        return joined.isBlank() ? null : joined;
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

            // [D-1.6] 누산된 free/paid 분할로 복원 — 총액 환불은 단계 진입 후 수 분~수 시간 뒤라
            //   유료분이 free로 흡수돼 소각됐다(최대 20E+, 비구독 상한 30 대비 거의 전량).
            var refund = job.chargedSplit();
            if (!refund.isZero()) {
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
            String prev = scratch.put(key, runpodId);
            if (prev != null && !prev.equals(runpodId)) {
                // [D-3.4 ②] 같은 키를 덮으면 선발 체인이 폴링 추적에서 빠진다 — 세대 경합을 관측 가능하게
                log.warn("[UGC-WORKER] externalJobs 키 덮어씀(세대 경합): jobId={}, key={}, {} → {}",
                    jobId, key, prev, runpodId);
            }
            j.updateExternalJobs(json.writeScratch(scratch));
        });
    }

    /** [D-3.2a] 죽은 외부 id를 스크래치에서 제거 — 재제출이 같은 키로 새 id를 기록하기 전까지 폴러가 재폴링하지 않게. */
    public void dropExternalJob(Long jobId, String key) {
        mutateJob(jobId, j -> removeExternalJob(j, key));
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

    /** fal(Qwen) future 상한 — 스테일 스윕 창보다 짧게(여유 5분, 최소 5분). 큐 혼잡으로 무기한 대기하던 체인을 실패 경로로 보낸다. */
    private long qwenTimeoutMinutes() {
        return Math.max(5, props.job().staleMinutes() - 5);
    }

    /** externalJobs 키 해석 결과 — "GOLDEN" | "BASE_REFINE:0" | "EMOTION_REFINE:JOY" | "CUTOUT:JOY". */
    public record ExternalKey(UgcStage stage, String token) {}

    /** externalJobs 키 → (스테이지, 토큰). 규약 밖 키는 null (폴러·스윕 공용). */
    public static ExternalKey parseExternalKey(String key) {
        if (key == null || !isExternalJobKey(key)) return null;
        try {
            int idx = key.indexOf(':');
            if (idx < 0) return new ExternalKey(UgcStage.valueOf(key), null);
            return new ExternalKey(UgcStage.valueOf(key.substring(0, idx)), key.substring(idx + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [D-3.1a/b/d · D-3.2a/b] 스테일 회수 — 서버 재시작·크래시로 유실된 in-flight 구간 복구
    //  (월드 트랙 UgcWorldPipelineWorker.recoverStaleJob 동형. 종전 캐릭터 트랙은 CONCEPT_PROCESSING만
    //   실패·환불했고 BINDING·POSTPROCESSING·fal(Qwen) 구간은 어느 리스트에도 없어 영구 좀비였다 —
    //   동시 1잡 정책 때문에 그 유저는 신규 생성까지 영구 차단됐고 유일한 탈출구가 무환불 abandon이었다.)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 스케줄러가 N분 무진행 잡에 호출.
     *
     * <ul>
     *   <li>외부 키가 <b>없는</b> 미결 항목(Qwen future 유실·누끼 미제출·BINDING 유실)은 즉시 재제출·재실행
     *       (무과금 — 자동 재시도). 30분 무진행 잡의 키 없는 DERIVING은 살아 있는 future일 확률이 0에 가깝다(Qwen ~1분).</li>
     *   <li>외부 키가 <b>있는</b> 항목은 폴러 담당 — 단 {@code hardStale}(폴러 위임 만료, D-3.2b)이면 '결과 소실'로
     *       주입해 스테이지별 재시도 예산으로 흘린다(소진 시에만 실패·환불). 종전엔 키가 존재하기만 하면 30일이든 스킵했다.</li>
     *   <li>CONCEPT_PROCESSING(LLM 동기 구간)은 복구 수단이 없어 실패·전액 환불(안건 21 (b): 서버가 실패로 마킹한 잡만 환불).</li>
     * </ul>
     * 재제출 후 {@code touchRecovery}로 다음 스윕 창까지 같은 잡의 중복 재제출을 막는다.
     */
    public void recoverStaleJob(Long jobId, boolean hardStale) {
        CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus().isTerminal()) return;

        Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
        List<String> pendingKeys = scratch.keySet().stream().filter(UgcPipelineWorker::isExternalJobKey).toList();
        boolean touched;
        switch (job.getStatus()) {
            case CONCEPT_PROCESSING -> {
                if (pendingKeys.isEmpty()) {
                    if (job.getStructuredConceptJson() != null) {
                        // [적대적 리뷰 P3] Stage0 산출은 커밋됐고 황금샷 제출(또는 리롤 제출)만 유실된 창 — 저장된 컨셉에서
                        //   재제출한다(runGoldenReroll: 상태 가드·APPEARANCE_EDIT 처리 내장). 전액 환불로 완주분을 버리지 않는다.
                        log.info("[UGC-WORKER] 스테일 CONCEPT_PROCESSING — 저장된 컨셉으로 황금샷 재제출: jobId={}", jobId);
                        runGoldenReroll(jobId);
                        touched = true;
                        break;
                    }
                    // 순수 Stage0 LLM 구간 유실 — 외부 키도 산출도 없어 재부착 불가. 기존 정책(2026-07-21) 유지.
                    log.warn("[UGC-WORKER] 스테일 CONCEPT_PROCESSING 회수 (LLM 구간 유실): jobId={}", jobId);
                    failAndRefund(jobId, "컨셉 처리 시간 초과 — 사용한 에너지는 환불되었어요.");
                    return;
                }
                touched = false;   // GOLDEN 키 있음 — 폴러 담당 (hardStale이면 아래에서 주입)
            }
            case BASE_PROCESSING -> touched = resubmitLostBaseCandidates(job, scratch);
            case EMOTIONS_PROCESSING, REVIEW_WAIT -> touched = resubmitLostEmotionDerivations(job, scratch);
            case POSTPROCESSING -> touched = resumeCutoutStage(job, scratch);
            case BINDING -> {
                // [D-3.1a] toBinding 커밋 후 bind()(자기호출이라 호출 스레드 동기 실행)가 죽은 상태 — 재실행.
                //   bind는 상태 가드 + uniqueSlug + 단일 TX(Character 저장·toReady)라 멱등(고아 slug 1개 비용).
                log.info("[UGC-WORKER] 스테일 BINDING 재실행: jobId={}", jobId);
                bind(jobId);
                return;
            }
            default -> { return; }   // GACHA_WAIT / BASE_WAIT — 순수 유저 대기, TTL 스윕 담당
        }

        if (hardStale && !pendingKeys.isEmpty()) {
            for (String key : pendingKeys) {
                injectLostExternalJob(jobId, key, scratch.get(key),
                    "폴러 위임 만료 — " + props.job().hardStaleMinutes() + "분 무진행");
            }
            touched = true;
        }
        if (touched) {
            mutateJob(jobId, CharacterCreationJob::touchRecovery);
        }
    }

    /**
     * [D-3.2a/b] 소실된 외부 잡을 정상 실패 경로로 주입 — 키를 먼저 지워(죽은 id 재폴링 방지) 스테이지별
     * 실패 핸들러에 합성 FAILED를 넘긴다. GOLDEN→스테이지 재시도, BASE/EMOTION→컷 단위 재시도, CUTOUT→컷 재시도;
     * 각각 예산 소진 시에만 failAndRefund. 블랭킷 failAndRefund보다 완주분을 보존한다.
     */
    public void injectLostExternalJob(Long jobId, String key, String observedRunpodId, String reason) {
        ExternalKey parsed = parseExternalKey(key);
        // [적대적 리뷰 P3] compare-and-drop — 호출자가 관측한 id가 아직 그 키에 있을 때만 제거·주입한다. 그 사이 웹훅이
        //   같은 키를 COMPLETED로 처리해 키를 지웠거나 재제출이 새 id를 기록했다면 이 주입은 낡은 세대다: 그대로 흘리면
        //   방금 READY가 된 컷을 handleEmotionFailure가 DERIVING으로 되돌려 Qwen+WF-2를 다시 태운다.
        boolean dropped = Boolean.TRUE.equals(txTemplate.execute(tx -> {
            CharacterCreationJob j = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (j == null) return false;
            Map<String, String> scratch = json.readScratch(j.getExternalJobsJson());
            String current = scratch.get(key);
            if (current == null || (observedRunpodId != null && !observedRunpodId.equals(current))) return false;
            scratch.remove(key);
            j.updateExternalJobs(json.writeScratch(scratch));
            return true;
        }));
        if (!dropped) {
            log.info("[UGC-WORKER] 소실 주입 스킵(키가 이미 바뀜·제거됨): jobId={}, key={}, observed={}", jobId, key, observedRunpodId);
            return;
        }
        if (parsed == null) {
            log.warn("[UGC-WORKER] 규약 밖 externalJobs 키 제거만: jobId={}, key={}", jobId, key);
            return;
        }
        log.warn("[UGC-WORKER] 외부 잡 소실 주입: jobId={}, key={}, reason={}", jobId, key, reason);
        onComfyEvent(jobId, parsed.stage(), parsed.token(), UgcComfyClient.JobStatus.lost(key, reason));
    }

    /** [D-3.1d] BASE_PROCESSING — 외부 키 없는 미정착 후보(Qwen 2패스 유실·WF-2 제출 직전 유실) 재파생. */
    private boolean resubmitLostBaseCandidates(CharacterCreationJob job, Map<String, String> scratch) {
        List<BaseCandidate> candidates = json.readBaseCandidates(job.getBaseCandidatesJson());
        List<Integer> lost = pendingBaseIndices(candidates, scratch);
        if (lost.isEmpty()) {
            // [적대적 리뷰 P2] toBaseProcessing/restartBaseGeneration 커밋 후 runBaseStage(후보 배치 append)가 뜨기 전에
            //   죽은 창 — 미정착 후보가 0이고 외부 키도 없으면 스윕이 영구 no-op이었다(유료 베이스 리롤 포함).
            boolean anyUnsettled = candidates.stream()
                .anyMatch(c -> !(c.is(BaseCandidate.READY) || c.is(BaseCandidate.FAILED)));
            boolean anyRefineKey = scratch.keySet().stream()
                .anyMatch(k -> k.startsWith(UgcStage.BASE_REFINE.name() + ":"));
            if (!anyUnsettled && !anyRefineKey) {
                log.info("[UGC-WORKER] 스테일 BASE_PROCESSING — 후보 배치 미부착, runBaseStage 재기동: jobId={}", job.getId());
                runBaseStage(job.getId());   // 자기호출=동기. 상태 가드 + 락 TX라 멱등
                return true;
            }
            return false;
        }
        for (int index : lost) {
            log.info("[UGC-WORKER] 스테일 스탠딩 후보 재파생: jobId={}, idx={}", job.getId(), index);
            try {
                submitBaseCandidate(job.getId(), index);
            } catch (RuntimeException e) {
                // [적대적 리뷰 P2] 동기 구간(presign·fal.subscribe) 예외를 예산 없는 5분 루프로 두지 않는다 — 후보 실패 경로로
                log.warn("[UGC-WORKER] 스테일 후보 재파생 실패 → 후보 실패 경로: jobId={}, idx={}, {}", job.getId(), index, e.getMessage());
                try { handleBaseCandidateFailure(job.getId(), index); } catch (RuntimeException ignored) { /* 다음 스윕 */ }
            }
        }
        return true;
    }

    /** [D-3.1d] EMOTIONS_PROCESSING·REVIEW_WAIT(리롤) — 외부 키 없는 DERIVING/REFINING 감정 재파생. */
    private boolean resubmitLostEmotionDerivations(CharacterCreationJob job, Map<String, String> scratch) {
        List<EmotionTag> lost = pendingEmotionTags(json.readEmotions(job.getEmotionAssetsJson()), scratch);
        if (lost.isEmpty()) return false;
        if (job.getStatus() == CreationJobStatus.EMOTIONS_PROCESSING) {
            deriveEmotionPromptsSafely(job.getId());   // 연출 산출 전 유실이면 먼저 (멱등)
        }
        // 최초 파생은 베이스 seed 고정(캐릭터 일관성), 리롤(REVIEW_WAIT)은 원래 의도대로 새 seed
        Long seed = job.getStatus() == CreationJobStatus.REVIEW_WAIT ? null : job.getBaseEditSeed();
        for (EmotionTag tag : lost) {
            log.info("[UGC-WORKER] 스테일 감정 재파생: jobId={}, tag={}, status={}", job.getId(), tag, job.getStatus());
            try {
                submitEmotionDerivation(job.getId(), tag, seed);
            } catch (RuntimeException e) {
                // [적대적 리뷰 P2] 동기 구간 예외 → 컷 실패 경로(재시도 예산 → 소진 시 FAILED/복귀) — 무기한 5분 루프 차단
                log.warn("[UGC-WORKER] 스테일 감정 재파생 실패 → 컷 실패 경로: jobId={}, tag={}, {}", job.getId(), tag, e.getMessage());
                try { handleEmotionFailure(job.getId(), tag); } catch (RuntimeException ignored) { /* 다음 스윕 */ }
            }
        }
        return true;
    }

    /**
     * [D-3.1b] POSTPROCESSING — 미제출 누끼만 재제출. {@link #runCutoutStage}는 진입 시 15컷 전체를 cutting()으로
     * 덮고 전량 제출하므로 재사용 불가(이미 DONE인 컷의 cutoutKey를 날린다) — 부분 재개 전용.
     */
    private boolean resumeCutoutStage(CharacterCreationJob job, Map<String, String> scratch) {
        Map<EmotionTag, EmotionAssetState> emotions = json.readEmotions(job.getEmotionAssetsJson());
        List<EmotionTag> lost = pendingCutoutTags(emotions, scratch);
        if (lost.isEmpty()) return false;
        mutateJob(job.getId(), j -> {
            Map<EmotionTag, EmotionAssetState> m = json.readEmotions(j.getEmotionAssetsJson());
            for (EmotionTag tag : lost) {
                EmotionAssetState s = m.get(tag);
                if (s != null && !s.is(EmotionAssetState.CUTTING)) m.put(tag, s.cutting());
            }
            j.updateEmotionAssets(json.writeEmotions(m));
        });
        for (EmotionTag tag : lost) {
            log.info("[UGC-WORKER] 스테일 누끼 재제출: jobId={}, tag={}", job.getId(), tag);
            try {
                submitCutout(job.getId(), tag, emotions.get(tag).key());
            } catch (RuntimeException e) {
                // [적대적 리뷰 P2] S3 download·RunPod submit 동기 예외 — 컷 재시도 예산(withRetry)으로 흘린다.
                //   RETRY면 다음 스윕이 다시 제출(예산 3회 ≈ 15분), EXHAUSTED면 실패·환불. 원본 runCutoutStage는
                //   같은 예외를 failAndRefund로 즉시 종결했는데 재개 경로만 무기한이었다.
                log.warn("[UGC-WORKER] 스테일 누끼 재제출 실패: jobId={}, tag={}, {}", job.getId(), tag, e.getMessage());
                noteCutoutSubmitFailure(job.getId(), tag, e.getMessage());
            }
        }
        return true;
    }

    /** 누끼 제출 자체가 실패했을 때의 예산 판정 — onCutoutResult 실패 분기와 같은 TX 규칙(withRetry → RETRY/EXHAUSTED). */
    private void noteCutoutSubmitFailure(Long jobId, EmotionTag tag, String error) {
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
        if ("EXHAUSTED".equals(verdict)) {
            failAndRefund(jobId, "누끼 처리 실패: " + tag + " — " + error);
        }
    }

    /** 미정착(READY/FAILED 아님)이면서 BASE_REFINE 키가 없는 후보 인덱스 — 순수 판정(테스트 대상). */
    static List<Integer> pendingBaseIndices(List<BaseCandidate> candidates, Map<String, String> scratch) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            BaseCandidate c = candidates.get(i);
            if (c.is(BaseCandidate.READY) || c.is(BaseCandidate.FAILED)) continue;
            if (scratch.containsKey(externalKey(UgcStage.BASE_REFINE, String.valueOf(i)))) continue;
            out.add(i);
        }
        return out;
    }

    /** DERIVING/REFINING이면서 EMOTION_REFINE 키가 없는 감정 — 순수 판정(테스트 대상). */
    static List<EmotionTag> pendingEmotionTags(Map<EmotionTag, EmotionAssetState> emotions, Map<String, String> scratch) {
        List<EmotionTag> out = new ArrayList<>();
        for (Map.Entry<EmotionTag, EmotionAssetState> e : emotions.entrySet()) {
            EmotionAssetState s = e.getValue();
            if (!(s.is(EmotionAssetState.DERIVING) || s.is(EmotionAssetState.REFINING))) continue;
            if (scratch.containsKey(externalKey(UgcStage.EMOTION_REFINE, e.getKey().name()))) continue;
            out.add(e.getKey());
        }
        return out;
    }

    /** DONE이 아니면서 CUTOUT 키가 없는(미제출) 감정 — 순수 판정(테스트 대상). 원본 key가 없는 컷은 제출 불가라 제외. */
    static List<EmotionTag> pendingCutoutTags(Map<EmotionTag, EmotionAssetState> emotions, Map<String, String> scratch) {
        List<EmotionTag> out = new ArrayList<>();
        for (Map.Entry<EmotionTag, EmotionAssetState> e : emotions.entrySet()) {
            EmotionAssetState s = e.getValue();
            if (s.is(EmotionAssetState.DONE) || s.key() == null) continue;
            if (scratch.containsKey(externalKey(UgcStage.CUTOUT, e.getKey().name()))) continue;
            out.add(e.getKey());
        }
        return out;
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
