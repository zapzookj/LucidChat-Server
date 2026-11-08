package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.ugc.CharacterCreationJob;
import com.spring.aichat.domain.ugc.CharacterCreationJobRepository;
import com.spring.aichat.domain.ugc.CreationJobStatus;
import com.spring.aichat.domain.ugc.UgcReferenceImage;
import com.spring.aichat.domain.ugc.UgcReferenceImageRepository;
import com.spring.aichat.domain.user.EnergySplit;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.external.FalGptEmotionEditClient;
import com.spring.aichat.external.UgcComfyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** [docs/56 §10] 그림 방식 원화 체인 — 후보별 태그 스냅샷·출처 누적·전원 실패 환불·종결 잡 무시. */
class ReferenceGoldenServiceTest {

    final ObjectMapper mapper = new ObjectMapper();
    final UgcJobJson json = new UgcJobJson(mapper);
    final UgcPipelineProperties props = new UgcPipelineProperties(null, null, null, null, null, null, null, null);
    final CharacterCreationJobRepository jobs = mock(CharacterCreationJobRepository.class);
    final UgcReferenceImageRepository refs = mock(UgcReferenceImageRepository.class);
    final FalGptEmotionEditClient gpt = mock(FalGptEmotionEditClient.class);
    final UgcComfyClient comfy = mock(UgcComfyClient.class);
    final UgcWorkflowFactory factory = mock(UgcWorkflowFactory.class);
    final UgcAssetService assets = mock(UgcAssetService.class);
    final UgcPipelineWorker worker = mock(UgcPipelineWorker.class);
    final TransactionTemplate tx = mock(TransactionTemplate.class);
    final FalGptEmotionEditClient.Receipt receipt =
        new FalGptEmotionEditClient.Receipt("r1", "https://queue.fal.run/s/r1", "https://queue.fal.run/r/r1");
    ReferenceGoldenService service;
    CharacterCreationJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        doAnswer(i -> { ((Consumer<org.springframework.transaction.TransactionStatus>) i.getArgument(0)).accept(new SimpleTransactionStatus()); return null; })
            .when(tx).executeWithoutResult(any());
        ReferenceGoldenService.TAGGER_POLL_MS = 50;
        service = new ReferenceGoldenService(jobs, refs, null, null, null, gpt, comfy, factory, assets, json, props, tx, worker, mapper);

