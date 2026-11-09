package com.spring.aichat.domain.world;

import com.spring.aichat.domain.enums.WorldId;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 세계관 마스터 엔티티
 *
 * <p>Theater 모드와 V2 Story 모드의 공유 진입 단위. 각 세계관은 1개 이상의 히로인을
 * 포함하며, 유저는 세계관을 먼저 선택한 뒤 그 안에서 페르소나/히로인/시작 장소를
 * 결정한다.
 *
 * <p>[패키지 이동 — V2 Story]
 * 기존: {@code com.spring.aichat.domain.theater.World}
 * V2:   {@code com.spring.aichat.domain.world.World}
 * Theater 전용이 아닌 *공유 자산*임을 명시. 필드 변경 없음.
 *
 * <p>[설계 원칙]
 * - DB 테이블 기반으로 관리하여 추후 세계관 추가 시 코드 수정 최소화
 * - {@link WorldId} enum과 1:1 매핑 (타입 안전성 확보)
 * - 세계관별 BGM/인트로/분위기를 메타데이터로 관리
 * - V2 Story의 *World 단위 시크릿 게이팅*: secretAllowed=false인 World에서는
 *   유저가 시크릿 BM을 보유해도 시크릿 모드 활성 불가
 *
 * <p>[연관 도메인 — V2 Story 신규]
 * - {@link WorldLocation}: World 안의 장소 풀 (1:N)
 * - {@link UserPersonaPreset}: World 종속 사전 정의 페르소나 (1:N)
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

    /**
     * Act 1 오프닝 공통 나레이션 템플릿 (세계관 몰입용).
     * Theater Act 1 / V2 Story 첫 진입 *고정 인트로 골격*으로 사용.
     */
    @Column(name = "opening_narration", columnDefinition = "TEXT")
    private String openingNarration;

    /** 기본 BGM ID (BgmMode enum 문자열) */
    @Column(name = "default_bgm", length = 30)
    private String defaultBgm;

    /** 분위기 키워드 (콤마 구분) */
    @Column(name = "mood_keywords", length = 200)
    private String moodKeywords;

    /**
     * 시크릿 모드 허용 여부 (세계관별 콘텐츠 정책).
     * V2 Story에서도 유지: 유저가 시크릿 BM 보유해도 false인 World에서는 비활성.
     */
    @Column(name = "secret_allowed", nullable = false)
    private boolean secretAllowed = false;

    /** 활성화 여부 (비활성 시 로비에서 숨김) */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** 정렬 순서 */
    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    /**
     * [V42 · 2026-11-09] 시드 잠금 — 어드민이 편집·활성 토글하면 true.
     * true인 행은 {@code WorldSeeder}가 갱신하지 않는다. '시드로 되돌리기'가 false로 되돌린다.
     */
    @Column(name = "seed_locked", nullable = false)
    private boolean seedLocked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Factory & Update
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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

    public void lockSeed() { this.seedLocked = true; }
    public void unlockSeed() { this.seedLocked = false; }

    /**
     * 시드 1건을 기존 행에 적용 — WorldSeeder의 UPDATE 경로와 어드민 '시드로 되돌리기'의 단일 구현.
     * 시더 관례대로 null 텍스트는 ""로, active 미지정은 true로, displayOrder 미지정은 0으로 본다.
     */
    public void applySeed(com.spring.aichat.config.WorldSeedProperties.WorldSeed seed) {
        update(
            nz(seed.displayName()), nz(seed.tagline()), nz(seed.description()),
            nz(seed.heroImageUrl()), nz(seed.thumbnailUrl()), nz(seed.openingNarration()),
            nz(seed.defaultBgm()), nz(seed.moodKeywords()),
            Boolean.TRUE.equals(seed.secretAllowed()),
            seed.displayOrder() == null ? 0 : seed.displayOrder());
        this.active = seed.active() == null || seed.active();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ━━━ [2026-11-09 백오피스 콘텐츠 편집] 부분 갱신 — null 필드는 변경 없음 ━━━

    public record AdminContentEdit(
        String displayName, String tagline, String description,
        String heroImageUrl, String thumbnailUrl, String openingNarration,
        String defaultBgm, String moodKeywords, Boolean secretAllowed, Integer displayOrder
    ) {}

    /**
     * @return 값이 바뀐 필드명 목록
     * @throws IllegalArgumentException displayName 공란·길이 초과·무효 BGM
     */
    public java.util.List<String> applyAdminEdit(AdminContentEdit e) {
        java.util.List<String> changed = new java.util.ArrayList<>();
        if (e.displayName() != null && e.displayName().isBlank())
            throw new IllegalArgumentException("displayName: 비울 수 없습니다");
        String bgm = e.defaultBgm() == null ? null : e.defaultBgm().trim();
        if (bgm != null && !bgm.isEmpty()) {
            try {
                com.spring.aichat.domain.enums.BgmMode.valueOf(bgm);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("defaultBgm: 알 수 없는 BGM '" + bgm + "'");
            }
        }
        // 검증을 모두 끝낸 뒤 적용한다 — 중간 실패로 일부 필드만 바뀐 엔티티가 남지 않게
        checkLen("displayName", e.displayName(), 50);
        checkLen("tagline", e.tagline(), 200);
        checkLen("heroImageUrl", e.heroImageUrl(), 500);
        checkLen("thumbnailUrl", e.thumbnailUrl(), 500);
        checkLen("moodKeywords", e.moodKeywords(), 200);

        displayName = edit(changed, "displayName", displayName, e.displayName());
        tagline = edit(changed, "tagline", tagline, e.tagline());
        description = edit(changed, "description", description, e.description());
        heroImageUrl = edit(changed, "heroImageUrl", heroImageUrl, e.heroImageUrl());
        thumbnailUrl = edit(changed, "thumbnailUrl", thumbnailUrl, e.thumbnailUrl());
        openingNarration = edit(changed, "openingNarration", openingNarration, e.openingNarration());
        defaultBgm = edit(changed, "defaultBgm", defaultBgm, bgm);
        moodKeywords = edit(changed, "moodKeywords", moodKeywords, e.moodKeywords());
        if (e.secretAllowed() != null && e.secretAllowed() != secretAllowed) {
            secretAllowed = e.secretAllowed();
            changed.add("secretAllowed");
        }
        if (e.displayOrder() != null && e.displayOrder() != displayOrder) {
            displayOrder = e.displayOrder();
            changed.add("displayOrder");
        }
        return changed;
    }

    private static void checkLen(String field, String v, int maxLen) {
        if (v != null && v.length() > maxLen)
            throw new IllegalArgumentException(field + ": 최대 " + maxLen + "자입니다 (현재 " + v.length() + "자)");
    }

    /** 시더 관례와 맞춰 빈 값은 ""로 저장한다. */
    private static String edit(java.util.List<String> changed, String field, String current, String next) {
        if (next == null) return current;
        String normalized = next.isBlank() ? "" : next;
        if (java.util.Objects.equals(current == null ? "" : current, normalized)) return current;
        changed.add(field);
        return normalized;
    }
}