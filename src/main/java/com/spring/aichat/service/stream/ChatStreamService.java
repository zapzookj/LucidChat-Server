package com.spring.aichat.service.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.*;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.dto.chat.SendChatResponse.*;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ContentModerationException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.external.OpenRouterStreamClient;
import com.spring.aichat.external.OpenRouterStreamClient.StreamResult;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.AchievementService;
import com.spring.aichat.service.ChatService;
import com.spring.aichat.service.ContentModerationService;
import com.spring.aichat.service.MemoryService;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.payment.BoostModeResolver;
import com.spring.aichat.service.payment.SecretModeService;
import com.spring.aichat.service.prompt.CharacterPromptAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * [Phase 5.5-Perf] SSE Dual-Streaming 채팅 서비스
 *
 * [Phase 5.5-EV] 이벤트 시스템 강화:
 *   - topic_concluded: LLM이 판단한 주제 종료 플래그
 *   - Director Mode: 이벤트를 "스노우볼" 형태로 진행
 *   - sendEventSelectStream(): 이벤트 선택 → 디렉터 모드 시작
 *   - sendDirectorWatchStream(): [👀 계속 지켜보기] → SYSTEM_DIRECTOR 주입
 *   - sendTimeSkipStream(): [시간 넘기기] → 시간/장소 전환 나레이션
 *   - 승급 판정: 5종 스탯 변화량 합산 기반
 *   - 승급 게이팅: topic_concluded=true 시에만 발동
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatStreamService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogMongoRepository chatLogRepository;
    private final CharacterPromptAssembler promptAssembler;
    private final OpenRouterStreamClient streamClient;
    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;
    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;
    private final TransactionTemplate txTemplate;
    private final RedisCacheService cacheService;
    private final AchievementService achievementService;
    private final BoostModeResolver boostModeResolver;
    private final PromptInjectionGuard injectionGuard;
    private final ContentModerationService contentModerationService;
    private final UserRepository userRepository;
    private final SecretModeService secretModeService;
    private final ChatService chatService;

    private static final long USER_TURN_MEMORY_CYCLE = 10;
    private static final long RAG_SKIP_LOG_THRESHOLD = USER_TURN_MEMORY_CYCLE * 2;

    /** [Phase 5.5-EV] 시간 넘기기 에너지 비용 */
    private static final int TIME_SKIP_ENERGY_COST = 1;

    /** [Phase 5.5-EV] SYSTEM_DIRECTOR 프롬프트 (지켜보기 시 LLM에 주입) */
    private static final String SYSTEM_DIRECTOR_PROMPT = """
        [SYSTEM_DIRECTOR] 유저는 아직 개입하지 않고 상황을 숨죽여 지켜보고 있습니다.
        현재의 갈등이나 상황을 한 단계 더 심화시키고 긴장감을 높이는 방향으로 2~3개의 씬을 추가 연출하세요.
        상황을 스스로 종료시키지 마세요. 캐릭터를 더 곤경에 빠뜨리거나, 묘한 분위기를 고조시키세요.
        반드시 "event_status": "ONGOING" 을 출력하세요.
        """;

    /** [Phase 5.5-EV] 시간 넘기기 시스템 나레이션 프롬프트 */
    private static final String TIME_SKIP_PROMPT = """
        [TIME_SKIP] 시간이 흘렀습니다. 자연스러운 시간 경과를 나레이션으로 표현하고,
        새로운 시간대/장소에서 캐릭터가 유저에게 먼저 말을 거는 씬을 1~2개 만들어주세요.
        반드시 location, time 필드를 적절히 변경하세요.
        """;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  TX 간 데이터 전달 DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record JpaPreResult(
        ChatRoom room, Long userId, long logCount,
        boolean wasPromotionPending, String username, int energyCost
    ) {}

    private record RollbackContext(
        Long userId, String username, int energyCost, String savedUserLogId
    ) {}

    /** LLM 결과 파싱 후 중간 데이터 */
    private record ParsedLlmResult(
        AiJsonOutput aiOutput, String cleanJson, String combinedDialogue,
        EmotionTag mainEmotion, List<SceneResponse> sceneResponses,
        String lastBgm, String lastLoc, String lastOutfit, String lastTime,
        AiJsonOutput.StatChanges statChanges, Integer bpm,
        String innerThought, boolean topicConcluded, String eventStatus,
        String scenesJson      // [Phase 5.5-Fix] 구조화된 씬 JSON
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  1. 메인 스트리밍 (유저 채팅) — 기존 + topic_concluded/promotion gating
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void sendMessageStream(Long roomId, String userMessage, SseEmitter emitter) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱ [STREAM-PERF] ====== sendMessageStream START ====== roomId={}", roomId);

        try {
            // ── Content Moderation ──
            ChatRoom roomForCheck = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
            boolean isSecretCheck = roomForCheck.getUser().getIsSecretMode()
                && secretModeService.canAccessSecretMode(
                roomForCheck.getUser(), roomForCheck.getCharacter().getId());

            ContentModerationService.ModerationVerdict verdict =
                contentModerationService.moderate(userMessage, isSecretCheck);
            if (!verdict.passed()) {
                sendSseError(emitter, "CONTENT_BLOCKED", verdict.userMessage());
                return;
            }

            // ── TX-1 ──
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
                int cost = boostModeResolver.resolveEnergyCost(room.getChatMode(), room.getUser());
                room.getUser().consumeEnergy(cost);
                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.isPromotionPending(), room.getUser().getUsername(), cost);
            });
            cacheService.evictUserProfile(jpa.username());

            // ── Prompt Injection Check ──
            PromptInjectionGuard.InjectionCheckResult injCheck =
                injectionGuard.checkChatMessage(userMessage, jpa.username());
            if (injCheck.detected()) {
                log.warn("⚠️ [INJECTION] Detected: user={}", jpa.username());
            }

            // ── MongoDB: USER 메시지 저장 ──
            String savedUserLogId;
            try {
                ChatLogDocument savedLog = chatLogRepository.save(
                    ChatLogDocument.user(roomId, userMessage));
                savedUserLogId = savedLog.getId();
            } catch (Exception e) {
                compensateEnergy(jpa.userId(), jpa.energyCost(), jpa.username());
                sendSseError(emitter, "INTERNAL_ERROR", "메시지 저장에 실패했습니다.");
                return;
            }

            RollbackContext rollbackCtx = new RollbackContext(
                jpa.userId(), jpa.username(), jpa.energyCost(), savedUserLogId);

            // [Phase 5.5-EV] 유저 개입인지 판단 (디렉터 모드 중 유저가 직접 채팅)
            boolean isUserIntervention = jpa.room().isEventActive();

            // ── LLM 호출 + 파싱 ──
            boolean effectiveSecretMode = resolveSecretMode(jpa.room());
            ParsedLlmResult parsed = streamLlmAndParse(jpa.room(), jpa.logCount() + 1,
                effectiveSecretMode, emitter, rollbackCtx);
            if (parsed == null) return; // 에러 시 이미 emitter 처리됨

            boolean isStory = jpa.room().isStoryMode();

            // [Phase 5.5-EV] 디렉터 모드 중 유저 개입 → RESOLVED 판정
            boolean wasEventActive = jpa.room().isEventActive();

            // ── TX-2 ──
            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> {
                    ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                    // [Phase 5.5-EV] topic_concluded 상태 업데이트
                    freshRoom.updateTopicConcluded(parsed.topicConcluded());

                    // [Phase 5.5-EV] 이벤트 상태 업데이트 (유저 개입 시)
                    if (wasEventActive) {
                        // [Fix 1] 유저가 실제 채팅으로 개입한 경우 → 무조건 RESOLVED
                        // LLM이 ONGOING을 뱉어도 백엔드에서 강제 오버라이드
                        freshRoom.updateEventStatus("RESOLVED");
                        log.info("🎬 [DIRECTOR] User intervention → forced RESOLVED | roomId={} | llmSaid={}", roomId, parsed.eventStatus());
                    }

                    // 스탯 적용 (이벤트 ONGOING 중에는 BPM만 적용, 스탯 동결)
                    boolean suppressStats = freshRoom.isEventActive() && !isUserIntervention;
                    if (!suppressStats) {
                        applyStatChanges(freshRoom, parsed.statChanges(), effectiveSecretMode);
                    }
                    if (parsed.bpm() != null) freshRoom.updateBpm(parsed.bpm());

                    // 승급 이벤트 처리
                    PromotionEvent promoEvent = null;
                    if (isStory) {
                        promoEvent = resolvePromotionLogic(freshRoom, parsed, jpa.wasPromotionPending(),
                            isUserIntervention);
                    }

                    freshRoom.updateLastActive(parsed.mainEmotion());
                    if (isStory) {
                        freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
                            parsed.lastOutfit(), parsed.lastTime());
                    }

                    if (!freshRoom.isPromotionPending()) {
                        freshRoom.refreshRelationFromStats();
                    }

                    // [Phase 5.5-EV] 승급 대기 처리 (topic_concluded 게이팅)
                    PromotionEvent deferredPromo = checkAndStartDeferredPromotion(freshRoom, parsed.topicConcluded());
                    if (deferredPromo != null) promoEvent = deferredPromo;

                    // 엔딩 트리거
                    EndingTrigger endingTrigger = null;
                    if (isStory) {
                        String endingCheck = freshRoom.checkEndingTrigger();
                        if (endingCheck != null) endingTrigger = new EndingTrigger(endingCheck);
                    }

                    // 이스터에그
                    EasterEggEvent easterEgg = processEasterEgg(parsed.aiOutput(), jpa.userId());

                    StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                    return new SendChatResponse(roomId, parsed.sceneResponses(),
                        freshRoom.getAffectionScore(), freshRoom.getStatusLevel().name(),
                        promoEvent, endingTrigger, easterEgg,
                        statsSnapshot, freshRoom.getCurrentBpm(),
                        freshRoom.getDynamicRelationTag(), null,
                        false, null,
                        freshRoom.isTopicConcluded(),
                        wasEventActive ? "RESOLVED" : (freshRoom.isEventActive() ? freshRoom.getEventStatus() : null));
                });
            } catch (Exception e) {
                log.error("❌ TX-2 failed | roomId={}", roomId, e);
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "응답 처리 중 오류가 발생했습니다.");
                return;
            }

            // ── MongoDB: ASSISTANT 저장 ──
            String assistantLogId = null;
            boolean hasInnerThought = false;
            try {
                ChatLogDocument assistantLog = ChatLogDocument.assistantWithThought(
                    roomId, parsed.cleanJson(), parsed.combinedDialogue(),
                    parsed.mainEmotion(), null, parsed.innerThought(), parsed.scenesJson());
                ChatLogDocument saved = chatLogRepository.save(assistantLog);
                assistantLogId = saved.getId();
                hasInnerThought = saved.hasInnerThought();
            } catch (Exception e) {
                log.error("⚠️ ASSISTANT log save failed | roomId={}", roomId, e);
            }
            cacheService.evictRoomInfo(roomId);

            // ── SSE: final_result ──
            sendFinalResult(emitter, response, hasInnerThought, assistantLogId);
            emitter.complete();

            log.info("⏱ [STREAM-PERF] sendMessageStream DONE: {}ms", System.currentTimeMillis() - totalStart);

            triggerPostProcessing(roomId, jpa.userId(), jpa.logCount() + 1, effectiveSecretMode);

        } catch (Exception e) {
            log.error("❌ Unexpected error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "예기치 않은 오류가 발생했습니다.");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  2. [Phase 5.5-EV] 이벤트 선택 → 디렉터 모드 시작 (SSE)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void sendEventSelectStream(Long roomId, String eventDetail, int energyCost, SseEmitter emitter) {
        log.info("🎬 [DIRECTOR] Event select START | roomId={}", roomId);

        try {
            // ── TX-1: 에너지 차감 + 디렉터 모드 시작 ──
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
                room.getUser().consumeEnergy(energyCost);
                room.startDirectorEvent(); // 디렉터 모드 시작
                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.isPromotionPending(), room.getUser().getUsername(), energyCost);
            });
            cacheService.evictUserProfile(jpa.username());

            // ── MongoDB: 이벤트 시작 시스템 메시지 저장 (프론트 미노출) ──
            String savedLogId;
            try {
                ChatLogDocument savedLog = chatLogRepository.save(
                    ChatLogDocument.hiddenUser(roomId, "[EVENT_START]\n" + eventDetail, eventDetail));
                savedLogId = savedLog.getId();
            } catch (Exception e) {
                compensateEnergy(jpa.userId(), jpa.energyCost(), jpa.username());
                sendSseError(emitter, "INTERNAL_ERROR", "이벤트 저장 실패");
                return;
            }

            RollbackContext rollbackCtx = new RollbackContext(
                jpa.userId(), jpa.username(), jpa.energyCost(), savedLogId);

            boolean effectiveSecretMode = resolveSecretMode(jpa.room());

            // ── LLM 스트림 ──
            ParsedLlmResult parsed = streamLlmAndParse(jpa.room(), jpa.logCount() + 1,
                effectiveSecretMode, emitter, rollbackCtx);
            if (parsed == null) return;

            // ── TX-2: 이벤트 상태 업데이트 (스탯 동결, BPM만 업데이트) ──
            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> {
                    ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                    freshRoom.updateEventStatus(
                        parsed.eventStatus() != null ? parsed.eventStatus() : "ONGOING");
                    freshRoom.updateTopicConcluded(false); // 이벤트 중 topic은 미종료

                    // 스탯 동결, BPM만 업데이트
                    if (parsed.bpm() != null) freshRoom.updateBpm(parsed.bpm());
                    freshRoom.updateLastActive(parsed.mainEmotion());
                    if (jpa.room().isStoryMode()) {
                        freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
                            parsed.lastOutfit(), parsed.lastTime());
                    }

                    StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                    return new SendChatResponse(roomId, parsed.sceneResponses(),
                        freshRoom.getAffectionScore(), freshRoom.getStatusLevel().name(),
                        null, null, null,
                        statsSnapshot, freshRoom.getCurrentBpm(),
                        freshRoom.getDynamicRelationTag(), null,
                        false, null,
                        false, // topicConcluded
                        freshRoom.getEventStatus()); // eventStatus
                });
            } catch (Exception e) {
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "이벤트 처리 실패");
                return;
            }

            // ── MongoDB: ASSISTANT 저장 ──
            String assistantLogId = saveAssistantLog(roomId, parsed);
            cacheService.evictRoomInfo(roomId);

            sendFinalResult(emitter, response, false, assistantLogId);
            emitter.complete();

            log.info("🎬 [DIRECTOR] Event select DONE | roomId={} | eventStatus={}",
                roomId, parsed.eventStatus());

        } catch (Exception e) {
            log.error("❌ Event select error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "이벤트 처리 중 오류 발생");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  3. [Phase 5.5-EV] 👀 계속 지켜보기 (SSE)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void sendDirectorWatchStream(Long roomId, SseEmitter emitter) {
        log.info("👀 [DIRECTOR] Watch START | roomId={}", roomId);

        try {
            // ── TX-1: 에너지 차감 (일반 채팅과 동일) ──
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                if (!room.isEventActive()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "진행 중인 이벤트가 없습니다.");
                }

                int cost = boostModeResolver.resolveEnergyCost(room.getChatMode(), room.getUser());
                room.getUser().consumeEnergy(cost);
                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.isPromotionPending(), room.getUser().getUsername(), cost);
            });
            cacheService.evictUserProfile(jpa.username());

            // ── MongoDB: SYSTEM_DIRECTOR 메시지 저장 (프론트 미노출) ──
            String savedLogId;
            try {
                ChatLogDocument savedLog = chatLogRepository.save(
                    ChatLogDocument.hiddenSystem(roomId, SYSTEM_DIRECTOR_PROMPT));
                savedLogId = savedLog.getId();
            } catch (Exception e) {
                compensateEnergy(jpa.userId(), jpa.energyCost(), jpa.username());
                sendSseError(emitter, "INTERNAL_ERROR", "메시지 저장 실패");
                return;
            }

            RollbackContext rollbackCtx = new RollbackContext(
                jpa.userId(), jpa.username(), jpa.energyCost(), savedLogId);

            boolean effectiveSecretMode = resolveSecretMode(jpa.room());

            // ── LLM 스트림 (SYSTEM_DIRECTOR가 포함된 히스토리) ──
            ParsedLlmResult parsed = streamLlmAndParse(jpa.room(), jpa.logCount() + 1,
                effectiveSecretMode, emitter, rollbackCtx);
            if (parsed == null) return;

            // ── TX-2: 스탯 동결, BPM만 업데이트 ──
            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> {
                    ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                    freshRoom.updateEventStatus(
                        parsed.eventStatus() != null ? parsed.eventStatus() : "ONGOING");

                    if (parsed.bpm() != null) freshRoom.updateBpm(parsed.bpm());
                    freshRoom.updateLastActive(parsed.mainEmotion());
                    if (jpa.room().isStoryMode()) {
                        freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
                            parsed.lastOutfit(), parsed.lastTime());
                    }

                    StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                    return new SendChatResponse(roomId, parsed.sceneResponses(),
                        freshRoom.getAffectionScore(), freshRoom.getStatusLevel().name(),
                        null, null, null,
                        statsSnapshot, freshRoom.getCurrentBpm(),
                        freshRoom.getDynamicRelationTag(), null,
                        false, null,
                        false,
                        freshRoom.getEventStatus());
                });
            } catch (Exception e) {
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "지켜보기 처리 실패");
                return;
            }

            String assistantLogId = saveAssistantLog(roomId, parsed);
            cacheService.evictRoomInfo(roomId);

            sendFinalResult(emitter, response, false, assistantLogId);
            emitter.complete();

            log.info("👀 [DIRECTOR] Watch DONE | roomId={} | eventStatus={}",
                roomId, parsed.eventStatus());

        } catch (Exception e) {
            log.error("❌ Watch error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "지켜보기 처리 중 오류 발생");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  4. [Phase 5.5-EV] 시간 넘기기 (SSE)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void sendTimeSkipStream(Long roomId, SseEmitter emitter) {
        log.info("⏭ [TIME_SKIP] START | roomId={}", roomId);

        try {
            // ── TX-1: 에너지 1 차감 ──
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
                room.getUser().consumeEnergy(TIME_SKIP_ENERGY_COST);
                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.isPromotionPending(), room.getUser().getUsername(), TIME_SKIP_ENERGY_COST);
            });
            cacheService.evictUserProfile(jpa.username());

            // ── MongoDB: 시간 넘기기 시스템 메시지 저장 (프론트 미노출) ──
            String savedLogId;
            try {
                ChatLogDocument savedLog = chatLogRepository.save(
                    ChatLogDocument.hiddenSystem(roomId, TIME_SKIP_PROMPT));
                savedLogId = savedLog.getId();
            } catch (Exception e) {
                compensateEnergy(jpa.userId(), jpa.energyCost(), jpa.username());
                sendSseError(emitter, "INTERNAL_ERROR", "메시지 저장 실패");
                return;
            }

            RollbackContext rollbackCtx = new RollbackContext(
                jpa.userId(), jpa.username(), jpa.energyCost(), savedLogId);

            boolean effectiveSecretMode = resolveSecretMode(jpa.room());

            // ── LLM 스트림 ──
            ParsedLlmResult parsed = streamLlmAndParse(jpa.room(), jpa.logCount() + 1,
                effectiveSecretMode, emitter, rollbackCtx);
            if (parsed == null) return;

            // ── TX-2: 일반적 스탯 적용 + topic 리셋 ──
            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> {
                    ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                    applyStatChanges(freshRoom, parsed.statChanges(), effectiveSecretMode);
                    if (parsed.bpm() != null) freshRoom.updateBpm(parsed.bpm());
                    freshRoom.updateLastActive(parsed.mainEmotion());
                    freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
                        parsed.lastOutfit(), parsed.lastTime());
                    freshRoom.updateTopicConcluded(false); // 시간 넘기기 후 → 새 주제 시작

                    if (!freshRoom.isPromotionPending()) {
                        freshRoom.refreshRelationFromStats();
                    }

                    StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                    return new SendChatResponse(roomId, parsed.sceneResponses(),
                        freshRoom.getAffectionScore(), freshRoom.getStatusLevel().name(),
                        null, null, null,
                        statsSnapshot, freshRoom.getCurrentBpm(),
                        freshRoom.getDynamicRelationTag(), null,
                        false, null,
                        false, null); // topic/event 리셋
                });
            } catch (Exception e) {
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "시간 넘기기 처리 실패");
                return;
            }

            String assistantLogId = saveAssistantLog(roomId, parsed);
            cacheService.evictRoomInfo(roomId);

            sendFinalResult(emitter, response, false, assistantLogId);
            emitter.complete();

            log.info("⏭ [TIME_SKIP] DONE | roomId={}", roomId);

        } catch (Exception e) {
            log.error("❌ Time skip error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "시간 넘기기 처리 중 오류 발생");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-EV] 승급 로직 (스탯 변화량 기반)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 승급 로직 — mood_score를 스탯 변화량 합산으로 대체
     *
     * @param isUserIntervention 디렉터 모드 중 유저 개입 턴인지 (이때만 턴 카운트)
     */
    private PromotionEvent resolvePromotionLogic(ChatRoom room, ParsedLlmResult parsed,
                                                 boolean wasPending, boolean isUserIntervention) {
        if (wasPending) {
            // 디렉터 모드 중 지켜보기 턴은 카운트하지 않음
            if (!isUserIntervention && room.isEventActive()) {
                return new PromotionEvent("IN_PROGRESS",
                    room.getPendingTargetStatus().name(),
                    RelationStatusPolicy.getDisplayName(room.getPendingTargetStatus()),
                    RelationStatusPolicy.PROMOTION_MAX_TURNS - room.getPromotionTurnCount(),
                    room.getPromotionMoodScore(), null);
            }

            // [Phase 5.5-EV] 5종 스탯 변화량 합산으로 mood_score 대체
            int statDelta = parsed.statChanges() != null
                ? parsed.statChanges().totalNormalStatDelta() : 0;
            room.advancePromotionTurn(statDelta);

            log.info("🎯 [PROMOTION] Turn {}/{} | statDelta={} (total: {}) | roomId={}",
                room.getPromotionTurnCount(), RelationStatusPolicy.PROMOTION_MAX_TURNS,
                statDelta, room.getPromotionMoodScore(), room.getId());

            if (room.getPromotionTurnCount() >= RelationStatusPolicy.PROMOTION_MAX_TURNS) {
                return resolvePromotionResult(room);
            }

            RelationStatus target = room.getPendingTargetStatus();
            return new PromotionEvent("IN_PROGRESS", target.name(),
                RelationStatusPolicy.getDisplayName(target),
                RelationStatusPolicy.PROMOTION_MAX_TURNS - room.getPromotionTurnCount(),
                room.getPromotionMoodScore(), null);

        } else {
            // [Phase 5.5-EV] 승급 감지 → 즉시 시작하지 않고, 대기(waiting) 상태로 전환
            RelationStatus oldStatus = room.getStatusLevel();
            room.applyLegacyAffectionChange(parsed.aiOutput().affectionChange());
            RelationStatus newStatus = RelationStatusPolicy.fromScore(room.getAffectionScore());

            if (RelationStatusPolicy.isUpgrade(oldStatus, newStatus)) {
                log.info("🎯 [PROMOTION] Upgrade detected → WAITING for topic_concluded | {} → {} | roomId={}",
                    oldStatus, newStatus, room.getId());

                int thresholdEdge = RelationStatusPolicy.getThresholdScore(newStatus) - 1;
                room.updateAffection(thresholdEdge);
                room.updateStatusLevel(oldStatus);
                room.markPromotionWaiting(newStatus); // 즉시 시작 대신 대기
            }
            return null;
        }
    }

    /**
     * [Phase 5.5-EV] topic_concluded=true 시 대기 중인 승급 이벤트 개시
     */
    private PromotionEvent checkAndStartDeferredPromotion(ChatRoom room, boolean topicConcluded) {
        if (!topicConcluded || !room.isPromotionWaitingForTopic()) {
            return null;
        }

        boolean started = room.tryStartPromotionFromWaiting();
        if (!started) return null;

        RelationStatus target = room.getPendingTargetStatus();
        log.info("🎯 [PROMOTION] Deferred promotion STARTED | target={} | roomId={}",
            target, room.getId());

        return new PromotionEvent("STARTED", target.name(),
            RelationStatusPolicy.getDisplayName(target),
            RelationStatusPolicy.PROMOTION_MAX_TURNS, 0, null);
    }

    private PromotionEvent resolvePromotionResult(ChatRoom room) {
        int totalStatDelta = room.getPromotionMoodScore();
        RelationStatus target = room.getPendingTargetStatus();
        boolean success = totalStatDelta >= RelationStatusPolicy.PROMOTION_SUCCESS_THRESHOLD;

        log.info("🎯 [PROMOTION] RESULT: {} | statDelta={}/{} | target={} | roomId={}",
            success ? "SUCCESS" : "FAILURE",
            totalStatDelta, RelationStatusPolicy.PROMOTION_SUCCESS_THRESHOLD,
            target, room.getId());

        if (success) {
            room.completePromotionSuccess();
            room.updateAffection(RelationStatusPolicy.getThresholdScore(target));
            List<UnlockInfo> unlocks = room.getCharacter().getUnlocksForRelation(target).stream()
                .map(u -> new UnlockInfo(u.type(), u.name(), u.displayName()))
                .collect(Collectors.toList());
            return new PromotionEvent("SUCCESS", target.name(),
                RelationStatusPolicy.getDisplayName(target), 0, totalStatDelta, unlocks);
        } else {
            room.completePromotionFailure();
            int penalty = RelationStatusPolicy.PROMOTION_FAILURE_PENALTY;
            int penalized = Math.max(0, RelationStatusPolicy.getThresholdScore(target) - 1 - penalty);
            room.updateAffection(penalized);
            room.updateStatusLevel(RelationStatusPolicy.fromScore(penalized));
            return new PromotionEvent("FAILURE", target.name(),
                RelationStatusPolicy.getDisplayName(target), 0, totalStatDelta, null);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 LLM 스트림 호출 + 파싱
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ParsedLlmResult streamLlmAndParse(ChatRoom room, long logCountForRag,
                                              boolean effectiveSecretMode,
                                              SseEmitter emitter, RollbackContext rollbackCtx) {
        // RAG 메모리
        String longTermMemory = "";
        if (logCountForRag >= RAG_SKIP_LOG_THRESHOLD) {
            try {
                longTermMemory = memoryService.retrieveContext(room.getId());
            } catch (Exception e) {
                log.warn("RAG failed (non-blocking): {}", e.getMessage());
            }
        }

        // 프롬프트 조립
        CharacterPromptAssembler.SystemPromptPayload systemPrompt =
            promptAssembler.assembleSystemPrompt(
                room.getCharacter(), room, room.getUser(),
                longTermMemory, effectiveSecretMode);

        List<OpenAiMessage> messages = buildMessageHistory(
            room.getId(), systemPrompt,
            room.getCharacter().getName(), room.getUser().getNickname());
        String model = boostModeResolver.resolveModel(room.getUser());

        Map<String, Object> providerRouting = Map.of(
            "order", List.of("Google AI Studio"),
            "allow_fallbacks", false
        );

        OpenAiChatRequest llmRequest = new OpenAiChatRequest(
            model, messages, 0.8, true, 0.3, 0.15, providerRouting);

        // ── LLM 스트림 ──
        StreamResult streamResult;
        try {
            streamResult = streamClient.streamCompletion(
                llmRequest,
                // ── 콜백 1: 첫 번째 씬 완성 시점 ──
                firstSceneJson -> {
                    try {
                        AiJsonOutput.Scene scene = objectMapper.readValue(firstSceneJson, AiJsonOutput.Scene.class);
                        EmotionTag emotion = parseEmotion(scene.emotion());
                        SceneResponse firstScene = new SceneResponse(
                            scene.speaker(),
                            scene.narration(), scene.dialogue(), emotion,
                            safeUpperCase(scene.location()), safeUpperCase(scene.time()),
                            safeUpperCase(scene.outfit()), safeUpperCase(scene.bgmMode()));
                        emitter.send(SseEmitter.event().name("first_scene")
                            .data(objectMapper.writeValueAsString(firstScene)));
                    } catch (Exception e) {
                        log.warn("first_scene send failed: {}", e.getMessage());
                    }
                },
                // ── [Fix 2] 콜백 2: event_status 선행 전송 ──
                eventStatus -> {
                    try {
                        Map<String, String> meta = Map.of("eventStatus", eventStatus);
                        emitter.send(SseEmitter.event().name("event_meta")
                            .data(objectMapper.writeValueAsString(meta)));
                        log.info("🎬 [SSE] event_meta sent: {} (before first_scene)", eventStatus);
                    } catch (Exception e) {
                        log.warn("event_meta send failed: {}", e.getMessage());
                    }
                }
            );
        } catch (Exception e) {
            log.error("LLM stream failed | roomId={}", room.getId(), e);
            compensateFullRollback(rollbackCtx);
            sendSseError(emitter, "LLM_ERROR", "AI 응답 생성 실패");
            return null;
        }

        // ── JSON 파싱 ──
        AiJsonOutput aiOutput;
        String cleanJson;
        try {
            cleanJson = extractJson(streamResult.fullResponse());
            aiOutput = objectMapper.readValue(cleanJson, AiJsonOutput.class);
        } catch (JsonProcessingException e) {
            log.error("JSON Parse Error: {}", streamResult.fullResponse(), e);
            compensateFullRollback(rollbackCtx);
            sendSseError(emitter, "PARSE_ERROR", "AI 응답 형식 오류");
            return null;
        }

        // ── 결과 정리 ──
        // [Phase 5.5-Fix] cleanContent: 나레이션 + 대사 통합 (재로딩 시 fallback 표시용)
        String combinedDialogue = buildRichCleanContent(aiOutput.scenes());

        String lastEmotionStr = aiOutput.scenes().isEmpty() ? "NEUTRAL"
            : aiOutput.scenes().get(aiOutput.scenes().size() - 1).emotion();
        EmotionTag mainEmotion = parseEmotion(lastEmotionStr);

        List<SceneResponse> sceneResponses = aiOutput.scenes().stream()
            .map(s -> new SceneResponse(
                s.speaker(),                        // [Phase 5.5-NPC] 화자
                s.narration(), s.dialogue(), parseEmotion(s.emotion()),
                safeUpperCase(s.location()), safeUpperCase(s.time()),
                safeUpperCase(s.outfit()), safeUpperCase(s.bgmMode())))
            .collect(Collectors.toList());

        // [Phase 5.5-Fix] scenesJson: 씬 배열 구조화 저장 (재로딩 시 씬별 분리 복원용)
        String scenesJson = buildScenesJson(sceneResponses);

        String innerThought = aiOutput.innerThought();
        if (innerThought != null && innerThought.isBlank()) innerThought = null;

        return new ParsedLlmResult(
            aiOutput, cleanJson, combinedDialogue, mainEmotion, sceneResponses,
            extractLastNonNull(sceneResponses, SceneResponse::bgmMode),
            extractLastNonNull(sceneResponses, SceneResponse::location),
            extractLastNonNull(sceneResponses, SceneResponse::outfit),
            extractLastNonNull(sceneResponses, SceneResponse::time),
            aiOutput.statChanges(), aiOutput.bpm(), innerThought,
            aiOutput.isTopicConcluded(),
            aiOutput.eventStatus(),
            scenesJson
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private boolean resolveSecretMode(ChatRoom room) {
        return room.getUser().getIsSecretMode()
            && secretModeService.canAccessSecretMode(room.getUser(), room.getCharacter().getId());
    }

    private String saveAssistantLog(Long roomId, ParsedLlmResult parsed) {
        try {
            ChatLogDocument assistantLog = ChatLogDocument.assistantWithThought(
                roomId, parsed.cleanJson(), parsed.combinedDialogue(),
                parsed.mainEmotion(), null, parsed.innerThought(), parsed.scenesJson());
            return chatLogRepository.save(assistantLog).getId();
        } catch (Exception e) {
            log.error("⚠️ ASSISTANT log save failed | roomId={}", roomId, e);
            return null;
        }
    }

    private void sendFinalResult(SseEmitter emitter, SendChatResponse response,
                                 boolean hasInnerThought, String assistantLogId) {
        try {
            SendChatResponse finalResponse = new SendChatResponse(
                response.roomId(), response.scenes(),
                response.currentAffection(), response.relationStatus(),
                response.promotionEvent(), response.endingTrigger(), response.easterEgg(),
                response.stats(), response.bpm(),
                response.dynamicRelationTag(), response.characterThought(),
                hasInnerThought, assistantLogId,
                response.topicConcluded(), response.eventStatus());

            emitter.send(SseEmitter.event().name("final_result")
                .data(objectMapper.writeValueAsString(finalResponse)));
        } catch (Exception e) {
            log.warn("final_result send failed: {}", e.getMessage());
        }
    }

    private EasterEggEvent processEasterEgg(AiJsonOutput aiOutput, Long userId) {
        String eggTrigger = aiOutput.easterEggTrigger();
        if (eggTrigger == null || eggTrigger.isBlank()) return null;
        try {
            EasterEggType eggType = EasterEggType.valueOf(eggTrigger.toUpperCase());
            var unlock = achievementService.unlockEasterEgg(userId, eggType);
            boolean revert = (eggType == EasterEggType.FOURTH_WALL);
            return new EasterEggEvent(eggType.name(),
                new AchievementInfo(unlock.code(), unlock.title(), unlock.titleKo(),
                    unlock.description(), unlock.icon(), unlock.isNew()), revert);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void applyStatChanges(ChatRoom room, AiJsonOutput.StatChanges sc, boolean isSecretMode) {
        if (sc == null) return;
        room.applyNormalStatChanges(sc.safeIntimacy(), sc.safeAffection(),
            sc.safeDependency(), sc.safePlayfulness(), sc.safeTrust());
        if (isSecretMode) {
            room.applySecretStatChanges(sc.safeLust(), sc.safeCorruption(), sc.safeObsession());
        }
    }

    private StatsSnapshot buildStatsSnapshot(ChatRoom room, boolean isSecretMode) {
        return new StatsSnapshot(room.getStatIntimacy(), room.getStatAffection(),
            room.getStatDependency(), room.getStatPlayfulness(), room.getStatTrust(),
            isSecretMode ? room.getStatLust() : null,
            isSecretMode ? room.getStatCorruption() : null,
            isSecretMode ? room.getStatObsession() : null);
    }

    private void sendSseError(SseEmitter emitter, String errorCode, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                .data(objectMapper.writeValueAsString(Map.of("errorCode", errorCode, "message", message))));
            emitter.complete();
        } catch (Exception e) {
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        }
    }

    private void compensateEnergy(Long userId, int cost, String username) {
        try {
            txTemplate.execute(status -> {
                User user = userRepository.findById(userId)
                    .orElseThrow(() -> new NotFoundException("User not found"));
                user.refundEnergy(cost);
                userRepository.save(user);
                return null;
            });
        } catch (Exception ex) {
            log.error("Energy refund FAILED: userId={}", userId, ex);
        }
        cacheService.evictUserProfile(username);
    }

    private void compensateFullRollback(RollbackContext ctx) {
        if (ctx.savedUserLogId() != null) {
            try { chatLogRepository.deleteById(ctx.savedUserLogId()); }
            catch (Exception ex) { log.error("User msg delete FAILED", ex); }
        }
        compensateEnergy(ctx.userId(), ctx.energyCost(), ctx.username());
    }

    private void triggerPostProcessing(Long roomId, Long userId, long totalLogCount, boolean isSecretMode) {
        long userMsgCount = chatLogRepository.countByRoomIdAndRole(roomId, ChatRole.USER);
        if (userMsgCount > 0 && userMsgCount % USER_TURN_MEMORY_CYCLE == 0) {
            memoryService.summarizeAndSaveMemory(roomId, userId);
        }
        long thoughtCycle = 10, thoughtOffset = 5;
        if (userMsgCount > 0 && userMsgCount % thoughtCycle == thoughtOffset) {
            chatService.generateCharacterThoughtAsync(roomId, userId, (int) userMsgCount, isSecretMode);
        }
    }

    /**
     * [Phase 5.5-Fix] LLM 히스토리 구성
     *
     * Bug Fix 1 (Role Reversal): 모든 메시지에 화자 이름표 강제 부착
     *   - USER: "[유저닉네임]: 원본 메시지"
     *   - ASSISTANT: "[캐릭터이름]: 파싱된 대사" (NPC 씬은 "[NPC이름]: ...")
     *   - SYSTEM: "[NARRATION]: 내용"
     *
     * Bug Fix 2 (Format Inertia): buildSanitizedAssistantContent 포맷 디커플링
     *   - AS-IS: (나레이션) 대사 [JOY]
     *   - TO-BE: [Emotion: JOY] (나레이션) 대사
     *
     * @param characterName 캐릭터 이름 (assistant 메시지 이름표용)
     * @param userNickname  유저 닉네임 (user 메시지 이름표용)
     */
    private List<OpenAiMessage> buildMessageHistory(Long roomId,
                                                    CharacterPromptAssembler.SystemPromptPayload systemPrompt,
                                                    String characterName, String userNickname) {
        List<ChatLogDocument> history = chatLogRepository.findTop200ByRoomIdOrderByCreatedAtDesc(roomId);
        history.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));
        List<OpenAiMessage> messages = new ArrayList<>();

        if (history.size() == 3 || history.size() % 20 == 0) {
            messages.add(OpenAiMessage.systemCached(systemPrompt.staticRules(), Map.of("type", "ephemeral")));
        } else {
            messages.add(OpenAiMessage.system(systemPrompt.staticRules()));
        }

        // [Phase 5.5-Fix] 이름표 prefix
        String userTag = "[" + (userNickname != null && !userNickname.isBlank() ? userNickname : "유저") + "]: ";
        String charTag = "[" + characterName + "]: ";

        for (ChatLogDocument chatLog : history) {
            switch (chatLog.getRole()) {
                case USER -> messages.add(OpenAiMessage.user(userTag + chatLog.getRawContent()));
                case ASSISTANT -> messages.add(OpenAiMessage.assistant(
                    buildSanitizedAssistantContent(chatLog, characterName)));
                case SYSTEM -> messages.add(OpenAiMessage.user("[NARRATION]: " + chatLog.getRawContent()));
            }
        }

        messages.add(OpenAiMessage.system(systemPrompt.dynamicRules()));
        messages.add(OpenAiMessage.system(systemPrompt.outputFormat()));
        return messages;
    }

    /**
     * [Phase 5.5-Fix] ASSISTANT 과거 대화를 LLM 히스토리용으로 정제
     *
     * Bug Fix 1 (Role Reversal):
     *   - 씬별로 화자 이름표 부착: [캐릭터이름] 또는 [NPC이름]
     *   - LLM이 "이 대사를 누가 했는지"를 절대 혼동하지 않음
     *
     * Bug Fix 2 (Format Inertia):
     *   - AS-IS: (나레이션) 대사 [JOY]  ← LLM이 이 패턴을 dialogue 필드에 모방
     *   - TO-BE: [Emotion: JOY] (나레이션) "대사" ← 감정 위치 이동 + 대사 인용부호
     *   - output JSON과 구조적으로 이질적이므로 패턴 모방(Few-Shot Leakage) 방지
     */
    private String buildSanitizedAssistantContent(ChatLogDocument chatLog, String characterName) {
        try {
            String raw = chatLog.getRawContent();
            if (raw == null || raw.isBlank()) {
                return "[" + characterName + "]: " +
                    (chatLog.getCleanContent() != null ? chatLog.getCleanContent() : "");
            }
            String cleaned = extractJson(raw);
            AiJsonOutput parsed = objectMapper.readValue(cleaned, AiJsonOutput.class);
            StringBuilder sb = new StringBuilder();
            for (AiJsonOutput.Scene scene : parsed.scenes()) {
                // ── 화자 이름표 (speaker 필드 or 메인 캐릭터) ──
                String speaker = (scene.speaker() != null && !scene.speaker().isBlank())
                    ? scene.speaker() : characterName;
                sb.append("[").append(speaker).append("] ");

                // ── 감정 태그를 앞으로 이동 (디커플링) ──
                if (scene.emotion() != null && !scene.emotion().isBlank()) {
                    sb.append("{Emotion: ").append(scene.emotion()).append("} ");
                }

                // ── 나레이션 (괄호 유지) ──
                if (scene.narration() != null && !scene.narration().isBlank()) {
                    sb.append("(").append(scene.narration().trim()).append(") ");
                }

                // ── 대사 (인용부호로 감싸서 output format과 구분) ──
                if (scene.dialogue() != null && !scene.dialogue().isBlank()) {
                    sb.append("\"").append(scene.dialogue().trim()).append("\"");
                }
                sb.append("\n");
            }
            String result = sb.toString().trim();
            return result.isEmpty()
                ? "[" + characterName + "]: " + (chatLog.getCleanContent() != null ? chatLog.getCleanContent() : "")
                : result;
        } catch (Exception e) {
            // JSON 파싱 실패 시 fallback — 이름표는 붙인다
            String content = chatLog.getCleanContent() != null ? chatLog.getCleanContent() : chatLog.getRawContent();
            return "[" + characterName + "]: " + content;
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        String text = raw.trim();

        // Step 1: Markdown 코드 블록 제거
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        text = text.trim();

        // Step 2: 첫 번째 '{' 위치 찾기
        int jsonStart = text.indexOf('{');
        if (jsonStart < 0) {
            // JSON 객체가 없으면 원본 반환 (파싱 단계에서 에러 처리)
            return text;
        }

        if (jsonStart > 0) {
            // JSON 앞에 프리앰블 텍스트가 있으면 경고 로그 + 제거
            String preamble = text.substring(0, jsonStart).trim();
            if (!preamble.isEmpty()) {
                log.warn("⚠️ [JSON] Stripped preamble before JSON ({}chars): '{}'",
                    preamble.length(),
                    preamble.substring(0, Math.min(80, preamble.length())));
            }
            text = text.substring(jsonStart);
        }

        // Step 3: 마지막 '}' 이후 텍스트 제거
        int lastBrace = text.lastIndexOf('}');
        if (lastBrace >= 0 && lastBrace < text.length() - 1) {
            text = text.substring(0, lastBrace + 1);
        }

        return text.trim();
    }

    private EmotionTag parseEmotion(String s) {
        try { return EmotionTag.valueOf(s.toUpperCase()); } catch (Exception e) { return EmotionTag.NEUTRAL; }
    }

    private String safeUpperCase(String v) {
        if (v == null || v.isBlank() || "null".equalsIgnoreCase(v)) return null;
        return v.toUpperCase().trim();
    }

    private String extractLastNonNull(List<SceneResponse> scenes, java.util.function.Function<SceneResponse, String> ext) {
        for (int i = scenes.size() - 1; i >= 0; i--) {
            String val = ext.apply(scenes.get(i));
            if (val != null) return val;
        }
        return null;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-Fix] 3-Layer 통일 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 씬 배열에서 나레이션 + 대사 + speaker를 포함한 풍부한 cleanContent 생성.
     * 재로딩 시 scenesJson이 없을 경우의 fallback 표시용.
     */
    private String buildRichCleanContent(List<AiJsonOutput.Scene> scenes) {
        if (scenes == null || scenes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scenes.size(); i++) {
            AiJsonOutput.Scene scene = scenes.get(i);
            if (i > 0) sb.append("\n---\n"); // 씬 구분자
            if (scene.speaker() != null && !scene.speaker().isBlank()) {
                sb.append("[").append(scene.speaker()).append("] ");
            }
            if (scene.narration() != null && !scene.narration().isBlank()) {
                sb.append("*").append(scene.narration().trim()).append("*\n");
            }
            if (scene.dialogue() != null && !scene.dialogue().isBlank()) {
                sb.append(scene.dialogue().trim());
            }
        }
        return sb.toString().trim();
    }

    /**
     * SceneResponse 리스트를 JSON 배열 문자열로 직렬화.
     * 프론트에서 재로딩 시 씬별 분리/speaker/narration을 완전히 복원.
     */
    private String buildScenesJson(List<SceneResponse> scenes) {
        if (scenes == null || scenes.isEmpty()) return null;
        try {
            // SceneResponse를 가벼운 Map으로 변환 (불필요한 필드 제거)
            List<Map<String, Object>> simplified = scenes.stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                if (s.speaker() != null) m.put("speaker", s.speaker());
                if (s.narration() != null) m.put("narration", s.narration());
                if (s.dialogue() != null) m.put("dialogue", s.dialogue());
                if (s.emotion() != null) m.put("emotion", s.emotion().name());
                return m;
            }).collect(Collectors.toList());
            return objectMapper.writeValueAsString(simplified);
        } catch (Exception e) {
            log.warn("scenesJson serialization failed", e);
            return null;
        }
    }
}