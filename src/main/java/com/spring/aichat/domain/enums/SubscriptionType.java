package com.spring.aichat.domain.enums;

/**
 * 구독 타입
 *
 * LUCID_PASS (Tier 1, 14,900원/월) - 전 연령
 *   - 에너지 부스트 모드 무제한 (일반 비용으로 Pro 모델)
 *   - 에너지 회복 속도 2배 (10분→5분)
 *   - freeEnergy 최대 보유량 30→100
 *   - Theater 초기 스탯 분배 20p (perStat 10)
 *
 * LUCID_MIDNIGHT_PASS (Tier 2, 24,900원/월) - 성인 전용 / 프리미엄
 *   - Tier 1의 모든 혜택
 *   - 모든 캐릭터 시크릿 모드 상시 개방
 *   - Theater 초기 스탯 분배 40p (perStat 20)  ※ 추후 무제한으로 정책 변경 예정
 *
 * @deprecated LUCID_PASS_PREMIUM
 *   초기 설계에서 정의되었으나 실제 결제 모델(ProductType)에 매핑되지 않은
 *   데드 enum 값. 새 코드에서 절대 사용 금지. 기존 DB에 stale 레코드가 존재할
 *   가능성을 위해 enum 값 자체는 보존(삭제 시 startup deserialization crash 위험).
 *   이 값을 가진 유저는 Theater 스탯 분배에서 FREE 등급으로 fallback 처리된다.
 */
public enum SubscriptionType {

    LUCID_PASS("루시드 패스", 14900, false),
    LUCID_MIDNIGHT_PASS("루시드 미드나잇 패스", 24900, true),

    /** @deprecated 사용 금지 — {@link SubscriptionType} 클래스 docs 참조 */
    @Deprecated
    LUCID_PASS_PREMIUM("루시드 프리미엄 패스 (deprecated)", 50000, true);

    private final String displayName;
    private final int monthlyPriceKrw;
    private final boolean adultOnly;

    SubscriptionType(String displayName, int monthlyPriceKrw, boolean adultOnly) {
        this.displayName = displayName;
        this.monthlyPriceKrw = monthlyPriceKrw;
        this.adultOnly = adultOnly;
    }

    public String getDisplayName() { return displayName; }
    public int getMonthlyPriceKrw() { return monthlyPriceKrw; }
    public boolean isAdultOnly() { return adultOnly; }
}