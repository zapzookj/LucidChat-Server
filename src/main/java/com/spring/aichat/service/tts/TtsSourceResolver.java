package com.spring.aichat.service.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.TtsProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.CharacterSource;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Component @RequiredArgsConstructor
public class TtsSourceResolver {
    private final TtsProperties props;
    private final ObjectMapper mapper;
    private final ChatLogMongoRepository logs;
    private final ChatRoomHeroineRepository heroines;
    public record Clip(int sceneIndex, String dialogue, String voiceId, String input, String objectKey) {
        public Clip withInput(String text) { return new Clip(sceneIndex, dialogue, voiceId, text, objectKey); }
        public Clip withKey(String key) { return new Clip(sceneIndex, dialogue, voiceId, input, key); }
    }
    public record Source(String hash, List<Clip> clips, String greetingSlug) {}

    public Source resolve(ChatRoom room, String logId) {
        ChatLogDocument log = logs.findById(logId).orElseThrow(TtsSourceResolver::notFound);
        if (!Objects.equals(room.getId(), log.getRoomId()) || log.isHidden() || log.getRole() != ChatRole.ASSISTANT) throw notFound();
        Character main = room.getCharacter();
        String hash = hash(log);
        if ((log.getScenesJson() == null || log.getScenesJson().isBlank()) && officialVoice(main) != null
                && Objects.equals(log.getCleanContent(), main.getFirstGreeting())
                && logs.findTop1ByRoomIdAndRoleAndHiddenFalseOrderByCreatedAtAsc(room.getId(), ChatRole.ASSISTANT)
                    .map(first -> first.getId().equals(logId)).orElse(false))
            return new Source(hash, List.of(), main.getSlug());
        if (log.getScenesJson() == null || log.getScenesJson().isBlank()) return new Source(hash, List.of(), null);
        try {
            List<Character> cast = new ArrayList<>();
            if (main != null) cast.add(main);
            if (room.isStoryMode()) heroines.findByChatRoom_Id(room.getId()).forEach(h -> cast.add(h.getCharacter()));
            JsonNode scenes = mapper.readTree(log.getScenesJson());
            JsonNode rawScenes = rawScenes(log.getRawContent());
            List<Clip> clips = new ArrayList<>();
            for (int i = 0; i < scenes.size(); i++) {
                var scene = scenes.get(i);
                String dialogue = scene.path("dialogue").asText("");
                String speaker = scene.path("speaker").asText("").trim();
                if (dialogue.isBlank() || scene.path("isSystem").asBoolean(false)) continue;
                Character character = cast.stream().filter(c -> c.getName().equals(speaker)).findFirst().orElse(null);
                if (speaker.isBlank() && main != null) character = main;
                String voice = officialVoice(character);
                if (voice == null) continue;
                String input = null;
                if (rawScenes.isArray() && i < rawScenes.size()) {
                    var raw = rawScenes.get(i);
                    // A malformed auxiliary field cannot fail text parsing or change spoken words.
                    if (Objects.equals(raw.path("dialogue").asText(), dialogue) && raw.path("tts_input").isTextual())
                        input = TtsText.validate(dialogue, raw.path("tts_input").asText());
                }
                clips.add(new Clip(i, dialogue, voice, input, null));
            }
            return new Source(hash, List.copyOf(clips), null);
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "저장된 대사를 읽을 수 없습니다."); }
    }
    private JsonNode rawScenes(String raw) {
        try { return mapper.readTree(raw).path("scenes"); } catch (Exception e) { return mapper.createArrayNode(); }
    }
    public String officialVoice(Character character) {
        return character == null || character.getSource() != CharacterSource.OFFICIAL || character.isHidden()
            ? null : props.voice(character.getSlug());
    }
    public boolean hasOfficialCast(ChatRoom room) {
        return heroines.findByChatRoom_Id(room.getId()).stream().anyMatch(h -> officialVoice(h.getCharacter()) != null);
    }
    public static String hash(ChatLogDocument log) {
        return TtsText.hash(Objects.toString(log.getScenesJson(), "") + "\n" + Objects.toString(log.getCleanContent(), ""));
    }
    static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "대사를 찾을 수 없습니다."); }
}
