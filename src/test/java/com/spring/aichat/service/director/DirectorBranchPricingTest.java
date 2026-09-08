package com.spring.aichat.service.director;

import com.spring.aichat.service.cache.RedisCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * [F1 · blockd §A-3] 분기 과금의 서버측 재판정 계약.
 *
 * <p>이 저장소는 과금 경로에 자동 테스트가 0건이었다. 그런데 {@code resolveBranchCost}의 반환값이
 * <b>돈을 결정</b>한다 — 값이 있으면 그 가격을 청구하고 분기 특권(constraint 주입 · 나레이션 가시 저장)을
 * 주고, 비어 있으면 호출부가 <b>일반 턴으로 강등</b>한다. 그래서 '언제 비는가'가 계약의 핵심이고,
 * 그 경계를 여기서 고정한다.
 *
 * <p>특히 <b>빈 Optional이 나와야 하는 4가지</b>를 못박는다 — 이 중 하나라도 값을 반환하도록 퇴화하면
 * 발급된 적 없는 분기가 과금·효과를 얻는다(F1의 원래 결함면).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("[F1] 분기 가격표 재판정 — 서버가 '발급한 적 있는가'를 판정한다")
class DirectorBranchPricingTest {

    private static final Long ROOM_ID = 42L;
    private static final String KEY = "director:branchprice:" + ROOM_ID;

    @Mock RedisCacheService cacheService;

    @InjectMocks DirectorService directorService;

    private void givenCached(List<?> costs) {
        when(cacheService.get(eq(KEY), eq(List.class))).thenReturn(Optional.of(costs));
    }

    // ── 값이 나와야 하는 경우 ────────────────────────────────────────────────

    @Test
    @DisplayName("발급된 분기는 서버가 캐싱해 둔 가격을 그대로 돌려준다")
    void resolvesCachedCost() {
        givenCached(List.of(2, 3, 4));

        assertThat(directorService.resolveBranchCost(ROOM_ID, 0)).contains(2);
        assertThat(directorService.resolveBranchCost(ROOM_ID, 1)).contains(3);
        assertThat(directorService.resolveBranchCost(ROOM_ID, 2)).contains(4);
    }

    @Test
    @DisplayName("가격표를 읽기만 하고 소비(evict)하지 않는다 — 소비는 턴 성공 후 별도 호출이다")
    void resolveDoesNotEvict() {
        givenCached(List.of(2, 3, 4));

        directorService.resolveBranchCost(ROOM_ID, 2);

        // [docs/19 §F D-8] 여기서 evict하면 Redis가 DB 롤백을 안 따라가므로,
        // 에너지 부족·스트림 실패로 보상 롤백이 돌 때 가격표만 사라져 재시도가 1E로 강등된다.
        verify(cacheService, never()).evict(anyString());
    }

    // ── 비어야 하는 경우 — 하나라도 값을 내면 F1이 되살아난다 ──────────────────

    @Test
    @DisplayName("chosenIndex가 없으면 비운다 — 요청에서 인덱스만 빼는 것이 원래 착취 경로였다")
    void emptyWhenIndexMissing() {
        assertThat(directorService.resolveBranchCost(ROOM_ID, null)).isEmpty();
        verify(cacheService, never()).get(anyString(), any());
    }

    @Test
    @DisplayName("음수 인덱스는 비운다")
    void emptyWhenIndexNegative() {
        assertThat(directorService.resolveBranchCost(ROOM_ID, -1)).isEmpty();
    }

    @Test
    @DisplayName("가격표가 만료·부재면 비운다 — '발급한 적 없음'과 같은 취급이다")
    void emptyWhenCacheMiss() {
        when(cacheService.get(eq(KEY), eq(List.class))).thenReturn(Optional.empty());

        assertThat(directorService.resolveBranchCost(ROOM_ID, 0)).isEmpty();
    }

    @Test
    @DisplayName("범위를 벗어난 인덱스는 비운다")
    void emptyWhenIndexOutOfRange() {
        givenCached(List.of(2, 3, 4));

        assertThat(directorService.resolveBranchCost(ROOM_ID, 3)).isEmpty();
        assertThat(directorService.resolveBranchCost(ROOM_ID, 99)).isEmpty();
    }

    @Test
    @DisplayName("캐시가 깨져 있어도 던지지 않고 비운다 — 과금 경로가 예외로 죽으면 안 된다")
    void emptyWhenCacheThrows() {
        when(cacheService.get(eq(KEY), eq(List.class))).thenThrow(new RuntimeException("redis down"));

        assertThat(directorService.resolveBranchCost(ROOM_ID, 0)).isEmpty();
    }

    // ── 클램프 — energy_cost는 LLM이 만든 JSON 필드다 ────────────────────────

    @Test
    @DisplayName("0 이하가 캐시돼 있어도 최소 1E로 올린다 — 무료 분기를 만들지 않는다")
    void clampsNonPositiveToOne() {
        givenCached(List.of(0, -5));

        assertThat(directorService.resolveBranchCost(ROOM_ID, 0)).contains(1);
        assertThat(directorService.resolveBranchCost(ROOM_ID, 1)).contains(1);
    }

    @Test
    @DisplayName("상한을 넘는 값은 4E로 자른다 — 모델 출력이 과다 청구가 되지 않게")
    void clampsAboveMaxToFour() {
        // 프롬프트 규칙은 normal 2 · affection 3 · secret 4다. 그보다 큰 값은 모델 일탈이다.
        givenCached(List.of(99, 5));

        assertThat(directorService.resolveBranchCost(ROOM_ID, 0)).contains(4);
        assertThat(directorService.resolveBranchCost(ROOM_ID, 1)).contains(4);
    }
}
