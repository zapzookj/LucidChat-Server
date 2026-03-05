package com.spring.aichat.domain.enums;

/**
 * 구독 타입
 *
 * LUCID_PASS (Tier 1, 14,900원/월) - 전 연령
 *   - 에너지 부스트 모드 무제한 (일반 비용으로 Pro 모델)
 *   - 에너지 회복 속도 2배 (10분→5분)
 *   - freeEnergy 최대 보유량 30→100
 *
 * LUCID_MIDNIGHT_PASS (Tier 2, 24,900원/월) - 성인 전용
 *   - Tier 1의 모든 혜택
 *   - 모든 캐릭터 시크릿 모드 상시 개방
 */
public enum SubscriptionType {

    LUCID_PASS("루시드 패스", 14900, false),
    LUCID_MIDNIGHT_PASS("루시드 미드나잇 패스", 24900, true);

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