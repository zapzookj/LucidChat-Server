package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.ugc.CharacterCreationJob;
import com.spring.aichat.domain.ugc.CharacterCreationJobRepository;
import com.spring.aichat.domain.user.EnergySplit;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.ugc.IllustrationPrompt;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.dto.ugc.UgcDtos;
import com.spring.aichat.service.admin.AdminUgcReviewService;
import com.spring.aichat.service.cache.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IllustrationPromptPersistenceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final UgcJobJson json=new UgcJobJson(mapper);
    final UgcPipelineProperties props=new UgcPipelineProperties(null,null,null,null,null,null,null,null);
    final IllustrationPrompt latestPrompt=new IllustrationPrompt("masterpiece, anime style, silver hair","bad quality");
    StructuredConcept legacy,latest;
    CharacterCreationJobRepository jobs;
    CharacterCreationJob job;
    UserRepository users;
    UgcPipelineWorker worker;
    TransactionTemplate tx;
    CharacterCreationService service;
    @BeforeEach void setup() throws Exception {
        legacy=mapper.readValue(ConceptIllustrationSplitTest.PROFILE,StructuredConcept.class).withIllustrationPrompt(null);
        latest=json.readConcept(json.writeConcept(legacy).replace("사용자 성격 편집","최신 편집 성격"))
            .withIllustrationPrompt(latestPrompt);
        jobs=mock(CharacterCreationJobRepository.class);users=mock(UserRepository.class);worker=mock(UgcPipelineWorker.class);
        tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>)i.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        doAnswer(i -> { ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)i.getArgument(0))
            .accept(new SimpleTransactionStatus()); return null; }).when(tx).executeWithoutResult(any());
        User user=mock(User.class);when(user.getId()).thenReturn(1L);when(users.findByUsername("owner")).thenReturn(Optional.of(user));
        job=CharacterCreationJob.start(1L,"이름","컨셉",EnergySplit.of(0,0));ReflectionTestUtils.setField(job,"id",7L);
        job.applyStage0(json.writeConcept(latest),"light gray");
        when(jobs.findByIdForUpdate(7L)).thenReturn(Optional.of(job));
        service=new CharacterCreationService(jobs,users,null,props,mock(UgcModerationService.class),worker,json,
            mock(RedisCacheService.class),tx,null);
    }
    @Test void roundTripAndEmotionCopyPreserveCompletePairWhileLegacyStaysAbsent() {
        assertThat(json.readConcept(json.writeConcept(latest)).illustrationPrompt()).isEqualTo(latestPrompt);
        assertThat(latest.withEmotionPrompts(Map.of()).illustrationPrompt()).isEqualTo(latestPrompt);
        assertThat(legacy.illustrationPrompt()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"positive_prompt\":\"p\"}","{\"negative_prompt\":\"n\"}","{\"positive_prompt\":\"\",\"negative_prompt\":\"n\"}"})
    void incompleteStoredPairCannotFallBackSilently(String pair) throws Exception {
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(legacy);
        tree.set("illustration_prompt",mapper.readTree(pair));
        assertThatThrownBy(() -> mapper.treeToValue(tree,StructuredConcept.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @Test void profileDraftEditPreservesArtworkPair() throws Exception {
        service.updateProfileDraft("owner",7L,mapper.readValue("{\"personality\":\"편집한 성격\"}",UgcDtos.UpdateProfileRequest.class));
        var saved=json.readConcept(job.getStructuredConceptJson());
        assertThat(saved.character().personality()).isEqualTo("편집한 성격");
        assertThat(saved.illustrationPrompt()).isEqualTo(latestPrompt);
    }
    @Test void selectingOldBatchRestoresItsEmptyNegativeAndKeepsNewProfileEdits() {
        var oldPrompt=new IllustrationPrompt("anime style, blue hair","");
        var old=legacy.withIllustrationPrompt(oldPrompt);
        selectEarlierBatch(old,latest);
        assertThat(json.readConcept(job.getStructuredConceptJson()).illustrationPrompt()).isEqualTo(oldPrompt);
        verify(worker).runBaseStage(7L);
    }
    @Test void selectingLegacyBatchClearsModernPairInsteadOfMixingPrompts() {
        selectEarlierBatch(legacy,latest);
        assertThat(json.readConcept(job.getStructuredConceptJson()).illustrationPrompt()).isNull();
    }
    @Test void selectingModernBatchFromLegacyRestoresBothPromptFields() {
        selectEarlierBatch(latest,legacy);
        assertThat(json.readConcept(job.getStructuredConceptJson()).illustrationPrompt()).isEqualTo(latestPrompt);
    }
    void selectEarlierBatch(StructuredConcept earlier,StructuredConcept current) {
        job.applyStage0(json.writeConcept(current),"light gray");
        job.updateExternalJobs(json.writeScratch(Map.of(UgcPipelineWorker.GOLDEN_SNAPSHOTS_KEY,
            json.writeGoldenSnapshots(List.of(new UgcJobJson.GoldenSnapshot(0,json.writeConcept(earlier)),
                new UgcJobJson.GoldenSnapshot(2,json.writeConcept(current)))))));
        job.toGachaWait(json.writeKeys(List.of("old0.png","old1.png","new0.png","new1.png")),72);
        service.selectGoldenShot("owner",7L,0);
        assertThat(json.readConcept(job.getStructuredConceptJson()).character().personality())
            .isEqualTo(current.character().personality());
    }
    @Test void workflowAndAdminUseStoredPairForBothGendersAndLeaveRefineNegativeAlone() {
        var factory=new UgcWorkflowFactory(mapper,props);factory.loadTemplates();
        var assembler=new UgcPromptAssembler(props);
        for (boolean male:List.of(false,true)) {
            var wf=factory.buildGoldenShot(assembler.goldenShotPositive(latest,male),assembler.goldenShotNegative(latest,male),"test",male);
            assertThat(wf.path("12").path("inputs").path("text").asText()).isEqualTo(latestPrompt.positivePrompt());
            assertThat(wf.path("13").path("inputs").path("text").asText()).isEqualTo(latestPrompt.negativePrompt());
            assertThat(factory.templateNegative(male)).isNotEqualTo(latestPrompt.negativePrompt());
        }
        var character=mock(com.spring.aichat.domain.character.Character.class);
        when(character.isUgc()).thenReturn(true);when(character.getId()).thenReturn(77L);
        var characters=mock(CharacterRepository.class);when(characters.findById(77L)).thenReturn(Optional.of(character));
        when(jobs.findByCharacterId(77L)).thenReturn(Optional.of(job));
        var admin=new AdminUgcReviewService(characters,null,null,null,null,null,null,jobs,json,assembler,factory);
        var inspection=admin.prompts(77L);
        assertThat(inspection.goldenShotPositive()).isEqualTo(latestPrompt.positivePrompt());
        assertThat(inspection.goldenShotNegative()).isEqualTo(latestPrompt.negativePrompt());
        assertThat(inspection.negative()).isEqualTo(factory.templateNegative(false));
    }
}
