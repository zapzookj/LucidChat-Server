package com.spring.aichat.service;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.lobby.CharacterResponse;
import com.spring.aichat.dto.lobby.CreateRoomRequest;
import com.spring.aichat.dto.lobby.RoomSummaryResponse;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.service.cache.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * [Phase 4.5] 로비 서비스
 *
 * 캐릭터 목록, 채팅방 목록, 채팅방 생성 비즈니스 로직
 *
 * [Phase 5.5-Fix] toRoomSummary: 지배 스탯 정보 추가
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LobbyService {

    private final CharacterRepository characterRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final RedisCacheService cacheService;
    /** [Chunk D] V2 방의 heroineCount 일괄 조회용 (N+1 방지). */
    private final ChatRoomHeroineRepository heroineRepository;

    /**
     * 전체 캐릭터 목록 조회
     */
    public List<CharacterResponse> getAllCharacters() {
        return characterRepository.findAll().stream()
            .map(this::toCharacterResponse)
            .toList();
    }

    /**
     * 유저의 모든 채팅방 목록 조회 (최근 활동 순).
     *
     * <p>[V2 호환] {@code @Transactional(readOnly = true)} — character/world LAZY FK 접근 시
     * 세션 닫힘 방지. V2 STORY 방은 character가 null이고 world FK가 존재 → toRoomSummary에서 분기.
     *
     * <p>[Chunk D] V2 방 heroineCount 일괄 조회 (N+1 방지) — V2 방이 없으면 빈 Map.
     */
    @Transactional(readOnly = true)
    public List<RoomSummaryResponse> getMyRooms(String username) {
        User user = findUserByUsername(username);
        List<ChatRoom> rooms = chatRoomRepository.findAllByUser_IdOrderByLastActiveAtDesc(user.getId());

        // [Chunk D] V2 방 ID 집합 추출 → 일괄 heroine count 조회
        Set<Long> v2RoomIds = rooms.stream()
            .filter(r -> r.getCharacter() == null)  // V2 방 식별
            .map(ChatRoom::getId)
            .collect(Collectors.toSet());

        Map<Long, Long> heroineCounts = v2RoomIds.isEmpty()
            ? Map.of()
            : heroineRepository.findHeroineCountsByRoomIds(v2RoomIds).stream()
            .collect(Collectors.toMap(
                ChatRoomHeroineRepository.RoomHeroineCountProjection::getRoomId,
                ChatRoomHeroineRepository.RoomHeroineCountProjection::getHeroineCount
            ));

        return rooms.stream()
            .map(r -> toRoomSummary(r, heroineCounts.getOrDefault(r.getId(), 0L).intValue()))
            .toList();
    }

    /**
     * 채팅방 생성 또는 기존 방 반환 (Idempotent)
     *
     * 동일 유저 + 캐릭터 + 모드 조합이 이미 존재하면 기존 방을 반환
     */
    @Transactional
    public RoomSummaryResponse createOrGetRoom(String username, CreateRoomRequest request) {
        User user = findUserByUsername(username);

        Character character = characterRepository.findById(request.characterId())
            .orElseThrow(() -> new NotFoundException("존재하지 않는 캐릭터입니다. characterId=" + request.characterId()));

        ChatMode chatMode;
        try {
            chatMode = ChatMode.valueOf(request.chatMode().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("올바르지 않은 채팅 모드입니다: " + request.chatMode());
        }

        // 스토리 모드인데 해당 캐릭터가 스토리 미지원이면 차단
        if (chatMode == ChatMode.STORY && !character.isStoryAvailable()) {
            throw new BadRequestException("해당 캐릭터는 아직 스토리 모드를 지원하지 않습니다.");
        }

        // 기존 방이 있으면 반환 (Idempotent)
        ChatRoom room = chatRoomRepository
            .findByUser_IdAndCharacter_IdAndChatMode(user.getId(), character.getId(), chatMode)
            .orElseGet(() -> {
                ChatRoom newRoom = chatRoomRepository.save(new ChatRoom(user, character, chatMode));
                // AuthGuard 소유권 캐싱
                cacheService.cacheRoomOwner(newRoom.getId(), user.getUsername());
                log.info("🏠 [LOBBY] New room created: roomId={}, character={}, mode={}",
                    newRoom.getId(), character.getName(), chatMode);
                return newRoom;
            });

        return toRoomSummary(room);
    }

    // ── Private Helpers ──

    private User findUserByUsername(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
    }

    private CharacterResponse toCharacterResponse(Character c) {
        return new CharacterResponse(
            c.getId(),
            c.getName(),
            c.getSlug(),
            c.getTagline(),
            c.getDescription(),
            c.getThumbnailUrl(),
            c.getDefaultImageUrl(),
            c.isStoryAvailable()
        );
    }

    private RoomSummaryResponse toRoomSummary(ChatRoom room) {
        // V1 호환 — heroineCount 미지정 시 0
        return toRoomSummary(room, 0);
    }

    /**
     * [Chunk D] V2 RoomSummaryResponse 확장 필드 (worldId / currentDay / heroineCount) 포함.
     */
    private RoomSummaryResponse toRoomSummary(ChatRoom room, int heroineCount) {
        Character c = room.getCharacter();
        if (c == null) {
            // [V2 STORY 분기] character FK 없음 → World 정보로 RoomSummary 빌드.
            return toV2StoryRoomSummary(room, heroineCount);
        }
        return new RoomSummaryResponse(
            room.getId(),
            c.getId(),
            c.getName(),
            c.getThumbnailUrl(),
            room.getAffectionScore(),
            room.getStatusLevel().name(),
            room.getChatMode().name(),
            room.getLastEmotion() != null ? room.getLastEmotion().name() : null,
            room.getLastActiveAt(),
            room.isEndingReached(),
            room.getEndingType() != null ? room.getEndingType().name() : null,
            room.getEndingTitle(),
            room.getDominantStatName(),
            room.getMaxNormalStatValue(),
            room.getDynamicRelationTag(),
            // [Chunk D] V2 필드 — V1 Sandbox/Story는 null/0
            null,                                              // worldId
            room.getCurrentDay() != null ? room.getCurrentDay() : 0,  // V1도 currentDay 보유 가능 (있으면 노출)
            0                                                  // heroineCount (V1은 단일 캐릭터)
        );
    }

    /**
     * [V2 STORY] character FK가 null인 V2 방의 RoomSummary 생성.
     * character* 필드를 *World 정보로 대체* — RoomSummaryResponse record 시그니처는 그대로 유지.
     *
     * <p>프론트는 {@code chatMode === "STORY"} + {@code characterId === null} 또는
     * {@code worldId !== null}로 분기하여 StoryV2RoomCard로 표시.
     *
     * <p>V2 방의 스탯은 ChatRoomHeroine에 분산 — RoomSummary는 *대표값 0* 또는 *집계*가 적절하나,
     * 단순화를 위해 0/STRANGER로 표시 (UI에서 World 정보 + 일차 + 히로인 수만 노출).
     *
     * <p>[Chunk D] V2 신규 필드 채움 — worldId / currentDay / heroineCount.
     */
    private RoomSummaryResponse toV2StoryRoomSummary(ChatRoom room, int heroineCount) {
        com.spring.aichat.domain.world.World w = room.getWorld();
        // V2 방인데 world도 null이면 손상된 row — 기본값으로 fallback
        String worldName = w != null ? w.getDisplayName() : "(unknown world)";
        String worldThumb = w != null ? w.getThumbnailUrl() : null;
        WorldId worldId = w != null ? w.getId() : null;
        int currentDay = room.getCurrentDay() != null ? room.getCurrentDay() : 0;

        return new RoomSummaryResponse(
            room.getId(),
            null,                 // characterId — V2 방은 character 없음
            worldName,            // characterName 자리에 World 이름
            worldThumb,           // thumbnail 자리에 World 썸네일
            0,                    // affectionScore — V2는 히로인별 분산
            "STRANGER",           // statusLevel — V2는 히로인별 분산
            room.getChatMode().name(),
            room.getLastEmotion() != null ? room.getLastEmotion().name() : null,
            room.getLastActiveAt(),
            room.isEndingReached(),
            room.getEndingType() != null ? room.getEndingType().name() : null,
            room.getEndingTitle(),
            null,                 // dominantStatName — V2 무관
            0,                    // dominantStatValue
            "이야기 진행 중",       // dynamicRelationTag — V2 방 식별용 텍스트
            // [Chunk D] V2 신규 필드
            worldId,              // V2 World FK
            currentDay,           // V2 진행 일차
            heroineCount          // V2 히로인 수 (batch 조회 결과)
        );
    }
}