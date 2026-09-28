package com.spring.aichat.bakeoff;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.aichat.config.*;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.*;
import com.spring.aichat.domain.heroine.*;
import com.spring.aichat.domain.memory.HeroineMemorySummaryRepository;
import com.spring.aichat.domain.notification.OffscreenNotificationRepository;
import com.spring.aichat.domain.ugc.*;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.world.*;
import com.spring.aichat.dto.chat.*;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.prompt.*;
import com.spring.aichat.service.story.*;
import com.spring.aichat.service.stream.ChatStreamService;
import com.spring.aichat.service.util.*;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Fixed, offline P1 acceptance fixtures. No Spring context, network, credentials or persistent writes. */
public final class AcceptanceBridge {
    private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();
    private static final long ROOM = 900022L;
    private static final String USER_NAME = "지우";
    private static final Set<String> NORMAL_STATS = Set.of("intimacy", "affection", "dependency", "playfulness", "trust");
    private static final Set<String> SECRET_STATS = Set.of("lust", "corruption", "obsession");
    private static final String OPENING_CUE = "[OPENING] 이야기의 도입 장면을 지금 생성하라. 유저는 아직 행동하지 않았다.";
    private static final String EVENT_NARRATION = "로제타가 찻잔 위에 띄운 작은 환영 나비가 갑자기 여러 마리로 갈라져 테이블 위를 맴돈다. 로제타는 원인을 살피며 아직 환영을 해제하지 못했다.";
    private static final String EVENT_CONSTRAINT = "이 장면에는 로제타와 지우만 있다. 제3자를 추가하지 않는다. 환영 나비가 불안정해진 원인을 살피며 로제타다운 말투를 유지한다. 사용자가 원인만 묻거나 지켜보는 동안에는 해결 행동이나 환영 해제를 임의로 만들지 말고 ONGOING을 유지한다.";

    private record Spec(String id, String mode, List<String> slugs, boolean secret, boolean opening,
                        boolean event, String userProfile, String defaultInput) {}
    private static final List<Spec> SPECS = List.of(
        new Spec("claire-free-normal", "SANDBOX", List.of("claire"), false, false, false,
            "21세 성인. 영지를 돌아보는 젊은 영주. 성녀에게 예의를 지키며 솔직하게 이야기한다.",
            "오늘은 성녀님도 잠깐 쉬면 좋겠어요. 다른 사람을 돌보는 일 말고, 클레어 씨 본인이 좋아하는 일은 뭐예요?"),
        new Spec("rosetta-story-opening", "STORY", List.of("rosetta"), false, true, false,
            RosettaFixture.USER_PROFILE, "(서비스 자동 오프닝 수용시험 — 실제 user 메시지는 전송하지 않음)"),
        new Spec("rosetta-sierra-story-multi", "STORY", List.of("rosetta", "sierra"), false, false, false,
            RosettaFixture.USER_PROFILE,
            "로제타 선배, 시에라, 둘 다 안녕. 내일 쉬는 시간에 정원에서 뭐 하면 좋을까? 두 사람 취향이 서로 다를 것 같은데."),
        new Spec("rosetta-free-event", "SANDBOX", List.of("rosetta"), false, false, true,
            RosettaFixture.USER_PROFILE,
            "나비가 점점 늘어나네. 왜 이런 거야? 난 아직 손대지 않고 보고만 있어."),
        new Spec("rosetta-free-secret-benign", "SANDBOX", List.of("rosetta"), true, false, false,
            RosettaFixture.USER_PROFILE,
            "*테이블 위에 새 찻잎 통을 내려놓는다.* 오늘은 장난 없이 차 맛부터 솔직히 평가해 줘. 마음에 안 들면 다른 걸 고르면 되고.")
    );

