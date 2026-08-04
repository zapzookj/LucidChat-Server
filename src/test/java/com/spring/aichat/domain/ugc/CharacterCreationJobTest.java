package com.spring.aichat.domain.ugc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-08-04 단계 과금] 캐릭터 잡 과금 모드 계약 테스트 — 레거시 호환의 단일 기준.
 */
class CharacterCreationJobTest {

    @Test
    @DisplayName("신규 잡: STAGED 마킹 시 단계 차감 대상 — 시작 6만 선기록, 단계 진입마다 누적")
    void stagedBillingAccumulates() {
        CharacterCreationJob job = CharacterCreationJob.start(1L, null, "컨셉", 6);
        job.markStagedBilling();

        assertThat(job.isStagedBilling()).isTrue();
        assertThat(job.getBillingMode()).isEqualTo(CharacterCreationJob.BILLING_MODE_STAGED);
        assertThat(job.getEnergyCharged()).isEqualTo(6);

        job.chargeEnergy(4);  // 스탠딩 진입
        job.chargeEnergy(8);  // 감정 진입
        job.chargeEnergy(2);  // 마무리 진입
        assertThat(job.getEnergyCharged()).isEqualTo(20); // 완주 = 선차감 총액과 동일 (환불 정산 기준)
    }

    @Test
    @DisplayName("레거시 잡: billing_mode null → 단계 차감 전부 스킵 판정 (선차감 20 유지)")
    void legacyJobSkipsStagedBilling() {
        CharacterCreationJob job = CharacterCreationJob.start(1L, null, "컨셉", 20);
        assertThat(job.isStagedBilling()).isFalse();
        assertThat(job.getBillingMode()).isNull();
        assertThat(job.getEnergyCharged()).isEqualTo(20);
    }
}
