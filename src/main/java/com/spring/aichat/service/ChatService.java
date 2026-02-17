package com.spring.aichat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.RelationStatus;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.dto.chat.ChatRoomInfoResponse;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.dto.chat.SendChatResponse.PromotionEvent;
import com.spring.aichat.dto.chat.SendChatResponse.UnlockInfo;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.cache.RedisCacheService;
import com.spring.aichat.service.prompt.CharacterPromptAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 채팅 핵심 서비스
 *
 * [Phase 3]   트랜잭션 분리 + Smart RAG Skip + Redis 캐싱
 * [Phase 4]   Scene direction fields
 * [Phase 4.1] 씬 상태 영속화 + BGM 관성 시스템
 * [Phase 5]   관계 승급 이벤트 시스템
 *   - 호감도 임계점 도달 시 승급 이벤트 자동 발동
 *   - 이벤트 중 호감도 동결 + mood_score 누적
 *   - 5턴 후 성공/실패 판정 → 해금 보상
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogRepository chatLogRepository;
    private final CharacterPromptAssembler promptAssembler;
    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;
    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;
    private final TransactionTemplate txTemplate;
    private final RedisCacheService cacheService;

    private static final long USER_TURN_MEMORY_CYCLE = 10;
    private static final long RAG_SKIP_LOG_THRESHOLD = USER_TURN_MEMORY_CYCLE * 2;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  TX 간 데이터 전달용 내부 DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record PreProcessResult(
        ChatRoom room,
        Long userId,
        long logCount,
        boolean wasPromotionPending,
        String username
    ) {}

    private record LlmResult(
        AiJsonOutput aiOutput,
        String cleanJson,
        String combinedDialogue,
        EmotionTag mainEmotion,
        List<SendChatResponse.SceneResponse> sceneResponses,
        String lastBgmMode,
        String lastLocation,
        String lastOutfit,
        String lastTimeOfDay,
        Integer moodScore       // [Phase 5] 승급 이벤트용 분위기 점수
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  유저 채팅 메시지 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public SendChatResponse sendMessage(Long roomId, String userMessage) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱ [PERF] ====== sendMessage START ====== roomId={}", roomId);

        // ── TX-1: 전처리 ──
        long tx1Start = System.currentTimeMillis();
        PreProcessResult pre = txTemplate.execute(status -> {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

            room.getUser().consumeEnergy(1);
            chatLogRepository.save(ChatLog.user(room, userMessage));
            long logCount = chatLogRepository.countByRoomId(roomId);

            return new PreProcessResult(room, room.getUser().getId(), logCount, room.isPromotionPending(), room.getUser().getUsername());
        });
        log.info("⏱ [PERF] TX-1 (preprocess): {}ms | promotionPending={}",
            System.currentTimeMillis() - tx1Start, pre.wasPromotionPending());

        // [Fix] Evict user cache after energy consumed in TX-1
        cacheService.evictUserProfile(pre.username());

        // ── Non-TX Zone: 외부 API 호출 ──
        LlmResult llmResult = callLlmAndParse(pre.room(), pre.logCount(), userMessage);

        // ── TX-2: 후처리 (승급 로직 포함) ──
        long tx2Start = System.currentTimeMillis();
        SendChatResponse response = txTemplate.execute(status -> {
            ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

            // [Phase 4.2] 승급 이벤트 처리
            PromotionEvent promoEvent = resolveAffectionAndPromotion(
                freshRoom, llmResult.aiOutput().affectionChange(), llmResult.moodScore(), pre.wasPromotionPending()
            );

            // [Phase 4.3] 엔딩 트리거 감지
            SendChatResponse.EndingTrigger endingTrigger = null;
            if (!freshRoom.isEndingReached()) {
                if (freshRoom.getAffectionScore() >= 100) {
                    endingTrigger = new SendChatResponse.EndingTrigger("HAPPY");
                    log.info("🎬 [ENDING] HAPPY ending triggered! affection={} | roomId={}",
                        freshRoom.getAffectionScore(), roomId);
                } else if (freshRoom.getAffectionScore() <= -100) {
                    endingTrigger = new SendChatResponse.EndingTrigger("BAD");
                    log.info("🎬 [ENDING] BAD ending triggered! affection={} | roomId={}",
                        freshRoom.getAffectionScore(), roomId);
                }
            }

            saveLog(freshRoom, ChatRole.ASSISTANT,
                llmResult.cleanJson(), llmResult.combinedDialogue(), llmResult.mainEmotion(), null);

            freshRoom.updateSceneState(
                llmResult.lastBgmMode(), llmResult.lastLocation(),
                llmResult.lastOutfit(), llmResult.lastTimeOfDay()
            );

            return new SendChatResponse(
                roomId,
                llmResult.sceneResponses(),
                freshRoom.getAffectionScore(),
                freshRoom.getStatusLevel().name(),
                promoEvent,
                endingTrigger
            );
        });
        log.info("⏱ [PERF] TX-2 (postprocess): {}ms", System.currentTimeMillis() - tx2Start);

        cacheService.evictRoomInfo(roomId);

        log.info("⏱ [PERF] ====== sendMessage DONE: {}ms ======",
            System.currentTimeMillis() - totalStart);

        triggerMemorySummarizationIfNeeded(roomId, pre.userId(), pre.logCount());

        return response;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5] 승급 이벤트 핵심 로직
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 호감도 적용 + 승급 이벤트 감지/진행/판정
     *
     * @return PromotionEvent (null이면 이벤트 없음)
     */
    private PromotionEvent resolveAffectionAndPromotion(
        ChatRoom room, int affectionChange, Integer moodScore, boolean wasPending) {

        if (wasPending) {
            // ── 승급 이벤트 진행 중 ──
            // 호감도 변경 동결, mood_score 누적
            // [Fix] Default mood_score to 1 when LLM omits it (not player's fault)
            int resolvedMood = (moodScore != null) ? moodScore : 1;
            if (moodScore == null) {
                log.warn("[PROMOTION] mood_score is NULL - LLM omitted it. Defaulting to 1 | roomId={}", room.getId());
            }
            room.advancePromotionTurn(resolvedMood);
            log.info("🎯 [PROMOTION] Turn {}/{} | moodScore +{} (total: {}) | roomId={}",
                room.getPromotionTurnCount(), RelationStatusPolicy.PROMOTION_MAX_TURNS,
                resolvedMood, room.getPromotionMoodScore(), room.getId());

            // 최종 턴 도달 → 판정
            if (room.getPromotionTurnCount() >= RelationStatusPolicy.PROMOTION_MAX_TURNS) {
                return resolvePromotionResult(room);
            }

            // 진행 중
            RelationStatus target = room.getPendingTargetStatus();
            return new PromotionEvent(
                "IN_PROGRESS",
                target.name(),
                RelationStatusPolicy.getDisplayName(target),
                RelationStatusPolicy.PROMOTION_MAX_TURNS - room.getPromotionTurnCount(),
                room.getPromotionMoodScore(),
                null
            );

        } else {
            // ── 일반 상태: 호감도 적용 + 승급 감지 ──
            RelationStatus oldStatus = room.getStatusLevel();

            // 호감도 적용
            applyAffectionChange(room, affectionChange);

            // 새 관계 확인
            RelationStatus newStatus = RelationStatusPolicy.fromScore(room.getAffectionScore());

            // 승급 감지
            if (RelationStatusPolicy.isUpgrade(oldStatus, newStatus)) {
                log.info("🎯 [PROMOTION] Upgrade detected: {} → {} | affection={} | roomId={}",
                    oldStatus, newStatus, room.getAffectionScore(), room.getId());

                // 호감도를 임계점 직전으로 롤백 (이벤트 중 동결 상태에서 시작)
                int thresholdEdge = RelationStatusPolicy.getThresholdScore(newStatus) - 1;
                room.updateAffection(thresholdEdge);
                room.updateStatusLevel(oldStatus);  // 원래 관계로 복원
                room.startPromotion(newStatus);      // 이벤트 시작

                log.info("🎯 [PROMOTION] Affection rolled back to {} (threshold edge) | roomId={}",
                    thresholdEdge, room.getId());

                return new PromotionEvent(
                    "STARTED",
                    newStatus.name(),
                    RelationStatusPolicy.getDisplayName(newStatus),
                    RelationStatusPolicy.PROMOTION_MAX_TURNS,
                    0,
                    null
                );
            }

            // 승급 없음 → 이벤트 없음
            return null;
        }
    }

    /**
     * 승급 이벤트 최종 판정 — 성공 또는 실패
     */
    private PromotionEvent resolvePromotionResult(ChatRoom room) {
        int totalMood = room.getPromotionMoodScore();
        RelationStatus target = room.getPendingTargetStatus();
        boolean success = totalMood >= RelationStatusPolicy.PROMOTION_SUCCESS_THRESHOLD;

        log.info("🎯 [PROMOTION] RESULT: {} | mood={}/{} | target={} | roomId={}",
            success ? "SUCCESS" : "FAILURE",
            totalMood, RelationStatusPolicy.PROMOTION_SUCCESS_THRESHOLD,
            target, room.getId());

        if (success) {
            room.completePromotionSuccess();

            // 성공: 호감도를 새 관계의 임계점으로 세팅
            int thresholdScore = RelationStatusPolicy.getThresholdScore(target);
            room.updateAffection(thresholdScore);

            log.info("🎯 [PROMOTION] Success: affection set to {} (threshold of {}) | roomId={}",
                thresholdScore, target, room.getId());

            // 해금 목록 구성
            List<UnlockInfo> unlocks = RelationStatusPolicy.getUnlocksForRelation(target)
                .stream()
                .map(u -> new UnlockInfo(u.type(), u.name(), u.displayName()))
                .collect(Collectors.toList());

            return new PromotionEvent(
                "SUCCESS",
                target.name(),
                RelationStatusPolicy.getDisplayName(target),
                0,
                totalMood,
                unlocks
            );
        } else {
            room.completePromotionFailure();

            // 실패 패널티: 호감도를 임계점 아래로 확실히 떨어뜨림
            int penalty = RelationStatusPolicy.PROMOTION_FAILURE_PENALTY;
            int thresholdEdge = RelationStatusPolicy.getThresholdScore(target) - 1;
            int penalizedScore = Math.max(0, thresholdEdge - penalty);
            room.updateAffection(penalizedScore);
            room.updateStatusLevel(RelationStatusPolicy.fromScore(penalizedScore));

            log.info("🎯 [PROMOTION] Failure penalty applied: affection {} → {} (penalty={}) | roomId={}",
                thresholdEdge, penalizedScore, penalty, room.getId());

            return new PromotionEvent(
                "FAILURE",
                target.name(),
                RelationStatusPolicy.getDisplayName(target),
                0,
                totalMood,
                null
            );
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  시스템 이벤트에 대한 캐릭터 반응 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public SendChatResponse generateResponseForSystemEvent(Long roomId, String systemDetail, int energyCost) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱ [PERF] ====== systemEvent START ====== roomId={}", roomId);

        long tx1Start = System.currentTimeMillis();
        PreProcessResult pre = txTemplate.execute(status -> {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("ChatRoom not found: " + roomId));

            if (energyCost > 0) {
                room.getUser().consumeEnergy(energyCost);
            }

            chatLogRepository.save(ChatLog.system(room, systemDetail));
            long logCount = chatLogRepository.countByRoomId(roomId);

            return new PreProcessResult(room, room.getUser().getId(), logCount, room.isPromotionPending(), room.getUser().getUsername());
        });
        log.info("⏱ [PERF] TX-1 (event preprocess): {}ms", System.currentTimeMillis() - tx1Start);

        // [Fix] Evict user cache after energy consumed
        cacheService.evictUserProfile(pre.username());

        String ragQuery = fetchLastUserMessage(roomId);
        LlmResult llmResult = callLlmAndParse(pre.room(), pre.logCount(), ragQuery);

        long tx2Start = System.currentTimeMillis();
        SendChatResponse response = txTemplate.execute(status -> {
            ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

            // 시스템 이벤트 중에는 승급 이벤트 감지하지 않음 (일반 호감도만 적용)
            applyAffectionChange(freshRoom, llmResult.aiOutput().affectionChange());
            saveLog(freshRoom, ChatRole.ASSISTANT,
                llmResult.cleanJson(), llmResult.combinedDialogue(), llmResult.mainEmotion(), null);

            freshRoom.updateSceneState(
                llmResult.lastBgmMode(), llmResult.lastLocation(),
                llmResult.lastOutfit(), llmResult.lastTimeOfDay()
            );

            return new SendChatResponse(
                roomId,
                llmResult.sceneResponses(),
                freshRoom.getAffectionScore(),
                freshRoom.getStatusLevel().name()
            );
        });
        log.info("⏱ [PERF] TX-2 (event postprocess): {}ms", System.currentTimeMillis() - tx2Start);

        cacheService.evictRoomInfo(roomId);

        log.info("⏱ [PERF] ====== systemEvent DONE: {}ms ======",
            System.currentTimeMillis() - totalStart);

        return response;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Non-TX 공통 로직
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private LlmResult callLlmAndParse(ChatRoom room, long logCount, String ragQuery) {
        String longTermMemory = "";
        if (logCount >= RAG_SKIP_LOG_THRESHOLD && ragQuery != null && !ragQuery.isEmpty()) {
            long ragStart = System.currentTimeMillis();
            try {
                longTermMemory = memoryService.retrieveContext(room.getUser().getId(), ragQuery);
            } catch (Exception e) {
                log.warn("⏱ [PERF] RAG failed (non-blocking): {}", e.getMessage());
            }
            log.info("⏱ [PERF] RAG: {}ms | found={}",
                System.currentTimeMillis() - ragStart, !longTermMemory.isEmpty());
        } else {
            log.info("⏱ [PERF] RAG SKIPPED (logCount={} < threshold={})", logCount, RAG_SKIP_LOG_THRESHOLD);
        }

        String systemPrompt = promptAssembler.assembleSystemPrompt(
            room.getCharacter(), room, room.getUser(), longTermMemory
        );

        List<OpenAiMessage> messages = buildMessageHistory(room.getId(), systemPrompt);

        String model = props.model();
        long llmStart = System.currentTimeMillis();
        log.info("⏱ [PERF] LLM call START | model={} | messages={} | promptChars={}",
            model, messages.size(),
            messages.stream().mapToInt(m -> m.content().length()).sum());

        String rawAssistant = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.8)
        );
        log.info("⏱ [PERF] LLM call DONE: {}ms | responseChars={}",
            System.currentTimeMillis() - llmStart, rawAssistant.length());

        try {
            String cleanJson = stripMarkdown(rawAssistant);
            AiJsonOutput aiOutput = objectMapper.readValue(cleanJson, AiJsonOutput.class);

            String combinedDialogue = aiOutput.scenes().stream()
                .map(AiJsonOutput.Scene::dialogue)
                .collect(Collectors.joining(" "));

            String lastEmotionStr = aiOutput.scenes().isEmpty() ? "NEUTRAL"
                : aiOutput.scenes().get(aiOutput.scenes().size() - 1).emotion();
            EmotionTag mainEmotion = parseEmotion(lastEmotionStr);

            List<SendChatResponse.SceneResponse> sceneResponses = aiOutput.scenes().stream()
                .map(s -> new SendChatResponse.SceneResponse(
                    s.narration(),
                    s.dialogue(),
                    parseEmotion(s.emotion()),
                    safeUpperCase(s.location()),
                    safeUpperCase(s.time()),
                    safeUpperCase(s.outfit()),
                    safeUpperCase(s.bgmMode())
                ))
                .collect(Collectors.toList());

            String lastBgm = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::bgmMode);
            String lastLoc = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::location);
            String lastOutfit = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::outfit);
            String lastTime = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::time);

            // [Phase 5] mood_score 추출 (없으면 0)
            Integer moodScore = aiOutput.moodScore();

            return new LlmResult(aiOutput, cleanJson, combinedDialogue, mainEmotion, sceneResponses,
                lastBgm, lastLoc, lastOutfit, lastTime, moodScore);

        } catch (JsonProcessingException e) {
            log.error("JSON Parsing Error: {}", rawAssistant, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 응답 형식이 올바르지 않습니다.");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  채팅방 관리 영역
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public ChatRoomInfoResponse getChatRoomInfo(Long roomId) {
        return cacheService.getRoomInfo(roomId, ChatRoomInfoResponse.class)
            .orElseGet(() -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

                ChatRoomInfoResponse response = new ChatRoomInfoResponse(
                    room.getId(),
                    room.getCharacter().getName(),
                    room.getCharacter().getDefaultImageUrl(),
                    "background_default.png",
                    room.getAffectionScore(),
                    room.getStatusLevel().name(),
                    room.getCurrentBgmMode() != null ? room.getCurrentBgmMode().name() : "DAILY",
                    room.getCurrentLocation() != null ? room.getCurrentLocation().name() : "ENTRANCE",
                    room.getCurrentOutfit() != null ? room.getCurrentOutfit().name() : "MAID",
                    room.getCurrentTimeOfDay() != null ? room.getCurrentTimeOfDay().name() : "NIGHT",
                    // [Phase 4.3] 엔딩 상태
                    room.isEndingReached(),
                    room.getEndingType() != null ? room.getEndingType().name() : null,
                    room.getEndingTitle()
                );

                cacheService.cacheRoomInfo(roomId, response);
                log.debug("🏠 [CACHE] ChatRoomInfo cached: roomId={}", roomId);
                return response;
            });
    }

    @Transactional
    public void deleteChatRoom(Long roomId) {
        chatLogRepository.deleteByRoom_Id(roomId);
        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow(
            () -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId)
        );
        room.resetAll(); // [Phase 5] 호감도 + 씬 + 승급 전부 리셋

        cacheService.evictRoomInfo(roomId);
        cacheService.evictRoomOwner(roomId);
    }

    @Transactional
    public void initializeChatRoom(Long roomId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("Room not found"));

        if (chatLogRepository.countByRoomId(roomId) > 0) return;

        String introNarration = """
            달빛이 쏟아지는 밤, 당신은 숲속 깊은 곳에 위치한 고풍스러운 저택 앞에 도착했습니다.
            저택의 무거운 현관문을 밀자, 따스한 온기와 은은한 향기가 당신을 감쌉니다.
            로비의 중앙, 샹들리에 아래에 단정하게 서 있던 메이드가 당신을 발견하고 부드럽게 고개를 숙입니다.
            """;

        chatLogRepository.save(ChatLog.system(room, introNarration));

        String firstGreeting = "어서 오세요, 주인님. 기다리고 있었습니다. 여행길이 고단하진 않으셨나요?";
        ChatLog assistantLog = new ChatLog(
            room, ChatRole.ASSISTANT, firstGreeting, firstGreeting, EmotionTag.NEUTRAL, null);
        chatLogRepository.save(assistantLog);

        room.updateLastActive(EmotionTag.NEUTRAL);
        room.resetSceneState();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 헬퍼 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String fetchLastUserMessage(Long roomId) {
        return chatLogRepository
            .findTop1ByRoom_IdAndRoleOrderByCreatedAtDesc(roomId, ChatRole.USER)
            .map(ChatLog::getCleanContent)
            .orElse("");
    }

    private void triggerMemorySummarizationIfNeeded(Long roomId, Long userId, long totalLogCount) {
        long userMsgCount = chatLogRepository.countByRoom_IdAndRole(roomId, ChatRole.USER);

        if (userMsgCount > 0 && userMsgCount % USER_TURN_MEMORY_CYCLE == 0) {
            log.info("🧠 [MEMORY] Summarization TRIGGERED | roomId={} | userMsgCount={}",
                roomId, userMsgCount);
            memoryService.summarizeAndSaveMemory(roomId, userId);
        }
    }

    private List<OpenAiMessage> buildMessageHistory(Long roomId, String systemPrompt) {
        List<ChatLog> recent = chatLogRepository.findTop20ByRoom_IdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLog::getCreatedAt));

        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.system(systemPrompt));

        for (ChatLog chatLog : recent) {
            switch (chatLog.getRole()) {
                case USER -> messages.add(OpenAiMessage.user(chatLog.getRawContent()));
                case ASSISTANT -> messages.add(OpenAiMessage.assistant(chatLog.getRawContent()));
                case SYSTEM -> messages.add(
                    OpenAiMessage.user("[NARRATION]\n" + chatLog.getRawContent())
                );
            }
        }

        return messages;
    }

    private void applyAffectionChange(ChatRoom room, int change) {
        if (change == 0) return;
        int newScore = room.getAffectionScore() + change;
        newScore = Math.max(-100, Math.min(100, newScore));  // -100 ~ 100
        room.updateAffection(newScore);
        room.updateStatusLevel(RelationStatusPolicy.fromScore(newScore));
    }

    private void saveLog(ChatRoom room, ChatRole role, String raw, String clean,
                         EmotionTag emotion, String audioUrl) {
        ChatLog chatLog = new ChatLog(room, role, raw, clean, emotion, audioUrl);
        chatLogRepository.save(chatLog);
        room.updateLastActive(emotion);
    }

    private String stripMarkdown(String text) {
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }

    private EmotionTag parseEmotion(String emotionStr) {
        try {
            return EmotionTag.valueOf(emotionStr.toUpperCase());
        } catch (Exception e) {
            return EmotionTag.NEUTRAL;
        }
    }

    private String safeUpperCase(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value.toUpperCase().trim();
    }

    private String extractLastNonNull(
        List<SendChatResponse.SceneResponse> scenes,
        java.util.function.Function<SendChatResponse.SceneResponse, String> extractor) {
        for (int i = scenes.size() - 1; i >= 0; i--) {
            String val = extractor.apply(scenes.get(i));
            if (val != null) return val;
        }
        return null;
    }
}