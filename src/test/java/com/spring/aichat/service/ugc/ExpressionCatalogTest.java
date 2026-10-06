package com.spring.aichat.service.ugc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.dto.chat.*;
import com.spring.aichat.dto.ugc.CharacterExpression;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpressionCatalogTest {
    @Test void catalogRoundTripPreservesAllIdsAndDuplicateSemanticEmotions() {
        var entries = DynamicExpressionPipelineTest.catalog();
        String raw = ExpressionCatalog.write(entries);
        assertThat(ExpressionCatalog.read(raw)).containsExactlyElementsOf(entries);
        assertThat(ExpressionCatalog.expectedIds(raw)).hasSize(9);
        assertThat(ExpressionCatalog.select(raw, "EX_03", EmotionTag.SAD)).isEqualTo("EX_03");
        assertThat(ExpressionCatalog.select(raw, "FOREIGN", EmotionTag.JOY)).isEqualTo("NEUTRAL");
        assertThat(ExpressionCatalog.select(raw, null, EmotionTag.JOY)).isEqualTo("EX_01");
    }
    @Test void duplicateIdsAndInvalidCountAreRejected() {
        var entries = new ArrayList<>(DynamicExpressionPipelineTest.catalog());
        entries.set(2, entries.get(1));
        assertThatThrownBy(() -> ExpressionCatalog.write(entries)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpressionCatalog.write(List.of(CharacterExpression.neutral()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void onlyTrustedSpeakerAssetsAreResolvedAndUnknownIdUsesOwnNeutral() {
        Character c = mock(Character.class);
        when(c.getExpressionCatalogJson()).thenReturn(ExpressionCatalog.write(DynamicExpressionPipelineTest.catalog()));
        when(c.getDefaultImageUrl()).thenReturn("https://assets.lucid-chat.com/characters/ugc-7/default_neutral.png");
        when(c.getSlug()).thenReturn("ugc-7");
        when(c.getName()).thenReturn("연");
        assertThat(ExpressionResolver.matches(c," ugc-7 ")).isTrue();
        assertThat(ExpressionResolver.matches(c,"NPC")).isFalse();
        assertThat(ExpressionResolver.resolve(c,"EX_04",EmotionTag.JOY).imageUrl()).endsWith("/ugc-7/default_ex_04.png");
        assertThat(ExpressionResolver.resolve(c,"FOREIGN",EmotionTag.ANGRY).imageUrl()).endsWith("/ugc-7/default_neutral.png");
        assertThat(ExpressionResolver.resolve(null,"EX_04",EmotionTag.JOY).imageUrl()).isNull();
    }
    @Test void optionalInputAndResponseFieldsSurviveJsonAndLegacyScenesRemainReadable() throws Exception {
        var mapper = new ObjectMapper();
        var s = mapper.readValue("{\"dialogue\":\"안녕\",\"emotion\":\"JOY\",\"expression_id\":\"EX_03\"}", AiJsonOutput.Scene.class);
        assertThat(s.expressionId()).isEqualTo("EX_03");
        assertThat(mapper.readValue("{\"dialogue\":\"안녕\",\"emotion\":\"JOY\"}", AiJsonOutput.Scene.class).expressionId()).isNull();
        var response = new SendChatResponse.SceneResponse("연", null, "안녕", EmotionTag.JOY, null, null, null, null, "EX_03", "https://assets.example/ex_03.png");
        assertThat(mapper.readValue(mapper.writeValueAsString(response), SendChatResponse.SceneResponse.class)).isEqualTo(response);
    }
}
