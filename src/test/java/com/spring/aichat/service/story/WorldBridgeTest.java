package com.spring.aichat.service.story;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.ugc.UgcWorld;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.world.WorldRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [2026-07-31 에픽 A] World enum PK 브리지 계약 테스트 — WorldRef 파싱, UGC STORY 방 팩토리,
 * 격리 확정(링크 전환 시 STORY/THEATER 플래그 자동 관리), WorldView 수위·키 정책.
 */
class WorldBridgeTest {

    // ━━━━━━━━━━ WorldRef — API 문자열 계약 ━━━━━━━━━━

    @Test
    @DisplayName("WorldRef.parse — 공식 enum name과 UGCW_ 접두를 판별한다")
    void parsesRefs() {
        WorldRef official = WorldRef.parse("MEDIEVAL_FANTASY");
        assertFalse(official.isUgc());
        assertEquals(WorldId.MEDIEVAL_FANTASY, official.officialId());
        assertEquals("MEDIEVAL_FANTASY", official.key());

        WorldRef ugc = WorldRef.parse("UGCW_123");
        assertTrue(ugc.isUgc());
        assertEquals(123L, ugc.ugcWorldId());
        assertEquals("UGCW_123", ugc.key());
    }

    @Test
    @DisplayName("WorldRef.parse — 무효 문자열은 IllegalArgumentException")
    void rejectsInvalidRefs() {
        assertThrows(IllegalArgumentException.class, () -> WorldRef.parse("NOT_A_WORLD"));
        assertThrows(IllegalArgumentException.class, () -> WorldRef.parse("UGCW_abc"));
        assertThrows(IllegalArgumentException.class, () -> WorldRef.parse(""));
        assertThrows(IllegalArgumentException.class, () -> WorldRef.parse(null));
    }

    @Test
    @DisplayName("WorldRef — 공식 XOR UGC 불변식")
    void refXorInvariant() {
        assertThrows(IllegalArgumentException.class, () -> new WorldRef(null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new WorldRef(WorldId.MEDIEVAL_FANTASY, 1L));
    }

    // ━━━━━━━━━━ ChatRoom — UGC STORY 방 팩토리 ━━━━━━━━━━

    @Test
    @DisplayName("createStoryV2Ugc — world 없이 ugcWorldId로 STORY 방을 만든다")
    void createsUgcStoryRoom() {
        ChatRoom room = ChatRoom.createStoryV2Ugc(new User(), 42L, "ROOFTOP_GARDEN", "페르소나", "닉");
        assertEquals(ChatMode.STORY, room.getChatMode());
        assertNull(room.getWorld());
        assertEquals(42L, room.getUgcWorldId());
        assertTrue(room.isUgcWorldStory());
        assertEquals("ROOFTOP_GARDEN", room.getCurrentUserLocationKey());
    }

    @Test
    @DisplayName("createStoryV2Ugc — ugcWorldId null이면 거부")
    void rejectsNullUgcWorldId() {
        assertThrows(IllegalArgumentException.class,
            () -> ChatRoom.createStoryV2Ugc(new User(), null, "KEY", null, null));
    }

    // ━━━━━━━━━━ Character — 격리 확정(링크 전환 플래그 자동 관리) ━━━━━━━━━━

    private static Character ugcChar(WorldId officialWorld, Long ugcWorld) {
        return Character.createUgc(new Character.UgcCharacterSpec(
            1L, "미아", "ugc-mia", "system", "model",
            "tagline", "desc", "role", "personality", "tone",
            "appearance", "clothing", "backstory", "core", "flaws", "quirks",
            "greeting", "intro", "http://img", "http://thumb", "DEFAULT",
            officialWorld, ugcWorld, "160cm", "likes", "dislikes", "hobby", "무드", "quote",
            "pink hair", "kuudere", null));
    }

    @Test
    @DisplayName("createUgc — UGC 월드 연결 캐릭터만 STORY/THEATER 개방 대상")
    void createUgcFlagsFollowWorldLink() {
        Character ugcLinked = ugcChar(null, 42L);
        assertTrue(ugcLinked.isStoryAvailable());

        Character officialLinked = ugcChar(WorldId.MEDIEVAL_FANTASY, null);
        assertFalse(officialLinked.isStoryAvailable(), "공식 월드 연결은 SANDBOX 유지(격리)");

        Character unlinked = ugcChar(null, null);
        assertFalse(unlinked.isStoryAvailable());
    }

    @Test
    @DisplayName("링크 전환 — linkUgcWorld=개방, linkOfficialWorld/unlinkWorld=해제")
    void linkTransitionsManageFlags() {
        Character c = ugcChar(null, 42L);
        assertTrue(c.isStoryAvailable());

        c.linkOfficialWorld(WorldId.MEDIEVAL_FANTASY);
        assertFalse(c.isStoryAvailable(), "공식 전환 시 STORY 해제(공식 캐스트 오염 차단)");
        assertNull(c.getUgcWorldId());

        c.linkUgcWorld(7L);
        assertTrue(c.isStoryAvailable());
        assertNull(c.getWorldId());

        c.unlinkWorld();
        assertFalse(c.isStoryAvailable(), "무대 없음 — 해제");
    }

    // ━━━━━━━━━━ WorldView — 수위·키 정책 ━━━━━━━━━━

    @Test
    @DisplayName("WorldView(UGC) — 시크릿 불허 확정 + UGCW_ canonical key + 전 장소 시작 가능")
    void ugcViewPolicies() {
        UgcWorld ugc = UgcWorld.create(1L, "달빛 학원", "소개", "설정 본문", "mystic, night", "http://thumb");
        // id는 영속 전 null — 키 검증은 ref 기준
        WorldView view = new WorldView(WorldRef.ofUgc(42L), null, ugc, List.of(
            new WorldView.LocationView("ROOFTOP_GARDEN", "옥상 정원", "달빛이 비치는 정원", true, "http://bg"),
            new WorldView.LocationView("LIBRARY", "도서관", null, true, null)
        ));

        assertTrue(view.isUgc());
        assertFalse(view.secretAllowed(), "UGC 월드 시크릿 불허(확정 정책)");
        assertEquals("달빛 학원", view.displayName());
        assertEquals("UGCW_42__ROOFTOP_GARDEN", view.canonicalKey("ROOFTOP_GARDEN"));
        assertEquals(2, view.startableLocations().size());
        assertEquals("옥상 정원", view.locationDisplayName("ROOFTOP_GARDEN"));
        assertEquals("UNKNOWN_KEY", view.locationDisplayName("UNKNOWN_KEY"), "미등록 키는 키 자체 표시");
    }
}
