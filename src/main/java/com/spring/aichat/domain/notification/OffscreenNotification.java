package com.spring.aichat.domain.notification;

import com.spring.aichat.domain.chat.ChatRoom;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [V2 Story] 오프스크린 캐릭터 알림
 *
 * <p>같은 공간에 없는 캐릭터가 *유저에게 흔적을 남기는* 시스템. 모바일 메신저
 * 인터페이스가 아닌 *세계관 톤 맞춤 알림* (편지/꿈결/마법통신/메신저).
 *
 * <p>[트리거 듀얼 채널]
 * 1. *디렉터 자율*: 응답 JSON {@code incoming_messages} 필드에 캐릭터가 자유 생성.
 * 2. *백엔드 가드*: 친밀도 ≥ 30 + 마지막 만남 후 World 4시간 이상 + 같은 캐릭터
 *    24시간 쿨다운 + 현재 대화의 무게가 가벼울 때만 노출.
 *
 * <p>[노출 타이밍]
 * - {@code ChatRoom.topicConcluded=true} 직후
 * - 또는 유저가 다음 메시지 입력 전 *대기 phase*
 * - 그 외 시점에는 큐에 적재만, 노출 X
 *
 * <p>[세계관 톤 매칭]
 * 본 엔티티는 *순수 내용*만 저장. UI 렌더링 시 {@code World} 톤에 맞춰 wrapping:
 *   - MODERN_KOREA       → "메시지가 도착했다"
 *   - MEDIEVAL_FANTASY   → "○○로부터 편지가 도착했다"
 *   - ORIENTAL_FANTASY   → "꿈결에 ○○의 목소리가 들렸다"
 *   - FANTASY_ACADEMY    → "○○에게서 마법 통신이 왔다"
 *
 * <p>[답하는 방식 = 그 캐릭터를 찾아가기]
 * 본 엔티티에 직접 답장하는 UI 없음. 유저가 24시간 안에 해당 캐릭터와 같은 공간이
 * 되면 디렉터 prompt에 *"○○로부터 받은 알림에 아직 답하지 않음"* 컨텍스트가
 * 자동 주입되어, 디렉터가 자연스럽게 그 화제를 꺼낸다.
 * 24시간 경과 시: {@code expires_at} 도달 → 폐기 + 친밀도 -1 (선택적 페널티).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "offscreen_notifications",
    indexes = {
        @Index(name = "idx_notification_room_active",
            columnList = "chat_room_id, read_at, expires_at"),
        @Index(name = "idx_notification_room_char",
            columnList = "chat_room_id, from_character_id")
    })
public class OffscreenNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    /** 발신 캐릭터 ID. */
    @Column(name = "from_character_id", nullable = false)
    private Long fromCharacterId;

    /** 알림 본문 (디렉터가 자유 생성). 세계관 톤 wrapping은 UI에서 처리. */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 발신 시점 — World 안의 시간 ({@code chatRoom.currentDayPart} + {@code currentDay}).
     * UI 표시용으로 "1일차 저녁" 같은 포맷 가능.
     */
    @Column(name = "world_day", nullable = false)
    private int worldDay;

    @Column(name = "world_day_part", nullable = false, length = 20)
    private String worldDayPart;

    /** 실제 발신 시점 (서버 시간). */
    @Column(name = "sent_at", nullable = false, updatable = false)
    private LocalDateTime sentAt;

    /**
     * 유저가 화면에서 확인한 시점. nullable — 미확인 상태.
     * "확인"은 *알림 토스트를 닫음*을 의미. 답장은 별개(그 캐릭터를 만나야).
     */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /**
     * 유저가 *해당 캐릭터와 만났을 때 알림이 자연스럽게 언급된 시점*.
     * 디렉터 prompt가 미응답 알림 컨텍스트를 *소비*했을 때 갱신.
     */
    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    /**
     * 폐기 시한 (24시간 후). 도달 시 미응답이면 자동 페널티 적용 후 row hidden.
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    void prePersist() {
        if (this.sentAt == null) {
            this.sentAt = LocalDateTime.now();
        }
        if (this.expiresAt == null) {
            this.expiresAt = this.sentAt.plusHours(24);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static OffscreenNotification create(ChatRoom chatRoom, Long fromCharacterId,
                                               String content, int worldDay, String worldDayPart) {
        OffscreenNotification n = new OffscreenNotification();
        n.chatRoom = chatRoom;
        n.fromCharacterId = fromCharacterId;
        n.content = content;
        n.worldDay = worldDay;
        n.worldDayPart = worldDayPart;
        return n;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  상태 전이
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void markRead() {
        if (this.readAt == null) {
            this.readAt = LocalDateTime.now();
        }
    }

    public void markResponded() {
        if (this.respondedAt == null) {
            this.respondedAt = LocalDateTime.now();
        }
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }

    public boolean isUnresponded() {
        return this.respondedAt == null;
    }
}