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
 * [Phase 3 최적화]
 * 1. 트랜잭션 분리: TX-1(전처리) → Non-TX(RAG + LLM) → TX-2(후처리)
 * 2. Smart RAG Skip: logCount < 20이면 RAG 호출 생략
 * 3. 불필요한 DB 쿼리 제거: userMessage 파라미터 직접 사용
 * 4. Redis 캐싱: ChatRoomInfo 캐싱 + 호감도 변경 시 eviction
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

    /** USER 메시지 기준 메모리 요약 주기 */
    private static final long USER_TURN_MEMORY_CYCLE = 10;

    /** RAG 호출 스킵 기준 */
    private static final long RAG_SKIP_LOG_THRESHOLD = USER_TURN_MEMORY_CYCLE * 2;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  TX 간 데이터 전달용 내부 DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private record PreProcessResult(
        ChatRoom room,
        Long userId,
        long logCount
    ) {}

    private record LlmResult(
        AiJsonOutput aiOutput,
        String cleanJson,
        String combinedDialogue,
        EmotionTag mainEmotion,
        List<SendChatResponse.SceneResponse> sceneResponses
    ) {}

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  유저 채팅 메시지 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public SendChatResponse sendMessage(Long roomId, String userMessage) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱️ [PERF] ====== sendMessage START ====== roomId={}", roomId);

        // ━━ TX-1: 전처리 ━━
        long tx1Start = System.currentTimeMillis();
        PreProcessResult pre = txTemplate.execute(status -> {
            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

            room.getUser().consumeEnergy(1);
            chatLogRepository.save(ChatLog.user(room, userMessage));
            long logCount = chatLogRepository.countByRoomId(roomId);

            return new PreProcessResult(room, room.getUser().getId(), logCount);
        });
        log.info("⏱️ [PERF] TX-1 (preprocess): {}ms", System.currentTimeMillis() - tx1Start);

        // ━━ Non-TX Zone: 외부 API 호출 ━━
        LlmResult llmResult = callLlmAndParse(pre.room(), pre.logCount(), userMessage);

        // ━━ TX-2: 후처리 ━━
        long tx2Start = System.currentTimeMillis();
        SendChatResponse response = txTemplate.execute(status -> {
            ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

            applyAffectionChange(freshRoom, llmResult.aiOutput().affectionChange());
            saveLog(freshRoom, ChatRole.ASSISTANT,
                llmResult.cleanJson(), llmResult.combinedDialogue(), llmResult.mainEmotion(), null);

            return new SendChatResponse(
                roomId,
                llmResult.sceneResponses(),
                freshRoom.getAffectionScore(),
                freshRoom.getStatusLevel().name()
            );
        });
        log.info("⏱️ [PERF] TX-2 (postprocess): {}ms", System.currentTimeMillis() - tx2Start);

        // [Redis] 호감도 변경 시 캐시 무효화
        if (llmResult.aiOutput().affectionChange() != 0) {
            cacheService.evictRoomInfo(roomId);
        }

        log.info("⏱️ [PERF] ====== sendMessage DONE: {}ms ======",
            System.currentTimeMillis() - totalStart);

        // 메모리 요약 트리거 (@Async — TX-2 이후)
        triggerMemorySummarizationIfNeeded(roomId, pre.userId(), pre.logCount());

        return response;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  시스템 이벤트에 대한 캐릭터 반응 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public SendChatResponse generateResponseForSystemEvent(Long roomId, String systemDetail, int energyCost) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱️ [PERF] ====== systemEvent START ====== roomId={}", roomId);

        // ━━ TX-1 ━━
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
        log.info("⏱️ [PERF] TX-1 (event preprocess): {}ms", System.currentTimeMillis() - tx1Start);

        // ━━ Non-TX Zone ━━
        String ragQuery = fetchLastUserMessage(roomId);
        LlmResult llmResult = callLlmAndParse(pre.room(), pre.logCount(), ragQuery);

        // ━━ TX-2 ━━
        long tx2Start = System.currentTimeMillis();
        SendChatResponse response = txTemplate.execute(status -> {
            ChatRoom freshRoom = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

            applyAffectionChange(freshRoom, llmResult.aiOutput().affectionChange());
            saveLog(freshRoom, ChatRole.ASSISTANT,
                llmResult.cleanJson(), llmResult.combinedDialogue(), llmResult.mainEmotion(), null);

            return new SendChatResponse(
                roomId,
                llmResult.sceneResponses(),
                freshRoom.getAffectionScore(),
                freshRoom.getStatusLevel().name()
            );
        });
        log.info("⏱️ [PERF] TX-2 (event postprocess): {}ms", System.currentTimeMillis() - tx2Start);

        // [Redis] 이벤트는 항상 에너지/호감도 변동 → 캐시 무효화
        cacheService.evictRoomInfo(roomId);

        log.info("⏱️ [PERF] ====== systemEvent DONE: {}ms ======",
            System.currentTimeMillis() - totalStart);

        return response;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Non-TX 공통 로직
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private LlmResult callLlmAndParse(ChatRoom room, long logCount, String ragQuery) {
        String longTermMemory = "";
        if (logCount >= RAG_SKIP_LOG_THRESHOLD && ragQuery != null && !ragQuery.isEmpty()) {
            long ragStart = System.currentTimeMillis();
            try {
                longTermMemory = memoryService.retrieveContext(room.getUser().getId(), ragQuery);
            } catch (Exception e) {
                log.warn("⏱️ [PERF] RAG failed (non-blocking): {}", e.getMessage());
            }
            log.info("⏱️ [PERF] RAG: {}ms | found={}",
                System.currentTimeMillis() - ragStart, !longTermMemory.isEmpty());
        } else {
            log.info("⏱️ [PERF] RAG SKIPPED (logCount={} < threshold={})", logCount, RAG_SKIP_LOG_THRESHOLD);
        }

        String systemPrompt = promptAssembler.assembleSystemPrompt(
            room.getCharacter(), room, room.getUser(), longTermMemory
        );

        List<OpenAiMessage> messages = buildMessageHistory(room.getId(), systemPrompt);

        String model = props.model();
        long llmStart = System.currentTimeMillis();
        log.info("⏱️ [PERF] LLM call START | model={} | messages={} | promptChars={}",
            model, messages.size(),
            messages.stream().mapToInt(m -> m.content().length()).sum());

        String rawAssistant = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.8)
        );
        log.info("⏱️ [PERF] LLM call DONE: {}ms | responseChars={}",
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
                    s.narration(), s.dialogue(), parseEmotion(s.emotion())))
                .collect(Collectors.toList());

            return new LlmResult(aiOutput, cleanJson, combinedDialogue, mainEmotion, sceneResponses);

        } catch (JsonProcessingException e) {
            log.error("JSON Parsing Error: {}", rawAssistant, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 응답 형식이 올바르지 않습니다.");
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  채팅방 관리 영역
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 채팅방 정보 조회
     *
     * [Phase 3 Redis 캐싱]
     * - Cache Hit: Redis에서 즉시 반환 (DB 접근 없음)
     * - Cache Miss: DB 조회 후 Redis에 60초 TTL로 캐싱
     * - 호감도 변경 시(sendMessage, selectEvent) 캐시 evict
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
                    room.getStatusLevel().name()
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

        // [Redis] 관련 캐시 모두 무효화
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
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  공통 헬퍼 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
}