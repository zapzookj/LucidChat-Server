package com.spring.aichat.dto.ugc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [D-2.l · D-2.m] 유료 리롤 소진 → '다음 1회 무료' 자격의 상태 전이·JSON 하위호환 계약.
 *
 * <p>이 결함의 핵심은 <b>비대칭</b>이었다: 완성본이 없는 컷은 {@code failed()}로 가서 다음 리롤이
 * {@code is(FAILED)} 판정으로 무료가 되는데, 완성본이 있는 컷(= 돈 내고 리롤한 정확히 그 케이스)만
 * {@code revertToReady()}로 READY에 돌아가 다음 리롤도 또 과금이었다.
 *
 * <p>이 상태는 DB 컬럼이 아니라 잡 JSON({@code emotionAssetsJson}/{@code illustrationAssetsJson}) 안의
 * 값이므로 <b>마이그레이션이 없고, 대신 구 JSON 역직렬화가 조용히 깨질 수 있다</b>.
 * 컴파일러도 부팅도 그것을 잡지 않는다(§3) — 그래서 여기서 못 박는다.
 */
class RerollFreeCreditTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── 감정 컷 (D-2.l) ──

    @Test
    @DisplayName("감정 컷: 완성본 있는 유료 리롤이 소진되면 다음 1회가 무료다")
    void emotionRevertGrantsFreeRetry() {
        EmotionAssetState paidReroll = EmotionAssetState.ready("v1").derivingAgain(0);
        assertThat(paidReroll.isFreeReroll()).as("리롤 시작 시점엔 자격 없음").isFalse();

        EmotionAssetState exhausted = paidReroll.revertToReady();
        assertThat(exhausted.is(EmotionAssetState.READY)).isTrue();
        assertThat(exhausted.isFreeReroll()).as("소진 후 다음 1회 무료").isTrue();
    }

    @Test
    @DisplayName("감정 컷: 무료 자격은 1회용 — 리롤을 시작하면 소모된다")
    void emotionFreeCreditIsSingleUse() {
        EmotionAssetState credited = EmotionAssetState.ready("v1").derivingAgain(0).revertToReady();
        assertThat(credited.isFreeReroll()).isTrue();

        assertThat(credited.derivingAgain(0).isFreeReroll())
            .as("무료 리롤을 시작하면 자격이 사라진다").isFalse();
    }

    @Test
    @DisplayName("감정 컷: 리롤이 성공하면 자격이 남지 않는다")
    void emotionSuccessClearsCredit() {
        EmotionAssetState afterSuccess = EmotionAssetState.ready("v1")
            .derivingAgain(0).revertToReady()   // 1차 실패 → 무료 자격
            .derivingAgain(0)                   // 무료 리롤 시작
            .readyWith("v2");                   // 성공
        assertThat(afterSuccess.isFreeReroll()).isFalse();
        assertThat(afterSuccess.history()).containsExactly("v1", "v2");
    }

    @Test
    @DisplayName("감정 컷: FAILED는 종전대로 무과금 — 정책 대칭")
    void emotionFailedStaysFree() {
        assertThat(EmotionAssetState.deriving(0).failed().isFreeReroll()).isTrue();
    }

    @Test
    @DisplayName("감정 컷: 버전 골라잡기는 자격을 소모하지 않는다")
    void emotionSelectVersionKeepsCredit() {
        EmotionAssetState credited = EmotionAssetState.ready("v1")
            .readyWith("v2").derivingAgain(0).revertToReady();
        assertThat(credited.selectVersion(0).isFreeReroll()).isTrue();
    }

    @Test
    @DisplayName("감정 컷: freeRetry 없는 구 JSON은 '자격 없음'으로 읽힌다 (마이그레이션 불요의 근거)")
    void emotionLegacyJsonHasNoCredit() throws Exception {
        String legacy = """
            {"status":"READY","key":"k1","cutoutKey":null,"retryCount":0,"history":["k1"]}""";
        EmotionAssetState parsed = mapper.readValue(legacy, EmotionAssetState.class);

        assertThat(parsed.is(EmotionAssetState.READY)).isTrue();
        assertThat(parsed.key()).isEqualTo("k1");
        assertThat(parsed.history()).containsExactly("k1");
        assertThat(parsed.isFreeReroll()).as("구 행은 무료 자격이 없다 — 소급 지급하지 않는다").isFalse();
    }

    @Test
    @DisplayName("감정 컷: history 없는 더 오래된 JSON도 계속 읽힌다 (기존 하위호환 유지)")
    void emotionOldestJsonStillParses() throws Exception {
        String oldest = """
            {"status":"READY","key":"k1","cutoutKey":null,"retryCount":0}""";
        EmotionAssetState parsed = mapper.readValue(oldest, EmotionAssetState.class);
        assertThat(parsed.history()).containsExactly("k1");
        assertThat(parsed.isFreeReroll()).isFalse();
    }

    // ── 월드 에셋 (D-2.m) — 캐릭터 트랙과 복붙 계보라 같은 계약을 강제한다 ──

    @Test
    @DisplayName("월드 에셋: 완성본 있는 유료 리롤이 소진되면 다음 1회가 무료다")
    void worldRevertGrantsFreeRetry() {
        WorldAssetState paidReroll = WorldAssetState.generating(0).readyWith("v1").generatingAgain(0);
        assertThat(paidReroll.isFreeReroll()).isFalse();

        WorldAssetState exhausted = paidReroll.revertToReady();
        assertThat(exhausted.is(WorldAssetState.READY)).isTrue();
        assertThat(exhausted.isFreeReroll()).isTrue();
    }

    @Test
    @DisplayName("월드 에셋: 무료 자격은 1회용이고 성공 시 사라진다")
    void worldFreeCreditIsSingleUseAndClearedOnSuccess() {
        WorldAssetState credited = WorldAssetState.generating(0).readyWith("v1")
            .generatingAgain(0).revertToReady();
        assertThat(credited.isFreeReroll()).isTrue();
        assertThat(credited.generatingAgain(0).isFreeReroll()).isFalse();
        assertThat(credited.generatingAgain(0).readyWith("v2").isFreeReroll()).isFalse();
    }

    @Test
    @DisplayName("월드 에셋: FAILED는 종전대로 무과금")
    void worldFailedStaysFree() {
        assertThat(WorldAssetState.generating(0).failed().isFreeReroll()).isTrue();
    }

    @Test
    @DisplayName("월드 에셋: freeRetry 없는 구 JSON은 '자격 없음'으로 읽힌다")
    void worldLegacyJsonHasNoCredit() throws Exception {
        String legacy = """
            {"status":"READY","key":"w1","retryCount":0,"history":["w1"]}""";
        WorldAssetState parsed = mapper.readValue(legacy, WorldAssetState.class);

        assertThat(parsed.is(WorldAssetState.READY)).isTrue();
        assertThat(parsed.history()).containsExactly("w1");
        assertThat(parsed.isFreeReroll()).isFalse();
    }

    @Test
    @DisplayName("두 트랙의 무과금 판정 계약이 동일하다 (복붙 계보 재분기 방지)")
    void bothTracksAgreeOnFreePredicate() {
        List<Boolean> emotion = List.of(
            EmotionAssetState.ready("k").isFreeReroll(),                          // READY 신규 → 과금
            EmotionAssetState.ready("k").failed().isFreeReroll(),                 // FAILED → 무료
            EmotionAssetState.ready("k").derivingAgain(0).revertToReady().isFreeReroll()); // 소진 복귀 → 무료
        List<Boolean> world = List.of(
            WorldAssetState.generating(0).readyWith("k").isFreeReroll(),
            WorldAssetState.generating(0).readyWith("k").failed().isFreeReroll(),
            WorldAssetState.generating(0).readyWith("k").generatingAgain(0).revertToReady().isFreeReroll());

        assertThat(emotion).containsExactly(false, true, true);
        assertThat(world).isEqualTo(emotion);
    }
}
