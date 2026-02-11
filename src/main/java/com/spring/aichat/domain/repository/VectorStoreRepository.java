package com.spring.aichat.domain.repository;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.VectorWithUnsignedIndices;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pinecone Vector Store Repository
 *
 * [Phase 3 최적화] Index 커넥션 캐싱
 * - 기존: 매 호출마다 pinecone.getIndexConnection() → 커넥션 수립 오버헤드
 * - 개선: @PostConstruct에서 한 번 연결, 이후 재사용
 */
@Repository
@Slf4j
public class VectorStoreRepository {

    private Pinecone pinecone;
    private Index cachedIndex;

    @org.springframework.beans.factory.annotation.Value("${spring.ai.pinecone.api-key}")
    private String apiKey;

    @org.springframework.beans.factory.annotation.Value("${spring.ai.pinecone.index-name}")
    private String indexName;

    @PostConstruct
    public void init() {
        this.pinecone = new Pinecone.Builder(apiKey).build();
        try {
            this.cachedIndex = pinecone.getIndexConnection(indexName);
            log.info("✅ Pinecone Index connection cached: {}", indexName);
        } catch (Exception e) {
            log.warn("⚠️ Pinecone Index connection failed at startup (will retry lazily): {}", e.getMessage());
        }
    }

    /**
     * 캐싱된 Index 커넥션 반환 (lazy fallback 포함)
     */
    private Index getIndex() {
        if (cachedIndex == null) {
            synchronized (this) {
                if (cachedIndex == null) {
                    cachedIndex = pinecone.getIndexConnection(indexName);
                    log.info("✅ Pinecone Index connection established (lazy): {}", indexName);
                }
            }
        }
        return cachedIndex;
    }

    /**
     * 기억 저장 (Upsert)
     * @param userId  유저 ID (Namespace 격리용)
     * @param content 요약된 텍스트 내용
     * @param vector  임베딩된 벡터
     */
    public void saveMemory(String userId, String content, List<Float> vector) {
        try {
            Index index = getIndex();

            Struct metadata = Struct.newBuilder()
                .putFields("userId", Value.newBuilder().setStringValue(userId).build())
                .putFields("content", Value.newBuilder().setStringValue(content).build())
                .putFields("timestamp", Value.newBuilder()
                    .setStringValue(String.valueOf(System.currentTimeMillis())).build())
                .build();

            VectorWithUnsignedIndices vectorRecord = new VectorWithUnsignedIndices(
                UUID.randomUUID().toString(),
                vector,
                metadata,
                null
            );

            index.upsert(
                List.of(vectorRecord),
                "lucid-chat-namespace"
            );

            log.info("Saved memory for user: {}", userId);

        } catch (Exception e) {
            log.error("Pinecone Save Error", e);
            // 저장 실패는 비즈니스 로직을 중단시키지 않음
        }
    }

    /**
     * 기억 검색 (Query)
     */
    public List<String> searchMemories(String userId, List<Float> vector, int topK) {
        try {
            Index index = getIndex();

            Struct filter = Struct.newBuilder()
                .putFields("userId", Value.newBuilder().setStringValue(userId).build())
                .build();

            QueryResponseWithUnsignedIndices response = index.queryByVector(
                topK,
                vector,
                "lucid-chat-namespace",
                filter,
                true,
                true
            );

            return response.getMatchesList().stream()
                .map(match -> match.getMetadata()
                    .getFieldsMap()
                    .get("content")
                    .getStringValue())
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Pinecone Search Error", e);
            return Collections.emptyList();
        }
    }
}