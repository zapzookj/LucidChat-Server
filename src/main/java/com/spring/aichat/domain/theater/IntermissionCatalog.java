package com.spring.aichat.domain.theater;

import com.spring.aichat.domain.enums.AvatarStat;
import com.spring.aichat.dto.theater.TheaterResponses.IntermissionActivity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * [Phase 5.5-Theater] 인터미션 활동 카탈로그
 *
 * 정적 마스터 데이터 — DB 테이블 없이 코드에 상수로 관리.
 * 향후 활동이 많아지면 DB 이전 가능.
 *
 * [구성]
 * - 기본 활동 5종 (5축 스탯 각각 하나씩)
 * - 특별 활동 5종 (확률적 등장, 서사적 깊이 부여)
 */
public final class IntermissionCatalog {

    private IntermissionCatalog() {}

    /** 특별 활동이 등장할 확률 (0.0 ~ 1.0) */
    public static final double SPECIAL_ACTIVITY_PROBABILITY = 0.15;

    /** 특별 활동 등장 시 기본 활동 중 교체할 최대 개수 */
    public static final int SPECIAL_REPLACE_MAX = 2;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  기본 활동 (5종)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final IntermissionActivity WORKOUT = new IntermissionActivity(
        "workout",
        "운동한다",
        "몸을 움직여 활력을 되찾는다. 거울 속 자신이 조금 달라 보인다.",
        "💪",
        AvatarStat.CHARM.name(),
        "workout_animation",
        false, 1, 2
    );

    private static final IntermissionActivity PRACTICE_SPEECH = new IntermissionActivity(
        "practice_speech",
        "독백을 연습한다",
        "거울 앞에서 말을 고른다. 어떻게 말해야 상대의 마음을 움직일까.",
        "🗣",
        AvatarStat.WIT.name(),
        "speech_animation",
        false, 1, 2
    );

    private static final IntermissionActivity READ_ADVENTURE = new IntermissionActivity(
        "read_adventure",
        "모험담을 읽는다",
        "책 속 영웅들의 이야기가 가슴을 뜨겁게 한다. 두려움을 조금씩 밀어낸다.",
        "🏔",
        AvatarStat.BOLDNESS.name(),
        "adventure_animation",
        false, 1, 2
    );

    private static final IntermissionActivity STUDY = new IntermissionActivity(
        "study",
        "독서한다",
        "책장을 넘길 때마다 세상이 조금 더 선명해진다.",
        "📚",
        AvatarStat.INTELLECT.name(),
        "study_animation",
        false, 1, 2
    );

    private static final IntermissionActivity WALK = new IntermissionActivity(
        "walk",
        "산책한다",
        "바람결에 실려오는 작은 풍경들. 놓치고 있던 감각이 깨어난다.",
        "🌸",
        AvatarStat.EMPATHY.name(),
        "walk_animation",
        false, 1, 2
    );

    public static final List<IntermissionActivity> BASE_ACTIVITIES = List.of(
        WORKOUT, PRACTICE_SPEECH, READ_ADVENTURE, STUDY, WALK
    );

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  특별 활동 (5종)
    //
    //  - 등장 확률: 15%
    //  - 이번 인터미션에서 기본 활동 중 1~2개를 교체
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final IntermissionActivity SPECIAL_ENCOUNTER = new IntermissionActivity(
        "special_encounter",
        "우연한 마주침",
        "낯익은 뒷모습을 발견한다. 그녀가 있었다.",
        "💫",
        null,  // 특수: 특정 히로인 호감도 +3
        "encounter_animation",
        true, 1, 3
    );

    private static final IntermissionActivity SPECIAL_STRANGER = new IntermissionActivity(
        "special_stranger",
        "낯선 이가 말을 건다",
        "누군가 당신을 불러세운다. 예상치 못한 대화가 시작된다.",
        "👥",
        null,  // 특수: 미니 분기 → 결과에 따라 스탯 여러 개 동시 상승
        "stranger_animation",
        true, 1, 3
    );

    private static final IntermissionActivity SPECIAL_MIRROR = new IntermissionActivity(
        "special_mirror",
        "거울을 본다",
        "한참을 들여다본다. 이번 장(章)에서 내가 마주한 순간들이 스쳐 지나간다.",
        "🪞",
        null,  // 특수: 랜덤 스탯 +1 + 감독 노트 자동 추가
        "mirror_animation",
        true, 1, 3
    );

    private static final IntermissionActivity SPECIAL_LETTER = new IntermissionActivity(
        "special_letter",
        "편지를 쓴다",
        "누군가에게 전하지 못한 말들. 종이 위로 마음이 흘러간다.",
        "✉️",
        AvatarStat.EMPATHY.name(),  // 감수성 대폭 상승 (성공/대성공 확률 보너스)
        "letter_animation",
        true, 1, 3
    );

    private static final IntermissionActivity SPECIAL_DREAM = new IntermissionActivity(
        "special_dream",
        "꿈을 꾼다",
        "잠깐의 휴식이 깊은 꿈으로 이어진다. 무언가 달라진 느낌이다.",
        "💤",
        null,  // 특수: 무작위 2개 스탯 각 +1
        "dream_animation",
        true, 1, 3
    );

    public static final List<IntermissionActivity> SPECIAL_ACTIVITIES = List.of(
        SPECIAL_ENCOUNTER, SPECIAL_STRANGER, SPECIAL_MIRROR, SPECIAL_LETTER, SPECIAL_DREAM
    );

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  조회 유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public static Optional<IntermissionActivity> findById(String id) {
        if (id == null) return Optional.empty();
        for (IntermissionActivity a : BASE_ACTIVITIES) {
            if (a.id().equals(id)) return Optional.of(a);
        }
        for (IntermissionActivity a : SPECIAL_ACTIVITIES) {
            if (a.id().equals(id)) return Optional.of(a);
        }
        return Optional.empty();
    }

    /**
     * 스탯 구간별 성공률 테이블
     *
     * @return [대성공%, 성공%, 실패%]
     */
    public static Map<String, Integer> getSuccessDistribution(int currentStatValue) {
        if (currentStatValue <= 20) {
            return Map.of("GREAT_SUCCESS", 15, "SUCCESS", 65, "FAIL", 20);
        } else if (currentStatValue <= 50) {
            return Map.of("GREAT_SUCCESS", 10, "SUCCESS", 55, "FAIL", 35);
        } else if (currentStatValue <= 80) {
            return Map.of("GREAT_SUCCESS", 10, "SUCCESS", 40, "FAIL", 50);
        } else {
            return Map.of("GREAT_SUCCESS", 5, "SUCCESS", 25, "FAIL", 70);
        }
    }

    /** 결과에 따른 스탯 증가량 */
    public static int getStatDelta(String outcome) {
        return switch (outcome) {
            case "GREAT_SUCCESS" -> 4;
            case "SUCCESS" -> 2;
            case "FAIL" -> 0;
            default -> 0;
        };
    }

    /** 결과별 나레이션 */
    public static String getResultNarration(String outcome, AvatarStat stat) {
        String statName = stat != null ? stat.getDisplayName() : "";
        return switch (outcome) {
            case "GREAT_SUCCESS" -> String.format("💥 대성공! %s이(가) 크게 상승했다.", statName);
            case "SUCCESS" -> String.format("✨ 성공. %s이(가) 한 뼘 성장했다.", statName);
            case "FAIL" -> "😑 별다른 수확 없이 시간이 지나갔다.";
            default -> "";
        };
    }
}