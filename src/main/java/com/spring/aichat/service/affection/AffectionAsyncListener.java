package com.spring.aichat.service.affection;

import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.ChatRoom;
import com.spring.aichat.domain.chat.ChatRoomRepository;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.external.OpenRouterClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 비동기 호감도 분석 Listener
 * - userMessage를 Sentiment 모델에 보내서 -5~+5 점수 획득
 * - ChatRoom.affectionScore 업데이트 및 관계 자동 변경
 */
@Component
@RequiredArgsConstructor
public class AffectionAsyncListener {

    private final OpenRouterClient openRouterClient;
    private final OpenAiProperties props;
    private final ChatRoomRepository chatRoomRepository;

    @Async
    @EventListener
    @Transactional
    public void onUserMessageSaved(UserMessageSavedEvent event) {
        ChatRoom room = chatRoomRepository.findById(event.roomId())
            .orElse(null);

        if (room == null) return;

        String prompt = """
                Analyze the user's message ranges from -5 to +5 regarding affection towards the character.
                Just output the number.
                """;

        String result = openRouterClient.chatCompletion(
            new OpenAiChatRequest(
                props.sentimentModel(),
                List.of(
                    OpenAiMessage.system(prompt),
                    OpenAiMessage.user(event.userMessage())
                ),
                0.0
            )
        );

        int delta = parseDelta(result);
        room.applyAffectionDelta(delta);
    }

    private int parseDelta(String raw) {
        if (raw == null) return 0;
        String cleaned = raw.trim().replaceAll("[^0-9+\\-]", "");
        if (cleaned.isBlank()) return 0;

        try {
            int v = Integer.parseInt(cleaned);
            return Math.max(-5, Math.min(5, v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
