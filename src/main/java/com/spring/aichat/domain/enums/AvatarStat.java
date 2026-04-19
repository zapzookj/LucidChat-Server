package com.spring.aichat.domain.enums;

/**
 * [Phase 5.5-Theater] 아바타 5축 스탯
 *
 * Theater 모드에서 유저가 컨트롤하는 주체인 "아바타(주인공)"의 능력치.
 * Story/Sandbox의 히로인 5축 스탯(친밀/호감/의존/장난기/신뢰)과는 별개.
 *
 * [스탯의 용도]
 * 1. 분기 잠금 해제 (Stat-gated Branch)
 * 2. 씬 나레이션의 "객관적 타인 반응" 결정 (페르소나-스탯 하이브리드)
 * 3. 다축 엔딩 분기 (지배 스탯 + 히로인 호감도 조합)
 *
 * [성장 경로]
 * - 인터미션 (Act 사이): 메인 성장 경로
 * - 분기 선택 보너스: 보조 성장
 * - BM (초기 분배): 유료 가입자 전용
 * - 스탯 포션 (단건 구매)
 *
 * [설명자(descriptor) 매핑]
 * LLM 프롬프트에 "매력: 20 (평범)" 같이 주입하기 위한 사람이 읽기 쉬운 라벨.
 */
public enum AvatarStat {

    CHARM("매력", "외모와 첫인상"),
    WIT("입담", "언변과 유머 감각"),
    BOLDNESS("담력", "용기와 결단력"),
    INTELLECT("지성", "지식과 통찰"),
    EMPATHY("감수성", "공감과 섬세함");

    private final String displayName;
    private final String description;

    AvatarStat(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    /**
     * 수치 → 레벨 설명자 변환 (LLM 프롬프트 주입용)
     *
     * 0~20   → 극저 (미천, 초라, 서툴)
     * 21~40  → 평범
     * 41~60  → 준수 (제법, 적당히)
     * 61~80  → 뛰어남
     * 81~100 → 압도적 (비범, 신급)
     */
    public static String toDescriptor(int value) {
        if (value <= 20) return "극저";
        if (value <= 40) return "평범";
        if (value <= 60) return "준수";
        if (value <= 80) return "뛰어남";
        return "압도적";
    }

    /**
     * 스탯별 레벨 설명자 (캐릭터 반응 톤 결정용)
     */
    public String getLevelDescriptor(int value) {
        return switch (this) {
            case CHARM -> charmDescriptor(value);
            case WIT -> witDescriptor(value);
            case BOLDNESS -> boldnessDescriptor(value);
            case INTELLECT -> intellectDescriptor(value);
            case EMPATHY -> empathyDescriptor(value);
        };
    }

    private String charmDescriptor(int v) {
        if (v <= 20) return "초라한 외모";
        if (v <= 40) return "평범한 외모";
        if (v <= 60) return "눈길을 끄는 외모";
        if (v <= 80) return "매력적인 외모";
        return "압도적으로 아름다운 외모";
    }

    private String witDescriptor(int v) {
        if (v <= 20) return "어눌한 말솜씨";
        if (v <= 40) return "평범한 말솜씨";
        if (v <= 60) return "재치 있는 말솜씨";
        if (v <= 80) return "유려한 말솜씨";
        return "탁월한 언변";
    }

    private String boldnessDescriptor(int v) {
        if (v <= 20) return "소심하고 망설임 많음";
        if (v <= 40) return "평범한 용기";
        if (v <= 60) return "제법 당당함";
        if (v <= 80) return "결단력 있음";
        return "두려움을 모르는 대담함";
    }

    private String intellectDescriptor(int v) {
        if (v <= 20) return "얕은 지식";
        if (v <= 40) return "평범한 지식";
        if (v <= 60) return "제법 박식함";
        if (v <= 80) return "통찰력 있음";
        return "천재적 지성";
    }

    private String empathyDescriptor(int v) {
        if (v <= 20) return "둔감함";
        if (v <= 40) return "평범한 감수성";
        if (v <= 60) return "섬세한 감수성";
        if (v <= 80) return "깊이 공감할 줄 앎";
        return "영혼까지 닿는 감수성";
    }

    public static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}