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
 * - 동적 프롬프트 조립(호감도/관계 반영)
 * - 최근 대화 내역 20개를 컨텍스트로 주입
 * - OpenRouter 호출 후 응답 파싱/저장
 *
 * [Phase 3] SSE 스트리밍은 트랜잭션을 3단계로 분리:
 *   TX-1(전처리) → No-TX(프롬프트 조립 + 스트리밍) → TX-2(후처리)
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

    // ──────────────────────────────────────────────
    //  [Phase 3] SSE 스트리밍 영역
    // ──────────────────────────────────────────────

    /**
     * SSE 스트리밍 메인 로직
     * 트랜잭션 경계를 [전처리] - [스트리밍] - [후처리]로 분리하여
     * LLM 응답 대기 동안 DB 커넥션을 점유하지 않는다.
     *
     * @return Flux<String> - 프론트엔드로 실시간 전송되는 텍스트 청크 스트림
     */
//    public Flux<String> streamMessage(Long roomId, String userMessage) {
//
//        // ── [Phase 1] 전처리: 유저 메시지 저장 & 에너지 차감 (TX-1) ──
//        // TransactionTemplate으로 명시적 트랜잭션 → 커밋 후 즉시 DB 커넥션 반환
//        Long userId = txTemplate.execute(status -> {
//            ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
//                .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
//
//            room.getUser().consumeEnergy(1);
//            chatLogRepository.save(ChatLog.user(room, userMessage));
//
//            // 메모리 요약 트리거 (20턴 단위)
//            long logCount = chatLogRepository.countByRoomId(roomId);
//            if (logCount > 0 && logCount % 20 == 0) {
//                memoryService.summarizeAndSaveMemory(roomId, room.getUser().getId());
//            }
//
//            return room.getUser().getId();
//        });
//        // ── TX-1 커밋 완료. DB 커넥션 반환됨. ──
//
//        // ── [Phase 1.5] 프롬프트 조립 (TX 불필요 - 읽기 전용) ──
//        // EntityGraph(fetch join)으로 즉시 로딩되므로 Lazy 이슈 없음
//        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
//            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));
//
//        // RAG: 장기 기억 조회 (외부 API 호출 포함 → TX 밖에서 실행)
//        String longTermMemory = userMessage.isEmpty() ? ""
//            : memoryService.retrieveContext(userId, userMessage);
//
//        String systemPrompt = promptAssembler.assembleSystemPrompt(
//            room.getCharacter(), room, room.getUser(), longTermMemory
//        );
//
//        // 히스토리 로딩 (TX-1에서 저장한 유저 메시지가 이미 포함됨)
//        List<OpenAiMessage> messages = buildMessageHistory(roomId, systemPrompt);
//
//        String model = room.getCharacter().getLlmModelName() != null
//            ? room.getCharacter().getLlmModelName() : props.model();
//
//        // ── [Phase 2] 스트리밍 (TX 없음) + 후처리 훅 ──
//        StringBuilder buffer = new StringBuilder();
//
//        return openRouterClient.streamChatCompletion(new OpenAiChatRequest(model, messages, 0.8))
//            .map(this::extractContentFromChunk)
//            .filter(content -> !content.isEmpty())
//            .doOnNext(buffer::append)
//            .doOnComplete(() -> postProcessStreaming(roomId, buffer.toString()))
//            .doOnError(e -> log.error("[SSE] Stream error. room={}", roomId, e));
//    }

    /**
     * [Phase 3 - 후처리] 스트림 완료 시 호출
     * 누적된 전체 응답을 JSON 파싱 → DB 저장 → 호감도 반영
     */
    private void postProcessStreaming(Long roomId, String fullResponse) {
        try {
            txTemplate.executeWithoutResult(status -> {
                ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
                    .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

                try {
                    String cleanJson = stripMarkdown(fullResponse);
                    AiJsonOutput aiOutput = objectMapper.readValue(cleanJson, AiJsonOutput.class);

                    // 호감도 반영
                    applyAffectionChange(room, aiOutput.affectionChange());

                    // 대사 합치기 (TTS/히스토리용)
                    String combinedDialogue = aiOutput.scenes().stream()
                        .map(AiJsonOutput.Scene::dialogue)
                        .collect(Collectors.joining(" "));

                    // 마지막 씬의 감정 태그
                    String lastEmotionStr = aiOutput.scenes().isEmpty() ? "NEUTRAL"
                        : aiOutput.scenes().get(aiOutput.scenes().size() - 1).emotion();
                    EmotionTag mainEmotion = parseEmotion(lastEmotionStr);

                    // Assistant 로그 저장
                    saveLog(room, ChatRole.ASSISTANT, cleanJson, combinedDialogue, mainEmotion, null);

                    log.info("[SSE] Post-process complete. room={}, affection={}, emotion={}",
                        roomId, room.getAffectionScore(), mainEmotion);

                } catch (JsonProcessingException e) {
                    log.error("[SSE] JSON Parse Error in post-process. room={}, response={}",
                        roomId, fullResponse.substring(0, Math.min(200, fullResponse.length())), e);
                    // Fallback: 파싱 실패 시 원본 텍스트 그대로 저장 (대화 유실 방지)
                    saveLog(room, ChatRole.ASSISTANT, fullResponse, fullResponse, EmotionTag.NEUTRAL, null);
                }
            });
        } catch (Exception e) {
            log.error("[SSE] Post-processing TX failed. room={}", roomId, e);
        }
    }

    // ──────────────────────────────────────────────
    //  REST (Non-Streaming) 영역 - 기존 유지
    // ──────────────────────────────────────────────

    @Transactional
    public SendChatResponse sendMessage(Long roomId, String userMessage) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

        // 에너지 차감
        room.getUser().consumeEnergy(1);

        // 1. 유저 로그 저장
        ChatLog userLog = ChatLog.user(room, userMessage);
        chatLogRepository.save(userLog);

        // 2. 트리거: 대화가 20턴 단위로 쌓일 때마다 비동기 요약 실행
        long logCount = chatLogRepository.countByRoomId(roomId);
        if (logCount > 0 && logCount % 20 == 0) {
            memoryService.summarizeAndSaveMemory(roomId, room.getUser().getId());
        }

        // 3. 캐릭터 응답 생성 (공통 로직 호출)
        return generateCharacterResponse(room);
    }

    /**
     * 시스템(이벤트) 메시지에 대한 캐릭터 반응 생성
     * NarratorService에서 호출
     */
    @Transactional
    public SendChatResponse generateResponseForSystemEvent(Long roomId, String systemDetail) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("ChatRoom not found: " + roomId));

        // 1. 시스템 로그 저장 (이벤트 내용)
        ChatLog systemLog = ChatLog.system(room, systemDetail);
        chatLogRepository.save(systemLog);

        // 2. 캐릭터 응답 생성 (공통 로직 호출)
        return generateCharacterResponse(room);
    }

    /**
     * 캐릭터 LLM 호출 및 응답 처리 공통 로직 (REST 전용)
     */
    private SendChatResponse generateCharacterResponse(ChatRoom room) {
        // 0. RAG: 장기 기억 회상 (최근 유저 질문 기반)
        String lastUserMessage = chatLogRepository.findTop1ByRoom_IdAndRoleOrderByCreatedAtDesc(room.getId(), ChatRole.USER)
            .map(ChatLog::getCleanContent)
            .orElse("");

        String longTermMemory = "";
        if (!lastUserMessage.isEmpty()) {
            longTermMemory = memoryService.retrieveContext(room.getUser().getId(), lastUserMessage);
        }

        // 1. 프롬프트 조립
        String systemPrompt = promptAssembler.assembleSystemPrompt(
            room.getCharacter(),
            room,
            room.getUser(),
            longTermMemory
        );

        // 2. 메시지 구성 (공통 헬퍼)
        List<OpenAiMessage> messages = buildMessageHistory(room.getId(), systemPrompt);

        // 3. LLM 호출
        String model = props.model();
        log.info("🤖 Sending Request to Model: {}", model);

        String rawAssistant = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.8)
        );

        log.debug("📝 Raw LLM Response: '{}'", rawAssistant);

        // 4. 응답 처리 및 저장
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

            List<SendChatResponse.SceneResponse> sceneResponses = aiOutput.scenes().stream()
                .map(s -> new SendChatResponse.SceneResponse(
                    s.narration(),
                    s.dialogue(),
                    parseEmotion(s.emotion())
                ))
                .collect(Collectors.toList());

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

    // ──────────────────────────────────────────────
    //  채팅방 관리 영역
    // ──────────────────────────────────────────────

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

    // ──────────────────────────────────────────────
    //  공통 헬퍼 메서드
    // ──────────────────────────────────────────────

    /**
     * 최근 대화 로그를 LLM 메시지 포맷으로 변환
     * - 스트리밍/REST 양쪽에서 공통으로 사용
     * - Anti-Hallucination: SYSTEM 로그에 [NARRATION] 태그 부착
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

    /**
     * OpenRouter SSE 청크에서 content 텍스트만 추출
     */
    private String extractContentFromChunk(String chunk) {
        try {
            if (chunk.equals("[DONE]")) return "";
            String jsonStr = chunk.startsWith("data:") ? chunk.substring(5).trim() : chunk;
            if (jsonStr.isEmpty() || jsonStr.equals("[DONE]")) return "";

            JsonNode node = objectMapper.readTree(jsonStr);
            if (node.has("choices") && !node.get("choices").isEmpty()) {
                JsonNode delta = node.get("choices").get(0).get("delta");
                if (delta != null && delta.has("content")) {
                    return delta.get("content").asText();
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
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