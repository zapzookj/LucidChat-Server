package com.spring.aichat.domain.ugc;

import com.spring.aichat.domain.enums.CharacterDifficulty;
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

    // ── [2026-08-05 난이도] 위저드 난이도 지정 계약 ──

    @Test
    @DisplayName("난이도 지정 잡: assignRequestedDifficulty → 바인딩 주입 소스로 노출")
    void requestedDifficultyAssigned() {
        CharacterCreationJob job = CharacterCreationJob.start(1L, null, "컨셉", 6);
        job.assignRequestedDifficulty(CharacterDifficulty.HARD);
        assertThat(job.getRequestedDifficultyOrNull()).isEqualTo(CharacterDifficulty.HARD);
    }

    @Test
    @DisplayName("난이도 미지정 잡: null 유지 — 바인딩 미설정 → 소비처 NORMAL 폴백 계약 보존")
    void requestedDifficultyDefaultsToNull() {
        CharacterCreationJob job = CharacterCreationJob.start(1L, null, "컨셉", 6);
        assertThat(job.getRequestedDifficultyOrNull()).isNull();
    }
}