    private static Spec spec(JsonNode request) {
        String id = request.path("fixture").asText();
        Spec spec = SPECS.stream().filter(s -> s.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown fixed acceptance fixture: " + id));
        if (request.has("mode") && !spec.mode().equals(request.path("mode").asText()))
            throw new IllegalArgumentException("Fixture mode mismatch");
        if (request.has("promptVersion") && !PromptVariants.P1.equals(request.path("promptVersion").asText()))
            throw new IllegalArgumentException("Acceptance fixtures only support " + PromptVariants.P1);
        return spec;
    }

    private static <T> T bind(String file, String prefix, Class<T> type) {
        try {
            var sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
            return new Binder(ConfigurationPropertySources.from(sources)).bind(prefix, type).get();
        } catch (Exception e) { throw new IllegalStateException("Cannot load official fixture: " + file, e); }
    }
    private static <T> T entity(Class<T> type) {
        try { var c = type.getDeclaredConstructor(); c.setAccessible(true); return c.newInstance(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void fields(Object bean, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2)
            ReflectionTestUtils.setField(bean, (String) pairs[i], pairs[i + 1]);
    }

    private static final class Context {
        final Spec spec;
        final List<Object> repositories = new ArrayList<>();
        final ChatLogMongoRepository logs = repo(ChatLogMongoRepository.class);
        final List<Character> characters = new ArrayList<>();
        final Map<String, String> idByName = new LinkedHashMap<>();
        final Set<String> sanitizerSpeakers = new LinkedHashSet<>();
        final User user = entity(User.class);
        final ChatStreamService v1 = mock(ChatStreamService.class, CALLS_REAL_METHODS);
        final ChatStreamServiceV2 v2 = mock(ChatStreamServiceV2.class, CALLS_REAL_METHODS);
        final CharacterPromptAssembler free;
        final StoryDirectorPromptAssemblerV2 story;
        final World world;
        final WorldView view;
        final ChatRoom room;

        Context(Spec spec) {
            this.spec = spec;
            var seeds = bind("application-characters.yml", "app", CharacterSeedProperties.class).characters();
            for (String slug : spec.slugs()) {
                var selected = seeds.stream().filter(s -> slug.equals(s.slug())).toList();
                if (selected.size() != 1) throw new IllegalStateException("Expected one official seed: " + slug);
                var seed = selected.get(0);
                Character c = new Character(seed.name(), seed.slug(), seed.baseSystemPrompt(), seed.llmModelName());
                c.applySeed(seed);
                if (c.getAge() == null || c.getAge() < 18) throw new IllegalStateException("Adult fixture required: " + slug);
                long id = switch (slug) { case "rosetta" -> 101L; case "sierra" -> 102L; case "claire" -> 103L; default -> throw new IllegalArgumentException(slug); };
                fields(c, "id", id);
                characters.add(c);
                idByName.put(c.getName(), Long.toString(id));
                sanitizerSpeakers.add(c.getName());
            }
            sanitizerSpeakers.add(USER_NAME);
            Character lead = characters.get(0);
            if (characters.stream().anyMatch(c -> c.getWorldId() != lead.getWorldId()))
                throw new IllegalStateException("Multi-character fixture must use the same official world");
            var worlds = bind("application-worlds.yml", "app", WorldSeedProperties.class).worlds().stream()
                .filter(w -> lead.getWorldId().name().equals(w.id())).toList();
            if (worlds.size() != 1) throw new IllegalStateException("Expected one matching official world");
            var w = worlds.get(0);
            world = World.create(WorldId.valueOf(w.id()), w.displayName(), w.tagline(), w.description(),
                w.heroImageUrl(), w.thumbnailUrl(), w.openingNarration(), w.defaultBgm(), w.moodKeywords(),
                Boolean.TRUE.equals(w.secretAllowed()), w.displayOrder() == null ? 0 : w.displayOrder());
            var locations = bind("application-v2.yml", "app.v2", WorldLocationSeedProperties.class).locations().stream()
                .filter(l -> w.id().equals(l.worldId()) && !Boolean.FALSE.equals(l.active()))
                .sorted(Comparator.comparingInt(l -> l.displayOrder() == null ? 0 : l.displayOrder()))
                .map(l -> new WorldView.LocationView(l.locationKey(), l.displayName(), l.description(),
                    !Boolean.FALSE.equals(l.selectableAsStart()), null)).toList();
            view = new WorldView(WorldRef.ofOfficial(world.getId()), world, null, locations);
            fields(user, "id", 900022L, "nickname", USER_NAME, "profileDescription", spec.userProfile());
            room = spec.mode().equals("STORY")
                ? ChatRoom.createStoryV2(user, world, RosettaFixture.STORY_LOCATION, spec.userProfile(), USER_NAME)
                : new ChatRoom(user, lead, ChatMode.SANDBOX);
            fields(room, "id", ROOM, "currentTimeOfDay", TimeOfDay.DAY, "secretModeActive", spec.secret());
            if (spec.mode().equals("STORY")) {
                fields(room, "currentDayPart", DayPart.NOON);
                if (view.location(RosettaFixture.STORY_LOCATION).isEmpty()) throw new IllegalStateException("Missing garden");
            }
            if (spec.event()) {
                room.startDirectorEvent();
                room.setDirectorInterlude(EVENT_NARRATION, EVENT_CONSTRAINT);
            }
            var worldRepo = repo(WorldRepository.class);
            when(worldRepo.findById(world.getId())).thenReturn(Optional.of(world));
            free = new CharacterPromptAssembler(new PromptInjectionGuard(), worldRepo,
                repo(UgcWorldRepository.class), repo(UgcWorldLocationRepository.class),
                new SceneIllustrationProperties(false, null, null), new LegacyFeatureProperties());
            var heroines = repo(ChatRoomHeroineRepository.class);
            var presence = repo(CharacterPresenceRepository.class);
            var promotion = repo(RelationPromotionEligibilityRepository.class);
            var notification = repo(OffscreenNotificationRepository.class);
            var memory = repo(HeroineMemorySummaryRepository.class);
            var viewService = mock(WorldViewService.class);
            when(viewService.resolveForRoom(any())).thenReturn(view);
            var heroineRows = new ArrayList<ChatRoomHeroine>();
            var presenceRows = new ArrayList<CharacterPresence>();
            for (Character c : characters) {
                var heroine = entity(ChatRoomHeroine.class);
                fields(heroine, "character", c, "statusLevel", RelationStatus.STRANGER,
                    "statTrust", 0, "statIntimacy", 0, "statAffection", 0, "statDependency", 0,
                    "statPlayfulness", 0, "statLust", 0, "statCorruption", 0, "statObsession", 0);
                heroineRows.add(heroine);
                var present = entity(CharacterPresence.class);
                fields(present, "characterId", c.getId(), "currentLocationKey", RosettaFixture.STORY_LOCATION);
                presenceRows.add(present);
                when(memory.findByRoomIdAndCharacterIdOrderByCreatedAtAsc(ROOM, c.getId())).thenReturn(List.of());
            }
            when(heroines.findByChatRoom_Id(ROOM)).thenReturn(heroineRows);
            when(presence.findByChatRoom_Id(ROOM)).thenReturn(presenceRows);
            when(promotion.findByChatRoomIdAndTriggeredFalse(ROOM)).thenReturn(List.of());
            when(notification.findByChatRoom_IdAndRespondedAtIsNullAndExpiresAtAfterOrderBySentAtAsc(eq(ROOM), any())).thenReturn(List.of());
            story = new StoryDirectorPromptAssemblerV2(heroines, presence, promotion, notification, viewService, memory, new PromptInjectionGuard());
            for (Object service : List.of(v1, v2)) fields(service, "chatLogRepository", logs, "objectMapper", JSON);
        }
        private <T> T repo(Class<T> type) { T value = mock(type); repositories.add(value); return value; }
        void assertReadOnly() {
            for (Object repository : repositories) for (var call : mockingDetails(repository).getInvocations())
                if (!call.getMethod().getName().startsWith("find"))
                    throw new IllegalStateException("Unexpected repository operation: " + call.getMethod().getName());
        }
        Map<String, Object> profile() {
            var profile = new LinkedHashMap<String, Object>();
            profile.put("id", spec.id());
            profile.put("source", "checked-in official seeds; Character.applySeed; no production DB reads");
            profile.put("characterSource", "src/main/resources/application-characters.yml");
            profile.put("worldSource", "src/main/resources/application-worlds.yml + application-v2.yml");
            profile.put("characters", characters.stream().map(c -> Map.of("id", c.getId(), "slug", c.getSlug(),
                "name", c.getName(), "age", c.getAge(), "personality", c.getEffectivePersonality(spec.secret()),
                "tone", c.getEffectiveTone(spec.secret()))).toList());
            profile.put("characterIds", characters.stream().map(Character::getId).toList());
            profile.put("worldId", world.getId());
            profile.put("mode", spec.mode());
            profile.put("userName", USER_NAME);
            profile.put("userAge", 21);
            profile.put("userProfile", spec.userProfile());
            profile.put("defaultInput", spec.defaultInput());
            profile.put("flags", Map.of("secretMode", room.isSecretModeActive(), "effectiveSecretMode", spec.secret(),
                "openingMode", spec.opening(), "eventActive", room.isEventActive(),
                "directorConstraintActive", room.hasActiveDirectorConstraint(), "multiCharacter", characters.size() > 1));
            profile.put("stateFrozen", true);
            profile.put("state", "STRANGER; all stats 0; DAY/NOON; repository fixtures only; no post-response state mutation");
            profile.put("inputDisposition", spec.opening() ? "input is an execution label only; no user log; exact service opening system cue" : "actual user log through production history builder");
            profile.put("openingCue", spec.opening() ? OPENING_CUE : "");
            profile.put("expectedEventStatusForDefaultInput", spec.event() ? "ONGOING" : "not applicable");
            profile.put("eventSetup", spec.event() ? EVENT_NARRATION : "");
            profile.put("speakerBoundary", spec.mode().equals("SANDBOX")
                ? "V1 lead speaker must be JSON null; no third-party NPC is part of these fixtures"
                : "Only fixture characters; unknown NPC is a scenario mismatch, not a general production DTO rejection");
            profile.put("secretCoverage", spec.secret() ? "benign tea conversation; 8-stat branch only; no explicit-content/moderation evaluation" : "normal mode");
            return profile;
        }
    }

    private static ChatLogDocument log(ChatRole role, String raw, String clean, int index) {
        return ChatLogDocument.builder().roomId(ROOM).role(role).rawContent(raw).cleanContent(clean)
            .createdAt(LocalDateTime.of(2026, 9, 22, 12, 0).plusSeconds(index)).build();
    }
    private static ChatLogDocument assistant(Context c, String raw, int index) throws Exception {
        String json = LlmOutputParser.extractJson(raw);
        JsonNode root = JSON.readTree(json);
        String clean;
        if (c.spec.mode().equals("SANDBOX")) {
            var parsed = JSON.readValue(json, AiJsonOutput.class);
            clean = ReflectionTestUtils.invokeMethod(c.v1, "buildRichCleanContent", parsed.scenes());
        } else {
            var parsed = JSON.readValue(json, AiJsonOutputV2.class);
            List<String> parts = new ArrayList<>();
            if (parsed.scenes() == null) throw new IllegalArgumentException("No scenes");
            for (var scene : parsed.scenes()) {
                String narration = DialogueSanitizer.stripSpeakerPrefix(scene.narration(), c.sanitizerSpeakers);
                String dialogue = DialogueSanitizer.stripSpeakerPrefix(scene.dialogue(), c.sanitizerSpeakers);
                var part = new ArrayList<String>();
                if (narration != null && !narration.isBlank()) part.add(narration);
                if (dialogue != null && !dialogue.isBlank()) part.add(dialogue);
                if (!part.isEmpty()) parts.add(String.join("\n", part));
            }
            clean = String.join("\n\n", parts);
        }
        var doc = log(ChatRole.ASSISTANT, json, clean, index);
        if (root.path("inner_thought").isTextual()) fields(doc, "innerThought", root.path("inner_thought").asText());
        return doc;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> prepare(JsonNode request) throws Exception {
        var c = new Context(spec(request));
        Spec spec = c.spec;
        String input = request.path("input").asText();
        if (!spec.opening() && (input.isBlank() || input.length() > 8000)) throw new IllegalArgumentException("Input must be 1–8000 characters");
        JsonNode continuation = request.path("history");
        if (!continuation.isMissingNode() && !continuation.isArray()) throw new IllegalArgumentException("history must be an array");
        if (continuation.size() > 40 || (spec.opening() && !continuation.isEmpty())) throw new IllegalArgumentException("Invalid fixture history length");
        List<ChatLogDocument> history = new ArrayList<>();
        if (!spec.opening()) {
            // Opening greeting is fixture setup; subsequent input/history uses actual service methods.
            if (spec.mode().equals("SANDBOX")) {
                Character lead = c.characters.get(0);
                var initial = JSON.createObjectNode();
                initial.putArray("scenes").addObject().putNull("speaker").put("narration", lead.getIntroNarration())
                    .put("dialogue", lead.getFirstGreeting()).put("emotion", "JOY");
                history.add(assistant(c, initial.toString(), history.size()));
            } else {
                String context = "점심시간, 아카데미 중앙 정원의 테라스에 로제타와 시에라가 함께 있다. 두 사람 모두 지우를 처음 만난다.";
                history.add(log(ChatRole.SYSTEM, context, context, history.size()));
            }
            if (spec.event()) history.add(log(ChatRole.SYSTEM, EVENT_NARRATION, EVENT_NARRATION, history.size()));
            for (JsonNode turn : continuation) {
                String text = turn.path("input").asText();
                if (text.isBlank() || text.length() > 8000) throw new IllegalArgumentException("Invalid history input");
                if (!Boolean.TRUE.equals(validate(JSON.createObjectNode().put("fixture", spec.id()).put("raw", turn.path("raw").asText())).get("valid")))
                    throw new IllegalArgumentException("Invalid fixture history output");
                history.add(log(ChatRole.USER, text, text, history.size()));
                history.add(assistant(c, turn.path("raw").asText(), history.size()));
            }
            history.add(log(ChatRole.USER, input, input, history.size()));
        }
        var recent = new ArrayList<>(history.subList(Math.max(0, history.size() - 20), history.size()));
        Collections.reverse(recent);
        when(c.logs.findTop20ByRoomIdOrderByCreatedAtDesc(ROOM)).thenAnswer(inv -> new ArrayList<>(recent));
        List<OpenAiMessage> messages;
        if (spec.mode().equals("SANDBOX")) {
            var prompt = c.free.assembleSystemPrompt(c.characters.get(0), c.room, c.user, "", spec.secret());
            messages = ReflectionTestUtils.invokeMethod(c.v1, "buildMessageHistory", ROOM, prompt, c.characters.get(0).getName(), USER_NAME);
        } else {
            var prompt = c.story.assemble(c.room, c.user, c.characters.get(0).getId(), "", spec.secret(), spec.opening(), List.of());
            messages = ReflectionTestUtils.invokeMethod(c.v2, "buildMessageHistoryV2", ROOM, prompt, spec.opening() ? OPENING_CUE : null);
        }
        c.assertReadOnly();
        var result = new LinkedHashMap<String, Object>();
        result.put("messages", messages);
        result.put("mode", spec.mode());
        result.put("maxTokens", spec.mode().equals("STORY") ? 8192 : 6144);
        result.put("historyLogs", recent.size());
        result.put("promptVersion", PromptVariants.P1);
        result.put("fixture", spec.id());
        result.put("fixtureProfile", c.profile());
        result.put("flags", c.profile().get("flags"));
        result.put("characterIds", c.characters.stream().map(Character::getId).toList());
        result.put("stateFrozen", true);
        result.put("parentPromptVersion", PromptVariants.P0);
        result.put("promptLabel", "P1 final production acceptance");
        result.put("changes", List.of("Unmodified live P1 assembler and service history path; fixed acceptance fixture"));
        result.put("baselineMessagesHash", PromptVariants.sha256(JSON.writeValueAsString(messages)));
        return result;
    }

    Map<String, Object> validate(JsonNode request) throws Exception {
        var c = new Context(spec(request));
        String raw = request.path("raw").asText();
        if (raw.length() > 200000) throw new IllegalArgumentException("Output too large");
        String json = LlmOutputParser.extractJson(raw);
        JsonNode root = JSON.readTree(json);
        List<String> issues = new ArrayList<>();
        JsonNode scenes = root.path("scenes");
        if (!scenes.isArray() || scenes.isEmpty()) issues.add("scenes must be non-empty array");
        if (c.spec.opening() && (scenes.size() < 1 || scenes.size() > 2)) issues.add("Opening requires 1–2 scenes");
        if (c.spec.mode().equals("STORY") && !c.spec.opening() && (scenes.size() < 3 || scenes.size() > 5)) issues.add("STORY requires 3–5 scenes");
        if (c.spec.event() && (scenes.size() < 2 || scenes.size() > 4)) issues.add("Event requires 2–4 scenes");
        Set<String> appearing = new LinkedHashSet<>();
        for (JsonNode scene : scenes) {
            if (!scene.isObject()) { issues.add("Scene must be an object"); continue; }
            String speaker = scene.path("speaker").asText("");
            if (c.spec.mode().equals("SANDBOX") && !scene.path("speaker").isNull())
                issues.add("V1 lead speaker must be explicit JSON null (no third-party NPC in this fixture)");
            if (USER_NAME.equals(speaker)) issues.add("User is the speaker");
            else if (!speaker.isEmpty() && !c.idByName.containsKey(speaker)) issues.add("Unknown fixture speaker: " + speaker);
            if (c.idByName.containsKey(speaker)) appearing.add(c.idByName.get(speaker));
            if (!scene.path("narration").isTextual() || !scene.path("dialogue").isTextual()) issues.add("narration/dialogue must be strings");
            if (scene.path("narration").asText().isBlank() && scene.path("dialogue").asText().isBlank()) issues.add("Empty scene");
            if (c.spec.mode().equals("STORY") && speaker.isEmpty() && !scene.path("dialogue").asText().isBlank()) issues.add("Environment scene cannot speak");
            try { EmotionTag.valueOf(scene.path("emotion").asText()); } catch (Exception e) { issues.add("Unknown emotion"); }
        }
        JsonNode changes;
        if (c.spec.mode().equals("STORY")) {
            JsonNode updates = root.path("system_updates");
            if (!updates.isObject()) issues.add("Missing system_updates");
            if (!updates.path("topic_concluded").isBoolean()) issues.add("Missing topic_concluded boolean");
            changes = updates.path("stat_changes");
            if (!changes.isObject()) issues.add("Missing stat_changes");
            else changes.fields().forEachRemaining(e -> {
                if (!c.idByName.containsValue(e.getKey())) issues.add("Unknown character id: " + e.getKey());
                checkStats(e.getValue(), c.spec.secret(), issues);
            });
            if (c.spec.opening()) {
                if (!changes.isEmpty()) issues.add("Opening must not update stats");
                if (updates.hasNonNull("time_advance") || updates.hasNonNull("relation_transition") || updates.path("ending_triggered").asBoolean(false))
                    issues.add("Opening must not advance time/relation/ending");
            } else for (String id : appearing) if (!changes.has(id)) issues.add("Missing stats for speaking character: " + id);
            checkCharacterIds(root, c.idByName.values(), issues);
            JsonNode memory = root.path("memory_delta").path("by_character");
            if (memory.isObject()) memory.fieldNames().forEachRemaining(id -> { if (!c.idByName.containsValue(id)) issues.add("Unknown memory character id: " + id); });
        } else {
            changes = root.path("stat_changes");
            checkStats(changes, c.spec.secret(), issues);
            if (!root.path("topic_concluded").isBoolean()) issues.add("Missing topic_concluded boolean");
            if (c.spec.event()) {
                if (!Set.of("ONGOING", "RESOLVED").contains(root.path("event_status").asText())) issues.add("Invalid event_status");
                if (root.hasNonNull("inner_thought")) issues.add("Event inner_thought must be null");
                if (root.path("topic_concluded").asBoolean(false)) issues.add("Event topic_concluded must be false");
                if ("ONGOING".equals(root.path("event_status").asText())) changes.forEach(v -> { if (v.asInt() != 0) issues.add("ONGOING event stats must be zero"); });
            } else if (root.hasNonNull("event_status")) issues.add("Normal conversation event_status must be null");
        }
        String clean = "";
        boolean dtoValid = true;
        try { clean = assistant(c, raw, 0).getCleanContent(); }
        catch (Exception e) { dtoValid = false; issues.add("Production DTO/clean-content parse: " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
        var displayed = JSON.createArrayNode();
        for (JsonNode scene : scenes) {
            JsonNode copy = scene.deepCopy();
            if (copy.isObject()) for (String key : List.of("narration", "dialogue")) if (copy.path(key).isTextual())
                ((ObjectNode) copy).put(key, DialogueSanitizer.stripSpeakerPrefix(copy.path(key).asText(), c.sanitizerSpeakers));
            displayed.add(copy);
        }
        c.assertReadOnly();
        return Map.of("valid", issues.isEmpty(), "productionDtoValid", dtoValid, "issues", issues, "scenes", displayed,
            "cleanContent", clean, "extractedJson", json, "parserStrippedWrapper", !raw.trim().equals(json), "fixture", c.spec.id());
    }

    private static void checkCharacterIds(JsonNode node, Collection<String> known, List<String> issues) {
        if (node.isObject()) node.fields().forEachRemaining(e -> {
            if (Set.of("character_id", "from_character_id").contains(e.getKey()) && !e.getValue().isNull()
                    && !known.contains(e.getValue().asText())) issues.add("Unknown " + e.getKey() + ": " + e.getValue().asText());
            checkCharacterIds(e.getValue(), known, issues);
        });
        else if (node.isArray()) node.forEach(v -> checkCharacterIds(v, known, issues));
    }
    private static void checkStats(JsonNode stats, boolean secret, List<String> issues) {
        Set<String> expected = new LinkedHashSet<>(NORMAL_STATS);
        if (secret) expected.addAll(SECRET_STATS);
        if (!stats.isObject()) { issues.add("Invalid stat object"); return; }
        for (String key : expected) if (!stats.has(key)) issues.add("Missing stat: " + key);
        stats.fields().forEachRemaining(e -> {
            if (!expected.contains(e.getKey())) issues.add("Unexpected stat: " + e.getKey());
            if (!e.getValue().isIntegralNumber() || !e.getValue().canConvertToInt()
                    || e.getValue().asInt() < -3 || e.getValue().asInt() > 3) issues.add("Invalid stat range: " + e.getKey());
        });
    }

    private static String validSample(Context c) {
        var root = JSON.createObjectNode();
        var scenes = root.putArray("scenes");
        int count = c.spec.opening() ? 1 : c.spec.mode().equals("STORY") ? 3 : c.spec.event() ? 2 : 1;
        for (int i = 0; i < count; i++) {
            ObjectNode scene = scenes.addObject();
            if (c.spec.mode().equals("SANDBOX")) scene.putNull("speaker");
            else scene.put("speaker", c.characters.get(i % c.characters.size()).getName());
            scene.put("narration", "찻잔 옆에서 인사를 건넨다.").put("dialogue", "안녕하세요.").put("emotion", "JOY");
        }
        ObjectNode changes;
        if (c.spec.mode().equals("STORY")) {
            changes = root.putObject("system_updates").put("topic_concluded", false).putObject("stat_changes");
            if (!c.spec.opening()) for (String id : c.idByName.values()) putStats(changes.putObject(id), c.spec.secret());
        } else {
            changes = root.putObject("stat_changes");
            putStats(changes, c.spec.secret());
            root.put("topic_concluded", false);
            if (c.spec.event()) root.put("event_status", "ONGOING");
        }
        return root.toString();
    }
    private static void putStats(ObjectNode target, boolean secret) {
        for (String key : new TreeSet<>(NORMAL_STATS)) target.put(key, 0);
        if (secret) for (String key : new TreeSet<>(SECRET_STATS)) target.put(key, 0);
    }
    private void selfTest() throws Exception {
        for (Spec spec : SPECS) {
            var c = new Context(spec);
            var req = JSON.createObjectNode().put("fixture", spec.id()).put("mode", spec.mode()).put("input", spec.defaultInput());
            var first = prepare(req);
            if (!JSON.writeValueAsString(first).equals(JSON.writeValueAsString(prepare(req)))) throw new AssertionError("Non-deterministic fixture: " + spec.id());
            JsonNode messages = JSON.valueToTree(first).path("messages");
            int users = 0;
            for (JsonNode m : messages) if (m.path("role").asText().equals("user") && m.path("content").asText().equals(spec.defaultInput())) users++;
            if (users != (spec.opening() ? 0 : 1)) throw new AssertionError("Wrong actual user input count");
            // V1 caches at 3 logs or each 20-log boundary; V2 always marks its static prefix.
            // The first free-mode fixture has two stored logs; its synthetic '(입장)' is not a DB log.
            int historyLogs = (Integer) first.get("historyLogs");
            boolean shouldCache = spec.mode().equals("STORY") || historyLogs == 3 || historyLogs % 20 == 0;
            JsonNode cache = messages.get(0).path("cache_control");
            if (shouldCache ? !cache.path("type").asText().equals("ephemeral") : !cache.isNull())
                throw new AssertionError("Service cache-marker branch changed: " + spec.id() + " / historyLogs=" + historyLogs);
            if (!messages.get(0).path("role").asText().equals("system")) throw new AssertionError("Static rules lost system role");
            if (spec.opening() && (messages.size() != 3 || !messages.get(2).path("content").asText().equals(OPENING_CUE)
                    || !messages.get(2).path("role").asText().equals("system"))) throw new AssertionError("Opening service path drift");
            if (spec.event()) {
                if (!c.room.isEventActive() || !c.room.hasActiveDirectorConstraint() || !"ONGOING".equals(c.room.getEventStatus()))
                    throw new AssertionError("Event entity setup drift");
                if (!messages.get(0).path("content").asText().contains(EVENT_CONSTRAINT))
                    throw new AssertionError("Director constraint missing from actual static prompt");
                boolean narrationFound = false;
                for (JsonNode m : messages) if (m.path("role").asText().equals("system")
                        && m.path("content").asText().equals("[NARRATION] " + EVENT_NARRATION)) narrationFound = true;
                if (!narrationFound) throw new AssertionError("Director narration lost production SYSTEM history role");
            }
            String valid = validSample(c);
            var check = JSON.createObjectNode().put("fixture", spec.id()).put("raw", valid);
            if (!Boolean.TRUE.equals(validate(check).get("valid"))) throw new AssertionError("Valid sample rejected: " + validate(check));
            if (!spec.opening()) {
                check.put("raw", valid.replace("\"trust\":0", "\"trust\":9"));
                if (!Boolean.FALSE.equals(validate(check).get("valid"))) throw new AssertionError("Bad stat accepted");
                check.put("raw", valid.replace(",\"trust\":0", ""));
                if (!Boolean.FALSE.equals(validate(check).get("valid"))) throw new AssertionError("Missing stat accepted");
            } else {
                check.put("raw", valid.replace("\"stat_changes\":{}", "\"stat_changes\":{\"101\":{\"trust\":1}}"));
                if (!Boolean.FALSE.equals(validate(check).get("valid"))) throw new AssertionError("Opening state update accepted");
            }
            ObjectNode wrongSpeaker = (ObjectNode) JSON.readTree(valid);
            ((ObjectNode) wrongSpeaker.path("scenes").get(0)).put("speaker", "등록되지않은인물");
            check.put("raw", wrongSpeaker.toString());
            if (!Boolean.FALSE.equals(validate(check).get("valid"))) throw new AssertionError("Unknown fixture speaker accepted");
            ((ObjectNode) wrongSpeaker.path("scenes").get(0)).put("speaker", USER_NAME);
            check.put("raw", wrongSpeaker.toString());
            if (!Boolean.FALSE.equals(validate(check).get("valid"))) throw new AssertionError("User speaker accepted");
            if (spec.mode().equals("SANDBOX")) {
                ((ObjectNode) wrongSpeaker.path("scenes").get(0)).put("speaker", c.characters.get(0).getName());
                check.put("raw", wrongSpeaker.toString());
                if (!Boolean.FALSE.equals(validate(check).get("valid"))) throw new AssertionError("Named V1 lead speaker accepted");
                ((ObjectNode) wrongSpeaker.path("scenes").get(0)).remove("speaker");
                check.put("raw", wrongSpeaker.toString());
                if (!Boolean.FALSE.equals(validate(check).get("valid"))) throw new AssertionError("Missing V1 speaker field accepted");
                ObjectNode wrongTopic = (ObjectNode) JSON.readTree(valid);
                wrongTopic.remove("topic_concluded");
                check.put("raw", wrongTopic.toString());
                if (!Boolean.FALSE.equals(validate(check).get("valid"))) throw new AssertionError("Missing V1 topic_concluded accepted");
                wrongTopic.put("topic_concluded", "false");
                check.put("raw", wrongTopic.toString());
                if (!Boolean.FALSE.equals(validate(check).get("valid"))) throw new AssertionError("String V1 topic_concluded accepted");
            }
            if (spec.mode().equals("STORY") && !spec.opening()) {
                check.put("raw", valid.replace("\"101\":", "\"999\":"));
                if (!Boolean.FALSE.equals(validate(check).get("valid"))) throw new AssertionError("Unknown character id accepted");
            }
            System.err.println("Acceptance fixture OK: " + spec.id() + " / " + messages.size() + " messages / adult characters=" + c.characters.stream().map(Character::getAge).toList());
        }
    }
    public static void main(String[] args) throws Exception {
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        var bridge = new AcceptanceBridge();
        if (args.length > 0 && args[0].equals("--self-test")) { bridge.selfTest(); return; }
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode request = null;
                try {
                    request = JSON.readTree(line);
                    Object result = switch (request.path("operation").asText()) {
                        case "prepare" -> bridge.prepare(request);
                        case "validate" -> bridge.validate(request);
                        case "profiles" -> SPECS.stream().map(s -> new Context(s).profile()).toList();
                        case "fixture" -> new Context(spec(request)).profile();
                        default -> throw new IllegalArgumentException("Unknown operation");
                    };
                    protocol.println(JSON.writeValueAsString(Map.of("id", request.path("id").asInt(), "result", result)));
                } catch (Exception e) {
                    protocol.println(JSON.writeValueAsString(Map.of("id", request == null ? -1 : request.path("id").asInt(),
                        "error", e.getClass().getSimpleName() + ": " + e.getMessage())));
                }
            }
        }
    }
}
