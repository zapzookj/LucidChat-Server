package com.spring.aichat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.ChatLog;
import com.spring.aichat.domain.chat.ChatLogRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.dto.chat.NarratorResponse;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
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
    private final ObjectMapper objectMapper;
    private final ChatService chatService;

    /**
     * 1단계: 이벤트 옵션 생성 (저장 X)
     */
    @Transactional
    public NarratorResponse triggerEvent(Long roomId) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

        // 1. 나레이터 프롬프트 조립
        String systemPrompt = promptAssembler.assembleNarratorPrompt(
            room.getCharacter(), room, room.getUser()
        );

        // 2. 컨텍스트 로딩 (최근 대화 흐름 파악용)
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

        String rawJson = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.9) // 창의성(Temperature)을 높임
        ).trim();

        try {
            // JSON 파싱 (Wrapper DTO 내부적으로 사용하거나, NarratorResponse 구조와 매핑)
            // 여기서는 NarratorResponse가 바로 매핑된다고 가정 (필드명 일치 시)
            // 만약 root가 "options"라면 Wrapper가 필요할 수 있음.
            // 편의상 JsonNode로 읽거나 Wrapper Class를 만드는 것이 정석.
            NarratorOptionsWrapper wrapper = objectMapper.readValue(extractJson(rawJson), NarratorOptionsWrapper.class);

            return new NarratorResponse(wrapper.options(), room.getUser().getEnergy());

        } catch (JsonProcessingException e) {
            log.error("Narrator JSON Parsing Failed: {}", rawJson, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "이벤트 생성 중 오류가 발생했습니다.");
        }
    }

    /**
     * 2단계: 유저가 선택한 이벤트 실행 (저장 O + 캐릭터 반응)
     */
    @Transactional
    public SendChatResponse selectEvent(Long roomId, String selectedDetail, int energyCost) {
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

        // 1. 에너지 차감 (선택 시점에 차감)
        room.getUser().consumeEnergy(energyCost);

        // 2. ChatService를 통해 시스템 로그 저장 및 캐릭터 반응 생성
        return chatService.generateResponseForSystemEvent(roomId, selectedDetail);
    }

    private String extractJson(String text) {
        if (text.startsWith("```json")) return text.substring(7, text.lastIndexOf("```")).trim();
        if (text.startsWith("```")) return text.substring(3, text.lastIndexOf("```")).trim();
        return text;
    }

    // JSON 파싱용 내부 DTO
    private record NarratorOptionsWrapper(List<NarratorResponse.EventOption> options) {}

//    private String cleanResponse(String text) {
//        return text.replace("\"", "").replace("Event:", "").trim();
//    }
}
