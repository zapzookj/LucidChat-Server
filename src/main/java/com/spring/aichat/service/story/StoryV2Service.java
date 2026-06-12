package com.spring.aichat.service.story;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.chat.StoryV2State;
import com.spring.aichat.domain.chat.StoryV2StateRepository;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.DayPart;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.heroine.CharacterPresence;
import com.spring.aichat.domain.heroine.CharacterPresenceRepository;
import com.spring.aichat.domain.heroine.ChatRoomHeroine;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import com.spring.aichat.domain.memory.MemorySummaryRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.domain.world.*;
import com.spring.aichat.dto.story.StoryV2Requests.CreateStoryV2Request;
import com.spring.aichat.dto.story.StoryV2Requests.ResetStoryRequest;
import com.spring.aichat.dto.story.StoryV2Responses.*;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.service.payment.SecretModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * [V2 Story] 메인 서비스 — CreateFlow / Detail / Reset / List
 *
 * <p>책임 (4):
 * <ol>
 *   <li>{@link #listWorlds(User)} — 로비 World 카드 리스트 + 기존 방 정보 attach</li>
 *   <li>{@link #getCreateContext(WorldId, User)} — CreateFlow 진입 시 World 상세 + 히로인/페르소나/장소 일괄</li>
 *   <li>{@link #createOrReuseRoom(User, CreateStoryV2Request)} — 방 생성 (또는 overwrite=true 시 기존 방 reset)</li>
 *   <li>{@link #resetStory(Long, User, ResetStoryRequest)} — 스토리 초기화 (페르소나 옵션)</li>
 *   <li>{@link #getRoomDetail(Long, User)} — ChatPage 진입 시 방 전체 상태</li>
 * </ol>
 *
 * <p>[트랜잭션 정책]
 * 본 서비스는 *생성/삭제*가 핵심이라 단일 @Transactional로 처리.
 * LLM 호출 없으므로 트랜잭션 분리 불필요.
 *
 * <p>[자유 페르소나 BM 권한]
 * 사전 정의 페르소나는 누구나 사용 가능. *자유 텍스트 페르소나*는 BM 보유 유저만.
 * 권한 체크는 {@code hasFreePersonaUnlock(userId)} — 현재 placeholder (운영 진입 전 연결 필요).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StoryV2Service {

    private final WorldRepository worldRepository;
    private final WorldLocationRepository worldLocationRepository;
    private final UserPersonaPresetRepository personaPresetRepository;
    private final CharacterRepository characterRepository;
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final StoryV2StateRepository storyV2StateRepository;
    private final ChatRoomHeroineRepository heroineRepository;
    private final CharacterPresenceRepository presenceRepository;
    private final ChatLogMongoRepository chatLogMongoRepository;
    private final MemorySummaryRepository memorySummaryRepository;

    private final WorldRoutingService routingService;
    private final HeroineMemoryService heroineMemoryService;
    private final OffscreenNotificationService notificationService;
    private final RelationPromotionService promotionService;
    private final SecretModeService secretModeService;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [1] listWorlds — 로비 V2 World 섹션
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional(readOnly = true)
    public List<WorldCardResponse> listWorlds(User user) {
        List<World> worlds = worldRepository.findByActiveTrueOrderByDisplayOrderAsc();
        if (worlds.isEmpty()) return List.of();

        // 유저의 V2 STORY 방들을 worldId로 매핑
        Map<WorldId, ChatRoom> existingRoomsByWorld = chatRoomRepository
            .findAllByUser_IdAndChatModeOrderByLastActiveAtDesc(user.getId(), ChatMode.STORY).stream()
            .filter(r -> r.getWorld() != null)
            .collect(Collectors.toMap(
                r -> r.getWorld().getId(),
                r -> r,
                (a, b) -> a  // World당 1방 unique constraint이라 충돌 없음
            ));

        // 각 World의 히로인 수 집계 (단일 쿼리)
        Map<WorldId, Long> heroineCountByWorld = characterRepository.findAll().stream()
            .filter(c -> c.getWorldId() != null && c.isStoryAvailable())
            .collect(Collectors.groupingBy(Character::getWorldId, Collectors.counting()));

        return worlds.stream()
            .map(w -> {
                ChatRoom existing = existingRoomsByWorld.get(w.getId());
                long heroineCount = heroineCountByWorld.getOrDefault(w.getId(), 0L);
                return new WorldCardResponse(
                    w.getId().name(),
                    w.getDisplayName(),
                    w.getTagline(),
                    w.getDescription(),
                    w.getHeroImageUrl(),
                    w.getThumbnailUrl(),
                    w.getMoodKeywords(),
                    w.isSecretAllowed(),
                    (int) heroineCount,
                    existing != null,
                    existing != null ? existing.getId() : null
                );
            })
            .toList();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2] getCreateContext — CreateFlow 진입 시 일괄 데이터
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional(readOnly = true)
    public CreateContextResponse getCreateContext(WorldId worldId, User user) {
        World world = worldRepository.findById(worldId)
            .orElseThrow(() -> new BusinessException(ErrorCode.WORLD_NOT_FOUND, "World not found: " + worldId));
        if (!world.isActive()) {
            throw new BadRequestException("Inactive world: " + worldId);
        }

        // 히로인 풀
        List<Character> heroines = characterRepository.findByWorldIdAndStoryAvailableTrueOrderByIdAsc(worldId);
        List<WorldHeroineCardResponse> heroineCards = heroines.stream()
            .map(c -> new WorldHeroineCardResponse(
                c.getId(),
                c.getName(),
                c.getThumbnailUrl(),
                c.getRole(),
                c.getAge() != null ? c.getAge() : 0,
                truncate(firstNonBlank(c.getStoryBehaviorGuide(), c.getPersonality()), 80)
            ))
            .toList();

        // 페르소나 프리셋
        List<PersonaPresetResponse> personaPresets = personaPresetRepository
            .findByWorldIdAndActiveTrueOrderByDisplayOrderAsc(worldId).stream()
            .map(p -> new PersonaPresetResponse(
                p.getPresetKey(),
                p.getName(),
                p.getDescription(),
                p.getDefaultNickname(),
                p.getSuggestedStartLocationKey()
            ))
            .toList();

        // 시작 장소 풀
        List<WorldLocationResponse> startLocations = worldLocationRepository
            .findByWorldIdAndActiveTrueAndSelectableAsStartTrueOrderByDisplayOrderAsc(worldId).stream()
            .map(l -> new WorldLocationResponse(
                l.getLocationKey(),
                l.getDisplayName(),
                l.getDescription(),
                l.getSelectableAsStart(),
                l.getDisplayOrder()
            ))
            .toList();

        World cardWorld = world;
        boolean hasFreePersona = hasFreePersonaUnlock(user.getId());

        WorldCardResponse worldCard = new WorldCardResponse(
            cardWorld.getId().name(),
            cardWorld.getDisplayName(),
            cardWorld.getTagline(),
            cardWorld.getDescription(),
            cardWorld.getHeroImageUrl(),
            cardWorld.getThumbnailUrl(),
            cardWorld.getMoodKeywords(),
            cardWorld.isSecretAllowed(),
            heroines.size(),
            false,  // CreateFlow 진입 시점에 existing room 정보 별도 처리
            null
        );

        return new CreateContextResponse(worldCard, heroineCards, personaPresets, startLocations, hasFreePersona);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [3] createOrReuseRoom — 방 생성 (또는 overwrite 시 reset)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public CreateStoryV2Response createOrReuseRoom(User user, CreateStoryV2Request request) {
        WorldId worldId;
        try {
            worldId = WorldId.valueOf(request.worldId());
        } catch (Exception e) {
            throw new BadRequestException("Invalid worldId: " + request.worldId());
        }

        World world = worldRepository.findById(worldId)
            .orElseThrow(() -> new BusinessException(ErrorCode.WORLD_NOT_FOUND, "World not found"));
        if (!world.isActive()) {
            throw new BadRequestException("Inactive world");
        }

        // 히로인 검증
        List<Character> heroines = characterRepository.findAllById(request.heroineIds());
        if (heroines.size() != request.heroineIds().size()) {
            throw new BadRequestException("Some heroine IDs not found");
        }
        for (Character h : heroines) {
            if (!worldId.equals(h.getWorldId())) {
                throw new BadRequestException(
                    "Heroine " + h.getId() + " does not belong to world " + worldId);
            }
            if (!h.isStoryAvailable()) {
                throw new BadRequestException("Inactive heroine: " + h.getId());
            }
        }

        // 시작 장소 검증 + 기본값
        String startLocationKey = resolveStartLocation(worldId, request.startLocationKey());

        // 페르소나 검증
        String userPersona = resolvePersona(user, worldId, request.personaText(), request.selectedPersonaPresetKey());

        // [Phase 7-V2 Pivot] 닉네임 resolve — 입력값 우선, blank면 User.nickname 폴백
        String storyNickname = (request.nickname() != null && !request.nickname().isBlank())
            ? request.nickname().trim()
            : user.getNickname();

        // 기존 방 체크
        Optional<ChatRoom> existing = chatRoomRepository
            .findByUser_IdAndWorld_IdAndChatMode(user.getId(), worldId, ChatMode.STORY);

        if (existing.isPresent()) {
            ChatRoom room = existing.get();
            boolean overwrite = Boolean.TRUE.equals(request.overwriteExisting());
            if (!overwrite) {
                // 409 — UI에서 confirm 받고 재호출
                throw new BusinessException(ErrorCode.STORY_V2_ROOM_EXISTS, "Existing room found: roomId=" + room.getId());
            }
            // overwrite — 페르소나 포함 완전 reset 후 새 페르소나 적용
            log.info("🔄 [STORY-V2] Overwriting existing room: roomId={}", room.getId());
            cascadeResetRoom(room, true);
            room.updateUserPersona(userPersona);
            room.updateStoryUserNickname(storyNickname);
            room.restoreStartLocation(startLocationKey);

            // 히로인/위치 재구성
            reconfigureHeroinesAndPresences(room, heroines, DayPart.defaultStart(), startLocationKey);

            return new CreateStoryV2Response(room.getId(), worldId.name(), false, true);
        }

        // 신규 생성
        ChatRoom room = ChatRoom.createStoryV2(user, world, startLocationKey, userPersona, storyNickname);
        room = chatRoomRepository.save(room);

        // [D-5/E-2b] 서사 나침반 상태 초기화 — 빈 thread로 시작(백본 미리 심지 않음).
        storyV2StateRepository.save(StoryV2State.create(room.getId()));

        reconfigureHeroinesAndPresences(room, heroines, DayPart.defaultStart(), startLocationKey);

        log.info("✨ [STORY-V2] Created new room: roomId={}, worldId={}, heroineCount={}, startLocation={}",
            room.getId(), worldId, heroines.size(), startLocationKey);

        return new CreateStoryV2Response(room.getId(), worldId.name(), true, false);
    }

    private void reconfigureHeroinesAndPresences(ChatRoom room, List<Character> heroines,
                                                 DayPart startDayPart, String startLocationKey) {
        // 1. ChatRoomHeroine N개 생성
        for (Character h : heroines) {
            ChatRoomHeroine entity = ChatRoomHeroine.create(room, h);
            heroineRepository.save(entity);
        }
        // 2. CharacterPresence 위치 초기화 (루틴 기반)
        List<Long> heroineCharIds = heroines.stream().map(Character::getId).toList();
        routingService.initializePresences(room, heroineCharIds, startDayPart);
    }

    private String resolveStartLocation(WorldId worldId, String requestedKey) {
        if (requestedKey != null && !requestedKey.isBlank()) {
            // 유효성 검증
            boolean valid = worldLocationRepository.existsByWorldIdAndLocationKey(worldId, requestedKey);
            if (!valid) {
                throw new BadRequestException(
                    "Invalid start location: " + requestedKey);
            }
            return requestedKey;
        }
        // 기본값: 첫 selectableAsStart 장소
        return worldLocationRepository
            .findByWorldIdAndActiveTrueAndSelectableAsStartTrueOrderByDisplayOrderAsc(worldId).stream()
            .findFirst()
            .map(WorldLocation::getLocationKey)
            .orElseThrow(() -> new BusinessException(ErrorCode.WORLD_LOCATION_MISSING,
                "World " + worldId + " has no startable location seeded"));
    }

    private String resolvePersona(User user, WorldId worldId,
                                  String personaText, String selectedPresetKey) {
        // 사전 정의 페르소나 선택
        if (selectedPresetKey != null && !selectedPresetKey.isBlank()) {
            return personaPresetRepository
                .findByWorldIdAndPresetKey(worldId, selectedPresetKey)
                .map(UserPersonaPreset::getDescription)
                .orElseThrow(() -> new BadRequestException(
                    "Invalid persona preset: " + selectedPresetKey));
        }

        // 자유 페르소나 — BM 권한 필요
        if (personaText != null && !personaText.isBlank()) {
            if (!hasFreePersonaUnlock(user.getId())) {
                throw new BusinessException(ErrorCode.PREMIUM_REQUIRED,
                    "Free persona requires unlock");
            }
            return personaText.trim();
        }

        // 둘 다 미입력 — null 폴백 (ChatRoom.getEffectivePersona에서 user.profileDescription 사용)
        return null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [4] resetStory — 스토리 초기화 (페르소나 옵션)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public void resetStory(Long roomId, User user, ResetStoryRequest request) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("Room not found"));
        if (!room.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not your room");
        }
        if (!room.isStoryMode()) {
            throw new BadRequestException("Not a V2 STORY room");
        }

        // [Phase 7-V2 Pivot Fix] 버그: cascadeResetRoom이 ChatRoomHeroine을 삭제하므로
        //   *삭제 전*에 히로인 목록을 캡처해야 한다. (기존 코드는 삭제 후 조회 → 빈 리스트 →
        //   히로인/프레즌스 재생성 실패 → 방에 히로인 0개 → 이후 모든 접근에서 NPE)
        List<ChatRoomHeroine> existingRows = heroineRepository.findByChatRoom_Id(room.getId());
        List<Character> heroines = existingRows.stream()
            .map(ChatRoomHeroine::getCharacter)
            .filter(java.util.Objects::nonNull)
            .toList();
        // LAZY 프록시를 삭제 전에 강제 초기화 (삭제 후 안전 접근 보장)
        heroines.forEach(c -> { c.getId(); c.getName(); });

        cascadeResetRoom(room, request.includePersona());

        // 시작 장소 갱신
        String startLocationKey = resolveStartLocation(room.getWorld().getId(), request.startLocationKey());
        room.restoreStartLocation(startLocationKey);

        // [Phase 7-V2 Pivot Fix] 캡처한 히로인으로 ChatRoomHeroine + CharacterPresence 재생성
        //   (overwrite 경로와 동일하게 reconfigureHeroinesAndPresences 사용)
        if (!heroines.isEmpty()) {
            reconfigureHeroinesAndPresences(room, heroines, DayPart.defaultStart(), startLocationKey);
        } else {
            log.warn("⚠️ [STORY-V2] Reset: roomId={}에 캡처된 히로인이 없음 — 재생성 스킵", roomId);
        }

        log.info("🔄 [STORY-V2] Reset done: roomId={}, includePersona={}, heroines={}",
            roomId, request.includePersona(), heroines.size());
    }

    /**
     * cascade reset — ChatRoom 본체 + 모든 자식 데이터 일괄 정리.
     * overwrite와 reset 양쪽에서 호출되는 공용 로직.
     */
    private void cascadeResetRoom(ChatRoom room, boolean includePersona) {
        Long roomId = room.getId();

        // 1. 본체 reset (페르소나 옵션)
        room.resetProgress(includePersona);

        // 2. ChatRoomHeroine — 모두 삭제 (호출처가 다시 생성)
        heroineRepository.deleteByChatRoom_Id(roomId);

        // 3. CharacterPresence — 모두 삭제 (호출처가 다시 생성)
        presenceRepository.deleteByChatRoom_Id(roomId);

        // ★ [버그 fix] Hibernate가 DELETE를 지연 flush 하지 않도록 명시 flush.
        //   같은 TX 내에서 reconfigureHeroines가 즉시 INSERT 하므로,
        //   DELETE가 먼저 DB에 반영되어야 unique (room_id, character_id) 제약 위반 회피.
        heroineRepository.flush();
        presenceRepository.flush();

        // 4. RelationPromotionEligibility
        promotionService.clearEligibilitiesForRoom(roomId);

        // 5. OffscreenNotification
        notificationService.clearNotificationsForRoom(roomId);

        // 6. HeroineMemorySummary (캐릭터별 메모리)
        heroineMemoryService.clearMemoriesForRoom(roomId);

        // 7. World-level MemorySummary (기존 RAG 시스템 재활용)
        memorySummaryRepository.deleteByRoomId(roomId);

        // 8. ChatLogDocument (대화 로그)
        chatLogMongoRepository.deleteByRoomId(roomId);

        // 9. [D-5/E-2b] StoryV2State 서사 thread 리셋 — 방은 유지하므로 row 보존 + 빈 배열로.
        storyV2StateRepository.findByRoomId(roomId).ifPresent(st -> {
            st.updateThreads("[]");
            storyV2StateRepository.save(st);
        });
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [5] getRoomDetail — ChatPage 진입 시 방 전체 상태
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional(readOnly = true)
    public StoryRoomV2DetailResponse getRoomDetail(Long roomId, User user) {
        ChatRoom room = chatRoomRepository.findWithMemberAndWorldById(roomId)
            .orElseThrow(() -> new NotFoundException("Room not found"));
        if (!room.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not your room");
        }
        if (!room.isStoryMode()) {
            throw new BadRequestException("Not a V2 STORY room");
        }

        World world = room.getWorld();
        WorldId worldId = world.getId();

        // 히로인 상태들
        List<ChatRoomHeroine> heroineRows = heroineRepository.findByChatRoom_Id(roomId);
        boolean effectiveSecretMode = room.isSecretModeActive() && world.isSecretAllowed()
            && secretModeService.canAccessSecretMode(user);

        List<HeroineStateResponse> heroineStates = heroineRows.stream()
            .filter(h -> h.getCharacter() != null)   // [Phase 7-V2 Pivot Fix] 깨진 heroine(character null) 방어 — 새로고침 500 방지
            .map(h -> toHeroineState(h, effectiveSecretMode))
            .toList();

        // 캐릭터 위치들
        Map<String, String> locationKeyToDisplay = worldLocationRepository
            .findByWorldIdAndActiveTrueOrderByDisplayOrderAsc(worldId).stream()
            .collect(Collectors.toMap(WorldLocation::getLocationKey, WorldLocation::getDisplayName));

        List<CharacterPresence> presences = presenceRepository.findByChatRoom_Id(roomId);
        List<CharacterPresenceResponse> presenceResponses = presences.stream()
            .map(p -> new CharacterPresenceResponse(
                p.getCharacterId(),
                p.getCurrentLocationKey(),
                locationKeyToDisplay.getOrDefault(p.getCurrentLocationKey(), p.getCurrentLocationKey()),
                p.getLastMovedAt()
            ))
            .toList();

        String userLocationDisplay = locationKeyToDisplay
            .getOrDefault(room.getCurrentUserLocationKey(), room.getCurrentUserLocationKey());

        long unreadCount = notificationService.countUnread(roomId);

        return new StoryRoomV2DetailResponse(
            room.getId(),
            worldId.name(),
            world.getDisplayName(),
            room.getEffectivePersona(user),
            room.getEffectiveNickname(user),   // [Phase 7-V2 Pivot] 실효 닉네임
            room.getCurrentUserLocationKey(),
            userLocationDisplay,
            room.getCurrentDay(),
            room.getCurrentDayPart(),
            room.getCurrentBgmMode() != null ? room.getCurrentBgmMode().name() : null,
            room.getCurrentDynamicLocationName(),
            room.getCurrentDynamicBgUrl(),
            room.isTopicConcluded(),
            room.isSecretModeActive(),
            room.isEndingReached(),
            room.getEndingType(),
            room.getEndingTitle(),
            room.isEndingEligible(),
            heroineStates,
            presenceResponses,
            (int) unreadCount
        );
    }

    private HeroineStateResponse toHeroineState(ChatRoomHeroine h, boolean secretMode) {
        Character c = h.getCharacter();
        boolean thoughtUnlocked = h.getCharacterThought() != null && !h.getCharacterThought().isBlank();
        return new HeroineStateResponse(
            c.getId(),
            c.getName(),
            c.getSlug(),              // [Phase 7-V2 Pivot Fix] 스프라이트 에셋 키
            c.getDefaultOutfit(),     // [Bug-Sprite] 기본 복장
            c.getThumbnailUrl(),
            h.getStatIntimacy(), h.getStatAffection(), h.getStatDependency(),
            h.getStatPlayfulness(), h.getStatTrust(),
            secretMode ? h.getStatLust() : null,
            secretMode ? h.getStatCorruption() : null,
            secretMode ? h.getStatObsession() : null,
            h.getStatusLevel(),
            h.getDynamicRelationTag(),
            h.getCurrentBpm(),
            h.getBaseBpm(),
            thoughtUnlocked ? h.getCharacterThought() : null,
            thoughtUnlocked,
            h.getLastSpokenAt()
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  자유 페르소나 BM 권한 — placeholder
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [TODO — 운영 진입 전 연결]
     * 자유 페르소나 BM 권한 체크. 현재 placeholder (true 반환).
     *
     * <p>연결 대상 후보:
     * - {@code UserService.hasFreePersonaUnlock(userId)} — 신규 메서드
     * - {@code SubscriptionService.hasFeature(userId, "FREE_PERSONA")}
     * - 또는 {@code User} 엔티티에 {@code freePersonaUnlocked} boolean 필드 추가
     *
     * <p>현재 모든 유저가 자유 페르소나 가능 — 결제 시스템 연결 후 false로 디폴트.
     */
    private boolean hasFreePersonaUnlock(Long userId) {
        // TODO: 결제 시스템 연결
        return true;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return "";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}