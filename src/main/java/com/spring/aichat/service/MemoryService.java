package com.spring.aichat.service;

import com.spring.aichat.domain.chat.ChatLog;
import com.spring.aichat.domain.chat.ChatLogRepository;
import com.spring.aichat.domain.repository.VectorStoreRepository;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.external.EmbeddingClient;
import com.spring.aichat.external.OpenRouterClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MemoryService {

    private final EmbeddingClient embeddingClient;
    private final VectorStoreRepository vectorStoreRepository;
    private final OpenRouterClient openRouterClient; // 요약용 LLM 호출
    private final ChatLogRepository chatLogRepository;

    /**
     * [READ] 현재 대화와 관련된 장기 기억을 검색
     */
    public String retrieveContext(Long userId, String query) {
        try {
            // 1. 임베딩 (Double -> Float 변환)
            List<Double> doubleVector = embeddingClient.embed(query);
            List<Float> floatVector = doubleVector.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

            // 2. 검색 (Top 3)
            List<String> memories = vectorStoreRepository.searchMemories(String.valueOf(userId), floatVector, 3);

            if (memories.isEmpty()) return "";

            // 3. 프롬프트용 텍스트 포맷팅
            return memories.stream()
                .map(m -> "- " + m)
                .collect(Collectors.joining("\n"));

        } catch (Exception e) {
            log.warn("Memory retrieval failed (Non-blocking): {}", e.getMessage());
            return ""; // 기억 회상 실패해도 대화는 계속되어야 함
        }
    }

    /**
     * [WRITE] 지난 대화(20턴)를 요약하여 장기 기억에 저장
     * @Async: 유저 응답 속도에 영향을 주지 않기 위해 비동기 처리
     */
    @Async
    @Transactional(readOnly = true)
    public void summarizeAndSaveMemory(Long roomId, Long userId) {
        log.info("Starting memory summarization for room: {}", roomId);

        // 1. 요약 대상 로드 (최근 20개 ~ 30개)
        List<ChatLog> recentLogs = chatLogRepository.findTop20ByRoom_IdOrderByCreatedAtDesc(roomId);
        // 시간순 정렬 (과거 -> 현재)
        List<ChatLog> sortedLogs = new ArrayList<>(recentLogs);
        sortedLogs.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));

        if (sortedLogs.isEmpty()) return;

        // 2. 요약 프롬프트 생성
        String conversationText = sortedLogs.stream()
            .map(log -> String.format("%s: %s", log.getRole(), log.getCleanContent()))
            .collect(Collectors.joining("\n"));

        String summaryPrompt = """
            Analyze the following conversation between User and AI Character.
            Extract key facts about the User (preferences, name, job, etc.) and significant events.
            
            [Conversation]
            %s
            
            [Output Rule]
            - Summarize in Korean within 3 sentences.
            - Focus on "User's Info" and "Relationship Progress".
            - Ignore small talk (greetings, weather).
            """.formatted(conversationText);

        try {
            // 3. LLM에게 요약 요청 (gpt-4o-mini 또는 저렴한 모델 권장)
            String summary = openRouterClient.chatCompletion(
                new OpenAiChatRequest("gpt-4o-mini", List.of(OpenAiMessage.system(summaryPrompt)), 0.5)
            );

            log.info("Memory Summarized: {}", summary);

            // 4. 임베딩 및 저장
            List<Double> doubleVector = embeddingClient.embed(summary);
            List<Float> floatVector = doubleVector.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

            vectorStoreRepository.saveMemory(String.valueOf(userId), summary, floatVector);

        } catch (Exception e) {
            log.error("Failed to summarize memory", e);
        }
    }
}
