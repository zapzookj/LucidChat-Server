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
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConceptIllustrationSplitTest {
    final ObjectMapper mapper = new ObjectMapper();
    OpenRouterClient llm;
    ConceptStructuringService service;
    static final String TAGS = """
        {"appearance_tags":["1girl","silver hair","silver hair"],
         "persona_tags":["smile"],"scene_tags":["sunset"]}
        """;
    static final String PROFILE = """
        {"appearance_tags":["red hair"],"persona_tags":["crying"],"scene_tags":["neon"],
         "mood_tags":["다정한"],"bg_color":"invalid",
         "character":{"name":"모델 작명","age":24,"appearance":"은발","clothing":"드레스",
          "personality":"사용자 성격 편집","backstory":"사용자 서사 편집","first_greeting":"안녕하세요."},
         "moderation":{"minor_signal":false,"reason":""}}
        """;
    @BeforeEach void setup() {
        llm = mock(OpenRouterClient.class);
        service = new ConceptStructuringService(llm,
            new OpenAiProperties(null,null,"fallback",null,null,null,null),
            new UgcPipelineProperties(null,null,null,null,null,null,"approved-model",null), mapper);
    }
    @Test void artworkRequestIsIsolatedAndProfileCannotReplaceItsTags() {
        when(llm.completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble()))
            .thenReturn(TAGS, PROFILE);
        StructuredConcept result = service.structure("은발 성인 마법사", "지정 이름");
        var order = inOrder(llm);
        order.verify(llm).completeJson("approved-model",ConceptStructuringService.IMAGE_TAG_SYSTEM_PROMPT,
            "[컨셉 서술]:\n은발 성인 마법사",8192,0.7);
        order.verify(llm).completeJson(eq("approved-model"),argThat(s -> s.contains("캐릭터 프로필 구조화")
            && !s.contains("40~60") && !s.contains("10~20")),
            argThat(s -> s.contains("지정 이름") && s.contains("[확정된 원화 태그")
                && s.contains("silver hair") && s.contains("sunset")),eq(8192),eq(0.7));
        order.verifyNoMoreInteractions();
        assertThat(result.appearanceTags()).containsExactly("1girl","silver hair","silver hair");
        assertThat(result.personaTags()).containsExactly("smile");
        assertThat(result.sceneTags()).containsExactly("sunset");
        assertThat(result.character().name()).isEqualTo("지정 이름");
        assertThat(result.character().appearance()).isEqualTo("은발");
        assertThat(result.moodTags()).containsExactly("다정한");
        assertThat(result.bgColor()).isEqualTo("light gray");
    }
    @ParameterizedTest @ValueSource(strings={
        "{}", "{\"appearance_tags\":[],\"persona_tags\":[],\"scene_tags\":[]}",
        "{\"appearance_tags\":[\"hair\"],\"scene_tags\":[]}",
        "{\"appearance_tags\":[\"hair\"],\"persona_tags\":[]}",
        "{\"appearance_tags\":[null],\"persona_tags\":[],\"scene_tags\":[]}",
        "{\"appearance_tags\":[1],\"persona_tags\":[],\"scene_tags\":[]}",
        "{\"appearance_tags\":[\" \"],\"persona_tags\":[],\"scene_tags\":[]}"
    }) void invalidArtworkStopsBeforeProfile(String response) {
        when(llm.completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble())).thenReturn(response);
        assertThatThrownBy(() -> service.structure("컨셉",null)).isInstanceOf(ExternalApiException.class);
        verify(llm,times(1)).completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble());
    }
    @ParameterizedTest @ValueSource(strings={
        "{\"character\":{\"name\":\"이름\"},\"moderation\":{\"minor_signal\":false}}",
        "{\"character\":{\"name\":\"이름\",\"age\":24}}",
        "{\"character\":{\"name\":\"이름\",\"age\":24},\"moderation\":{\"minor_signal\":false}}",
        "{\"character\":{\"name\":\"이름\",\"age\":24,\"appearance\":\"은발\",\"clothing\":\" \"},\"moderation\":{\"minor_signal\":false}}"
    }) void profileRequiresAgeAndModeration(String profile) {
        when(llm.completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble())).thenReturn(TAGS, profile);
        assertThatThrownBy(() -> service.structure("컨셉",null)).isInstanceOf(ExternalApiException.class);
    }
    @Test void rerollReusesImageInstructionAndPreservesProfileEdits() throws Exception {
        StructuredConcept current = mapper.readValue(PROFILE,StructuredConcept.class);
        when(llm.completeJson(anyString(),anyString(),anyString(),anyInt(),anyDouble())).thenReturn(TAGS,"""
            {"appearance_tags":["ignored"],"bg_color":"muted teal","appearance":"새 은발",
             "clothing":"새 드레스","moderation":{"minor_signal":false}}
            """);
        StructuredConcept result = service.restructureAppearance("원래 컨셉",current,"디자인을 바꿔줘");
        verify(llm).completeJson(eq("approved-model"),eq(ConceptStructuringService.IMAGE_TAG_SYSTEM_PROMPT),
            argThat(s -> s.contains("원래 컨셉") && s.contains("디자인을 바꿔줘")),eq(8192),eq(0.7));
        assertThat(result.appearanceTags()).containsExactly("1girl","silver hair","silver hair");
        assertThat(result.personaTags()).containsExactly("smile");
        assertThat(result.sceneTags()).containsExactly("sunset");
        assertThat(result.character().personality()).isEqualTo(current.character().personality());
        assertThat(result.character().backstory()).isEqualTo(current.character().backstory());
        assertThat(result.character().appearance()).isEqualTo("새 은발");
        assertThat(current.withAppearanceFrom(result).moodTags()).isEqualTo(current.moodTags());
        assertThat(current.withAppearanceFrom(result).personaTags()).containsExactly("smile");
        var legacy = new StructuredConcept(result.appearanceTags(),null,null,result.sceneTags(),
            result.bgColor(),result.character(),result.moderation(),null,null);
        assertThat(current.withAppearanceFrom(legacy).personaTags()).isEqualTo(current.personaTags());
    }
}
