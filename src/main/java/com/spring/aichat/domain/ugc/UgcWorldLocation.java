package com.spring.aichat.domain.ugc;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [UGC 세계관 빌더] 월드 장소 — {@code WorldLocation}의 UGC 변형.
 *
 * <p>차이점: (1) worldId가 enum이 아닌 UgcWorld Long 참조(FK 미설정 관례)
 * (2) <b>사전 생성된 대표 배경 1장</b>({@code backgroundUrl})을 직접 보유 —
 * 공식 WorldLocation은 배경이 없고 채팅에서 동적 생성만 하지만, UGC 월드는 W2에서
 * 장소당 대표 배경을 확정해 채팅 진입/장소 전환 시 즉시 표시한다(DAY/NIGHT 변형은 백로그).
 *
 * <p>locationKey는 월드 내 unique한 영문 SCREAMING_SNAKE_CASE(40자 이내) — 동적 배경
 * canonical key {@code UGCW_{ugcWorldId}__{locationKey}} 조립과 v1.1 루틴 이행에 사용된다.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "ugc_world_locations",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_ugc_world_location_key", columnNames = {"ugc_world_id", "location_key"})
    },
    indexes = {
        @Index(name = "idx_ugc_world_location_world", columnList = "ugc_world_id, display_order")
    })
public class UgcWorldLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ugc_world_id", nullable = false)
    private Long ugcWorldId;

    /** 월드 안에서 unique한 식별자. 예: "ROOFTOP_GARDEN" */
    @Column(name = "location_key", nullable = false, length = 50)
    private String locationKey;

    /** UI/프롬프트에 노출되는 표시명. 예: "옥상 정원" */
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** 장소 분위기 설명 (1~2문장) — 시스템 프롬프트 장소 풀 주입용. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** W0/W1에서 확정된 배경 생성 프롬프트(영문) — 리롤·디버깅 재현용. */
    @Column(name = "background_prompt", columnDefinition = "TEXT")
    private String backgroundPrompt;

    /** 대표 배경 1장 — 서비스 CDN 공개 URL. */
    @Column(name = "background_url", length = 500)
    private String backgroundUrl;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * [2026-07-22 사후 장소 추가] 배경 생성 상태 — READY(사용 가능) / GENERATING(배경 생성 중) /
     * FAILED(생성 실패 — 무료 재시도 대상). 빌더 잡 산출 장소는 항상 READY로 태어난다.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = READY;

    public static final String READY = "READY";
    public static final String GENERATING = "GENERATING";
    public static final String FAILED = "FAILED";

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static UgcWorldLocation create(Long ugcWorldId, String locationKey, String displayName,
                                          String description, String backgroundPrompt,
                                          String backgroundUrl, int displayOrder) {
        UgcWorldLocation loc = new UgcWorldLocation();
        loc.ugcWorldId = ugcWorldId;
        loc.locationKey = locationKey;
        loc.displayName = displayName;
        loc.description = description;
        loc.backgroundPrompt = backgroundPrompt;
        loc.backgroundUrl = backgroundUrl;
        loc.displayOrder = displayOrder;
        loc.status = READY;
        return loc;
    }

    /** [사후 장소 추가] 배경 생성 대기 상태로 생성 — 프롬프트화·flux 생성은 비동기. */
    public static UgcWorldLocation createGenerating(Long ugcWorldId, String locationKey,
                                                    String displayName, String description, int displayOrder) {
        UgcWorldLocation loc = new UgcWorldLocation();
        loc.ugcWorldId = ugcWorldId;
        loc.locationKey = locationKey;
        loc.displayName = displayName;
        loc.description = description;
        loc.displayOrder = displayOrder;
        loc.status = GENERATING;
        return loc;
    }

    public void markReady(String backgroundPrompt, String backgroundUrl) {
        this.backgroundPrompt = backgroundPrompt;
        this.backgroundUrl = backgroundUrl;
        this.status = READY;
    }

    public void markFailed() {
        this.status = FAILED;
    }

    public void markGenerating() {
        this.status = GENERATING;
    }

    public boolean is(String s) {
        return s.equals(status);
    }
}
