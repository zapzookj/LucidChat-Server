package com.spring.aichat.domain.enums;

/**
 * 상품 타입 정의 (Phase 5 BM Package)
 *
 * [에너지 상품] - 전 연령
 * ENERGY_T1: 커피 한 잔 (1,500원) -> 30 에너지
 * ENERGY_T2: 달콤한 디저트 세트 (4,500원) -> 100 에너지
 * ENERGY_T3: 프리미엄 애프터눈 티 (9,900원) -> 250 에너지
 *
 * [패키지 상품] - 성인 전용
 * SECRET_PASS_24H: 시크릿 나이트 패스 (2,900원) -> 24시간 시크릿 모드
 * SECRET_UNLOCK_PERMANENT: 캐릭터 시크릿 영구 해금 (14,900원)
 *
 * [구독 상품]
 * LUCID_PASS: 루시드 패스 (14,900원/월) -> 부스트 무제한 + 리젠 2배 + max 100 (전 연령)
 * LUCID_MIDNIGHT_PASS: 루시드 미드나잇 패스 (24,900원/월) -> Tier1 + 전캐릭 시크릿 (성인 전용)
 */
public enum ProductType {

    // 에너지 상품 (전 연령)
    ENERGY_T1("커피 한 잔", 1500, 30, false),
    ENERGY_T2("달콤한 디저트 세트", 4500, 100, false),
    ENERGY_T3("프리미엄 애프터눈 티", 9900, 250, false),

    // 시크릿 상품 (성인 전용)
    SECRET_PASS_24H("시크릿 나이트 패스", 2900, 0, true),
    SECRET_UNLOCK_PERMANENT("캐릭터 시크릿 영구 해금", 14900, 0, true),

    // 구독 상품
    LUCID_PASS("루시드 패스", 14900, 0, false),
    LUCID_MIDNIGHT_PASS("루시드 미드나잇 패스", 24900, 0, true);

    private final String displayName;
    private final int priceKrw;
    private final int energyAmount;
    private final boolean adultOnly;

    ProductType(String displayName, int priceKrw, int energyAmount, boolean adultOnly) {
        this.displayName = displayName;
        this.priceKrw = priceKrw;
        this.energyAmount = energyAmount;
        this.adultOnly = adultOnly;
    }

    public String getDisplayName() { return displayName; }
    public int getPriceKrw() { return priceKrw; }
    public int getEnergyAmount() { return energyAmount; }
    public boolean isAdultOnly() { return adultOnly; }

    public boolean isEnergyProduct() {
        return this == ENERGY_T1 || this == ENERGY_T2 || this == ENERGY_T3;
    }

    public boolean isSubscription() {
        return this == LUCID_PASS || this == LUCID_MIDNIGHT_PASS;
    }

    public boolean isSecretProduct() {
        return this == SECRET_PASS_24H || this == SECRET_UNLOCK_PERMANENT;
    }

    /** ProductType -> SubscriptionType 변환 (구독 상품인 경우) */
    public SubscriptionType toSubscriptionType() {
        return switch (this) {
            case LUCID_PASS -> SubscriptionType.LUCID_PASS;
            case LUCID_MIDNIGHT_PASS -> SubscriptionType.LUCID_MIDNIGHT_PASS;
            default -> throw new IllegalStateException("Not a subscription product: " + this);
        };
    }
}