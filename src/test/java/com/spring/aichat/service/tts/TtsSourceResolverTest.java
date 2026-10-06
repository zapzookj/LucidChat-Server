package com.spring.aichat.service.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.TtsProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.*;
import com.spring.aichat.domain.heroine.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TtsSourceResolverTest {
    final TtsProperties props = new TtsProperties();
    final ChatLogMongoRepository logs = mock(ChatLogMongoRepository.class);
    final ChatRoomHeroineRepository heroines = mock(ChatRoomHeroineRepository.class);
    final ChatRoom room = mock(ChatRoom.class);
    final Character airi = mock(Character.class);
    final TtsSourceResolver resolver = new TtsSourceResolver(props, new ObjectMapper(), logs, heroines);
    @BeforeEach void setup() {
        props.setVoices(Map.of("airi", "voiceA", "seolah", "shared", "rosetta", "shared"));
        when(room.getId()).thenReturn(1L); when(room.getCharacter()).thenReturn(airi);
        when(room.isSandboxMode()).thenReturn(true);
        when(airi.getSource()).thenReturn(CharacterSource.OFFICIAL);
        when(airi.getName()).thenReturn("아이리"); when(airi.getSlug()).thenReturn("airi");
        when(airi.getFirstGreeting()).thenReturn("안녕하세요.");
        when(heroines.findByChatRoom_Id(1L)).thenReturn(List.of());
    }
    private ChatLogDocument log(String scenes, String raw) {
        return ChatLogDocument.builder().id("log").roomId(1L).role(ChatRole.ASSISTANT).cleanContent("안녕하세요.").scenesJson(scenes).rawContent(raw).build();
    }
    @Test void fixedGreetingUsesFirstAssistantAfterIntroSystemLog() {
        var greeting = log(null, null);
        when(logs.findById("log")).thenReturn(Optional.of(greeting));
        when(logs.findTop1ByRoomIdAndRoleAndHiddenFalseOrderByCreatedAtAsc(1L, ChatRole.ASSISTANT)).thenReturn(Optional.of(greeting));
        assertThat(resolver.resolve(room, "log").greetingSlug()).isEqualTo("airi");
        verify(logs, never()).findTop1ByRoomIdOrderByCreatedAtAsc(anyLong());
    }
    @Test void legacyMainNullSpeakerWorksAndNarrationNpcDoNotAcquireVoice() {
        when(room.isSandboxMode()).thenReturn(false); when(room.isStoryMode()).thenReturn(true);
        var saved = log("[{\"speaker\":null,\"dialogue\":\"안녕\"},{\"speaker\":\"NPC\",\"dialogue\":\"비밀\"},{\"speaker\":\"아이리\",\"narration\":\"움직인다\",\"dialogue\":\"\"}]",
            "{\"scenes\":[{\"speaker\":null,\"dialogue\":\"안녕\",\"tts_input\":\"[softly] 안녕\"}]}");
        when(logs.findById("log")).thenReturn(Optional.of(saved));
        var clips = resolver.resolve(room, "log").clips();
        assertThat(clips).hasSize(1); assertThat(clips.get(0).sceneIndex()).isZero(); assertThat(clips.get(0).input()).isEqualTo("[softly] 안녕");
    }
    @Test void storyV2SystemHasNoDefaultHeroineVoiceAndWrongAuxBodyIsDiscarded() {
        when(room.getCharacter()).thenReturn(null); when(room.isStoryMode()).thenReturn(true);
        var heroine = mock(ChatRoomHeroine.class); when(heroine.getCharacter()).thenReturn(airi);
        when(heroines.findByChatRoom_Id(1L)).thenReturn(List.of(heroine));
        var saved = log("[{\"speaker\":null,\"isSystem\":true,\"dialogue\":\"시스템\"},{\"speaker\":\"아이리\",\"dialogue\":\"안녕\"}]",
            "{\"scenes\":[{}, {\"dialogue\":\"안녕\",\"tts_input\":\"[calm] 새로운 말\"}]}");
        when(logs.findById("log")).thenReturn(Optional.of(saved));
        var clips = resolver.resolve(room, "log").clips();
        assertThat(clips).hasSize(1); assertThat(clips.get(0).sceneIndex()).isEqualTo(1); assertThat(clips.get(0).input()).isNull();
    }
    @Test void wrongRoomHiddenRoleAndDeletedLogsCannotResolve() {
        for (var saved : List.of(ChatLogDocument.builder().roomId(2L).role(ChatRole.ASSISTANT).build(),
            ChatLogDocument.builder().roomId(1L).role(ChatRole.ASSISTANT).hidden(true).build(),
            ChatLogDocument.builder().roomId(1L).role(ChatRole.USER).build())) {
            when(logs.findById("log")).thenReturn(Optional.of(saved));
            assertThatThrownBy(() -> resolver.resolve(room, "log")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        when(logs.findById("log")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(room, "log")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void sharedOfficialVoiceIdsAreAllowedButUgcAndHiddenAreNot() {
        when(airi.getSlug()).thenReturn("rosetta"); assertThat(resolver.officialVoice(airi)).isEqualTo("shared");
        when(airi.getSlug()).thenReturn("seolah"); assertThat(resolver.officialVoice(airi)).isEqualTo("shared");
        when(airi.getSource()).thenReturn(CharacterSource.UGC); assertThat(resolver.officialVoice(airi)).isNull();
        when(airi.getSource()).thenReturn(CharacterSource.OFFICIAL); when(airi.isHidden()).thenReturn(true); assertThat(resolver.officialVoice(airi)).isNull();
    }
}
