package com.spring.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * [Story V2] Character Routine 시드 설정.
 *
 * <p>application.yml 예시 (한 캐릭터의 시간대별 루틴):
 * <pre>
 * app:
 *   v2-seeds:
 *     character-routines:
 *       - character-slug: claire
 *         time-of-day: MORNING
 *         location-key: CATHEDRAL
 *         probability: 80
 *         notes: 새벽 기도 시간
 *       - character-slug: claire
 *         time-of-day: MORNING
 *         location-key: GARDEN
 *         probability: 20
 *         notes: 기도 후 산책
 *       - character-slug: claire
 *         time-of-day: AFTERNOON
 *         location-key: LIBRARY
 *         probability: 60
 *       ...
 * </pre>
 *
 * <p>같은 (character, time-of-day)에 여러 후보 가능 — 확률 가중치 합산 후 단일 선택.
 *
 * <p>업데이트 정책: 시드 재실행 시 *전체 캐릭터의 모든 루틴 삭제 + 재삽입*.
 * 한 캐릭터의 (timeOfDay, locationKey) 조합이 1:N이라 *부분 update*가 불가능.
 */
@ConfigurationProperties(prefix = "app.v2")
public record CharacterRoutineSeedProperties(
    List<RoutineSeed> characterRoutines
) {

    public record RoutineSeed(
        /**
         * 캐릭터 slug (예: "claire"). CharacterRepository.findBySlug로 ID 조회.
         * V1 CharacterSeeder가 먼저 실행되어 slug → id가 안정.
         */
        String characterSlug,

        /** "MORNING" | "NOON" | "AFTERNOON" | "EVENING" | "NIGHT" */
        String timeOfDay,

        /** WorldLocation.locationKey 참조. */
        String locationKey,

        /** 확률 (0~100). 같은 timeOfDay 내 합산이 100일 필요는 없음 (총합으로 정규화). */
        Integer probability,

        /** 내부 메모 — 디버그/기획용. */
        String notes
    ) {}
}