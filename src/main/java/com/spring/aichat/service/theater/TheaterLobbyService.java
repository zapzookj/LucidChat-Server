package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.AvatarStat;
import com.spring.aichat.domain.enums.BranchLevel;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.enums.WorldId;
import com.spring.aichat.domain.theater.*;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.domain.world.World;
import com.spring.aichat.domain.world.WorldRepository;
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
    // [Polish · LOCATION fix] requiresLocationChoice 결정 시 분기 기록 조회용
    private final TheaterBranchChoiceRepository branchChoiceRepository;
    private final UserRepository userRepository;
    private final RedisCacheService cacheService;
    private final ObjectMapper objectMapper;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  초기 스탯 분배 정책 (구독 티어별)
    //
    //  매핑:
    //   - 미구독              → FREE     (0p / 분배 잠김)
    //   - LUCID_PASS          → STANDARD (20p, perStat 10)
    //   - LUCID_MIDNIGHT_PASS → PREMIUM  (40p, perStat 20)
    //   - LUCID_PASS_PREMIUM  → FREE     (deprecated 데드 enum 값 — fallback)
    //
    //  ⚠️ LUCID_MIDNIGHT_PASS는 향후 "분배 무제한" 정책으로 전환될 예정.
    //     현재는 임시로 40/20 캡을 적용한다.
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 무료 유저: 초기 분배 불가 (총 0) */
    private static final int FREE_TOTAL_POINTS = 0;
    private static final int FREE_PER_STAT_MAX = 0;

    /** LUCID_PASS (Standard, 14,900원/월): 총 20포인트, 단일 스탯 최대 10 */
    private static final int STANDARD_TOTAL_POINTS = 20;
    private static final int STANDARD_PER_STAT_MAX = 10;

    /** LUCID_MIDNIGHT_PASS (Premium, 24,900원/월): 총 40포인트, 단일 스탯 최대 20 */
    private static final int PREMIUM_TOTAL_POINTS = 500;
    private static final int PREMIUM_PER_STAT_MAX = 100;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 세계관 목록 조회
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public List<WorldCard> getWorldCards() {
        List<World> worlds = worldRepository.findByActiveTrueOrderByDisplayOrderAsc();
        List<WorldCard> cards = new ArrayList<>(worlds.size());

        for (World world : worlds) {
            List<Character> heroines = characterRepository
                .findByWorldIdAndTheaterAvailableTrueAndHiddenFalse(world.getId());

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
                room.getLastActiveAt(),
                // [Phase 5.5 UX Polish · R4] 세션 상태 노출
                state.getSessionStatus() != null ? state.getSessionStatus() : "ACTIVE",
                state.getSessionStatusChangedAt()
            ));
        }
        return cards;
    }

    /**
     * [Phase 5.5 UX Polish · R4] 활성(ACTIVE) Theater 세션 1개만 조회 — 로비 메인 노출용.
     * 활성극이 없으면 빈 리스트.
     */
    public List<TheaterSessionCard> getActiveTheaterSessions(String username) {
        return getMyTheaterSessions(username).stream()
            .filter(c -> c.sessionStatus() == null || "ACTIVE".equals(c.sessionStatus()))
            .toList();
    }

    /**
     * [Phase 5.5 UX Polish · R4] 아카이브 세션 — ARCHIVED + ENDED.
     * 최근 변경순 (sessionStatusChangedAt 내림차순 → lastActiveAt 내림차순).
     */
    public List<TheaterSessionCard> getArchivedTheaterSessions(String username) {
        return getMyTheaterSessions(username).stream()
            .filter(c -> "ARCHIVED".equals(c.sessionStatus()) || "ENDED".equals(c.sessionStatus()))
            .sorted((a, b) -> {
                java.time.LocalDateTime ta = a.sessionStatusChangedAt() != null
                    ? a.sessionStatusChangedAt() : a.lastActiveAt();
                java.time.LocalDateTime tb = b.sessionStatusChangedAt() != null
                    ? b.sessionStatusChangedAt() : b.lastActiveAt();
                if (ta == null && tb == null) return 0;
                if (ta == null) return 1;
                if (tb == null) return -1;
                return tb.compareTo(ta);
            })
            .toList();
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
                // [Phase 5.5 UX Polish · R4] 같은 세계관 + 같은 lead heroine 이라도
                // 아카이브된 세션이면 resume이 정확한 의미. 활성으로 전환.
                if (existingState.isArchived()) {
                    // 다른 활성 세션이 있다면 그것을 ARCHIVED로 (모델 C-2)
                    archiveCurrentActiveIfAny(user, existingState.getRoom().getId());
                    existingState.resumeFromArchive();
                    theaterStateRepository.save(existingState);
                    log.info("🎭 [THEATER] Resumed archived session | roomId={} | user={}",
                        existing.get().getId(), username);
                }
                // ENDED 세션은 새로 시작해야 하지만, 같은 lead heroine은 충돌
                // → 다른 lead heroine으로 가도록 안내 (단, 이건 매우 드물어 BadRequest)
                else if (existingState.isEnded()) {
                    throw new BadRequestException(
                        "이 히로인의 극은 이미 엔딩에 도달했습니다. 다른 히로인을 선택하거나 아카이브에서 다시 감상하세요.");
                }

                log.info("🎭 [THEATER] Existing session returned | roomId={} | world={} | user={}",
                    existing.get().getId(), worldId, username);
                return buildRoomInfo(existing.get(), existingState);
            }
            // 다른 세계관의 방 — 존재할 수 있음 (모델 C-2: 활성 1개 + 아카이브 N).
            // 정책: 다른 세계관도 별도 활성/아카이브로 가질 수 있음.
            //       (이전 BadRequest는 해제) 단 활성 정책은 유저 단위 전체.
        }

        // [Phase 5.5 UX Polish · R4] 활성극 충돌 처리.
        // 신규 세션을 만들기 전, 다른 활성극이 있다면:
        //  - request.overwriteActive=true 면 archive 후 진행
        //  - 그 외엔 409 Conflict (UI에서 confirm 받고 재호출 유도)
        Optional<TheaterState> currentActive = theaterStateRepository.findActiveByUserId(user.getId());
        if (currentActive.isPresent()) {
            boolean overwrite = Boolean.TRUE.equals(request.overwriteActive());
            if (!overwrite) {
                throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "이미 진행 중인 극이 있습니다. 이 극을 시작하면 기존 극은 아카이브로 보관됩니다."
                );
            }
            currentActive.get().archiveAsInterrupted();
            theaterStateRepository.save(currentActive.get());
            log.info("🎭 [THEATER] Active session archived for new start | archivedRoomId={} | user={}",
                currentActive.get().getRoom().getId(), username);
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
    //  [Phase 5.5 UX Polish · R4] Resume / Archive 정책
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 아카이브된 극을 다시 활성화 (resume).
     *
     * 정책 (모델 C-2):
     *  - 대상 세션이 ARCHIVED여야 함 (ENDED는 resume 불가 — 영구 완결)
     *  - 다른 활성 세션이 있다면 그것을 ARCHIVED로 자동 전환 (활성 1개 정책)
     *  - 본 세션을 ACTIVE로 전환 후 room 정보 반환
     *
     * @return resume된 방의 RoomInfo
     */
    @Transactional
    public TheaterRoomInfo resumeArchivedSession(String username, Long roomId) {
        User user = findUser(username);
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        if (!room.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "접근 권한이 없습니다.");
        }
        TheaterState state = theaterStateRepository.findByRoom_Id(roomId)
            .orElseThrow(() -> new NotFoundException("Theater 상태를 찾을 수 없습니다."));

        if (state.isEnded()) {
            throw new BadRequestException("엔딩에 도달한 극은 다시 시작할 수 없습니다. 아카이브에서 감상만 가능합니다.");
        }
        if (state.isActive()) {
            // 이미 활성 — 그대로 진입
            return buildRoomInfo(room, state);
        }

        // 다른 활성극 있으면 archive
        archiveCurrentActiveIfAny(user, roomId);

        state.resumeFromArchive();
        theaterStateRepository.save(state);

        log.info("🎭 [THEATER] Session resumed | roomId={} | user={}", roomId, username);
        return buildRoomInfo(room, state);
    }

    /**
     * 활성 극을 명시적으로 아카이브 (사용자가 "잠시 멈추기" 또는 임의 중단).
     */
    @Transactional
    public void archiveActiveSession(String username) {
        User user = findUser(username);
        Optional<TheaterState> active = theaterStateRepository.findActiveByUserId(user.getId());
        if (active.isEmpty()) return; // 활성극 없음 → no-op
        active.get().archiveAsInterrupted();
        theaterStateRepository.save(active.get());
        log.info("🎭 [THEATER] Active session archived (manual) | roomId={} | user={}",
            active.get().getRoom().getId(), username);
    }

    /**
     * 활성극이 있고 그것이 excludeRoomId가 아니면 ARCHIVED로 전환.
     * createSession / resume 흐름에서 사용 — 활성 1개 정책 보장.
     */
    private void archiveCurrentActiveIfAny(User user, Long excludeRoomId) {
        Optional<TheaterState> currentActive = theaterStateRepository.findActiveByUserId(user.getId());
        if (currentActive.isEmpty()) return;
        TheaterState s = currentActive.get();
        if (excludeRoomId != null && excludeRoomId.equals(s.getRoom().getId())) return;
        s.archiveAsInterrupted();
        theaterStateRepository.save(s);
        log.info("🎭 [THEATER] Auto-archived previous active | archivedRoomId={} | user={}",
            s.getRoom().getId(), user.getUsername());
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
                // [Polish] LUCID_PASS = Standard tier (20/10)
                case LUCID_PASS -> {
                    maxTotal = STANDARD_TOTAL_POINTS;
                    maxPerStat = STANDARD_PER_STAT_MAX;
                }
                // [Polish] LUCID_MIDNIGHT_PASS = Premium tier (40/20)
                //  ※ 추후 "분배 무제한" 정책으로 전환 예정 — 그때 별도 분기로 처리.
                case LUCID_MIDNIGHT_PASS -> {
                    maxTotal = PREMIUM_TOTAL_POINTS;
                    maxPerStat = PREMIUM_PER_STAT_MAX;
                }
                // [Polish] LUCID_PASS_PREMIUM은 deprecated. 안전을 위해 FREE로 fallback.
                //   (실제 활성화 경로 없음 — DB stale 레코드 보호 차원)
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

        // 히로인 목록 (먼저 조회 — requiresLocationChoice 판단에 필요)
        List<TheaterHeroineAffection> affections = heroineAffectionRepository
            .findByRoomOrderByAffectionDesc(room.getId());

        // [Polish · P1 #7 + LOCATION fix] LOCATION choice 선행 필요 여부.
        //   조건: 멀티 히로인(≥2) + Act ≤ 3 + 새 Chapter 시작 시점(batchId=0 + scenesInCurrentChapter=0)
        //         + 인터미션/난입/엔딩이 아닌 일반 진행 상태
        //         + 이번 Chapter에 LOCATION 분기 선택 기록이 아직 없음
        //   true면 프론트는 batch LLM 호출을 보류하고 LOCATION 모달만 띄운다.
        //
        //   ⚠️ LOCATION 분기 기록 확인이 누락되어 있던 것이 패치 직후 버그의 원인:
        //      유저가 LOCATION을 선택해도 state의 어떤 필드도 변하지 않아 buildRoomInfo가
        //      계속 true를 반환 → autoStart=false 유지 → batch 안 만들어짐 → "반응 없음".
        //      branch_choices 테이블의 기록을 진실의 단일 원천으로 활용.
        boolean locationAlreadyChosenThisChapter =
            branchChoiceRepository.existsByRoom_IdAndActNumberAndChapterNumberAndBranchLevel(
                room.getId(),
                state.getCurrentAct().getNumber(),
                state.getCurrentChapter(),
                BranchLevel.LOCATION
            );

        boolean requiresLocationChoice =
            affections.size() >= 2
                && state.getCurrentAct().getNumber() <= 3
                && state.getCurrentBatchId() == 0
                && state.getScenesInCurrentChapter() == 0
                && !state.isInIntermission()
                && !state.isInterventionActive()
                && !state.isEndingReached()
                && !locationAlreadyChosenThisChapter;

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
            state.getIntermissionStamina(),
            requiresLocationChoice
        );

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

        // [Polish · P0-3] 감독 명령어 등 에너지 게이트 UI를 위해 현재 에너지 노출.
        //   기존엔 누락 — 프론트의 roomInfo.energy가 항상 undefined → 0 → "에너지 부족" 오인.
        User owner = room.getUser();
        int currentEnergy = owner != null ? owner.getEnergy() : 0;
        int currentFreeMax = owner != null ? owner.getFreeEnergyMax() : 30;

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
            state.getEndingTitle(),
            currentEnergy,
            currentFreeMax
        );
    }
}