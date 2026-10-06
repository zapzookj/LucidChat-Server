package com.spring.aichat.service.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.TtsProperties;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.tts.TtsSourceResolver.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TtsInputFormatterTest {
    final ObjectMapper mapper = new ObjectMapper();
    final OpenRouterClient llm = mock(OpenRouterClient.class);
    final TtsProperties props = new TtsProperties();
    final TtsInputFormatter formatter = new TtsInputFormatter(llm, props, mapper);

    @Test void manualActingUsesCharacterAndSceneWithoutSpeakingContext() throws Exception {
        var context = new PerformanceContext("백루나", "겁이 많다", "말을 더듬는다", "scared", "상대와 부딪혀 놀란다");
        var clip = new Clip(2, "죄, 죄송해요!", "voice", null, null, context);
        when(llm.completeJson(anyString(), anyString(), anyString(), anyInt(), anyDouble()))
            .thenReturn("{\"clips\":[{\"sceneIndex\":2,\"tts_input\":\"[scared] 죄, 죄송해요!\"}]}");
        var result = formatter.format(List.of(clip)).get(0);
        assertThat(result.input()).isEqualTo("[scared] 죄, 죄송해요!");
        assertThat(result.context()).isEqualTo(context);
        assertThat(result.withKey("stored").context()).isEqualTo(context);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(llm).completeJson(anyString(), anyString(), payload.capture(), anyInt(), anyDouble());
        var data = mapper.readTree(payload.getValue()).path("clips").get(0);
        assertThat(data.path("dialogue").asText()).isEqualTo(clip.dialogue());
        assertThat(data.path("context").path("speakingTone").asText()).isEqualTo("말을 더듬는다");
        assertThat(data.path("context").path("narration").asText()).isEqualTo(context.narration());
        assertThat(data.path("context").path("emotion").asText()).isEqualTo("scared");
        assertThat(data.path("context").size()).isEqualTo(5);
    }

    @Test void dialogueChangesAndSpokenNarrationAreDiscarded() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), anyInt(), anyDouble()))
            .thenReturn("{\"clips\":[{\"sceneIndex\":0,\"tts_input\":\"[bored] 태리는 한숨을 쉰다. 왜?\"},{\"sceneIndex\":1,\"tts_input\":\"[scared] 죄송합니다!\"}]}");
        var result = formatter.format(List.of(new Clip(0, "왜?", "voice", null, null), new Clip(1, "죄송해요!", "voice", null, null)));
        assertThat(result).extracting(Clip::input).containsExactly("왜?", "죄송해요!");
    }

    @Test void automaticValidHintsDoNotInvokeFormatter() throws Exception {
        var result = formatter.format(List.of(new Clip(0, "...왜?", "voice", "[bored] ...왜?", null)));
        assertThat(result.get(0).input()).isEqualTo("[bored] ...왜?");
        verifyNoInteractions(llm);
    }

    @Test void snapshotsCreatedBeforeActingContextStillDeserialize() throws Exception {
        var clip = mapper.readValue("{\"sceneIndex\":0,\"dialogue\":\"안녕\",\"voiceId\":\"voice\",\"input\":null,\"objectKey\":null}", Clip.class);
        assertThat(clip.context()).isNull();
        assertThat(clip.withInput("[calm] 안녕").dialogue()).isEqualTo("안녕");
    }
}
