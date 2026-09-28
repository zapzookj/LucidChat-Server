package com.spring.aichat.service.prompt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.CharacterSeedProperties;
import com.spring.aichat.config.LegacyFeatureProperties;
import com.spring.aichat.config.SceneIllustrationProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.*;
import com.spring.aichat.domain.ugc.UgcWorldLocationRepository;
import com.spring.aichat.domain.ugc.UgcWorldRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.world.WorldRepository;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.stream.ChatStreamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Offline assembler and existing history contract checks: no Spring context, network, or database. */
class CharacterPromptAssemblerContractTest {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> NORMAL_STATS = Set.of("intimacy", "affection", "dependency", "playfulness", "trust");

    static Stream<Arguments> outputModes() {
        return Stream.of(ChatMode.SANDBOX, ChatMode.STORY).flatMap(mode ->
            Stream.of(false, true).flatMap(event -> Stream.of(false, true).map(secret ->
                Arguments.of(mode, event, secret))));
    }

    @ParameterizedTest
    @MethodSource("outputModes")
    void activeOutputIsStrictJsonWithExistingModeContract(ChatMode mode, boolean event, boolean secret) throws Exception {
        Character character = character("rosetta");
        // Neither this arbitrary ID nor unusual name may become a hard-coded JSON speaker.
        ReflectionTestUtils.setField(character, "id", 784L);
        ReflectionTestUtils.setField(character, "name", "시험 인물 \"가람\"\n두 번째 줄");
        User user = user();
        ChatRoom room = room(user, character, mode, event);
        var payload = assembler(false).assembleSystemPrompt(character, room, user, "", secret);
        String output = payload.outputFormat();
        JsonNode sample = example(output);
        AiJsonOutput parsed = JSON.treeToValue(sample, AiJsonOutput.class);

        assertEquals("reasoning", sample.fieldNames().next());
        assertNotNull(parsed.reasoning());
        assertEquals(event ? 2 : 1, parsed.scenes().size());
        assertEquals(event ? "ONGOING" : null, parsed.eventStatus());
        assertFalse(parsed.isTopicConcluded());
        assertNull(parsed.innerThought());
        for (var scene : parsed.scenes()) {
            assertNull(scene.speaker());
            assertNotNull(scene.narration());
            assertNotNull(scene.dialogue());
            assertDoesNotThrow(() -> EmotionTag.valueOf(scene.emotion()));
        }
        Set<String> fields = new HashSet<>();
        sample.path("stat_changes").fieldNames().forEachRemaining(fields::add);
        Set<String> expected = new HashSet<>(NORMAL_STATS);
        if (secret) expected.addAll(Set.of("lust", "corruption", "obsession"));
        assertEquals(expected, fields);
        Set<String> rootFields = new HashSet<>();
        sample.fieldNames().forEachRemaining(rootFields::add);
        assertEquals(Set.of("reasoning", "event_status", "scenes", "stat_changes", "inner_thought",
            "topic_concluded", "easter_egg_trigger", "generate_illustration", "new_location_name",
            "location_canonical_key", "location_description", "illustration_scene_hint"), rootFields);
        assertThat(sample.toString()).doesNotContain("로제타", "784", "캐릭터ID", "NPC name", "One of");
        assertThat(output).doesNotContain("Output EXACTLY 1 scene");

        assertThat(output).contains("One of [" + String.join(", ", character.getAllowedLocations(room.getStatusLevel(), secret, false)) + "] or null");
        assertThat(output).contains("One of [" + String.join(", ", character.getAllowedOutfits(room.getStatusLevel(), secret, false)) + "] or null");
        assertThat(output).contains(secret ? "TOUCHING, TENSE, EROTIC] or null" : "TOUCHING, TENSE] or null");
        if (event) {
            assertThat(output).contains("\"ONGOING\" or \"RESOLVED\"", "null (disabled during events)",
                "false (events always false)", "Use 2~4 scenes per turn", "Only set values when RESOLVED.");
            assertThat(output).contains(character.getName());
        } else {
            assertThat(output).contains("null (⚠️ ALWAYS null in normal conversation)",
                "null or \"Korean string (15~50 chars)\"", "true or false", "Multi-Scene Coherence Rules");
        }
        assertThat(payload.staticRules()).contains(character.getEffectivePersonality(secret), character.getEffectiveTone(secret));
    }

    static Stream<Arguments> innerThoughtStates() {
        return Stream.of(false, true).flatMap(event -> Stream.of(false, true)
            .map(secret -> Arguments.of(event, secret)));
    }

