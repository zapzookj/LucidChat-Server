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
import com.spring.aichat.dto.lobby.LobbyPublicDtos;
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
    // [2026-07-30 P2 정적-우선 배선] 방 진입 배경 시딩
    private final com.spring.aichat.service.illustration.BackgroundGenerationService backgroundGenerationService;
    private final HeroineMemoryService heroineMemoryService;
    private final OffscreenNotificationService notificationService;
    private final RelationPromotionService promotionService;
    private final SecretModeService secretModeService;
    // [2026-07-31 에픽 A] enum PK 브리지 — UGC 월드 STORY 개방
    private final WorldViewService worldViewService;
    private final com.spring.aichat.domain.ugc.UgcWorldRepository ugcWorldRepository;
    private final com.spring.aichat.config.UgcModeProperties ugcModeProperties;
    // [2026-08-04 페르소나] 카드 선택 → 방 스냅샷
    private final com.spring.aichat.service.persona.UserPersonaService userPersonaService;

    /**
     * [블록 B 페르소나] 현재 활성 프로필 스냅샷 — 본문·렌즈·성별을 방에 복사(수정 소급 불변)
     * + 스토리 닉네임을 프로필 이름으로 일원화. 공식·UGC 생성/overwrite/리셋 경로 공용.
     * 피커 입력(userPersonaId/personaText/preset/nickname)은 더 이상 소비하지 않는다.
     */
    private void applyProfileSnapshot(ChatRoom room, User user) {
        var profile = userPersonaService.getOrCreateProfile(user);
        room.applyPersonaCard(profile.personaTextOrNull(), profile.statsJson(), profile.getGenderOrDefault());
        room.updateStoryUserNickname(profile.getName());
    }

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
        // [적대적 리뷰 P2] !isHidden() 추가 — CreateFlow 히로인 풀(findBy…HiddenFalse…)·게스트 카드와
        //   카운트를 일치시킨다(hidden 캐릭터가 있으면 카드 숫자만 +1 부풀던 잔존 결함).
        Map<WorldId, Long> heroineCountByWorld = characterRepository.findAll().stream()
            .filter(c -> c.getWorldId() != null && c.isStoryAvailable() && !c.isHidden())
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
    //  [1-b] [블록 A 게스트] 공개 월드 카드 — 시크릿 메타·유저 종속 필드 없는 스코프
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 게스트용 공식 월드 카드 — {@link #listWorlds(User)}의 비로그인 변형.
     * secretAllowed(시크릿 수위 메타)·hasExistingRoom·existingRoomId를 <b>필드 자체로 갖지 않는</b>
     * {@link LobbyPublicDtos.GuestWorldCard}로 반환한다 (게스트=비성인 취급, docs/14 부록 §3).
     */
    @Transactional(readOnly = true)
    public List<LobbyPublicDtos.GuestWorldCard> listWorldsForGuest() {
        List<World> worlds = worldRepository.findByActiveTrueOrderByDisplayOrderAsc();
        if (worlds.isEmpty()) return List.of();

        Map<WorldId, Long> heroineCountByWorld = characterRepository.findAll().stream()
            .filter(c -> c.getWorldId() != null && c.isStoryAvailable() && !c.isHidden())
            .collect(Collectors.groupingBy(Character::getWorldId, Collectors.counting()));

        return worlds.stream()
            .map(w -> new LobbyPublicDtos.GuestWorldCard(
                w.getId().name(),
                w.getDisplayName(),
                w.getTagline(),
                w.getDescription(),
                w.getHeroImageUrl(),
                w.getThumbnailUrl(),
                w.getMoodKeywords(),
                heroineCountByWorld.getOrDefault(w.getId(), 0L).intValue()
            ))
            .toList();
    }

    /**
     * 게스트용 UGC 월드 카드 — 검수 APPROVED이고 PUBLIC 히로인이 있는 월드만.
     * (플레이 자격 정책 {@code WorldViewService.assertUgcPlayable}와 동일 기준 — 목록에 보이는
     * 월드는 로그인 후 실제로 시작 가능하다.) 게이트 off면 빈 목록.
     */
    @Transactional(readOnly = true)
    public List<LobbyPublicDtos.GuestWorldCard> listPublicUgcWorldsForGuest() {
        if (!ugcModeProperties.storyOn()) return List.of();

        return ugcWorldRepository
            .findByReviewStatusOrderByIdDesc(com.spring.aichat.domain.ugc.WorldReviewStatus.APPROVED).stream()
            .map(w -> {
                long publicHeroines = characterRepository
                    .findByUgcWorldIdAndStoryAvailableTrueAndHiddenFalseOrderByIdAsc(w.getId()).stream()
                    .filter(c -> c.getVisibility().isPubliclyVisible())
                    .count();
                if (publicHeroines == 0) return null;
                return new LobbyPublicDtos.GuestWorldCard(
                    com.spring.aichat.domain.world.WorldRef.ofUgc(w.getId()).key(),
                    w.getName(),
                    w.getIntro(),
                    w.getIntro(),            // 카드 설명은 intro 재사용 — lore는 프롬프트 전용(게스트 비노출)
                    w.getThumbnailUrl(),
                    w.getThumbnailUrl(),
                    w.getMoodTags(),
                    (int) publicHeroines
                );
            })
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    /**
     * [블록 A] 회원용 세계관 탭 UGC 카드 — 내 월드(전체) + 타인의 APPROVED·플레이 가능 월드.
     * 타인 월드 진입은 {@code assertUgcPlayable}(소유 또는 APPROVED)이 이미 허용하는 경로라
     * 목록만 새로 연 것이다(docs/14 §B — '새로운 만남 UGC 합류'의 세계관 탭 편입).
     */
    @Transactional(readOnly = true)
    public List<WorldCardResponse> listUgcWorldsCombined(User user) {
        if (!ugcModeProperties.storyOn()) return List.of();

        List<WorldCardResponse> own = listUgcStoryWorlds(user);
        java.util.Set<String> ownKeys = own.stream()
            .map(WorldCardResponse::worldId)
            .collect(Collectors.toSet());

        List<WorldCardResponse> publicOthers = ugcWorldRepository
            .findByReviewStatusOrderByIdDesc(com.spring.aichat.domain.ugc.WorldReviewStatus.APPROVED).stream()
            .filter(w -> !w.isOwnedBy(user.getId()))
            .map(w -> {
                List<Character> heroines = ugcHeroinePool(w.getId(), user);
                if (heroines.isEmpty()) return null;
                WorldView view = worldViewService.resolve(
                    com.spring.aichat.domain.world.WorldRef.ofUgc(w.getId()));
                ChatRoom existing = chatRoomRepository
                    .findByUser_IdAndUgcWorldIdAndChatMode(user.getId(), w.getId(), ChatMode.STORY)
                    .orElse(null);
                return toUgcWorldCard(view, heroines.size(), existing);
            })
            .filter(java.util.Objects::nonNull)
            .filter(card -> !ownKeys.contains(card.worldId()))
            .toList();

        List<WorldCardResponse> combined = new java.util.ArrayList<>(own.size() + publicOthers.size());
        combined.addAll(own);
        combined.addAll(publicOthers);
        return combined;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [2] getCreateContext — CreateFlow 진입 시 일괄 데이터
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [2026-07-31 에픽 A] 통합 진입 — 공식(enum name) / UGC({@code UGCW_{id}}) 문자열 ref 수용.
     */
    @Transactional(readOnly = true)
    public CreateContextResponse getCreateContext(String worldRefRaw, User user) {
        com.spring.aichat.domain.world.WorldRef ref = parseRef(worldRefRaw);
        if (ref.isUgc()) {
            return getUgcCreateContext(ref, user);
        }
        return getCreateContext(ref.officialId(), user);
    }

    @Transactional(readOnly = true)
    public CreateContextResponse getCreateContext(WorldId worldId, User user) {
        World world = worldRepository.findById(worldId)
            .orElseThrow(() -> new BusinessException(ErrorCode.WORLD_NOT_FOUND, "World not found: " + worldId));
        if (!world.isActive()) {
            throw new BadRequestException("Inactive world: " + worldId);
        }

        // 히로인 풀
        List<Character> heroines = characterRepository.findByWorldIdAndStoryAvailableTrueAndHiddenFalseOrderByIdAsc(worldId);
        List<WorldHeroineCardResponse> heroineCards = heroines.stream()
            .map(c -> new WorldHeroineCardResponse(
                c.getId(),
                c.getName(),
                c.getThumbnailUrl(),
                c.getRole(),
                c.getAge() != null ? c.getAge() : 0,
                // 프롬프트 필드(storyBehaviorGuide·personality)는 UI 노출 금지 — 저작 전시 필드만
                truncate(c.getTagline(), 80),
                c.getDifficultyOrDefault().name()
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
    //  [2-b] [에픽 A] UGC 월드 — CreateFlow / 로비 카드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * UGC 월드 CreateFlow — 페르소나 프리셋 없음(자유 텍스트만 — 격리 설계의 기능 축소 확정),
     * 시작 장소는 월드의 전체 활성 장소. 히로인 풀은 접근 가능한(소유 또는 PUBLIC) 캐릭터만.
     */
    private CreateContextResponse getUgcCreateContext(com.spring.aichat.domain.world.WorldRef ref, User user) {
        assertUgcStoryOpen();
        WorldView view = worldViewService.resolve(ref);
        worldViewService.assertUgcPlayable(view, user);

        List<Character> heroines = ugcHeroinePool(ref.ugcWorldId(), user);
        if (heroines.isEmpty()) {
            throw new BadRequestException("이 세계관에서 스토리를 시작할 수 있는 캐릭터가 없어요.");
        }

        List<WorldHeroineCardResponse> heroineCards = heroines.stream()
            .map(c -> new WorldHeroineCardResponse(
                c.getId(), c.getName(), c.getThumbnailUrl(), c.getRole(),
                c.getAge() != null ? c.getAge() : 0,
                // 프롬프트 필드(storyBehaviorGuide·personality)는 UI 노출 금지 — 저작 전시 필드만
                truncate(c.getTagline(), 80),
                c.getDifficultyOrDefault().name()))
            .toList();

        List<WorldLocationResponse> startLocations = view.locations().stream()
            .map(l -> new WorldLocationResponse(l.key(), l.displayName(), l.description(), true, 0))
            .toList();
        if (startLocations.isEmpty()) {
            // [리뷰픽스] 유저 유발 상태(장소 전삭제 월드)는 400 — WORLD_LOCATION_MISSING(500성) 오분류 방지
            throw new BadRequestException("이 세계관에는 시작할 장소가 없어요. 스튜디오에서 장소를 추가해 주세요.");
        }

        Optional<ChatRoom> existing = chatRoomRepository
            .findByUser_IdAndUgcWorldIdAndChatMode(user.getId(), ref.ugcWorldId(), ChatMode.STORY);
        WorldCardResponse worldCard = toUgcWorldCard(view, heroines.size(), existing.orElse(null));

        return new CreateContextResponse(worldCard, heroineCards, List.of(), startLocations,
            hasFreePersonaUnlock(user.getId()));
    }

    /**
     * [에픽 A] 로비 — 유저가 스토리를 시작할 수 있는 UGC 월드 카드(내 월드 한정 v1 —
     * 타인 월드는 캐릭터 프로필 경유 진입이 후속). 게이트 off면 빈 목록(프론트 섹션 자체 미노출).
     */
    @Transactional(readOnly = true)
    public List<WorldCardResponse> listUgcStoryWorlds(User user) {
        if (!ugcModeProperties.storyOn()) return List.of();

        return ugcWorldRepository.findByOwnerUserIdOrderByIdDesc(user.getId()).stream()
            .map(w -> {
                List<Character> heroines = ugcHeroinePool(w.getId(), user);
                if (heroines.isEmpty()) return null;
                WorldView view = worldViewService.resolve(
                    com.spring.aichat.domain.world.WorldRef.ofUgc(w.getId()));
                ChatRoom existing = chatRoomRepository
                    .findByUser_IdAndUgcWorldIdAndChatMode(user.getId(), w.getId(), ChatMode.STORY)
                    .orElse(null);
                return toUgcWorldCard(view, heroines.size(), existing);
            })
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private WorldCardResponse toUgcWorldCard(WorldView view, int heroineCount, ChatRoom existing) {
        return new WorldCardResponse(
            view.ref().key(),
            view.displayName(),
            view.tagline(),
            view.tagline(),          // 카드 설명은 intro 재사용 — lore는 프롬프트 전용(장문·캡슐화 대상)
            view.heroImageUrl(),
            view.thumbnailUrl(),
            view.moodKeywords(),
            false,                   // UGC 월드 시크릿 불허(확정 정책)
            heroineCount,
            existing != null,
            existing != null ? existing.getId() : null
        );
    }

    /** UGC 히로인 풀 — storyAvailable·비숨김·유저 접근 가능(소유 또는 PUBLIC)만. */
    private List<Character> ugcHeroinePool(Long ugcWorldId, User user) {
        return characterRepository
            .findByUgcWorldIdAndStoryAvailableTrueAndHiddenFalseOrderByIdAsc(ugcWorldId).stream()
            .filter(c -> c.isAccessibleBy(user.getId()))
            .toList();
    }

    private void assertUgcStoryOpen() {
        if (!ugcModeProperties.storyOn()) {
            throw new BadRequestException("UGC 세계관 스토리 모드는 아직 준비 중이에요.");
        }
    }

    /**
     * [리뷰픽스] UGC 방 접근 재검증 — SSE 가드(blockIfUgcStoryInaccessible)의 REST 대칭.
     * 월드 재잠금(수정 시 NONE 리셋)·캐릭터 철회 이후 상세/리셋 경로의 우회 접근 차단.
     */
    private void assertUgcRoomAccessible(ChatRoom room, User user) {
        if (!room.isUgcWorldStory()) return;
        var ugcWorld = ugcWorldRepository.findById(room.getUgcWorldId()).orElse(null);
        if (ugcWorld == null || (!ugcWorld.isOwnedBy(user.getId())
            && ugcWorld.getReviewStatus() != com.spring.aichat.domain.ugc.WorldReviewStatus.APPROVED)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 세계관은 더 이상 이용할 수 없어요.");
        }
        boolean anyBlocked = heroineRepository.findByChatRoom_Id(room.getId()).stream()
            .map(ChatRoomHeroine::getCharacter)
            .filter(java.util.Objects::nonNull)
            .anyMatch(c -> c.isUgc() && !c.isAccessibleBy(user.getId()));
        if (anyBlocked) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 캐릭터는 더 이상 대화할 수 없어요.");
        }
    }

    private com.spring.aichat.domain.world.WorldRef parseRef(String raw) {
        try {
            return com.spring.aichat.domain.world.WorldRef.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid worldId: " + raw);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [3] createOrReuseRoom — 방 생성 (또는 overwrite 시 reset)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public CreateStoryV2Response createOrReuseRoom(User user, CreateStoryV2Request request) {
        // [에픽 A] UGC 월드 분기 — UGCW_ 접두 ref
        com.spring.aichat.domain.world.WorldRef ref = parseRef(request.worldId());
        if (ref.isUgc()) {
            return createOrReuseUgcRoom(user, ref, request);
        }
        WorldId worldId = ref.officialId();

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
            // overwrite — 페르소나 포함 완전 reset 후 현재 프로필 재적용
            log.info("🔄 [STORY-V2] Overwriting existing room: roomId={}", room.getId());
            cascadeResetRoom(room, true);
            room.restoreStartLocation(startLocationKey);
            applyProfileSnapshot(room, user);   // [블록 B] 현재 프로필 자동 적용

            // 히로인/위치 재구성
            reconfigureHeroinesAndPresences(room, heroines, DayPart.defaultStart(), startLocationKey);

            // [2026-07-30 P2 정적-우선 배선] 방 진입 배경 시딩 (overwrite에도 동일)
            seedOfficialWorldBackground(room, worldId, startLocationKey, world);

            return new CreateStoryV2Response(room.getId(), worldId.name(), false, true);
        }

        // 신규 생성 — 페르소나·닉네임은 applyProfileSnapshot이 현재 프로필로 세팅
        ChatRoom room = ChatRoom.createStoryV2(user, world, startLocationKey, null, null);
        applyProfileSnapshot(room, user);   // [블록 B] 현재 프로필 자동 적용
        room = chatRoomRepository.save(room);

        // [D-5/E-2b] 서사 나침반 상태 초기화 — 빈 thread로 시작(백본 미리 심지 않음).
        storyV2StateRepository.save(StoryV2State.create(room.getId()));

        reconfigureHeroinesAndPresences(room, heroines, DayPart.defaultStart(), startLocationKey);

        // [2026-07-30 P2 정적-우선 배선] 방 진입 배경 시딩 — 시작 장소의 정적-우선 배경
        // (seedUgcWorldBackground의 공식판 — 캐시 히트면 즉시, 미스면 1회 생성 후 영구 캐시)
        seedOfficialWorldBackground(room, worldId, startLocationKey, world);

        log.info("✨ [STORY-V2] Created new room: roomId={}, worldId={}, heroineCount={}, startLocation={}",
            room.getId(), worldId, heroines.size(), startLocationKey);

        return new CreateStoryV2Response(room.getId(), worldId.name(), true, false);
    }

    /**
     * [2026-07-31 에픽 A] UGC 월드 STORY 방 생성 — 공식 흐름의 미러.
     * 접근: 월드 플레이 자격(소유 또는 APPROVED) + 히로인 접근권·storyAvailable·월드 소속.
     * 페르소나는 자유 텍스트만(프리셋 지정 시 400). 배경은 장소 대표 배경 직시딩.
     */
    private CreateStoryV2Response createOrReuseUgcRoom(User user,
                                                       com.spring.aichat.domain.world.WorldRef ref,
                                                       CreateStoryV2Request request) {
        assertUgcStoryOpen();
        WorldView view = worldViewService.resolve(ref);
        worldViewService.assertUgcPlayable(view, user);

        // 히로인 검증 — 월드 소속·storyAvailable·접근권
        List<Character> heroines = characterRepository.findAllById(request.heroineIds());
        if (heroines.size() != request.heroineIds().size()) {
            throw new BadRequestException("Some heroine IDs not found");
        }
        for (Character h : heroines) {
            if (!ref.ugcWorldId().equals(h.getUgcWorldId())) {
                throw new BadRequestException(
                    "Heroine " + h.getId() + " does not belong to world " + ref.key());
            }
            if (!h.isStoryAvailable() || h.isHidden()) {
                throw new BadRequestException("Inactive heroine: " + h.getId());
            }
            if (!h.isAccessibleBy(user.getId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "접근할 수 없는 캐릭터예요: " + h.getId());
            }
        }

        // 시작 장소 — 요청 키 검증 or 첫 활성 장소
        String startLocationKey = resolveUgcStartLocation(view, request.startLocationKey());

        // 기존 방 체크 — 'UGC 월드당 1방'
        Optional<ChatRoom> existing = chatRoomRepository
            .findByUser_IdAndUgcWorldIdAndChatMode(user.getId(), ref.ugcWorldId(), ChatMode.STORY);

        if (existing.isPresent()) {
            ChatRoom room = existing.get();
            if (!Boolean.TRUE.equals(request.overwriteExisting())) {
                throw new BusinessException(ErrorCode.STORY_V2_ROOM_EXISTS,
                    "Existing room found: roomId=" + room.getId());
            }
            log.info("🔄 [STORY-V2-UGC] Overwriting existing room: roomId={}", room.getId());
            cascadeResetRoom(room, true);
            room.restoreStartLocation(startLocationKey);
            applyProfileSnapshot(room, user);   // [블록 B] 현재 프로필 자동 적용
            reconfigureHeroinesAndPresences(room, heroines, DayPart.defaultStart(), startLocationKey);
            seedUgcStoryBackground(room, view, startLocationKey);
            return new CreateStoryV2Response(room.getId(), ref.key(), false, true);
        }

        ChatRoom room = ChatRoom.createStoryV2Ugc(user, ref.ugcWorldId(), startLocationKey,
            null, null);
        applyProfileSnapshot(room, user);   // [블록 B] 현재 프로필 자동 적용
        try {
            room = chatRoomRepository.saveAndFlush(room);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // [리뷰픽스] 동시 생성 레이스 — uk_user_ugc_world_mode 위반을 500이 아닌 409로
            // (기존 방 조회와 save 사이 창에서 다른 요청이 먼저 생성한 경우)
            throw new BusinessException(ErrorCode.STORY_V2_ROOM_EXISTS,
                "Existing room found (concurrent create): world=" + ref.key());
        }
        storyV2StateRepository.save(StoryV2State.create(room.getId()));
        reconfigureHeroinesAndPresences(room, heroines, DayPart.defaultStart(), startLocationKey);
        seedUgcStoryBackground(room, view, startLocationKey);

        log.info("✨ [STORY-V2-UGC] Created new room: roomId={}, world={}, heroineCount={}, startLocation={}",
            room.getId(), ref.key(), heroines.size(), startLocationKey);

        return new CreateStoryV2Response(room.getId(), ref.key(), true, false);
    }

    private String resolveUgcStartLocation(WorldView view, String requestedKey) {
        if (requestedKey != null && !requestedKey.isBlank()) {
            if (view.location(requestedKey).isEmpty()) {
                throw new BadRequestException("Invalid start location: " + requestedKey);
            }
            return requestedKey;
        }
        return view.locations().stream()
            .findFirst()
            .map(WorldView.LocationView::key)
            .orElseThrow(() -> new BadRequestException(
                "이 세계관에는 시작할 장소가 없어요. 스튜디오에서 장소를 추가해 주세요."));
    }

    /**
     * [에픽 A] UGC 월드 방 진입 배경 — 장소 대표 배경(빌더 확정본) 직시딩.
     * 시작 장소에 배경이 없으면 배경 보유 첫 장소 폴백(LobbyService.seedUgcWorldBackground 관례).
     */
    private void seedUgcStoryBackground(ChatRoom room, WorldView view, String startLocationKey) {
        try {
            WorldView.LocationView start = view.location(startLocationKey).orElse(null);
            if (start != null && start.backgroundUrl() != null && !start.backgroundUrl().isBlank()) {
                room.updateDynamicBackground(start.displayName(),
                    view.canonicalKey(start.key()), start.backgroundUrl());
                return;
            }
            for (WorldView.LocationView loc : view.locations()) {
                if (loc.backgroundUrl() != null && !loc.backgroundUrl().isBlank()) {
                    room.updateDynamicBackground(loc.displayName(),
                        view.canonicalKey(loc.key()), loc.backgroundUrl());
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("🏠 [STORY-V2-UGC] 배경 시딩 실패 (non-blocking): roomId={}, {}",
                room.getId(), e.getMessage());
        }
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

    /**
     * [2026-07-30 P2 정적-우선 배선] 시작 장소 배경 시딩 — {@code seedUgcWorldBackground}의 공식판.
     * 결정론 키({WORLD}__{LOCATION_KEY})로 캐시 조회: 히트면 즉시 세팅, 미스면 비동기 1회 생성
     * (완성분은 영구 캐시 — 이후 모든 방 진입·이동에서 재사용). 실패는 비차단.
     */
    private void seedOfficialWorldBackground(ChatRoom room, WorldId worldId, String startLocationKey,
                                             com.spring.aichat.domain.world.World world) {
        try {
            WorldLocation loc = worldLocationRepository
                .findByWorldIdAndLocationKey(worldId, startLocationKey).orElse(null);
            if (loc == null) return;

            String canonicalKey = worldId.name() + "__" + loc.getLocationKey();
            String timeOfDay = DayPart.defaultStart().toBackgroundTimeOfDay().name();
            String description = (loc.getDescription() != null && !loc.getDescription().isBlank())
                ? loc.getDescription() : loc.getDisplayName();

            com.spring.aichat.service.illustration.BackgroundGenerationService.BackgroundResult bg
                = backgroundGenerationService.resolveBackground(
                loc.getDisplayName(), canonicalKey, description, timeOfDay, 0L);
            if (bg.cacheHit()) {
                room.updateDynamicBackground(loc.getDisplayName(), canonicalKey, bg.imageUrl());
            } else {
                room.updateDynamicLocationName(loc.getDisplayName(), canonicalKey);
                backgroundGenerationService.generateBackgroundAsync(
                    loc.getDisplayName(), canonicalKey, description, timeOfDay, 0L, world, false);
            }
        } catch (Exception e) {
            log.warn("🏠 [STORY-V2] 시작 장소 배경 시딩 실패 (non-blocking): roomId={}, {}",
                room.getId(), e.getMessage());
        }
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

        // [리뷰픽스] UGC 방 접근 재검증 — 철회/재잠금된 방의 리셋(미검수 수정본 재주입) 차단
        assertUgcRoomAccessible(room, user);

        cascadeResetRoom(room, request.includePersona());

        // 시작 장소 갱신 — [에픽 A] UGC 방은 UGC 장소 풀 기준
        String startLocationKey;
        if (room.isUgcWorldStory()) {
            WorldView view = worldViewService.resolveForRoom(room);
            startLocationKey = resolveUgcStartLocation(view, request.startLocationKey());
            room.restoreStartLocation(startLocationKey);
            seedUgcStoryBackground(room, view, startLocationKey);
        } else {
            startLocationKey = resolveStartLocation(room.getWorld().getId(), request.startLocationKey());
            room.restoreStartLocation(startLocationKey);
        }

        // [블록 B 페르소나] '페르소나 초기화' = 현재 프로필 다시 적용 (개념 역전 후 의미 재정의)
        if (request.includePersona()) {
            applyProfileSnapshot(room, user);
        }

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

        // [에픽 A] 공식/UGC 공용 월드 뷰
        WorldView worldView = worldViewService.resolveForRoom(room);
        if (worldView == null) {
            throw new BadRequestException("STORY room without world: " + roomId);
        }
        // [리뷰픽스] UGC 방 접근 재검증 — 철회/재잠금된 방 상세 열람 차단(SSE 가드와 대칭)
        assertUgcRoomAccessible(room, user);

        // 히로인 상태들 — UGC 월드는 secretAllowed=false 확정이라 시크릿 스탯 상시 차단
        List<ChatRoomHeroine> heroineRows = heroineRepository.findByChatRoom_Id(roomId);
        boolean effectiveSecretMode = room.isSecretModeActive() && worldView.secretAllowed()
            && secretModeService.canAccessSecretMode(user);

        List<HeroineStateResponse> heroineStates = heroineRows.stream()
            .filter(h -> h.getCharacter() != null)   // [Phase 7-V2 Pivot Fix] 깨진 heroine(character null) 방어 — 새로고침 500 방지
            .map(h -> toHeroineState(h, effectiveSecretMode))
            .toList();

        // 캐릭터 위치들
        Map<String, String> locationKeyToDisplay = worldView.locations().stream()
            .collect(Collectors.toMap(WorldView.LocationView::key, WorldView.LocationView::displayName));

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
            worldView.ref().key(),
            worldView.displayName(),
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
            c.getDefaultImageUrl(),   // [2026-08-06 UGC 스프라이트 CDN 픽스] assetDir 유도용
            h.getStatIntimacy(), h.getStatAffection(), h.getStatDependency(),
            h.getStatPlayfulness(), h.getStatTrust(),
            secretMode ? h.getStatLust() : null,
            secretMode ? h.getStatCorruption() : null,
            secretMode ? h.getStatObsession() : null,
            h.getStatusLevel(),
            h.getDynamicRelationTag(),
            thoughtUnlocked ? h.getCharacterThought() : null,
            thoughtUnlocked,
            h.getLastSpokenAt()
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  자유 페르소나 BM 권한 — placeholder
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [블록 B BM 확정 — docs/14 #4] 자유 입력·스탯은 전면 무료(과금은 슬롯 수만).
     * 이 플래그는 CreateContextResponse 하위호환용으로만 true 고정 유지 — 게이트로 쓰지 말 것.
     */
    private boolean hasFreePersonaUnlock(Long userId) {
        return true;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}