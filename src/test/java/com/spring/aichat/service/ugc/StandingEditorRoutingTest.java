package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.ugc.CharacterCreationJobRepository;
import com.spring.aichat.dto.ugc.BaseCandidate;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.external.FalGptEmotionEditClient;
import com.spring.aichat.external.PoseEditClient;
import com.spring.aichat.external.UgcComfyClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** [2026-11-06] 원화→스탠딩 2패스 편집 모델 라우팅 — 기본 GPT Image 2.5(high), editor=qwen이면 기존 Qwen. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StandingEditorRoutingTest {
    @Mock CharacterCreationJobRepository jobs;
    @Mock CharacterRepository characters;
    @Mock FalGptEmotionEditClient gpt;
    @Mock PoseEditClient qwen;
    @Mock UgcComfyClient comfy;
    @Mock UgcAssetService assets;
    @Mock UgcWorkflowFactory workflow;
    @Mock TransactionTemplate tx;
    @Spy UgcJobJson json = new UgcJobJson(new ObjectMapper());
    @Spy UgcPromptAssembler prompts = new UgcPromptAssembler(new UgcPipelineProperties(null, null, null, null, null, null, null, null));
    @Spy UgcPipelineProperties props = new UgcPipelineProperties(null, null, null, null, null, null, null, null);
    @InjectMocks UgcPipelineWorker worker;

    final FalGptEmotionEditClient.Receipt receipt =
        new FalGptEmotionEditClient.Receipt("r1", "https://queue.fal.run/status/r1", "https://queue.fal.run/result/r1");

    @SuppressWarnings("unchecked")
    CompletableFuture<PoseEditClient.EditResult> edit(String prompt, String url) {
        return (CompletableFuture<PoseEditClient.EditResult>) ReflectionTestUtils.invokeMethod(worker, "standingEdit", prompt, url);
    }

    @Test void defaultsToGptHighWithSamePromptAndNoSeed() throws Exception {
        when(gpt.submit("pose prompt", "https://assets.example/golden.png", "high")).thenReturn(receipt);
        when(gpt.await(receipt)).thenReturn(CompletableFuture.completedFuture(new FalGptEmotionEditClient.Result("https://fal.media/p1.png")));

        PoseEditClient.EditResult result = edit("pose prompt", "https://assets.example/golden.png").get(5, TimeUnit.SECONDS);

        assertThat(result.imageUrl()).isEqualTo("https://fal.media/p1.png");
        assertThat(result.seed()).isNull();
        verify(gpt).submit("pose prompt", "https://assets.example/golden.png", "high");
        verifyNoInteractions(qwen);
    }

    @Test void gptSubmitFailureCompletesExceptionallyForCandidateFailurePath() throws Exception {
        when(gpt.submit(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("fal HTTP 422"));

        assertThatThrownBy(() -> edit("p", "https://assets.example/golden.png").join())
            .isInstanceOf(CompletionException.class).hasRootCauseMessage("fal HTTP 422");
        verify(gpt, never()).await(any());
    }

    @Test void qwenEditorRollsBackToQwenWithNegative() {
        ReflectionTestUtils.setField(worker, "props", new UgcPipelineProperties(null, null, null, null, null, null, null, null,
            new UgcPipelineProperties.Standing("qwen", null)));
        var expected = new PoseEditClient.EditResult("q1", "https://fal.media/q.png", 42L);
        when(qwen.edit(any())).thenReturn(CompletableFuture.completedFuture(expected));

        assertThat(edit("pose prompt", "https://assets.example/golden.png").join()).isEqualTo(expected);
        verify(qwen).edit(new PoseEditClient.EditRequest("pose prompt", prompts.qwenNegative(), "https://assets.example/golden.png", null));
        verifyNoInteractions(gpt);
    }

    /** 생성자 2개(정식+8인자)인 레코드가 Spring 바인딩에서 정식 생성자로 묶이는지 — 실 기동 경로와 같은 Binder. */
    @Test void springBindsStandingThroughCanonicalConstructor() {
        var binder = new org.springframework.boot.context.properties.bind.Binder(
            new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
                "ugc.standing.editor", "qwen", "ugc.standing.gpt-quality", "low", "ugc.stage0-model", "m")));
        var bound = binder.bind("ugc", UgcPipelineProperties.class).get();
        assertThat(bound.standing().usesGpt()).isFalse();
        assertThat(bound.standing().gptQualityOrDefault()).isEqualTo("low");
        assertThat(bound.stage0ModelOrNull()).isEqualTo("m");
        assertThat(bound.qwen().effectiveModel()).isEqualTo("fal-ai/qwen-image-edit-2511");

        var empty = new org.springframework.boot.context.properties.bind.Binder(
            new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of("ugc.stage0-model", "m")));
        assertThat(empty.bind("ugc", UgcPipelineProperties.class).get().standing().usesGpt()).isTrue();
    }

    @Test void invalidStandingSettingsFailAtStartup() {
        assertThatThrownBy(() -> new UgcPipelineProperties.Standing("qwne", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ugc.standing.editor");
        assertThatThrownBy(() -> new UgcPipelineProperties.Standing(null, "medium"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ugc.standing.gpt-quality");
        assertThat(new UgcPipelineProperties.Standing("GPT", " High ").gptQualityOrDefault()).isEqualTo("high");
    }

    /** 제출 422 거절은 재시도 없이 그 후보만 FAILED — 다른 후보는 계속 진행(전원 실패 시에만 잡 실패·환불). */
    @Test void submitRejectionFailsCandidateWithoutRetry() throws Exception {
        var job = baseProcessingJob();
        when(gpt.submit(anyString(), anyString(), anyString()))
            .thenThrow(new FalGptEmotionEditClient.RequestRejected("content_policy_violation"));

        ReflectionTestUtils.invokeMethod(worker, "submitBaseCandidate", 7L, 0);

        BaseCandidate first = awaitCandidate(job, 0, BaseCandidate.FAILED);
        assertThat(first.retryCount()).isZero();
        assertThat(json.readBaseCandidates(job.getBaseCandidatesJson()).get(1).status()).isEqualTo(BaseCandidate.DERIVING);
        verify(gpt, times(1)).submit(anyString(), anyString(), anyString());
        assertThat(job.getStatus()).isEqualTo(com.spring.aichat.domain.ugc.CreationJobStatus.BASE_PROCESSING);
    }

    /** 일시 오류는 기존처럼 후보 재시도 예산을 쓴다. */
    @Test void transientSubmitFailureStillRetries() throws Exception {
        var job = baseProcessingJob();
        when(gpt.submit(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("fal HTTP 503"));

        ReflectionTestUtils.invokeMethod(worker, "submitBaseCandidate", 7L, 0);

        awaitCandidate(job, 0, BaseCandidate.FAILED);
        // 최초 1 + 재시도 emotion-max-retries(기본 3)
        verify(gpt, times(4)).submit(anyString(), anyString(), anyString());
    }

    com.spring.aichat.domain.ugc.CharacterCreationJob baseProcessingJob() {
        when(tx.execute(any())).thenAnswer(i -> ((org.springframework.transaction.support.TransactionCallback<?>) i.getArgument(0))
            .doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus()));
        var job = com.spring.aichat.domain.ugc.CharacterCreationJob.start(1L, "테스트", "테스트",
            com.spring.aichat.domain.user.EnergySplit.of(0, 0));
        ReflectionTestUtils.setField(job, "id", 7L);
        job.applyStage0(json.writeConcept(new StructuredConcept(java.util.List.of("blonde hair"), java.util.List.of(),
            java.util.List.of(), java.util.List.of(), "light gray", null, null, null, null)), "light gray");
        job.toBaseProcessing("golden.png");
        job.updateBaseCandidates(json.writeBaseCandidates(java.util.List.of(BaseCandidate.deriving(0), BaseCandidate.deriving(0))));
        when(jobs.findById(7L)).thenReturn(java.util.Optional.of(job));
        when(jobs.findByIdForUpdate(7L)).thenReturn(java.util.Optional.of(job));
        when(assets.presignGet(eq("golden.png"), any())).thenReturn("https://assets.example/golden.png");
        return job;
    }

    BaseCandidate awaitCandidate(com.spring.aichat.domain.ugc.CharacterCreationJob job, int index, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            BaseCandidate c = json.readBaseCandidates(job.getBaseCandidatesJson()).get(index);
            if (status.equals(c.status())) return c;
            Thread.sleep(20);
        }
        throw new AssertionError("후보 " + index + "가 " + status + "에 도달하지 않음: " + job.getBaseCandidatesJson());
    }

    @Test void standingSettingDefaults() {
        var defaults = new UgcPipelineProperties(null, null, null, null, null, null, null, null).standing();
        assertThat(defaults.usesGpt()).isTrue();
        assertThat(defaults.gptQualityOrDefault()).isEqualTo("high");
        assertThat(new UgcPipelineProperties.Standing(" QWEN ", null).usesGpt()).isFalse();
        assertThat(new UgcPipelineProperties.Standing("gpt", "low").gptQualityOrDefault()).isEqualTo("low");
    }
}
