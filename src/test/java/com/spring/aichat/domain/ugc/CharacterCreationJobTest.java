package com.spring.aichat.domain.ugc;

import com.spring.aichat.domain.enums.CharacterDifficulty;
import com.spring.aichat.domain.user.EnergySplit;
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
        CharacterCreationJob job = CharacterCreationJob.start(1L, null, "컨셉", new EnergySplit(6, 0));
        job.markStagedBilling();

        assertThat(job.isStagedBilling()).isTrue();
        assertThat(job.getBillingMode()).isEqualTo(CharacterCreationJob.BILLING_MODE_STAGED);
        assertThat(job.getEnergyCharged()).isEqualTo(6);

        job.chargeEnergy(new EnergySplit(4, 0));  // 스탠딩 진입
        job.chargeEnergy(new EnergySplit(3, 5));  // 감정 진입 — free 3 소진 후 paid 5
        job.chargeEnergy(new EnergySplit(0, 2));  // 마무리 진입 — 전부 paid
        assertThat(job.getEnergyCharged()).isEqualTo(20); // 완주 = 선차감 총액과 동일 (환불 정산 기준)
        // [D-1.6] 유료분 누산 — failAndRefund가 이 분할로 되돌린다
        assertThat(job.getEnergyChargedPaid()).isEqualTo(7);
        assertThat(job.chargedSplit()).isEqualTo(new EnergySplit(13, 7));
    }

    @Test
    @DisplayName("[D-1.6] 구 행(paid 컬럼 0)은 전액 free 분할로 복원된다 — 보수적 폴백")
    void legacyRowFallsBackToAllFree() {
        CharacterCreationJob job = CharacterCreationJob.start(1L, null, "컨셉", new EnergySplit(20, 0));
        assertThat(job.chargedSplit()).isEqualTo(new EnergySplit(20, 0));
    }

    @Test
    @DisplayName("레거시 잡: billing_mode null → 단계 차감 전부 스킵 판정 (선차감 20 유지)")
    void legacyJobSkipsStagedBilling() {
        CharacterCreationJob job = CharacterCreationJob.start(1L, null, "컨셉", new EnergySplit(20, 0));
        assertThat(job.isStagedBilling()).isFalse();
        assertThat(job.getBillingMode()).isNull();
        assertThat(job.getEnergyCharged()).isEqualTo(20);
    }

    // ── [2026-08-05 난이도] 위저드 난이도 지정 계약 ──

    @Test
    @DisplayName("난이도 지정 잡: assignRequestedDifficulty → 바인딩 주입 소스로 노출")
    void requestedDifficultyAssigned() {
        CharacterCreationJob job = CharacterCreationJob.start(1L, null, "컨셉", new EnergySplit(6, 0));
        job.assignRequestedDifficulty(CharacterDifficulty.HARD);
        assertThat(job.getRequestedDifficultyOrNull()).isEqualTo(CharacterDifficulty.HARD);
    }

    @Test
    @DisplayName("난이도 미지정 잡: null 유지 — 바인딩 미설정 → 소비처 NORMAL 폴백 계약 보존")
    void requestedDifficultyDefaultsToNull() {
        CharacterCreationJob job = CharacterCreationJob.start(1L, null, "컨셉", new EnergySplit(6, 0));
        assertThat(job.getRequestedDifficultyOrNull()).isNull();
    }
}
