package com.spring.aichat.domain.illustration;

import com.spring.aichat.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [Phase 5.5-Illust] 유저 일러스트 수집품 엔티티
 *
 * 유저가 실시간 생성한 캐릭터 일러스트를 영구 보관.
 * AchievementGallery와 별도로, 순수 이미지 수집 시스템.
 *
 * 트리거:
 *   - 대화 중 유저가 "일러스트 생성" 버튼 클릭 (에너지 10 소모)
 *   - 관계 승급 이벤트 성공 시 자동 생성
 *   - 엔딩 크레딧 도달 시 자동 생성
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "user_illustrations", indexes = {
    @Index(name = "idx_illust_user", columnList = "user_id"),
    @Index(name = "idx_illust_user_char", columnList = "user_id, character_id"),
    @Index(name = "idx_illust_request", columnList = "fal_request_id", unique = true)
})
public class UserIllustration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    @Column(name = "character_name", length = 50)
    private String characterName;

    /** Fal.ai 요청 ID (폴링/웹훅 추적용, unique) */
    @Column(name = "fal_request_id", nullable = false, unique = true, length = 100)
    private String falRequestId;

    /** 생성 상태: PENDING → GENERATING → COMPLETED → FAILED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** S3 영구 URL (생성 완료 후) */
    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    /** Fal.ai 임시 URL (S3 업로드 전) */
    @Column(name = "fal_temp_url", length = 1000)
    private String falTempUrl;

    /** 상태 폴링용 URL */
    @Column(name = "status_url", length = 1000)
    private String statusUrl;

    /** 결과 조회용 URL */
    @Column(name = "response_url", length = 1000)
    private String responseUrl;

    /** 생성에 사용된 포지티브 프롬프트 (디버깅/재생성용) */
    @Column(name = "prompt_used", columnDefinition = "TEXT")
    private String promptUsed;

    /** 생성 트리거 (MANUAL / PROMOTION / ENDING) */
    @Column(name = "trigger_type", nullable = false, length = 20)
    private String triggerType;

    /** 생성 당시 감정 태그 */
    @Column(name = "emotion", length = 30)
    private String emotion;

    /** 생성 당시 장소 */
    @Column(name = "location", length = 30)
    private String location;

    /** 생성 당시 복장 */
    @Column(name = "outfit", length = 30)
    private String outfit;

    /** 에러 메시지 (FAILED 상태) */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  팩토리 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static UserIllustration createPending(
        User user, Long characterId, String characterName,
        String falRequestId, String statusUrl, String responseUrl,
        String promptUsed, String triggerType,
        String emotion, String location, String outfit
    ) {
        UserIllustration illust = new UserIllustration();
        illust.user = user;
        illust.characterId = characterId;
        illust.characterName = characterName;
        illust.falRequestId = falRequestId;
        illust.statusUrl = statusUrl;
        illust.responseUrl = responseUrl;
        illust.promptUsed = promptUsed;
        illust.triggerType = triggerType;
        illust.emotion = emotion;
        illust.location = location;
        illust.outfit = outfit;
        illust.status = "PENDING";
        return illust;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  상태 전이 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public void markGenerating() {
        this.status = "GENERATING";
    }

    public void markCompleted(String s3Url, String falTempUrl) {
        this.status = "COMPLETED";
        this.imageUrl = s3Url;
        this.falTempUrl = falTempUrl;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = "FAILED";
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    public boolean isPending() { return "PENDING".equals(status) || "GENERATING".equals(status); }
    public boolean isCompleted() { return "COMPLETED".equals(status); }
}