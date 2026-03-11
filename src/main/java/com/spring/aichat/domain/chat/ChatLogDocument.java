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
 * 3. {rating: 1, createdAt: -1} — [Phase 5.2] RLHF 데이터 ETL용 (평가된 문서만 효율적 추출)
 *
 * [Phase 5.1] rating 필드 추가 — RLHF 데이터 수집용
 * [Phase 5.2] dislikeReason 필드 추가 — 싫어요 사유 카테고리
 * [Phase 5.5-IT] innerThought + thoughtUnlocked 필드 추가 — 속마음 시스템
 */
@Document(collection = "chat_logs")
@CompoundIndexes({
    @CompoundIndex(name = "idx_room_created", def = "{'roomId': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "idx_room_role_created", def = "{'roomId': 1, 'role': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "idx_rating_created", def = "{'rating': 1, 'createdAt': -1}")
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

    /**
     * [Phase 5.1] RLHF 유저 평가
     * - "LIKE": 좋아요
     * - "DISLIKE": 싫어요
     * - null: 미평가
     *
     * ASSISTANT 메시지에만 적용. USER/SYSTEM 메시지에는 항상 null.
     */
    @Field("rating")
    private String rating;

    /**
     * [Phase 5.2] 싫어요 사유 카테고리
     * rating == "DISLIKE"일 때만 유효.
     *
     * 값: OOC | HALLUCINATION | BORING | REPETITIVE | CONTEXT_MISMATCH | OTHER | null
     */
    @Field("dislikeReason")
    private String dislikeReason;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-IT] 속마음 시스템
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 캐릭터의 속마음 텍스트 (LLM inner_thought 필드에서 추출)
     * - ASSISTANT 메시지에만 존재 가능
     * - LLM이 "겉과 속이 다를 때만" 생성하므로 대부분 null
     * - 보안: 프론트에 전달 시 해금(thoughtUnlocked=true) 전까지 텍스트 은닉
     */
    @Field("innerThought")
    private String innerThought;

    /**
     * 속마음 해금 여부
     * - false: 유저 미해금 (프론트에 hasInnerThought=true 플래그만 전달)
     * - true: 유저가 에너지 소모하여 해금 완료 (실제 텍스트 전달)
     */
    @Field("thoughtUnlocked")
    @Builder.Default
    private boolean thoughtUnlocked = false;

    @CreatedDate
    @Field("createdAt")
    private LocalDateTime createdAt;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.1] Rating 업데이트
    //  [Phase 5.2] DislikeReason 업데이트
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 평가 업데이트 (토글 가능: 같은 값 재전송 → null로 해제)
     */
    public void updateRating(String newRating) {
        if (newRating != null && newRating.equals(this.rating)) {
            this.rating = null;
        } else {
            this.rating = newRating;
        }
    }

    /**
     * [Phase 5.2] 싫어요 사유 업데이트
     */
    public void updateDislikeReason(String reason) {
        this.dislikeReason = reason;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  [Phase 5.5-IT] 속마음 해금
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 속마음 존재 여부 (텍스트가 있는지)
     */
    public boolean hasInnerThought() {
        return this.innerThought != null && !this.innerThought.isBlank();
    }

    /**
     * 속마음 해금 처리
     */
    public void unlockThought() {
        this.thoughtUnlocked = true;
    }

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

    /**
     * [Phase 5.5-IT] 속마음 포함 ASSISTANT 메시지 팩토리
     */
    public static ChatLogDocument assistantWithThought(Long roomId, String raw, String clean,
                                                       EmotionTag emotion, String audioUrl,
                                                       String innerThought) {
        return ChatLogDocument.builder()
            .roomId(roomId)
            .role(ChatRole.ASSISTANT)
            .rawContent(raw)
            .cleanContent(clean)
            .emotionTag(emotion)
            .audioUrl(audioUrl)
            .innerThought(innerThought)
            .thoughtUnlocked(false)
            .build();
    }
}