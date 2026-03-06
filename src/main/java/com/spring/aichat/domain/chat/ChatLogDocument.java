package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EmotionTag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * [Phase 5] ChatLog MongoDB Document
 *
 * RDB(JPA)에서 MongoDB로 마이그레이션된 채팅 로그 엔티티.
 *
 * [마이그레이션 근거]
 * - Append-Only: 한 번 저장되면 수정되지 않는 시계열 데이터
 * - 고볼륨: 유저당 수천~수만 건 축적, RDB 테이블 비대화 원인 1위
 * - No JOIN: ChatRoom과의 관계가 roomId 조회로 충분 (FK 불필요)
 * - 시계열 쿼리 특화: {roomId, createdAt} 복합 인덱스로 최적 커버
 *
 * [인덱스 설계]
 * 1. {roomId: 1, createdAt: -1} — 가장 빈번한 쿼리 (최근 로그 조회, 페이지네이션)
 * 2. {roomId: 1, role: 1, createdAt: -1} — 역할별 필터 (메모리 트리거 판단, 마지막 유저 메시지 조회)
 *
 * [기존 JPA ChatLog 대비 변경점]
 * - @Entity → @Document("chat_logs")
 * - Long id → String id (MongoDB ObjectId)
 * - @ManyToOne ChatRoom room → Long roomId (비정규화)
 * - @PrePersist → @CreatedDate (Spring Data Auditing)
 */
@Document(collection = "chat_logs")
@CompoundIndexes({
    @CompoundIndex(name = "idx_room_created", def = "{'roomId': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "idx_room_role_created", def = "{'roomId': 1, 'role': 1, 'createdAt': -1}")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatLogDocument {

    @Id
    private String id;

    @Field("roomId")
    private Long roomId;

    @Field("role")
    private ChatRole role;

    @Field("rawContent")
    private String rawContent;

    @Field("cleanContent")
    private String cleanContent;

    @Field("emotionTag")
    private EmotionTag emotionTag;

    @Field("audioUrl")
    private String audioUrl;

    @CreatedDate
    @Field("createdAt")
    private LocalDateTime createdAt;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Static Factory Methods (기존 ChatLog 인터페이스 호환)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static ChatLogDocument user(Long roomId, String message) {
        return ChatLogDocument.builder()
            .roomId(roomId)
            .role(ChatRole.USER)
            .rawContent(message)
            .cleanContent(message)
            .emotionTag(EmotionTag.NEUTRAL)
            .build();
    }

    public static ChatLogDocument assistant(Long roomId, String raw, String clean, EmotionTag emotionTag) {
        return ChatLogDocument.builder()
            .roomId(roomId)
            .role(ChatRole.ASSISTANT)
            .rawContent(raw)
            .cleanContent(clean)
            .emotionTag(emotionTag)
            .build();
    }

    public static ChatLogDocument system(Long roomId, String introNarration) {
        return ChatLogDocument.builder()
            .roomId(roomId)
            .role(ChatRole.SYSTEM)
            .rawContent(introNarration)
            .cleanContent(introNarration)
            .emotionTag(EmotionTag.NEUTRAL)
            .build();
    }

    public static ChatLogDocument of(Long roomId, ChatRole role, String raw, String clean,
                                     EmotionTag emotion, String audioUrl) {
        return ChatLogDocument.builder()
            .roomId(roomId)
            .role(role)
            .rawContent(raw)
            .cleanContent(clean)
            .emotionTag(emotion)
            .audioUrl(audioUrl)
            .build();
    }
}