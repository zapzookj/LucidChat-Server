package com.spring.aichat.domain.ugc;

import com.spring.aichat.domain.user.EnergySplit;
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

    /**
     * [D-1.8 · V32] 사후 장소 추가 차감의 총액(추가 시점 가격)과 유료(paid) 분할분. 실패 장소 삭제 환불은
     * 추가 시점과 임의의 시간이 떨어져 있어 종전(정액 {@code props.world().reroll()} 환불)은 분할을 알 수 없었고,
     * 총액을 삭제 시점 가격표에서 가져오면 가격 개정 사이에 유료분이 클램프로 잘린다 — 그래서 나머지 3 테이블과
     * 동형으로 총액도 영속한다.
     *
     * <p>빌더 잡이 산출한 장소는 항상 READY로 태어나고 retryLocation이 READY를 거부하므로 실패 삭제 경로에
     * 도달하지 않는다 — 0/0은 '과금 없음'이지 폴백 시나리오가 아니다. 유일한 폴백 대상은 배포 이전에 사후 추가된
     * 구 행(총액 0)이며, 그 경우 삭제 시점 가격표를 총액으로 쓴다({@link #chargedSplit(int)}). V32가 배포 시점
     * 미완(status ≠ READY) 구 행을 1/1로 백필하므로 실제 폴백 발생은 드물다.
     */
    @Column(name = "energy_charged", nullable = false)
    private int energyCharged = 0;

    @Column(name = "energy_charged_paid", nullable = false)
    private int energyChargedPaid = 0;

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

    /**
     * [사후 장소 추가] 배경 생성 대기 상태로 생성 — 프롬프트화·flux 생성은 비동기.
     *
     * @param charge 추가 시 차감(1E)의 free/paid 분할 — 실패 삭제 환불이 그대로 되돌린다(D-1.8)
     */
    public static UgcWorldLocation createGenerating(Long ugcWorldId, String locationKey,
                                                    String displayName, String description, int displayOrder,
                                                    EnergySplit charge) {
        UgcWorldLocation loc = new UgcWorldLocation();
        loc.ugcWorldId = ugcWorldId;
        loc.locationKey = locationKey;
        loc.displayName = displayName;
        loc.description = description;
        loc.displayOrder = displayOrder;
        loc.status = GENERATING;
        loc.energyCharged = charge.total();
        loc.energyChargedPaid = charge.fromPaid();
        return loc;
    }

    /**
     * [D-1.8] 실패 삭제 환불용 분할 복원 — 추가 시점에 영속한 (총액, 유료분) 그대로.
     *
     * @param legacyTotalFallback 총액 컬럼이 없던 배포 이전 구 행(총액 0)에만 쓰는 폴백 총액 — 호출측이
     *                            현재 가격표({@code props.world().reroll()})를 넘긴다. 그 구 행은 유료분도 0이라
     *                            전액 free(상한 캡)로 돌아간다(V32 백필 대상에서 빠진 행 = 배포 시점 READY였던 행뿐).
     */
    public EnergySplit chargedSplit(int legacyTotalFallback) {
        int total = energyCharged > 0 ? energyCharged : legacyTotalFallback;
        return EnergySplit.of(total, energyChargedPaid);
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