    static Stream<Boolean> innerThoughtSecretModes() { return Stream.of(false, true); }

    @ParameterizedTest
    @MethodSource("innerThoughtStates")
    void innerThoughtInstructionsMatchTheEventOutputContract(boolean event, boolean secret) throws Exception {
        Character character = character("rosetta");
        User user = user();
        ChatRoom room = room(user, character, ChatMode.SANDBOX, false);
        if (event) room.startDirectorEvent();

        var payload = assembler(false).assembleSystemPrompt(character, room, user, "", secret);
        assertEquals(event, room.isEventActive());
        if (event) {
            assertEquals("ONGOING", room.getEventStatus());
            assertThat(payload.staticRules()).doesNotContain("# 💭 Inner Thought System (속마음)",
                "You have a hidden inner voice.", "## When to generate inner_thought:",
                "In **Secret Mode**, inner thoughts can be more explicit");
            assertThat(payload.outputFormat()).contains("`inner_thought`: null (disabled during events)");
        } else {
            assertThat(payload.staticRules()).contains("# 💭 Inner Thought System (속마음)",
                "You have a hidden inner voice.", "## When to generate inner_thought:");
            assertThat(payload.outputFormat()).contains("`inner_thought`: null or \"Korean string (15~50 chars)\"")
                .doesNotContain("null (disabled during events)");
            if (secret) assertThat(payload.staticRules()).contains("In **Secret Mode**, inner thoughts can be more explicit");
            else assertThat(payload.staticRules()).doesNotContain("In **Secret Mode**, inner thoughts can be more explicit");
        }
        assertTrue(example(payload.outputFormat()).path("inner_thought").isNull());
    }

    @ParameterizedTest
    @MethodSource("innerThoughtSecretModes")
    void nonEventDirectorInterludeKeepsNormalInnerThoughtInstructions(boolean secret) {
        Character character = character("rosetta");
        User user = user();
        ChatRoom room = room(user, character, ChatMode.SANDBOX, false);
        room.setDirectorInterlude("바람에 찻잔 받침이 흔들린다.", "찻잔 받침을 바로 놓고 대화를 이어간다.");
        assertFalse(room.isEventActive());
        assertTrue(room.hasActiveDirectorConstraint());

        var payload = assembler(false).assembleSystemPrompt(character, room, user, "", secret);
        assertThat(payload.staticRules()).contains("# 💭 Inner Thought System (속마음)",
            "찻잔 받침을 바로 놓고 대화를 이어간다.");
        assertThat(payload.outputFormat()).contains("`inner_thought`: null or \"Korean string (15~50 chars)\"")
            .doesNotContain("null (disabled during events)");
    }

    @ParameterizedTest
    @MethodSource("innerThoughtSecretModes")
    void resolvedEventRestoresTheEntireNormalPrompt(boolean secret) {
        Character character = character("rosetta");
        User user = user();
        ChatRoom room = room(user, character, ChatMode.SANDBOX, false);
        var assembler = assembler(false);
        var beforeEvent = assembler.assembleSystemPrompt(character, room, user, "", secret);
        assertThat(beforeEvent.staticRules()).contains("# 💭 Inner Thought System (속마음)");

        room.startDirectorEvent();
        var ongoing = assembler.assembleSystemPrompt(character, room, user, "", secret);
        assertThat(ongoing.staticRules()).doesNotContain("# 💭 Inner Thought System (속마음)");
        assertThat(ongoing.outputFormat()).contains("`inner_thought`: null (disabled during events)");

        room.updateEventStatus("RESOLVED");
        assertFalse(room.isEventActive());
        assertEquals("RESOLVED", room.getEventStatus());
        var afterEvent = assembler.assembleSystemPrompt(character, room, user, "", secret);
        assertEquals(beforeEvent, afterEvent, "Ending an event must restore the normal prompt without stale event restrictions");
    }

