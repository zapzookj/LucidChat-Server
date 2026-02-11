package com.spring.aichat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 채팅 핵심 서비스
 * [Phase 3 - Bottleneck Analysis] 각 구간별 타이밍 로그 추가
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

    // ──────────────────────────────────────────────────
    //  REST (Non-Streaming) 영역
    // ──────────────────────────────────────────────────

    @Transactional
    public SendChatResponse sendMessage(Long roomId, String userMessage) {
        long totalStart = System.currentTimeMillis();
        log.info("⏱️ [TIMING] ====== sendMessage START ====== roomId={}", roomId);

        // ── [구간 1] DB: 채팅방 조회 (fetch join) ──
        long t0 = System.currentTimeMillis();
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));
        log.info("⏱️ [TIMING] [1] DB findRoom: {}ms", System.currentTimeMillis() - t0);

        // ── [구간 2] 에너지 차감 ──
        room.getUser().consumeEnergy(1);

        // ── [구간 3] DB: 유저 로그 저장 ──
        long t1 = System.currentTimeMillis();
        ChatLog userLog = ChatLog.user(room, userMessage);
        chatLogRepository.save(userLog);
        log.info("⏱️ [TIMING] [2] DB saveUserLog: {}ms", System.currentTimeMillis() - t1);

        // ── [구간 4] DB: 로그 카운트 + 메모리 트리거 체크 ──
        long t2 = System.currentTimeMillis();
        long logCount = chatLogRepository.countByRoomId(roomId);
        log.info("⏱️ [TIMING] [3] DB countLogs: {}ms (count={})", System.currentTimeMillis() - t2, logCount);

        if (logCount > 0 && logCount % 20 == 0) {
            long tMem = System.currentTimeMillis();
            log.info("⏱️ [TIMING] [3.1] Memory summarization TRIGGERED (logCount={})", logCount);
            memoryService.summarizeAndSaveMemory(roomId, room.getUser().getId());
            log.info("⏱️ [TIMING] [3.1] @Async dispatch returned: {}ms (should be ~0ms if truly async)",
                System.currentTimeMillis() - tMem);
        }

        // ── [구간 5~9] 캐릭터 응답 생성 (핵심 구간) ──
        return generateCharacterResponse(room);
    }

    /**
     * 시스템(이벤트) 메시지에 대한 캐릭터 반응 생성
     */
    @Transactional
    public SendChatResponse generateResponseForSystemEvent(Long roomId, String systemDetail) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("ChatRoom not found: " + roomId));

        ChatLog systemLog = ChatLog.system(room, systemDetail);
        chatLogRepository.save(systemLog);

        return generateCharacterResponse(room);
    }

    /**
     * 캐릭터 LLM 호출 및 응답 처리 공통 로직 (REST 전용)
     * ⚠️ 병목 의심 핵심 구간 — 상세 타이밍 로그 추가
     */
    private SendChatResponse generateCharacterResponse(ChatRoom room) {
        long genStart = System.currentTimeMillis();

        // ── [구간 5] DB: 최근 유저 메시지 조회 (RAG 쿼리용) ──
        long t3 = System.currentTimeMillis();
        String lastUserMessage = chatLogRepository
            .findTop1ByRoom_IdAndRoleOrderByCreatedAtDesc(room.getId(), ChatRole.USER)
            .map(ChatLog::getCleanContent)
            .orElse("");
        log.info("⏱️ [TIMING] [4] DB findLastUserMsg: {}ms", System.currentTimeMillis() - t3);

        // ── [구간 6] ⚠️ RAG: 임베딩 + Pinecone 검색 (외부 API 2회 호출) ──
        String longTermMemory = "";
        if (!lastUserMessage.isEmpty()) {
            long tRagTotal = System.currentTimeMillis();

            // 6a. Embedding API 호출
            long tEmbed = System.currentTimeMillis();
            try {
                longTermMemory = memoryService.retrieveContext(room.getUser().getId(), lastUserMessage);
            } catch (Exception e) {
                log.warn("⏱️ [TIMING] [5] RAG failed (non-blocking): {}", e.getMessage());
            }
            // retrieveContext 내부에서 embed + search 둘 다 하므로 전체 시간만 측정
            log.info("⏱️ [TIMING] [5] ⚠️ RAG retrieveContext (embed + pinecone): {}ms | memoryFound={}",
                System.currentTimeMillis() - tRagTotal,
                !longTermMemory.isEmpty());
        } else {
            log.info("⏱️ [TIMING] [5] RAG SKIPPED (empty lastUserMessage)");
        }

        // ── [구간 7] 프롬프트 조립 (CPU 연산) ──
        long t5 = System.currentTimeMillis();
        String systemPrompt = promptAssembler.assembleSystemPrompt(
            room.getCharacter(), room, room.getUser(), longTermMemory
        );
        log.info("⏱️ [TIMING] [6] assemblePrompt: {}ms | promptLength={} chars",
            System.currentTimeMillis() - t5, systemPrompt.length());

        // ── [구간 8] DB: 메시지 히스토리 빌드 (findTop20) ──
        long t6 = System.currentTimeMillis();
        List<OpenAiMessage> messages = buildMessageHistory(room.getId(), systemPrompt);
        log.info("⏱️ [TIMING] [7] buildMessageHistory: {}ms | messageCount={}",
            System.currentTimeMillis() - t6, messages.size());

        // ── [참고] 전송할 토큰 추정 (프롬프트 크기가 TTFT에 직접 영향) ──
        int totalChars = messages.stream().mapToInt(m -> m.content().length()).sum();
        log.info("⏱️ [TIMING] [7.1] Total prompt chars (approx tokens ÷ 2~3): {} chars", totalChars);

        // ── [구간 9] ⚠️ LLM 호출 (최대 병목 구간) ──
        String model = props.model();
        log.info("⏱️ [TIMING] [8] LLM call START | model={}", model);
        long tLlm = System.currentTimeMillis();

        String rawAssistant = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.8)
        );

        long llmElapsed = System.currentTimeMillis() - tLlm;
        log.info("⏱️ [TIMING] [8] ⚠️ LLM call DONE: {}ms | responseLength={} chars",
            llmElapsed, rawAssistant.length());

        // ── [구간 10] JSON 파싱 + DB 저장 ──
        long t8 = System.currentTimeMillis();
        try {
            String cleanJson = stripMarkdown(rawAssistant);
            AiJsonOutput aiOutput = objectMapper.readValue(cleanJson, AiJsonOutput.class);

            applyAffectionChange(room, aiOutput.affectionChange());

            String combinedDialogue = aiOutput.scenes().stream()
                .map(AiJsonOutput.Scene::dialogue)
                .collect(Collectors.joining(" "));

            String lastEmotionStr = aiOutput.scenes().isEmpty() ? "NEUTRAL"
                : aiOutput.scenes().get(aiOutput.scenes().size() - 1).emotion();
            EmotionTag mainEmotion = parseEmotion(lastEmotionStr);

            saveLog(room, ChatRole.ASSISTANT, cleanJson, combinedDialogue, mainEmotion, null);

            log.info("⏱️ [TIMING] [9] parseJSON + saveLog: {}ms", System.currentTimeMillis() - t8);

            List<SendChatResponse.SceneResponse> sceneResponses = aiOutput.scenes().stream()
                .map(s -> new SendChatResponse.SceneResponse(
                    s.narration(),
                    s.dialogue(),
                    parseEmotion(s.emotion())
                ))
                .collect(Collectors.toList());

            // ── 최종 요약 ──
            long totalElapsed = System.currentTimeMillis() - genStart;
            log.info("⏱️ [TIMING] ====== generateCharacterResponse DONE: {}ms ======", totalElapsed);
            log.info("⏱️ [TIMING] ====== BREAKDOWN: RAG={}구간 참조 | LLM={}ms | 나머지={}ms ======",
                "", llmElapsed, totalElapsed - llmElapsed);

            return new SendChatResponse(
                room.getId(),
                sceneResponses,
                room.getAffectionScore(),
                room.getStatusLevel().name()
            );

        } catch (JsonProcessingException e) {
            log.error("JSON Parsing Error: {}", rawAssistant, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 응답 형식이 올바르지 않습니다.");
        }
    }

    // ──────────────────────────────────────────────────
    //  채팅방 관리 영역
    // ──────────────────────────────────────────────────

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
        ChatLog assistantLog = new ChatLog(room, ChatRole.ASSISTANT, firstGreeting, firstGreeting, EmotionTag.NEUTRAL, null);
        chatLogRepository.save(assistantLog);

        room.updateLastActive(EmotionTag.NEUTRAL);
    }

    // ──────────────────────────────────────────────────
    //  공통 헬퍼 메서드
    // ──────────────────────────────────────────────────

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

    private void saveLog(ChatRoom room, ChatRole role, String raw, String clean, EmotionTag emotion, String audioUrl) {
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