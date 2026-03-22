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

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * [Phase 5.5-Perf] SSE Dual-Streaming 채팅 서비스
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  "백엔드에서 완벽하게 조립된 박스(JSON)만 검수해서 던져주는
 *   스마트 컨베이어 벨트"
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * [SSE 이벤트 시퀀스]
 *
 *   ┌─ 유저 전송 ─┐
 *   │             │
 *   │  TX-1       │  에너지 차감 + MongoDB 유저 메시지 저장
 *   │  (동기)     │
 *   │             │
 *   │  LLM 스트림  │  OpenRouter → 백엔드 (토큰 누적)
 *   │  ~1.5초 ──► │ ── SSE: first_scene ──►  프론트에서 즉시 캐릭터 대사 렌더링
 *   │             │                          유저는 글을 읽기 시작 (체감 로딩 종료)
 *   │  ~4초  ──►  │  전체 JSON 완성
 *   │             │
 *   │  TX-2       │  스탯/승급/엔딩/이스터에그 처리
 *   │  (동기)     │
 *   │             │
 *   │  MongoDB    │  ASSISTANT 메시지 + 속마음 저장
 *   │             │
 *   │  ───────►   │ ── SSE: final_result ──► 호감도/BPM/스탯 UI 업데이트
 *   │             │                          다음 씬 큐 준비
 *   │  비동기     │  메모리 요약 + 캐릭터 생각 트리거
 *   └─────────────┘
 *
 * [기존 sendMessage와의 공존]
 *   - ChatService.sendMessage()는 그대로 유지 (폴백/시스템 이벤트용)
 *   - 프론트엔드는 stream 엔드포인트를 우선 사용
 *   - 네트워크 문제로 SSE 실패 시 기존 REST 폴백 가능
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatStreamService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogMongoRepository chatLogRepository;
    private final CharacterPromptAssembler promptAssembler;
    private final OpenRouterStreamClient streamClient;
    private final OpenRouterClient openRouterClient;  // 폴백용
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
    private final ChatService chatService;   // 공유 헬퍼 (캐릭터 생각, 메모리 트리거 등)

    private static final long USER_TURN_MEMORY_CYCLE = 10;
    private static final long RAG_SKIP_LOG_THRESHOLD = USER_TURN_MEMORY_CYCLE * 2;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  TX 간 데이터 전달 DTO (ChatService와 동일)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record JpaPreResult(
        ChatRoom room, Long userId, long logCount,
        boolean wasPromotionPending, String username, int energyCost
    ) {}

    private record RollbackContext(
        Long userId, String username, int energyCost, String savedUserLogId
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  메인 스트리밍 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * SSE 기반 스트리밍 메시지 전송
     *
     * @Async로 실행 — 컨트롤러는 SseEmitter를 즉시 반환하고,
     * 실제 처리는 별도 스레드에서 비동기로 진행.
     */
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

            // ── TX-1: JPA (에너지 차감) ──
            long tx1Start = System.currentTimeMillis();
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
                int cost = boostModeResolver.resolveEnergyCost(room.getChatMode(), room.getUser());
                room.getUser().consumeEnergy(cost);
                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.isPromotionPending(), room.getUser().getUsername(), cost);
            });
            log.info("⏱ [STREAM-PERF] TX-1: {}ms", System.currentTimeMillis() - tx1Start);
            cacheService.evictUserProfile(jpa.username());

            // ── Prompt Injection Check ──
            PromptInjectionGuard.InjectionCheckResult injCheck =
                injectionGuard.checkChatMessage(userMessage, jpa.username());
            if (injCheck.detected()) {
                log.warn("⚠️ [INJECTION] Detected: user={}, severity={}", jpa.username(), injCheck.severity());
            }

            // ── MongoDB: USER 메시지 저장 ──
            String savedUserLogId;
            try {
                ChatLogDocument savedLog = chatLogRepository.save(
                    ChatLogDocument.user(jpa.room().getId(), userMessage));
                savedUserLogId = savedLog.getId();
            } catch (Exception e) {
                log.error("❌ [COMPENSATION] MongoDB USER save failed", e);
                compensateEnergy(jpa.userId(), jpa.energyCost(), jpa.username());
                sendSseError(emitter, "INTERNAL_ERROR", "메시지 저장에 실패했습니다.");
                return;
            }

            RollbackContext rollbackCtx = new RollbackContext(
                jpa.userId(), jpa.username(), jpa.energyCost(), savedUserLogId);

            // ── RAG 메모리 조회 (Redis/RDB) ──
            String longTermMemory = "";
            long logCountForRag = jpa.logCount() + 1;
            if (logCountForRag >= RAG_SKIP_LOG_THRESHOLD) {
                long ragStart = System.currentTimeMillis();
                try {
                    longTermMemory = memoryService.retrieveContext(roomId);
                } catch (Exception e) {
                    log.warn("⏱ [STREAM-PERF] RAG failed (non-blocking): {}", e.getMessage());
                }
                log.info("⏱ [STREAM-PERF] RAG: {}ms | found={}",
                    System.currentTimeMillis() - ragStart, !longTermMemory.isEmpty());
            } else {
                log.info("⏱ [STREAM-PERF] RAG SKIPPED (logCount={} < {})", logCountForRag, RAG_SKIP_LOG_THRESHOLD);
            }

            // ── 프롬프트 조립 ──
            boolean effectiveSecretMode = jpa.room().getUser().getIsSecretMode()
                && secretModeService.canAccessSecretMode(
                jpa.room().getUser(), jpa.room().getCharacter().getId());

            CharacterPromptAssembler.SystemPromptPayload systemPrompt =
                promptAssembler.assembleSystemPrompt(
                    jpa.room().getCharacter(), jpa.room(), jpa.room().getUser(),
                    longTermMemory, effectiveSecretMode);

            List<OpenAiMessage> messages = buildMessageHistory(jpa.room().getId(), systemPrompt);
            String model = boostModeResolver.resolveModel(jpa.room().getUser());

            Map<String, Object> providerRouting = Map.of(
                "order", List.of("Google AI Studio"),
                "allow_fallbacks", false
            );

            OpenAiChatRequest llmRequest = new OpenAiChatRequest(
                model, messages, 0.8, true, 0.3, 0.15, providerRouting);

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            //  🚀 Dual-Streaming LLM 호출
            //  첫 번째 씬 완성 → SSE first_scene 발사
            //  전체 완성 → 후처리 → SSE final_result 발사
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

            long llmStart = System.currentTimeMillis();
            log.info("⏱ [STREAM-PERF] LLM stream START | model={} | messages={}",
                model, messages.size());

            StreamResult streamResult;
            try {
                streamResult = streamClient.streamCompletion(llmRequest, firstSceneJson -> {
                    // ── 콜백: 첫 번째 씬 완성 시점 ──
                    try {
                        // 검증: 파싱 가능한지 확인
                        AiJsonOutput.Scene scene = objectMapper.readValue(firstSceneJson, AiJsonOutput.Scene.class);
                        EmotionTag emotion = parseEmotion(scene.emotion());

                        SceneResponse firstScene = new SceneResponse(
                            scene.narration(), scene.dialogue(), emotion,
                            safeUpperCase(scene.location()), safeUpperCase(scene.time()),
                            safeUpperCase(scene.outfit()), safeUpperCase(scene.bgmMode())
                        );

                        // SSE 발사: first_scene
                        String json = objectMapper.writeValueAsString(firstScene);
                        emitter.send(SseEmitter.event()
                            .name("first_scene")
                            .data(json));

                        log.info("🚀 [SSE] first_scene sent: emotion={} | dialogueLen={}",
                            emotion, scene.dialogue() != null ? scene.dialogue().length() : 0);

                    } catch (Exception e) {
                        log.warn("⚠️ [SSE] first_scene send failed: {}", e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.error("❌ [COMPENSATION] LLM stream failed | roomId={}", roomId, e);
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "LLM_ERROR", "AI 응답 생성에 실패했습니다.");
                return;
            }

            log.info("⏱ [STREAM-PERF] LLM stream DONE: {}ms | chars={}",
                System.currentTimeMillis() - llmStart, streamResult.fullResponse().length());

            // ── 전체 JSON 파싱 ──
            AiJsonOutput aiOutput;
            String cleanJson;
            try {
                cleanJson = stripMarkdown(streamResult.fullResponse());
                aiOutput = objectMapper.readValue(cleanJson, AiJsonOutput.class);
            } catch (JsonProcessingException e) {
                log.error("❌ JSON Parsing Error: {}", streamResult.fullResponse(), e);
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "PARSE_ERROR", "AI 응답 형식이 올바르지 않습니다.");
                return;
            }

            // ── 파싱 결과 정리 ──
            String combinedDialogue = aiOutput.scenes().stream()
                .map(AiJsonOutput.Scene::dialogue)
                .collect(Collectors.joining(" "));

            String lastEmotionStr = aiOutput.scenes().isEmpty() ? "NEUTRAL"
                : aiOutput.scenes().get(aiOutput.scenes().size() - 1).emotion();
            EmotionTag mainEmotion = parseEmotion(lastEmotionStr);

            List<SceneResponse> sceneResponses = aiOutput.scenes().stream()
                .map(s -> new SceneResponse(
                    s.narration(), s.dialogue(), parseEmotion(s.emotion()),
                    safeUpperCase(s.location()), safeUpperCase(s.time()),
                    safeUpperCase(s.outfit()), safeUpperCase(s.bgmMode())))
                .collect(Collectors.toList());

            String lastBgm = extractLastNonNull(sceneResponses, SceneResponse::bgmMode);
            String lastLoc = extractLastNonNull(sceneResponses, SceneResponse::location);
            String lastOutfit = extractLastNonNull(sceneResponses, SceneResponse::outfit);
            String lastTime = extractLastNonNull(sceneResponses, SceneResponse::time);

            AiJsonOutput.StatChanges statChanges = aiOutput.statChanges();
            Integer bpm = aiOutput.bpm();
            String innerThought = aiOutput.innerThought();
            if (innerThought != null && innerThought.isBlank()) innerThought = null;

            boolean isStory = jpa.room().isStoryMode();

            // ── TX-2: JPA (스탯/승급/엔딩/이스터에그) ──
            long tx2Start = System.currentTimeMillis();
            final String finalInnerThought = innerThought;

            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> {
                    ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                        .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                    PromotionEvent promoEvent = null;
                    if (isStory) {
                        promoEvent = resolveAffectionAndPromotion(freshRoom, aiOutput.affectionChange(),
                            aiOutput.moodScore(), jpa.wasPromotionPending());
                    } else {
                        freshRoom.applyLegacyAffectionChange(aiOutput.affectionChange());
                    }

                    freshRoom.updateLastActive(mainEmotion);
                    if (isStory) {
                        freshRoom.updateSceneState(lastBgm, lastLoc, lastOutfit, lastTime);
                    }

                    // 스탯 + BPM + 관계
                    applyStatChanges(freshRoom, statChanges, effectiveSecretMode);
                    if (bpm != null) freshRoom.updateBpm(bpm);
                    if (!freshRoom.isPromotionPending()) {
                        freshRoom.refreshRelationFromStats();
                    }

                    // 엔딩 트리거
                    EndingTrigger endingTrigger = null;
                    if (isStory) {
                        String endingCheck = freshRoom.checkEndingTrigger();
                        if (endingCheck != null) {
                            endingTrigger = new EndingTrigger(endingCheck);
                        }
                    }

                    // 이스터에그
                    EasterEggEvent easterEggEvent = null;
                    String eggTrigger = aiOutput.easterEggTrigger();
                    if (eggTrigger != null && !eggTrigger.isBlank()) {
                        try {
                            EasterEggType eggType = EasterEggType.valueOf(eggTrigger.toUpperCase());
                            var unlock = achievementService.unlockEasterEgg(jpa.userId(), eggType);
                            boolean revert = (eggType == EasterEggType.FOURTH_WALL);
                            easterEggEvent = new EasterEggEvent(eggType.name(),
                                new AchievementInfo(unlock.code(), unlock.title(), unlock.titleKo(),
                                    unlock.description(), unlock.icon(), unlock.isNew()), revert);
                        } catch (IllegalArgumentException ignored) {}
                    }

                    StatsSnapshot statsSnapshot = buildStatsSnapshot(freshRoom, effectiveSecretMode);

                    return new SendChatResponse(roomId, sceneResponses,
                        freshRoom.getAffectionScore(), freshRoom.getStatusLevel().name(),
                        promoEvent, endingTrigger, easterEggEvent,
                        statsSnapshot, freshRoom.getCurrentBpm(),
                        freshRoom.getDynamicRelationTag(), null);
                });
            } catch (Exception e) {
                log.error("❌ [COMPENSATION] TX-2 failed | roomId={}", roomId, e);
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "응답 처리 중 오류가 발생했습니다.");
                return;
            }
            log.info("⏱ [STREAM-PERF] TX-2: {}ms", System.currentTimeMillis() - tx2Start);

            // ── MongoDB: ASSISTANT 저장 (속마음 포함) ──
            String assistantLogId = null;
            boolean hasInnerThought = false;
            try {
                ChatLogDocument assistantLog = ChatLogDocument.assistantWithThought(
                    roomId, cleanJson, combinedDialogue, mainEmotion, null, finalInnerThought);
                ChatLogDocument saved = chatLogRepository.save(assistantLog);
                assistantLogId = saved.getId();
                hasInnerThought = saved.hasInnerThought();
            } catch (Exception e) {
                log.error("⚠️ [INCONSISTENCY] ASSISTANT log save failed | roomId={}", roomId, e);
            }
            cacheService.evictRoomInfo(roomId);

            // ── SSE 발사: final_result ──
            try {
                SendChatResponse finalResponse = new SendChatResponse(
                    response.roomId(), response.scenes(),
                    response.currentAffection(), response.relationStatus(),
                    response.promotionEvent(), response.endingTrigger(), response.easterEgg(),
                    response.stats(), response.bpm(),
                    response.dynamicRelationTag(), response.characterThought(),
                    hasInnerThought, assistantLogId);

                String finalJson = objectMapper.writeValueAsString(finalResponse);
                emitter.send(SseEmitter.event()
                    .name("final_result")
                    .data(finalJson));

                log.info("🚀 [SSE] final_result sent | roomId={} | scenes={}",
                    roomId, sceneResponses.size());
            } catch (Exception e) {
                log.warn("⚠️ [SSE] final_result send failed: {}", e.getMessage());
            }

            // ── SSE 완료 ──
            emitter.complete();

            log.info("⏱ [STREAM-PERF] ====== sendMessageStream DONE: {}ms ======",
                System.currentTimeMillis() - totalStart);

            // ── 비동기 후처리 (메모리/캐릭터 생각) ──
            triggerPostProcessing(roomId, jpa.userId(), jpa.logCount() + 1, effectiveSecretMode);

        } catch (Exception e) {
            log.error("❌ [STREAM] Unexpected error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "예기치 않은 오류가 발생했습니다.");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  SSE 유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void sendSseError(SseEmitter emitter, String errorCode, String message) {
        try {
            Map<String, String> error = Map.of("errorCode", errorCode, "message", message);
            emitter.send(SseEmitter.event()
                .name("error")
                .data(objectMapper.writeValueAsString(error)));
            emitter.complete();
        } catch (Exception e) {
            log.warn("Failed to send SSE error: {}", e.getMessage());
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  ChatService 로직 위임 헬퍼 (중복 최소화)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void triggerPostProcessing(Long roomId, Long userId, long totalLogCount, boolean isSecretMode) {
        // 메모리 요약
        long userMsgCount = chatLogRepository.countByRoomIdAndRole(roomId, ChatRole.USER);
        if (userMsgCount > 0 && userMsgCount % USER_TURN_MEMORY_CYCLE == 0) {
            log.info("🧠 [MEMORY] Summarization TRIGGERED | roomId={} | userMsgCount={}", roomId, userMsgCount);
            memoryService.summarizeAndSaveMemory(roomId, userId);
        }

        // 캐릭터 생각 (ChatService에 위임)
        long thoughtCycle = 10, thoughtOffset = 5;
        if (userMsgCount > 0 && userMsgCount % thoughtCycle == thoughtOffset) {
            chatService.generateCharacterThoughtAsync(roomId, userId, (int) userMsgCount, isSecretMode);
        }
    }

    /**
     * 메시지 히스토리 빌드 (ChatService.buildMessageHistory와 동일 로직)
     */
    private List<OpenAiMessage> buildMessageHistory(Long roomId,
                                                    CharacterPromptAssembler.SystemPromptPayload systemPrompt) {
        List<ChatLogDocument> history = chatLogRepository.findTop200ByRoomIdOrderByCreatedAtDesc(roomId);
        history.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));

        List<OpenAiMessage> messages = new ArrayList<>();

        if (history.size() == 3 || history.size() % 20 == 0) {
            messages.add(OpenAiMessage.systemCached(systemPrompt.staticRules(), Map.of("type", "ephemeral")));
        } else {
            messages.add(OpenAiMessage.system(systemPrompt.staticRules()));
        }

        for (ChatLogDocument chatLog : history) {
            switch (chatLog.getRole()) {
                case USER -> messages.add(OpenAiMessage.user(chatLog.getRawContent()));
                case ASSISTANT -> {
                    String sanitized = buildSanitizedAssistantContent(chatLog);
                    messages.add(OpenAiMessage.assistant(sanitized));
                }
                case SYSTEM -> messages.add(
                    OpenAiMessage.user("[NARRATION]\n" + chatLog.getRawContent()));
            }
        }

        messages.add(OpenAiMessage.system(systemPrompt.dynamicRules()));
        messages.add(OpenAiMessage.system(systemPrompt.outputFormat()));
        return messages;
    }

    private String buildSanitizedAssistantContent(ChatLogDocument chatLog) {
        try {
            String raw = chatLog.getRawContent();
            if (raw == null || raw.isBlank()) {
                return chatLog.getCleanContent() != null ? chatLog.getCleanContent() : "";
            }
            String cleaned = stripMarkdown(raw);
            AiJsonOutput parsed = objectMapper.readValue(cleaned, AiJsonOutput.class);
            StringBuilder sb = new StringBuilder();
            for (AiJsonOutput.Scene scene : parsed.scenes()) {
                if (scene.narration() != null && !scene.narration().isBlank())
                    sb.append("(").append(scene.narration().trim()).append(") ");
                if (scene.dialogue() != null && !scene.dialogue().isBlank())
                    sb.append(scene.dialogue().trim());
                if (scene.emotion() != null)
                    sb.append(" [").append(scene.emotion()).append("]");
                sb.append("\n");
            }
            String result = sb.toString().trim();
            return result.isEmpty()
                ? (chatLog.getCleanContent() != null ? chatLog.getCleanContent() : "")
                : result;
        } catch (Exception e) {
            return chatLog.getCleanContent() != null ? chatLog.getCleanContent() : chatLog.getRawContent();
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  스탯/승급/보상 헬퍼 (ChatService와 동일 로직)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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

    private PromotionEvent resolveAffectionAndPromotion(
        ChatRoom room, int affectionChange, Integer moodScore, boolean wasPending) {
        if (wasPending) {
            int resolved = (moodScore != null) ? moodScore : 1;
            room.advancePromotionTurn(resolved);
            if (room.getPromotionTurnCount() >= RelationStatusPolicy.PROMOTION_MAX_TURNS) {
                return resolvePromotionResult(room);
            }
            RelationStatus target = room.getPendingTargetStatus();
            return new PromotionEvent("IN_PROGRESS", target.name(),
                RelationStatusPolicy.getDisplayName(target),
                RelationStatusPolicy.PROMOTION_MAX_TURNS - room.getPromotionTurnCount(),
                room.getPromotionMoodScore(), null);
        } else {
            RelationStatus oldStatus = room.getStatusLevel();
            room.applyLegacyAffectionChange(affectionChange);
            RelationStatus newStatus = RelationStatusPolicy.fromScore(room.getAffectionScore());
            if (RelationStatusPolicy.isUpgrade(oldStatus, newStatus)) {
                int thresholdEdge = RelationStatusPolicy.getThresholdScore(newStatus) - 1;
                room.updateAffection(thresholdEdge);
                room.updateStatusLevel(oldStatus);
                room.startPromotion(newStatus);
                return new PromotionEvent("STARTED", newStatus.name(),
                    RelationStatusPolicy.getDisplayName(newStatus),
                    RelationStatusPolicy.PROMOTION_MAX_TURNS, 0, null);
            }
            return null;
        }
    }

    private PromotionEvent resolvePromotionResult(ChatRoom room) {
        int totalMood = room.getPromotionMoodScore();
        RelationStatus target = room.getPendingTargetStatus();
        boolean success = totalMood >= RelationStatusPolicy.PROMOTION_SUCCESS_THRESHOLD;
        if (success) {
            room.completePromotionSuccess();
            room.updateAffection(RelationStatusPolicy.getThresholdScore(target));
            List<UnlockInfo> unlocks = room.getCharacter().getUnlocksForRelation(target).stream()
                .map(u -> new UnlockInfo(u.type(), u.name(), u.displayName()))
                .collect(Collectors.toList());
            return new PromotionEvent("SUCCESS", target.name(),
                RelationStatusPolicy.getDisplayName(target), 0, totalMood, unlocks);
        } else {
            room.completePromotionFailure();
            int penalty = RelationStatusPolicy.PROMOTION_FAILURE_PENALTY;
            int penalized = Math.max(0, RelationStatusPolicy.getThresholdScore(target) - 1 - penalty);
            room.updateAffection(penalized);
            room.updateStatusLevel(RelationStatusPolicy.fromScore(penalized));
            return new PromotionEvent("FAILURE", target.name(),
                RelationStatusPolicy.getDisplayName(target), 0, totalMood, null);
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
            log.error("🔄 [COMPENSATION] Energy refund FAILED: userId={}", userId, ex);
        }
        cacheService.evictUserProfile(username);
    }

    private void compensateFullRollback(RollbackContext ctx) {
        if (ctx.savedUserLogId() != null) {
            try { chatLogRepository.deleteById(ctx.savedUserLogId()); }
            catch (Exception ex) { log.error("🔄 [COMPENSATION] User msg delete FAILED", ex); }
        }
        compensateEnergy(ctx.userId(), ctx.energyCost(), ctx.username());
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  텍스트 유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String stripMarkdown(String text) {
        if (text.startsWith("```json")) text = text.substring(7);
        else if (text.startsWith("```")) text = text.substring(3);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        return text.trim();
    }

    private EmotionTag parseEmotion(String s) {
        try { return EmotionTag.valueOf(s.toUpperCase()); }
        catch (Exception e) { return EmotionTag.NEUTRAL; }
    }

    private String safeUpperCase(String v) {
        if (v == null || v.isBlank() || "null".equalsIgnoreCase(v)) return null;
        return v.toUpperCase().trim();
    }

    private String extractLastNonNull(List<SceneResponse> scenes,
                                      java.util.function.Function<SceneResponse, String> ext) {
        for (int i = scenes.size() - 1; i >= 0; i--) {
            String val = ext.apply(scenes.get(i));
            if (val != null) return val;
        }
        return null;
    }
}