    @Test
    void appearanceDefaultsAreOptionalAndCurrentOutfitIsPreserved() {
        Character character = character("rosetta");
        ReflectionTestUtils.setField(character, "appearance", "눈은 \"호박색\"\n왼쪽 뺨의 점");
        ReflectionTestUtils.setField(character, "clothing", "기본 남색 코트");
        User user = user();
        ChatRoom room = room(user, character, ChatMode.SANDBOX, false);
        ReflectionTestUtils.setField(room, "currentOutfit", Outfit.values()[0]);
        var full = assembler(false).assembleSystemPrompt(character, room, user, "", false);
        assertThat(full.staticRules()).contains(character.getAppearance(), character.getClothing(),
            "current outfit in CURRENT SCENE STATE takes precedence");
        assertThat(full.dynamicRules()).contains("- outfit   : " + Outfit.values()[0].name());

        ReflectionTestUtils.setField(character, "appearance", null);
        var clothingOnly = assembler(false).assembleSystemPrompt(character, room, user, "", false);
        assertThat(clothingOnly.staticRules()).doesNotContain("- Appearance: null").contains("- Default Clothing: 기본 남색 코트");
        ReflectionTestUtils.setField(character, "clothing", " \n ");
        var absent = assembler(false).assembleSystemPrompt(character, room, user, "", false);
        assertThat(absent.staticRules()).doesNotContain("## Appearance & Default Clothing", "- Appearance:", "- Default Clothing:");
        assertEquals(full.dynamicRules(), absent.dynamicRules());
        assertEquals(full.outputFormat(), absent.outputFormat());
        ReflectionTestUtils.setField(character, "appearance", "한쪽 눈썹의 흉터");
        var appearanceOnly = assembler(false).assembleSystemPrompt(character, room, user, "", false);
        assertThat(appearanceOnly.staticRules()).contains("- Appearance: 한쪽 눈썹의 흉터").doesNotContain("- Default Clothing:");
    }

    static Stream<String> characterSlugs() { return Stream.of("airi", "claire", "rosetta"); }

    @ParameterizedTest
    @MethodSource("characterSlugs")
    void usesEachCharactersDefinitionAndAllowedValues(String slug) throws Exception {
        Character character = character(slug);
        User user = user();
        for (boolean secret : new boolean[] {false, true}) {
            ChatRoom room = room(user, character, ChatMode.SANDBOX, true);
            var payload = assembler(false).assembleSystemPrompt(character, room, user, "", secret);
            assertThat(payload.staticRules()).contains(character.getName(), character.getEffectivePersonality(secret), character.getEffectiveTone(secret));
            if (character.getAppearance() != null && !character.getAppearance().isBlank()) {
                assertThat(payload.staticRules()).contains(character.getAppearance());
            }
            assertThat(payload.outputFormat()).contains("YOU (" + character.getName() + ") are speaking");
            assertThat(payload.outputFormat()).contains("One of [" + String.join(", ", character.getAllowedLocations(room.getStatusLevel(), secret, false)) + "] or null");
            assertNotNull(JSON.treeToValue(example(payload.outputFormat()), AiJsonOutput.class));
        }
    }

    @Test
    void interpretationRulesCoverMixedAndPendingActionsWithoutFixtureExceptions() {
        Character character = character("rosetta");
        User user = user();
        var prompt = assembler(false).assembleSystemPrompt(character, room(user, character, ChatMode.SANDBOX, false), user, "", false).staticRules();
        assertThat(prompt).contains("text in `*...*`", "text outside it is spoken dialogue", "future plan is not a completed action",
            "permission or proposal does not mean the user has accepted", "standalone `(입장)`",
            "immediately before the first assistant response", "other parenthesized text or a later user message");
        assertThat(prompt).doesNotContain("ALWAYS the user's actual spoken words. Nothing else.",
            "treat it as the user physically doing/experiencing that action", "점심시간, 아카데미 중앙 정원");
    }

    @Test
    void automaticIllustrationGuideRemainsSeparateAndGated() throws Exception {
        Character character = character("rosetta");
        User user = user();
        ChatRoom room = room(user, character, ChatMode.STORY, false);
        String normal = assembler(false).assembleSystemPrompt(character, room, user, "", false).outputFormat();
        String automatic = assembler(true).assembleSystemPrompt(character, room, user, "", false).outputFormat();
        assertThat(automatic).startsWith(normal).contains("Scene Illustration (ADDITIONAL fields", "HARD LIMIT: at most 2 people", "SHARED interaction tags only");
        assertEquals(example(normal), example(automatic));
        assertThat(normal).doesNotContain("Scene Illustration (ADDITIONAL fields");
    }

