package com.spring.aichat.domain.enums;

/**
 * [2026-07-31 난이도] 캐릭터 공략 난이도 — 이중 게이트(종원 확정: 하이브리드).
 *
 * <p>승급/엔딩과 동형의 철학: <b>프롬프트 층</b>({@link #promptDirective} — 캐릭터가 서사적으로
 * 마음을 여는 문턱을 연기)이 톤을, <b>백엔드 층</b>({@link #scaleGain} — 양수 스탯 델타의
 * 결정론 배율)이 보장을 담당한다. LLM이 지시를 무시해도 수치는 배율이 통제한다.
 *
 * <p>배율은 <b>확률적 반올림</b>: LLM 델타가 1~3 소폭이라 단순 반올림이면 HARD(0.6)에서
 * +1이 항상 +1로 남아 무력화된다. 0.6은 60% 확률 +1(기대값 정확·추가 상태 불요).
 * 음수 델타(실수·무례)는 무배율 통과 — 어려운 캐릭터도 상처는 똑같이 받는다.
 */
public enum CharacterDifficulty {

    EASY(1.5), NORMAL(1.0), HARD(0.6), EXTREME(0.35);

    private final double gainMultiplier;

    CharacterDifficulty(double gainMultiplier) {
        this.gainMultiplier = gainMultiplier;
    }

    public double gainMultiplier() {
        return gainMultiplier;
    }

    /** 양수 델타만 확률 반올림 배율. null·0 이하는 그대로 통과. */
    public Integer scaleGain(Integer delta) {
        if (delta == null || delta <= 0 || gainMultiplier == 1.0) return delta;
        return scaleGain(delta, java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    /** 테스트 가능 오버로드 — roll ∈ [0,1). */
    int scaleGain(int delta, double roll) {
        double scaled = delta * gainMultiplier;
        int base = (int) Math.floor(scaled);
        double frac = scaled - base;
        return base + (roll < frac ? 1 : 0);
    }

    /**
     * 프롬프트 성격 지시(서사 층) — NORMAL은 null(무주입, 스키마 세금 제로).
     * 캐릭터 정의 블록에 부착되어 스탯 판정의 '서사적 이유'를 만든다(수치-톤 부조화 방지).
     */
    public String promptDirective() {
        return switch (this) {
            case EASY -> """
                ### Approach Difficulty: EASY
                이 캐릭터는 마음을 여는 문턱이 낮다. 유저의 호의와 관심에 비교적 빠르고 솔직하게 반응하며, 긍정적 스탯 변화 판정에 관대하라.""";
            case NORMAL -> null;
            case HARD -> """
                ### Approach Difficulty: HARD
                이 캐릭터는 쉽게 마음을 열지 않는다. 호감이 오르는 계기를 까다롭게 판정하라 — 피상적 호의·아부·값싼 칭찬에는 동요하지 않는다. 신뢰는 구체적 행동과 시간의 누적으로만 쌓이며, 스탯 상승은 그에 상응하는 사건이 있을 때만 소폭 발생한다.""";
            case EXTREME -> """
                ### Approach Difficulty: EXTREME
                이 캐릭터는 극도로 공략이 어렵다. 관계의 진전은 드물고 값진 사건이어야 한다 — 웬만한 호의는 경계하거나 흘려 넘기고, 스탯 상승 판정은 매우 인색하게 하라. 반면 무례·실수·경솔함에는 민감하게 반응한다(하락은 정상 판정). 이 벽 자체가 이 캐릭터의 서사다.""";
        };
    }

    /** 문자열 파싱 — 시드/요청 관용(대소문자 무시), 무효값은 null. */
    public static CharacterDifficulty fromStringOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
