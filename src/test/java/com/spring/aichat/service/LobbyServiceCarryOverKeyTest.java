package com.spring.aichat.service;

import com.spring.aichat.config.UgcModeProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.ugc.UgcWorld;
import com.spring.aichat.domain.ugc.UgcWorldRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.lobby.RoomSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [docs/55 Carry-Over · UGC 확장] 자유 방 목록의 {@code carryOverWorldKey} 자격 계산 —
 * 공식 캐릭터는 enum name, UGC 캐릭터는 {@code UGCW_{id}}(게이트 on + 내 월드 또는 APPROVED), 그 외 null.
 */
class LobbyServiceCarryOverKeyTest {

    private static final long USER_ID = 7L;

    private final CharacterRepository characterRepository = mock(CharacterRepository.class);
    private final ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final UgcWorldRepository ugcWorldRepository = mock(UgcWorldRepository.class);

    private LobbyService service(boolean ugcStoryOn) {
        return new LobbyService(characterRepository, chatRoomRepository, userRepository, null, null,
            ugcWorldRepository, new UgcModeProperties(ugcStoryOn, false, false), null);
    }

    private static User user() {
        User u = User.local("tester", "pw", "닉", "t@test.com");
        ReflectionTestUtils.setField(u, "id", USER_ID);
        return u;
    }

    private static Character official(long id, WorldId worldId) {
        Character c = new Character("아이리", "airi", "prompt", "model");
        ReflectionTestUtils.setField(c, "id", id);
        ReflectionTestUtils.setField(c, "worldId", worldId);
        return c;
    }

    private static Character ugc(long id, Long ownerId, Long ugcWorldId) {
        Character c = Character.createUgc(new Character.UgcCharacterSpec(
            ownerId, "유지민", "ugc-" + id, "system", "model",
            "tagline", "desc", "role", null, "personality", "tone",
            "appearance", "clothing", "backstory", "core", "flaws", "quirks",
            "greeting", "intro", "http://img", "http://thumb", "DEFAULT",
            null, ugcWorldId, "160cm", "likes", "dislikes", "hobby", "무드", "quote",
            "pink hair", "kuudere", null));
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private static UgcWorld world(long id, long ownerId, boolean approved) {
        UgcWorld w = UgcWorld.create(ownerId, "세계" + id, "intro", "lore", "mood", null);
        ReflectionTestUtils.setField(w, "id", id);
        if (approved) w.approve("ok");
        return w;
    }

    private static ChatRoom sandbox(long id, User user, Character c) {
        ChatRoom r = new ChatRoom(user, c, ChatMode.SANDBOX);
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    private Map<Long, String> keysByRoom(boolean ugcStoryOn, User user, List<ChatRoom> rooms) {
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(user));
        when(chatRoomRepository.findAllByUser_IdOrderByLastActiveAtDesc(USER_ID)).thenReturn(rooms);
        return service(ugcStoryOn).getMyRooms("tester").stream()
            .collect(Collectors.toMap(RoomSummaryResponse::roomId, r -> r.carryOverWorldKey() == null ? "" : r.carryOverWorldKey(), (a, b) -> a));
    }

    @Test
    @DisplayName("공식 캐릭터 → worldId enum name / UGC 내 월드 → UGCW_{id} / 타인 미승인 월드 → null / 타인 APPROVED → UGCW_{id}")
    void resolvesKeysPerOwnershipAndApproval() {
        User user = user();
        Character airi = official(5L, WorldId.MEDIEVAL_FANTASY);
        Character mine = ugc(11L, USER_ID, 77L);
        Character foreignPending = ugc(12L, 9L, 88L);
        Character foreignApproved = ugc(13L, 9L, 99L);
        when(ugcWorldRepository.findById(77L)).thenReturn(Optional.of(world(77L, USER_ID, false)));
        when(ugcWorldRepository.findById(88L)).thenReturn(Optional.of(world(88L, 9L, false)));
        when(ugcWorldRepository.findById(99L)).thenReturn(Optional.of(world(99L, 9L, true)));

        Map<Long, String> keys = keysByRoom(true, user, List.of(
            sandbox(1L, user, airi), sandbox(2L, user, mine), sandbox(3L, user, foreignPending), sandbox(4L, user, foreignApproved)));

        assertEquals("MEDIEVAL_FANTASY", keys.get(1L));
        assertEquals("UGCW_77", keys.get(2L));
        assertEquals("", keys.get(3L), "타인의 미승인 UGC 월드는 입구를 내지 않는다");
        assertEquals("UGCW_99", keys.get(4L));
    }

    @Test
    @DisplayName("UGC 스토리 게이트 off → UGC 키 null, 공식 키는 영향 없음")
    void ugcGateOffHidesUgcKeysOnly() {
        User user = user();
        Character airi = official(5L, WorldId.MEDIEVAL_FANTASY);
        Character mine = ugc(11L, USER_ID, 77L);
        when(ugcWorldRepository.findById(77L)).thenReturn(Optional.of(world(77L, USER_ID, false)));

        Map<Long, String> keys = keysByRoom(false, user, List.of(sandbox(1L, user, airi), sandbox(2L, user, mine)));

        assertEquals("MEDIEVAL_FANTASY", keys.get(1L));
        assertEquals("", keys.get(2L));
    }

    @Test
    @DisplayName("월드 없는 UGC 캐릭터(storyAvailable=false)·숨김 캐릭터 → null")
    void unavailableCharactersYieldNull() {
        User user = user();
        Character worldless = ugc(14L, USER_ID, null);           // createUgc: ugcWorldId 없으면 storyAvailable=false
        Character hidden = official(6L, WorldId.MODERN_KOREA);
        ReflectionTestUtils.setField(hidden, "hidden", true);

        Map<Long, String> keys = keysByRoom(true, user, List.of(sandbox(1L, user, worldless), sandbox(2L, user, hidden)));

        assertEquals("", keys.get(1L));
        assertEquals("", keys.get(2L));
        assertFalse(worldless.isStoryAvailable());
        assertNotNull(Function.identity());
    }
}
