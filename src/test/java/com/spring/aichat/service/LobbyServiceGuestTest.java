package com.spring.aichat.service;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.lobby.LobbyPublicDtos;
import com.spring.aichat.exception.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [블록 A 게스트] 로비 게스트 스코프 — 접근 규칙·피드 구성·DTO 필드 계약 검증.
 */
class LobbyServiceGuestTest {

    private final CharacterRepository characterRepository = mock(CharacterRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final LobbyService lobbyService = new LobbyService(
        characterRepository, null, userRepository, null, null,
        mock(com.spring.aichat.domain.ugc.UgcWorldRepository.class), null);

    private static Character ugc(long id, String name, boolean publish, boolean hidden, Long ownerId) {
        Character c = Character.createUgc(new Character.UgcCharacterSpec(
            ownerId, name, "ugc-" + id, "system", "model",
            "tagline-" + name, "desc", "role", "personality", "tone",
            "appearance", "clothing", "backstory", "core", "flaws", "quirks",
            "greeting", "intro", "http://img", "http://thumb", "DEFAULT",
            null, null, "160cm", "likes", "dislikes", "hobby", "무드", "quote",
            "pink hair", "kuudere", null));
        ReflectionTestUtils.setField(c, "id", id);
        if (publish) {
            c.requestPublish();
            c.approvePublish("test");
        }
        if (hidden) {
            ReflectionTestUtils.setField(c, "hidden", true);
        }
        return c;
    }

    // ━━━━━━━━━━ 게스트 프로필 접근 규칙 ━━━━━━━━━━

    @Test
    @DisplayName("게스트는 PUBLIC UGC 프로필을 볼 수 있다")
    void guestSeesPublicUgcProfile() {
        Character pub = ugc(1L, "미아", true, false, 9L);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(pub));
        when(userRepository.findById(9L)).thenReturn(Optional.of(mock(User.class)));

        var profile = lobbyService.getCharacterProfileForGuest(1L);
        assertEquals("미아", profile.name());
        assertTrue(profile.ugc());
    }

    @Test
    @DisplayName("게스트는 PRIVATE UGC 프로필에 404 은닉된다")
    void guestBlockedFromPrivateUgc() {
        Character priv = ugc(2L, "유하", false, false, 9L);
        when(characterRepository.findById(2L)).thenReturn(Optional.of(priv));
        assertThrows(NotFoundException.class, () -> lobbyService.getCharacterProfileForGuest(2L));
    }

    @Test
    @DisplayName("게스트는 숨김(hidden) 캐릭터 프로필에 404 은닉된다")
    void guestBlockedFromHidden() {
        Character hidden = ugc(3L, "숨김", true, true, 9L);
        when(characterRepository.findById(3L)).thenReturn(Optional.of(hidden));
        assertThrows(NotFoundException.class, () -> lobbyService.getCharacterProfileForGuest(3L));
    }

    @Test
    @DisplayName("[리뷰 P2] 게스트 프로필은 firstGreeting/introNarration 파생 발췌를 노출하지 않는다")
    void guestProfileStripsPromptDerivedTeasers() {
        Character pub = ugc(5L, "미아", true, false, 9L); // firstGreeting='greeting', introNarration='intro'
        when(characterRepository.findById(5L)).thenReturn(Optional.of(pub));
        when(userRepository.findById(9L)).thenReturn(Optional.of(mock(User.class)));

        var p = lobbyService.getCharacterProfileForGuest(5L);
        assertNull(p.greetingExcerpt(), "firstGreeting 발췌는 게스트에 제외(부록 §3)");
        assertNull(p.introTeaser(), "introNarration 발췌는 게스트에 제외");
        // authored profileQuote('quote')는 공개 카피라 유지
        assertEquals("quote", p.profileQuote());
    }

    // ━━━━━━━━━━ 홈 피드 구성 ━━━━━━━━━━

