package com.spring.aichat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.dto.chat.NarratorResponse;
import com.spring.aichat.dto.chat.SendChatResponse;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.external.OpenRouterClient;
import com.spring.aichat.service.prompt.NarratorPromptAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 나레이터(이벤트) 서비스
 *
 * [Phase 5] MongoDB 마이그레이션: ChatLog → ChatLogDocument
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NarratorService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatLogMongoRepository chatLogRepository;
    private final NarratorPromptAssembler promptAssembler;
    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;
    private final ObjectMapper objectMapper;
    private final ChatService chatService;

    /**
     * 1단계: 이벤트 옵션 생성 (저장 X)
     */
    public NarratorResponse triggerEvent(Long roomId) {
        long totalStart = System.currentTimeMillis();

        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

        String systemPrompt = promptAssembler.assembleNarratorPrompt(
            room.getCharacter(), room, room.getUser()
        );

        List<ChatLogDocument> recent = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLogDocument::getCreatedAt));

        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.system(systemPrompt));

        for (ChatLogDocument doc : recent) {
            String roleName = switch (doc.getRole()) {
                case USER -> "User";
                case ASSISTANT -> "Character";
                case SYSTEM -> "Narrator";
            };
            messages.add(OpenAiMessage.user(roleName + ": " + doc.getRawContent()));
        }

        String model = props.sentimentModel();
        long llmStart = System.currentTimeMillis();

        String rawJson = openRouterClient.chatCompletion(
            new OpenAiChatRequest(model, messages, 0.9)
        ).trim();

        log.info("⏱️ [PERF] Narrator LLM: {}ms | model={}", System.currentTimeMillis() - llmStart, model);

        try {
            NarratorOptionsWrapper wrapper = objectMapper.readValue(
                extractJson(rawJson), NarratorOptionsWrapper.class);

            log.info("⏱️ [PERF] triggerEvent DONE: {}ms", System.currentTimeMillis() - totalStart);

            return new NarratorResponse(wrapper.options(), room.getUser().getEnergy());

        } catch (JsonProcessingException e) {
            log.error("Narrator JSON Parsing Failed: {}", rawJson, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "이벤트 생성 중 오류가 발생했습니다.");
        }
    }

    /**
     * 2단계: 유저가 선택한 이벤트 실행
     */
    public SendChatResponse selectEvent(Long roomId, String selectedDetail, int energyCost) {
        return chatService.generateResponseForSystemEvent(roomId, selectedDetail, energyCost);
    }

    private String extractJson(String text) {
        if (text.startsWith("```json")) return text.substring(7, text.lastIndexOf("```")).trim();
        if (text.startsWith("```")) return text.substring(3, text.lastIndexOf("```")).trim();
        return text;
    }

    private record NarratorOptionsWrapper(List<NarratorResponse.EventOption> options) {}
}