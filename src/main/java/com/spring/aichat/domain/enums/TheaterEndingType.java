package com.spring.aichat.domain.enums;

/**
 * [Phase 5.5-Theater] Theater 모드 엔딩 타입
 *
 * Theater의 엔딩은 [히로인 호감도] + [아바타 지배 스탯] 조합으로 결정된다.
 *
 * [해피 엔딩 계열 — 호감도 70+]
 * - CHARM_ENDING:     매력 지배 → "매혹의 엔딩"
 * - INTELLECT_ENDING: 지성 지배 → "영혼의 동반자"
 * - BOLDNESS_ENDING:  담력 지배 → "영웅 서사"
 * - EMPATHY_ENDING:   감수성 지배 → "치유의 엔딩"
 * - WIT_ENDING:       입담 지배 → "웃음의 엔딩"
 *
 * [중립/배드 엔딩 계열]
 * - FADED_ENDING:    호감도 20~69 → "스쳐간 인연"
 * - BITTER_ENDING:   호감도 -30 ~ 19 → "엇갈린 마음"
 * - ENEMY_ENDING:    호감도 -30 미만 → "원수의 엔딩"
 *
 * [특수 엔딩]
 * - UNFINISHED:      Act 4 미도달 중단 → "미완의 이야기" (세이브로 이어가기 가능)
 */
public enum TheaterEndingType {

    // 해피 엔딩 계열
    CHARM_ENDING("매혹의 엔딩", "HAPPY", AvatarStat.CHARM),
    INTELLECT_ENDING("영혼의 동반자", "HAPPY", AvatarStat.INTELLECT),
    BOLDNESS_ENDING("영웅 서사", "HAPPY", AvatarStat.BOLDNESS),
    EMPATHY_ENDING("치유의 엔딩", "HAPPY", AvatarStat.EMPATHY),
    WIT_ENDING("웃음의 엔딩", "HAPPY", AvatarStat.WIT),

    // 중립/배드 엔딩
    FADED_ENDING("스쳐간 인연", "NEUTRAL", null),
    BITTER_ENDING("엇갈린 마음", "BAD", null),
    ENEMY_ENDING("원수의 엔딩", "BAD", null),

    // 특수
    UNFINISHED("미완의 이야기", "INCOMPLETE", null);

    private final String titleKo;
    private final String moodCategory;   // HAPPY / NEUTRAL / BAD / INCOMPLETE
    private final AvatarStat dominantStat;

    TheaterEndingType(String titleKo, String moodCategory, AvatarStat dominantStat) {
        this.titleKo = titleKo;
        this.moodCategory = moodCategory;
        this.dominantStat = dominantStat;
    }

    public String getTitleKo() { return titleKo; }
    public String getMoodCategory() { return moodCategory; }
    public AvatarStat getDominantStat() { return dominantStat; }

    public boolean isHappy() { return "HAPPY".equals(moodCategory); }
    public boolean isBad() { return "BAD".equals(moodCategory); }
}