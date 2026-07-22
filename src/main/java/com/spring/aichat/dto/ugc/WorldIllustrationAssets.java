package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [UGC 세계관 빌더] W2 일러 상태 컨테이너 —
 * {@code UgcWorldCreationJob.illustrationAssetsJson}의 루트 타입.
 *
 * @param thumbnail 월드 대표 썸네일 상태
 * @param locations locationKey → 장소 배경 상태 (드래프트 장소와 1:1)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorldIllustrationAssets(WorldAssetState thumbnail, Map<String, WorldAssetState> locations) {

    public WorldIllustrationAssets {
        if (locations == null) locations = new LinkedHashMap<>();
    }

    public static WorldIllustrationAssets empty() {
        return new WorldIllustrationAssets(null, new LinkedHashMap<>());
    }

    public WorldIllustrationAssets withThumbnail(WorldAssetState state) {
        return new WorldIllustrationAssets(state, locations);
    }

    public WorldIllustrationAssets withLocation(String locationKey, WorldAssetState state) {
        Map<String, WorldAssetState> next = new LinkedHashMap<>(locations);
        next.put(locationKey, state);
        return new WorldIllustrationAssets(thumbnail, next);
    }

    /** 썸네일 + 전 장소가 정착(READY/FAILED)했는가 — REVIEW_WAIT 전이 판정. */
    public boolean allSettled() {
        return thumbnail != null && thumbnail.isSettled()
            && locations.values().stream().allMatch(WorldAssetState::isSettled);
    }

    /** 썸네일 + 전 장소 READY — confirm 가능 판정. */
    public boolean allReady() {
        return thumbnail != null && thumbnail.is(WorldAssetState.READY)
            && locations.values().stream().allMatch(s -> s.is(WorldAssetState.READY));
    }
}
