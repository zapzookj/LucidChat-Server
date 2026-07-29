package com.spring.aichat.domain.illustration;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [2026-07-30 B-2/A-1 재피벗] 턴 단위 씬 일러 행 — 디오라마 검증 구조의 프로드 수용
 * (docs/09 A-1 오케스트레이션: skip 플래그+scene_hash 이중 디덥, 비동기 제출+폴링).
 *
 * <p>{@link UserIllustration}(유저 트리거 수집품·ModelsLab 트랙)과 별도 — 이 테이블은
 * 채팅 턴에 종속된 씬 렌더의 상태·재사용 추적이 목적이다.
 * "방의 현재 씬"은 방당 마지막 COMPLETED 행에서 유도한다(방 엔티티 캐시 컬럼 없음 —
 * 디오라마의 @Version 경합 지점 제거).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "scene_illustrations", indexes = {
    @Index(name = "idx_scene_illust_room", columnList = "chat_room_id, id"),
    @Index(name = "idx_scene_illust_request", columnList = "provider_request_id")
})
public class SceneIllustration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_room_id", nullable = false)
    private Long chatRoomId;

    /** 렌더 시점의 턴 수(로그 카운트 기준) — 파일 키·씬 네비게이션 정렬용. */
    @Column(name = "turn_index", nullable = false)
    private int turnIndex;

    /** PENDING → GENERATING → COMPLETED / FAILED, 또는 SKIPPED(디덥 재사용). */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 장소+행위+cast SHA-256 — LLM skip 플래그와 함께 이중 디덥(비용 통제). */
    @Column(name = "scene_hash", length = 64)
    private String sceneHash;

    /** 영속/디버그용 positive 전문. */
    @Column(name = "positive_prompt", columnDefinition = "TEXT")
    private String positivePrompt;

    /** 서비스 S3/CDN 공개 URL (presigned 저장 금지 원칙). */
    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    /** RunPod job id (폴링 추적). */
    @Column(name = "provider_request_id", length = 100)
    private String providerRequestId;

    /** SKIPPED일 때 재사용한 원본 일러 id. */
    @Column(name = "reused_illustration_id")
    private Long reusedIllustrationId;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── 팩토리 ──

    public static SceneIllustration pending(Long chatRoomId, int turnIndex, String sceneHash, String positivePrompt) {
        SceneIllustration s = new SceneIllustration();
        s.chatRoomId = chatRoomId;
        s.turnIndex = turnIndex;
        s.status = "PENDING";
        s.sceneHash = sceneHash;
        s.positivePrompt = positivePrompt;
        return s;
    }

    /** 디덥 스킵 행 — 직전 완료 일러를 그대로 가리킨다(씬 네비게이션에서 턴별 매핑 유지). */
    public static SceneIllustration skipped(Long chatRoomId, int turnIndex, String sceneHash,
                                            Long reusedId, String reusedUrl) {
        SceneIllustration s = new SceneIllustration();
        s.chatRoomId = chatRoomId;
        s.turnIndex = turnIndex;
        s.status = "SKIPPED";
        s.sceneHash = sceneHash;
        s.reusedIllustrationId = reusedId;
        s.imageUrl = reusedUrl;
        return s;
    }

    // ── 상태 전이 (짧은 tx 쓰기 빈에서만 호출) ──

    public void markSubmitted(String providerRequestId) {
        this.providerRequestId = providerRequestId;
        this.status = "GENERATING";
    }

    public void complete(String imageUrl) {
        this.imageUrl = imageUrl;
        this.status = "COMPLETED";
        this.errorMessage = null;
    }

    public void fail(String error) {
        this.status = "FAILED";
        this.errorMessage = error == null ? null
            : error.substring(0, Math.min(500, error.length()));
    }

    public boolean isTerminal() {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "SKIPPED".equals(status);
    }
}
