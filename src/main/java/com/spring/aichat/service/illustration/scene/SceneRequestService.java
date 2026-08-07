package com.spring.aichat.service.illustration.scene;

import com.spring.aichat.config.SceneIllustrationProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.character.CharacterRepository;
import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatMode;
import com.spring.aichat.domain.heroine.CharacterPresence;
import com.spring.aichat.domain.heroine.CharacterPresenceRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.payment.SecretModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * [2026-07-31 에픽 B] 씬 일러 수동 요청 오케스트레이션 — 유저 트리거 전용(종원 확정).
 *
 * <p>흐름: 검증(소유권·UGC 접근 재검증) → 인플라이트 가드 → 에너지 차감(TX) →
 * 씬 디렉터 스펙 작성(no-TX LLM) → 렌더 제출. LLM/제출 실패는 즉시 환불,
 * 비동기 렌더 실패는 {@link SceneRenderWriteService#failRender}가 행 기록 기반으로 환불.
 *
 * <p>채팅 스트림과 직교 — V1(SANDBOX)·V2(STORY) 공용. 캐스트는 V1=방 캐릭터 1인,
 * V2=유저 현재 위치의 프레즌스(같은 공간 히로인). 디덥 없음: 유저가 명시적으로 과금
 * 요청하므로 같은 씬 재요청도 새 시드 렌더가 맞다(방당 동시 1렌더만 강제).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneRequestService {

    private final SceneRenderService renderService;
    private final SceneDirectorService directorService;
    private final SceneIllustrationProperties props;
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final CharacterRepository characterRepository;
    private final CharacterPresenceRepository presenceRepository;
    private final ChatLogMongoRepository chatLogRepository;
    private final SecretModeService secretModeService;
    private final RedisCacheService cacheService;
    private final TransactionTemplate txTemplate;
    // [에픽 A 리뷰픽스] UGC 월드 방 자격 재검증
    private final com.spring.aichat.domain.ugc.UgcWorldRepository ugcWorldRepository;
    // [2026-08-07 씬당 1회] 같은 턴 수동 재요청 차단 판정
    private final com.spring.aichat.domain.illustration.SceneIllustrationRepository illustrationRepository;

    /**
     * 프론트 기능 노출용 가용성 — FAB 렌더 여부와 표기 비용의 단일 소스(하드코딩 드리프트 방지).
     *
     * @param alreadyDrawn [2026-08-07 씬당 1회] 현재 턴에서 이미 수동 씬을 소비했는가 —
     *                     roomId 미제공 호출(레거시)은 null. 프론트 FAB 비활성 판정 소스.
     */
    public record SceneAvailability(boolean enabled, int energyCost, Boolean alreadyDrawn) {}

    public SceneAvailability availability() {
        return availability(null);
    }

    public SceneAvailability availability(Long roomId) {
        boolean ready = renderService.ready();
        Boolean alreadyDrawn = null;
        if (ready && roomId != null) {
            int turnIndex = (int) chatLogRepository.countByRoomId(roomId);
            alreadyDrawn = manualAlreadyDrawn(roomId, turnIndex);
        }
        return new SceneAvailability(ready, props.energyCostOrDefault(), alreadyDrawn);
    }

    /** [2026-08-07 씬당 1회] 같은 턴의 비-FAILED 수동 렌더 존재 — 서버 권위 판정의 단일 지점. */
    private boolean manualAlreadyDrawn(Long roomId, int turnIndex) {
        return illustrationRepository.existsByChatRoomIdAndTurnIndexAndTriggerSourceAndStatusNot(
            roomId, turnIndex, "MANUAL", "FAILED");
    }

    public SceneRenderService.SceneView requestManual(String username, Long roomId) {
        if (!renderService.ready()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "씬 일러 기능이 비활성 상태입니다.");
        }

        // ── 1. 검증 (소유권 + UGC 접근 재검증) — 에너지 차감 전 ──
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ChatRoom not found"));
        if (!room.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Not your chat room");
        }
        if (room.getChatMode() == ChatMode.THEATER) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "극장 모드는 씬 일러를 지원하지 않습니다.");
        }
        // [에픽 A 리뷰픽스] UGC 월드 방 — 월드 재잠금/철회 재검증 (V2 SSE 가드와 동일 기준)
        assertUgcWorldPlayable(room, user.getId());

        List<Character> cast = resolveCast(room, user.getId());

        // ── 2. 방 단위 락 + 인플라이트 가드 — 방당 동시 1렌더 (과금 전 차단) ──
        // [리뷰픽스 TOCTOU] 행 조회 가드만으로는 '가드 통과 → 디렉터 LLM(수 초) → 행 생성' 창에서
        // 동시 요청이 전부 과금·제출됐다. Redis 락(TTL 90s)으로 창 폐쇄 — Redis 장애 시 개방
        // 실패(fail-open)로 가용성 우선, 행 가드가 2차 방어선.
        if (!cacheService.tryAcquireSceneRequestLock(roomId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 씬 일러를 생성하고 있어요.");
        }
        try {
            SceneRenderService.SceneView inFlight = renderService.inFlightView(roomId);
            if (inFlight != null) {
                throw new BusinessException(ErrorCode.CONFLICT, "이미 씬 일러를 생성하고 있어요.");
            }

            boolean sfw = !resolveSecretMode(room);
            int cost = props.energyCostOrDefault();
            int turnIndex = (int) chatLogRepository.countByRoomId(roomId);

            // ── [2026-08-07 씬당 1회] 같은 턴(새 로그 없음)의 수동 재요청 차단 — 차감 전 ──
            // FAILED(자동 환불 완료)는 제외 — 실패 재시도 허용. 대화가 진행돼 로그가 쌓이면
            // turnIndex가 달라져 자연 해제. 종원 확정: 한 장면당 1회 생성.
            if (manualAlreadyDrawn(roomId, turnIndex)) {
                throw new BusinessException(ErrorCode.CONFLICT,
                    "이 장면은 이미 그렸어요. 대화를 이어간 뒤 다시 요청해 주세요.");
            }

            // ── 3. 에너지 차감 (짧은 TX) — InsufficientEnergyException은 그대로 전파 ──
            txTemplate.execute(status -> {
                User u = userRepository.findById(user.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
                u.consumeEnergy(cost);
                return null;
            });
            // [리뷰픽스] 캐시 무효화 실패는 표시 문제일 뿐 — 차감 커밋 후 예외로 렌더·환불이
            // 모두 증발하지 않도록 스왈로우
            try {
                cacheService.evictUserProfile(username);
            } catch (Exception e) {
                log.warn("[SCENE-REQUEST] 프로필 캐시 무효화 실패 (무해): {}", e.getMessage());
            }

            // ── 4. 씬 디렉터 (no-TX LLM) + 렌더 제출 — 동기 실패는 즉시 환불 ──
            try {
                List<ChatLogDocument> recentLogs = chatLogRepository
                    .findTop20ByRoomIdOrderByCreatedAtDesc(roomId).stream()
                    .limit(props.director().contextTurnsOrDefault())
                    .toList();
                if (recentLogs.isEmpty()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "아직 그릴 장면이 없어요. 대화를 먼저 시작해 주세요.");
                }
                AiJsonOutput.SceneIllustrationSpec spec =
                    directorService.composeSpec(recentLogs, cast, resolveLocationText(room), sfw,
                        room.isPersonaUserMale());   // [페르소나] 유저 성별 스냅샷 반영
                return renderService.submitManual(
                    roomId, cast, spec, turnIndex, sfw, user.getId(), cost);
            } catch (SceneRenderService.RenderPoolSaturatedException e) {
                // [리뷰픽스 이중 환불] failRender가 행 기반 멱등 환불을 이미 완료 — 여기서 재환불 금지
                log.warn("[SCENE-REQUEST] 렌더 풀 포화(환불은 failRender가 완료): roomId={}", roomId);
                throw new BusinessException(ErrorCode.CONFLICT,
                    "지금은 씬 일러 요청이 몰려 있어요. 잠시 후 다시 시도해 주세요. 에너지는 환불됐어요.");
            } catch (BusinessException e) {
                refund(user.getId(), cost, username);
                throw e;
            } catch (Exception e) {
                refund(user.getId(), cost, username);
                log.warn("[SCENE-REQUEST] 수동 요청 실패(환불 완료): roomId={} {}", roomId, e.getMessage());
                throw new BusinessException(ErrorCode.BAD_REQUEST, "씬 일러 생성에 실패했어요. 에너지는 환불됐어요.");
            }
        } finally {
            cacheService.releaseSceneRequestLock(roomId);
        }
    }

    /**
     * [에픽 A 리뷰픽스] UGC 월드 STORY 방의 플레이 자격 재검증 — 소유자 무조건, 타인은
     * 검수 APPROVED만(월드 수정 시 NONE 리셋 재잠금과 맞물림). ChatStreamServiceV2의
     * blockIfUgcStoryInaccessible과 동일 기준 — 씬 요청이 그 가드의 우회로가 되지 않게 한다.
     */
    private void assertUgcWorldPlayable(ChatRoom room, Long userId) {
        if (room.getUgcWorldId() == null) return;
        com.spring.aichat.domain.ugc.UgcWorld world =
            ugcWorldRepository.findById(room.getUgcWorldId()).orElse(null);
        if (world == null || (!world.isOwnedBy(userId)
            && world.getReviewStatus() != com.spring.aichat.domain.ugc.WorldReviewStatus.APPROVED)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 세계관은 더 이상 이용할 수 없어요.");
        }
    }

    /**
     * 캐스트 해소 — V1: 방 캐릭터 1인(UGC 접근 재검증 — 철회/반려 캐릭터 차단, V1 SSE의
     * blockIfUgcInaccessible과 동일 기준). V2: 유저 현재 위치의 프레즌스 히로인들
     * (빈 리스트 = 배경 전용 씬 허용). UGC 접근 불가 캐릭터는 캐스트에서 제외(에픽 A 선제 방어).
     */
    private List<Character> resolveCast(ChatRoom room, Long userId) {
        // [에픽 A] 공식·UGC 월드 STORY 공용 — 캐스트는 프레즌스 기준이라 월드 종류 무관.
        // [리뷰픽스] 레거시 V1 STORY(캐릭터형 — world·ugcWorld 모두 없음)는 프레즌스가 없어
        // 빈 캐스트 과금 렌더가 되므로 V1 캐릭터 분기로 폴스루.
        if (room.getChatMode() == ChatMode.STORY
            && (room.getWorld() != null || room.getUgcWorldId() != null)) {
            List<Long> presentIds = presenceRepository
                .findByChatRoom_IdAndCurrentLocationKey(room.getId(), room.getCurrentUserLocationKey())
                .stream().map(CharacterPresence::getCharacterId).toList();
            return characterRepository.findAllById(presentIds).stream()
                .filter(c -> !c.isUgc() || c.isAccessibleBy(userId))
                .toList();
        }
        Character character = room.getCharacter();
        if (character == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "씬 일러를 그릴 캐릭터가 없습니다.");
        }
        if (character.isUgc() && !character.isAccessibleBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 캐릭터는 더 이상 대화할 수 없어요.");
        }
        return List.of(character);
    }

    /** 수위 판정 — V1: 캐릭터 secretEligible 게이트(2-arg), V2: world.isSecretAllowed + 1-arg. */
    private boolean resolveSecretMode(ChatRoom room) {
        if (!room.isSecretModeActive()) return false;
        if (room.getChatMode() == ChatMode.STORY) {
            // [에픽 A] UGC 월드 방(world==null)은 시크릿 불허 확정 — sfw 강제 유지
            return room.getWorld() != null
                && room.getWorld().isSecretAllowed()
                && secretModeService.canAccessSecretMode(room.getUser());
        }
        return room.getCharacter() != null
            && secretModeService.canAccessSecretMode(room.getUser(), room.getCharacter().getId());
    }

    /** 씬 디렉터에 주는 현재 장소 텍스트 — 동적 장소명 우선, V2는 위치 키 폴백. */
    private String resolveLocationText(ChatRoom room) {
        if (room.getCurrentDynamicLocationName() != null
            && !room.getCurrentDynamicLocationName().isBlank()) {
            return room.getCurrentDynamicLocationName();
        }
        return room.getCurrentUserLocationKey();
    }

    /** 동기 실패 환불 — 유저 id 직접 조회(V2 보상 경로의 room 경유 조회 버그 재발 방지). */
    private void refund(Long userId, int amount, String username) {
        try {
            txTemplate.execute(status -> {
                userRepository.findById(userId).ifPresent(u -> u.refundEnergy(amount));
                return null;
            });
            cacheService.evictUserProfile(username);
        } catch (Exception e) {
            log.error("[SCENE-REQUEST] 환불 실패 userId={} amount={}: {}", userId, amount, e.getMessage());
        }
    }
}
