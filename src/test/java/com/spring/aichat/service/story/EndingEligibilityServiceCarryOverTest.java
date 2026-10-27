package com.spring.aichat.service.story;

import com.spring.aichat.config.LegacyFeatureProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.heroine.ChatRoomHeroine;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import com.spring.aichat.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * [docs/55 결정 ③] 자유 모드 이식 후 엔딩 자격 유예 — 이식 호감이 임계를 넘어도 유저 10턴 전엔 활성하지 않는다.
 * 엔딩 게이트(legacy.ending.dialogueEnabled)는 운영 기본 off라 테스트에서 켠다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EndingEligibilityServiceCarryOverTest {

    @Mock private ChatRoomHeroineRepository heroineRepository;
    @Mock private ChatLogMongoRepository chatLogRepository;

    private EndingEligibilityService service;
    private ChatRoom story;
    private ChatRoomHeroine heroine;

    @BeforeEach
    void setUp() {
        LegacyFeatureProperties legacy = new LegacyFeatureProperties();
        legacy.getEnding().setDialogueEnabled(true);
        service = new EndingEligibilityService(legacy, heroineRepository, chatLogRepository);

        User user = User.local("tester", "pw", "닉", "t@test.com");
        ReflectionTestUtils.setField(user, "id", 7L);
        Character character = new Character("아이리", "airi", "prompt", "model");
        ReflectionTestUtils.setField(character, "id", 5L);

        ChatRoom sandbox = new ChatRoom(user, character, ChatMode.SANDBOX);
        ReflectionTestUtils.setField(sandbox, "id", 42L);
        sandbox.applyNormalStatChanges(0, 100, 0, 0, 0);   // 호감 100 = 해피 엔딩 임계

        story = ChatRoom.createStoryV2Ugc(user, 1L, "START", null, null);
        ReflectionTestUtils.setField(story, "id", 100L);
        heroine = ChatRoomHeroine.create(story, character);
        heroine.seedFromSandbox(sandbox);
        when(heroineRepository.findByChatRoom_Id(100L)).thenReturn(List.of(heroine));
    }

    @Test
    @DisplayName("이식 직후(이 방 응답 3턴)엔 호감 100이어도 자격 활성 안 함")
    void deferredDuringGrace() {
        story.markCarriedOver();
        when(chatLogRepository.countByRoomIdAndRoleAndImportedFromRoomIdIsNull(100L, ChatRole.ASSISTANT)).thenReturn(3L);

        assertFalse(service.checkAndActivateEligibility(story));
        assertFalse(story.isEndingEligible());
    }

    @Test
    @DisplayName("이식 후 이 방 응답 10턴을 채우면 자격 활성 (액션 턴도 ASSISTANT 응답으로 세어진다)")
    void activatesAfterGrace() {
        story.markCarriedOver();
        when(chatLogRepository.countByRoomIdAndRoleAndImportedFromRoomIdIsNull(100L, ChatRole.ASSISTANT))
            .thenReturn((long) EndingEligibilityService.CARRY_OVER_GRACE_TURNS);

        assertTrue(service.checkAndActivateEligibility(story));
        assertTrue(story.isEndingEligible());
    }

    @Test
    @DisplayName("이식이 없는 방은 종전처럼 즉시 활성 (회귀 방지)")
    void noCarryOverUnchanged() {
        assertNull(story.getCarryOverAt());
        assertTrue(service.checkAndActivateEligibility(story));
        assertTrue(story.isEndingEligible());
    }
}
