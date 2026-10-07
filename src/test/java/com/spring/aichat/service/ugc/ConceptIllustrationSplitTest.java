package com.spring.aichat.service.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.config.UgcPipelineProperties;
import com.spring.aichat.dto.ugc.StructuredConcept;
import com.spring.aichat.exception.ExternalApiException;
import com.spring.aichat.external.OpenRouterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConceptIllustrationSplitTest {
    final ObjectMapper mapper = new ObjectMapper();
    OpenRouterClient llm;
    ConceptStructuringService service;
    static final String PROMPT = """
        {"positive_prompt":"masterpiece, best quality, anime style, silver hair, delicate features, smile, sunset",
         "negative_prompt":"bad quality, blue hair, rim lighting"}
        """;
    static final String PROFILE = """
        {"appearance_tags":["silver hair","delicate features"],"persona_tags":["smile"],"scene_tags":["sunset"],
         "mood_tags":["다정한"],"bg_color":"invalid",
         "character":{"name":"모델 작명","age":24,"appearance":"은발","clothing":"드레스",
          "personality":"사용자 성격 편집","backstory":"사용자 서사 편집","first_greeting":"안녕하세요."},
         "moderation":{"minor_signal":false,"reason":""},
         "illustration_prompt":{"positive_prompt":"ignored","negative_prompt":"ignored"}}
        """;
    @BeforeEach void setup() {
        llm = mock(OpenRouterClient.class);
        service = new ConceptStructuringService(llm,
            new OpenAiProperties(null,null,"fallback",null,null,null,null),
            new UgcPipelineProperties(null,null,null,null,null,null,"approved-model",null),mapper);
    }
    @Test void requestsWholePromptThenExtractsMetadataWithoutOverwritingArtwork() throws Exception {
        when(llm.completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble())).thenReturn(PROMPT,PROFILE);
        StructuredConcept result = service.structure("은발 성인 마법사","지정 이름");
        var order = inOrder(llm);
        order.verify(llm).completeJson("approved-model",ConceptStructuringService.IMAGE_PROMPT_SYSTEM,
            "[컨셉 서술]:\n은발 성인 마법사",8192,0.7);
        assertThat(ConceptStructuringService.IMAGE_PROMPT_SYSTEM).contains("그림체, 품질, 인물의 매력", "positive_prompt","negative_prompt")
            .doesNotContain("appearance_tags","persona_tags","scene_tags","Danbooru","masterpiece");
        order.verify(llm).completeJson(eq("approved-model"),argThat(s -> s.contains("positive_prompt")
            && s.contains("negative_prompt의 배제 대상은 추출하지 않는다")),
            argThat(s -> s.contains("지정 이름") && s.contains("[확정된 원화 프롬프트")
                && s.contains("masterpiece") && s.contains("rim lighting")),eq(8192),eq(0.7));
        order.verifyNoMoreInteractions();
        assertThat(result.illustrationPrompt().positivePrompt()).isEqualTo(mapper.readTree(PROMPT).path("positive_prompt").asText());
        assertThat(result.illustrationPrompt().negativePrompt()).isEqualTo("bad quality, blue hair, rim lighting");
        assertThat(result.appearanceTags()).containsExactly("silver hair","delicate features");
        assertThat(result.character().name()).isEqualTo("지정 이름");
        assertThat(result.bgColor()).isEqualTo("light gray");
        assertThat(result.appearanceTags()).doesNotContain("blue hair");
    }
    @ParameterizedTest @ValueSource(strings={"{}",
        "{\"positive_prompt\":\"p\"}","{\"negative_prompt\":\"n\"}",
        "{\"positive_prompt\":\" \",\"negative_prompt\":\"n\"}",
        "{\"positive_prompt\":[\"p\"],\"negative_prompt\":\"n\"}",
        "{\"positive_prompt\":12,\"negative_prompt\":\"n\"}",
        "{\"positive_prompt\":\"p\",\"negative_prompt\":null}",
        "{\"positive_prompt\":\"p\",\"negative_prompt\":[\"n\"]}"
    }) void invalidArtworkStopsBeforeProfile(String response) {
        when(llm.completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble())).thenReturn(response);
        assertThatThrownBy(() -> service.structure("컨셉",null)).isInstanceOf(ExternalApiException.class);
        verify(llm,times(1)).completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble());
    }
    @Test void emptyNegativeAndFormattingArePreservedAsModelChoices() {
        when(llm.completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble()))
            .thenReturn("{\"positive_prompt\":\" (anime style:1.2), smile, smile \",\"negative_prompt\":\"\"}");
        var result=service.generateIllustrationPrompt("컨셉");
        assertThat(result.positivePrompt()).isEqualTo(" (anime style:1.2), smile, smile ");
        assertThat(result.negativePrompt()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"age","moderation","appearance","clothing"})
    void missingRequiredProfileIsRejected(String field) throws Exception {
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(PROFILE);
        if (field.equals("moderation")) tree.remove(field);
        else ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("character")).remove(field);
        String profile=tree.toString();
        when(llm.completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble())).thenReturn(PROMPT,profile);
        assertThatThrownBy(() -> service.structure("컨셉",null)).isInstanceOf(ExternalApiException.class);
    }
    @Test void malformedExtractedTagsAreRejectedEvenWithValidProfile() {
        when(llm.completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble()))
            .thenReturn(PROMPT,PROFILE.replace("[\"silver hair\",\"delicate features\"]","[null]"));
        assertThatThrownBy(() -> service.structure("컨셉",null)).isInstanceOf(ExternalApiException.class);
    }
    @Test void rerollUsesCompletePromptAndOnlyUpdatesAppearanceMetadata() throws Exception {
        StructuredConcept current=mapper.readValue(PROFILE,StructuredConcept.class);
        when(llm.completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble())).thenReturn(PROMPT,"""
            {"appearance_tags":["silver hair"],"persona_tags":["smile"],"scene_tags":["sunset"],
             "bg_color":"muted teal","appearance":"새 은발","clothing":"새 드레스",
             "moderation":{"minor_signal":false},
             "positive_prompt":"ignored","negative_prompt":"ignored"}
            """);
        StructuredConcept result=service.restructureAppearance("원래 컨셉",current,"디자인을 바꿔줘");
        verify(llm).completeJson(eq("approved-model"),eq(ConceptStructuringService.IMAGE_PROMPT_SYSTEM),
            argThat(s -> s.contains("원래 컨셉") && s.contains("디자인을 바꿔줘")),eq(8192),eq(0.7));
        assertThat(result.illustrationPrompt().positivePrompt()).startsWith("masterpiece");
        assertThat(result.illustrationPrompt().negativePrompt()).isEqualTo("bad quality, blue hair, rim lighting");
        assertThat(result.personaTags()).containsExactly("smile");
        assertThat(result.character().personality()).isEqualTo(current.character().personality());
        assertThat(result.character().backstory()).isEqualTo(current.character().backstory());
        assertThat(result.character().appearance()).isEqualTo("새 은발");
    }
}
