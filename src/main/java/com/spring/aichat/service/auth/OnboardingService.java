package com.spring.aichat.service.auth;

import com.spring.aichat.config.CharacterSeedProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.service.cache.RedisCacheService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 회원 온보딩 서비스
 *
 * [Phase 3 Redis 캐싱]
 * - 기본 캐릭터 ID를 Redis에 영구 캐싱
 *   → 로그인/회원가입마다 characterRepository.findByName() 쿼리 제거
 * - 캐릭터 데이터는 부팅 시 DefaultCharacterSeeder가 시드하므로 런타임에 변하지 않음
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OnboardingService {

    private final CharacterRepository characterRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final CharacterSeedProperties characterSeedProperties;
    private final RedisCacheService cacheService;

    private static final String DEFAULT_CHARACTER_ID_KEY = "character:default_id";

    @Transactional
    public ChatRoom getOrCreateDefaultRoom(User user) {
        Long characterId = getDefaultCharacterId();

        Character character = characterRepository.findById(characterId)
            .orElseThrow(() -> new NotFoundException("기본 캐릭터가 존재하지 않습니다. characterId=" + characterId));

        return chatRoomRepository.findByUser_IdAndCharacter_Id(user.getId(), character.getId())
            .orElseGet(() -> {
                ChatRoom newRoom = chatRoomRepository.save(new ChatRoom(user, character));
                // 새 방 생성 시 소유권 캐싱 (AuthGuard에서 사용)
                cacheService.cacheRoomOwner(newRoom.getId(), user.getUsername());
                log.debug("🔑 [CACHE] Room ownership pre-cached on creation: roomId={} → {}",
                    newRoom.getId(), user.getUsername());
                return newRoom;
            });
    }

    /**
     * 기본 캐릭터 ID를 Redis에서 조회, 없으면 DB 조회 후 영구 캐싱
     */
    private Long getDefaultCharacterId() {
        return cacheService.getString(DEFAULT_CHARACTER_ID_KEY)
            .map(Long::parseLong)
            .orElseGet(() -> {
                Character character = characterRepository.findByName(characterSeedProperties.characters().get(0).name())
                    .orElseThrow(() -> new NotFoundException("기본 캐릭터가 존재하지 않습니다. 시드 설정을 확인하세요."));

                cacheService.putString(DEFAULT_CHARACTER_ID_KEY, String.valueOf(character.getId()));
                log.info("🎭 [CACHE] Default character ID cached: {} → id={}",
                    characterSeedProperties.characters().get(0).name(), character.getId());

                return character.getId();
            });
    }
}