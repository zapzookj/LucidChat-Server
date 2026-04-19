package com.spring.aichat.service.theater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatModePolicy;
import com.spring.aichat.domain.theater.TheaterDirectorNote;
import com.spring.aichat.domain.theater.TheaterDirectorNoteRepository;
import com.spring.aichat.domain.theater.TheaterState;
import com.spring.aichat.domain.theater.TheaterStateRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.theater.TheaterResponses.InterventionResume;
import com.spring.aichat.dto.theater.TheaterResponses.InterventionStart;
import com.spring.aichat.exception.BadRequestException;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * [Phase 5.5-Theater] 난입(Intervention) 서비스
 *
 * Theater 감상 흐름을 일시 중단하고 유저가 직접 아바타로 대화한 뒤 복귀.
 *
 * [토큰 관리]
 * 난입 토큰은 Theater 모듈 내부에서 Redis로 직접 관리 (외부 RedisCacheService 의존 없음).
 *
 * [에너지]
 * User 엔티티의 consumeEnergy() 직접 호출.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterInterventionService {

    private final ChatRoomRepository chatRoomRepository;
    private final TheaterStateRepository theaterStateRepository;
    private final TheaterDirectorNoteRepository directorNoteRepository;
    private final TheaterBatchCacheService batchCache;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String INT_TOKEN_KEY = "theater:intervention:token:";
    private static final Duration TOKEN_TTL = Duration.ofHours(1);

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 난입 시작
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public InterventionStart startIntervention(Long roomId, String username, String trigger) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        if (state.isInterventionActive()) throw new BadRequestException("이미 난입 세션이 활성 상태입니다.");
        if (state.isInIntermission()) throw new BadRequestException("인터미션 중에는 난입할 수 없습니다.");
        if (state.isEndingReached()) throw new BadRequestException("엔딩에 도달한 세션에는 난입할 수 없습니다.");

        // 에너지 차감
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("유저를 찾을 수 없습니다."));
        user.consumeEnergy(ChatModePolicy.INTERVENTION_ENERGY_COST);

        // 체크포인트
        Map<String, Object> checkpoint = new HashMap<>();
        checkpoint.put("act", state.getCurrentAct().name());
        checkpoint.put("chapter", state.getCurrentChapter());
        checkpoint.put("scenesInChapter", state.getScenesInCurrentChapter());
        checkpoint.put("batchId", state.getCurrentBatchId());
        checkpoint.put("currentHeroineId", state.getCurrentHeroineId());
        checkpoint.put("trigger", trigger);
        checkpoint.put("startedAt", System.currentTimeMillis());

        String checkpointJson;
        try {
            checkpointJson = objectMapper.writeValueAsString(checkpoint);
        } catch (JsonProcessingException e) {
            checkpointJson = "{}";
        }

        // 토큰 발급 + Redis 저장
        String token = "INT-" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.opsForValue().set(INT_TOKEN_KEY + roomId, token, TOKEN_TTL);

        state.enterIntervention(checkpointJson);

        directorNoteRepository.save(TheaterDirectorNote.intervention(
            room,
            String.format("난입 시작 (%s). Act %d Chapter %d에서 잠시 이야기를 멈추고 직접 개입하다.",
                trigger, state.getCurrentAct().getNumber(), state.getCurrentChapter()),
            state.getCurrentAct().getNumber(), state.getCurrentChapter()
        ));

        batchCache.invalidateBatchesFrom(roomId, state.getCurrentBatchId());

        log.info("🎭 [INTERVENTION] Started | roomId={} | trigger={}", roomId, trigger);

        String transitionNarration = "장면이 잠시 멈춘다. 지금, 당신 차례다.";
        Long currentHeroineId = state.getCurrentHeroineId();
        String currentHeroineName = room.getCharacter() != null ? room.getCharacter().getName() : null;

        return new InterventionStart(
            roomId, token, transitionNarration,
            currentHeroineId, currentHeroineName
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. 난입 로그 기록 (유저 대화 중)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public void recordInterventionLog(Long roomId, String logId) {
        TheaterState state = getState(roomId);
        if (!state.isInterventionActive()) return;
        state.recordInterventionLog(logId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. 난입 복귀
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public InterventionResume resumeFromIntervention(Long roomId, String username, String token) {
        ChatRoom room = getOwnedRoom(roomId, username);
        TheaterState state = getState(roomId);

        if (!state.isInterventionActive()) {
            throw new BadRequestException("활성화된 난입 세션이 없습니다.");
        }

        // 토큰 검증 + 소비
        String key = INT_TOKEN_KEY + roomId;
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null || !stored.equals(token)) {
            log.warn("🎭 [INTERVENTION] Invalid token | roomId={} | token={}", roomId, token);
            throw new BadRequestException("유효하지 않은 난입 토큰입니다.");
        }
        redisTemplate.delete(key);

        String redirectHint = String.format(
            "INTERVENTION_SUMMARY: 유저가 Act %d Chapter %d에서 직접 개입하여 대화했다. " +
                "이 개입은 이후 씬의 분위기와 캐릭터 반응에 자연스럽게 반영되어야 한다. " +
                "마지막 로그 ID: %s",
            state.getCurrentAct().getNumber(),
            state.getCurrentChapter(),
            state.getInterventionLastLogId()
        );

        batchCache.putBranchContext(roomId, "active", redirectHint);
        state.exitIntervention();

        directorNoteRepository.save(TheaterDirectorNote.intervention(
            room,
            "난입 종료. Theater 흐름으로 복귀. 유저의 개입이 다음 씬에 반영된다.",
            state.getCurrentAct().getNumber(), state.getCurrentChapter()
        ));

        log.info("🎭 [INTERVENTION] Resumed | roomId={}", roomId);
        return new InterventionResume(roomId, true, redirectHint);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ChatRoom getOwnedRoom(Long roomId, String username) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        if (!room.getUser().getUsername().equals(username)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "접근 권한이 없습니다.");
        }
        return room;
    }

    private TheaterState getState(Long roomId) {
        return theaterStateRepository.findByRoom_Id(roomId)
            .orElseThrow(() -> new NotFoundException("Theater 세션이 없습니다."));
    }
}