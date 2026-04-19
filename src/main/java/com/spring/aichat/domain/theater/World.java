package com.spring.aichat.domain.theater;

import com.spring.aichat.domain.enums.WorldId;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [Phase 5.5-Theater] 세계관 마스터 엔티티
 *
 * Theater 모드의 "진입 단위". 각 세계관은 1개 이상의 히로인을 포함하며,
 * 유저는 세계관을 먼저 선택한 뒤 그 안에서 아바타를 만들고 히로인을 결정한다.
 *
 * [설계 원칙]
 * - DB 테이블 기반으로 관리하여 추후 세계관 추가 시 코드 수정 최소화
 * - WorldId enum과 1:1 매핑 (타입 안전성 확보)
 * - 세계관별 BGM/인트로/분위기를 메타데이터로 관리
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "worlds")
public class World {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "id", length = 50)
    private WorldId id;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "tagline", length = 200)
    private String tagline;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 세계관 선택 화면의 대표 배경 이미지 (전체 화면) */
    @Column(name = "hero_image_url", length = 500)
    private String heroImageUrl;

    /** 썸네일 (로비 카드용) */
    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    /** Act 1 오프닝 공통 나레이션 템플릿 (세계관 몰입용) */
    @Column(name = "opening_narration", columnDefinition = "TEXT")
    private String openingNarration;

    /** 기본 BGM ID (BgmMode enum 문자열) */
    @Column(name = "default_bgm", length = 30)
    private String defaultBgm;

    /** 분위기 키워드 (콤마 구분) */
    @Column(name = "mood_keywords", length = 200)
    private String moodKeywords;

    /** 시크릿 모드 허용 여부 (세계관별 콘텐츠 정책) */
    @Column(name = "secret_allowed", nullable = false)
    private boolean secretAllowed = false;

    /** 활성화 여부 (비활성 시 로비에서 숨김) */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** 정렬 순서 */
    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    // ── Factory ──

    public static World create(WorldId id, String displayName, String tagline,
                               String description, String heroImageUrl, String thumbnailUrl,
                               String openingNarration, String defaultBgm,
                               String moodKeywords, boolean secretAllowed, int displayOrder) {
        World w = new World();
        w.id = id;
        w.displayName = displayName;
        w.tagline = tagline;
        w.description = description;
        w.heroImageUrl = heroImageUrl;
        w.thumbnailUrl = thumbnailUrl;
        w.openingNarration = openingNarration;
        w.defaultBgm = defaultBgm;
        w.moodKeywords = moodKeywords;
        w.secretAllowed = secretAllowed;
        w.displayOrder = displayOrder;
        return w;
    }

    public void update(String displayName, String tagline, String description,
                       String heroImageUrl, String thumbnailUrl,
                       String openingNarration, String defaultBgm,
                       String moodKeywords, boolean secretAllowed, int displayOrder) {
        this.displayName = displayName;
        this.tagline = tagline;
        this.description = description;
        this.heroImageUrl = heroImageUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.openingNarration = openingNarration;
        this.defaultBgm = defaultBgm;
        this.moodKeywords = moodKeywords;
        this.secretAllowed = secretAllowed;
        this.displayOrder = displayOrder;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}