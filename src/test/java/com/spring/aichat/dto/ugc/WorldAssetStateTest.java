package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [UGC 세계관 빌더] 일러 컷 리롤 누적 상태 계약 테스트 — {@link EmotionAssetStateTest} 동형.
 */
class WorldAssetStateTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("리롤 누적: readyWith는 히스토리에 누적하고 최신본을 선택본으로 한다")
    void readyWithAccumulates() {
        WorldAssetState v1 = WorldAssetState.generating(0).readyWith("k1");
        WorldAssetState v2 = v1.generatingAgain(0).readyWith("k2");

        assertThat(v2.history()).containsExactly("k1", "k2");
        assertThat(v2.key()).isEqualTo("k2");
        // 리롤 진행 중에도 직전 선택본 유지 (프런트 스피너 오버레이용)
        assertThat(v1.generatingAgain(0).key()).isEqualTo("k1");
        assertThat(v1.generatingAgain(0).is(WorldAssetState.GENERATING)).isTrue();
    }

    @Test
    @DisplayName("버전 골라잡기: 이전 버전 선택 시 key만 바뀌고 히스토리는 유지된다")
    void selectVersionKeepsHistory() {
        WorldAssetState state = WorldAssetState.generating(0).readyWith("k1")
            .generatingAgain(0).readyWith("k2")
            .selectVersion(0);

        assertThat(state.key()).isEqualTo("k1");
        assertThat(state.history()).containsExactly("k1", "k2");
        assertThat(state.is(WorldAssetState.READY)).isTrue();
    }

    @Test
    @DisplayName("재시도 소진: 완성본이 있으면 READY 복귀, 없으면 FAILED")
    void exhaustionPolicy() {
        WorldAssetState withVersion = WorldAssetState.generating(0).readyWith("k1").generatingAgain(3);
        assertThat(withVersion.hasCompletedVersion()).isTrue();
        assertThat(withVersion.revertToReady().is(WorldAssetState.READY)).isTrue();
        assertThat(withVersion.revertToReady().key()).isEqualTo("k1");

        WorldAssetState neverCompleted = WorldAssetState.generating(3);
        assertThat(neverCompleted.hasCompletedVersion()).isFalse();
        assertThat(neverCompleted.failed().is(WorldAssetState.FAILED)).isTrue();
    }

    @Test
    @DisplayName("컨테이너: 썸네일+전 장소 정착/READY 판정과 JSON 왕복")
    void containerSettlementAndRoundtrip() throws Exception {
        WorldIllustrationAssets assets = WorldIllustrationAssets.empty()
            .withThumbnail(WorldAssetState.generating(0))
            .withLocation("ROOFTOP_GARDEN", WorldAssetState.generating(0));
        assertThat(assets.allSettled()).isFalse();

        assets = assets.withThumbnail(assets.thumbnail().readyWith("t1"))
            .withLocation("ROOFTOP_GARDEN", assets.locations().get("ROOFTOP_GARDEN").failed());
        assertThat(assets.allSettled()).isTrue();  // FAILED 포함 정착 → REVIEW_WAIT (무료 재시도)
        assertThat(assets.allReady()).isFalse();   // confirm은 불가

        assets = assets.withLocation("ROOFTOP_GARDEN",
            assets.locations().get("ROOFTOP_GARDEN").generatingAgain(0).readyWith("b1"));
        assertThat(assets.allReady()).isTrue();

        // TEXT-JSON 왕복 (잡 스크래치 저장 계약)
        String json = om.writeValueAsString(assets);
        WorldIllustrationAssets back = om.readValue(json, WorldIllustrationAssets.class);
        assertThat(back.thumbnail().key()).isEqualTo("t1");
        assertThat(back.locations()).containsKey("ROOFTOP_GARDEN");
        assertThat(back.allReady()).isTrue();
    }

    @Test
    @DisplayName("하위 호환: history 없는 JSON은 현 key를 유일 버전으로 승격한다")
    void legacyJsonWithoutHistory() throws Exception {
        WorldAssetState state = om.readValue(
            "{\"status\":\"READY\",\"key\":\"ugc/world-jobs/4/thumb_a.png\",\"retryCount\":1}",
            WorldAssetState.class);
        assertThat(state.history()).containsExactly("ugc/world-jobs/4/thumb_a.png");
    }

    @Test
    @DisplayName("빈 locations는 null 안전하게 빈 맵으로 정규화된다")
    void nullLocationsNormalized() throws Exception {
        WorldIllustrationAssets assets = om.readValue("{\"thumbnail\":null}", WorldIllustrationAssets.class);
        assertThat(assets.locations()).isEqualTo(Map.of());
        assertThat(assets.allSettled()).isFalse(); // 썸네일 없음 → 미정착
    }
}
