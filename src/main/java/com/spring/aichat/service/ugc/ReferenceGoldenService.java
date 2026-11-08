package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.ugc.CharacterCreationJob;
import com.spring.aichat.domain.ugc.CharacterCreationJobRepository;
import com.spring.aichat.domain.ugc.CreationJobStatus;
import com.spring.aichat.domain.ugc.UgcReferenceImage;
import com.spring.aichat.domain.ugc.UgcReferenceImageRepository;
import com.spring.aichat.dto.ugc.FaceDetailTags;
import com.spring.aichat.dto.ugc.IllustrationPrompt;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.dto.ugc.UgcDtos;
import com.spring.aichat.exception.ContentModerationException;
import com.spring.aichat.external.FalGptEmotionEditClient;
import com.spring.aichat.external.UgcComfyClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * [docs/56 §10] 그림 방식 원화 생성 — 글 방식의 WF-1(텍스트→이미지) 자리를 대신한다.
 *
 * <p>흐름(CONCEPT_PROCESSING 구간):
 * <ol>
 *   <li>Stage0: 이미지 프롬프트 LLM 없이 프로필만 만든다. VLM 판독(외형 영문 태그)을 '확정 원화 프롬프트'로
 *       넘겨 기존 {@link ConceptStructuringService#structure} 계약(태그·프로필·배경색·미성년 판정)을 그대로 쓴다.</li>
 *   <li>원화: 올린 그림(또는 고칠 원화)을 GPT Image 2.5 high로 다시 그린다 — 후보마다 독립 요청.</li>
 *   <li>태그: 후보마다 워커 WD14 태거로 정체성 태그를 역추출해 후보별 스냅샷으로 남긴다. 선택 시 그 후보의
 *       태그가 스탠딩·감정 리파인·실시간 일러(잠금) 전부의 외형 기준이 된다. 태거 실패 시 Stage0 태그로 폴백.</li>
 *   <li>전 후보 정착 → 기존 후보 뒤에 누적하고 GACHA_WAIT. 전원 실패면 실패·전액 환불(기존 정책).</li>
 * </ol>
 * 세대 토큰({@value #RUN_KEY})으로 중복 실행·중도 포기 뒤 늦게 도착한 결과를 버린다. 서버 재시작으로 체인이
 * 끊기면 스테일 스윕이 실패·환불한다({@code UgcPipelineWorker.recoverStaleJob} 그림 방식 분기).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceGoldenService {

    static final String RUN_KEY = "K_REF_GOLDEN_RUN";
    /** 후보별 출처 — [{origin: REFERENCE|FIX, fixedFrom}] (golden keys와 같은 순서). */
    static final String ORIGINS_KEY = "K_GOLDEN_ORIGINS";
    /** "이 그림 고치기" 1회용 조정값 — 잡 조정값(다시 뽑기용)과 분리 보관, 정착 시 제거. */
    static final String FIX_ADJUSTMENTS_KEY = "K_REF_FIX_ADJUSTMENTS";
    static final int BATCH_SIZE = 2;
    private static final Duration PRESIGN_TTL = Duration.ofHours(2);
    /**
     * 콜드스타트 포함 태거 상한. 후보 체인 최장 = GPT 대기 15분 + 태거 8분 — 체인마다 이미지 정착 시 updatedAt을
     * 갱신하므로 스테일 스윕(30분 무진행)이 살아 있는 체인을 회수하지 않는다(독립 검토 #4).
     */
    private static final Duration TAGGER_DEADLINE = Duration.ofMinutes(8);
    static long TAGGER_POLL_MS = 4000; // 테스트가 줄인다
    /** 후보당 자동 재시도(무과금) — 일시 오류만. 제출 422 거절은 같은 입력이라 재시도하지 않는다. */
    static final int CANDIDATE_RETRIES = 1;

    /** GPT 제출(블로킹 POST)·S3 복사 전용 — commonPool 점유 방지. 태거 폴링은 지연 실행기로 스레드를 잡지 않는다. 데몬. */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "ugc-reference-golden");
        t.setDaemon(true);
        return t;
    });

    private final CharacterCreationJobRepository jobRepository;
    private final UgcReferenceImageRepository referenceRepository;
    private final ReferenceImageService referenceImageService;
    private final ConceptStructuringService conceptStructuringService;
    private final UgcModerationService moderationService;
    private final FalGptEmotionEditClient gptClient;
    private final UgcComfyClient comfyClient;
    private final UgcWorkflowFactory workflowFactory;
    private final UgcAssetService assetService;
    private final UgcJobJson json;
    private final UgcPipelineProperties props;
    private final TransactionTemplate txTemplate;
    private final UgcPipelineWorker worker;
    private final ObjectMapper objectMapper;

    public record Origin(String origin, Integer fixedFrom) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void runStage0(Long jobId) {
        CharacterCreationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != CreationJobStatus.CONCEPT_PROCESSING || !job.usesReference()) return;
        try {
            UgcReferenceImage ref = referenceRepository.findById(job.getReferenceImageId()).orElseThrow();
            var features = referenceImageService.featuresOf(ref);
            UgcDtos.ReferenceAdjustments adj = adjustments(job);
            String input = UgcPipelineWorker.withGenderDirective(job.getConceptInputRaw(), job.getGenderOrDefault())
                + "\n\n[그림에서 읽은 외형]: " + nz(features.appearanceKo())
                + "\n[그림에서 읽은 복장]: " + nz(features.clothingKo())
                + (adj == null ? "" : "\n[유저가 요청한 변경 — 외형·복장 서술에 반영]: " + ReferencePrompts.koreanSummary(adj));
            // 확정 원화 프롬프트 자리에 그림 판독(영문 태그)을 넣는다 — 프로필 LLM이 이 외형을 기준으로 서술한다.
            IllustrationPrompt prompt = new IllustrationPrompt(positive(features.appearanceEn(), adj), "");
            StructuredConcept concept = structureWithRetry(input, job.getRequestedName(), prompt);
            moderationService.assertStructuredConceptAllowed(concept, job.getConceptInputRaw(), job.getUserId());
            Boolean applied = txTemplate.execute(tx -> {
                CharacterCreationJob locked = jobRepository.findByIdForUpdate(jobId).orElse(null);
                if (locked == null || locked.getStatus() != CreationJobStatus.CONCEPT_PROCESSING) return false;
                locked.applyStage0(json.writeConcept(concept), concept.bgColor());
                return true;
            });
            if (Boolean.TRUE.equals(applied)) generate(jobId, "REFERENCE", null);
        } catch (ContentModerationException e) {
            worker.failAndRefund(jobId, UgcModerationService.BLOCK_MESSAGE);
        } catch (Exception e) {
            log.warn("[UGC-REF] Stage0 실패: jobId={}, {}", jobId, e.getMessage());
            worker.failAndRefund(jobId, "캐릭터 설정을 만들지 못했어요. 사용한 에너지는 환불되었어요.");
        }
    }

    /** 올린 그림에서 다시 뽑기 — 과금·상태 전이(CONCEPT_PROCESSING 복귀)는 서비스 계층이 끝낸 상태. */
    @Async
    public void runReroll(Long jobId) {
        generate(jobId, "REFERENCE", null);
    }

    /** "이 그림 고치기" — 고른 원화를 바탕으로 1장. 과금·상태 전이는 서비스 계층 완료 상태. */
    @Async
    public void runFix(Long jobId, int baseIndex) {
        generate(jobId, "FIX", baseIndex);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void generate(Long jobId, String origin, Integer baseIndex) {
        String token = UUID.randomUUID().toString();
        CharacterCreationJob job = txTemplate.execute(tx -> {
            CharacterCreationJob locked = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (locked == null || locked.getStatus() != CreationJobStatus.CONCEPT_PROCESSING) return null;
            Map<String, String> scratch = json.readScratch(locked.getExternalJobsJson());
            scratch.put(RUN_KEY, token);
            locked.updateExternalJobs(json.writeScratch(scratch));
            return locked;
        });
        if (job == null) return;

        UgcDtos.ReferenceAdjustments adj = "FIX".equals(origin)
            ? readAdjustments(json.readScratch(job.getExternalJobsJson()).get(FIX_ADJUSTMENTS_KEY))
            : adjustments(job);
        String sourceUrl;
        String prompt;
        try {
            if ("FIX".equals(origin)) {
                if (!ReferencePrompts.hasChanges(adj)) throw new IllegalStateException("고치기 조정값 없음");
                List<String> keys = json.readKeys(job.getGoldenShotKeysJson());
                sourceUrl = assetService.presignGet(keys.get(baseIndex), PRESIGN_TTL);
                prompt = ReferencePrompts.fix(adj);
            } else {
                UgcReferenceImage ref = referenceRepository.findById(job.getReferenceImageId()).orElseThrow();
                sourceUrl = assetService.presignGet(ref.getStorageKey(), PRESIGN_TTL);
                prompt = ReferencePrompts.golden(adj);
            }
        } catch (Exception e) {
            // 입력 준비 실패(원본 정리됨·인덱스 이탈) — CONCEPT_PROCESSING에 남겨 두면 스윕까지 30분 좀비가 된다
            log.warn("[UGC-REF] 원화 입력 준비 실패: jobId={}, {}", jobId, e.getMessage());
            worker.failAndRefund(jobId, "원화를 그리지 못했어요. 사용한 에너지는 환불되었어요.");
            return;
        }
        int count = "FIX".equals(origin) ? 1 : BATCH_SIZE;
        String quality = props.standing().gptQualityOrDefault();

        List<CompletableFuture<Candidate>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String label = ("FIX".equals(origin) ? "golden_fix" : "golden_ref") + i;
            futures.add(candidate(jobId, prompt, sourceUrl, quality, label, CANDIDATE_RETRIES));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ok, err) -> {
            List<Candidate> done = futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList();
            settle(jobId, token, origin, baseIndex, done);
        });
    }

    /** 후보 1장: GPT 편집 → S3 복사(+진행 표시) → 태거. 실패 시 일시 오류만 재시도, 최종 실패는 null. */
    private CompletableFuture<Candidate> candidate(Long jobId, String prompt, String sourceUrl, String quality,
                                                   String label, int retriesLeft) {
        return CompletableFuture
            .supplyAsync(() -> submit(jobId, prompt, sourceUrl, quality), EXECUTOR)
            .thenCompose(gptClient::await)
            .thenApplyAsync(result -> {
                String key = assetService.storeFromUrl(result.imageUrl(), jobId, label);
                touch(jobId);
                return key;
            }, EXECUTOR)
            .thenCompose(key -> tagAsync(jobId, key).thenApply(tags -> new Candidate(key, tags)))
            .exceptionallyCompose(err -> {
                Throwable root = root(err);
                boolean rejected = root instanceof FalGptEmotionEditClient.RequestRejected;
                boolean ended = jobRepository.findById(jobId).map(j -> j.getStatus().isTerminal()).orElse(true);
                if (!rejected && !ended && retriesLeft > 0) {
                    log.info("[UGC-REF] 원화 후보 재시도: jobId={}, {}", jobId, rootMessage(err));
                    return candidate(jobId, prompt, sourceUrl, quality, label, retriesLeft - 1);
                }
                log.warn("[UGC-REF] 원화 후보 실패: jobId={}, rejected={}, {}", jobId, rejected, rootMessage(err));
                return CompletableFuture.completedFuture(null);
            });
    }

    /** 체인 진행 표시 — 스테일 스윕이 무진행으로 오판하지 않게 updatedAt 갱신(CONCEPT_PROCESSING일 때만). */
    private void touch(Long jobId) {
        txTemplate.executeWithoutResult(tx -> jobRepository.findByIdForUpdate(jobId)
            .filter(j -> j.getStatus() == CreationJobStatus.CONCEPT_PROCESSING)
            .ifPresent(CharacterCreationJob::touchRecovery));
    }

    private FalGptEmotionEditClient.Receipt submit(Long jobId, String prompt, String sourceUrl, String quality) {
        // 늦은 제출 차단 — 중도 포기(취소=정산) 후 외부 지출을 만들지 않는다
        CharacterCreationJob current = jobRepository.findById(jobId).orElse(null);
        if (current == null || current.getStatus().isTerminal()) throw new CompletionException(new IllegalStateException("종결 잡"));
        try {
            var receipt = gptClient.submit(prompt, sourceUrl, quality);
            log.info("[UGC-REF] GPT 원화 접수: jobId={}, requestId={}", jobId, receipt.requestId());
            return receipt;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    private record Candidate(String key, List<String> tags) {}

    private void settle(Long jobId, String token, String origin, Integer baseIndex, List<Candidate> done) {
        if (done.isEmpty()) {
            Boolean current = txTemplate.execute(tx -> jobRepository.findByIdForUpdate(jobId)
                .map(j -> j.getStatus() == CreationJobStatus.CONCEPT_PROCESSING
                    && token.equals(json.readScratch(j.getExternalJobsJson()).get(RUN_KEY))).orElse(false));
            if (Boolean.TRUE.equals(current)) {
                worker.failAndRefund(jobId, "원화를 그리지 못했어요. 사용한 에너지는 환불되었어요.");
            }
            return;
        }
        txTemplate.executeWithoutResult(tx -> {
            CharacterCreationJob j = jobRepository.findByIdForUpdate(jobId).orElse(null);
            if (j == null || j.getStatus() != CreationJobStatus.CONCEPT_PROCESSING) return;
            Map<String, String> scratch = json.readScratch(j.getExternalJobsJson());
            if (!token.equals(scratch.get(RUN_KEY))) return; // 다른 세대(중복 실행) 결과
            StructuredConcept latest = json.readConcept(j.getStructuredConceptJson());
            List<String> keys = new ArrayList<>(json.readKeys(j.getGoldenShotKeysJson()));
            List<Origin> origins = new ArrayList<>(readOrigins(scratch.get(ORIGINS_KEY), keys.size()));
            List<UgcJobJson.GoldenSnapshot> snaps =
                new ArrayList<>(json.readGoldenSnapshots(scratch.get(UgcPipelineWorker.GOLDEN_SNAPSHOTS_KEY)));
            for (Candidate c : done) {
                int index = keys.size();
                keys.add(c.key());
                origins.add(new Origin(origin, baseIndex));
                snaps.add(new UgcJobJson.GoldenSnapshot(index, json.writeConcept(withTags(latest, c.tags()))));
            }
            scratch.remove(RUN_KEY);
            scratch.remove(FIX_ADJUSTMENTS_KEY);
            scratch.put(ORIGINS_KEY, writeOrigins(origins));
            scratch.put(UgcPipelineWorker.GOLDEN_SNAPSHOTS_KEY, json.writeGoldenSnapshots(snaps));
            j.updateExternalJobs(json.writeScratch(scratch));
            j.toGachaWait(json.writeKeys(keys), props.job().ttlHours());
        });
        log.info("[UGC-REF] 원화 {}장 정착: jobId={}, origin={}", done.size(), jobId, origin);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  태거 (워커 WD14 노드 — 텍스트 출력)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 태거 — 실패해도 후보를 버리지 않는다(빈 리스트면 스냅샷이 Stage0 그림 판독 태그를 그대로 쓴다).
     * 폴링은 지연 실행기 재귀라 대기 중 스레드를 점유하지 않는다(독립 검토 #4 — 고정 풀 고갈 방지).
     */
    private CompletableFuture<List<String>> tagAsync(Long jobId, String key) {
        CompletableFuture<List<String>> out = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                String name = "tag_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
                var submit = comfyClient.submit(workflowFactory.buildTagger(name),
                    List.of(new UgcComfyClient.InputImage(name, Base64.getEncoder().encodeToString(assetService.download(key)))),
                    null);
                pollTags(jobId, submit.jobId(), System.currentTimeMillis() + TAGGER_DEADLINE.toMillis(), out);
            } catch (Exception e) {
                log.warn("[UGC-REF] 태거 제출 실패 — Stage0 태그 폴백: jobId={}, {}", jobId, e.getMessage());
                out.complete(List.of());
            }
        }, EXECUTOR);
        return out;
    }

    private void pollTags(Long jobId, String runpodJobId, long deadline, CompletableFuture<List<String>> out) {
        CompletableFuture.delayedExecutor(TAGGER_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS, EXECUTOR).execute(() -> {
            if (System.currentTimeMillis() > deadline) {
                log.warn("[UGC-REF] 태거 시간 초과 — Stage0 태그 폴백: jobId={}, runpod={}", jobId, runpodJobId);
                out.complete(List.of());
                return;
            }
            UgcComfyClient.TextStatus status = comfyClient.getTextStatus(runpodJobId);
            if (status.completed()) {
                List<String> tags = status.tags().isEmpty() ? List.of() : IdentityTagFilter.identityTags(status.tags().get(0));
                if (tags.isEmpty()) log.warn("[UGC-REF] 태거 결과 비어 있음 — Stage0 태그 폴백: jobId={}", jobId);
                out.complete(tags);
            } else if (status.failed()) {
                log.warn("[UGC-REF] 태거 실패 — Stage0 태그 폴백: jobId={}, {}", jobId, status.error());
                out.complete(List.of());
            } else {
                pollTags(jobId, runpodJobId, deadline, out); // IN_QUEUE·IN_PROGRESS·일시 오류
            }
        });
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 후보별 스냅샷 — 외형 태그와 얼굴 복원 눈 색만 후보 것으로 바꾼다(태그가 비면 원본 유지). */
    static StructuredConcept withTags(StructuredConcept c, List<String> tags) {
        if (tags == null || tags.isEmpty()) return c;
        String expression = c.faceDetailTags() == null ? null : c.faceDetailTags().expression();
        String eye = IdentityTagFilter.eyeColor(tags);
        FaceDetailTags face = new FaceDetailTags(eye != null ? eye : (c.faceDetailTags() == null ? null : c.faceDetailTags().eyeColor()), expression);
        return new StructuredConcept(tags, c.personaTags(), c.moodTags(), c.sceneTags(), c.bgColor(), c.character(),
            c.moderation(), c.basePose(), c.emotionPrompts(), c.illustrationPrompt(), face);
    }

    /** 그림 판독 영문 태그 + 조정의 영문 서술 — 프로필 LLM이 바뀐 복장·체형까지 서술하게 한다. */
    static String positive(String appearanceEn, UgcDtos.ReferenceAdjustments adj) {
        String base = appearanceEn == null || appearanceEn.isBlank() ? "1girl, solo" : appearanceEn.strip();
        List<String> changes = ReferencePrompts.changes(adj);
        return changes.isEmpty() ? base : base + "\n[requested changes override the reference]\n" + String.join("\n", changes);
    }

    public UgcDtos.ReferenceAdjustments adjustments(CharacterCreationJob job) {
        return readAdjustments(job.getAdjustmentsJson());
    }

    private UgcDtos.ReferenceAdjustments readAdjustments(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, UgcDtos.ReferenceAdjustments.class);
        } catch (Exception e) {
            return null;
        }
    }

    public List<Origin> origins(CharacterCreationJob job) {
        int size = json.readKeys(job.getGoldenShotKeysJson()).size();
        return readOrigins(json.readScratch(job.getExternalJobsJson()).get(ORIGINS_KEY), size);
    }

    private List<Origin> readOrigins(String raw, int size) {
        List<Origin> out = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            try {
                out.addAll(objectMapper.readValue(raw, new TypeReference<List<Origin>>() {}));
            } catch (Exception ignored) {
                // 손상 시 아래에서 REFERENCE로 채운다 — 뷰 표기용이라 생성 흐름에 영향 없음
            }
        }
        while (out.size() < size) out.add(new Origin("REFERENCE", null));
        return out.subList(0, size);
    }

    private String writeOrigins(List<Origin> origins) {
        try {
            return objectMapper.writeValueAsString(origins);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private StructuredConcept structureWithRetry(String input, String name, IllustrationPrompt prompt) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= props.job().autoRetries(); attempt++) {
            try {
                return conceptStructuringService.structure(input, name, prompt);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static Throwable root(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t;
    }

    private static String rootMessage(Throwable t) {
        Throwable r = root(t);
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }
}