        job = CharacterCreationJob.start(1L, null, "성격 이야기", EnergySplit.of(0, 0));
        ReflectionTestUtils.setField(job, "id", 7L);
        job.assignReference(5L, null);
        job.applyStage0(json.writeConcept(new StructuredConcept(List.of("from vlm"), List.of("calm"), List.of(), List.of(),
            "light gray", null, null, null, null)), "light gray");
        when(jobs.findById(7L)).thenAnswer(i -> Optional.of(job));
        when(jobs.findByIdForUpdate(7L)).thenAnswer(i -> Optional.of(job));
        UgcReferenceImage ref = UgcReferenceImage.of(1L, "ugc/references/1/a.png", "0".repeat(64), 800, 1000,
            UgcReferenceImage.Verdict.OK, "{}");
        when(refs.findById(5L)).thenReturn(Optional.of(ref));
        when(assets.presignGet(anyString(), any())).thenAnswer(i -> "https://r2.example/" + i.getArgument(0));
        AtomicInteger n = new AtomicInteger();
        when(assets.storeFromUrl(anyString(), eq(7L), anyString())).thenAnswer(i -> "ugc/jobs/7/" + i.getArgument(2) + "_" + n.incrementAndGet() + ".png");
        when(assets.download(anyString())).thenReturn(new byte[]{1, 2, 3});
        when(factory.buildTagger(anyString())).thenReturn(mapper.createObjectNode());
        when(comfy.submit(any(), anyList(), isNull())).thenReturn(new UgcComfyClient.SubmitResult("rp1", "IN_QUEUE"));
        when(comfy.getTextStatus("rp1")).thenReturn(
            new UgcComfyClient.TextStatus("COMPLETED", List.of("1girl, blonde hair, yellow eyes, thigh gap, medical eyepatch"), null));
    }

    void awaitStatus(CreationJobStatus status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (job.getStatus() != status && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertThat(job.getStatus()).isEqualTo(status);
    }

    @Test void batchAppendsTwoCandidatesWithTheirOwnTaggerSnapshots() throws Exception {
        when(gpt.submit(anyString(), eq("https://r2.example/ugc/references/1/a.png"), eq("high"))).thenReturn(receipt);
        when(gpt.await(receipt)).thenReturn(CompletableFuture.completedFuture(new FalGptEmotionEditClient.Result("https://fal.media/x.png")));

        service.runReroll(7L);
        awaitStatus(CreationJobStatus.GACHA_WAIT);

        List<String> keys = json.readKeys(job.getGoldenShotKeysJson());
        assertThat(keys).hasSize(2).allMatch(k -> k.contains("golden_ref"));
        Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
        assertThat(scratch).doesNotContainKey(ReferenceGoldenService.RUN_KEY);
        var snaps = json.readGoldenSnapshots(scratch.get(UgcPipelineWorker.GOLDEN_SNAPSHOTS_KEY));
        assertThat(snaps).extracting(UgcJobJson.GoldenSnapshot::startIndex).containsExactly(0, 1);
        StructuredConcept snap = json.readConcept(snaps.get(1).conceptJson());
        assertThat(snap.appearanceTags()).containsExactly("blonde hair", "yellow eyes", "medical eyepatch"); // 신체 강조 제거
        assertThat(snap.faceDetailTags().eyeColor()).isEqualTo("yellow eyes");
        assertThat(service.origins(job)).extracting(ReferenceGoldenService.Origin::origin).containsExactly("REFERENCE", "REFERENCE");
        verify(worker, never()).failAndRefund(anyLong(), anyString());
    }

    @Test void taggerFailureKeepsTheImageWithStage0Tags() throws Exception {
        when(gpt.submit(anyString(), anyString(), anyString())).thenReturn(receipt);
        when(gpt.await(receipt)).thenReturn(CompletableFuture.completedFuture(new FalGptEmotionEditClient.Result("https://fal.media/x.png")));
        when(comfy.getTextStatus("rp1")).thenReturn(new UgcComfyClient.TextStatus("FAILED", List.of(), "node error"));

        service.runReroll(7L);
        awaitStatus(CreationJobStatus.GACHA_WAIT);

        var snaps = json.readGoldenSnapshots(json.readScratch(job.getExternalJobsJson()).get(UgcPipelineWorker.GOLDEN_SNAPSHOTS_KEY));
        assertThat(snaps).hasSize(2);
        assertThat(json.readConcept(snaps.get(0).conceptJson()).appearanceTags()).containsExactly("from vlm");
    }

    @Test void transientFailureIsRetriedOnceWithoutRefund() throws Exception {
        when(gpt.submit(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("fal HTTP 503")).thenReturn(receipt);
        when(gpt.await(receipt)).thenReturn(CompletableFuture.completedFuture(new FalGptEmotionEditClient.Result("https://fal.media/x.png")));

        service.runReroll(7L); // 두 후보 중 먼저 제출한 쪽이 503 → 그 후보만 1회 재시도
        awaitStatus(CreationJobStatus.GACHA_WAIT);
        assertThat(json.readKeys(job.getGoldenShotKeysJson())).hasSize(2);
        verify(gpt, atLeast(3)).submit(anyString(), anyString(), anyString()); // 한 후보는 재시도
        verify(worker, never()).failAndRefund(anyLong(), anyString());
    }

    @Test void allCandidatesFailingRefunds() throws Exception {
        when(gpt.submit(anyString(), anyString(), anyString())).thenThrow(new FalGptEmotionEditClient.RequestRejected("content_policy_violation"));

        service.runReroll(7L);
        verify(worker, timeout(10000)).failAndRefund(eq(7L), contains("원화를 그리지 못했어요"));
        assertThat(job.getStatus()).isEqualTo(CreationJobStatus.CONCEPT_PROCESSING);
        verify(assets, never()).storeFromUrl(anyString(), anyLong(), anyString());
    }

    @Test void fixAddsOneCandidateFromTheChosenGoldenAndClearsTheOneShotAdjustments() throws Exception {
        job.toGachaWait(json.writeKeys(List.of("ugc/jobs/7/golden_ref0_1.png")), 72);
        job.restartGoldenGeneration();
        Map<String, String> scratch = json.readScratch(job.getExternalJobsJson());
        scratch.put(ReferenceGoldenService.FIX_ADJUSTMENTS_KEY, "{\"outfit\":\"maid\",\"body\":\"keep\",\"bust\":\"keep\"}");
        job.updateExternalJobs(json.writeScratch(scratch));
        when(gpt.submit(contains("Edit only the following"), eq("https://r2.example/ugc/jobs/7/golden_ref0_1.png"), eq("high")))
            .thenReturn(receipt);
        when(gpt.await(receipt)).thenReturn(CompletableFuture.completedFuture(new FalGptEmotionEditClient.Result("https://fal.media/f.png")));

        service.runFix(7L, 0);
        awaitStatus(CreationJobStatus.GACHA_WAIT);

        assertThat(json.readKeys(job.getGoldenShotKeysJson())).hasSize(2);
        assertThat(service.origins(job).get(1)).isEqualTo(new ReferenceGoldenService.Origin("FIX", 0));
        assertThat(json.readScratch(job.getExternalJobsJson())).doesNotContainKey(ReferenceGoldenService.FIX_ADJUSTMENTS_KEY);
        verify(gpt, times(1)).submit(anyString(), anyString(), anyString());
    }

    @Test void resultsArrivingAfterTheJobEndedAreDropped() throws Exception {
        when(gpt.submit(anyString(), anyString(), anyString())).thenReturn(receipt);
        when(gpt.await(receipt)).thenAnswer(i -> {
            ReflectionTestUtils.setField(job, "status", CreationJobStatus.FAILED); // 중도 포기
            return CompletableFuture.completedFuture(new FalGptEmotionEditClient.Result("https://fal.media/x.png"));
        });

        service.runReroll(7L);
        Thread.sleep(6000);
        assertThat(job.getGoldenShotKeysJson()).isNull();
        verify(worker, never()).failAndRefund(anyLong(), anyString());
    }
}
