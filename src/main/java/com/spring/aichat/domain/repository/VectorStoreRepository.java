package com.spring.aichat.domain.repository;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.pinecone.clients.Pinecone;
import io.pinecone.clients.Pinecone;
import io.pinecone.clients.Index;
import io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.VectorWithUnsignedIndices;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@Slf4j
public class VectorStoreRepository {

    private Pinecone pinecone;

    @org.springframework.beans.factory.annotation.Value("${spring.ai.pinecone.api-key}")
    private String apiKey;

    @org.springframework.beans.factory.annotation.Value("${spring.ai.pinecone.index-name}")
    private String indexName;

    @PostConstruct
    public void init() {
        this.pinecone = new Pinecone.Builder(apiKey).build();
    }

    /**
     * 기억 저장 (Upsert)
     * @param userId 유저 ID (Namespace 격리용)
     * @param content 요약된 텍스트 내용
     * @param vector 임베딩된 벡터
     */
    public void saveMemory(String userId, String content, List<Float> vector) {
        try {
            Index index = pinecone.getIndexConnection(indexName);

            // Metadata 생성 (텍스트 원본 저장)
            Struct metadata = Struct.newBuilder()
                .putFields("userId", Value.newBuilder().setStringValue(userId).build())
                .putFields("content", Value.newBuilder().setStringValue(content).build())
                .putFields("timestamp", Value.newBuilder().setStringValue(String.valueOf(System.currentTimeMillis())).build())
                .build();

            VectorWithUnsignedIndices vectorRecord = new VectorWithUnsignedIndices(
                UUID.randomUUID().toString(), // Unique ID
                vector,
                metadata,
                null // Sparse values
            );

            index.upsert(
                List.of(vectorRecord),
                "lucid-chat-namespace"
            );

            log.info("Saved memory for user: {}", userId);

        } catch (Exception e) {
            log.error("Pinecone Save Error", e);
            // 저장 실패는 비즈니스 로직을 중단시키지 않음 (로그만 남김)
        }
    }

    /**
     * 기억 검색 (Query)
     */
    public List<String> searchMemories(String userId, List<Float> vector, int topK) {
        try {
            Index index = pinecone.getIndexConnection(indexName);

            // Metadata Filter: 내 기억만 검색
            Struct filter = Struct.newBuilder()
                .putFields("userId", Value.newBuilder().setStringValue(userId).build())
                .build();

            QueryResponseWithUnsignedIndices response = index.queryByVector(
                topK,
                vector,
                "lucid-chat-namespace",
                filter,
                true, // includeValues
                true  // includeMetadata (텍스트 원본 필요)
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