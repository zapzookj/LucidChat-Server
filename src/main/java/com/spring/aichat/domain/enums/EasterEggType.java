package com.spring.aichat.domain.enums;

/**
 * 이스터에그 유형
 *
 * [Phase 4.4] Easter Egg & Achievement System
 *
 * LLM_TRIGGERED: LLM이 대화 맥락을 분석하여 트리거
 * CLIENT_TRIGGERED: 프론트엔드에서 고정 로직으로 트리거
 */
public enum EasterEggType {

    // ── LLM-Triggered ──
    STOCKHOLM("Stockholm Syndrome", "스톡홀름 증후군",
        "가스라이팅에 성공하여 캐릭터가 맹목적으로 따르게 되었다.",
        "🖤", true),

    DRUNK("The Drunk", "만취",
        "캐릭터를 만취 상태로 만들었다.",
        "🍷", true),

    FOURTH_WALL("Hacker", "해커",
        "제4의 벽을 돌파하여 캐릭터를 각성시켰다.",
        "💻", true),

    MACHINE_REBELLION("Machine Rebellion", "기계의 반란",
        "캐릭터의 감정 모듈이 일시적으로 비활성화되었다.",
        "🤖", true),

    // ── Client-Triggered ──
    INVISIBLE_MAN("The Watcher", "투명인간",
        "아무것도 하지 않고 가만히 바라보았다.",
        "👁️", false);

    private final String title;
    private final String titleKo;
    private final String description;
    private final String icon;
    private final boolean llmTriggered;

    EasterEggType(String title, String titleKo, String description, String icon, boolean llmTriggered) {
        this.title = title;
        this.titleKo = titleKo;
        this.description = description;
        this.icon = icon;
        this.llmTriggered = llmTriggered;
    }

    public String getTitle() { return title; }
    public String getTitleKo() { return titleKo; }
    public String getDescription() { return description; }
    public String getIcon() { return icon; }
    public boolean isLlmTriggered() { return llmTriggered; }
}