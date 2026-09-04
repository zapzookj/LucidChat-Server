package com.spring.aichat.service.story;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.aichat.config.OpenAiProperties;
import com.spring.aichat.domain.memory.HeroineMemorySummary;
import com.spring.aichat.domain.memory.HeroineMemorySummaryRepository;
import com.spring.aichat.dto.openai.OpenAiChatRequest;
import com.spring.aichat.dto.openai.OpenAiMessage;
import com.spring.aichat.external.OpenRouterClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * [V2 Story + Theater 결함 A 통합] 캐릭터별 누적 메모리 서비스
 *
 * <p>기존 {@link com.spring.aichat.service.MemoryService}와 *병행 운영*:
 * <pre>
 *   MemoryService          — room 단위 메모리 (V2에서 World-level 메모리로 의미 재정의)
 *   HeroineMemoryService   — (room, character) 단위 메모리 — 본 서비스
 * </pre>
 *
 * <p>패턴은 MemoryService와 거의 동일 — RDB + Redis 캐싱 read-through, @Async 압축.
 * 캐시 키만 {@code heroine_memory:{roomId}:{characterId}} 형태로 분리.
 *
 * <p>[갱신 정책]
 * 매 디렉터 응답의 {@code memory_delta.by_character} 필드에서 캐릭터별 1줄 누적.
 * in-memory 누적 후 N=10 메시지마다 LLM 압축 → 본 서비스의 {@code summarizeAndSave} 호출.
 * 정확한 트리거는 {@code ChatStreamService}에서 결정.
 *
 * <p>[Theater 결함 A 호환]
 * Theater 측에서도 본 Repository 사용 가능. {@code sourceMode}로 구분되어 동일 저장소 공유.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HeroineMemoryService {

    private final HeroineMemorySummaryRepository memoryRepository;
    private final OpenRouterClient openRouterClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final OpenAiProperties props;

    private static final String CACHE_PREFIX = "heroine_memory:";

    private static String cacheKey(Long roomId, Long characterId) {
        return CACHE_PREFIX + roomId + ":" + characterId;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [READ] 캐릭터별 누적 메모리 조회 — Redis 우선, RDB 폴백
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * @return 캐릭터의 누적 메모리 요약 텍스트 (이미 포맷팅된 prompt 주입용 문자열). 없으면 빈 문자열.
     */
    public String retrieveContext(Long roomId, Long characterId) {
        long start = System.currentTimeMillis();
        String key = cacheKey(roomId, characterId);

        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                List<String> summaries = objectMapper.readValue(cached, new TypeReference<>() {});
                String result = formatMemories(summaries);
                log.debug("⏱️ [H-MEMORY] Cache HIT: room={}, char={} | {}ms",
                    roomId, characterId, System.currentTimeMillis() - start);
                return result;
            }

            List<HeroineMemorySummary> rows = memoryRepository
                .findByRoomIdAndCharacterIdOrderByCreatedAtAsc(roomId, characterId);
            if (rows.isEmpty()) {
                log.debug("⏱️ [H-MEMORY] No memories: room={}, char={} | {}ms",
                    roomId, characterId, System.currentTimeMillis() - start);
                return "";
            }

            List<String> texts = rows.stream().map(HeroineMemorySummary::getSummary).collect(Collectors.toList());
            cacheTexts(key, texts);

            String result = formatMemories(texts);
            log.debug("⏱️ [H-MEMORY] DB load + cache: room={}, char={} | {}ms | n={}",
                roomId, characterId, System.currentTimeMillis() - start, texts.size());
            return result;
        } catch (Exception e) {
            log.warn("⏱️ [H-MEMORY] retrieve failed (non-blocking): room={}, char={} | {}",
                roomId, characterId, e.getMessage());
            return "";
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [WRITE] LLM 요약 → RDB 저장 → 캐시 무효화
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 매 N=10 메시지마다 호출. ChatStreamService가 in-memory에 누적한 1줄 요약들을
     * LLM으로 한 번 더 압축한 뒤 본 테이블에 영속.
     *
     * @param recentDeltas in-memory 누적 1줄 요약들 (대략 10개)
     * @param sourceMode   "STORY_V2" 또는 "THEATER"
     */
    @Async
    public void summarizeAndSave(Long roomId, Long characterId, Long userId,
                                 List<String> recentDeltas, int turnNumber, String sourceMode) {
        if (recentDeltas == null || recentDeltas.isEmpty()) return;

        long start = System.currentTimeMillis();
        try {
            String joined = recentDeltas.stream()
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n- ", "- ", ""));

            String summaryPrompt = buildSummaryPrompt(joined);
            String model = props.sentimentModel();

            String summary = openRouterClient.chatCompletion(
                OpenAiChatRequest.withoutPenalty(
                    model,
                    List.of(OpenAiMessage.system(summaryPrompt)),
                    0.5
                )
            );

            HeroineMemorySummary entity;
            if ("THEATER".equalsIgnoreCase(sourceMode)) {
                entity = HeroineMemorySummary.forTheater(roomId, characterId, userId, summary.trim(), turnNumber);
            } else {
                entity = HeroineMemorySummary.forStory(roomId, characterId, userId, summary.trim(), turnNumber);
            }
            memoryRepository.save(entity);

            evictCache(roomId, characterId);

            log.info("✅ [H-MEMORY-WRITE] DONE: {}ms | room={}, char={}, mode={}, summary='{}'",
                System.currentTimeMillis() - start, roomId, characterId, sourceMode,
                summary.substring(0, Math.min(60, summary.length())));
        } catch (Exception e) {
            log.error("❌ [H-MEMORY-WRITE] FAILED: room={}, char={} | {}",
                roomId, characterId, e.getMessage(), e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [DELETE] 스토리 초기화 시
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** 방 전체 캐릭터의 모든 메모리 삭제 + 캐시 무효화 (캐시는 prefix 패턴이라 일괄 삭제). */
    @Transactional
    public void clearMemoriesForRoom(Long roomId) {
        clearMemoriesForRoom(roomId, java.util.List.of());
    }

    /**
     * [E-4.6] 방 메모리 전체 삭제 + <b>캐릭터별 Redis 캐시 즉시 무효화</b>.
     *
     * <p>종전 주석은 "다음 read 시 자연 무효화 (deleteByRoomId 이후 cache miss → empty)"였고 <b>그게 거짓이었다</b> —
     * 캐시에는 옛 값이 그대로 남아 있으므로 read는 miss가 아니라 <b>hit</b>이다. 결과적으로 리셋 직후
     * 첫 턴부터 캐시 TTL이 다 될 때까지 이전 회차 기억이 계속 프롬프트에 주입됐다.
     * 유저 눈에는 "리셋했는데 캐릭터가 기억한다"로 보인다.
     *
     * <p>SCAN 없이 정확히 지운다: 삭제 <b>전에</b> 메모리 행에서 characterId를 뽑고,
     * 호출자가 아는 캐스트({@code extraCharacterIds})와 합집합으로 evict한다.
     * 행이 이미 정리됐는데 캐시만 남은 (room, char) 조합은 행만으로는 못 잡기 때문에 합집합이 필요하다.
     */
    @Transactional
    public void clearMemoriesForRoom(Long roomId, java.util.Collection<Long> extraCharacterIds) {
        java.util.Set<Long> targets = new java.util.HashSet<>();
        memoryRepository.findByRoomIdOrderByCreatedAtAsc(roomId)
            .forEach(m -> targets.add(m.getCharacterId()));
        if (extraCharacterIds != null) targets.addAll(extraCharacterIds);

        memoryRepository.deleteByRoomId(roomId);
        targets.forEach(cid -> evictCache(roomId, cid));

        log.info("🗑️ [H-MEMORY] Cleared room memories: roomId={}, evicted={}", roomId, targets.size());
    }

    /** 특정 캐릭터만 메모리 삭제 — 향후 *그 캐릭터만 기억 초기화* 기능 대비. */
    @Transactional
    public void clearMemoriesForCharacter(Long roomId, Long characterId) {
        memoryRepository.deleteByRoomIdAndCharacterId(roomId, characterId);
        evictCache(roomId, characterId);
        log.info("🗑️ [H-MEMORY] Cleared character memories: room={}, char={}", roomId, characterId);
    }

    public void evictCache(Long roomId, Long characterId) {
        redisTemplate.delete(cacheKey(roomId, characterId));
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  내부 유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void cacheTexts(String key, List<String> texts) {
        try {
            String json = objectMapper.writeValueAsString(texts);
            redisTemplate.opsForValue().set(key, json, java.time.Duration.ofHours(2));
        } catch (Exception e) {
            log.warn("Cache write failed: key={} | {}", key, e.getMessage());
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
            아래는 한 캐릭터 시점에서 최근 일어난 사건들의 1줄 요약 목록이다.
            이를 *그 캐릭터의 시점에서* 1~3 문장의 짧은 요약으로 압축하라.

            압축 원칙:
            - 그 캐릭터의 감정·인식·관계 변화 중심
            - 유저와의 *공유 경험*만 (다른 캐릭터의 일은 제외)
            - 시간 순서 보존
            - 한국어로 출력 (요약문만, 다른 설명 금지)

            사건 목록:
            %s

            요약:
            """.formatted(conversationText);
    }
}