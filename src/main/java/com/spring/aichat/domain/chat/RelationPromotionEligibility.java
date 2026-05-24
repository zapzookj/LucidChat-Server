package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.RelationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [V2 Story] 관계 승급 자격 게이트 (LLM 자율 발동 패턴)
 *
 * <p>V1의 *시스템 강제 임무* 패턴(promotion mood score 누적 게임)을 폐기하고,
 * Q-9 엔딩 트리거와 동일한 *이중 게이트* 패턴으로 변경.
 *
 * <pre>
 *   [1단계 자격 활성] 호감도 임계값 도달 → 백엔드가 자동으로 행 생성 (eligible_since 기록)
 *                  → ChatRoom 다음 턴부터 디렉터 prompt에 *자격 활성 신호* 인젝션
 *   [2단계 LLM 발동] 디렉터가 *자연스러운 순간*(낭만적 분위기, 깊은 대화 등)에
 *                  응답 JSON `relation_transition` 필드로 발동
 *                  → 백엔드 가드: 자격 없으면 무시 + 로그
 *                  → 발동 시 triggered=true, triggered_at 기록, ChatRoomHeroine.statusLevel 갱신
 * </pre>
 *
 * <p>[페일세이프]
 * 자격 활성 후 일정 임계(turns 또는 days) 경과해도 발동 없으면, 백엔드가
 * 디렉터 prompt에 *강제 권유* 문구 추가. 무한 지연 방지.
 *
 * <p>[유니크 제약]
 * {@code (chat_room_id, character_id, next_level)} unique — 같은 단계에 대한 자격은
 * 한 번만 활성. 발동 후에는 row 보존(triggered=true)으로 *이력*까지 남는다.
 *
 * <p>[자격 임계값]
 * V1의 {@code RelationStatusPolicy.getThresholdScore(target)} 재사용 가능. 변동성을
 * 위해 정책은 코드(상수)에 두고 이 테이블은 *발동 상태*만 추적.
 *
 * <p>[V2 ChatRoomHeroine과의 관계]
 * 실제 *현재 단계*({@link com.spring.aichat.domain.heroine.ChatRoomHeroine#statusLevel})는
 * {@code ChatRoomHeroine}에 저장. 이 테이블은 *전환 자격/발동 이력*만.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "relation_promotion_eligibilities",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_promotion_eligibility",
            columnNames = {"chat_room_id", "character_id", "next_level"})
    },
    indexes = {
        @Index(name = "idx_promotion_eligibility_room",
            columnList = "chat_room_id, triggered, eligible_since")
    })
public class RelationPromotionEligibility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "chat_room_id", nullable = false)
    private Long chatRoomId;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** 도달 목표 단계. STRANGER→ACQUAINTANCE, ACQUAINTANCE→FRIEND, FRIEND→LOVER */
    @Enumerated(EnumType.STRING)
    @Column(name = "next_level", nullable = false, length = 30)
    private RelationStatus nextLevel;

    /** 자격 활성 시점 (호감도 임계값 도달 순간). */
    @Column(name = "eligible_since", nullable = false)
    private LocalDateTime eligibleSince;

    /** LLM 발동 여부 — true 후에는 row 보존(이력). */
    @Column(name = "triggered", nullable = false)
    private boolean triggered = false;

    /** 발동 시점. nullable. */
    @Column(name = "triggered_at")
    private LocalDateTime triggeredAt;

    /**
     * 페일세이프 카운터 — 자격 활성 후 디렉터가 발동을 미루는 턴 수.
     * 임계 초과 시 디렉터 prompt에 강제 권유 문구 추가.
     */
    @Column(name = "deferred_turn_count", nullable = false)
    private int deferredTurnCount = 0;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory & State transitions
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static RelationPromotionEligibility activate(Long chatRoomId, Long characterId,
                                                        RelationStatus nextLevel) {
        RelationPromotionEligibility e = new RelationPromotionEligibility();
        e.chatRoomId = chatRoomId;
        e.characterId = characterId;
        e.nextLevel = nextLevel;
        e.eligibleSince = LocalDateTime.now();
        e.triggered = false;
        e.deferredTurnCount = 0;
        return e;
    }

    /** LLM이 자율 발동했을 때 호출 — ChatRoomHeroine.statusLevel 갱신과 같은 TX에서. */
    public void markTriggered() {
        this.triggered = true;
        this.triggeredAt = LocalDateTime.now();
    }

    /** 매 디렉터 응답마다 +1 (자격 활성 + 미발동인 경우). 페일세이프 임계 비교용. */
    public void incrementDeferred() {
        this.deferredTurnCount++;
    }

    public boolean isPending() {
        return !this.triggered;
    }
}