    @Test
    @DisplayName("피드는 PUBLIC·비숨김 UGC만 최신순으로 담고 크리에이터 닉네임을 배치 해석한다")
    void feedFiltersAndOrdersUgc() {
        Character oldPub = ugc(10L, "구공개", true, false, 100L);
        Character newPub = ugc(20L, "신공개", true, false, 200L);
        Character priv = ugc(30L, "비공개", false, false, 100L);
        Character hidden = ugc(40L, "숨김", true, true, 100L);
        when(characterRepository.findAll()).thenReturn(List.of(oldPub, newPub, priv, hidden));

        User owner100 = mock(User.class);
        when(owner100.getId()).thenReturn(100L);
        when(owner100.getNickname()).thenReturn("메이커");
        User owner200 = mock(User.class);
        when(owner200.getId()).thenReturn(200L);
        when(owner200.getNickname()).thenReturn(""); // 빈 닉네임 → "크리에이터" 폴백
        when(userRepository.findAllById(any())).thenReturn(List.of(owner100, owner200));

        var feed = lobbyService.getHomeFeed();

        assertEquals(2, feed.items().size(), "PRIVATE·hidden은 피드에서 제외");
        assertEquals("신공개", feed.items().get(0).name(), "UGC는 최신(id 역순) 우선");
        assertEquals("크리에이터", feed.items().get(0).creatorNickname());
        assertEquals("메이커", feed.items().get(1).creatorNickname());
        assertTrue(feed.items().get(0).ugc());
    }

    // ━━━━━━━━━━ 게스트 DTO 필드 계약 ━━━━━━━━━━

    /** 게스트 직렬화 레코드에 유입되면 안 되는 필드명 (시크릿 메타·프롬프트성·유저 종속). */
    private static final Set<String> FORBIDDEN_COMPONENTS = Set.of(
        "secretallowed", "secreteligible", "secretreviewstatus", "secretmodeactive",
        "personality", "tone", "firstgreeting", "lore", "basesystemprompt",
        "hasexistingroom", "existingroomid", "isadult", "isadultverified",
        "reviewstatus", "reviewnote", "visibility");

    /**
     * 게스트에게 실제 직렬화되는 전체 DTO 표면 — LobbyPublicDtos(재귀) + 재사용/공유 DTO.
     * [적대적 리뷰 P2] 계약 테스트가 LobbyPublicDtos만 스캔하면, 게스트 경로가 재사용하는
     * CharacterProfileResponse·CharacterResponse·ExploreItem·(GuestTheaterWorldCard가 중첩하는)
     * HeroineSummary에 금지 필드가 추가돼도 green으로 통과하는 사각지대가 생긴다.
     */
    private static final Set<Class<?>> GUEST_SERIALIZED_ROOTS = Set.of(
        com.spring.aichat.dto.lobby.CharacterProfileResponse.class,   // GET /lobby/characters/{id}/profile (guest)
        com.spring.aichat.dto.lobby.CharacterResponse.class,          // GET /lobby/characters
        com.spring.aichat.dto.ugc.UgcDtos.ExploreItem.class,          // GET /ugc/characters/explore
        com.spring.aichat.dto.theater.TheaterResponses.HeroineSummary.class); // GuestTheaterWorldCard.heroines

    @Test
    @DisplayName("게스트 직렬화 표면(LobbyPublicDtos 재귀 + 재사용 DTO) 전체가 금지 필드를 갖지 않는다")
    void guestDtosCarryNoForbiddenFields() {
        java.util.Set<Class<?>> visited = new java.util.HashSet<>();
        java.util.Deque<Class<?>> queue = new java.util.ArrayDeque<>();
        for (Class<?> nested : LobbyPublicDtos.class.getDeclaredClasses()) {
            if (nested.isRecord()) queue.add(nested);
        }
        queue.addAll(GUEST_SERIALIZED_ROOTS);

        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            if (type == null || !type.isRecord() || !visited.add(type)) continue;
            for (RecordComponent rc : type.getRecordComponents()) {
                String name = rc.getName().toLowerCase(Locale.ROOT);
                assertFalse(FORBIDDEN_COMPONENTS.contains(name),
                    type.getSimpleName() + "." + rc.getName() + " — 게스트 스코프 계약 위반");
                // 중첩 레코드 재귀 (List<X> 등 제네릭 원소도 추적)
                queue.add(rc.getType());
                java.lang.reflect.Type gt = rc.getGenericType();
                if (gt instanceof java.lang.reflect.ParameterizedType pt) {
                    for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
                        if (arg instanceof Class<?> ac) queue.add(ac);
                    }
                }
            }
        }
    }
}
