package com.spring.aichat.service.auth;

import com.spring.aichat.config.DefaultCharacterProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 온보딩 서비스
 * - 기본 캐릭터를 가져오고(없으면 예외: 부팅 시드가 보장해야 함)
 * - 회원의 기본 채팅방을 생성/조회한다.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final CharacterRepository characterRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final DefaultCharacterProperties defaultCharacterProperties;

    @Transactional
    public ChatRoom getOrCreateDefaultRoom(User user) {
        Character character = characterRepository.findByName(defaultCharacterProperties.name())
            .orElseThrow(() -> new NotFoundException("기본 캐릭터가 존재하지 않습니다. 시드 설정을 확인하세요."));

        return chatRoomRepository.findByUser_IdAndCharacter_Id(user.getId(), character.getId())
            .orElseGet(() -> chatRoomRepository.save(new ChatRoom(user, character)));
    }
}
