package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.ChatRole;
import com.spring.aichat.domain.enums.EmotionTag;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "chat_logs", indexes = {
    @Index(name = "idx_log_room_created", columnList = "room_id, created_at")
})
/**
 * 채팅 로그(대화/연출 기록)
 * - rawContent: 지문 포함 원본
 * - cleanContent: 괄호 지문 제거된 순수 대사 (TTS 대비)
 * - emotionTag: 지문 기반 감정 태그
 */
public class ChatLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatRole role;

    @Lob
    @Column(name = "raw_content", nullable = false)
    private String rawContent;

    @Lob
    @Column(name = "clean_content")
    private String cleanContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "emotion_tag", length = 30)
    private EmotionTag emotionTag;

    @Column(name = "audio_url", length = 500)
    private String audioUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    private ChatLog(ChatRoom room, ChatRole role, String rawContent, String cleanContent, EmotionTag emotionTag) {
        this.room = room;
        this.role = role;
        this.rawContent = rawContent;
        this.cleanContent = cleanContent;
        this.emotionTag = emotionTag;
    }

    public static ChatLog user(ChatRoom room, String message) {
        return new ChatLog(room, ChatRole.USER, message, message, EmotionTag.NEUTRAL);
    }

    public static ChatLog assistant(ChatRoom room, String raw, String clean, EmotionTag emotionTag) {
        return new ChatLog(room, ChatRole.ASSISTANT, raw, clean, emotionTag);
    }
}
