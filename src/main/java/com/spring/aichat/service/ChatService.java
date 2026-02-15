package com.spring.aichat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.*;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.dto.chat.AiJsonOutput;
import com.spring.aichat.dto.chat.ChatRoomInfoResponse;
import com.spring.aichat.dto.chat.SendChatResponse;
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
 * [Phase 3] 트랜잭션 분리 + Smart RAG Skip + Redis 캐싱
 * [Phase 4] Scene direction fields (location, time, outfit, bgmMode) 매핑 추가
 * [Phase 4.1] 씬 상태 영속화 + BGM 관성 시스템
 *   - ChatRoom에 씬 상태 저장 (bgmMode, location, outfit, timeOfDay)
 *   - LLM 프롬프트에 현재 상태 주입 → 불필요한 전환 방지
 *   - 재접속 시 마지막 씬 상태 복원
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
        long logCount
    ) {}

    /**
     * [Phase 4.1] LLM 결과에 씬 상태 갱신 정보 포함
     * lastXxx 필드: 응답의 마지막 씬에서 추출한 non-null 값 (DB 영속용)
     */
    private record LlmResult(
        AiJsonOutput aiOutput,
        String cleanJson,
        String combinedDialogue,
        EmotionTag mainEmotion,
        List<SendChatResponse.SceneResponse> sceneResponses,
        String lastBgmMode,
        String lastLocation,
        String lastOutfit,
        String lastTimeOfDay
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

            return new PreProcessResult(room, room.getUser().getId(), logCount);
        });
        log.info("⏱ [PERF] TX-1 (preprocess): {}ms", System.currentTimeMillis() - tx1Start);

        // ── Non-TX Zone: 외부 API 호출 ──
        LlmResult llmResult = callLlmAndParse(pre.room(), pre.logCount(), userMessage);

        // ── TX-2: 후처리 ──
        long tx2Start = System.currentTimeMillis();
        SendChatResponse response = txTemplate.execute(status -> {
            ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

            applyAffectionChange(freshRoom, llmResult.aiOutput().affectionChange());
            saveLog(freshRoom, ChatRole.ASSISTANT,
                llmResult.cleanJson(), llmResult.combinedDialogue(), llmResult.mainEmotion(), null);

            // [Phase 4.1] 씬 상태 영속화
            freshRoom.updateSceneState(
                llmResult.lastBgmMode(),
                llmResult.lastLocation(),
                llmResult.lastOutfit(),
                llmResult.lastTimeOfDay()
            );

            return new SendChatResponse(
                roomId,
                llmResult.sceneResponses(),
                freshRoom.getAffectionScore(),
                freshRoom.getStatusLevel().name()
            );
        });
        log.info("⏱ [PERF] TX-2 (postprocess): {}ms", System.currentTimeMillis() - tx2Start);

        if (llmResult.aiOutput().affectionChange() != 0) {
            cacheService.evictRoomInfo(roomId);
        }

        log.info("⏱ [PERF] ====== sendMessage DONE: {}ms ======",
            System.currentTimeMillis() - totalStart);

        triggerMemorySummarizationIfNeeded(roomId, pre.userId(), pre.logCount());

        return response;
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

            return new PreProcessResult(room, room.getUser().getId(), logCount);
        });
        log.info("⏱ [PERF] TX-1 (event preprocess): {}ms", System.currentTimeMillis() - tx1Start);

        String ragQuery = fetchLastUserMessage(roomId);
        LlmResult llmResult = callLlmAndParse(pre.room(), pre.logCount(), ragQuery);

        long tx2Start = System.currentTimeMillis();
        SendChatResponse response = txTemplate.execute(status -> {
            ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

            applyAffectionChange(freshRoom, llmResult.aiOutput().affectionChange());
            saveLog(freshRoom, ChatRole.ASSISTANT,
                llmResult.cleanJson(), llmResult.combinedDialogue(), llmResult.mainEmotion(), null);

            // [Phase 4.1] 씬 상태 영속화
            freshRoom.updateSceneState(
                llmResult.lastBgmMode(),
                llmResult.lastLocation(),
                llmResult.lastOutfit(),
                llmResult.lastTimeOfDay()
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

            // [Phase 4] Scene direction fields 매핑 (null-safe)
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

            // [Phase 4.1] 마지막 씬의 non-null 상태 추출 (DB 영속용)
            // 여러 씬 중 마지막 non-null 값을 역순으로 탐색
            String lastBgm = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::bgmMode);
            String lastLoc = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::location);
            String lastOutfit = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::outfit);
            String lastTime = extractLastNonNull(sceneResponses, SendChatResponse.SceneResponse::time);

            return new LlmResult(aiOutput, cleanJson, combinedDialogue, mainEmotion, sceneResponses,
                lastBgm, lastLoc, lastOutfit, lastTime);

        } catch (JsonProcessingException e) {
            log.error("JSON Parsing Error: {}", rawAssistant, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 응답 형식이 올바르지 않습니다.");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  채팅방 관리 영역
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * [Phase 4.1] 씬 상태 포함하여 반환 — 재접속 시 복원용
     */
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
                    // [Phase 4.1] 씬 상태
                    room.getCurrentBgmMode() != null ? room.getCurrentBgmMode().name() : "DAILY",
                    room.getCurrentLocation() != null ? room.getCurrentLocation().name() : "ENTRANCE",
                    room.getCurrentOutfit() != null ? room.getCurrentOutfit().name() : "MAID",
                    room.getCurrentTimeOfDay() != null ? room.getCurrentTimeOfDay().name() : "NIGHT"
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
        room.resetAffection();
        room.resetSceneState(); // [Phase 4.1] 씬 상태도 리셋

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
        room.resetSceneState(); // [Phase 4.1] 초기 씬 상태 설정
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
        newScore = Math.max(-100, Math.min(100, newScore));
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

    /**
     * [Phase 4] LLM 출력 문자열을 안전하게 대문자 변환
     * null 또는 "null" 문자열이면 null 반환 (프론트에서 "이전 값 유지" 처리)
     */
    private String safeUpperCase(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value.toUpperCase().trim();
    }

    /**
     * [Phase 4.1] 씬 리스트에서 특정 필드의 마지막 non-null 값을 역순 탐색
     */
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