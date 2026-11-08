package com.spring.aichat.domain.ugc;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [docs/56 §10 레퍼런스 빌더] 유저가 올린 캐릭터 그림 1장 — 업로드·검사 결과.
 *
 * <p>저장본은 원본이 아니라 서버 재인코딩 PNG(EXIF 제거·긴 변 1536px 이하)다. 키는 추측 불가 UUID 경로이며
 * 공개 URL로 노출하지 않는다(소유자 미리보기·외부 편집 입력은 presigned). 판정이 {@link Verdict#OK}인 행만
 * 생성에 쓸 수 있다. 보관 정책: 캐릭터와 수명 동일, 캐릭터 없이 끝난 그림은 7일 뒤 스토리지 정리
 * ({@link #deletedAt} — 행은 감사용으로 남긴다).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "ugc_reference_images",
    indexes = @Index(name = "idx_uri_user_created", columnList = "user_id, created_at"))
public class UgcReferenceImage {

    public enum Verdict { OK, UNCLEAR, MULTIPLE, POLICY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "storage_key", nullable = false, length = 300)
    private String storageKey;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "width", nullable = false)
    private int width;

    @Column(name = "height", nullable = false)
    private int height;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 20)
    private Verdict verdict;

    /** VLM 판독(JSON) — {@code ReferenceFeatures}. 판정이 OK가 아니면 null일 수 있다. */
    @Column(name = "features_json", columnDefinition = "TEXT")
    private String featuresJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public static UgcReferenceImage of(Long userId, String storageKey, String sha256, int width, int height,
                                       Verdict verdict, String featuresJson) {
        UgcReferenceImage r = new UgcReferenceImage();
        r.userId = userId;
        r.storageKey = storageKey;
        r.sha256 = sha256;
        r.width = width;
        r.height = height;
        r.verdict = verdict;
        r.featuresJson = featuresJson;
        return r;
    }

    public boolean usable() { return verdict == Verdict.OK && deletedAt == null; }

    public void markDeleted() { this.deletedAt = LocalDateTime.now(); }
}
