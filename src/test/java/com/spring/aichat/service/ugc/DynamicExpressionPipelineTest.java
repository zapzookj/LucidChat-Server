package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.ugc.*;
import com.spring.aichat.domain.user.EnergySplit;
import com.spring.aichat.dto.ugc.*;
import com.spring.aichat.external.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DynamicExpressionPipelineTest {
    @Mock CharacterCreationJobRepository jobs;
    @Mock CharacterRepository characters;
    @Mock FalGptEmotionEditClient gpt;
    @Mock PoseEditClient qwen;
    @Mock UgcComfyClient comfy;
    @Mock UgcAssetService assets;
    @Mock UgcWorkflowFactory workflow;
    @Mock com.spring.aichat.domain.user.UserRepository users;
    @Mock com.spring.aichat.service.cache.RedisCacheService cache;
    @Mock TransactionTemplate tx;
    @Spy UgcJobJson json = new UgcJobJson(new ObjectMapper());
    @Spy UgcPromptAssembler prompts = new UgcPromptAssembler(new UgcPipelineProperties(null, null, null, null, null, null, null, null));
    @Spy UgcPipelineProperties props = new UgcPipelineProperties(null, null, null, null, null, null, null, null);
    @InjectMocks UgcPipelineWorker worker;
    CharacterCreationJob job;
    final FalGptEmotionEditClient.Receipt receipt = new FalGptEmotionEditClient.Receipt("r1", "https://queue.fal.run/status/r1", "https://queue.fal.run/result/r1");

    static List<CharacterExpression> catalog() {
        List<CharacterExpression> list = new ArrayList<>();
        list.add(CharacterExpression.neutral());
        for (int i = 1; i <= 8; i++) list.add(new CharacterExpression("EX_%02d".formatted(i), "장난 " + i,
            "장난을 치는 상황 " + i, EmotionTag.JOY, "a restrained knowing smile", "one hand on hip"));
        return list;
    }

    @BeforeEach void setup() {
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        doAnswer(i -> { ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) i.getArgument(0)).accept(new SimpleTransactionStatus()); return null; })
            .when(tx).executeWithoutResult(any());
        job = CharacterCreationJob.start(1L, "테스트", "테스트", EnergySplit.of(0, 0));
        ReflectionTestUtils.setField(job, "id", 7L);
        job.enableDynamicExpressions();
        job.toEmotionsProcessing("base.png");
        job.freezeExpressionCatalog(ExpressionCatalog.write(catalog()));
        Map<String, EmotionAssetState> map = new LinkedHashMap<>();
        for (var e : catalog()) map.put(e.id(), e.id().equals("NEUTRAL") ? EmotionAssetState.ready("base.png") : EmotionAssetState.deriving(0));
        job.updateEmotionAssets(json.writeExpressionAssets(map));
        when(jobs.findById(7L)).thenReturn(Optional.of(job));
        when(jobs.findByIdForUpdate(7L)).thenReturn(Optional.of(job));
        when(assets.presignGet(eq("base.png"), any())).thenReturn("https://assets.example/base.png");
    }
    void submit(String id) { ReflectionTestUtils.invokeMethod(worker, "submitGptExpression", 7L, id); }
    String encodedReceipt() { return json.writeScratch(Map.of("requestId", receipt.requestId(), "statusUrl", receipt.statusUrl(), "responseUrl", receipt.responseUrl())); }
    EmotionAssetState state() { return json.readExpressionAssets(job.getEmotionAssetsJson()).get("EX_01"); }

    @Test void initialUsesLowAndSkipsAllRefinement() throws Exception {
        when(gpt.submit(anyString(), anyString(), eq("low"))).thenReturn(receipt);
        when(gpt.await(receipt)).thenReturn(CompletableFuture.completedFuture(new FalGptEmotionEditClient.Result("https://fal.media/result.png")));
        when(assets.storeFromUrl(anyString(), eq(7L), anyString())).thenReturn("edit.png");
        submit("EX_01");
        assertThat(state().status()).isEqualTo(EmotionAssetState.READY);
        assertThat(state().key()).isEqualTo("edit.png");
        verify(gpt).submit(anyString(), anyString(), eq("low"));
        verifyNoInteractions(qwen, comfy);
    }

    @Test void acceptedRequestRecoveryPollsWithoutAnotherPaidPost() throws Exception {
        job.updateExternalJobs(json.writeScratch(Map.of("K_GPT_RECEIPT_EX_01", encodedReceipt(), "K_GPT_QUALITY_EX_01", "low")));
        when(gpt.await(receipt)).thenReturn(new CompletableFuture<>());
        submit("EX_01"); submit("EX_01");
        verify(gpt, never()).submit(anyString(), anyString(), anyString());
        verify(gpt, times(1)).await(receipt);
    }

    @Test void ambiguousSubmissionStopsWithoutAutomaticPostAndManualRetryIsFree() throws Exception {
        job.updateExternalJobs(json.writeScratch(Map.of("K_GPT_RECEIPT_EX_01", "SUBMITTING:1:attempt")));
        submit("EX_01");
        assertThat(state().isFreeReroll()).isTrue();
        verify(gpt, never()).submit(anyString(), anyString(), anyString());
    }

    @Test void userRerollUsesHighAndKeepsEarlierVersion() throws Exception {
        var map = json.readExpressionAssets(job.getEmotionAssetsJson());
        map.put("EX_01", EmotionAssetState.ready("previous.png"));
        job.updateEmotionAssets(json.writeExpressionAssets(map));
        job.toReviewWait(72);
        worker.resetEmotionForReroll(job, "EX_01");
        when(gpt.submit(anyString(), anyString(), eq("high"))).thenReturn(receipt);
        when(gpt.await(receipt)).thenReturn(new CompletableFuture<>());
        submit("EX_01");
        assertThat(state().history()).containsExactly("previous.png");
        assertThat(state().status()).isEqualTo(EmotionAssetState.DERIVING);
        verify(gpt).submit(anyString(), anyString(), eq("high"));
    }

    @Test void automaticRetryPreservesOriginalLowQuality() throws Exception {
        var second = new FalGptEmotionEditClient.Receipt("r2", "https://queue.fal.run/status/r2", "https://queue.fal.run/result/r2");
        when(gpt.submit(anyString(), anyString(), eq("low"))).thenReturn(receipt, second);
        when(gpt.await(receipt)).thenReturn(CompletableFuture.failedFuture(new FalGptEmotionEditClient.ProviderFailure("failed")));
        when(gpt.await(second)).thenReturn(new CompletableFuture<>());
        submit("EX_01");
        verify(gpt, times(2)).submit(anyString(), anyString(), eq("low"));
        verify(gpt, never()).submit(anyString(), anyString(), eq("high"));
        assertThat(state().retryCount()).isEqualTo(1);
    }

    @Test void staleGptResultCannotOverwriteNewReroll() throws Exception {
        CompletableFuture<FalGptEmotionEditClient.Result> old = new CompletableFuture<>();
        when(gpt.submit(anyString(), anyString(), eq("low"))).thenReturn(receipt);
        when(gpt.await(receipt)).thenReturn(old);
        submit("EX_01");
        // A previous generation was settled and the user started a different generation.
        var map = json.readExpressionAssets(job.getEmotionAssetsJson());
        map.put("EX_01", state().readyWith("selected.png"));
        job.updateEmotionAssets(json.writeExpressionAssets(map));
        worker.resetEmotionForReroll(job, "EX_01");
        old.complete(new FalGptEmotionEditClient.Result("https://fal.media/old.png"));
        assertThat(state().key()).isEqualTo("selected.png");
        assertThat(state().history()).containsExactly("selected.png");
        verify(assets, never()).storeFromUrl(anyString(), anyLong(), anyString());
    }

    @Test void equalCountWithWrongIdsCannotFinish() {
        var map = json.readExpressionAssets(job.getEmotionAssetsJson());
        map.replaceAll((id, s) -> EmotionAssetState.ready("k-" + id));
        map.put("INTRUDER", map.remove("EX_08"));
        ReflectionTestUtils.invokeMethod(worker, "checkEmotionsSettled", job, map);
        assertThat(job.getStatus()).isEqualTo(CreationJobStatus.EMOTIONS_PROCESSING);
        map.put("EX_08", map.remove("INTRUDER"));
        ReflectionTestUtils.invokeMethod(worker, "checkEmotionsSettled", job, map);
        assertThat(job.getStatus()).isEqualTo(CreationJobStatus.REVIEW_WAIT);
    }

    @Test void legacyJobsStayLegacyAndCatalogCannotChange() {
        var legacy = CharacterCreationJob.start(1L, "old", "old", EnergySplit.of(0, 0));
        assertThat(legacy.usesDynamicExpressions()).isFalse();
        assertThat(ExpressionCatalog.expectedIds(null)).hasSize(15).contains("JOY");
        var changed = new ArrayList<>(catalog());
        changed.set(1, new CharacterExpression("EX_01", "다름", "다른 상황", EmotionTag.SAD, "a soft frown", "arms lowered"));
        assertThatThrownBy(() -> job.freezeExpressionCatalog(ExpressionCatalog.write(changed))).isInstanceOf(IllegalStateException.class);
    }

    @Test void unknownLegacyRefineFailureCannotInsertANewSlot() {
        ReflectionTestUtils.setField(job, "expressionPipelineVersion", null);
        worker.onComfyEvent(7L, UgcStage.EMOTION_REFINE, "INTRUDER", UgcComfyClient.JobStatus.lost("dead", "failed"));
        assertThat(json.readExpressionAssets(job.getEmotionAssetsJson())).doesNotContainKey("INTRUDER");
        verifyNoInteractions(gpt, comfy);
    }

    void cutting() {
        job.toReviewWait(72); job.toPostprocessing();
        var map = json.readExpressionAssets(job.getEmotionAssetsJson());
        map.replaceAll((id, s) -> EmotionAssetState.ready("k-" + id).cutting());
        job.updateEmotionAssets(json.writeExpressionAssets(map));
    }

    @Test void duplicateCutoutSubmissionUsesOneRunpodPost() {
        cutting();
        when(assets.download("k-EX_01")).thenReturn(new byte[]{1,2});
        when(workflow.buildCutout(anyString(), anyString())).thenReturn(new ObjectMapper().createObjectNode());
        java.util.concurrent.atomic.AtomicBoolean reentered = new java.util.concurrent.atomic.AtomicBoolean();
        when(comfy.submit(any(), anyList(), any())).thenAnswer(i -> {
            if (reentered.compareAndSet(false,true)) ReflectionTestUtils.invokeMethod(worker,"submitCutout",7L,"EX_01","k-EX_01");
            return new UgcComfyClient.SubmitResult("cut1", "IN_QUEUE");
        });
        ReflectionTestUtils.invokeMethod(worker, "submitCutout", 7L, "EX_01", "k-EX_01");
        ReflectionTestUtils.invokeMethod(worker, "submitCutout", 7L, "EX_01", "k-EX_01");
        verify(comfy, times(1)).submit(any(), anyList(), any());
        assertThat(json.readScratch(job.getExternalJobsJson())).containsEntry("CUTOUT:EX_01","cut1");
    }

    @Test void oldCutoutCompletionCannotOverwriteCurrentGeneration() {
        cutting();
        job.updateExternalJobs(json.writeScratch(Map.of("CUTOUT:EX_01","new-cut")));
        worker.onComfyEvent(7L,UgcStage.CUTOUT,"EX_01",new UgcComfyClient.JobStatus("old-cut","COMPLETED",
            List.of(new UgcComfyClient.OutputImage("cut.png","s3","https://assets.example/old.png")),null,null,null));
        assertThat(state().status()).isEqualTo(EmotionAssetState.CUTTING);
        verify(assets, never()).storeFromUrl(anyString(),anyLong(),anyString());
    }

    @Test void persistedLostCutoutIsSettledBeforeRecoveryReposts() {
        cutting();
        job.updateExternalJobs(json.writeScratch(Map.of("K_CUT_LOST_REQUEST_EX_01","lost-cut")));
        when(assets.download("k-EX_01")).thenReturn(new byte[]{1,2});
        when(workflow.buildCutout(anyString(),anyString())).thenReturn(new ObjectMapper().createObjectNode());
        when(comfy.submit(any(),anyList(),any())).thenReturn(new UgcComfyClient.SubmitResult("new-cut","IN_QUEUE"));
        ReflectionTestUtils.invokeMethod(worker,"resumeCutoutStage",job,json.readScratch(job.getExternalJobsJson()));
        assertThat(state().retryCount()).isEqualTo(1);
        assertThat(json.readScratch(job.getExternalJobsJson())).doesNotContainKey("K_CUT_LOST_REQUEST_EX_01").containsEntry("CUTOUT:EX_01","new-cut");
        verify(comfy,times(1)).submit(any(),anyList(),any());
    }

    @Test void duplicateUserRerollChargesOnlyOnceAndPreservesPaidSplit() {
        var map=json.readExpressionAssets(job.getEmotionAssetsJson());
        map.put("EX_01",EmotionAssetState.ready("previous.png"));
        job.updateEmotionAssets(json.writeExpressionAssets(map));job.toReviewWait(72);
        var user=mock(com.spring.aichat.domain.user.User.class);
        when(users.findByUsername("owner")).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(1L);when(user.consumeEnergy(2)).thenReturn(EnergySplit.of(2,1));
        UgcPipelineWorker spy=spy(worker);
        doNothing().when(spy).runEmotionReroll(anyLong(),anyString());
        var service=new CharacterCreationService(jobs,users,null,props,null,spy,null,null,null,json,cache,tx,null);
        service.rerollEmotion("owner",7L,"EX_01");
        assertThatThrownBy(() -> service.rerollEmotion("owner",7L,"EX_01")).isInstanceOf(com.spring.aichat.exception.BadRequestException.class);
        verify(user,times(1)).consumeEnergy(2);
        verify(spy,times(1)).runEmotionReroll(7L,"EX_01");
        assertThat(job.getEnergyCharged()).isEqualTo(2);assertThat(job.getEnergyChargedPaid()).isEqualTo(1);
        assertThatThrownBy(() -> service.selectEmotionVersion("owner",7L,"EX_01",0)).isInstanceOf(com.spring.aichat.exception.BadRequestException.class);
        assertThat(state().status()).isEqualTo(EmotionAssetState.DERIVING);
    }

    @Test void highRerollRetriesStayHighAndExhaustionPreservesPaidValue() throws Exception {
        var map=json.readExpressionAssets(job.getEmotionAssetsJson());map.put("EX_01",EmotionAssetState.ready("previous.png"));
        job.updateEmotionAssets(json.writeExpressionAssets(map));job.toReviewWait(72);worker.resetEmotionForReroll(job,"EX_01");
        java.util.concurrent.atomic.AtomicInteger sequence=new java.util.concurrent.atomic.AtomicInteger();
        when(gpt.submit(anyString(),anyString(),eq("high"))).thenAnswer(i -> {
            String id="high-"+sequence.incrementAndGet();
            return new FalGptEmotionEditClient.Receipt(id,"https://queue.fal.run/status/"+id,"https://queue.fal.run/result/"+id);
        });
        when(gpt.await(any())).thenReturn(CompletableFuture.failedFuture(new FalGptEmotionEditClient.ProviderFailure("provider failed")));
        submit("EX_01");
        verify(gpt,times(props.job().emotionRetries()+1)).submit(anyString(),anyString(),eq("high"));
        verify(gpt,never()).submit(anyString(),anyString(),eq("low"));
        assertThat(state().status()).isEqualTo(EmotionAssetState.READY);
        assertThat(state().key()).isEqualTo("previous.png");assertThat(state().history()).containsExactly("previous.png");
        assertThat(state().isFreeReroll()).isTrue();
    }

    /**
     * [2026-11-09 핫픽스 · 잡 51] 요청 번호 기록이 lost update로 지워져 예약만 남은 컷 — 종전엔 잡 전체를 실패·환불했다.
     * 이제 예약을 넘겨받아 그 컷만 다시 제출하고(재시도 예산 1 사용), 잡은 계속 진행한다.
     */
    @Test void staleReservationWithoutRequestIdResubmitsOnlyThatCut() {
        cutting();job.updateExternalJobs(json.writeScratch(Map.of("K_CUT_SUBMITTING_EX_01","1:unknown")));
        when(assets.download("k-EX_01")).thenReturn(new byte[]{1,2});
        when(workflow.buildCutout(anyString(),anyString())).thenReturn(new ObjectMapper().createObjectNode());
        when(comfy.submit(any(),anyList(),any())).thenReturn(new UgcComfyClient.SubmitResult("retry-cut","IN_QUEUE"));
        ReflectionTestUtils.invokeMethod(worker,"submitCutout",7L,"EX_01","k-EX_01");
        assertThat(job.getStatus()).isEqualTo(CreationJobStatus.POSTPROCESSING);
        assertThat(state().retryCount()).isEqualTo(1);
        assertThat(json.readScratch(job.getExternalJobsJson())).containsEntry("CUTOUT:EX_01","retry-cut").doesNotContainKey("K_CUT_SUBMITTING_EX_01");
        verify(comfy,times(1)).submit(any(),anyList(),any());
    }

    @Test void staleReservationAfterTheRetryBudgetFailsWithoutAnotherPost() {
        cutting();
        var map = json.readExpressionAssets(job.getEmotionAssetsJson());
        map.put("EX_01", map.get("EX_01").withRetry(props.job().emotionRetries()));
        job.updateEmotionAssets(json.writeExpressionAssets(map));
        job.updateExternalJobs(json.writeScratch(Map.of("K_CUT_SUBMITTING_EX_01","1:unknown")));
        ReflectionTestUtils.invokeMethod(worker,"submitCutout",7L,"EX_01","k-EX_01");
        assertThat(job.getStatus()).isEqualTo(CreationJobStatus.FAILED);
        verifyNoInteractions(comfy,gpt,qwen,assets);
    }

    @Test void freshReservationIsLeftToTheSubmissionInFlight() {
        cutting();job.updateExternalJobs(json.writeScratch(Map.of("K_CUT_SUBMITTING_EX_01",System.currentTimeMillis()+":in-flight")));
        ReflectionTestUtils.invokeMethod(worker,"submitCutout",7L,"EX_01","k-EX_01");
        assertThat(job.getStatus()).isEqualTo(CreationJobStatus.POSTPROCESSING);
        assertThat(state().retryCount()).isZero();
        verifyNoInteractions(comfy,gpt,qwen,assets);
    }

    @Test void lateAcceptedCutoutIsNotResubmitted() {
        // 예약을 관측한 뒤 락을 잡기 직전에 원래 제출의 요청 번호가 기록된 경우 — 락 안에서 다시 보고 아무것도 하지 않는다
        cutting();job.updateExternalJobs(json.writeScratch(Map.of("K_CUT_SUBMITTING_EX_01","1:old")));
        doAnswer(i -> {
            job.updateExternalJobs(json.writeScratch(Map.of("CUTOUT:EX_01","just-accepted")));
            return ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(new SimpleTransactionStatus());
        }).when(tx).execute(any());
        ReflectionTestUtils.invokeMethod(worker,"submitCutout",7L,"EX_01","k-EX_01");
        assertThat(job.getStatus()).isEqualTo(CreationJobStatus.POSTPROCESSING);
        assertThat(json.readScratch(job.getExternalJobsJson())).containsEntry("CUTOUT:EX_01","just-accepted");
        verifyNoInteractions(comfy,gpt,qwen,assets);
    }

    @Test void cutoutStageStartsAFreshRetryBudgetAndSurvivesOneCutsSubmitFailure() {
        // 감정 생성에서 예산을 다 쓴 컷도 누끼 단계는 0부터 — 한 컷의 제출 예외가 잡 전체를 실패시키지 않는다
        job.toReviewWait(72); job.toPostprocessing();
        var map = json.readExpressionAssets(job.getEmotionAssetsJson());
        map.replaceAll((id, s) -> EmotionAssetState.ready("k-" + id).withRetry(props.job().emotionRetries()));
        job.updateEmotionAssets(json.writeExpressionAssets(map));
        when(workflow.buildCutout(anyString(),anyString())).thenReturn(new ObjectMapper().createObjectNode());
        when(assets.download(anyString())).thenReturn(new byte[]{1,2});
        when(assets.download("k-EX_01")).thenThrow(new IllegalStateException("S3 timeout"));
        when(comfy.submit(any(),anyList(),any())).thenReturn(new UgcComfyClient.SubmitResult("cut","IN_QUEUE"));
        worker.runCutoutStage(7L);
        assertThat(job.getStatus()).isEqualTo(CreationJobStatus.POSTPROCESSING);
        assertThat(state().status()).isEqualTo(EmotionAssetState.CUTTING);
        assertThat(state().retryCount()).isEqualTo(1);   // 실패한 컷만 예산 1 사용
        assertThat(json.readScratch(job.getExternalJobsJson())).doesNotContainKey("CUTOUT:EX_01").doesNotContainKey("K_CUT_SUBMITTING_EX_01");
        verify(comfy, times(map.size() - 1)).submit(any(),anyList(),any());
    }

    @Test void ambiguousSubmitFailureIsCountedOnceByTheReservationTakeover() {
        cutting();
        when(assets.download("k-EX_01")).thenReturn(new byte[]{1,2});
        when(workflow.buildCutout(anyString(),anyString())).thenReturn(new ObjectMapper().createObjectNode());
        when(comfy.submit(any(),anyList(),any())).thenThrow(new IllegalStateException("read timed out"));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(worker,"submitCutout",7L,"EX_01","k-EX_01"));
        ReflectionTestUtils.invokeMethod(worker,"noteCutoutSubmitFailure",7L,"EX_01","read timed out");
        assertThat(state().retryCount()).isZero();   // 예약이 남아 있으니 여기선 세지 않는다
        assertThat(json.readScratch(job.getExternalJobsJson())).containsKey("K_CUT_SUBMITTING_EX_01");
    }

    @Test void reservationAgeParsing() {
        long now = 1_000_000;
        assertThat(UgcPipelineWorker.reservationStale((now - UgcPipelineWorker.CUT_RESERVATION_STALE_MS) + ":x", now)).isTrue();
        assertThat(UgcPipelineWorker.reservationStale((now - 1) + ":x", now)).isFalse();
        assertThat(UgcPipelineWorker.reservationStale("garbage", now)).isTrue();
    }
}
