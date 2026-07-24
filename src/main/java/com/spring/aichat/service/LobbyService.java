package com.spring.aichat.service;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.ugc.UgcWorldLocation;
import com.spring.aichat.domain.ugc.UgcWorldLocationRepository;
import com.spring.aichat.domain.ugc.UgcWorldRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.lobby.CharacterProfileResponse;
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
    private final UgcWorldLocationRepository ugcWorldLocationRepository; // [세계관 빌더] 방 생성 배경 시딩
    private final UgcWorldRepository ugcWorldRepository; // [프로필 뷰] 소속 월드 이름 해석

    /**
     * 전체 캐릭터 목록 조회
     *
     * <p>[UGC v1] 공식 캐릭터만 — UGC는 스튜디오 탭(/ugc/characters/mine·explore) 전용.
     * PRIVATE UGC가 공용 로비에 노출되는 것을 여기서 차단한다.
     */
    public List<CharacterResponse> getAllCharacters() {
        return characterRepository.findAll().stream()
            .filter(c -> !c.isUgc())
            .map(this::toCharacterResponse)
            .toList();
    }

    /**
     * [Phase 7-V2 Pivot] 세계관별 캐릭터 목록 — 통합 로비의 캐릭터 선택 단계용.
     * worldId가 null/blank면 전체 반환 (getAllCharacters와 동일).
     * 그 외엔 해당 World 소속 캐릭터만 필터 (Character.worldId 매칭).
     */
    public List<CharacterResponse> getCharactersByWorld(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            return getAllCharacters();
        }
        return characterRepository.findAll().stream()
            .filter(c -> !c.isUgc()) // [UGC v1] 공식 전용
            .filter(c -> c.getWorldId() != null && c.getWorldId().name().equals(worldId))
            .map(this::toCharacterResponse)
            .toList();
    }

    /**
     * 유저의 모든 채팅방 목록 조회 (최근 활동 순).
     *
     * <p>[V2 호환] {@code @Transactional(readOnly = true)} — character/world LAZY FK 접근 시
     * 세션 닫힘 방지. V2 STORY 방은 character가 null이고 world FK가 존재 → toRoomSummary에서 분기.
     */
    @Transactional(readOnly = true)
    public List<RoomSummaryResponse> getMyRooms(String username) {
        User user = findUserByUsername(username);
        List<ChatRoom> rooms = chatRoomRepository.findAllByUser_IdOrderByLastActiveAtDesc(user.getId());
        return rooms.stream()
            // [E-1 A-3] THEATER 방 제외 — TheaterLobbyService가 별도 세션 목록을 제공하므로
            //   (1) 로비 "기억의 끈"에서 중복 표시 방지, (2) V1 스탯 경로(getDominantStatName 등)로
            //   새어 발생하던 requireSandbox 계열 충돌을 원천 차단.
            .filter(r -> r.getChatMode() != ChatMode.THEATER)
            .map(this::toRoomSummary)
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

        // [UGC v1] 접근 규칙: PUBLIC은 전체, PRIVATE/PENDING_PUBLIC은 소유자만 (존재 은닉)
        if (!character.isAccessibleBy(user.getId())) {
            throw new NotFoundException("존재하지 않는 캐릭터입니다. characterId=" + request.characterId());
        }
        // [UGC v1] v1 스코프: UGC 캐릭터는 SANDBOX 전용 (story/theaterAvailable=false와 이중 방어)
        if (character.isUgc() && chatMode != ChatMode.SANDBOX) {
            throw new BadRequestException("커스텀 캐릭터는 자유 대화 모드만 지원해요.");
        }

        // 기존 방이 있으면 반환 (Idempotent)
        ChatRoom room = chatRoomRepository
            .findByUser_IdAndCharacter_IdAndChatMode(user.getId(), character.getId(), chatMode)
            .orElseGet(() -> {
                ChatRoom newRoom = chatRoomRepository.save(new ChatRoom(user, character, chatMode));
                // [세계관 빌더] UGC 월드 캐릭터 — 첫 장소 대표 배경을 초기 동적 배경으로 시딩
                //   (UGC는 slug 정적 배경 에셋이 없어 dynamicBg가 유일한 실효 렌더 소스)
                seedUgcWorldBackground(newRoom, character);
                // AuthGuard 소유권 캐싱
                cacheService.cacheRoomOwner(newRoom.getId(), user.getUsername());
                log.info("🏠 [LOBBY] New room created: roomId={}, character={}, mode={}",
                    newRoom.getId(), character.getName(), chatMode);
                return newRoom;
            });

        return toRoomSummary(room);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2026-07-22 프로필 뷰] 몰입형 캐릭터 프로필 (카드 클릭 → 프로필 → 대화)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 접근 규칙: 공개 캐릭터 전체 + 비공개는 소유자만 — 그 외 404 은닉. hidden도 404. */
    @Transactional(readOnly = true)
    public CharacterProfileResponse getCharacterProfile(String username, Long characterId) {
        User user = findUserByUsername(username);
        Character c = characterRepository.findById(characterId)
            .filter(ch -> !ch.isHidden())
            .filter(ch -> ch.isAccessibleBy(user.getId()))
            .orElseThrow(() -> new NotFoundException("존재하지 않는 캐릭터입니다. characterId=" + characterId));

        String worldType = null;
        String worldName = null;
        if (c.getWorldId() != null) {
            worldType = "OFFICIAL";
            worldName = c.getWorldId().getDisplayName();
        } else if (c.getUgcWorldId() != null) {
            worldType = "UGC";
            worldName = ugcWorldRepository.findById(c.getUgcWorldId())
                .map(w -> w.getName()).orElse(null);
        }

        String creatorNickname = null;
        if (c.isUgc() && c.getOwnerUserId() != null) {
            creatorNickname = userRepository.findById(c.getOwnerUserId())
                .map(u -> (u.getNickname() != null && !u.getNickname().isBlank()) ? u.getNickname() : "크리에이터")
                .orElse(null);
        }

        return new CharacterProfileResponse(
            c.getId(), c.getName(), c.getSlug(), c.getAge(),
            c.getEffectiveRole(), c.getTagline(),
            splitCsv(c.getMoodTags()),
            worldType, worldName,
            c.getAppearance(), c.getClothing(),
            c.getHeight(), c.getLikes(), c.getDislikes(), c.getHobby(),
            (c.getProfileQuote() != null && !c.getProfileQuote().isBlank())
                ? c.getProfileQuote()
                : firstSentence(c.getFirstGreeting()),
            firstSentence(c.getIntroNarration()),
            firstSentence(c.getFirstGreeting()),
            c.getDefaultImageUrl(), c.getThumbnailUrl(),
            c.isUgc(), creatorNickname
        );
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static final char[] SENTENCE_DELIMS = {'.', '!', '?', '…', '\n'};

    /**
     * 첫 문장 절삭 — 티저/발췌는 스포일러 방지를 위해 한 문장(최대 90자)만 노출.
     * [리뷰 픽스] "……안녕하세요" 류 말줄임표 시작 텍스트가 구분자 한 글자로 붕괴하지 않도록
     * 선행 구분자 런을 건너뛴 위치부터 경계를 찾고, 90자 절삭은 서로게이트 페어 안전 처리.
     */
    static String firstSentence(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.strip();
        int start = 0;
        while (start < t.length() && (isSentenceDelim(t.charAt(start)) || t.charAt(start) == ' ')) start++;
        if (start >= t.length()) return null; // 구분자·공백뿐인 텍스트

        int end = t.length();
        for (char delim : SENTENCE_DELIMS) {
            int idx = t.indexOf(delim, start);
            if (idx >= 0 && idx + 1 < end) end = idx + 1;
        }
        String sentence = t.substring(0, Math.min(end, t.length())).strip();
        if (sentence.length() > 90) {
            int cut = 90;
            // 이모지 페어 중간 절단 방지 (도메인 Character 엔티티 import와의 충돌로 FQN 사용)
            if (java.lang.Character.isHighSurrogate(sentence.charAt(cut - 1))) cut--;
            sentence = sentence.substring(0, cut).strip() + "…";
        }
        return sentence;
    }

    private static boolean isSentenceDelim(char c) {
        for (char d : SENTENCE_DELIMS) {
            if (c == d) return true;
        }
        return false;
    }

    // ── Private Helpers ──

    /**
     * [세계관 빌더] UGC 월드 소속 캐릭터의 방 생성 시 첫 장소(display order 0) 배경을
     * 초기 동적 배경으로 세팅 — 방 진입 즉시 세계관 배경이 보이게 한다. 실패는 비차단.
     */
    private void seedUgcWorldBackground(ChatRoom room, Character character) {
        if (character.getUgcWorldId() == null) return;
        try {
            List<UgcWorldLocation> locations = ugcWorldLocationRepository
                .findByUgcWorldIdAndActiveTrueOrderByDisplayOrderAsc(character.getUgcWorldId());
            for (UgcWorldLocation loc : locations) {
                if (loc.getBackgroundUrl() != null && !loc.getBackgroundUrl().isBlank()) {
                    room.updateDynamicBackground(loc.getDisplayName(),
                        "UGCW_" + character.getUgcWorldId() + "__" + loc.getLocationKey(),
                        loc.getBackgroundUrl());
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("🏠 [LOBBY] UGC 월드 배경 시딩 실패 (non-blocking): roomId={}, {}", room.getId(), e.getMessage());
        }
    }

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
            c.isStoryAvailable(),
            c.getWorldId() != null ? c.getWorldId().name() : null,  // [Phase 7-V2 Pivot]
            c.isTheaterAvailable()                                   // [Phase 7-V2 Pivot]
        );
    }

    private RoomSummaryResponse toRoomSummary(ChatRoom room) {
        // [E-1 A-3] 분기 기준을 'character == null' 휴리스틱 → 'chatMode'로 교정.
        //   기존 휴리스틱은 V2 STORY(character 없음)만 걸렀고, character를 *가진* 비-SANDBOX 방
        //   (THEATER, 레거시 V1 STORY)이 V1 스탯 경로로 새어 getDominantStatName/getMaxNormalStatValue
        //   → requireSandbox 계열에서 터졌다(로비 500 → 기억의 끈 로드 실패).
        //   이제 SANDBOX만 V1 스탯 요약을 쓰고, 그 외 모드는 World 기반 V2 요약으로 안전 처리.
        if (room.getChatMode() != ChatMode.SANDBOX) {
            return toV2StoryRoomSummary(room);
        }
        Character c = room.getCharacter();
        if (c == null) {
            // SANDBOX인데 character FK가 없는 손상 row — 방어적으로 V2 요약으로 폴백.
            return toV2StoryRoomSummary(room);
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
            room.getDynamicRelationTag()
        );
    }

    /**
     * [V2 STORY] character FK가 null인 V2 방의 RoomSummary 생성.
     * character* 필드를 *World 정보로 대체* — RoomSummaryResponse record 시그니처는 그대로 유지.
     *
     * <p>프론트는 {@code chatMode === "STORY"}로 분기하여 World 카드 형태로 표시 가능.
     * V2 방의 스탯은 ChatRoomHeroine에 분산 — RoomSummary는 *대표값 0* 또는 *집계*가 적절하나,
     * 단순화를 위해 0/STRANGER로 표시 (UI에서 World 정보만 노출).
     */
    private RoomSummaryResponse toV2StoryRoomSummary(ChatRoom room) {
        com.spring.aichat.domain.world.World w = room.getWorld();
        // V2 방인데 world도 null이면 손상된 row — 기본값으로 fallback
        String worldName = w != null ? w.getDisplayName() : "(unknown world)";
        String worldThumb = w != null ? w.getThumbnailUrl() : null;
        return new RoomSummaryResponse(
            room.getId(),
            null,                 // characterId — V2 방은 character 없음
            worldName,            // characterName 자리에 World 이름
            worldThumb,           // thumbnail 자리에 World 썸네일
            0,                    // affectionScore — V2는 히로인별 분산 (대표값 0)
            "STRANGER",           // statusLevel — V2는 히로인별 분산
            room.getChatMode().name(),
            room.getLastEmotion() != null ? room.getLastEmotion().name() : null,
            room.getLastActiveAt(),
            room.isEndingReached(),
            room.getEndingType() != null ? room.getEndingType().name() : null,
            room.getEndingTitle(),
            null,                 // dominantStatName — V2 무관
            0,                    // dominantStatValue
            "이야기 진행 중"        // dynamicRelationTag — V2 방 식별용 텍스트
        );
    }
}