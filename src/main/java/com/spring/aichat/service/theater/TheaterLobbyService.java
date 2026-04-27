package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.AvatarStat;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.theater.*;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.theater.AvatarProfile;
import com.spring.aichat.dto.theater.TheaterRequests.CreateTheaterSessionRequest;
import com.spring.aichat.dto.theater.TheaterRequests.InitialStatDistribution;
import com.spring.aichat.dto.theater.TheaterResponses.*;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.service.cache.RedisCacheService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * [Phase 5.5-Theater] Theater 로비 서비스
 *
 * 기존 LobbyService(Dialogue 그룹)와 병렬로 동작.
 * 세계관 목록 조회, Theater 세션 생성, 히로인 선택, 아바타 빌더 등을 담당.
 *
 * [핵심 책임]
 * 1. 세계관 목록 조회 (세계관별 히로인 요약 포함)
 * 2. Theater 세션 생성 (ChatRoom + TheaterState + TheaterHeroineAffection 원자적 생성)
 * 3. 유저의 Theater 세션 목록 조회
 * 4. 초기 스탯 분배 검증 (구독 티어별 포인트 상한)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TheaterLobbyService {

    private final WorldRepository worldRepository;
    private final CharacterRepository characterRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final TheaterStateRepository theaterStateRepository;
    private final TheaterHeroineAffectionRepository heroineAffectionRepository;
    private final UserRepository userRepository;
    private final RedisCacheService cacheService;
    private final ObjectMapper objectMapper;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  초기 스탯 분배 정책 (구독 티어별)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 무료 유저: 초기 분배 불가 (총 0) */
    private static final int FREE_TOTAL_POINTS = 0;
    private static final int FREE_PER_STAT_MAX = 0;

    /** Lucid Pass Standard: 총 20포인트, 단일 스탯 최대 10 */
    private static final int STANDARD_TOTAL_POINTS = 20;
    private static final int STANDARD_PER_STAT_MAX = 10;

    /** Lucid Pass Premium: 총 40포인트, 단일 스탯 최대 20 */
    private static final int PREMIUM_TOTAL_POINTS = 40;
    private static final int PREMIUM_PER_STAT_MAX = 20;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 세계관 목록 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public List<WorldCard> getWorldCards() {
        List<World> worlds = worldRepository.findByActiveTrueOrderByDisplayOrderAsc();
        List<WorldCard> cards = new ArrayList<>(worlds.size());

        for (World world : worlds) {
            List<Character> heroines = characterRepository
                .findByWorldIdAndTheaterAvailableTrue(world.getId());

            List<HeroineSummary> summaries = heroines.stream()
                .map(c -> new HeroineSummary(
                    c.getId(), c.getName(), c.getSlug(),
                    c.getTagline(), c.getThumbnailUrl()))
                .toList();

            List<String> keywords = world.getMoodKeywords() == null
                ? List.of()
                : Arrays.stream(world.getMoodKeywords().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

            cards.add(new WorldCard(
                world.getId().name(),
                world.getDisplayName(),
                world.getTagline(),
                world.getDescription(),
                world.getHeroImageUrl(),
                world.getThumbnailUrl(),
                keywords,
                world.isSecretAllowed(),
                heroines.size(),
                summaries
            ));
        }
        return cards;
    }

    /** 단일 세계관 조회 (세부 페이지용) */
    public WorldCard getWorldCard(String worldIdStr) {
        WorldId wid = WorldId.fromStringOrNull(worldIdStr);
        if (wid == null) throw new BadRequestException("존재하지 않는 세계관입니다.");

        return getWorldCards().stream()
            .filter(w -> w.id().equals(wid.name()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("세계관을 찾을 수 없습니다."));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 유저의 Theater 세션 목록
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public List<TheaterSessionCard> getMyTheaterSessions(String username) {
        User user = findUser(username);
        List<ChatRoom> theaterRooms = chatRoomRepository
            .findAllByUser_IdAndChatModeOrderByLastActiveAtDesc(user.getId(), ChatMode.THEATER);

        List<TheaterSessionCard> cards = new ArrayList<>();
        for (ChatRoom room : theaterRooms) {
            TheaterState state = theaterStateRepository.findByRoom_Id(room.getId()).orElse(null);
            if (state == null) continue;

            World world = worldRepository.findById(state.getWorldId()).orElse(null);
            String worldDisplayName = world != null ? world.getDisplayName() : state.getWorldId().name();

            TheaterHeroineAffection lead = heroineAffectionRepository
                .findByRoomOrderByAffectionDesc(room.getId())
                .stream().findFirst().orElse(null);

            cards.add(new TheaterSessionCard(
                room.getId(),
                state.getWorldId().name(),
                worldDisplayName,
                state.getAvatarName() != null ? state.getAvatarName() : user.getNickname(),
                state.getCurrentAct().getNumber(),
                state.getCurrentChapter(),
                lead != null ? lead.getCharacter().getId() : null,
                lead != null ? lead.getCharacter().getName() : null,
                lead != null ? lead.getCharacter().getSlug() : null,              // [Polish-v2] leadHeroineSlug
                lead != null ? lead.getCharacter().getThumbnailUrl() : null,      // [Polish-v2] leadHeroineThumbnailUrl
                lead != null ? lead.getAffection() : 0,
                state.isEndingReached(),
                state.getEndingTitle(),
                state.getTotalSceneCount(),
                room.getLastActiveAt()
            ));
        }
        return cards;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. Theater 세션 생성
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Theater 세션 생성
     *
     * [원자적 처리]
     * - ChatRoom 생성 (chatMode=THEATER, character=대표 히로인)
     * - TheaterState 생성 (아바타, 스탯)
     * - TheaterHeroineAffection 생성 (히로인 수만큼)
     *
     * [중복 생성 방지]
     * - 같은 유저 + 같은 세계관 + 같은 대표 히로인 조합 존재 시 기존 방 반환 (Idempotent)
     *
     * @return 생성된(또는 기존) 방 ID + 초기 정보
     */
    @Transactional
    public TheaterRoomInfo createSession(String username, CreateTheaterSessionRequest request) {
        User user = findUser(username);

        // ─── 1. 세계관 검증 ───
        WorldId worldId = WorldId.fromStringOrNull(request.worldId());
        if (worldId == null) throw new BadRequestException("존재하지 않는 세계관입니다: " + request.worldId());

        World world = worldRepository.findById(worldId)
            .orElseThrow(() -> new NotFoundException("세계관 마스터 데이터가 없습니다."));
        if (!world.isActive()) throw new BadRequestException("현재 이용할 수 없는 세계관입니다.");

        // ─── 2. 히로인 검증 ───
        List<Long> heroineIds = request.heroineIds();
        if (heroineIds == null || heroineIds.isEmpty()) {
            throw new BadRequestException("히로인을 최소 1명 선택해야 합니다.");
        }
        if (heroineIds.size() > 3) {
            throw new BadRequestException("히로인은 최대 3명까지 선택할 수 있습니다.");
        }

        List<Character> heroines = characterRepository.findAllById(heroineIds);
        if (heroines.size() != heroineIds.size()) {
            throw new BadRequestException("존재하지 않는 히로인이 포함되어 있습니다.");
        }

        for (Character c : heroines) {
            if (!c.isTheaterAvailable()) {
                throw new BadRequestException("Theater를 지원하지 않는 히로인입니다: " + c.getName());
            }
            if (c.getWorldId() != worldId) {
                throw new BadRequestException(
                    String.format("%s는 %s 세계관에 속하지 않습니다.", c.getName(), world.getDisplayName()));
            }
        }

        // ─── 3. 대표 히로인 (첫 번째) ───
        Long leadHeroineId = heroineIds.get(0);
        Character leadHeroine = heroines.stream()
            .filter(c -> c.getId().equals(leadHeroineId))
            .findFirst()
            .orElseThrow();

        // ─── 4. 초기 스탯 검증 ───
        validateInitialStats(user, request.initialStats());

        // ─── 5. 기존 방 체크 (Idempotent) ───
        Optional<ChatRoom> existing = chatRoomRepository
            .findByUser_IdAndCharacter_IdAndChatMode(user.getId(), leadHeroineId, ChatMode.THEATER);

        if (existing.isPresent()) {
            // 같은 세계관의 기존 Theater 방이 있으면 재진입
            TheaterState existingState = theaterStateRepository.findByRoom_Id(existing.get().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "TheaterState 일관성 오류: room=" + existing.get().getId()));
            if (existingState.getWorldId() == worldId) {
                log.info("🎭 [THEATER] Existing session returned | roomId={} | world={} | user={}",
                    existing.get().getId(), worldId, username);
                return buildRoomInfo(existing.get(), existingState);
            }
            throw new BadRequestException(
                "이미 다른 세계관의 Theater 방이 존재합니다. 새 세션을 만들려면 기존 방을 삭제해주세요.");
        }

        // ─── 6. ChatRoom 생성 ───
        ChatRoom room = new ChatRoom(user, leadHeroine, ChatMode.THEATER);
        room = chatRoomRepository.save(room);
        cacheService.cacheRoomOwner(room.getId(), user.getUsername());

        // ─── 7. 아바타 프로필 JSON 직렬화 ───
        AvatarProfile profile = request.avatarProfile() != null ? request.avatarProfile() : AvatarProfile.empty();
        String avatarJson = serializeProfile(profile);

        String avatarName = resolveAvatarName(request.avatarName(), profile, user);
        String personaText = request.personaText() != null ? request.personaText().trim() : "";

        // ─── 8. TheaterState 생성 ───
        TheaterState.AvatarStatDistribution distribution = toDistribution(request.initialStats());
        TheaterState state = TheaterState.create(
            room, worldId, avatarName, avatarJson, personaText, distribution
        );
        state = theaterStateRepository.save(state);

        // ─── 9. 히로인별 호감도 레코드 생성 ───
        for (Character heroine : heroines) {
            TheaterHeroineAffection affection = TheaterHeroineAffection.create(room, heroine);
            heroineAffectionRepository.save(affection);
        }

        log.info("🎭 [THEATER] New session created | roomId={} | world={} | heroines={} | user={}",
            room.getId(), worldId, heroineIds, username);

        return buildRoomInfo(room, state);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  4. 방 정보 조회 (재진입 시)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public TheaterRoomInfo getRoomInfo(Long roomId, String username) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방을 찾을 수 없습니다."));

        if (!room.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "접근 권한이 없습니다.");
        }
        if (room.getChatMode() != ChatMode.THEATER) {
            throw new BadRequestException("Theater 모드 방이 아닙니다.");
        }

        TheaterState state = theaterStateRepository.findByRoom_Id(roomId)
            .orElseThrow(() -> new NotFoundException("Theater 세션 데이터가 없습니다."));

        return buildRoomInfo(room, state);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  5. 아바타 업데이트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public void updateAvatar(Long roomId, String username, String newName,
                             AvatarProfile newProfile, String newPersonaText) {
        verifyRoomOwnership(roomId, username);

        TheaterState state = theaterStateRepository.findByRoom_Id(roomId)
            .orElseThrow(() -> new NotFoundException("Theater 세션 데이터가 없습니다."));

        String profileJson = newProfile != null ? serializeProfile(newProfile) : null;
        state.updateAvatarProfile(newName, profileJson, newPersonaText);

        log.info("🎭 [THEATER] Avatar updated | roomId={} | user={}", roomId, username);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  6. 리롤권 — 스탯 재분배 (구매 아이템)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public void rerollStats(Long roomId, String username, InitialStatDistribution newDistribution) {
        User user = findUser(username);
        verifyRoomOwnership(roomId, username);

        validateInitialStats(user, newDistribution);

        TheaterState state = theaterStateRepository.findByRoom_Id(roomId)
            .orElseThrow(() -> new NotFoundException("Theater 세션 데이터가 없습니다."));

        // 리롤은 세션 초반(Act 1, 총 씬 50 미만)에만 허용
        if (state.getTotalSceneCount() >= 50) {
            throw new BadRequestException(
                "리롤권은 세션 초반에만 사용할 수 있습니다. (현재 누적 씬: " + state.getTotalSceneCount() + ")");
        }

        // 리롤은 덮어쓰기 (증가분이 아닌 재분배)
        state.applyStatChange(AvatarStat.CHARM, newDistribution.charm() - state.getStatCharm());
        state.applyStatChange(AvatarStat.WIT, newDistribution.wit() - state.getStatWit());
        state.applyStatChange(AvatarStat.BOLDNESS, newDistribution.boldness() - state.getStatBoldness());
        state.applyStatChange(AvatarStat.INTELLECT, newDistribution.intellect() - state.getStatIntellect());
        state.applyStatChange(AvatarStat.EMPATHY, newDistribution.empathy() - state.getStatEmpathy());

        log.info("🎭 [THEATER] Stats rerolled | roomId={} | new={}", roomId, newDistribution);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Private helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private User findUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다: " + username));
    }

    private void verifyRoomOwnership(Long roomId, String username) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        if (!room.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "접근 권한이 없습니다.");
        }
    }

    /**
     * 초기 스탯 분배 검증 — 구독 티어별 포인트 상한
     */
    private void validateInitialStats(User user, InitialStatDistribution dist) {
        if (dist == null) return; // null이면 전부 0 (무료 유저 기본)

        // 음수 금지
        if (dist.charm() < 0 || dist.wit() < 0 || dist.boldness() < 0
            || dist.intellect() < 0 || dist.empathy() < 0) {
            throw new BadRequestException("스탯 값은 음수일 수 없습니다.");
        }

        int totalPoints = dist.total();
        int maxTotal;
        int maxPerStat;

        if (user.getSubscriptionTier() == null) {
            maxTotal = FREE_TOTAL_POINTS;
            maxPerStat = FREE_PER_STAT_MAX;
        } else {
            switch (user.getSubscriptionTier()) {
                case LUCID_PASS, LUCID_MIDNIGHT_PASS -> {
                    maxTotal = STANDARD_TOTAL_POINTS;
                    maxPerStat = STANDARD_PER_STAT_MAX;
                }
                case LUCID_PASS_PREMIUM -> {
                    maxTotal = PREMIUM_TOTAL_POINTS;
                    maxPerStat = PREMIUM_PER_STAT_MAX;
                }
                default -> {
                    maxTotal = FREE_TOTAL_POINTS;
                    maxPerStat = FREE_PER_STAT_MAX;
                }
            }
        }

        if (totalPoints > maxTotal) {
            throw new BadRequestException(
                String.format("스탯 총합이 한도를 초과했습니다. (현재: %d, 최대: %d)", totalPoints, maxTotal));
        }

        if (dist.charm() > maxPerStat || dist.wit() > maxPerStat || dist.boldness() > maxPerStat
            || dist.intellect() > maxPerStat || dist.empathy() > maxPerStat) {
            throw new BadRequestException(
                String.format("단일 스탯이 한도를 초과했습니다. (최대: %d)", maxPerStat));
        }
    }

    private TheaterState.AvatarStatDistribution toDistribution(InitialStatDistribution dist) {
        if (dist == null) return new TheaterState.AvatarStatDistribution(0, 0, 0, 0, 0);
        return new TheaterState.AvatarStatDistribution(
            dist.charm(), dist.wit(), dist.boldness(), dist.intellect(), dist.empathy());
    }

    private String serializeProfile(AvatarProfile profile) {
        if (profile == null || profile.isBlank()) return null;
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (JsonProcessingException e) {
            log.warn("🎭 [THEATER] Avatar profile serialization failed: {}", e.getMessage());
            return null;
        }
    }

    private AvatarProfile deserializeProfile(String json) {
        if (json == null || json.isBlank()) return AvatarProfile.empty();
        try {
            return objectMapper.readValue(json, AvatarProfile.class);
        } catch (JsonProcessingException e) {
            log.warn("🎭 [THEATER] Avatar profile deserialization failed: {}", e.getMessage());
            return AvatarProfile.empty();
        }
    }

    private String resolveAvatarName(String requested, AvatarProfile profile, User user) {
        if (requested != null && !requested.isBlank()) return requested.trim();
        if (profile != null && profile.name() != null && !profile.name().isBlank()) return profile.name().trim();
        return user.getNickname();
    }

    /**
     * TheaterRoomInfo DTO 조립
     */
    private TheaterRoomInfo buildRoomInfo(ChatRoom room, TheaterState state) {
        World world = worldRepository.findById(state.getWorldId()).orElse(null);
        String worldDisplayName = world != null ? world.getDisplayName() : state.getWorldId().name();

        // 아바타 스냅샷
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (AvatarStat s : AvatarStat.values()) {
            stats.put(s.name(), state.getStat(s));
        }
        AvatarProfile profile = deserializeProfile(state.getAvatarProfileJson());

        AvatarSnapshot avatar = new AvatarSnapshot(
            state.getAvatarName(),
            stats,
            profile,
            state.getAvatarPersonaText()
        );

        // 서사 진행
        Character currentHeroine = null;
        if (state.getCurrentHeroineId() != null) {
            currentHeroine = characterRepository.findById(state.getCurrentHeroineId()).orElse(null);
        }

        NarrativeProgress progress = new NarrativeProgress(
            state.getCurrentAct().getNumber(),
            state.getCurrentAct().getTitle(),
            state.getCurrentChapter(),
            state.getScenesInCurrentChapter(),
            state.getChapterTargetScenes(),
            ChatModePolicy.THEATER_CHAPTERS_PER_ACT_MIN,   // [Polish-v2] actTotalChapters 추가
            state.getTotalSceneCount(),
            state.getCurrentBatchId(),
            currentHeroine != null ? currentHeroine.getId() : null,
            currentHeroine != null ? currentHeroine.getName() : null,
            state.isInIntermission(),
            state.getIntermissionStamina()
        );

        // 히로인 목록
        List<TheaterHeroineAffection> affections = heroineAffectionRepository
            .findByRoomOrderByAffectionDesc(room.getId());

        List<HeroineAffectionSnapshot> heroineSnapshots = affections.stream()
            .map(a -> new HeroineAffectionSnapshot(
                a.getCharacter().getId(),
                a.getCharacter().getName(),
                a.getCharacter().getSlug(),
                a.getCharacter().getThumbnailUrl(),
                a.getAffection(),
                a.getLastChapterDelta(),
                a.getTotalScenes(),
                a.isConfirmedMain()
            )).toList();

        PlaySettings playSettings = new PlaySettings(
            state.isAutoPlayEnabled(),
            state.getPlaySpeed()
        );

        return new TheaterRoomInfo(
            room.getId(),
            state.getWorldId().name(),
            worldDisplayName,
            state.getAvatarName(),    // [Polish-v2] flat avatarName 추가
            avatar,
            progress,
            heroineSnapshots,
            playSettings,
            state.isInterventionActive(),
            state.isEndingReached(),
            state.getEndingTitle()
        );
    }
}