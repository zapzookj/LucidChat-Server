package com.spring.aichat.service.prompt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.RelationPromotionEligibilityRepository;
import com.spring.aichat.domain.heroine.CharacterPresenceRepository;
import com.spring.aichat.domain.heroine.ChatRoomHeroine;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import com.spring.aichat.domain.memory.HeroineMemorySummaryRepository;
import com.spring.aichat.domain.notification.OffscreenNotificationRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.dto.chat.AiJsonOutputV2;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.story.WorldView;
import com.spring.aichat.service.story.WorldViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Prompt contracts: source facts, persona boundaries, output examples, and existing game rules. */
class StoryDirectorPromptAssemblerContractTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final long ROOM_ID = 8123L;
    private final ChatRoomHeroineRepository heroines = mock(ChatRoomHeroineRepository.class);
    private final CharacterPresenceRepository presence = mock(CharacterPresenceRepository.class);
    private final RelationPromotionEligibilityRepository promotions = mock(RelationPromotionEligibilityRepository.class);
    private final OffscreenNotificationRepository notifications = mock(OffscreenNotificationRepository.class);
    private final HeroineMemorySummaryRepository memories = mock(HeroineMemorySummaryRepository.class);
    private final WorldViewService worlds = mock(WorldViewService.class);
    private final ChatRoom room = mock(ChatRoom.class);
    private final User user = mock(User.class);
    private final StoryDirectorPromptAssemblerV2 assembler = new StoryDirectorPromptAssemblerV2(
        heroines, presence, promotions, notifications, worlds, memories, new PromptInjectionGuard());

    @BeforeEach
    void prepareFixture() {
        when(room.isStoryMode()).thenReturn(true);
        when(room.getId()).thenReturn(ROOM_ID);
        when(room.getCurrentUserLocationKey()).thenReturn("UGCW_8123__CUSTOM_PLACE");
        when(room.getEffectiveNickname(user)).thenReturn("하늘");
        when(room.getEffectivePersona(user)).thenReturn("낯선 장소를 살피는 성인 방문객.");
        var world = mock(WorldView.class);
        when(worlds.resolveForRoom(room)).thenReturn(world);
        when(world.locations()).thenReturn(List.of(new WorldView.LocationView(
            "UGCW_8123__CUSTOM_PLACE", "유리 \"회랑\"", "나무 바닥의 회랑.", true, null)));
        when(world.isUgc()).thenReturn(true);
        when(world.displayName()).thenReturn("인용 \"세계\"");
        when(world.description()).thenReturn("독자적인 세계 설정.");
        when(world.tagline()).thenReturn("검증 세계");
        when(world.moodKeywords()).thenReturn("고요함");
        when(heroines.findByChatRoom_Id(ROOM_ID)).thenReturn(List.of(heroine(character(982L, "이안 \"라온\""))));
        when(presence.findByChatRoom_Id(ROOM_ID)).thenReturn(List.of());
        when(promotions.findByChatRoomIdAndTriggeredFalse(ROOM_ID)).thenReturn(List.of());
        when(notifications.findByChatRoom_IdAndRespondedAtIsNullAndExpiresAtAfterOrderBySentAtAsc(eq(ROOM_ID), any())).thenReturn(List.of());
        when(memories.findByRoomIdAndCharacterIdOrderByCreatedAtAsc(eq(ROOM_ID), anyLong())).thenReturn(List.of());
    }

    @Test
    void includesOfficialAppearanceAndClothingOnceWithoutDuplicatingBackstory() {
        Character c = character(982L, "이안 \"라온\"");
        fields(c, "appearance", "회색 눈과 짧은 \"은빛\" 머리.", "clothing", "남색 망토.");
        when(heroines.findByChatRoom_Id(ROOM_ID)).thenReturn(List.of(heroine(c)));
        String prompt = assemble(false, false, 982L).staticPart();
        assertThat(prompt).contains("### Appearance (공식 외형)", "회색 눈과 짧은 \"은빛\" 머리.",
            "### Default Clothing (기본 복장)", "남색 망토.", "현재 장면에서 이미 확정된 복장");
        assertThat(count(prompt, "BACKSTORY-982")).isEqualTo(1);
        assertThat(prompt).doesNotContain("### Extended Backstory");
        assertThat(prompt).contains("이안 \"라온\" (Character ID: 982)", "CORE-982", "FLAW-982", "BEHAVIOR-982", "SPEECH-982");
    }

    @Test
    void omitsNullAndBlankAppearanceFields() {
        Character first = character(982L, "첫 인물"), second = character(1207L, "다른 인물");
        fields(first, "appearance", null, "clothing", " \n ");
        fields(second, "appearance", "\t", "clothing", null);
        when(heroines.findByChatRoom_Id(ROOM_ID)).thenReturn(List.of(heroine(first), heroine(second)));
        String prompt = assemble(false, false, 982L).staticPart();
        assertThat(prompt).doesNotContain("### Appearance", "### Default Clothing");
        assertThat(count(prompt, "BACKSTORY-982")).isEqualTo(1);
        assertThat(count(prompt, "BACKSTORY-1207")).isEqualTo(1);
    }

    @Test
    void preservesEffectiveSecretPersonalityToneAndBlankFallback() {
        Character c = character(982L, "정의 검증 인물");
        fields(c, "personalitySecret", "SECRET-PERSONALITY", "toneSecret", "SECRET-TONE");
        when(heroines.findByChatRoom_Id(ROOM_ID)).thenReturn(List.of(heroine(c)));
        String normal = assemble(false, false, 982L).staticPart();
        String secret = assemble(true, false, 982L).staticPart();
        assertThat(normal).contains("NORMAL-PERSONALITY-982", "NORMAL-TONE-982").doesNotContain("SECRET-PERSONALITY", "SECRET-TONE");
        assertThat(secret).contains("SECRET-PERSONALITY", "SECRET-TONE").doesNotContain("NORMAL-PERSONALITY-982", "NORMAL-TONE-982");
        fields(c, "personalitySecret", " ", "toneSecret", null);
        String fallback = assemble(true, false, 982L).staticPart();
        assertThat(fallback).contains("NORMAL-PERSONALITY-982", "NORMAL-TONE-982");
    }

    @Test
    void normalAndSecretExamplesAreStrictDtoCompatibleJsonWithScenesFirst() throws Exception {
        for (boolean secret : List.of(false, true)) {
            String output = output(assemble(secret, false, 982L));
            String raw = example(output);
            JsonNode root = JSON.readTree(raw);
            AiJsonOutputV2 dto = JSON.readValue(raw, AiJsonOutputV2.class);
            assertThat(root.fieldNames().next()).isEqualTo("scenes");
            assertThat(dto.sceneCount()).isBetween(3, 5);
            assertThat(root.path("system_updates").path("topic_concluded").isBoolean()).isTrue();
            assertThat(root.path("system_updates").path("stat_changes").isObject()).isTrue();
            for (var scene : dto.scenes()) {
                assertThat(scene.speaker()).isNull();
                assertThat(scene.dialogue()).isEmpty();
                assertThat(scene.locationChange()).isNull();
            }
            assertThat(raw).doesNotContain("982", "1207", "47", "로제타", "UGCW_8123", "이안", "유리");
            assertThat(output).contains("현재 장소·시간·화자·인물의 출석·감정·스탯을 지정하지 않는다",
                "등장하거나 대사한 히로인의 숫자 ID와 필수 스탯", "아래 빈 stat_changes를 그대로 복사하지 않는다");
            assertThat(output).contains(secret
                ? "intimacy, affection, dependency, playfulness, trust, lust, corruption, obsession — 8종 모두 명시."
                : "intimacy, affection, dependency, playfulness, trust — 5종 모두 명시. lust, corruption, obsession은 출력하지 않는다.");
        }
    }

    @Test
    void preservesEveryOriginalFieldDescriptionAndCriticalRule() throws Exception {
        String before = resource("/prompts/story-output-contract-before.txt");
        String after = output(assemble(false, false, 982L));
        assertThat(after.substring(after.indexOf("**Critical Rules**:")))
            .isEqualTo(before.substring(before.indexOf("**Critical Rules**:")));
        String sourceSchema = example(before);
        String descriptions = after.substring(after.indexOf("## 필드별 원계약 설명"), after.indexOf("**Critical Rules**:"));
        var fields = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:\\s*(.*)$", Pattern.MULTILINE).matcher(sourceSchema);
        int described = 0;
        while (fields.find()) {
            assertThat(descriptions).contains(fields.group(1));
            assertThat(descriptions).contains(fields.group(2));
            described++;
        }
        assertThat(described).isGreaterThan(25);
        for (String line : sourceSchema.lines().map(String::strip).toList()) {
            if (line.startsWith("{ \"")) assertThat(descriptions).contains(line);
        }
        assertThat(descriptions).contains("new_dynamic_location", "illustration_scene_hint", "user_impressions", "narrative_threads",
            "incoming_messages", "relation_transition", "character_movements", "NEUTRAL | JOY", "OPEN|ADVANCED|RESOLVED");
    }

    @Test
    void ambientAndOpeningDoNotInstructInventingTheUsersInterior() {
        var normal = assemble(false, false, null);
        var opening = assemble(false, true, null);
        assertThat(normal.staticPart()).doesNotContain("the protagonist's own thoughts", "풍경·시간·사건·내면", "단 유저 페르소나의 *외적 반응*은 묘사 가능");
        assertThat(normal.staticPart()).contains("`*...*` 구간은 명시된 행동·상황", "미제시 외적 반응을 임의로 생성하지 않는다");
        assertThat(normal.dynamicPart()).contains("관찰 가능한 주변의 변화", "내적 독백·감정·감각·기억을 대신 만들지 마라")
            .doesNotContain("내적 독백·감각·기억의 결", "세계와 유저의 내면");
        assertThat(opening.dynamicPart()).contains("씬은 1~2개로 충분하다", "stat_changes / time_advance / ending / promotion 은 *모두 비워라*", "내적 독백·감정·기억을 대신 만들지 마라")
            .doesNotContain("유저 페르소나의 내적 독백만으로");
        assertThat(output(opening)).contains("[OPENING]이 있으면 해당 1~2개·상태 변경 금지 규칙");
        assertThat(normal.staticPart()).isEqualTo(opening.staticPart());
    }

    @Test
    void examplesStayIndependentOfCurrentSpeakerAndKeepLoadedNumericIds() {
        var first = heroine(character(982L, "따옴표 \"인물\""));
        var second = heroine(character(1207L, "역슬래시 \\ 인물"));
        when(heroines.findByChatRoom_Id(ROOM_ID)).thenReturn(List.of(first, second));
        var one = assemble(false, false, 982L);
        var two = assemble(false, false, 1207L);
        var ambient = assemble(false, false, null);
        assertThat(one.staticPart()).isEqualTo(two.staticPart()).isEqualTo(ambient.staticPart());
        assertThat(one.staticPart()).contains("Character ID: 982", "Character ID: 1207", "숫자 ID", "등장한 모든 히로인");
        assertThat(one.dynamicPart()).contains("이번 턴 화자: **따옴표 \"인물\"** (Character ID: 982)");
        assertThat(two.dynamicPart()).contains("이번 턴 화자: **역슬래시 \\ 인물** (Character ID: 1207)");
    }

    @Test
    void usesAlreadyLoadedDefinitionsAndExistingReadQueriesOnly() {
        assemble(false, false, 982L);
        verify(heroines).findByChatRoom_Id(ROOM_ID);
        verify(presence).findByChatRoom_Id(ROOM_ID);
        verify(promotions).findByChatRoomIdAndTriggeredFalse(ROOM_ID);
        verify(notifications).findByChatRoom_IdAndRespondedAtIsNullAndExpiresAtAfterOrderBySentAtAsc(eq(ROOM_ID), any());
        verify(worlds).resolveForRoom(room);
        verify(memories).findByRoomIdAndCharacterIdOrderByCreatedAtAsc(ROOM_ID, 982L);
        verifyNoMoreInteractions(heroines, presence, promotions, notifications, worlds, memories);
    }

    private StoryDirectorPromptAssemblerV2.SystemPromptPayload assemble(boolean secret, boolean opening, Long speaker) {
        return assembler.assemble(room, user, speaker, "", secret, opening, List.of());
    }
    private static String output(StoryDirectorPromptAssemblerV2.SystemPromptPayload payload) {
        String s = payload.staticPart();
        return s.substring(s.indexOf("# [10] OUTPUT FORMAT — JSON\n"));
    }
    private static String example(String output) {
        int start = output.indexOf("```json\n") + "```json\n".length();
        return output.substring(start, output.indexOf("\n```", start));
    }
    private static Character character(long id, String name) {
        Character c = new Character(name, "contract-" + id, "", "unused-test-model");
        fields(c, "id", id, "age", 27, "role", "연구원", "personality", "NORMAL-PERSONALITY-" + id,
            "tone", "NORMAL-TONE-" + id, "backstory", "BACKSTORY-" + id, "coreValues", "CORE-" + id,
            "flaws", "FLAW-" + id, "storyBehaviorGuide", "BEHAVIOR-" + id, "speechQuirks", "SPEECH-" + id);
        return c;
    }
    private static ChatRoomHeroine heroine(Character c) {
        var h = new ChatRoomHeroine();
        fields(h, "character", c);
        return h;
    }
    private static void fields(Object target, Object... values) {
        for (int i = 0; i < values.length; i += 2) ReflectionTestUtils.setField(target, (String) values[i], values[i + 1]);
    }
    private static int count(String haystack, String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }
    private static String resource(String path) throws IOException {
        try (var stream = StoryDirectorPromptAssemblerContractTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing test contract " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
