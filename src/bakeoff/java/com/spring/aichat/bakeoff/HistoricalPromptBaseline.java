package com.spring.aichat.bakeoff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.dto.openai.OpenAiMessage;
import java.util.*;

/** Historical lab fixture only. Live production assemblers and user/assistant history remain separate. */
final class HistoricalPromptBaseline {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SNAPSHOT = load();

    private static JsonNode load() {
        try (var stream = HistoricalPromptBaseline.class.getResourceAsStream("/bakeoff/system-baseline-20260918.json")) {
            if (stream == null) throw new IllegalStateException("Historical prompt resource missing");
            return JSON.readTree(stream);
        } catch (Exception e) { throw new IllegalStateException("Cannot load historical prompt fixture", e); }
    }

    static Map<String, Object> candidateFacts(Character character) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("appearance", character.getAppearance());
        facts.put("clothing", character.getClothing());
        facts.put("effectiveOocExample", character.getEffectiveOocExample());
        facts.put("characterId", Objects.toString(character.getId(), null));
        return facts;
    }

    static List<OpenAiMessage> apply(String mode, List<OpenAiMessage> live, Object profile, int historyLogs, Object candidateFacts) {
        JsonNode saved = SNAPSHOT.path("modes").path(mode);
        if (saved.isMissingNode() || !saved.path("fixtureProfile").equals(JSON.valueToTree(profile)))
            throw new IllegalStateException("Historical prompt fixture changed; create a new audited baseline");
        if (!SNAPSHOT.path("candidateFacts").equals(JSON.valueToTree(candidateFacts)))
            throw new IllegalStateException("Historical candidate facts changed; create a new audited baseline");
        if (live.size() < 3 || historyLogs < 3 || historyLogs > 20) throw new IllegalStateException("Historical message shape changed");
        List<Integer> positions = mode.equals("STORY") ? List.of(0, 1) : List.of(0, live.size()-2, live.size()-1);
        var expected = saved.path("systemMessages");
        if (positions.size() != expected.size()) throw new IllegalStateException("Historical system count changed");
        var result = new ArrayList<>(live);
        for (int i = 0; i < live.size(); i++) {
            var message = live.get(i);
            int systemIndex = positions.indexOf(i);
            if (systemIndex < 0) {
                if (!List.of("user", "assistant").contains(message.role()))
                    throw new IllegalStateException("Unexpected history role in historical baseline");
                continue;
            }
            try {
                var frozen = JSON.treeToValue(expected.get(systemIndex), OpenAiMessage.class);
                // V1 historically marks the static block only at 3 or 20 retrieved logs; V2 marks every turn.
                Map<String, Object> expectedCache = i == 0 && (mode.equals("STORY") || historyLogs == 3 || historyLogs == 20)
                    ? Map.of("type", "ephemeral") : null;
                if (!"system".equals(message.role()) || !"system".equals(frozen.role())
                        || !Objects.equals(message.cache_control(), expectedCache))
                    throw new IllegalStateException("Historical system role/cache layout changed");
                result.set(i, new OpenAiMessage(message.role(), frozen.content(), message.cache_control()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        }
        int currentIndex = mode.equals("STORY") ? live.size()-1 : live.size()-3;
        if (!"user".equals(live.get(currentIndex).role()))
            throw new IllegalStateException("Historical current user position changed");
        return List.copyOf(result);
    }
}
