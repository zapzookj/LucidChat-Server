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

        List<Character> cast = resolveCast(room, user.getId());

        // ── 2. 인플라이트 가드 — 방당 동시 1렌더 (과금 전 차단) ──
        SceneRenderService.SceneView inFlight = renderService.inFlightView(roomId);
        if (inFlight != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 씬 일러를 생성하고 있어요.");
        }

        boolean sfw = !resolveSecretMode(room);
        int cost = props.energyCostOrDefault();
        int turnIndex = (int) chatLogRepository.countByRoomId(roomId);

        // ── 3. 에너지 차감 (짧은 TX) — InsufficientEnergyException은 그대로 전파 ──
        txTemplate.execute(status -> {
            User u = userRepository.findById(user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
            u.consumeEnergy(cost);
            return null;
        });
        cacheService.evictUserProfile(username);

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
                directorService.composeSpec(recentLogs, cast, resolveLocationText(room), sfw);
            return renderService.submitManual(
                roomId, cast, spec, turnIndex, sfw, user.getId(), cost);
        } catch (BusinessException e) {
            refund(user.getId(), cost, username);
            throw e;
        } catch (Exception e) {
            refund(user.getId(), cost, username);
            log.warn("[SCENE-REQUEST] 수동 요청 실패(환불 완료): roomId={} {}", roomId, e.getMessage());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "씬 일러 생성에 실패했어요. 에너지는 환불됐어요.");
        }
    }

    /**
     * 캐스트 해소 — V1: 방 캐릭터 1인(UGC 접근 재검증 — 철회/반려 캐릭터 차단, V1 SSE의
     * blockIfUgcInaccessible과 동일 기준). V2: 유저 현재 위치의 프레즌스 히로인들
     * (빈 리스트 = 배경 전용 씬 허용). UGC 접근 불가 캐릭터는 캐스트에서 제외(에픽 A 선제 방어).
     */
    private List<Character> resolveCast(ChatRoom room, Long userId) {
        // [에픽 A] 공식·UGC 월드 STORY 공용 — 캐스트는 프레즌스 기준이라 월드 종류 무관
        if (room.getChatMode() == ChatMode.STORY) {
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
