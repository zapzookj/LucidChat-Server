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
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 채팅 핵심 서비스
 * - 동적 프롬프트 조립(호감도/관계 반영)
 * - 최근 대화 내역 20개를 컨텍스트로 주입
 * - OpenRouter 호출 후 응답 파싱/저장
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

    @Transactional
    public SendChatResponse sendMessage(Long roomId, String userMessage) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다. roomId=" + roomId));

        // 에너지 차감
        room.getUser().consumeEnergy(1);

        // 1. 유저 로그 저장
        ChatLog userLog = ChatLog.user(room, userMessage);
        chatLogRepository.save(userLog);

        // 2. 캐릭터 응답 생성 (공통 로직 호출)
        return generateCharacterResponse(room);
    }

    /**
     * [NEW] 시스템(이벤트) 메시지에 대한 캐릭터 반응 생성
     * NarratorService에서 호출함
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
     * [Refactored] 캐릭터 LLM 호출 및 응답 처리 공통 로직
     */
    private SendChatResponse generateCharacterResponse(ChatRoom room) {
        // 1. 프롬프트 조립
        String systemPrompt = promptAssembler.assembleSystemPrompt(
            room.getCharacter(),
            room,
            room.getUser()
        );

        // 2. 히스토리 로딩
        List<ChatLog> recent = chatLogRepository.findTop20ByRoom_IdOrderByCreatedAtDesc(room.getId());
        recent.sort(Comparator.comparing(ChatLog::getCreatedAt));

        // 3. 메시지 구성 (Anti-Hallucination Tagging 적용)
        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.system(systemPrompt));

        for (ChatLog log : recent) {
            if (log.getRole() == ChatRole.USER) {
                messages.add(OpenAiMessage.user(log.getRawContent()));
            } else if (log.getRole() == ChatRole.ASSISTANT) {
                messages.add(OpenAiMessage.assistant(log.getRawContent()));
            } else if (log.getRole() == ChatRole.SYSTEM) {
                // [FIX] 시스템 로그에 태그를 붙여서 캐릭터가 유저 발화로 착각하지 않게 함
                String taggedContent = "[NARRATION]\n" + log.getRawContent();
                messages.add(OpenAiMessage.system(taggedContent));
            }
        }

        // 4. LLM 호출
        String model = room.getCharacter().getLlmModelName() != null
            ? room.getCharacter().getLlmModelName() : props.model();

        String rawAssistant = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.8)
        );

        // 5. 응답 처리 및 저장
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

    @Transactional
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

    // [NEW] 인트로 초기화 메서드
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

    private void applyAffectionChange(ChatRoom room, int change) {
        if (change == 0) return;
        int newScore = room.getAffectionScore() + change;
        // -100 ~ 100 클램핑
        newScore = Math.max(-100, Math.min(100, newScore));
        room.updateAffection(newScore);

        // 관계 단계 업데이트 로직 (필요 시)
        room.updateStatusLevel(RelationStatusPolicy.fromScore(newScore));
    }

    private void saveLog(ChatRoom room, ChatRole role, String raw, String clean, EmotionTag emotion, String audioUrl) {
        ChatLog log = new ChatLog(room, role, raw, clean, emotion, audioUrl);
        chatLogRepository.save(log);

        // 마지막 활동 시간 등 업데이트
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