    @Test
    void existingHistoryKeepsNarrationSyntheticEntryAndMixedUserTextInTheirRoles() throws Exception {
        Character character = character("rosetta");
        User user = user();
        var payload = assembler(false).assembleSystemPrompt(character, room(user, character, ChatMode.SANDBOX, false), user, "", false);
        String mixed = "*탁자에 책을 내려놓는다.* (입장해도 될까?) 아직 의자에는 앉지 않았어.";
        List<ChatLogDocument> history = List.of(log(ChatRole.SYSTEM, "정원에 바람이 분다.", 0),
            log(ChatRole.ASSISTANT, example(payload.outputFormat()).toString(), 1), log(ChatRole.USER, mixed, 2));
        List<OpenAiMessage> messages = history(history, payload, character.getName());
        assertEquals(List.of("system", "system", "user", "assistant", "user", "system", "system"), messages.stream().map(OpenAiMessage::role).toList());
        assertEquals("[NARRATION] 정원에 바람이 분다.", messages.get(1).content());
        assertEquals("(입장)", messages.get(2).content());
        assertEquals(mixed, messages.get(4).content());
        assertEquals(Map.of("type", "ephemeral"), messages.get(0).cache_control());
        assertEquals(payload.dynamicRules(), messages.get(5).content());
        assertEquals(payload.outputFormat(), messages.get(6).content());

        List<OpenAiMessage> userFirst = history(List.of(log(ChatRole.USER, "(먼저 말을 걸까?)", 0),
            log(ChatRole.ASSISTANT, example(payload.outputFormat()).toString(), 1), log(ChatRole.USER, "(입장)", 2)), payload, character.getName());
        assertEquals(List.of("(먼저 말을 걸까?)", "(입장)"), userFirst.stream().filter(m -> m.role().equals("user")).map(OpenAiMessage::content).toList());
    }

    private static JsonNode example(String output) throws Exception {
        String open = "```json\n";
        int start = output.indexOf(open);
        int end = output.indexOf("\n```", start + open.length());
        assertTrue(start >= 0 && end > start, "valid JSON example boundaries must exist");
        String json = output.substring(start + open.length(), end);
        assertThrows(Exception.class, () -> JSON.readTree(json + " unexpected"));
        return JSON.readTree(json);
    }

    private static CharacterPromptAssembler assembler(boolean auto) {
        return new CharacterPromptAssembler(new PromptInjectionGuard(), mock(WorldRepository.class),
            mock(UgcWorldRepository.class), mock(UgcWorldLocationRepository.class),
            new SceneIllustrationProperties(auto, auto ? "auto" : "manual", null, null, null, null), new LegacyFeatureProperties());
    }

    private static Character character(String slug) {
        try {
            var sources = new YamlPropertySourceLoader().load("characters", new ClassPathResource("application-characters.yml"));
            var config = new Binder(ConfigurationPropertySources.from(sources)).bind("app", CharacterSeedProperties.class).get();
            var seed = config.characters().stream().filter(c -> c.slug().equals(slug)).findFirst().orElseThrow();
            Character character = new Character(seed.name(), seed.slug(), seed.baseSystemPrompt(), seed.llmModelName());
            character.applySeed(seed);
            ReflectionTestUtils.setField(character, "id", 784L);
            return character;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static User user() {
        try {
            var constructor = User.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            User user = constructor.newInstance();
            ReflectionTestUtils.setField(user, "id", 120L);
            ReflectionTestUtils.setField(user, "nickname", "테스터");
            return user;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static ChatRoom room(User user, Character character, ChatMode mode, boolean event) {
        // New STORY creation uses V2; populate the legacy V1 shape explicitly for its preserved contract.
        ChatRoom room = new ChatRoom(user, character, ChatMode.SANDBOX);
        ReflectionTestUtils.setField(room, "chatMode", mode);
        ReflectionTestUtils.setField(room, "id", 350L);
        ReflectionTestUtils.setField(room, "eventActive", event);
        return room;
    }

    private static ChatLogDocument log(ChatRole role, String raw, int order) {
        return ChatLogDocument.builder().roomId(350L).role(role).rawContent(raw).cleanContent(raw)
            .createdAt(LocalDateTime.of(2026, 9, 18, 12, 0).plusSeconds(order)).build();
    }

    @SuppressWarnings("unchecked")
    private static List<OpenAiMessage> history(List<ChatLogDocument> records,
            CharacterPromptAssembler.SystemPromptPayload prompt, String name) {
        ChatLogMongoRepository repository = mock(ChatLogMongoRepository.class);
        when(repository.findTop20ByRoomIdOrderByCreatedAtDesc(anyLong())).thenAnswer(invocation -> new ArrayList<>(records));
        ChatStreamService service = mock(ChatStreamService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "chatLogRepository", repository);
        ReflectionTestUtils.setField(service, "objectMapper", JSON);
        return ReflectionTestUtils.invokeMethod(service, "buildMessageHistory", 350L, prompt, name, "테스터");
    }
}
