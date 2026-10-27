package com.spring.aichat.service.story;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.RelationStatus;
import com.spring.aichat.domain.heroine.ChatRoomHeroine;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import com.spring.aichat.domain.memory.MemorySummary;
import com.spring.aichat.domain.memory.MemorySummaryRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.dto.story.StoryV2Requests.CarryOverRequest;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.external.OpenRouterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * [docs/55 Carry-Over] 자유 모드 관계 이식 — 검증 규칙과 적용 결과(스탯 스냅샷·로그 복사·기억·브리지·이식 시각).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoryCarryOverServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final Long CHAR_ID = 5L;
    private static final Long SANDBOX_ROOM_ID = 42L;
    private static final Long STORY_ROOM_ID = 100L;

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private ChatRoomHeroineRepository heroineRepository;
    @Mock private ChatLogMongoRepository chatLogRepository;
    @Mock private MemorySummaryRepository memorySummaryRepository;
    @Mock private HeroineMemoryService heroineMemoryService;
    @Mock private OpenRouterClient openRouterClient;
    @Mock private OpenAiProperties props;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private StoryCarryOverService service;

    private User user;
    private Character character;
    private ChatRoom sandbox;
    private ChatRoom story;
    private ChatRoomHeroine heroine;

    @BeforeEach
    void setUp() {
        user = User.local("tester", "pw", "닉", "t@test.com");
        ReflectionTestUtils.setField(user, "id", USER_ID);

        character = new Character("아이리", "airi", "prompt", "model");
        ReflectionTestUtils.setField(character, "id", CHAR_ID);

        sandbox = new ChatRoom(user, character, ChatMode.SANDBOX);
        ReflectionTestUtils.setField(sandbox, "id", SANDBOX_ROOM_ID);
        sandbox.applyNormalStatChanges(30, 54, 10, 20, 40);
        sandbox.applySecretStatChanges(5, 0, 0);
        sandbox.updateStatusLevel(RelationStatus.FRIEND);
        sandbox.updateDynamicRelationTag("썸");
        sandbox.updateCharacterThought("오늘도 그 사람 생각", 3);

        story = ChatRoom.createStoryV2Ugc(user, 1L, "START", null, null);
        ReflectionTestUtils.setField(story, "id", STORY_ROOM_ID);
        heroine = ChatRoomHeroine.create(story, character);

        when(props.sentimentModel()).thenReturn("test-model");
        when(chatRoomRepository.findById(SANDBOX_ROOM_ID)).thenReturn(Optional.of(sandbox));
        when(chatRoomRepository.findById(STORY_ROOM_ID)).thenReturn(Optional.of(story));
        when(heroineRepository.findByChatRoom_IdAndCharacter_Id(STORY_ROOM_ID, CHAR_ID)).thenReturn(Optional.of(heroine));
        when(chatLogRepository.findTop200ByRoomIdAndHiddenNotOrderByCreatedAtDesc(anyLong(), eq(true))).thenReturn(List.of());
        when(chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of());
        when(memorySummaryRepository.findByRoomIdOrderByCreatedAtAsc(anyLong())).thenReturn(List.of());
    }

    // ━━━━━━━━━━ validate ━━━━━━━━━━

    @Test
    @DisplayName("validate: 내 SANDBOX 방 + 캐스트에 포함된 캐릭터면 통과, includeLogs 기본 true")
    void validate_ok() {
        List<StoryCarryOverService.Plan> plans = service.validate(user, List.of(CHAR_ID, 9L),
            List.of(new CarryOverRequest(CHAR_ID, SANDBOX_ROOM_ID, null)));
        assertEquals(1, plans.size());
        assertSame(sandbox, plans.get(0).sandboxRoom());
        assertTrue(plans.get(0).includeLogs());

        List<StoryCarryOverService.Plan> noLogs = service.validate(user, List.of(CHAR_ID),
            List.of(new CarryOverRequest(CHAR_ID, SANDBOX_ROOM_ID, false)));
        assertFalse(noLogs.get(0).includeLogs());
    }

    @Test
    @DisplayName("validate: 요청이 없으면 빈 계획 — 방 생성은 영향 없음")
    void validate_empty() {
        assertTrue(service.validate(user, List.of(CHAR_ID), null).isEmpty());
        assertTrue(service.validate(user, List.of(CHAR_ID), List.of()).isEmpty());
    }

    @Test
    @DisplayName("validate: 다른 유저의 방은 403")
    void validate_rejectsOtherUsersRoom() {
        User other = User.local("other", "pw", "o", "o@test.com");
        ReflectionTestUtils.setField(other, "id", OTHER_USER_ID);
        assertThrows(BusinessException.class, () -> service.validate(other, List.of(CHAR_ID),
            List.of(new CarryOverRequest(CHAR_ID, SANDBOX_ROOM_ID, null))));
    }

    @Test
    @DisplayName("validate: STORY 방을 원본으로 지정하면 400")
    void validate_rejectsNonSandboxRoom() {
        assertThrows(BadRequestException.class, () -> service.validate(user, List.of(CHAR_ID),
            List.of(new CarryOverRequest(CHAR_ID, STORY_ROOM_ID, null))));
    }

    @Test
    @DisplayName("validate: 원본 방의 캐릭터와 다른 characterId면 400")
    void validate_rejectsCharacterMismatch() {
        assertThrows(BadRequestException.class, () -> service.validate(user, List.of(6L),
            List.of(new CarryOverRequest(6L, SANDBOX_ROOM_ID, null))));
    }

    @Test
    @DisplayName("validate: 이번 캐스트(heroineIds)에 없는 캐릭터면 400")
    void validate_rejectsCharacterNotInCast() {
        assertThrows(BadRequestException.class, () -> service.validate(user, List.of(9L),
            List.of(new CarryOverRequest(CHAR_ID, SANDBOX_ROOM_ID, null))));
    }

    // ━━━━━━━━━━ apply ━━━━━━━━━━

    @Test
    @DisplayName("apply: 스탯 100% 스냅샷 + 로그 복사(hidden 제외·createdAt 보존·importedFromRoomId) + 재서술 기억 + 브리지 + 이식 시각")
    void apply_seedsStatsCopiesLogsImportsMemoriesAndBridges() {
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 1, 10, 0);
        LocalDateTime t2 = t1.plusMinutes(1);
        LocalDateTime t3 = t1.plusMinutes(2);
        ChatLogDocument l1 = ChatLogDocument.builder().id("a").roomId(SANDBOX_ROOM_ID).role(ChatRole.USER)
            .rawContent("안녕").cleanContent("안녕").createdAt(t1).build();
        ChatLogDocument l2 = ChatLogDocument.builder().id("b").roomId(SANDBOX_ROOM_ID).role(ChatRole.ASSISTANT)
            .rawContent("raw").cleanContent("반가워").scenesJson("[{\"speaker\":\"아이리\"}]").createdAt(t2).build();
        ChatLogDocument hiddenLog = ChatLogDocument.hiddenSystem(SANDBOX_ROOM_ID, "[SYSTEM_DIRECTOR]");
        ReflectionTestUtils.setField(hiddenLog, "createdAt", t3);
        when(chatLogRepository.findTop200ByRoomIdAndHiddenNotOrderByCreatedAtDesc(SANDBOX_ROOM_ID, true))
            .thenReturn(List.of(hiddenLog, l2, l1));   // 최신순 (hidden은 질의가 거르지만 방어 필터도 검증)

        MemorySummary s1 = mock(MemorySummary.class);
        when(s1.getSummary()).thenReturn("유저는 커피를 좋아한다. 둘은 카페에서 자주 만났다.");
        when(memorySummaryRepository.findByRoomIdOrderByCreatedAtAsc(SANDBOX_ROOM_ID)).thenReturn(List.of(s1));
        when(openRouterClient.chatCompletion(any()))
            .thenReturn("```json\n{\"memories\": [\"그 사람은 따뜻한 음료를 좋아했다. 자주 가던 곳에서 함께 웃었다.\"], \"bridge\": \"함께한 시간을 안고, 이야기는 이 세계에서 이어진다.\"}\n```");

        List<StoryCarryOverService.Plan> plans = service.validate(user, List.of(CHAR_ID),
            List.of(new CarryOverRequest(CHAR_ID, SANDBOX_ROOM_ID, null)));
        service.apply(story, plans);

        // ① 스탯 스냅샷
        assertEquals(30, heroine.getStatIntimacy());
        assertEquals(54, heroine.getStatAffection());
        assertEquals(10, heroine.getStatDependency());
        assertEquals(20, heroine.getStatPlayfulness());
        assertEquals(40, heroine.getStatTrust());
        assertEquals(5, heroine.getStatLust());
        assertEquals(RelationStatus.FRIEND, heroine.getStatusLevel());
        assertEquals("썸", heroine.getDynamicRelationTag());
        assertEquals("오늘도 그 사람 생각", heroine.getCharacterThought());
        assertEquals(SANDBOX_ROOM_ID, heroine.getCarriedFromRoomId());
        assertTrue(heroine.isCarriedOver());

        // ② 로그 복사 — hidden 제외, 오래된 순, createdAt 보존, id 선지정, 출처 표기
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatLogDocument>> copies = ArgumentCaptor.forClass(List.class);
        verify(chatLogRepository).saveAll(copies.capture());
        List<ChatLogDocument> saved = copies.getValue();
        assertEquals(2, saved.size());
        assertEquals(t1, saved.get(0).getCreatedAt());
        assertEquals(t2, saved.get(1).getCreatedAt());
        for (ChatLogDocument d : saved) {
            assertEquals(STORY_ROOM_ID, d.getRoomId());
            assertEquals(SANDBOX_ROOM_ID, d.getImportedFromRoomId());
            assertTrue(d.isImported());
            assertNotNull(d.getId(), "id 선지정 — 감사(@CreatedDate)가 createdAt을 덮지 않도록");
            assertFalse(d.isHidden());
        }
        assertEquals("[{\"speaker\":\"아이리\"}]", saved.get(1).getScenesJson());

        // ③ 재서술 기억
        verify(heroineMemoryService).importSummaries(eq(STORY_ROOM_ID), eq(CHAR_ID), eq(USER_ID),
            eq(List.of("그 사람은 따뜻한 음료를 좋아했다. 자주 가던 곳에서 함께 웃었다.")));

        // ④ 브리지 — 마지막 이식 로그 + 1초, 이식 배치로 표기
        ArgumentCaptor<ChatLogDocument> bridge = ArgumentCaptor.forClass(ChatLogDocument.class);
        verify(chatLogRepository).save(bridge.capture());
        assertEquals(ChatRole.SYSTEM, bridge.getValue().getRole());
        assertEquals("함께한 시간을 안고, 이야기는 이 세계에서 이어진다.", bridge.getValue().getCleanContent());
        assertEquals(t2.plusSeconds(1), bridge.getValue().getCreatedAt());
        assertEquals(SANDBOX_ROOM_ID, bridge.getValue().getImportedFromRoomId());

        // ⑤ 이식 시각
        assertNotNull(story.getCarryOverAt());
    }

    @Test
    @DisplayName("apply: 재서술 LLM 실패 → 원문 요약 그대로 이식 + 기본 브리지(와/과 조사)")
    void apply_fallsBackWhenLlmFails() {
        MemorySummary s1 = mock(MemorySummary.class);
        when(s1.getSummary()).thenReturn("원문 요약 1");
        when(memorySummaryRepository.findByRoomIdOrderByCreatedAtAsc(SANDBOX_ROOM_ID)).thenReturn(List.of(s1));
        when(openRouterClient.chatCompletion(any())).thenThrow(new RuntimeException("boom"));

        service.apply(story, service.validate(user, List.of(CHAR_ID),
            List.of(new CarryOverRequest(CHAR_ID, SANDBOX_ROOM_ID, false))));

        verify(heroineMemoryService).importSummaries(eq(STORY_ROOM_ID), eq(CHAR_ID), eq(USER_ID), eq(List.of("원문 요약 1")));
        verify(chatLogRepository, never()).saveAll(any());   // includeLogs=false
        ArgumentCaptor<ChatLogDocument> bridge = ArgumentCaptor.forClass(ChatLogDocument.class);
        verify(chatLogRepository).save(bridge.capture());
        assertTrue(bridge.getValue().getCleanContent().contains("아이리와"), bridge.getValue().getCleanContent());
        assertNotNull(story.getCarryOverAt());
    }

    @Test
    @DisplayName("apply: 복수 히로인 이식 — 브리지는 두 방의 이식 로그 전체 뒤에, 계획 순서로 1초 간격 (독립 검토 #1)")
    void apply_multiHeroine_bridgesAfterAllImportedLogs() {
        // 두 번째 히로인 + 그 자유 방 (더 최근까지 대화)
        Character other = new Character("에델", "edel", "prompt", "model");
        ReflectionTestUtils.setField(other, "id", 6L);
        ChatRoom sandbox2 = new ChatRoom(user, other, ChatMode.SANDBOX);
        ReflectionTestUtils.setField(sandbox2, "id", 43L);
        ChatRoomHeroine heroine2 = ChatRoomHeroine.create(story, other);
        when(chatRoomRepository.findById(43L)).thenReturn(Optional.of(sandbox2));
        when(heroineRepository.findByChatRoom_IdAndCharacter_Id(STORY_ROOM_ID, 6L)).thenReturn(Optional.of(heroine2));

        LocalDateTime a1 = LocalDateTime.of(2026, 10, 10, 10, 0);   // 아이리 방: 10/10
        LocalDateTime b1 = LocalDateTime.of(2026, 10, 27, 10, 0);   // 에델 방: 10/27 (더 최근)
        when(chatLogRepository.findTop200ByRoomIdAndHiddenNotOrderByCreatedAtDesc(SANDBOX_ROOM_ID, true))
            .thenReturn(List.of(ChatLogDocument.builder().id("a").roomId(SANDBOX_ROOM_ID).role(ChatRole.USER).rawContent("a").cleanContent("a").createdAt(a1).build()));
        when(chatLogRepository.findTop200ByRoomIdAndHiddenNotOrderByCreatedAtDesc(43L, true))
            .thenReturn(List.of(ChatLogDocument.builder().id("b").roomId(43L).role(ChatRole.USER).rawContent("b").cleanContent("b").createdAt(b1).build()));
        when(openRouterClient.chatCompletion(any())).thenThrow(new RuntimeException("no llm"));

        service.apply(story, service.validate(user, List.of(CHAR_ID, 6L), List.of(
            new CarryOverRequest(CHAR_ID, SANDBOX_ROOM_ID, null),
            new CarryOverRequest(6L, 43L, null))));

        ArgumentCaptor<ChatLogDocument> bridge = ArgumentCaptor.forClass(ChatLogDocument.class);
        verify(chatLogRepository, times(2)).save(bridge.capture());
        List<ChatLogDocument> bridges = bridge.getAllValues();
        // 아이리 브리지도 에델 방의 마지막 로그(10/27) 뒤에 온다 — 10/10 + 1초가 아니라
        assertEquals(b1.plusSeconds(1), bridges.get(0).getCreatedAt());
        assertEquals(SANDBOX_ROOM_ID, bridges.get(0).getImportedFromRoomId());
        assertEquals(b1.plusSeconds(2), bridges.get(1).getCreatedAt());
        assertEquals(43L, bridges.get(1).getImportedFromRoomId());
        assertTrue(bridges.get(1).getCleanContent().contains("에델과"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatLogDocument>> copies = ArgumentCaptor.forClass(List.class);
        verify(chatLogRepository).saveAll(copies.capture());   // 두 방의 복사본이 한 번에
        assertEquals(2, copies.getValue().size());
        assertEquals(43L, heroine2.getCarriedFromRoomId());
    }

    @Test
    @DisplayName("apply: 계획이 비면 아무것도 건드리지 않는다 (carryOverAt null 유지)")
    void apply_noop() {
        service.apply(story, List.of());
        verifyNoInteractions(heroineRepository, chatLogRepository, heroineMemoryService, openRouterClient);
        assertNull(story.getCarryOverAt());
    }

    // ━━━━━━━━━━ 유틸 ━━━━━━━━━━

    @Test
    @DisplayName("parseNarration: 코드펜스·잡문을 걷어내고 JSON만 읽는다, 깨진 입력은 null")
    void parseNarration() {
        StoryCarryOverService.Narration n = service.parseNarration("여기요:\n```json\n{\"memories\":[\"m1\",\"\",\"m2\"],\"bridge\":\" b \"}\n```");
        assertNotNull(n);
        assertEquals(List.of("m1", "m2"), n.memories());
        assertEquals("b", n.bridge());
        assertTrue(n.fromLlm());
        assertNull(service.parseNarration("not json"));
        assertNull(service.parseNarration(null));
    }

    @Test
    @DisplayName("josaWaGwa: 받침 유무로 와/과")
    void josa() {
        assertEquals("와", StoryCarryOverService.josaWaGwa("아이리"));
        assertEquals("과", StoryCarryOverService.josaWaGwa("에델"));
        assertEquals("과", StoryCarryOverService.josaWaGwa("채린"));
        assertEquals("와", StoryCarryOverService.josaWaGwa("Luna"));
    }
}
