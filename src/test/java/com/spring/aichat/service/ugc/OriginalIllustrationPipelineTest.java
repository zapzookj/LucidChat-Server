package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.ugc.*;
import com.spring.aichat.domain.user.*;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.exception.ExternalApiException;
import com.spring.aichat.external.UgcComfyClient;
import com.spring.aichat.service.cache.RedisCacheService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OriginalIllustrationPipelineTest {
    @Mock CharacterCreationJobRepository jobs;
    @Mock ConceptStructuringService concepts;
    @Mock UgcModerationService moderation;
    @Mock UgcWorkflowFactory workflow;
    @Mock UgcComfyClient comfy;
    @Mock UserRepository users;
    @Mock RedisCacheService cache;
    @Mock TransactionTemplate tx;
    @Spy UgcJobJson json = new UgcJobJson(new ObjectMapper());
    @Spy UgcPipelineProperties props = new UgcPipelineProperties(null,null,null,null,null,null,null,null);
    @Spy UgcPromptAssembler prompts = new UgcPromptAssembler(props);
    @InjectMocks UgcPipelineWorker worker;
    CharacterCreationJob job;
    StructuredConcept concept;
    com.spring.aichat.dto.ugc.IllustrationPrompt prompt;

    @BeforeEach void setup() throws Exception {
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>)i.getArgument(0))
            .doInTransaction(new SimpleTransactionStatus()));
        doAnswer(i -> { ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)
            i.getArgument(0)).accept(new SimpleTransactionStatus()); return null; })
            .when(tx).executeWithoutResult(any());
        job = CharacterCreationJob.start(1L,"지정 이름","원래 컨셉",EnergySplit.of(6,2));
        ReflectionTestUtils.setField(job,"id",7L);
        when(jobs.findById(7L)).thenReturn(Optional.of(job));
        when(jobs.findByIdForUpdate(7L)).thenReturn(Optional.of(job));
        concept = new ObjectMapper().readValue(ConceptIllustrationSplitTest.PROFILE,StructuredConcept.class);
        prompt = new com.spring.aichat.dto.ugc.IllustrationPrompt("masterpiece, anime style, silver hair", "bad quality, rim lighting");
        concept = concept.withIllustrationPrompt(prompt);
        when(concepts.generateIllustrationPrompt(anyString())).thenReturn(prompt);
        when(concepts.structure(anyString(),any(),eq(prompt))).thenReturn(concept);
        when(workflow.buildGoldenShot(anyString(),anyString(),anyString(),anyBoolean()))
            .thenReturn(new ObjectMapper().createObjectNode());
        when(comfy.submit(any(),isNull(),isNull())).thenReturn(new UgcComfyClient.SubmitResult("accepted","IN_QUEUE"));
    }
    @Test void successfulPromptsAreReusedAfterProfileRetryAndModerationPrecedesGpu() {
        when(concepts.structure(anyString(),any(),eq(prompt)))
            .thenThrow(new ExternalApiException("temporary")).thenReturn(concept);
        worker.runStage0(7L);
        verify(concepts,times(1)).generateIllustrationPrompt("원래 컨셉\n\n[캐릭터 성별]: 여성");
        verify(concepts,times(2)).structure(anyString(),eq("지정 이름"),eq(prompt));
        var order = inOrder(moderation,comfy);
        order.verify(moderation).assertStructuredConceptAllowed(concept,"원래 컨셉",1L);
        order.verify(comfy).submit(any(),isNull(),isNull());
        verify(workflow).buildGoldenShot(prompt.positivePrompt(),prompt.negativePrompt(),"job_7_golden",false);
        assertThat(json.readScratch(job.getExternalJobsJson())).containsEntry("GOLDEN","accepted");
        assertThat(json.readGoldenSnapshots(json.readScratch(job.getExternalJobsJson())
            .get(UgcPipelineWorker.GOLDEN_SNAPSHOTS_KEY))).hasSize(1);
    }
    @Test void ambiguousGpuPostDoesNotRepeatLlmOrGpuAndRefundKeepsPaidSplit() {
        User user = mock(User.class);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(comfy.submit(any(),isNull(),isNull())).thenThrow(new ExternalApiException("ambiguous response"));
        worker.runStage0(7L);
        worker.failAndRefund(7L,"duplicate failure callback");
        verify(concepts,times(1)).generateIllustrationPrompt(anyString());
        verify(concepts,times(1)).structure(anyString(),any(),eq(prompt));
        verify(comfy,times(1)).submit(any(),isNull(),isNull());
        verify(user,times(1)).refundEnergy(EnergySplit.of(6,2));
        assertThat(job.getStatus()).isEqualTo(CreationJobStatus.FAILED);
    }
    @Test void blockedConceptNeverReachesGpu() {
        doThrow(new com.spring.aichat.exception.ContentModerationException("blocked","MINOR",2))
            .when(moderation).assertStructuredConceptAllowed(any(),anyString(),anyLong());
        worker.runStage0(7L);
        verifyNoInteractions(comfy);
        assertThat(job.getStatus()).isEqualTo(CreationJobStatus.FAILED);
    }
    @Test void terminalJobDuringProfileGenerationCannotSubmitGpu() {
        when(concepts.structure(anyString(),any(),eq(prompt))).thenAnswer(i -> { job.fail("cancelled"); return concept; });
        worker.runStage0(7L);
        verifyNoInteractions(comfy,moderation);
    }
    @Test void ordinaryDesignRerollUsesCachedImageTagsAndPreservesConcurrentProfile() throws Exception {
        job.applyStage0(json.writeConcept(concept),concept.bgColor());
        job.updateExternalJobs(json.writeScratch(Map.of(UgcPipelineWorker.APPEARANCE_EDIT_KEY,"새 디자인")));
        var revised = new com.spring.aichat.dto.ugc.IllustrationPrompt("anime style, blue hair, wink, garden", "");
        var updated = new StructuredConcept(List.of("blue hair"),List.of("wink"),concept.moodTags(),
            List.of("garden"),"muted teal",concept.character(),concept.moderation(),null,null).withIllustrationPrompt(revised);
        var edited = new ObjectMapper().readValue(ConceptIllustrationSplitTest.PROFILE
            .replace("사용자 성격 편집","생성 중에 편집한 성격"),StructuredConcept.class);
        when(concepts.generateAppearancePrompt(anyString(),any(),eq("새 디자인"))).thenReturn(revised);
        when(concepts.restructureAppearance(anyString(),any(),eq("새 디자인"),eq(revised)))
            .thenThrow(new ExternalApiException("temporary")).thenAnswer(i -> {
                job.applyStage0(json.writeConcept(edited),edited.bgColor()); return updated;
            });
        worker.runGoldenReroll(7L);
        verify(concepts,times(1)).generateAppearancePrompt(anyString(),any(),eq("새 디자인"));
        verify(concepts,times(2)).restructureAppearance(anyString(),any(),eq("새 디자인"),eq(revised));
        verify(comfy,times(1)).submit(any(),isNull(),isNull());
        StructuredConcept persisted = json.readConcept(job.getStructuredConceptJson());
        assertThat(persisted.appearanceTags()).containsExactly("blue hair");
        assertThat(persisted.personaTags()).containsExactly("wink");
        assertThat(persisted.sceneTags()).containsExactly("garden");
        assertThat(persisted.character().personality()).isEqualTo("생성 중에 편집한 성격");
        assertThat(json.readScratch(job.getExternalJobsJson())).doesNotContainKey(UgcPipelineWorker.APPEARANCE_EDIT_KEY);
        var snapshot = json.readGoldenSnapshots(json.readScratch(job.getExternalJobsJson())
            .get(UgcPipelineWorker.GOLDEN_SNAPSHOTS_KEY)).get(0);
        assertThat(json.readConcept(snapshot.conceptJson()).personaTags()).containsExactly("wink");
        assertThat(persisted.illustrationPrompt()).isEqualTo(revised);
        assertThat(json.readConcept(snapshot.conceptJson()).illustrationPrompt()).isEqualTo(revised);
        verify(moderation).assertStructuredConceptAllowed(eq(updated),contains("새 디자인"),eq(1L));
    }

    @Test void cancellationAfterImageTagsStopsBeforeProfile() {
        when(concepts.generateIllustrationPrompt(anyString())).thenAnswer(i -> { job.fail("cancelled"); return prompt; });
        worker.runStage0(7L);
        verify(concepts,never()).structure(anyString(),any(),any());
        verifyNoInteractions(comfy);
    }
    @Test void cancellationDuringFailedProfileStopsBeforeRetry() {
        when(concepts.structure(anyString(),any(),eq(prompt))).thenAnswer(i -> {
            job.fail("cancelled"); throw new ExternalApiException("cancelled response");
        });
        worker.runStage0(7L);
        verify(concepts,times(1)).structure(anyString(),any(),eq(prompt));
        verifyNoInteractions(comfy);
    }
    @Test void acceptedJobReentryUsesExistingReceiptWithoutMorePaidRequests() {
        worker.runStage0(7L);
        worker.runStage0(7L);
        worker.runGoldenReroll(7L);
        verify(concepts,times(1)).generateIllustrationPrompt(anyString());
        verify(concepts,times(1)).structure(anyString(),any(),eq(prompt));
        verify(comfy,times(1)).submit(any(),isNull(),isNull());
        assertThat(json.readScratch(job.getExternalJobsJson())).containsEntry("GOLDEN","accepted")
            .doesNotContainKey(UgcPipelineWorker.GOLDEN_SUBMITTING_KEY);
    }
    @Test void reentryDuringPaidPostHasOneSubmissionAndOneSnapshot() {
        when(comfy.submit(any(),isNull(),isNull())).thenAnswer(i -> {
            worker.runStage0(7L); worker.runGoldenReroll(7L);
            return new UgcComfyClient.SubmitResult("accepted","IN_QUEUE");
        });
        worker.runStage0(7L);
        verify(comfy,times(1)).submit(any(),isNull(),isNull());
        verify(concepts,times(1)).generateIllustrationPrompt(anyString());
        assertThat(json.readGoldenSnapshots(json.readScratch(job.getExternalJobsJson())
            .get(UgcPipelineWorker.GOLDEN_SNAPSHOTS_KEY))).hasSize(1);
    }
    @Test void persistedAmbiguousReservationRefundsWithoutAutomaticResubmission() {
        User user = mock(User.class);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        job.updateExternalJobs(json.writeScratch(Map.of(UgcPipelineWorker.GOLDEN_SUBMITTING_KEY,"old-attempt")));
        worker.runStage0(7L); worker.runGoldenReroll(7L);
        verifyNoInteractions(concepts,comfy);
        verify(user,times(1)).refundEnergy(EnergySplit.of(6,2));
        assertThat(job.getStatus()).isEqualTo(CreationJobStatus.FAILED);
    }
    @Test void cancelledRerollAfterTagsStopsBeforeMetadata() {
        job.applyStage0(json.writeConcept(concept),concept.bgColor());
        job.updateExternalJobs(json.writeScratch(Map.of(UgcPipelineWorker.APPEARANCE_EDIT_KEY,"부분 변경")));
        when(concepts.generateAppearancePrompt(anyString(),any(),anyString()))
            .thenAnswer(i -> { job.fail("cancelled"); return prompt; });
        worker.runGoldenReroll(7L);
        verify(concepts,never()).restructureAppearance(anyString(),any(),anyString(),any());
        verifyNoInteractions(comfy);
    }
    @Test void cancelledRerollDuringFailedMetadataStopsBeforeRetry() {
        job.applyStage0(json.writeConcept(concept),concept.bgColor());
        job.updateExternalJobs(json.writeScratch(Map.of(UgcPipelineWorker.APPEARANCE_EDIT_KEY,"부분 변경")));
        when(concepts.generateAppearancePrompt(anyString(),any(),anyString())).thenReturn(prompt);
        when(concepts.restructureAppearance(anyString(),any(),anyString(),eq(prompt))).thenAnswer(i -> {
            job.fail("cancelled"); throw new ExternalApiException("cancelled response");
        });
        worker.runGoldenReroll(7L);
        verify(concepts,times(1)).restructureAppearance(anyString(),any(),anyString(),eq(prompt));
        verifyNoInteractions(comfy);
    }
    @Test void cancelledRerollDuringSuccessfulMetadataStopsBeforeModerationConfirmation() {
        job.applyStage0(json.writeConcept(concept),concept.bgColor());
        job.updateExternalJobs(json.writeScratch(Map.of(UgcPipelineWorker.APPEARANCE_EDIT_KEY,"부분 변경")));
        when(concepts.generateAppearancePrompt(anyString(),any(),anyString())).thenReturn(prompt);
        when(concepts.restructureAppearance(anyString(),any(),anyString(),eq(prompt))).thenAnswer(i -> {
            job.fail("cancelled"); return concept;
        });
        worker.runGoldenReroll(7L);
        verifyNoInteractions(comfy,moderation);
    }
    @Test void normalUserRerollAfterReceiptSettlementStillSubmitsNewBatch() {
        worker.runStage0(7L);
        var scratch = json.readScratch(job.getExternalJobsJson());
        scratch.remove("GOLDEN");
        job.updateExternalJobs(json.writeScratch(scratch));
        job.toGachaWait("[\"first.png\"]",72);
        job.restartGoldenGeneration();
        worker.runGoldenReroll(7L);
        verify(comfy,times(2)).submit(any(),isNull(),isNull());
        assertThat(json.readGoldenSnapshots(json.readScratch(job.getExternalJobsJson())
            .get(UgcPipelineWorker.GOLDEN_SNAPSHOTS_KEY))).hasSize(2);
    }
}
