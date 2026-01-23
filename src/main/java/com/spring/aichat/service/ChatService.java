package com.spring.aichat.service;

import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.ChatLog;
import com.spring.aichat.domain.chat.ChatLogRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.affection.UserMessageSavedEvent;
import com.spring.aichat.service.prompt.EmotionParser;
import com.spring.aichat.service.prompt.PromptAssembler;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 채팅 핵심 서비스
 * - 동적 프롬프트 조립(호감도/관계 반영)
 * - 최근 대화 내역 20개를 컨텍스트로 주입
 * - OpenRouter 호출 후 응답 파싱/저장
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogRepository chatLogRepository;
    private final PromptAssembler promptAssembler;
    private final EmotionParser emotionParser;
    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;
    private final ApplicationEventPublisher eventPublisher;

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
        List<ChatLog> recent = chatLogRepository.findTop20ByRoom_IdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLog::getCreatedAt)); // ASC

        // 4) messages 구성: [System] + [History] (+ 이미 userLog가 history 안에 포함됨)
        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.system(systemPrompt));

        for (ChatLog log : recent) {
            if (log.getRole() == ChatRole.USER) {
                messages.add(OpenAiMessage.user(log.getRawContent()));
            } else {
                messages.add(OpenAiMessage.assistant(log.getRawContent()));
            }
        }

        // 5) OpenRouter 호출
        String model = room.getCharacter().getLlmModelName() != null
            ? room.getCharacter().getLlmModelName()
            : props.model();

        String rawAssistant = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.8)
        );

        // 6) 지문 파싱(감정/cleanContent) :contentReference[oaicite:18]{index=18}
        EmotionParser.ParsedEmotion parsed = emotionParser.parse(rawAssistant);

        // 7) assistant 로그 저장
        ChatLog assistantLog = ChatLog.assistant(room, rawAssistant, parsed.cleanContent(), parsed.emotionTag());
        chatLogRepository.save(assistantLog);

        // 8) 방 상태 업데이트
        room.touch(parsed.emotionTag());

        // 9) 비동기 호감도 분석 트리거 :contentReference[oaicite:19]{index=19}
        eventPublisher.publishEvent(new UserMessageSavedEvent(roomId, userMessage));

        // 10) 클라이언트 즉시 반환(clean/emotion)
        return new SendChatResponse(
            rawAssistant,
            parsed.cleanContent(),
            parsed.stageDirection(),
            parsed.emotionTag().name(),
            room.getAffectionScore()
        );
    }
}
