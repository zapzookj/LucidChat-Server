package com.spring.aichat.service;

import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.repository.VectorStoreRepository;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.external.EmbeddingClient;
import com.spring.aichat.external.OpenRouterClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 장기 기억 서비스 (RAG)
 *
 * [Phase 5] MongoDB 마이그레이션:
 * - ChatLogRepository(JPA) → ChatLogMongoRepository
 * - ChatLog → ChatLogDocument
 * - @Transactional(readOnly) 제거 — MongoDB는 JPA 트랜잭션과 무관
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemoryService {

    private final EmbeddingClient embeddingClient;
    private final VectorStoreRepository vectorStoreRepository;
    private final OpenRouterClient openRouterClient;
    private final ChatLogMongoRepository chatLogRepository;

    /**
     * [READ] 현재 대화와 관련된 장기 기억을 검색
     */
    public String retrieveContext(Long userId, String query) {
        long totalStart = System.currentTimeMillis();
        try {
            long tEmbed = System.currentTimeMillis();
            List<Double> doubleVector = embeddingClient.embed(query);
            List<Float> floatVector = doubleVector.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());
            log.info("⏱️ [RAG-TIMING] [RAG-1] Embedding API: {}ms | vectorDim={}",
                System.currentTimeMillis() - tEmbed, floatVector.size());

            long tSearch = System.currentTimeMillis();
            List<String> memories = vectorStoreRepository.searchMemories(
                String.valueOf(userId), floatVector, 3
            );
            log.info("⏱️ [RAG-TIMING] [RAG-2] Pinecone search: {}ms | resultsFound={}",
                System.currentTimeMillis() - tSearch, memories.size());

            if (memories.isEmpty()) {
                log.info("⏱️ [RAG-TIMING] Total retrieveContext: {}ms (no memories)",
                    System.currentTimeMillis() - totalStart);
                return "";
            }

            String result = memories.stream()
                .map(m -> "- " + m)
                .collect(Collectors.joining("\n"));

            log.info("⏱️ [RAG-TIMING] Total retrieveContext: {}ms",
                System.currentTimeMillis() - totalStart);
            return result;

        } catch (Exception e) {
            log.warn("⏱️ [RAG-TIMING] retrieveContext FAILED after {}ms: {}",
                System.currentTimeMillis() - totalStart, e.getMessage());
            return "";
        }
    }

    /**
     * [WRITE] 지난 대화(20턴)를 요약하여 장기 기억에 저장
     *
     * [Phase 5 변경] @Transactional(readOnly) 제거
     * MongoDB 읽기는 JPA 트랜잭션 매니저 범위 밖이므로 불필요.
     */
    @Async
    public void summarizeAndSaveMemory(Long roomId, Long userId) {
        long asyncStart = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        log.info("⏱️ [ASYNC-TIMING] summarizeAndSaveMemory START | thread={} | roomId={}",
            threadName, roomId);

        if (threadName.contains("http-nio") || threadName.contains("tomcat")) {
            log.error("🚨 [ASYNC-TIMING] WARNING: summarizeAndSaveMemory is running on HTTP thread! " +
                "@Async is NOT working! thread={}", threadName);
        }

        // 1. 요약 대상 로드 (MongoDB)
        long t1 = System.currentTimeMillis();
        List<ChatLogDocument> recentLogs = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
        List<ChatLogDocument> sortedLogs = new ArrayList<>(recentLogs);
        sortedLogs.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
        log.info("⏱️ [ASYNC-TIMING] [A1] Load logs: {}ms | logCount={}",
            System.currentTimeMillis() - t1, sortedLogs.size());

        if (sortedLogs.isEmpty()) return;

        String conversationText = sortedLogs.stream()
            .map(doc -> String.format("%s: %s", doc.getRole(), doc.getCleanContent()))
            .collect(Collectors.joining("\n"));

        String summaryPrompt = """
            Analyze the following conversation between User and AI Character.
            Extract key facts about the User (preferences, name, job, etc.) and significant events.
            
            [Conversation]
            %s
            
            [Time]
            %s
            
            [Output Rule]
            - Summarize in Korean within 3 sentences.
            - Focus on "User's Info" and "Relationship Progress".
            - Ignore small talk (greetings, weather).
            - Write the date (time) of that memory.
            """.formatted(conversationText, LocalDateTime.now().toString());

        try {
            long t2 = System.currentTimeMillis();
            String summary = openRouterClient.chatCompletion(
                new OpenAiChatRequest("gpt-4o-mini", List.of(OpenAiMessage.system(summaryPrompt)), 0.5)
            );
            log.info("⏱️ [ASYNC-TIMING] [A2] LLM summarize: {}ms", System.currentTimeMillis() - t2);

            long t3 = System.currentTimeMillis();
            List<Double> doubleVector = embeddingClient.embed(summary);
            List<Float> floatVector = doubleVector.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());
            log.info("⏱️ [ASYNC-TIMING] [A3] Embedding: {}ms", System.currentTimeMillis() - t3);

            long t4 = System.currentTimeMillis();
            vectorStoreRepository.saveMemory(String.valueOf(userId), summary, floatVector);
            log.info("⏱️ [ASYNC-TIMING] [A4] Pinecone save: {}ms", System.currentTimeMillis() - t4);

            log.info("⏱️ [ASYNC-TIMING] summarizeAndSaveMemory DONE: {}ms total",
                System.currentTimeMillis() - asyncStart);

        } catch (Exception e) {
            log.error("⏱️ [ASYNC-TIMING] summarizeAndSaveMemory FAILED after {}ms",
                System.currentTimeMillis() - asyncStart, e);
        }
    }
}