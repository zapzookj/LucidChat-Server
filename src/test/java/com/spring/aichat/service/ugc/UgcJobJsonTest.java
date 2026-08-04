package com.spring.aichat.service.ugc;

import com.spring.aichat.service.ugc.UgcJobJson.GoldenSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-08-05 디자인 리롤] 황금샷 배치 스냅샷 해석 계약 — 선택 인덱스가 속한 배치의
 * 외형 스냅샷을 복원하는 정합 로직(startIndex ≤ index 최대, 재시도 중복은 마지막 것).
 */
class UgcJobJsonTest {

    @Test
    void resolvesBatchByStartIndex() {
        List<GoldenSnapshot> snaps = List.of(
            new GoldenSnapshot(0, "c0"),   // 배치 1 (키 0·1)
            new GoldenSnapshot(2, "c1"),   // 배치 2 (키 2·3)
            new GoldenSnapshot(4, "c2"));  // 배치 3 (키 4·5)

        assertThat(UgcJobJson.resolveSnapshot(snaps, 0).conceptJson()).isEqualTo("c0");
        assertThat(UgcJobJson.resolveSnapshot(snaps, 1).conceptJson()).isEqualTo("c0");
        assertThat(UgcJobJson.resolveSnapshot(snaps, 2).conceptJson()).isEqualTo("c1");
        assertThat(UgcJobJson.resolveSnapshot(snaps, 5).conceptJson()).isEqualTo("c2");
    }

    @Test
    void retryDuplicateTakesLastRecord() {
        // 재시도 재제출로 동일 startIndex가 중복 기록된 경우 — 마지막 기록(동일 내용) 채택
        List<GoldenSnapshot> snaps = List.of(
            new GoldenSnapshot(0, "c0"),
            new GoldenSnapshot(2, "c1-retry1"),
            new GoldenSnapshot(2, "c1-retry2"));

        assertThat(UgcJobJson.resolveSnapshot(snaps, 3).conceptJson()).isEqualTo("c1-retry2");
    }

    @Test
    void legacyJobWithoutSnapshotsResolvesNull() {
        assertThat(UgcJobJson.resolveSnapshot(List.of(), 0)).isNull();
    }
}
