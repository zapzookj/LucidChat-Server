package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [UGC 리롤 누적 2026-07-20] 감정 컷 버전 히스토리 계약 테스트.
 */
class EmotionAssetStateTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("하위 호환: history 없는 구버전 JSON은 현 key를 유일 버전으로 승격한다")
    void legacyJsonWithoutHistory() throws Exception {
        EmotionAssetState state = om.readValue(
            "{\"status\":\"READY\",\"key\":\"ugc/jobs/4/emo_joy_a.png\",\"cutoutKey\":null,\"retryCount\":1}",
            EmotionAssetState.class);

        assertThat(state.history()).containsExactly("ugc/jobs/4/emo_joy_a.png");
        assertThat(state.key()).isEqualTo("ugc/jobs/4/emo_joy_a.png");
    }

    @Test
    @DisplayName("리롤 누적: readyWith는 히스토리에 누적하고 최신본을 선택본으로 한다")
    void readyWithAccumulates() {
        EmotionAssetState v1 = EmotionAssetState.deriving(0).refining().readyWith("k1");
        EmotionAssetState v2 = v1.derivingAgain(0).refining().readyWith("k2");

        assertThat(v2.history()).containsExactly("k1", "k2");
        assertThat(v2.key()).isEqualTo("k2");
        // 리롤 진행 중에도 직전 선택본 유지 (프런트 스피너 오버레이용)
        assertThat(v1.derivingAgain(0).key()).isEqualTo("k1");
    }

    @Test
    @DisplayName("버전 골라잡기: 이전 버전 선택 시 key만 바뀌고 히스토리는 유지된다")
    void selectVersionKeepsHistory() {
        EmotionAssetState state = EmotionAssetState.deriving(0).readyWith("k1")
            .derivingAgain(0).readyWith("k2")
            .selectVersion(0);

        assertThat(state.key()).isEqualTo("k1");
        assertThat(state.history()).containsExactly("k1", "k2");
        assertThat(state.is(EmotionAssetState.READY)).isTrue();
    }

    @Test
    @DisplayName("재시도 소진: 완성본이 있으면 READY 복귀, 없으면 FAILED")
    void exhaustionPolicy() {
        EmotionAssetState withVersion = EmotionAssetState.deriving(0).readyWith("k1").derivingAgain(3);
        assertThat(withVersion.hasCompletedVersion()).isTrue();
        assertThat(withVersion.revertToReady().is(EmotionAssetState.READY)).isTrue();
        assertThat(withVersion.revertToReady().key()).isEqualTo("k1");

        EmotionAssetState neverCompleted = EmotionAssetState.deriving(3);
        assertThat(neverCompleted.hasCompletedVersion()).isFalse();
        assertThat(neverCompleted.failed().is(EmotionAssetState.FAILED)).isTrue();
    }
}
