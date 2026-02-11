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
 *    → DB 커넥션 점유 시간을 ~20ms 이하로 축소
 * 2. Smart RAG Skip: logCount < 20이면 RAG 호출 생략 (메모리가 존재할 수 없으므로)
 *    → 초반 대화에서 5~6초 절약
 * 3. 불필요한 DB 쿼리 제거: userMessage 파라미터 직접 사용
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

    /** USER 메시지 기준 메모리 요약 주기 (10 유저턴 ≈ 20 총 로그) */
    private static final long USER_TURN_MEMORY_CYCLE = 10;

    /** RAG 호출 스킵 기준: 이 로그 수 미만이면 메모리가 존재할 수 없음 */
    private static final long RAG_SKIP_LOG_THRESHOLD = USER_TURN_MEMORY_CYCLE * 2;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  TX 간 데이터 전달용 내부 DTO
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** TX-1 → Non-TX 구간으로 전달되는 데이터 */
    private record PreProcessResult(
        ChatRoom room,      // Detached but fully loaded (EntityGraph)
        Long userId,
        long logCount
    ) {}

    /** Non-TX 구간에서 LLM 호출 + 파싱 결과 */
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

    /**
     * 유저 메시지 전송 → 캐릭터 응답 반환
     * @Transactional 제거 — TransactionTemplate으로 수동 분리
     */
    public SendChatResponse sendMessage(Long roomId, String userMessage) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱️ [PERF] ====== sendMessage START ====== roomId={}", roomId);

        // ━━ TX-1: 전처리 (유저 메시지 저장, 에너지 차감) ━━
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
        // ━━ TX-1 커밋 완료. DB 커넥션 반환됨. ━━

        // ━━ Non-TX Zone: 외부 API 호출 (DB 커넥션 미점유) ━━
        // userMessage를 RAG 쿼리로 직접 사용 (불필요한 DB 조회 제거)
        LlmResult llmResult = callLlmAndParse(pre.room(), pre.logCount(), userMessage);

        // ━━ TX-2: 후처리 (AI 로그 저장, 호감도 반영) ━━
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
        // ━━ TX-2 커밋 완료. ━━

        log.info("⏱️ [PERF] ====== sendMessage DONE: {}ms ======",
            System.currentTimeMillis() - totalStart);

        // ━━ 메모리 요약 트리거 (@Async) ━━
        // ⚠️ 반드시 TX-2 이후에 호출: 모든 OpenRouter 호출이 완료된 시점에서 비동기 시작
        //   → 동시 API 요청 경합 방지 (401 "User not found" 해소)
        // ⚠️ 반드시 TX-1 커밋 이후에 호출: 비동기 스레드가 커밋된 데이터를 읽을 수 있어야 함
        triggerMemorySummarizationIfNeeded(roomId, pre.userId(), pre.logCount());

        return response;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  시스템 이벤트에 대한 캐릭터 반응 처리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 시스템(이벤트) 메시지 저장 + 에너지 차감 + 캐릭터 반응 생성
     * NarratorService.selectEvent()에서 호출
     *
     * @param energyCost 이벤트 선택 시 차감할 에너지 (0이면 차감 안 함)
     */
    public SendChatResponse generateResponseForSystemEvent(Long roomId, String systemDetail, int energyCost) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱️ [PERF] ====== systemEvent START ====== roomId={}", roomId);

        // ━━ TX-1: 시스템 로그 저장 + 에너지 차감 ━━
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

        // ━━ Non-TX Zone: RAG + LLM ━━
        // 이벤트 시에는 최근 유저 메시지를 RAG 쿼리로 사용
        String ragQuery = fetchLastUserMessage(roomId);
        LlmResult llmResult = callLlmAndParse(pre.room(), pre.logCount(), ragQuery);

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
        log.info("⏱️ [PERF] TX-2 (event postprocess): {}ms", System.currentTimeMillis() - tx2Start);

        log.info("⏱️ [PERF] ====== systemEvent DONE: {}ms ======",
            System.currentTimeMillis() - totalStart);

        return response;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Non-TX 공통 로직: RAG + 프롬프트 + LLM 호출 + 파싱
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * DB 커넥션 없이 실행되는 핵심 AI 파이프라인
     * - Smart RAG: logCount < MEMORY_THRESHOLD 이면 외부 API 호출 생략
     * - 프롬프트 조립 + LLM 호출 + JSON 파싱
     *
     * @param room      Detached but fully loaded (EntityGraph)
     * @param logCount  현재 방의 총 로그 수 (RAG 스킵 판단용)
     * @param ragQuery  RAG 검색 쿼리 (유저의 최근 발화)
     */
    private LlmResult callLlmAndParse(ChatRoom room, long logCount, String ragQuery) {

        // ── [Strategy 1] Smart RAG Skip ──
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

        // ── 프롬프트 조립 ──
        String systemPrompt = promptAssembler.assembleSystemPrompt(
            room.getCharacter(), room, room.getUser(), longTermMemory
        );

        // ── 메시지 히스토리 빌드 (짧은 DB read — Non-TX 가능) ──
        List<OpenAiMessage> messages = buildMessageHistory(room.getId(), systemPrompt);

        // ── LLM 호출 ──
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

        // ── JSON 파싱 ──
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

    @Transactional(readOnly = true)
    public ChatRoomInfoResponse getChatRoomInfo(Long roomId) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

        return new ChatRoomInfoResponse(
            room.getId(),
            room.getCharacter().getName(),
            room.getCharacter().getDefaultImageUrl(),
            "background_default.png",
            room.getAffectionScore(),
            room.getStatusLevel().name()
        );
    }

    @Transactional
    public void deleteChatRoom(Long roomId) {
        chatLogRepository.deleteByRoom_Id(roomId);
        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow(
            () -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId)
        );
        room.resetAffection();
    }

    @Transactional
    public void initializeChatRoom(Long roomId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
            .orElseThrow(() -> new NotFoundException("Room not found"));

        if (chatLogRepository.countByRoomId(roomId) > 0) return;

        String introNarration = """
            [NARRATION]
            달빛이 쏟아지는 밤, 당신은 숲속 깊은 곳에 위치한 고풍스러운 저택 앞에 도착했습니다.
            초대장을 손에 쥐고 무거운 현관문을 밀자, 따스한 온기와 은은한 홍차 향기가 당신을 감쌉니다.
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

    /** 이벤트 처리 시 RAG 쿼리용 최근 유저 메시지 조회 */
    private String fetchLastUserMessage(Long roomId) {
        return chatLogRepository
            .findTop1ByRoom_IdAndRoleOrderByCreatedAtDesc(roomId, ChatRole.USER)
            .map(ChatLog::getCleanContent)
            .orElse("");
    }

    /**
     * 메모리 요약 트리거 (TX-1 커밋 이후에 호출해야 함)
     *
     * [수정된 로직]
     * 1. 총 로그 수(logCount)가 아닌 USER 메시지 수 기준으로 판단
     *    → 총 로그 수는 SYSTEM/ASSISTANT 로그에 따라 홀짝이 달라져 % 연산이 불안정
     * 2. TX-1 커밋 이후에 호출 → @Async 스레드가 커밋된 데이터를 정상 읽음
     */
    private void triggerMemorySummarizationIfNeeded(Long roomId, Long userId, long totalLogCount) {
        // USER 메시지 수 추정: (총 로그 - 초기 2개) / 2 ≈ 유저 턴 수
        // 정확한 카운트가 필요하므로 DB에서 직접 조회
        long userMsgCount = chatLogRepository.countByRoom_IdAndRole(roomId, ChatRole.USER);

        if (userMsgCount > 0 && userMsgCount % USER_TURN_MEMORY_CYCLE == 0) {
            log.info("🧠 [MEMORY] Summarization TRIGGERED | roomId={} | userMsgCount={}",
                roomId, userMsgCount);
            memoryService.summarizeAndSaveMemory(roomId, userId);
        }
    }

    /**
     * 최근 대화 로그를 LLM 메시지 포맷으로 변환
     * Anti-Hallucination: SYSTEM 로그에 [NARRATION] 태그 부착
     */
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