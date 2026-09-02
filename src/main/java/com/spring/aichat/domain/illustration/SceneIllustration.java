package com.spring.aichat.domain.illustration;

import com.spring.aichat.domain.user.EnergySplit;
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

    // ── [2026-07-31 에픽 B] 수동 트리거 과금·환불 추적 (V17) ──

    /** AUTO(인밴드 휴면 경로) | MANUAL(유저 요청 + 전용 프롬프트 라이터). */
    @Column(name = "trigger_source", nullable = false, length = 10)
    private String triggerSource = "AUTO";

    /** MANUAL 요청 유저 id — 실패 환불 대상 (FK 미설정, owner_user_id 관례). */
    @Column(name = "requested_by")
    private Long requestedBy;

    /** 요청 시점 차감 에너지 — 실패 환불 정산 기준. AUTO/SKIPPED는 0. */
    @Column(name = "energy_charged", nullable = false)
    private int energyCharged = 0;

    /**
     * [D-1.2 · V32] {@link #energyCharged} 중 유료(paid) 분할분. 렌더 실패 콜백은 요청 후 수십 초~수 분
     * 뒤라 총액만으로 환불하면 유료분이 free로 흡수돼 소각됐다. 구 행은 0 = 전액 free 폴백.
     */
    @Column(name = "energy_charged_paid", nullable = false)
    private int energyChargedPaid = 0;

    /** 환불 완료 여부 — failRender 환불의 멱등 가드. */
    @Column(name = "energy_refunded", nullable = false)
    private boolean energyRefunded = false;

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

    /**
     * [2026-07-31 에픽 B] 수동 요청 행 — 유저가 명시 요청·에너지 차감. 실패 시 failRender가
     * {@link #chargedSplit()}을 requestedBy에게 환불한다(energyRefunded 멱등 가드).
     *
     * @param charge 차감 시점의 free/paid 분할 — {@code User.consumeEnergy}의 반환값을 그대로 넘긴다
     */
    public static SceneIllustration pendingManual(Long chatRoomId, int turnIndex, String sceneHash,
                                                  String positivePrompt, Long requestedBy, EnergySplit charge) {
        SceneIllustration s = pending(chatRoomId, turnIndex, sceneHash, positivePrompt);
        s.triggerSource = "MANUAL";
        s.requestedBy = requestedBy;
        s.energyCharged = charge.total();
        s.energyChargedPaid = charge.fromPaid();
        return s;
    }

    /** [D-1.2] 환불용 분할 복원 — 구 행(paid=0)은 전액 free. */
    public EnergySplit chargedSplit() {
        return EnergySplit.of(energyCharged, energyChargedPaid);
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

    /** 실패 환불 대상인가 — MANUAL·차감액 존재·미환불일 때만(멱등). */
    public boolean refundableOnFail() {
        return "MANUAL".equals(triggerSource) && energyCharged > 0
            && !energyRefunded && requestedBy != null;
    }

    public void markRefunded() {
        this.energyRefunded = true;
    }
}
