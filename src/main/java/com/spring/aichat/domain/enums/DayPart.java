package com.spring.aichat.domain.enums;

/**
 * [V2 Story] World 안의 하루 시간대 — 캐릭터 루틴/씬 시간 관리용
 *
 * <p>기존 {@link TimeOfDay}(DAY/NIGHT/SUNSET 3단계)는 *배경 이미지 변형* 용도로
 * Theater/Story V1에서 계속 사용된다. V2 Story의 *시간 흐름*과 *캐릭터 루틴*은
 * 더 세분화된 5단계가 필요하여 별도 enum으로 분리.
 *
 * <p>{@link com.spring.aichat.domain.character.CharacterRoutine} 및
 * V2 ChatRoom 시간 흐름 표시에 사용된다.
 *
 * <p>대응 TimeOfDay 매핑 (배경 렌더링 시 폴백):
 *   - MORNING / NOON / AFTERNOON → DAY
 *   - EVENING                    → SUNSET
 *   - NIGHT                      → NIGHT
 */
public enum DayPart {
    /** 새벽 ~ 오전 (대략 06시 ~ 11시) */
    MORNING,
    /** 정오 ~ 이른 오후 (대략 11시 ~ 14시) */
    NOON,
    /** 늦은 오후 (대략 14시 ~ 17시) */
    AFTERNOON,
    /** 저녁 ~ 해질녘 (대략 17시 ~ 20시) */
    EVENING,
    /** 밤 (대략 20시 ~ 다음날 06시) */
    NIGHT;

    /**
     * 배경 렌더링용 TimeOfDay로 변환.
     * V2 Story에서 동적 배경 캐싱 시 기존 캐시 키 호환을 위해 사용.
     */
    public TimeOfDay toBackgroundTimeOfDay() {
        return switch (this) {
            case MORNING, NOON, AFTERNOON -> TimeOfDay.DAY;
            case EVENING                  -> TimeOfDay.SUNSET;
            case NIGHT                    -> TimeOfDay.NIGHT;
        };
    }

    /** 한국어 표시명 */
    public String displayName() {
        return switch (this) {
            case MORNING   -> "아침";
            case NOON      -> "낮";
            case AFTERNOON -> "오후";
            case EVENING   -> "저녁";
            case NIGHT     -> "밤";
        };
    }

    /** 디폴트 시작 시간대 — World 진입 시 시간이 명시되지 않으면 EVENING. */
    public static DayPart defaultStart() {
        return EVENING;
    }
}