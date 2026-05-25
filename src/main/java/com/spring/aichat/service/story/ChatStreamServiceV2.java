package com.spring.aichat.service.story;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.BgmMode;
import com.spring.aichat.domain.enums.DayPart;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.heroine.ChatRoomHeroine;
import com.spring.aichat.domain.heroine.ChatRoomHeroineRepository;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.dto.chat.AiJsonOutputV2;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.dto.chat.SendChatResponse.SceneResponse;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.dto.story.StoryV2Requests.SendStoryV2MessageRequest;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.LlmCircuitBreaker;
import com.spring.aichat.external.OpenRouterStreamClient;
import com.spring.aichat.external.OpenRouterStreamClient.StreamResult;
import com.spring.aichat.external.LlmCircuitBreaker.TtftTimeoutException;
import com.spring.aichat.dto.chat.SendChatResponse.LocationTransition;
import com.spring.aichat.security.PromptInjectionGuard;
import com.spring.aichat.service.ContentModerationService;
import com.spring.aichat.service.util.LlmOutputParser;
import com.spring.aichat.service.MemoryService;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.illustration.BackgroundGenerationService;
import com.spring.aichat.service.payment.BoostModeResolver;
import com.spring.aichat.service.payment.SecretModeService;
import com.spring.aichat.service.prompt.StoryDirectorPromptAssemblerV2;
import com.spring.aichat.service.prompt.StoryDirectorPromptAssemblerV2.SystemPromptPayload;
import com.spring.aichat.service.stream.ChatLogPersister;
import com.spring.aichat.service.util.DialogueSanitizer;
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
 * [V2 Story] 메인 스트리밍 파이프라인 — 멀티 씬 (4~5) 지원
 *
 * <p>V1 {@link com.spring.aichat.service.ChatStreamService}와 *별개* 운영.
 * V1은 SANDBOX 모드 전용 (이번 V2 작업 후 STORY 분기 제거).
 * 본 서비스는 STORY V2 전용 — 디렉터 시점 + 멀티 히로인 + 위치 기반 라우팅 + 4~5 씬 응답.
 *
 * <p>[V1 패턴 충실 재현 + V2 차이점]
 * - SSE 흐름: V1과 동일 (first_scene → final_result). 단 first_scene만 즉시 발사,
 *   나머지 3~4 씬은 final_result에 일괄 담아 전송. 프론트는 V1처럼 순차 표시.
 * - JSON 스키마: V2는 {@code scenes} 4~5 원소 배열.
 * - 트랜잭션 분리: V1과 동일 TX-1 → LLM (no-TX) → TX-2 패턴.
 * - 보안 레이어: 동일 인프라 재활용.
 * - 차이: 캐릭터별 스탯 갱신, 위치 기반 라우팅, LLM 자율 발동 게이트,
 *        오프스크린 알림, 액션 메시지 인젝션, **멀티 씬 화자별 갱신**.
 *
 * <p>[멀티 씬 화자별 갱신 정책 — 사용자 합의]
 * <pre>
 *   last_emotion / last_illustration_hint:
 *     씬별 화자에게 각각 적용 (한 응답에서 여러 화자 갱신 가능).
 *   character_thought (속마음):
 *     마지막 씬의 inner_thought만 그 화자에게 (가장 최근 1개).
 *   markSpoken():
 *     그 응답에서 대사한 모든 화자에게 적용.
 *   응답 DTO currentSpeaker:
 *     마지막 씬의 speaker (UI "지금 누구 차례" 표시 기준).
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatStreamServiceV2 {

    // ── V1과 공유하는 인프라 ──
    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogMongoRepository chatLogRepository;
    private final ChatLogPersister chatLogPersister;
    private final TransactionTemplate txTemplate;
    private final OpenRouterStreamClient streamClient;
    private final LlmCircuitBreaker llmCircuitBreaker;
    private final ContentModerationService contentModerationService;
    private final PromptInjectionGuard injectionGuard;
    private final SecretModeService secretModeService;
    private final BoostModeResolver boostModeResolver;
    private final MemoryService memoryService;
    private final RedisCacheService cacheService;
    private final BackgroundGenerationService backgroundGenerationService;
    private final ObjectMapper objectMapper;

    // ── V2 신규 의존성 ──
    private final ChatRoomHeroineRepository heroineRepository;
    private final StoryDirectorPromptAssemblerV2 promptAssembler;
    private final WorldRoutingService routingService;
    private final HeroineMemoryService heroineMemoryService;
    private final OffscreenNotificationService notificationService;
    private final EndingEligibilityService endingService;
    private final RelationPromotionService promotionService;

    private static final int RAG_SKIP_THRESHOLD = 6;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  inner records
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record JpaPreResult(ChatRoom room, Long userId, long logCount,
                                String username, int energyCost) {}

    private record RollbackContext(Long userId, String username, int energyCost,
                                   String savedUserLogId) {}

    /**
     * V2 LLM 응답 파싱 결과.
     * @param sceneResponses  4~5 씬 (V1 SceneResponse 재활용, outfit/location/time/bgmMode는 null)
     * @param routedSpeakerId 라우팅이 결정한 *시작 화자* (참고용. 실제 응답은 LLM이 씬마다 화자 다를 수 있음)
     */
    private record ParsedV2Result(
        AiJsonOutputV2 aiOutput,
        String cleanJson,
        String combinedContent,            // 모든 씬의 narration+dialogue 합산 (ChatLog 저장용)
        EmotionTag lastEmotion,            // 마지막 씬의 emotion (응답 DTO용)
        List<SceneResponse> sceneResponses,
        String scenesJson,
        Long routedSpeakerId
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  메인 엔트리포인트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void sendMessageStream(Long roomId, SendStoryV2MessageRequest request, SseEmitter emitter) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱ [V2-STREAM] ====== START ====== roomId={}, actionType={}",
            roomId, request.actionType());

        String userMessage = request.message() != null ? request.message() : "";
        String actionType = request.actionType();

        try {
            // ── 1. Content Moderation ──
            ChatRoom roomForCheck = chatRoomRepository.findWithMemberAndWorldById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
            if (!roomForCheck.isStoryMode()) {
                sendSseError(emitter, "INVALID_MODE", "V2 STORY 전용 엔드포인트입니다.");
                return;
            }
            boolean isSecretCheck = roomForCheck.isSecretModeActive()
                && roomForCheck.getWorld().isSecretAllowed()
                && secretModeService.canAccessSecretMode(roomForCheck.getUser());

            if (!userMessage.isBlank()) {
                ContentModerationService.ModerationVerdict verdict =
                    contentModerationService.moderate(userMessage, isSecretCheck);
                if (!verdict.passed()) {
                    sendSseError(emitter, "CONTENT_BLOCKED", verdict.userMessage());
                    return;
                }
            }

            // ── 2. TX-1 ──
            JpaPreResult jpa = txTemplate.execute(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndWorldById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
                int cost = boostModeResolver.resolveEnergyCost(room.getChatMode(), room.getUser());
                room.getUser().consumeEnergy(cost);
                long logCount = chatLogRepository.countByRoomId(roomId);
                return new JpaPreResult(room, room.getUser().getId(), logCount,
                    room.getUser().getUsername(), cost);
            });
            cacheService.evictUserProfile(jpa.username());

            // ── 3. Prompt Injection Check ──
            if (!userMessage.isBlank()) {
                PromptInjectionGuard.InjectionCheckResult injCheck =
                    injectionGuard.checkChatMessage(userMessage, jpa.username());
                if (injCheck.detected()) {
                    log.warn("⚠️ [V2-INJECTION] Detected: user={}", jpa.username());
                }
            }

            // ── 4. 액션 메시지 사전 처리 (MOVE 즉시 반영 + system 메시지 인젝션 결정) ──
            String systemActionInjection = buildSystemActionInjection(jpa.room(), actionType, request.actionPayload());

            // ── 5. MongoDB USER 메시지 저장 ──
            String savedUserLogId;
            try {
                ChatLogDocument savedLog = chatLogRepository.save(
                    buildUserLog(roomId, userMessage, actionType, request.actionPayload()));
                savedUserLogId = savedLog.getId();
            } catch (Exception e) {
                compensateEnergy(jpa.userId(), jpa.energyCost(), jpa.username());
                sendSseError(emitter, "INTERNAL_ERROR", "메시지 저장에 실패했습니다.");
                return;
            }

            RollbackContext rollbackCtx = new RollbackContext(
                jpa.userId(), jpa.username(), jpa.energyCost(), savedUserLogId);

            // ── 6. V2 라우팅 — 시작 화자 결정 ──
            WorldRoutingService.RoutingResult routing = routingService.route(jpa.room(), userMessage);
            log.info("🎯 [V2-ROUTING] roomId={}, routedSpeakerId={}, ambient={}",
                roomId, routing.currentSpeakerId(), routing.isAmbient());

            // ── 7. LLM 호출 + 파싱 ──
            boolean effectiveSecretMode = resolveSecretMode(jpa.room());
            ParsedV2Result parsed = streamLlmAndParseV2(
                jpa.room(), routing.currentSpeakerId(), userMessage,
                systemActionInjection, jpa.logCount() + 1,
                effectiveSecretMode, emitter, rollbackCtx);
            if (parsed == null) return;

            // ── 8. TX-2 ──
            SendChatResponse response;
            try {
                response = txTemplate.execute(status -> processV2Updates(
                    roomId, parsed, effectiveSecretMode));
            } catch (Exception e) {
                log.error("❌ [V2-TX-2] failed | roomId={}", roomId, e);
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "TX_ERROR", "응답 처리 중 오류가 발생했습니다.");
                return;
            }

            // ── 9. MongoDB ASSISTANT 저장 ──
            String assistantLogId = persistAssistantLog(roomId, parsed);
            cacheService.evictRoomInfo(roomId);

            // ── 10. 오프스크린 알림 처리 ──
            processOffscreenNotifications(jpa.room(), parsed);

            // ── 11. 동적 배경 처리 (마지막 씬 기준) ──
            LocationTransition locationTransition =
                processDynamicBackground(jpa.room(), parsed);

            // ── 12. SSE final_result (모든 씬 포함) ──
            AiJsonOutputV2.SceneV2 lastSceneAi = parsed.aiOutput().lastScene();
            boolean hasInnerThought = lastSceneAi != null && lastSceneAi.hasInnerThought();
            sendFinalResult(emitter, response, hasInnerThought, assistantLogId, locationTransition);
            emitter.complete();

            log.info("⏱ [V2-STREAM] DONE: {}ms | sceneCount={}",
                System.currentTimeMillis() - totalStart, parsed.aiOutput().sceneCount());

            // ── 13. Post-processing (메모리 압축 async) ──
            triggerPostProcessing(roomId, jpa.userId(), jpa.logCount() + 1, parsed.aiOutput());

        } catch (Exception e) {
            log.error("❌ [V2-STREAM] Unexpected error | roomId={}", roomId, e);
            sendSseError(emitter, "UNEXPECTED_ERROR", "예기치 않은 오류가 발생했습니다.");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  액션 메시지 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String buildSystemActionInjection(ChatRoom room, String actionType,
                                              com.spring.aichat.dto.story.StoryV2Requests.ActionPayload payload) {
        if (actionType == null) return null;

        return switch (actionType.toUpperCase()) {
            case "MOVE" -> {
                if (payload == null || payload.toLocationKey() == null) {
                    log.warn("⚠️ [V2-ACTION] MOVE without toLocationKey, ignored");
                    yield null;
                }
                String to = payload.toLocationKey();
                txTemplate.execute(status -> {
                    ChatRoom fresh = chatRoomRepository.findById(room.getId()).orElseThrow();
                    fresh.updateUserLocation(to);
                    return null;
                });
                log.info("📍 [V2-ACTION] MOVE: roomId={}, → {}", room.getId(), to);
                yield "[USER_ACTION] 유저가 " + to + " 장소로 이동했다. 도착 묘사 + 그곳의 캐릭터(있다면) 반응으로 응답하라.";
            }
            case "TIME_ADVANCE" -> "[USER_ACTION] 유저가 시간을 흘러가게 하고 싶어한다. 대화 맥락에 자연스러운 만큼 시간을 진전시킨 후 새 씬으로 응답하라.";
            case "NEXT_SCENE" -> "[USER_ACTION] 흐름을 진행시켜달라. 자율적으로 시간이 흐르거나 캐릭터의 행동/사건이 발생하도록 새 씬을 연출하라.";
            default -> null;
        };
    }

    private ChatLogDocument buildUserLog(Long roomId, String userMessage, String actionType,
                                         com.spring.aichat.dto.story.StoryV2Requests.ActionPayload payload) {
        if (actionType == null && !userMessage.isBlank()) {
            return ChatLogDocument.user(roomId, userMessage);
        }
        String sysContent = "[ACTION:" + actionType + "]"
            + (payload != null && payload.toLocationKey() != null ? " to=" + payload.toLocationKey() : "")
            + (!userMessage.isBlank() ? " | message=" + userMessage : "");
        return ChatLogDocument.hiddenSystem(roomId, sysContent);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  LLM 호출 + 파싱 (멀티 씬 처리)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ParsedV2Result streamLlmAndParseV2(ChatRoom room, Long routedSpeakerId, String userMessage,
                                               String systemActionInjection, long logCountForRag,
                                               boolean effectiveSecretMode,
                                               SseEmitter emitter, RollbackContext rollbackCtx) {
        // RAG: World-level memory (기존 MemoryService 재활용)
        String worldMemory = "";
        if (logCountForRag >= RAG_SKIP_THRESHOLD) {
            try {
                worldMemory = memoryService.retrieveContext(room.getId());
            } catch (Exception e) {
                log.warn("[V2-RAG] world memory failed (non-blocking): {}", e.getMessage());
            }
        }

        // 시스템 프롬프트 빌딩
        SystemPromptPayload systemPrompt = promptAssembler.assemble(
            room, room.getUser(), routedSpeakerId, worldMemory, effectiveSecretMode);

        List<OpenAiMessage> messages = buildMessageHistoryV2(
            room.getId(), systemPrompt, systemActionInjection);

        String model = boostModeResolver.resolveModel(room.getUser());
        LlmCircuitBreaker.ProviderDecision decision = llmCircuitBreaker.decide();
        log.info("🔌 [V2-CIRCUIT] provider={}, deadline={}ms, roomId={}",
            decision.provider(), decision.ttftDeadlineMs(), room.getId());

        Set<String> sanitizerSpeakers = collectSanitizerSpeakers(room);

        // first_scene 콜백 — V1 패턴 (배열의 첫 객체)
        Consumer<String> onFirstScene = firstSceneJson -> {
            try {
                AiJsonOutputV2.SceneV2 scene = objectMapper.readValue(firstSceneJson, AiJsonOutputV2.SceneV2.class);
                EmotionTag emotion = LlmOutputParser.parseEmotion(scene.emotion());
                String sanitizedDialogue = DialogueSanitizer.stripSpeakerPrefix(scene.dialogue(), sanitizerSpeakers);
                String sanitizedNarration = DialogueSanitizer.stripSpeakerPrefix(scene.narration(), sanitizerSpeakers);
                SceneResponse firstScene = new SceneResponse(
                    scene.speaker(),
                    sanitizedNarration, sanitizedDialogue, emotion,
                    null, null, null, null);
                emitter.send(SseEmitter.event().name("first_scene")
                    .data(objectMapper.writeValueAsString(firstScene)));
            } catch (Exception e) {
                log.warn("[V2-SSE] first_scene send failed: {}", e.getMessage());
            }
        };

        // event_meta 콜백은 V2에서 미사용 (event_status 필드 폐기)
        Consumer<String> onEventStatus = ignored -> { /* no-op for V2 */ };

        // LLM 스트림 + Circuit Breaker (V1 패턴 충실 재현)
        StreamResult streamResult;
        try {
            Map<String, Object> providerRouting = Map.of(
                "order", List.of(decision.provider()),
                "allow_fallbacks", false
            );
            OpenAiChatRequest req = new OpenAiChatRequest(
                model, messages, 0.8, true, 0.3, 0.15, providerRouting,
                Map.of("type", "json_object"));
            streamResult = streamClient.streamCompletion(
                req, onFirstScene, onEventStatus, decision.ttftDeadlineMs());
            if (decision.isPrimary()) {
                llmCircuitBreaker.recordSuccess(streamResult.ttft());
            }
        } catch (TtftTimeoutException ttftEx) {
            llmCircuitBreaker.recordFailure(ttftEx.getDeadlineMs());
            log.warn("🔄 [V2-CIRCUIT] TTFT timeout → Vertex fallback | deadline={}ms",
                ttftEx.getDeadlineMs());
            try {
                Map<String, Object> fb = Map.of(
                    "order", List.of(LlmCircuitBreaker.PROVIDER_VERTEX),
                    "allow_fallbacks", false);
                OpenAiChatRequest fbReq = new OpenAiChatRequest(
                    model, messages, 0.8, true, 0.3, 0.15, fb,
                    Map.of("type", "json_object"));
                streamResult = streamClient.streamCompletion(fbReq, onFirstScene, onEventStatus, 0);
                log.info("✅ [V2-CIRCUIT] Vertex fallback OK | TTFT={}ms", streamResult.ttft());
            } catch (Exception fbEx) {
                log.error("❌ [V2-CIRCUIT] Vertex fallback failed | roomId={}", room.getId(), fbEx);
                compensateFullRollback(rollbackCtx);
                sendSseError(emitter, "LLM_ERROR", "AI 응답 생성 실패 (폴백 포함)");
                return null;
            }
        } catch (Exception e) {
            if (decision.isPrimary()) llmCircuitBreaker.recordFailure(-1);
            log.error("[V2-LLM] stream failed | roomId={}", room.getId(), e);
            compensateFullRollback(rollbackCtx);
            sendSseError(emitter, "LLM_ERROR", "AI 응답 생성 실패");
            return null;
        }

        // JSON 파싱
        AiJsonOutputV2 aiOutput;
        String cleanJson;
        try {
            cleanJson = LlmOutputParser.extractJson(streamResult.fullResponse());
            aiOutput = objectMapper.readValue(cleanJson, AiJsonOutputV2.class);
        } catch (JsonProcessingException e) {
            log.error("[V2-PARSE] failed: {}", streamResult.fullResponse(), e);
            compensateFullRollback(rollbackCtx);
            sendSseError(emitter, "PARSE_ERROR", "AI 응답 형식 오류");
            return null;
        }

        if (aiOutput.scenes() == null || aiOutput.scenes().isEmpty()) {
            log.error("[V2-PARSE] empty scenes | roomId={}", room.getId());
            compensateFullRollback(rollbackCtx);
            sendSseError(emitter, "PARSE_ERROR", "AI 응답에 씬이 없습니다.");
            return null;
        }

        // 모든 씬 변환 + sanitize
        List<SceneResponse> sceneResponses = aiOutput.scenes().stream()
            .map(s -> new SceneResponse(
                s.speaker(),
                DialogueSanitizer.stripSpeakerPrefix(s.narration(), sanitizerSpeakers),
                DialogueSanitizer.stripSpeakerPrefix(s.dialogue(), sanitizerSpeakers),
                LlmOutputParser.parseEmotion(s.emotion()),
                null, null, null, null))
            .collect(Collectors.toList());

        // 마지막 씬 emotion (응답 DTO 대표 emotion)
        EmotionTag lastEmotion = sceneResponses.get(sceneResponses.size() - 1).emotion();

        // 전체 narration + dialogue 합산 (ChatLog 저장용 fallback)
        String combinedContent = sceneResponses.stream()
            .map(sr -> {
                StringBuilder b = new StringBuilder();
                if (sr.narration() != null && !sr.narration().isBlank()) b.append(sr.narration());
                if (sr.dialogue() != null && !sr.dialogue().isBlank()) {
                    if (b.length() > 0) b.append("\n");
                    b.append(sr.dialogue());
                }
                return b.toString();
            })
            .filter(s -> !s.isBlank())
            .collect(Collectors.joining("\n\n"));

        String scenesJson = buildScenesJson(sceneResponses);

        log.info("🎬 [V2-PARSE] sceneCount={}, lastSpeaker={}",
            sceneResponses.size(),
            sceneResponses.get(sceneResponses.size() - 1).speaker());

        return new ParsedV2Result(aiOutput, cleanJson, combinedContent,
            lastEmotion, sceneResponses, scenesJson, routedSpeakerId);
    }

    private Set<String> collectSanitizerSpeakers(ChatRoom room) {
        Set<String> speakers = new LinkedHashSet<>();
        List<ChatRoomHeroine> heroines = heroineRepository.findByChatRoom_Id(room.getId());
        for (ChatRoomHeroine h : heroines) {
            String name = h.getCharacter().getName();
            if (name != null && !name.isBlank()) speakers.add(name.trim());
        }
        if (room.getUser() != null && room.getUser().getNickname() != null) {
            speakers.add(room.getUser().getNickname().trim());
        }
        return speakers;
    }

    private List<OpenAiMessage> buildMessageHistoryV2(Long roomId, SystemPromptPayload sysPrompt,
                                                      String actionInjection) {
        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.systemCached(sysPrompt.staticPart(),
            Map.of("type", "ephemeral")));
        messages.add(OpenAiMessage.system(sysPrompt.dynamicPart()));

        List<ChatLogDocument> history = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
        Collections.reverse(history);
        for (ChatLogDocument log : history) {
            if (log.isHidden() && !log.getRawContent().startsWith("[ACTION:")) {
                continue;
            }
            String role = mapChatLogRoleToOpenAi(log);
            String content = log.isHidden() ? log.getRawContent() : log.getCleanContent();
            if (content == null || content.isBlank()) continue;
            messages.add(new OpenAiMessage(role, content, null));
        }

        if (actionInjection != null) {
            messages.add(OpenAiMessage.system(actionInjection));
        }
        return messages;
    }

    private String mapChatLogRoleToOpenAi(ChatLogDocument log) {
        return switch (log.getRole()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
        };
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  TX-2 처리 — 멀티 씬 화자별 갱신
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private SendChatResponse processV2Updates(Long roomId, ParsedV2Result parsed,
                                              boolean effectiveSecretMode) {
        ChatRoom freshRoom = chatRoomRepository.findWithMemberAndWorldById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
        AiJsonOutputV2.SystemUpdates sysUpdates = parsed.aiOutput().systemUpdates();
        AiJsonOutputV2.SceneV2 lastScene = parsed.aiOutput().lastScene();

        // (a) topic_concluded
        if (sysUpdates != null && sysUpdates.topicConcluded() != null) {
            freshRoom.updateTopicConcluded(sysUpdates.isTopicConcluded());
        }
        // (b) BGM
        if (sysUpdates != null && sysUpdates.bgmMode() != null) {
            try {
                freshRoom.updateBgmMode(BgmMode.valueOf(sysUpdates.bgmMode()));
            } catch (Exception ignored) {}
        }
        // (c) 시간 진전 — character routine 재추정
        if (sysUpdates != null && sysUpdates.hasTimeAdvance()) {
            DayPart newDayPart = parseDayPart(sysUpdates.timeAdvance().dayPart());
            int days = sysUpdates.timeAdvance().days() != null ? sysUpdates.timeAdvance().days() : 0;
            freshRoom.advanceTime(days, newDayPart);
            if (newDayPart != null) {
                routingService.recomputePresencesFromRoutine(freshRoom, newDayPart);
            }
        }
        // (d) 유저 위치 변경 — *어느 씬에서든* location_change 있으면 가장 마지막 것 적용
        applyLatestUserLocationChange(freshRoom, parsed.aiOutput());

        // (e) 캐릭터 위치 변경 (character_movements — 응답 전체 단위)
        if (sysUpdates != null && sysUpdates.hasCharacterMovements()) {
            List<WorldRoutingService.Movement> movements = sysUpdates.characterMovements().stream()
                .map(m -> new WorldRoutingService.Movement(m.characterId(), m.locationKey()))
                .toList();
            routingService.applyCharacterMovements(freshRoom, movements);
        }
        // (f) 캐릭터별 스탯 갱신 (응답 전체 단위)
        applyHeroineStatChanges(freshRoom, sysUpdates, effectiveSecretMode);

        // (g) 멀티 씬 화자별 갱신
        applyMultiSceneSpeakerUpdates(freshRoom, parsed.aiOutput());

        // (h) 엔딩/관계 승급 LLM trigger 처리
        if (sysUpdates != null) {
            if (sysUpdates.isEndingTriggered()) {
                endingService.processDirectorTrigger(freshRoom, true, sysUpdates.endingType());
            }
            if (sysUpdates.relationTransition() != null) {
                AiJsonOutputV2.RelationTransition rt = sysUpdates.relationTransition();
                promotionService.processDirectorTrigger(freshRoom,
                    new RelationPromotionService.RelationTransition(rt.characterId(), rt.from(), rt.to()));
            }
        }
        // (i) 스탯 갱신 후 자격 활성 체크
        endingService.checkAndActivateEligibility(freshRoom);
        promotionService.checkAndActivateEligibility(freshRoom);
        // (j) 매 턴 deferred 카운터 ++ (활성 자격 있다면)
        promotionService.incrementDeferredCounters(roomId);
        // (k) lastActiveAt
        freshRoom.touch();

        // 응답 빌딩 — *마지막 씬의 speaker*를 currentSpeaker로
        Long lastSpeakerId = resolveSpeakerIdByName(freshRoom, lastScene);
        return buildSendChatResponseV2(freshRoom, parsed, lastSpeakerId, effectiveSecretMode);
    }

    /**
     * 모든 씬을 역순 탐색 — 가장 마지막의 location_change가 유저 위치 갱신값.
     * 같은 응답에서 여러 씬이 location_change를 가지는 경우는 드물지만 안전을 위해.
     */
    private void applyLatestUserLocationChange(ChatRoom room, AiJsonOutputV2 ai) {
        if (ai.scenes() == null) return;
        for (int i = ai.scenes().size() - 1; i >= 0; i--) {
            AiJsonOutputV2.SceneV2 s = ai.scenes().get(i);
            if (s.hasLocationChange()) {
                room.updateUserLocation(s.locationChange());
                log.debug("📍 [V2-LOC] User location updated: roomId={}, → {}",
                    room.getId(), s.locationChange());
                return;
            }
        }
    }

    private void applyHeroineStatChanges(ChatRoom room, AiJsonOutputV2.SystemUpdates sysUpdates,
                                         boolean effectiveSecretMode) {
        if (sysUpdates == null || sysUpdates.statChanges() == null) return;
        for (Map.Entry<String, AiJsonOutput.StatChanges> entry : sysUpdates.statChanges().entrySet()) {
            Long charId;
            try { charId = Long.parseLong(entry.getKey()); }
            catch (NumberFormatException e) { continue; }
            ChatRoomHeroine h = heroineRepository
                .findByChatRoom_IdAndCharacter_Id(room.getId(), charId).orElse(null);
            if (h == null) continue;
            AiJsonOutput.StatChanges sc = entry.getValue();
            h.applyNormalStatChanges(sc.safeIntimacy(), sc.safeAffection(),
                sc.safeDependency(), sc.safePlayfulness(), sc.safeTrust());
            if (effectiveSecretMode) {
                h.applySecretStatChanges(sc.safeLust(), sc.safeCorruption(), sc.safeObsession());
            }
        }
    }

    /**
     * 멀티 씬 화자별 갱신 — 사용자 합의 정책 적용:
     * <ul>
     *   <li>last_emotion / last_illustration_hint: 씬별 화자에게 각각 적용</li>
     *   <li>character_thought: 마지막 씬의 inner_thought만 그 화자에게</li>
     *   <li>markSpoken: 대사한 모든 화자에게</li>
     * </ul>
     */
    private void applyMultiSceneSpeakerUpdates(ChatRoom room, AiJsonOutputV2 ai) {
        if (ai.scenes() == null || ai.scenes().isEmpty()) return;

        // 응답 전체에서 등장한 화자 캐싱 (이름 → ChatRoomHeroine)
        Map<String, ChatRoomHeroine> heroineByName = heroineRepository.findByChatRoom_Id(room.getId()).stream()
            .collect(Collectors.toMap(h -> h.getCharacter().getName(), h -> h, (a, b) -> a));

        // 1. 씬별 화자에게 last_emotion / last_illustration_hint 적용 + markSpoken
        for (AiJsonOutputV2.SceneV2 scene : ai.scenes()) {
            if (scene.speaker() == null || scene.speaker().isBlank()) continue;
            ChatRoomHeroine speaker = heroineByName.get(scene.speaker());
            if (speaker == null) continue;

            EmotionTag emotion = LlmOutputParser.parseEmotion(scene.emotion());
            if (emotion != null) speaker.updateLastEmotion(emotion);
            if (scene.hasIllustrationSceneHint()) {
                speaker.updateLastIllustrationHint(scene.illustrationSceneHint());
            }
            speaker.markSpoken();
        }

        // 2. 마지막 씬의 inner_thought만 그 화자에게
        AiJsonOutputV2.SceneV2 lastScene = ai.lastScene();
        if (lastScene != null && lastScene.hasInnerThought()
            && lastScene.speaker() != null && !lastScene.speaker().isBlank()) {
            ChatRoomHeroine speaker = heroineByName.get(lastScene.speaker());
            if (speaker != null) {
                int currentTurnCount = 0;  // V2에서 thoughtUpdatedAtTurn은 해금 만료 정책 약함
                speaker.updateCharacterThought(lastScene.innerThought(), currentTurnCount);
            }
        }
    }

    /**
     * 마지막 씬의 speaker 이름 → 캐릭터 ID 매핑.
     * V2 응답 DTO {@code currentSpeaker} 값으로 사용. 화자가 null이면 null 반환 (AMBIENT).
     */
    private Long resolveSpeakerIdByName(ChatRoom room, AiJsonOutputV2.SceneV2 lastScene) {
        if (lastScene == null || lastScene.speaker() == null || lastScene.speaker().isBlank()) {
            return null;
        }
        return heroineRepository.findByChatRoom_Id(room.getId()).stream()
            .filter(h -> h.getCharacter().getName().equals(lastScene.speaker()))
            .map(h -> h.getCharacter().getId())
            .findFirst()
            .orElse(null);
    }

    private SendChatResponse buildSendChatResponseV2(ChatRoom room, ParsedV2Result parsed,
                                                     Long lastSpeakerId, boolean effectiveSecretMode) {
        // V1 SendChatResponse 구조 최대한 재활용. V2 캐릭터별 스탯은 *마지막 화자*의 것을 담음.
        Integer affection = null;
        String relationStatus = null;
        Integer bpm = null;
        String dynamicRelationTag = null;
        SendChatResponse.StatsSnapshot statsSnapshot = null;

        if (lastSpeakerId != null) {
            ChatRoomHeroine speaker = heroineRepository
                .findByChatRoom_IdAndCharacter_Id(room.getId(), lastSpeakerId).orElse(null);
            if (speaker != null) {
                affection = speaker.getStatAffection();
                relationStatus = speaker.getStatusLevel().name();
                bpm = speaker.getCurrentBpm();
                dynamicRelationTag = speaker.getDynamicRelationTag();
                statsSnapshot = new SendChatResponse.StatsSnapshot(
                    speaker.getStatIntimacy(), speaker.getStatAffection(),
                    speaker.getStatDependency(), speaker.getStatPlayfulness(), speaker.getStatTrust(),
                    effectiveSecretMode ? speaker.getStatLust() : null,
                    effectiveSecretMode ? speaker.getStatCorruption() : null,
                    effectiveSecretMode ? speaker.getStatObsession() : null);
            }
        }

        return new SendChatResponse(
            room.getId(), parsed.sceneResponses(),  // 모든 씬 (4~5개)
            affection, relationStatus,
            null, null, null,  // promotionEvent/endingTrigger/easterEgg — V2는 별도 채널
            statsSnapshot, bpm, dynamicRelationTag,
            null,  // characterThought는 hasInnerThought 플래그로 분리 전달
            false, null,
            room.isTopicConcluded(),
            null);  // eventStatus — V2 폐기
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  알림 / 동적 배경
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void processOffscreenNotifications(ChatRoom room, ParsedV2Result parsed) {
        AiJsonOutputV2 ai = parsed.aiOutput();
        if (ai.hasIncomingMessages()) {
            List<OffscreenNotificationService.IncomingMessage> msgs = ai.incomingMessages().stream()
                .map(m -> new OffscreenNotificationService.IncomingMessage(m.fromCharacterId(), m.content()))
                .toList();
            notificationService.processDirectorOutput(room, msgs);
        }
        // *대사한 모든 화자*가 미응답 알림 발신자였다면 응답 마킹
        Set<Long> speakerIds = collectSpokeSpeakerIds(room, ai);
        for (Long charId : speakerIds) {
            notificationService.markRespondedByCharacter(room.getId(), charId);
        }
    }

    /** 응답의 모든 씬에서 대사한 캐릭터 ID 수집 (중복 제거). */
    private Set<Long> collectSpokeSpeakerIds(ChatRoom room, AiJsonOutputV2 ai) {
        if (ai.scenes() == null) return Set.of();
        Map<String, Long> idByName = heroineRepository.findByChatRoom_Id(room.getId()).stream()
            .collect(Collectors.toMap(h -> h.getCharacter().getName(), h -> h.getCharacter().getId(), (a, b) -> a));
        Set<Long> ids = new LinkedHashSet<>();
        for (AiJsonOutputV2.SceneV2 s : ai.scenes()) {
            if (s.speaker() == null || s.speaker().isBlank()) continue;
            Long id = idByName.get(s.speaker());
            if (id != null) ids.add(id);
        }
        return ids;
    }

    /**
     * 동적 배경은 *마지막 씬의 new_dynamic_location* 기준.
     * 같은 응답에서 여러 씬이 location을 바꾸는 경우는 드물지만, 최종 위치가 가장 늦은 씬에 있다고 본다.
     */
    private LocationTransition processDynamicBackground(ChatRoom room,
                                                                                   ParsedV2Result parsed) {
        AiJsonOutputV2.SceneV2 lastWithLoc = null;
        if (parsed.aiOutput().scenes() != null) {
            for (int i = parsed.aiOutput().scenes().size() - 1; i >= 0; i--) {
                AiJsonOutputV2.SceneV2 s = parsed.aiOutput().scenes().get(i);
                if (s.hasNewDynamicLocation()) { lastWithLoc = s; break; }
            }
        }
        if (lastWithLoc == null) return null;
        AiJsonOutputV2.NewDynamicLocation loc = lastWithLoc.newDynamicLocation();
        if (loc.name() == null || loc.name().isBlank()) return null;

        // 같은 canonical_key 이면 transition 생략 (V1 hotfix 패턴)
        if (room.getCurrentDynamicCanonicalKey() != null
            && loc.hasCanonicalKey()
            && room.getCurrentDynamicCanonicalKey().equals(loc.canonicalKey())) {
            log.info("🛡️ [V2-BG-GUARD] Same canonical_key — transition suppressed | roomId={}", room.getId());
            return null;
        }

        String timeOfDay = mapDayPartToTimeOfDay(room.getCurrentDayPart());
        String canonicalKey = loc.hasCanonicalKey() ? loc.canonicalKey() : null;
        Long charContextId = resolveSpeakerIdByName(room, lastWithLoc);
        if (charContextId == null) charContextId = 0L;  // 시스템

        BackgroundGenerationService.BackgroundResult bg = backgroundGenerationService.resolveBackground(
            loc.name(), canonicalKey, loc.description(), timeOfDay, charContextId);

        LocationTransition transition;
        if (bg.cacheHit()) {
            transition = LocationTransition.cached(loc.name(), bg.imageUrl());
        } else {
            transition = LocationTransition.generating(loc.name(), bg.cacheHash());
            backgroundGenerationService.generateBackgroundAsync(
                loc.name(), canonicalKey, loc.description(), timeOfDay, charContextId,
                room.getWorld(), room.isSecretModeActive());
        }

        // 영속화
        final String bgUrlToStore = bg.cacheHit() ? bg.imageUrl() : null;
        final String nameToStore = loc.name();
        final String keyToStore = canonicalKey;
        try {
            txTemplate.execute(status -> {
                ChatRoom bgRoom = chatRoomRepository.findById(room.getId()).orElse(null);
                if (bgRoom != null) {
                    if (bgUrlToStore != null) {
                        bgRoom.updateDynamicBackground(nameToStore, keyToStore, bgUrlToStore);
                    } else {
                        bgRoom.updateDynamicLocationName(nameToStore, keyToStore);
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[V2-BG] persist failed (non-blocking): {}", e.getMessage());
        }
        cacheService.evictRoomInfo(room.getId());
        return transition;
    }

    private String mapDayPartToTimeOfDay(DayPart dp) {
        if (dp == null) return "DAY";
        return dp.toBackgroundTimeOfDay().name();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Post-processing
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void triggerPostProcessing(Long roomId, Long userId, long currentTurnCount,
                                      AiJsonOutputV2 aiOutput) {
        try {
            AiJsonOutputV2.MemoryDelta delta = aiOutput.memoryDelta();
            if (delta == null) return;

            // World-level 메모리 — 10턴마다 압축
            if (delta.hasWorld() && currentTurnCount % 10 == 0) {
                memoryService.summarizeAndSaveMemory(roomId, userId);
            }

            // 캐릭터별 메모리 — 각 (room, character)에 대해 누적
            if (delta.hasCharacterMemories()) {
                for (Map.Entry<String, String> e : delta.byCharacter().entrySet()) {
                    Long charId;
                    try { charId = Long.parseLong(e.getKey()); }
                    catch (NumberFormatException ex) { continue; }
                    // [TODO — Phase 7 폴리싱]
                    // 현재는 N=10마다 직접 LLM 압축 호출 (간소). 향후 in-memory buffer로 누적 → 일괄 압축.
                    if (currentTurnCount % 10 == 0) {
                        heroineMemoryService.summarizeAndSave(
                            roomId, charId, userId,
                            List.of(e.getValue()), (int) currentTurnCount, "STORY_V2");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[V2-POST] post-processing failed (non-blocking): {}", e.getMessage());
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 헬퍼
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private boolean resolveSecretMode(ChatRoom room) {
        if (!room.isSecretModeActive()) return false;
        if (room.getWorld() != null && !room.getWorld().isSecretAllowed()) return false;
        return secretModeService.canAccessSecretMode(room.getUser());
    }

    private String persistAssistantLog(Long roomId, ParsedV2Result parsed) {
        AiJsonOutputV2.SceneV2 lastScene = parsed.aiOutput().lastScene();
        String innerThought = lastScene != null ? lastScene.innerThought() : null;
        if (innerThought != null && innerThought.isBlank()) innerThought = null;

        ChatLogDocument doc = ChatLogDocument.assistantWithThought(
            roomId, parsed.cleanJson(), parsed.combinedContent(),
            parsed.lastEmotion(), null, innerThought, parsed.scenesJson());
        ChatLogDocument saved = chatLogPersister.saveWithRetry(doc);
        if (saved != null) return saved.getId();

        log.error("⚠️ [V2-CHAT-LOG] ASSISTANT_LOG_PERSIST_FAILED | roomId={}", roomId);
        return null;
    }

    private void sendFinalResult(SseEmitter emitter, SendChatResponse response,
                                 boolean hasInnerThought, String assistantLogId,
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
                false, locationTransition);
            emitter.send(SseEmitter.event().name("final_result")
                .data(objectMapper.writeValueAsString(finalResponse)));
        } catch (Exception e) {
            log.warn("[V2-SSE] final_result send failed: {}", e.getMessage());
        }
    }

    private void sendSseError(SseEmitter emitter, String errorCode, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                .data(Map.of("errorCode", errorCode, "message", message)));
            emitter.complete();
        } catch (Exception ignored) {}
    }

    private void compensateEnergy(Long userId, int amount, String username) {
        try {
            txTemplate.execute(status -> {
                chatRoomRepository.findById(userId).ifPresent(r -> r.getUser().refundEnergy(amount));
                return null;
            });
            cacheService.evictUserProfile(username);
        } catch (Exception e) {
            log.error("⚠️ [V2-COMP] energy refund failed | userId={}", userId, e);
        }
    }

    private void compensateFullRollback(RollbackContext ctx) {
        try {
            if (ctx.savedUserLogId() != null) {
                try { chatLogRepository.deleteById(ctx.savedUserLogId()); }
                catch (Exception e) { log.warn("[V2-ROLLBACK] log delete failed: {}", e.getMessage()); }
            }
            cacheService.evictUserProfile(ctx.username());
        } catch (Exception e) {
            log.error("⚠️ [V2-ROLLBACK] failed | userId={}", ctx.userId(), e);
        }
    }

    private String buildScenesJson(List<SceneResponse> scenes) {
        try {
            return objectMapper.writeValueAsString(scenes);
        } catch (Exception e) {
            return "[]";
        }
    }

    private DayPart parseDayPart(String s) {
        if (s == null) return null;
        try { return DayPart.valueOf(s.trim().toUpperCase()); }
        catch (Exception e) { return null; }
    }
}