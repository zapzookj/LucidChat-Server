package com.spring.aichat.domain.ugc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [UGC 세계관 빌더] 월드 잡 상태 머신 계약 테스트.
 */
class UgcWorldCreationJobTest {

    @Test
    @DisplayName("정상 경로: CONCEPT→EDIT_WAIT→ILLUSTRATING→REVIEW_WAIT→BINDING→READY, WAIT에서만 expiresAt")
    void happyPath() {
        UgcWorldCreationJob job = UgcWorldCreationJob.start(1L, "달빛 학원", "몽환", "컨셉 서술", 10);
        assertThat(job.getStatus()).isEqualTo(WorldCreationJobStatus.CONCEPT_PROCESSING);
        assertThat(job.getEnergyCharged()).isEqualTo(10);

        job.toEditWait("{\"name\":\"달빛 학원\"}", 72);
        assertThat(job.getStatus()).isEqualTo(WorldCreationJobStatus.EDIT_WAIT);
        assertThat(job.getExpiresAt()).isNotNull();

        job.toIllustrating();
        assertThat(job.getExpiresAt()).isNull();

        job.toReviewWait(72);
        assertThat(job.getStatus().isWait()).isTrue();
        assertThat(job.getExpiresAt()).isNotNull();

        job.toBinding();
        assertThat(job.getExpiresAt()).isNull();

        job.toReady(42L);
        assertThat(job.getUgcWorldId()).isEqualTo(42L);
        assertThat(job.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("fail/expire는 멱등이고, 종결 후 전이 시도는 IllegalStateException")
    void terminalGuards() {
        UgcWorldCreationJob job = UgcWorldCreationJob.start(1L, null, null, "컨셉", 10);
        job.fail("실패 사유");
        assertThat(job.getStatus()).isEqualTo(WorldCreationJobStatus.FAILED);

        job.fail("두 번째 사유"); // 멱등 — 상태·사유 유지
        assertThat(job.getFailReason()).isEqualTo("실패 사유");
        job.expire(); // 멱등
        assertThat(job.getStatus()).isEqualTo(WorldCreationJobStatus.FAILED);

        assertThatThrownBy(() -> job.toEditWait("{}", 72))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("리롤 과금은 energyCharged에 누적된다 (환불 정산 기준)")
    void chargeAccumulates() {
        UgcWorldCreationJob job = UgcWorldCreationJob.start(1L, null, null, "컨셉", 10);
        job.chargeEnergy(1);
        job.chargeEnergy(1);
        assertThat(job.getEnergyCharged()).isEqualTo(12);
    }

    @Test
    @DisplayName("세계관 3택 기록: 공식·UGC 동시 지정은 불가")
    void requestedWorldXor() {
        var characterJob = CharacterCreationJob.start(1L, null, "컨셉", 20);
        characterJob.assignRequestedWorld(null, 7L);
        assertThat(characterJob.getRequestedUgcWorldId()).isEqualTo(7L);

        assertThatThrownBy(() -> characterJob.assignRequestedWorld(
            com.spring.aichat.domain.enums.WorldId.MEDIEVAL_FANTASY, 7L))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
