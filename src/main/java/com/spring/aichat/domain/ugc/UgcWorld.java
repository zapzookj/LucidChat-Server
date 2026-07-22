package com.spring.aichat.domain.ugc;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [UGC 세계관 빌더] 유저 생성 세계관 — 재사용 자산 (1 월드 : N 캐릭터).
 *
 * <p>기존 {@code World}는 PK가 {@code WorldId} <b>enum</b>이라 런타임 생성이 불가능해
 * 별도 엔티티로 둔다(2026-07-20 확정 — 전면 마이그레이션은 STORY 개방 시점 재검토).
 * {@code Character}는 공식 연결용 {@code worldId}(enum)와 UGC 연결용 {@code ugcWorldId}(Long)를
 * 병행 컬럼으로 갖는다.
 *
 * <p>v1 스코프: SANDBOX 채팅 효과 전용 — lore는 시스템 프롬프트 주입, 장소 풀은 동적 배경.
 * 독립 공개 기능 없음(캐릭터 공개 심사에 피기백 — {@link WorldReviewStatus}).
 * READY 후 편집 API 없음(승인 무효화 문제 원천 차단 — 2026-07-20 종원 확정).
 *
 * <p>URL 필드는 서비스 CDN 공개 URL이다(Character.thumbnailUrl 관례와 동일 — presigned 저장 금지).
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "ugc_worlds", indexes = {
    @Index(name = "idx_ugc_world_owner", columnList = "owner_user_id")
})
public class UgcWorld {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 생성자 유저. 의도적 FK 미설정(Long 참조 — V2 도메인 관례). */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 유저 노출용 짧은 소개 (카드/셀렉터). */
    @Column(name = "intro", columnDefinition = "TEXT")
    private String intro;

    /** 채팅 시스템 프롬프트 주입용 설정 본문 — 유저 생성 텍스트(주입 시 캡슐화 필수). */
    @Column(name = "lore", columnDefinition = "TEXT")
    private String lore;

    /** 무드 태그 — 콤마 구분 (World.moodKeywords 관례). */
    @Column(name = "mood_tags", length = 200)
    private String moodTags;

    /** 월드 대표 썸네일 — 서비스 CDN 공개 URL. */
    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    /** 캐릭터 공개 심사 피기백 판정 결과. */
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private WorldReviewStatus reviewStatus = WorldReviewStatus.NONE;

    /** 마지막 판정 사유. */
    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  생성 (W3 바인딩 전용)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static UgcWorld create(Long ownerUserId, String name, String intro, String lore,
                                  String moodTags, String thumbnailUrl) {
        UgcWorld w = new UgcWorld();
        w.ownerUserId = ownerUserId;
        w.name = name;
        w.intro = intro;
        w.lore = lore;
        w.moodTags = moodTags;
        w.thumbnailUrl = thumbnailUrl;
        w.reviewStatus = WorldReviewStatus.NONE;
        return w;
    }

    public boolean isOwnedBy(Long userId) {
        return ownerUserId != null && ownerUserId.equals(userId);
    }

    /**
     * [2026-07-22 사후 편집] 설정 텍스트 수정 (무료 — null 필드 유지).
     * 판정 이력이 있으면(APPROVED/REJECTED) NONE으로 리셋 — 수정본은 재검수 대상이며,
     * PUBLIC 캐릭터 연결 게이트(APPROVED만)가 자동으로 다시 잠긴다. 기존 PUBLIC 캐릭터는 유지.
     */
    /** @param moodTags null=유지 · 빈 문자열=클리어 마커(태그 전체 삭제) · 그 외=교체 */
    public void updateTexts(String name, String intro, String lore, String moodTags) {
        boolean changed = false;
        if (name != null && !name.isBlank() && !name.equals(this.name)) { this.name = name; changed = true; }
        if (intro != null && !intro.equals(this.intro)) { this.intro = intro; changed = true; }
        if (lore != null && !lore.equals(this.lore)) { this.lore = lore; changed = true; }
        if (moodTags != null) {
            // [리뷰 픽스] 빈 문자열 = 클리어 — 드래프트 편집과 달리 사후 편집에서 태그를 비울 수
            // 없던 비대칭 해소 (null '유지' 시맨틱과 구분)
            String next = moodTags.isBlank() ? null : moodTags;
            if (!java.util.Objects.equals(next, this.moodTags)) { this.moodTags = next; changed = true; }
        }
        if (changed && reviewStatus != WorldReviewStatus.NONE) {
            this.reviewStatus = WorldReviewStatus.NONE;
        }
    }

    // ── 검수 판정 (캐릭터 공개 심사 피기백 — AdminUgcReviewService 전용) ──

    public void approve(String note) {
        this.reviewStatus = WorldReviewStatus.APPROVED;
        if (note != null) this.reviewNote = note;
    }

    public void reject(String note) {
        this.reviewStatus = WorldReviewStatus.REJECTED;
        if (note != null) this.reviewNote = note;
    }
}
