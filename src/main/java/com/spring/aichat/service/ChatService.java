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

        // 에너지 차감 (MVP BM 모델)
        room.getUser().consumeEnergy(1);

        // 1) 사용자 입력 저장 :contentReference[oaicite:15]{index=15}
        ChatLog userLog = ChatLog.user(room, userMessage);
        chatLogRepository.save(userLog);

        // 2) 시스템 프롬프트 생성 + 호감도/관계 상태 주입 :contentReference[oaicite:16]{index=16}
        String systemPrompt = promptAssembler.assembleSystemPrompt(
            room.getCharacter(),
            room,
            room.getUser()
        );

        // 3) 최근 대화 20개 로딩(ASC로 정렬) :contentReference[oaicite:17]{index=17}
        // TODO: 캐싱
        List<ChatLog> recent = chatLogRepository.findTop20ByRoom_IdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLog::getCreatedAt)); // ASC

        // 4) messages 구성: [System] + [History] (+ 이미 userLog가 history 안에 포함됨)
        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.system(systemPrompt));

        for (ChatLog log : recent) {
            if (log.getRole() == ChatRole.USER) {
                messages.add(OpenAiMessage.user(log.getRawContent()));
            } else if (log.getRole() == ChatRole.ASSISTANT) {
                messages.add(OpenAiMessage.assistant(log.getRawContent()));
            } else if (log.getRole() == ChatRole.SYSTEM) {
                // [Phase 2] 나레이션/이벤트 로그는 System 메시지로 주입하여 캐릭터가 상황을 인지하게 함
                messages.add(OpenAiMessage.system(log.getRawContent()));
            }
        }

        log.info("[ChatService] roomId={} sending messages: {}", roomId, messages);

        // 5) OpenRouter 호출
        String model = room.getCharacter().getLlmModelName() != null
            ? room.getCharacter().getLlmModelName()
            : props.model();

        String rawAssistant = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.8)
        );

        try {
            // 6. JSON 파싱 및 전처리 (마크다운 제거)
            String cleanJson = stripMarkdown(rawAssistant);
            AiJsonOutput aiOutput = objectMapper.readValue(cleanJson, AiJsonOutput.class);

            // 7. 호감도 반영 (LLM이 판단한 수치 적용)
            applyAffectionChange(room, aiOutput.affectionChange());

            // 8. AI 응답 로그 저장
            // - Raw: JSON 원본
            // - Clean: 대사만 합쳐서 저장 (히스토리 주입용)
            // - Emotion: 마지막 씬의 감정을 대표 감정으로 저장
            String combinedDialogue = aiOutput.scenes().stream()
                .map(AiJsonOutput.Scene::dialogue)
                .collect(Collectors.joining(" "));

            String lastEmotionStr = aiOutput.scenes().isEmpty() ? "NEUTRAL"
                : aiOutput.scenes().get(aiOutput.scenes().size() - 1).emotion();
            EmotionTag mainEmotion = parseEmotion(lastEmotionStr);

            saveLog(room, ChatRole.ASSISTANT, cleanJson, combinedDialogue, mainEmotion, null);

            // 9. 응답 DTO 생성
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
            log.error("JSON Parsing Error. Raw: {}", rawAssistant, e);
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
