package com.spring.aichat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.chat.ChatLogDocument;
import com.spring.aichat.domain.chat.ChatLogMongoRepository;
import com.spring.aichat.domain.memory.MemorySummary;
import com.spring.aichat.domain.memory.MemorySummaryRepository;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.external.OpenRouterClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * [Phase 5.5-Perf] 장기 기억 서비스 — RDB + Redis 캐싱
 *
 * [Phase 5.5-EV Fix] 모델명 하드코딩 버그 수정
 *   - 기존: "gpt-4o-mini" (OpenRouter에서 400 Bad Request → 조용히 실패)
 *   - 수정: props.sentimentModel() 사용 (OpenRouter 호환 모델명)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemoryService {

    private final MemorySummaryRepository memorySummaryRepository;
    private final OpenRouterClient openRouterClient;
    private final ChatLogMongoRepository chatLogRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final OpenAiProperties props;  // [Fix] 추가 — 모델명 참조용

    private static final String MEMORY_CACHE_PREFIX = "memory:";
    private static final long MEMORY_CACHE_TTL_HOURS = 2;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [READ] 장기 기억 조회 — Redis 우선, RDB 폴백
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public String retrieveContext(Long roomId) {
        long start = System.currentTimeMillis();
        String cacheKey = MEMORY_CACHE_PREFIX + roomId;

        try {
            // 1. Redis 캐시 조회
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                List<String> summaries = objectMapper.readValue(cached, new TypeReference<>() {});
                String result = formatMemories(summaries);
                log.info("⏱️ [MEMORY] Cache HIT: roomId={} | {}ms | memories={}",
                    roomId, System.currentTimeMillis() - start, summaries.size());
                return result;
            }

            // 2. 캐시 미스 → RDB 조회
            List<MemorySummary> memories = memorySummaryRepository.findByRoomIdOrderByCreatedAtAsc(roomId);
            if (memories.isEmpty()) {
                log.info("⏱️ [MEMORY] No memories: roomId={} | {}ms",
                    roomId, System.currentTimeMillis() - start);
                return "";
            }

            // 3. 캐시 워밍
            List<String> summaryTexts = memories.stream()
                .map(MemorySummary::getSummary)
                .collect(Collectors.toList());

            cacheMemories(roomId, summaryTexts);

            String result = formatMemories(summaryTexts);
            log.info("⏱️ [MEMORY] Cache MISS → DB loaded: roomId={} | {}ms | memories={}",
                roomId, System.currentTimeMillis() - start, summaryTexts.size());
            return result;

        } catch (Exception e) {
            log.warn("⏱️ [MEMORY] retrieveContext failed (non-blocking): roomId={} | {}ms | {}",
                roomId, System.currentTimeMillis() - start, e.getMessage());
            return "";
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [WRITE] 장기 기억 생성 — LLM 요약 → RDB 저장 → 캐시 무효화
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Async
    public void summarizeAndSaveMemory(Long roomId, Long userId) {
        long asyncStart = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        log.info("⏱️ [MEMORY-WRITE] START | thread={} | roomId={}", threadName, roomId);

        if (threadName.contains("http-nio") || threadName.contains("tomcat")) {
            log.error("🚨 [MEMORY-WRITE] Running on HTTP thread — @Async NOT working!");
        }

        try {
            // 1. 요약 대상 로드 (MongoDB — 최근 20턴)
            long t1 = System.currentTimeMillis();
            List<ChatLogDocument> recentLogs = chatLogRepository.findTop20ByRoomIdOrderByCreatedAtDesc(roomId);
            List<ChatLogDocument> sortedLogs = new ArrayList<>(recentLogs);
            sortedLogs.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
            log.info("⏱️ [MEMORY-WRITE] [1] Load logs: {}ms | count={}",
                System.currentTimeMillis() - t1, sortedLogs.size());

            if (sortedLogs.isEmpty()) return;

            String conversationText = sortedLogs.stream()
                .map(doc -> String.format("%s: %s", doc.getRole(), doc.getCleanContent()))
                .collect(Collectors.joining("\n"));

            // 2. LLM 요약 생성
            // [Fix] props.sentimentModel() 사용 — 기존 "gpt-4o-mini" 하드코딩은
            //        OpenRouter에서 400 Bad Request를 발생시켜 메모리 저장이 실패했음
            long t2 = System.currentTimeMillis();
            String summaryPrompt = buildSummaryPrompt(conversationText);
            String model = props.sentimentModel();

            log.info("⏱️ [MEMORY-WRITE] [2] LLM summarize START | model={}", model);

            String summary = openRouterClient.chatCompletion(
                OpenAiChatRequest.withoutPenalty(
                    model,
                    List.of(OpenAiMessage.system(summaryPrompt)),
                    0.5
                )
            );
            log.info("⏱️ [MEMORY-WRITE] [2] LLM summarize: {}ms | model={}",
                System.currentTimeMillis() - t2, model);

            // 3. RDB 저장
            long t3 = System.currentTimeMillis();
            int turnNumber = (int) chatLogRepository.countByRoomId(roomId);
            MemorySummary entity = new MemorySummary(roomId, userId, summary.trim(), turnNumber);
            memorySummaryRepository.save(entity);
            log.info("⏱️ [MEMORY-WRITE] [3] RDB save: {}ms", System.currentTimeMillis() - t3);

            // 4. Redis 캐시 무효화 (다음 읽기 시 재캐싱)
            evictMemoryCache(roomId);

            log.info("✅ [MEMORY-WRITE] DONE: {}ms total | roomId={} | model={} | summary='{}'",
                System.currentTimeMillis() - asyncStart, roomId, model,
                summary.substring(0, Math.min(80, summary.length())));

        } catch (Exception e) {
            log.error("❌ [MEMORY-WRITE] FAILED after {}ms | roomId={} | error={}",
                System.currentTimeMillis() - asyncStart, roomId, e.getMessage(), e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  캐시 관리
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Transactional
    public void clearMemories(Long roomId) {
        memorySummaryRepository.deleteByRoomId(roomId);
        evictMemoryCache(roomId);
        log.info("🗑️ [MEMORY] Cleared all memories: roomId={}", roomId);
    }

    public void evictMemoryCache(Long roomId) {
        redisTemplate.delete(MEMORY_CACHE_PREFIX + roomId);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Private Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void cacheMemories(Long roomId, List<String> summaryTexts) {
        try {
            String json = objectMapper.writeValueAsString(summaryTexts);
            redisTemplate.opsForValue().set(
                MEMORY_CACHE_PREFIX + roomId, json,
                MEMORY_CACHE_TTL_HOURS, TimeUnit.HOURS
            );
        } catch (JsonProcessingException e) {
            log.warn("[MEMORY] Cache write failed: roomId={}", roomId, e);
        }
    }

    private String formatMemories(List<String> summaries) {
        if (summaries == null || summaries.isEmpty()) return "";
        return summaries.stream()
            .map(s -> "- " + s)
            .collect(Collectors.joining("\n"));
    }

    private String buildSummaryPrompt(String conversationText) {
        return """
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
    }
}