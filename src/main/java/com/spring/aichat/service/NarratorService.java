package com.spring.aichat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.ChatLog;
import com.spring.aichat.domain.chat.ChatLogRepository;
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
 * [Phase 3 최적화]
 * - @Transactional 제거: LLM 호출 중 DB 커넥션 점유 방지
 * - triggerEvent: 읽기 전용 → EntityGraph가 즉시로딩하므로 TX 불필요
 * - selectEvent: 에너지 차감을 ChatService.generateResponseForSystemEvent에 위임
 */
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
     * @Transactional 제거 — EntityGraph로 즉시 로딩, LLM 호출 중 DB 미점유
     */
    public NarratorResponse triggerEvent(Long roomId) {
        long totalStart = System.currentTimeMillis();

        // DB 조회 (EntityGraph → user, character 즉시 로딩)
        ChatRoom room = chatRoomRepository.findWithMemberAndCharacterById(roomId)
            .orElseThrow(() -> new NotFoundException("채팅방이 존재하지 않습니다."));

        // 나레이터 프롬프트 조립
        String systemPrompt = promptAssembler.assembleNarratorPrompt(
            room.getCharacter(), room, room.getUser()
        );

        // 컨텍스트 로딩 (최근 대화 흐름 파악용)
        List<ChatLog> recent = chatLogRepository.findTop20ByRoom_IdOrderByCreatedAtDesc(roomId);
        recent.sort(Comparator.comparing(ChatLog::getCreatedAt));

        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.system(systemPrompt));

        for (ChatLog chatLog : recent) {
            String roleName = switch (chatLog.getRole()) {
                case USER -> "User";
                case ASSISTANT -> "Character";
                case SYSTEM -> "Narrator";
            };
            messages.add(OpenAiMessage.user(roleName + ": " + chatLog.getRawContent()));
        }

        // LLM 호출 (DB 커넥션 미점유 상태)
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
     * 2단계: 유저가 선택한 이벤트 실행 (저장 O + 캐릭터 반응)
     * @Transactional 제거 — ChatService 내부에서 TX 분리 처리
     */
    public SendChatResponse selectEvent(Long roomId, String selectedDetail, int energyCost) {
        // 에너지 차감 + 시스템 로그 저장 + 캐릭터 반응 생성 모두 ChatService에 위임
        return chatService.generateResponseForSystemEvent(roomId, selectedDetail, energyCost);
    }

    private String extractJson(String text) {
        if (text.startsWith("```json")) return text.substring(7, text.lastIndexOf("```")).trim();
        if (text.startsWith("```")) return text.substring(3, text.lastIndexOf("```")).trim();
        return text;
    }

    // JSON 파싱용 내부 DTO
    private record NarratorOptionsWrapper(List<NarratorResponse.EventOption> options) {}
}