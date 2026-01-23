package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.character.Character;
import com.spring.aichat.domain.enums.EmotionTag;
import com.spring.aichat.domain.enums.RelationStatus;
import com.spring.aichat.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "chat_rooms",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_member_character", columnNames = {"user_id", "character_id"})
    },
    indexes = {
        @Index(name = "idx_room_member", columnList = "user_id"),
        @Index(name = "idx_room_character", columnList = "character_id")
    })
/**
 * 채팅방(관계/상태 저장의 핵심 엔티티)
 * - affectionScore, statusLevel로 동적 프롬프트를 구성한다.
 */
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    private Character character;

    @Column(name = "affection_score", nullable = false)
    private int affectionScore = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_level", nullable = false, length = 30)
    private RelationStatus statusLevel = RelationStatus.STRANGER;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_emotion", length = 30)
    private EmotionTag lastEmotion;

    public ChatRoom(User user, Character character) {
        this.user = user;
        this.character = character;
        this.affectionScore = 0;
        this.statusLevel = RelationStatus.STRANGER;
        this.lastActiveAt = LocalDateTime.now();
        this.lastEmotion = EmotionTag.NEUTRAL;
    }

    public void touch(EmotionTag lastEmotion) {
        this.lastActiveAt = LocalDateTime.now();
        this.lastEmotion = lastEmotion;
    }

    public void applyAffectionDelta(int delta) {
        this.affectionScore = clamp(0, 100, this.affectionScore + delta);
        this.statusLevel = RelationStatusPolicy.fromScore(this.affectionScore);
    }

    private int clamp(int min, int max, int v) {
        return Math.max(min, Math.min(max, v));
    }
}
