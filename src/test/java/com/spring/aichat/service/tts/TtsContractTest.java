package com.spring.aichat.service.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.*;
import com.spring.aichat.dto.chat.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

class TtsContractTest {
    @Test void performanceTagsCannotChangeSpokenWords() {
        assertThat(TtsText.validate("안녕!", "[excited] 안녕!")).isEqualTo("[excited] 안녕!");
        assertThat(TtsText.validate("...왜?", "[bored] ...왜?")).isEqualTo("[bored] ...왜?");
        assertThat(TtsText.validate("죄, 죄송해요!", "[scared] 죄, 죄송해요!")).isEqualTo("[scared] 죄, 죄송해요!");
        assertThat(TtsText.validate("안녕!", "[excited] 안녕하세요!")).isNull();
        assertThat(TtsText.validate("안녕!", "[explosion] 안녕!")).isNull();
        assertThat(TtsText.validate("안녕!", "[calm][sad][angry]안녕!")).isNull();
        assertThat(TtsText.validate("[공지] 안녕!", "[공지] 안녕!")).isNull();
        assertThat(TtsText.validate("[공지] 안녕!", "［공지］ 안녕!")).isEqualTo("［공지］ 안녕!");
    }
    @Test void malformedAuxiliaryFieldsNeverBreakDialogueParsing() throws Exception {
        var mapper = new ObjectMapper();
        var scene = mapper.readValue("{\"dialogue\":\"안녕\",\"tts_input\":{\"bad\":true}}", AiJsonOutput.Scene.class);
        var v2 = mapper.readValue("{\"dialogue\":\"안녕\",\"tts_input\":[1,2]}", AiJsonOutputV2.SceneV2.class);
        assertThat(scene.dialogue()).isEqualTo("안녕"); assertThat(v2.dialogue()).isEqualTo("안녕");
    }
    @Test void privateBucketGuardAcceptsSeparateBucketAndRejectsPublicOrEmpty() {
        var props = new TtsProperties();
        var assets = new S3Properties("public-images", "auto", "", "", "", "");
        var storage = new TtsAudioStorage(mock(software.amazon.awssdk.services.s3.S3Client.class), props, assets);
        assertThatThrownBy(storage::checkPrivateBucket).isInstanceOf(IllegalStateException.class);
        props.setAudioBucket("public-images"); assertThatThrownBy(storage::checkPrivateBucket).isInstanceOf(IllegalStateException.class);
        props.setAudioBucket("private-audio"); assertThatCode(storage::checkPrivateBucket).doesNotThrowAnyException();
    }
    @Test void socketCollectsFragmentedAudioAndRequiresFinal() throws Exception {
        var listener = new ElevenLabsDialogueClient.AudioListener(new ObjectMapper());
        var socket = mock(java.net.http.WebSocket.class);
        listener.onText(socket, "{\"audio\":\"" + Base64.getEncoder().encodeToString(new byte[]{1,2,3}), false);
        listener.onText(socket, "\"}", true);
        assertThat(listener.done).isNotDone();
        listener.onText(socket, "{\"is_final\":true}", true);
        assertThat(listener.done.get(1, TimeUnit.SECONDS)).containsExactly(1,2,3);
        var closed = new ElevenLabsDialogueClient.AudioListener(new ObjectMapper());
        closed.onClose(socket, 1000, "closed"); assertThat(closed.done).isCompletedExceptionally();
    }
    @Test void longInputPreservesAllTextIncludingSurrogatePairs() {
        String input = "가".repeat(1799) + "😀" + "나".repeat(3000);
        var parts = TtsService.splitInput(input);
        assertThat(String.join("", parts)).isEqualTo(input);
        assertThat(parts).allMatch(p -> p.length() <= 1800);
        var tagged = TtsService.splitInput("가".repeat(1798) + "[sad]나");
        assertThat(tagged.get(0)).hasSize(1798);
        assertThat(tagged.get(1)).isEqualTo("[sad]나");
    }
}
