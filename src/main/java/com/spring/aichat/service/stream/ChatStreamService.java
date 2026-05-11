package com.spring.aichat.service.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.*;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.dto.chat.SendChatResponse.*;
import com.spring.aichat.dto.director.DirectorDirective;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.LlmCircuitBreaker;
import com.spring.aichat.external.LlmCircuitBreaker.TtftTimeoutException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.external.OpenRouterStreamClient;
import com.spring.aichat.external.OpenRouterStreamClient.StreamResult;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.AchievementService;
import com.spring.aichat.service.ChatService;
import com.spring.aichat.service.ContentModerationService;
import com.spring.aichat.service.MemoryService;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.director.DirectorService;
import com.spring.aichat.service.illustration.BackgroundGenerationService;
import com.spring.aichat.service.illustration.IllustrationService;
import com.spring.aichat.service.payment.BoostModeResolver;
import com.spring.aichat.service.payment.SecretModeService;
import com.spring.aichat.service.prompt.CharacterPromptAssembler;
import com.spring.aichat.service.prompt.DirectorPromptAssembler;
import com.spring.aichat.service.theater.TheaterInterventionService;
import com.spring.aichat.service.util.LlmOutputParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.function.Consumer;
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
 *
 * [Phase 5.5-Stability] LLM Provider 서킷 브레이커:
 *   - TTFT 기반 AI Studio ↔ Vertex 동적 라우팅
 *   - Per-request fallback: TTFT 2초 초과 시 즉시 Vertex 재시도
 *   - Circuit breaker: 연속 3회 초과 → 5분간 Vertex 전환 → 프로브 복구
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
    private final IllustrationService illustrationService;
    private final BackgroundGenerationService backgroundGenerationService;
    private final LlmCircuitBreaker llmCircuitBreaker;
    private final DirectorService directorService;
    /**
     * [Phase III · 작업 4] Theater 난입 통합용
     *
     * Theater 모드에서 유저가 "난입" 후 ChatService를 통해 직접 대화할 때,
     * 매 ASSISTANT 응답의 logId를 InterventionService에 알려줘야 한다.
     * 이게 빠지면 resumeFromIntervention의 redirectHint에 "마지막 로그 ID: null"이
     * 박혀 들어가서 LLM이 개입의 맥락을 정확히 받지 못한다.
     *
     * 비-Theater 방에는 아무 영향도 주지 않으므로 안전하게 주입.
     */
    private final TheaterInterventionService theaterInterventionService;
    /** [Phase6/Tier3 / C-9] ASSISTANT log retry + deadletter wrapper */
    private final ChatLogPersister chatLogPersister;

    private static final long USER_TURN_MEMORY_CYCLE = 10;
    private static final long RAG_SKIP_LOG_THRESHOLD = USER_TURN_MEMORY_CYCLE * 2;

    /** [Phase 5.5-EV] 시간 넘기기 에너지 비용 */
    private static final int TIME_SKIP_ENERGY_COST = 1;

    // [Phase 5.5-Fix-IT] 속마음 히스토리 포함 윈도우 — 최근 N개 ASSISTANT 메시지에만 속마음 포함
    private static final int INNER_THOUGHT_HISTORY_WINDOW = 3;

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
        String scenesJson,      // [Phase 5.5-Fix] 구조화된 씬 JSON
        boolean generateIllustration,
        String newLocationName,
        String locationDescription
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
            boolean isSecretCheck = roomForCheck.isSecretModeActive()
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

                    // [Phase 5.5-Sep] topic_concluded + event: 스토리 모드 전용
                    if (ChatModePolicy.supportsTopicConcluded(freshRoom.getChatMode())) {
                        freshRoom.updateTopicConcluded(parsed.topicConcluded());
                    }

                    if (wasEventActive && ChatModePolicy.supportsEvents(freshRoom.getChatMode())) {
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

                    // [Phase 5.5-Director] 디렉터 constraint 소비 (일회성)
                    // 이 턴에서 constraint가 사용되었으면 클리어
                    if (freshRoom.hasActiveDirectorConstraint() && !freshRoom.isEventActive()) {
                        freshRoom.clearDirectorInterlude();
                    }

                    // 이벤트 모드에서는 RESOLVED될 때까지 constraint 유지
                    if (wasEventActive && "RESOLVED".equalsIgnoreCase(parsed.eventStatus())) {
                        freshRoom.clearDirectorInterlude();
                    }

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

                    // [Phase 5.5-Sep] 승급 대기: 스토리 모드 전용
                    PromotionEvent deferredPromo = isStory
                        ? checkAndStartDeferredPromotion(freshRoom, parsed.topicConcluded()) : null;
                    if (deferredPromo != null) promoEvent = deferredPromo;

                    // 엔딩 트리거
                    EndingTrigger endingTrigger = null;
                    if (isStory) {
                        String endingCheck = freshRoom.checkEndingTrigger();
                        if (endingCheck != null) {
                            endingTrigger = new EndingTrigger(endingCheck);

                            // ★ [Phase 5.5-Illust] 엔딩 도달 시 자동 일러스트 생성
                            illustrationService.generateAutoIllustration(
                                freshRoom.getUser().getId(), freshRoom.getCharacter().getId(),
                                freshRoom.getId(), "ENDING");
                        }
                    }

                    // [Phase 5.5-Sep] 이스터에그: 스토리 모드 전용
                    EasterEggEvent easterEgg = isStory
                        ? processEasterEgg(parsed.aiOutput(), jpa.userId()) : null;

                    StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                    return new SendChatResponse(roomId, parsed.sceneResponses(),
                        freshRoom.getAffectionScore(), freshRoom.getStatusLevel().name(),
                        promoEvent, endingTrigger, easterEgg,
                        statsSnapshot, freshRoom.getCurrentBpm(),
                        freshRoom.getDynamicRelationTag(), null,
                        false, null,
                        isStory ? freshRoom.isTopicConcluded() : false,
                        isStory ? (wasEventActive ? "RESOLVED" : (freshRoom.isEventActive() ? freshRoom.getEventStatus() : null)) : null);
                });
            } catch (Exception e) {
                log.error("❌ TX-2 failed | roomId={}", roomId, e);
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "응답 처리 중 오류가 발생했습니다.");
                return;
            }

            // ── MongoDB: ASSISTANT 저장 ──
            // [Phase6/Tier3 / C-9] 단순 try-catch → ChatLogPersister(retry + deadletter)로 위임.
            //   기존 흐름: save 실패 → 로그만 → SSE 정상 전송 → history 누락 → 정합성 파괴.
            //   신규 흐름: 3회 재시도 + 데드레터 보존 + null 시 운영 alert.
            String assistantLogId = null;
            boolean hasInnerThought = false;
            String innerThoughtToSave = isStory ? parsed.innerThought() : null;
            ChatLogDocument assistantLog = ChatLogDocument.assistantWithThought(
                roomId, parsed.cleanJson(), parsed.combinedDialogue(),
                parsed.mainEmotion(), null, innerThoughtToSave, parsed.scenesJson());
            ChatLogDocument saved = chatLogPersister.saveWithRetry(assistantLog);
            if (saved != null) {
                assistantLogId = saved.getId();
                hasInnerThought = saved.hasInnerThought();
            } else {
                log.error("⚠️ [CHAT-LOG] ASSISTANT_LOG_PERSIST_FAILED — deadlettered | roomId={}", roomId);
                // SSE는 이미 final_result로 전송됨 → 유저 경험은 유지. 운영자가 데드레터 점검 후 수동 복구.
            }
            cacheService.evictRoomInfo(roomId);

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            //  [Phase III · 작업 4] Theater 난입 통합
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            //  Theater 모드 + 난입 활성 상태에서 발생한 ASSISTANT 응답이라면,
            //  마지막 logId를 InterventionService에 알려서 resumeFromIntervention
            //  시 redirectHint에 정확한 컨텍스트가 박히도록 한다.
            //
            //  - 비-Theater 방: chatMode 체크에서 즉시 fallthrough
            //  - 난입 비활성: recordInterventionLog 내부에서 조용히 noop
            //  - 어떤 실패도 채팅 흐름을 깨지 않는다 (try-catch 격리)
            if (assistantLogId != null
                && jpa.room().getChatMode() == ChatMode.THEATER) {
                try {
                    theaterInterventionService.recordInterventionLog(roomId, assistantLogId);
                } catch (Exception e) {
                    log.warn("🎭 [INTERVENTION] log relay failed | roomId={} | logId={}: {}",
                        roomId, assistantLogId, e.getMessage());
                }
            }

            // ★ [Phase 5.5-Illust] 새로운 장소 전환 처리 ★
            // [Bug Fix] 동일 장소 반복 전환 방지 가드
            LocationTransition locationTransition = null;
            if (isStory && parsed.newLocationName() != null && !parsed.newLocationName().isBlank()) {
                String currentDynamic = jpa.room().getCurrentDynamicLocationName();
                if (currentDynamic != null && isSameLocation(currentDynamic, parsed.newLocationName())) {
                    log.info("🛡️ [BG-GUARD] Duplicate dynamic location skipped: '{}' ≈ '{}' | roomId={}",
                        currentDynamic, parsed.newLocationName(), roomId);
                } else {
                    String timeOfDay = parsed.lastTime() != null ? parsed.lastTime() : "DAY";
                    BackgroundGenerationService.BackgroundResult bgResult =
                        backgroundGenerationService.resolveBackground(
                            parsed.newLocationName(), parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId());

                    if (bgResult.isCacheHit()) {
                        locationTransition = LocationTransition.cached(
                            parsed.newLocationName(), bgResult.imageUrl());
                    } else {
                        locationTransition = LocationTransition.generating(
                            parsed.newLocationName(), bgResult.cacheHash());
                        backgroundGenerationService.generateBackgroundAsync(
                            parsed.newLocationName(), parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId());
                    }

                    final String bgUrlToStore = bgResult.isCacheHit() ? bgResult.imageUrl() : null;
                    final String locationNameToStore = parsed.newLocationName();
                    try {
                        txTemplate.execute(status -> {
                            ChatRoom bgRoom = chatRoomRepository.findById(roomId).orElse(null);
                            if (bgRoom != null) {
                                if (bgUrlToStore != null) {
                                    bgRoom.updateDynamicBackground(locationNameToStore, bgUrlToStore);
                                } else {
                                    bgRoom.updateDynamicLocationName(locationNameToStore);
                                }
                            }
                            return null;
                        });
                    } catch (Exception e) {
                        log.warn("⚠️ [BG] Dynamic background persistence failed (non-blocking): {}", e.getMessage());
                    }
                    cacheService.evictRoomInfo(roomId);
                }
            }

            // ── SSE: final_result ──
            sendFinalResult(emitter, response, isStory && hasInnerThought, assistantLogId,
                isStory && parsed.generateIllustration(), locationTransition);
            emitter.complete();

            log.info("⏱ [STREAM-PERF] sendMessageStream DONE: {}ms", System.currentTimeMillis() - totalStart);

            triggerPostProcessing(roomId, jpa.userId(), jpa.logCount() + 1, effectiveSecretMode, jpa.room().getChatMode());

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
            // [Phase 5.5-Sep] 이벤트: 스토리 모드 전용
            ChatRoom modeCheck = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
            if (!ChatModePolicy.supportsEvents(modeCheck.getChatMode())) {
                sendSseError(emitter, "MODE_RESTRICTED", "이벤트는 스토리 모드에서만 사용할 수 있습니다.");
                return;
            }
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
            // [Hallucination Fix] hiddenUser → hiddenSystem
            // 이벤트 상황 묘사(캐릭터 행동)를 role="user"로 저장하면
            // LLM이 캐릭터의 행동을 유저 발화로 오귀속하는 환각 발생
            String savedLogId;
            try {
                ChatLogDocument savedLog = chatLogRepository.save(
                    ChatLogDocument.hiddenSystem(roomId, "[EVENT_START]\n" + eventDetail));
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

            sendFinalResult(emitter, response, false, assistantLogId, false, null);
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
        log.info("👀 [DIRECTOR-WATCH] START | roomId={}", roomId);

        try {
            // ── TX-1: 에너지 차감 ──
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                if (!room.isEventActive()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "진행 중인 이벤트가 없습니다.");
                }

                int cost = 1; // 지켜보기 비용
                room.getUser().consumeEnergy(cost);
                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.isPromotionPending(), room.getUser().getUsername(), cost);
            });
            cacheService.evictUserProfile(jpa.username());

            // ── [Director] 강화된 지켜보기 프롬프트 ──
            // 기존: 하드코딩된 SYSTEM_DIRECTOR_PROMPT
            // 개선: DirectorPromptAssembler가 캐릭터/상황에 맞춤 생성
            String eventContext = buildRecentEventContext(roomId);
            String watchPrompt = new DirectorPromptAssembler().assembleWatchDirective(
                jpa.room().getCharacter(), jpa.room(), eventContext);

            // MongoDB에 SYSTEM_DIRECTOR 메시지 저장
            String savedLogId;
            try {
                ChatLogDocument savedLog = chatLogRepository.save(
                    ChatLogDocument.hiddenSystem(roomId, watchPrompt));
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

            // ── TX-2: 이벤트 상태만 업데이트 (스탯 동결) ──
            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> {
                    ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                    // 지켜보기 중에는 스탯 동결, BPM만 갱신
                    if (parsed.bpm() != null) freshRoom.updateBpm(parsed.bpm());
                    freshRoom.updateLastActive(parsed.mainEmotion());

                    // event_status 업데이트 (LLM이 자체 종료하면 RESOLVED)
                    if (parsed.eventStatus() != null) {
                        freshRoom.updateEventStatus(parsed.eventStatus());
                    }

                    // 지켜보기에서도 씬 상태는 업데이트 (장소 이동 등)
                    freshRoom.updateSceneState(parsed.lastBgm(), parsed.lastLoc(),
                        parsed.lastOutfit(), parsed.lastTime());

                    StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                    return new SendChatResponse(roomId, parsed.sceneResponses(),
                        freshRoom.getAffectionScore(), freshRoom.getStatusLevel().name(),
                        null, null, null,
                        statsSnapshot, freshRoom.getCurrentBpm(),
                        freshRoom.getDynamicRelationTag(), null,
                        false, null,
                        false, freshRoom.getEventStatus());
                });
            } catch (Exception e) {
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "지켜보기 처리 실패");
                return;
            }

            String assistantLogId = saveAssistantLog(roomId, parsed);
            cacheService.evictRoomInfo(roomId);

            sendFinalResult(emitter, response, false, assistantLogId, false, null);
            emitter.complete();

            log.info("👀 [DIRECTOR-WATCH] DONE | roomId={}", roomId);

        } catch (Exception e) {
            log.error("❌ Director watch error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "지켜보기 처리 중 오류 발생");
        }
    }

    /**
     * 최근 이벤트 컨텍스트 구성 (지켜보기 프롬프트용)
     */
    private String buildRecentEventContext(Long roomId) {
        List<ChatLogDocument> recent = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));

        // 이벤트 시작 이후의 로그만 추출
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = recent.size() - 1; i >= 0 && count < 6; i--) {
            ChatLogDocument doc = recent.get(i);
            String content = doc.getCleanContent() != null ? doc.getCleanContent() : "";
            if (content.length() > 150) content = content.substring(0, 150) + "...";
            sb.insert(0, "[" + doc.getRole().name() + "] " + content + "\n");
            count++;
        }
        return sb.toString().trim();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  4. [Phase 5.5-EV] 시간 넘기기 (SSE)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void sendTimeSkipStream(Long roomId, SseEmitter emitter) {
        log.info("⏭ [TIME_SKIP] START | roomId={}", roomId);

        try {
            // [Phase 5.5-Sep] 시간 넘기기: 스토리 모드 전용
            ChatRoom modeCheck = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
            if (!ChatModePolicy.supportsDirectorMode(modeCheck.getChatMode())) {
                sendSseError(emitter, "MODE_RESTRICTED", "시간 넘기기는 스토리 모드에서만 사용할 수 있습니다.");
                return;
            }
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

            //   // ★ [Phase 5.5-Illust] 시간 넘기기에서도 장소 전환 가능 ★
            // [Bug Fix] 동일 장소 반복 전환 방지 가드
            SendChatResponse.LocationTransition timeSkipLocationTransition = null;
            if (parsed.newLocationName() != null && !parsed.newLocationName().isBlank()) {
                String currentDynamic = jpa.room().getCurrentDynamicLocationName();
                if (currentDynamic != null && isSameLocation(currentDynamic, parsed.newLocationName())) {
                    log.info("🛡️ [BG-GUARD] TimeSkip duplicate location skipped: '{}' ≈ '{}' | roomId={}",
                        currentDynamic, parsed.newLocationName(), roomId);
                } else {
                    String timeOfDay = parsed.lastTime() != null ? parsed.lastTime() : "DAY";
                    BackgroundGenerationService.BackgroundResult bgResult =
                        backgroundGenerationService.resolveBackground(
                            parsed.newLocationName(), parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId());

                    if (bgResult.isCacheHit()) {
                        timeSkipLocationTransition = SendChatResponse.LocationTransition.cached(
                            parsed.newLocationName(), bgResult.imageUrl());
                    } else {
                        timeSkipLocationTransition = SendChatResponse.LocationTransition.generating(
                            parsed.newLocationName(), bgResult.cacheHash());
                        backgroundGenerationService.generateBackgroundAsync(
                            parsed.newLocationName(), parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId());
                    }

                    final String bgUrlToStore = bgResult.isCacheHit() ? bgResult.imageUrl() : null;
                    final String locationNameToStore = parsed.newLocationName();
                    try {
                        txTemplate.execute(status -> {
                            ChatRoom bgRoom = chatRoomRepository.findById(roomId).orElse(null);
                            if (bgRoom != null) {
                                if (bgUrlToStore != null) {
                                    bgRoom.updateDynamicBackground(locationNameToStore, bgUrlToStore);
                                } else {
                                    bgRoom.updateDynamicLocationName(locationNameToStore);
                                }
                            }
                            return null;
                        });
                    } catch (Exception e) {
                        log.warn("⚠️ [BG] Dynamic background persistence failed (non-blocking): {}", e.getMessage());
                    }
                    cacheService.evictRoomInfo(roomId);
                }
            }

            sendFinalResult(emitter, response, false, assistantLogId, false, timeSkipLocationTransition);
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
            // [Phase 5.5-Fix] 승급 감지 → 5종 스탯 MAX 기반으로 통일 (기존: affection만 사용 → 버그)
            RelationStatus oldStatus = room.getStatusLevel();
            room.applyLegacyAffectionChange(parsed.aiOutput().affectionChange());

            // [Fix] fromScore(affectionOnly) → fromStats(max of all 5 stats)
            RelationStatus newStatus = RelationStatusPolicy.fromStats(
                room.getStatAffection(),
                room.getStatIntimacy(), room.getStatAffection(),
                room.getStatDependency(), room.getStatPlayfulness(), room.getStatTrust()
            );

            if (RelationStatusPolicy.isUpgrade(oldStatus, newStatus)) {
                log.info("🎯 [PROMOTION] Upgrade detected → WAITING for topic_concluded | {} → {} | maxStat={} | roomId={}",
                    oldStatus, newStatus, room.getMaxNormalStatValue(), room.getId());

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
            // ★ [Phase 5.5-Illust] 승급 성공 시 자동 일러스트 생성
            illustrationService.generateAutoIllustration(
                room.getUser().getId(), room.getCharacter().getId(),
                room.getId(), "PROMOTION");
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
        long ragThreshold = ChatModePolicy.getRagSkipThreshold(room.getChatMode());
        if (logCountForRag >= ragThreshold) {
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

        // ━━━ [Phase 5.5-Stability] 서킷 브레이커 기반 Provider 결정 ━━━
        LlmCircuitBreaker.ProviderDecision decision = llmCircuitBreaker.decide();
        log.info("🔌 [CIRCUIT] Provider decision: {} | deadline={}ms | state={} | roomId={}",
            decision.provider(), decision.ttftDeadlineMs(), llmCircuitBreaker.getState(), room.getId());

        // ── SSE 콜백 정의 (Primary/Fallback 재시도 시에도 동일하게 사용) ──
        // [Polish · P1 #2] dialogue prefix sanitizer를 위해 알려진 화자 이름 모음.
        //   STORY/SANDBOX 모드는 캐릭터(메인 화자) + 유저 nickname만 안다.
        //   NPC 화자가 등장할 수 있으므로 안전한 화이트리스트 매칭만 수행.
        final java.util.Set<String> sanitizerSpeakers = new java.util.LinkedHashSet<>();
        if (room.getCharacter() != null && room.getCharacter().getName() != null) {
            sanitizerSpeakers.add(room.getCharacter().getName().trim());
        }
        if (room.getUser() != null && room.getUser().getNickname() != null) {
            sanitizerSpeakers.add(room.getUser().getNickname().trim());
        }

        Consumer<String> onFirstScene = firstSceneJson -> {
            try {
                AiJsonOutput.Scene scene = objectMapper.readValue(firstSceneJson, AiJsonOutput.Scene.class);
                EmotionTag emotion = LlmOutputParser.parseEmotion(scene.emotion());
                // [Polish · P1 #2] dialogue / narration 화자 prefix 제거
                String sanitizedDialogue = com.spring.aichat.service.util.DialogueSanitizer
                    .stripSpeakerPrefix(scene.dialogue(), sanitizerSpeakers);
                String sanitizedNarration = com.spring.aichat.service.util.DialogueSanitizer
                    .stripSpeakerPrefix(scene.narration(), sanitizerSpeakers);
                SceneResponse firstScene = new SceneResponse(
                    scene.speaker(),
                    sanitizedNarration, sanitizedDialogue, emotion,
                    LlmOutputParser.safeUpperCase(scene.location()), LlmOutputParser.safeUpperCase(scene.time()),
                    LlmOutputParser.safeUpperCase(scene.outfit()), LlmOutputParser.safeUpperCase(scene.bgmMode()));
                emitter.send(SseEmitter.event().name("first_scene")
                    .data(objectMapper.writeValueAsString(firstScene)));
            } catch (Exception e) {
                log.warn("first_scene send failed: {}", e.getMessage());
            }
        };
        Consumer<String> onEventStatus = eventStatus -> {
            try {
                Map<String, String> meta = Map.of("eventStatus", eventStatus);
                emitter.send(SseEmitter.event().name("event_meta")
                    .data(objectMapper.writeValueAsString(meta)));
                log.info("🎬 [SSE] event_meta sent: {} (before first_scene)", eventStatus);
            } catch (Exception e) {
                log.warn("event_meta send failed: {}", e.getMessage());
            }
        };

        // ── LLM 스트림 (서킷 브레이커 연동) ──
        StreamResult streamResult;
        try {
            Map<String, Object> providerRouting = Map.of(
                "order", List.of(decision.provider()),
                "allow_fallbacks", false
            );
            OpenAiChatRequest llmRequest = new OpenAiChatRequest(
                model, messages, 0.8, true, 0.3, 0.15, providerRouting, Map.of("type", "json_object"));

            streamResult = streamClient.streamCompletion(
                llmRequest, onFirstScene, onEventStatus, decision.ttftDeadlineMs());

            // ✅ AI Studio 성공 → 서킷 브레이커 기록
            if (decision.isPrimary()) {
                llmCircuitBreaker.recordSuccess(streamResult.ttft());
            }

        } catch (TtftTimeoutException ttftEx) {
            // ━━━ [Stability] TTFT 데드라인 초과 → 실패 기록 + Vertex 즉시 폴백 ━━━
            llmCircuitBreaker.recordFailure(ttftEx.getDeadlineMs());
            log.warn("🔄 [CIRCUIT] TTFT 초과 → Vertex 폴백 | deadline={}ms | state={} | roomId={}",
                ttftEx.getDeadlineMs(), llmCircuitBreaker.getState(), room.getId());

            try {
                Map<String, Object> fallbackRouting = Map.of(
                    "order", List.of(LlmCircuitBreaker.PROVIDER_VERTEX),
                    "allow_fallbacks", false
                );
                OpenAiChatRequest fallbackRequest = new OpenAiChatRequest(
                    model, messages, 0.8, true, 0.3, 0.15, fallbackRouting, Map.of("type", "json_object"));

                streamResult = streamClient.streamCompletion(
                    fallbackRequest, onFirstScene, onEventStatus, 0); // Vertex는 데드라인 없음

                log.info("✅ [CIRCUIT] Vertex 폴백 성공 | TTFT={}ms | roomId={}", streamResult.ttft(), room.getId());

            } catch (Exception fallbackEx) {
                log.error("❌ [CIRCUIT] Vertex 폴백마저 실패 | roomId={}", room.getId(), fallbackEx);
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "LLM_ERROR", "AI 응답 생성 실패 (폴백 포함)");
                return null;
            }

        } catch (Exception e) {
            // 기타 예외 (네트워크 에러, 5xx 등) — AI Studio 시도였다면 실패 기록
            if (decision.isPrimary()) {
                llmCircuitBreaker.recordFailure(-1);
            }
            log.error("LLM stream failed | provider={} | roomId={}", decision.provider(), room.getId(), e);
            compensateFullRollback(rollbackCtx);
            sendSseError(emitter, "LLM_ERROR", "AI 응답 생성 실패");
            return null;
        }

        // ── JSON 파싱 ──
        AiJsonOutput aiOutput;
        String cleanJson;
        try {
            cleanJson = LlmOutputParser.extractJson(streamResult.fullResponse());
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
        EmotionTag mainEmotion = LlmOutputParser.parseEmotion(lastEmotionStr);

        List<SceneResponse> sceneResponses = aiOutput.scenes().stream()
            .map(s -> new SceneResponse(
                s.speaker(),                        // [Phase 5.5-NPC] 화자
                // [Polish · P1 #2] narration / dialogue 화자 prefix 제거.
                //   final_result에서도 일관성 유지 — first_scene과 같은 sanitizerSpeakers 사용.
                com.spring.aichat.service.util.DialogueSanitizer.stripSpeakerPrefix(
                    s.narration(), sanitizerSpeakers),
                com.spring.aichat.service.util.DialogueSanitizer.stripSpeakerPrefix(
                    s.dialogue(), sanitizerSpeakers),
                LlmOutputParser.parseEmotion(s.emotion()),
                LlmOutputParser.safeUpperCase(s.location()), LlmOutputParser.safeUpperCase(s.time()),
                LlmOutputParser.safeUpperCase(s.outfit()), LlmOutputParser.safeUpperCase(s.bgmMode())))
            .collect(Collectors.toList());

        // [Phase 5.5-Fix] scenesJson: 씬 배열 구조화 저장 (재로딩 시 씬별 분리 복원용)
        String scenesJson = buildScenesJson(sceneResponses);

        String innerThought = aiOutput.innerThought();
        if (innerThought != null && innerThought.isBlank()) innerThought = null;

        return new ParsedLlmResult(
            aiOutput, cleanJson, combinedDialogue, mainEmotion, sceneResponses,
            LlmOutputParser.extractLastNonNull(sceneResponses, SceneResponse::bgmMode),
            LlmOutputParser.extractLastNonNull(sceneResponses, SceneResponse::location),
            LlmOutputParser.extractLastNonNull(sceneResponses, SceneResponse::outfit),
            LlmOutputParser.extractLastNonNull(sceneResponses, SceneResponse::time),
            aiOutput.statChanges(), aiOutput.bpm(), innerThought,
            aiOutput.isTopicConcluded(),
            aiOutput.eventStatus(),
            scenesJson,
            aiOutput.shouldGenerateIllustration(),
            aiOutput.newLocationName(),
            aiOutput.locationDescription()
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // [Bug #3 Fix] Room-level 시크릿 모드 판정
    private boolean resolveSecretMode(ChatRoom room) {
        return room.isSecretModeActive()
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
                                 boolean hasInnerThought, String assistantLogId,
                                 boolean generateIllustration,
                                 LocationTransition locationTransition) {
        try {
            SendChatResponse finalResponse = new SendChatResponse(
                response.roomId(), response.scenes(),
                response.currentAffection(), response.relationStatus(),
                response.promotionEvent(), response.endingTrigger(), response.easterEgg(),
                response.stats(), response.bpm(),
                response.dynamicRelationTag(), response.characterThought(),
                hasInnerThought, assistantLogId,
                response.topicConcluded(), response.eventStatus(),
                generateIllustration, locationTransition);

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

    /**
     * [Phase 5.5-Director] 비동기 후처리 — 디렉터 판단 통합
     *
     * 기존: 메모리 요약 + 캐릭터 생각
     * 추가: 디렉터 비동기 판단 (스토리 모드 전용)
     */
    private void triggerPostProcessing(Long roomId, Long userId, long totalLogCount,
                                       boolean isSecretMode, ChatMode chatMode) {
        long userMsgCount = chatLogRepository.countByRoomIdAndRole(roomId, ChatRole.USER);

        // ── 기존: 메모리 요약 ──
        long memoryCycle = ChatModePolicy.getMemorySummarizationCycle(chatMode);
        if (userMsgCount > 0 && userMsgCount % memoryCycle == 0) {
            memoryService.summarizeAndSaveMemory(roomId, userId);
        }

        // ── 기존: 캐릭터 생각 ──
        long thoughtCycle = ChatModePolicy.getCharacterThoughtCycle(chatMode);
        long thoughtOffset = ChatModePolicy.getCharacterThoughtOffset(chatMode);
        if (userMsgCount > 0 && userMsgCount % thoughtCycle == thoughtOffset) {
            chatService.generateCharacterThoughtAsync(roomId, userId, (int) userMsgCount, isSecretMode);
        }

        // ── [Phase 6 도그푸딩 #1] 자동 디렉터 인터루드 폐기 ──
        // 도그푸딩 결과: 자동 인터루드가 유저의 깊은 대화 흐름을 끊어 UX 저하.
        // 폴리시: 유저의 명시적 의지(StoryController#requestDirector "다음 씬" 버튼)만 유지.
        // 향후 부활 시 이 블록 + DirectorService.evaluateAndCache의 @Deprecated 표식 함께 정리.
    }

    /**
     * [Phase 5.5-Fix-IT] LLM 히스토리 구성 (이름표 부착 + 속마음 컨텍스트 주입)
     *
     * 최근 N개의 ASSISTANT 메시지에 속마음(inner_thought)을 포함하여
     /**
     * [Hallucination Fix] LLM 컨텍스트용 대화 히스토리 구성
     *
     * 환각 방지를 위한 핵심 원칙:
     *   1. USER 메시지: role="user"만으로 화자 식별 — 텍스트 태그([유저]:) 제거
     *   2. SYSTEM 나레이션: role="system"으로 전환 — 유저 발화와 구조적 분리
     *   3. ASSISTANT 메시지: 감정 메타데이터 제거 — 대사+나레이션만 유지
     *
     * [속마음 히스토리]
     * 최근 N개 ASSISTANT 메시지에만 이전 속마음을 포함하여
     * LLM이 이전 속마음을 인지하고 반복을 회피하도록 유도.
     */
    private List<OpenAiMessage> buildMessageHistory(Long roomId, CharacterPromptAssembler.SystemPromptPayload systemPrompt,
                                                    String characterName, String userNickname) {
        List<ChatLogDocument> history = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
        history.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));

        List<OpenAiMessage> messages = new ArrayList<>();

        if (history.size() == 3 || history.size() % 20 == 0) {
            messages.add(OpenAiMessage.systemCached(systemPrompt.staticRules(), Map.of("type", "ephemeral")));
        } else messages.add(OpenAiMessage.system(systemPrompt.staticRules()));

        // [Phase 5.5-Fix-IT] ASSISTANT 메시지 역순 카운팅 — 최근 N개 판별
        int totalAssistantCount = 0;
        for (ChatLogDocument log : history) {
            if (log.getRole() == ChatRole.ASSISTANT) totalAssistantCount++;
        }
        int assistantThreshold = totalAssistantCount - INNER_THOUGHT_HISTORY_WINDOW;

        // [Bug Fix] 인트로 환각 방지: 첫 ASSISTANT 앞에 USER가 없으면 합성 삽입
        boolean needsSyntheticUserMsg = false;
        if (!history.isEmpty()) {
            for (ChatLogDocument log : history) {
                if (log.getRole() == ChatRole.USER) { break; }
                if (log.getRole() == ChatRole.ASSISTANT) { needsSyntheticUserMsg = true; break; }
            }
        }

        int assistantIdx = 0;
        for (ChatLogDocument chatLog : history) {
            if (needsSyntheticUserMsg && chatLog.getRole() == ChatRole.ASSISTANT) {
                messages.add(OpenAiMessage.user("(입장)"));
                needsSyntheticUserMsg = false;
            }

            switch (chatLog.getRole()) {
                // [Fix] USER: role="user"가 화자 신호 — 텍스트 태그 불필요
                case USER -> messages.add(OpenAiMessage.user(chatLog.getRawContent()));

                // [Fix] ASSISTANT: 감정 메타데이터 제거, 대사+나레이션만 유지
                case ASSISTANT -> {
                    boolean includeThought = assistantIdx >= assistantThreshold;
                    String sanitized = LlmOutputParser.buildSanitizedAssistantContent(
                        objectMapper, chatLog, characterName, includeThought);
                    messages.add(OpenAiMessage.assistant(sanitized));
                    assistantIdx++;
                }

                // [Fix 핵심] SYSTEM 나레이션: role="system"으로 전환
                // 기존: role="user" + [NARRATION] 태그 → 유저 발화로 오귀속
                // 수정: role="system" → 구조적으로 제3자 서술임을 명시
                case SYSTEM -> messages.add(
                    OpenAiMessage.system("[NARRATION] " + chatLog.getRawContent())
                );
            }
        }

        messages.add(OpenAiMessage.system(systemPrompt.dynamicRules()));
        messages.add(OpenAiMessage.system(systemPrompt.outputFormat()));

        return messages;
    }

    // [Bug #6 Fix] buildSanitizedAssistantContent, extractJson, parseEmotion,
    // safeUpperCase, extractLastNonNull → LlmOutputParser로 통합 이관

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

    /**
     * [Phase 5.5-Director] Directive를 ChatRoom에 적용
     *
     * 프론트가 인터루드를 유저에게 보여준 뒤, 다음 액터 호출 전에 호출.
     * Directive의 constraint/narration을 ChatRoom에 세팅하여
     * CharacterPromptAssembler가 액터 프롬프트에 주입할 수 있게 한다.
     *
     * @param roomId    채팅방 ID
     * @param directive 소비된 Directive
     */
    public void applyDirectiveToRoom(Long roomId, DirectorDirective directive) {
        txTemplate.execute(status -> {
            ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("Room not found"));

            if (directive.checkInterlude() && directive.interlude() != null) {
                var interlude = directive.interlude();
                // [v3] INTERLUDE는 항상 일회성 constraint (투명 처리)
                room.setDirectorInterlude(
                    interlude.narration(),
                    interlude.actorConstraint());

                if (interlude.environment() != null) {
                    var env = interlude.environment();
                    room.updateSceneState(env.bgm(), null, null, env.time());
                }

            } else if (directive.checkTransition() && directive.transition() != null) {
                var transition = directive.transition();
                room.setDirectorInterlude(
                    transition.narration(),
                    transition.actorConstraint());
                room.updateSceneState(
                    transition.newBgm(), null, null, transition.newTime());

            } else if (directive.checkAway() && directive.away() != null) {
                var away = directive.away();
                // AWAY: 이벤트 ONGOING 모드로 진입 + constraint 설정
                room.startDirectorInterlude(
                    away.narration(),
                    away.actorConstraint());

                if (away.environment() != null) {
                    var env = away.environment();
                    room.updateSceneState(env.bgm(), null, null, env.time());
                }
            }

            return null;
        });
        cacheService.evictRoomInfo(roomId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [v3] 투명 디렉터 자동 응답 (INTERLUDE / TRANSITION / AWAY)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 디렉터 Directive가 소비되고 constraint가 ChatRoom에 적용된 상태에서
     * 캐릭터의 자동 응답을 SSE로 생성.
     *
     * INTERLUDE/TRANSITION: 원샷 응답 → constraint 자동 클리어
     * AWAY: 이벤트 ONGOING 진입 → 유저 개입 전까지 멀티턴
     */
    @Async
    public void sendAutoDirectorResponse(Long roomId, String directiveType, String eventContext, SseEmitter emitter) {
        log.info("🎬 [DIRECTOR-AUTO-RESPOND] START | type={} | context={} | roomId={}",
            directiveType, eventContext != null ? eventContext.length() + "chars" : "null", roomId);
        boolean isAway = "AWAY".equalsIgnoreCase(directiveType);
        boolean isBranchResponse = "BRANCH".equalsIgnoreCase(directiveType);

        try {
            // ── TX-1: 에너지 차감 ──
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                int cost = 1;
                room.getUser().consumeEnergy(cost);

                if (isAway) {
                    room.updateEventStatus("ONGOING");
                    room.updateTopicConcluded(false);
                }

                // [Bug Fix] BRANCH 카드 선택 시: constraint로 detail 적용
                if (isBranchResponse && eventContext != null && !eventContext.isBlank()) {
                    room.setDirectorInterlude(eventContext,
                        "상황: " + eventContext + " — 이 상황에 자연스럽게 반응하세요.");
                }

                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.isPromotionPending(), room.getUser().getUsername(), cost);
            });
            cacheService.evictUserProfile(jpa.username());

            // ── MongoDB: 숨겨진 시스템 메시지 저장 (LLM 컨텍스트용) ──
            String systemMessage;
            if (isAway) {
                systemMessage = "[SYSTEM_DIRECTOR] 유저가 자리를 비웠습니다. 캐릭터는 혼자(또는 NPC와) 행동합니다.";
            } else if (isBranchResponse && eventContext != null) {
                systemMessage = "[NARRATION] " + eventContext;
            } else {
                systemMessage = "[SYSTEM_DIRECTOR] 상황이 발생했습니다. 캐릭터는 자연스럽게 반응합니다.";
            }

            String savedLogId;
            try {
                // [Bug Fix A] BRANCH 나레이션은 visible로 저장 (새로고침 시 히스토리에 표시)
                // AWAY/INTERLUDE/TRANSITION은 hidden (LLM 컨텍스트 전용)
                ChatLogDocument savedLog;
                if (isBranchResponse && eventContext != null) {
                    savedLog = chatLogRepository.save(
                        ChatLogDocument.system(roomId, eventContext));
                } else {
                    savedLog = chatLogRepository.save(
                        ChatLogDocument.hiddenSystem(roomId, systemMessage));
                }
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

            // ── TX-2: 상태 업데이트 ──
            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> {
                    ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                    if (parsed.bpm() != null) freshRoom.updateBpm(parsed.bpm());
                    freshRoom.updateLastActive(parsed.mainEmotion());

                    if (isAway) {
                        // AWAY: 스탯 동결, 이벤트 상태 유지
                        freshRoom.updateEventStatus(
                            parsed.eventStatus() != null ? parsed.eventStatus() : "ONGOING");
                    } else {
                        // INTERLUDE/TRANSITION: 일반 스탯 적용 + constraint 클리어
                        applyStatChanges(freshRoom, parsed.statChanges(), effectiveSecretMode);
                        freshRoom.clearDirectorInterlude();
                    }

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
                        isAway ? false : parsed.topicConcluded(),
                        isAway ? freshRoom.getEventStatus() : null);
                });
            } catch (Exception e) {
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "자동 응답 처리 실패");
                return;
            }

            // ── 장소 전환 처리 ──
            LocationTransition locationTransition = null;
            if (parsed.newLocationName() != null && !parsed.newLocationName().isBlank()) {
                String currentDynamic = jpa.room().getCurrentDynamicLocationName();
                if (currentDynamic == null || !isSameLocation(currentDynamic, parsed.newLocationName())) {
                    String timeOfDay = parsed.lastTime() != null ? parsed.lastTime() : "DAY";
                    BackgroundGenerationService.BackgroundResult bgResult =
                        backgroundGenerationService.resolveBackground(
                            parsed.newLocationName(), parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId());

                    if (bgResult.isCacheHit()) {
                        locationTransition = LocationTransition.cached(parsed.newLocationName(), bgResult.imageUrl());
                    } else {
                        locationTransition = LocationTransition.generating(parsed.newLocationName(), bgResult.cacheHash());
                        backgroundGenerationService.generateBackgroundAsync(
                            parsed.newLocationName(), parsed.locationDescription(),
                            timeOfDay, jpa.room().getCharacter().getId());
                    }
                }
            }

            String assistantLogId = saveAssistantLog(roomId, parsed);
            cacheService.evictRoomInfo(roomId);

            sendFinalResult(emitter, response, false, assistantLogId, false, locationTransition);
            emitter.complete();

            log.info("🎬 [DIRECTOR-AUTO-RESPOND] DONE | type={} | roomId={}", directiveType, roomId);

        } catch (Exception e) {
            log.error("❌ Director auto-respond error | type={} | roomId={}", directiveType, roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "자동 응답 처리 중 오류 발생");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Bug Fix] 동적 장소 반복 전환 방지 — 유사도 가드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private boolean isSameLocation(String existing, String incoming) {
        if (existing == null || incoming == null) return false;
        String a = normalizeLocationName(existing);
        String b = normalizeLocationName(incoming);
        if (a.equals(b)) return true;
        if (a.contains(b) || b.contains(a)) return true;
        String coreA = extractCoreNoun(a);
        String coreB = extractCoreNoun(b);
        if (!coreA.isEmpty() && !coreB.isEmpty() && coreA.equals(coreB)) return true;
        return calculateBigramSimilarity(a, b) > 0.45;
    }

    private String normalizeLocationName(String name) {
        return name.replaceAll("[\\s·,.!?~'\"()（）]", "")
            .replace("의", "").replace("한", "").replace("인", "")
            .toLowerCase();
    }

    private String extractCoreNoun(String normalized) {
        String[] placeNouns = {
            "카페", "공원", "해변", "바다", "도서관", "학교", "교실", "옥상",
            "놀이공원", "영화관", "식당", "레스토랑", "편의점", "거리", "골목",
            "방", "침실", "거실", "부엌", "정원", "발코니", "테라스", "지하실",
            "사무실", "병원", "역", "미술관", "박물관", "체육관", "수영장",
            "온천", "신사", "절", "숲", "산", "강", "호수", "다리",
            "포장마차", "바", "클럽", "노래방", "서점", "꽃집",
            "시장", "백화점", "마트", "운동장", "광장", "동아리실", "강당"
        };
        for (String noun : placeNouns) {
            if (normalized.contains(noun)) return noun;
        }
        return normalized;
    }

    private double calculateBigramSimilarity(String a, String b) {
        if (a.length() < 2 || b.length() < 2) return 0.0;
        Set<String> bigramsA = new HashSet<>();
        Set<String> bigramsB = new HashSet<>();
        for (int i = 0; i < a.length() - 1; i++) bigramsA.add(a.substring(i, i + 2));
        for (int i = 0; i < b.length() - 1; i++) bigramsB.add(b.substring(i, i + 2));
        Set<String> intersection = new HashSet<>(bigramsA);
        intersection.retainAll(bigramsB);
        Set<String> union = new HashSet<>(bigramsA);
        union.addAll(bigramsB);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
}