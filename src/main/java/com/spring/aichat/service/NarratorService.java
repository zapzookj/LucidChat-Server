package com.spring.aichat.service;

import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.ChatLog;
import com.spring.aichat.domain.chat.ChatLogRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.dto.chat.NarratorResponse;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.prompt.NarratorPromptAssembler;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class NarratorService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogRepository chatLogRepository;
    private final NarratorPromptAssembler promptAssembler;
    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;

    // 이벤트 트리거 비용 (적당히 조절하자. 토큰 많이 먹음)
    private static final int EVENT_ENERGY_COST = 2;

    @Transactional
    public NarratorResponse triggerEvent(Long roomId) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

        // 1. 에너지 차감 (이벤트는 일반 대화보다 비쌀 수 있음)
        room.getUser().consumeEnergy(EVENT_ENERGY_COST);

        // 2. 나레이터 프롬프트 조립
        String systemPrompt = promptAssembler.assembleNarratorPrompt(
            room.getCharacter(), room, room.getUser()
        );

        // 3. 컨텍스트 로딩 (최근 대화 흐름 파악용)
        List<ChatLog> recent = chatLogRepository.findTop20ByRoom_IdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLog::getCreatedAt));

        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.system(systemPrompt));

        for (ChatLog log : recent) {
            // 시스템 로그도 포함하여 문맥 유지
            String roleName = (log.getRole() == ChatRole.USER) ? "User" :
                (log.getRole() == ChatRole.ASSISTANT) ? "Character" : "Narrator";
            messages.add(OpenAiMessage.user(roleName + ": " + log.getRawContent()));
        }

        // 4. LLM 호출 (나레이터 전용)
        // 메인 모델과 다른 sentimentModel 사용
        String model = props.sentimentModel();

        String eventDescription = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.9) // 창의성(Temperature)을 높임
        ).trim();

        // 따옴표 제거나 마크다운 제거 등 간단한 후처리
        eventDescription = cleanResponse(eventDescription);

        // 5. 로그 저장 (Role: SYSTEM)
        // 나레이션은 감정이 없으므로 NEUTRAL
        ChatLog eventLog = new ChatLog(
            room, ChatRole.SYSTEM, eventDescription, eventDescription, EmotionTag.NEUTRAL, null
        );
        chatLogRepository.save(eventLog);

        return new NarratorResponse(eventDescription, room.getUser().getEnergy());
    }

    private String cleanResponse(String text) {
        return text.replace("\"", "").replace("Event:", "").trim();
    }
}
