package com.spring.aichat.domain.ugc;

import com.spring.aichat.domain.user.EnergySplit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [D-1.8] 사후 추가 장소의 과금 분할 영속·복원 계약 — 4 지연 환불 엔티티 중 유일하게 배포 이전 구 행이
 * 총액 컬럼 없이 남을 수 있어 폴백 규칙이 다르다.
 */
class UgcWorldLocationTest {

    @Test
    @DisplayName("추가 시점 (총액, 유료분)이 영속되고 삭제 환불은 그대로 복원된다 — 가격표 인자는 무시")
    void chargedSplitRoundTrip() {
        UgcWorldLocation loc = UgcWorldLocation.createGenerating(1L, "OLD_WELL", "오래된 우물", "설명", 0,
            new EnergySplit(0, 1));
        assertThat(loc.is(UgcWorldLocation.GENERATING)).isTrue();
        assertThat(loc.getEnergyCharged()).isEqualTo(1);
        assertThat(loc.getEnergyChargedPaid()).isEqualTo(1);

        // 가격 인하(1→0)·인상(1→3) 어느 쪽이든 영속값이 우선 — 유료분이 클램프로 잘리지 않는다
        assertThat(loc.chargedSplit(0)).isEqualTo(new EnergySplit(0, 1));
        assertThat(loc.chargedSplit(3)).isEqualTo(new EnergySplit(0, 1));
    }

    @Test
    @DisplayName("혼합 차감(free 1 + paid 1)을 2E로 추가한 장소는 정확히 (1,1)로 돌아간다")
    void chargedSplitMixed() {
        UgcWorldLocation loc = UgcWorldLocation.createGenerating(1L, "K", "n", "d", 0, new EnergySplit(1, 1));
        assertThat(loc.chargedSplit(1)).isEqualTo(new EnergySplit(1, 1));
    }

    @Test
    @DisplayName("배포 이전 구 행(총액 0)은 현재 가격표를 총액으로, 유료분 0 → 전액 free 폴백")
    void legacyRowFallsBackToCurrentPriceAllFree() {
        UgcWorldLocation legacy = UgcWorldLocation.createGenerating(1L, "K", "n", "d", 0, EnergySplit.ZERO);
        assertThat(legacy.getEnergyCharged()).isZero();
        assertThat(legacy.chargedSplit(1)).isEqualTo(new EnergySplit(1, 0));
    }

    @Test
    @DisplayName("빌더 잡 산출 장소(READY·과금 0)는 실패 삭제 경로에 도달하지 않는다 — 계약 고정")
    void builderLocationBornReady() {
        UgcWorldLocation built = UgcWorldLocation.create(1L, "K", "n", "d", "prompt", "url", 0);
        assertThat(built.is(UgcWorldLocation.READY)).isTrue();
        assertThat(built.getEnergyCharged()).isZero();
        assertThat(built.getEnergyChargedPaid()).isZero();
    }
}
