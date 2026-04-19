package com.spring.aichat.domain.enums;

/**
 * [Phase 5.5-Theater] Theater 모드의 Act (막) 구분
 *
 * 4막 구조:
 * - ACT_1_MEETING:    만남 (Opening, 각 히로인과의 초회 만남 보장)
 * - ACT_2_BONDING:    관계의 형성 (호감도 격차 벌어지기 시작)
 * - ACT_3_TURNING:    전환점 (메인 히로인 수렴, Climax 근접)
 * - ACT_4_RESOLUTION: 결말 (엔딩 수렴, 메인 히로인 확정)
 */
public enum TheaterAct {

    ACT_1_MEETING(1, "만남", "새로운 세계와의 첫 조우"),
    ACT_2_BONDING(2, "관계의 형성", "엇갈리는 감정, 쌓여가는 인연"),
    ACT_3_TURNING(3, "전환점", "선택의 순간이 다가온다"),
    ACT_4_RESOLUTION(4, "결말", "이야기의 끝에서");

    private final int number;
    private final String title;
    private final String subtitle;

    TheaterAct(int number, String title, String subtitle) {
        this.number = number;
        this.title = title;
        this.subtitle = subtitle;
    }

    public int getNumber() { return number; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }

    public boolean isFinalAct() { return this == ACT_4_RESOLUTION; }

    public TheaterAct next() {
        return switch (this) {
            case ACT_1_MEETING -> ACT_2_BONDING;
            case ACT_2_BONDING -> ACT_3_TURNING;
            case ACT_3_TURNING -> ACT_4_RESOLUTION;
            case ACT_4_RESOLUTION -> null;
        };
    }

    public static TheaterAct fromNumber(int n) {
        return switch (n) {
            case 1 -> ACT_1_MEETING;
            case 2 -> ACT_2_BONDING;
            case 3 -> ACT_3_TURNING;
            case 4 -> ACT_4_RESOLUTION;
            default -> ACT_1_MEETING;
        };
    }
}