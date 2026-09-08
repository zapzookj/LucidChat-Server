package com.spring.aichat.domain.chat;

import com.spring.aichat.domain.enums.RelationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.spring.aichat.domain.chat.RelationStatusPolicy.isNewPeak;
import static com.spring.aichat.domain.chat.RelationStatusPolicy.isUpgrade;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * [G-3 · 안건 18 · blockd §A-8] 승급 세리머니 히스테리시스의 판정 계약.
 *
 * <p>고치는 증상 원문: <i>"관계 수치가 경계선(39↔40)에서 오르내릴 때마다 '관계 상승' 축하 연출이
 * 무한 반복된다 — 강등은 무연출이라 유저에게는 '올라감'만 계속 보인다."</i>
 *
 * <p>이 파일이 지키는 것은 두 가지다.
 * <ol>
 *   <li><b>{@code isUpgrade}와 {@code isNewPeak}는 다르다.</b> 전자는 '직전보다 위인가',
 *       후자는 '처음 도달했는가'다. 둘을 같은 것으로 본 것이 결함의 원인이었다.</li>
 *   <li><b>서열은 enum ordinal이 아니다.</b> {@code RelationStatus}의 선언 순서는
 *       STRANGER, ACQUAINTANCE, FRIEND, LOVER, <b>ENEMY</b>라 ENEMY가 ordinal 최상위다.
 *       관계 진전 서열에서 ENEMY는 STRANGER보다 <b>아래</b>이므로, ordinal로 비교하도록
 *       퇴화하면 <b>적대가 되는 것이 최고 기록으로 잡혀</b> 이후 모든 승급 연출이 사라진다.</li>
 * </ol>
 */
@DisplayName("[G-3] 승급 세리머니 — 최고 도달 단계 판정")
class RelationPeakPolicyTest {

    @Test
    @DisplayName("처음 올라간 단계는 새 최고 기록이다")
    void firstTimeReachingIsNewPeak() {
        assertThat(isNewPeak(RelationStatus.STRANGER, RelationStatus.ACQUAINTANCE)).isTrue();
        assertThat(isNewPeak(RelationStatus.ACQUAINTANCE, RelationStatus.FRIEND)).isTrue();
        assertThat(isNewPeak(RelationStatus.FRIEND, RelationStatus.LOVER)).isTrue();
    }

    @Test
    @DisplayName("★ 경계 진동 — 내려갔다 같은 단계로 돌아오면 연출하지 않는다 (이 결함의 본체)")
    void reEntryToSameLevelIsNotNewPeak() {
        // 39↔40 진동: ACQUAINTANCE 도달(연출) → STRANGER로 하락(무연출) → 다시 ACQUAINTANCE
        // 마지막 복귀는 isUpgrade는 참이지만 새 기록은 아니다 — 여기서 갈린다.
        assertThat(isUpgrade(RelationStatus.STRANGER, RelationStatus.ACQUAINTANCE)).isTrue();
        assertThat(isNewPeak(RelationStatus.ACQUAINTANCE, RelationStatus.ACQUAINTANCE)).isFalse();
    }

    @Test
    @DisplayName("최고 기록보다 낮은 단계로의 상승도 연출하지 않는다")
    void climbingBackBelowPeakIsNotNewPeak() {
        // LOVER까지 갔던 방이 STRANGER까지 떨어졌다가 FRIEND로 회복 — 이미 겪은 단계다.
        assertThat(isUpgrade(RelationStatus.STRANGER, RelationStatus.FRIEND)).isTrue();
        assertThat(isNewPeak(RelationStatus.LOVER, RelationStatus.FRIEND)).isFalse();
    }

    @Test
    @DisplayName("최고 기록을 넘어서면 다시 연출한다")
    void surpassingPeakFiresAgain() {
        assertThat(isNewPeak(RelationStatus.FRIEND, RelationStatus.LOVER)).isTrue();
    }

    // ── ENEMY — ordinal 함정 ────────────────────────────────────────────────

    @Test
    @DisplayName("★ ENEMY는 최고 기록이 될 수 없다 — ordinal로 비교하면 여기가 깨진다")
    void enemyIsNeverAPeak() {
        // ordinal: STRANGER=0 … LOVER=3, ENEMY=4. ordinal 비교였다면 전부 true가 되어
        // '적대가 되는 순간이 최고 기록'이 되고, 그 뒤로는 어떤 승급도 연출되지 않는다.
        assertThat(isNewPeak(RelationStatus.STRANGER, RelationStatus.ENEMY)).isFalse();
        assertThat(isNewPeak(RelationStatus.ACQUAINTANCE, RelationStatus.ENEMY)).isFalse();
        assertThat(isNewPeak(RelationStatus.FRIEND, RelationStatus.ENEMY)).isFalse();
        assertThat(isNewPeak(RelationStatus.LOVER, RelationStatus.ENEMY)).isFalse();
    }

    @Test
    @DisplayName("ENEMY를 기록으로 들고 있어도 STRANGER 복귀는 새 기록이 된다")
    void recoveringFromEnemyRankIsAbove() {
        // 서열상 ENEMY(-1) < STRANGER(0). 실제 서비스에서는 이 조합에 도달하기 전에
        // isEnemyRecovery가 먼저 무음 처리하지만, 서열 자체가 뒤집히지 않았음을 고정한다.
        assertThat(isNewPeak(RelationStatus.ENEMY, RelationStatus.STRANGER)).isTrue();
    }

    @Test
    @DisplayName("ENEMY 회복은 세리머니 없이 단계만 복원한다 (종원 확정)")
    void enemyRecoveryStaysSilent() {
        assertThat(RelationStatusPolicy.isEnemyRecovery(RelationStatus.ENEMY, RelationStatus.FRIEND)).isTrue();
        assertThat(RelationStatusPolicy.isEnemyRecovery(RelationStatus.FRIEND, RelationStatus.ENEMY)).isFalse();
    }

    // ── 시나리오 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 진동 5회에 연출은 1회만 — 증상 그대로의 시나리오")
    void oscillationFiresCeremonyOnlyOnce() {
        RelationStatus peak = RelationStatus.STRANGER;
        int ceremonies = 0;

        // 39↔40을 다섯 번 오간다: 매번 isUpgrade는 참이다.
        for (int i = 0; i < 5; i++) {
            if (isNewPeak(peak, RelationStatus.ACQUAINTANCE)) {
                peak = RelationStatus.ACQUAINTANCE;
                ceremonies++;
            }
            // 하락은 peak를 건드리지 않는다.
        }

        assertThat(ceremonies).isEqualTo(1);
        assertThat(peak).isEqualTo(RelationStatus.ACQUAINTANCE);
    }

    @Test
    @DisplayName("정상 진행은 단계마다 한 번씩 연출한다 — 억제가 과하지 않은지")
    void normalProgressionFiresEachStage() {
        RelationStatus peak = RelationStatus.STRANGER;
        int ceremonies = 0;

        for (RelationStatus next : List.of(
                RelationStatus.ACQUAINTANCE, RelationStatus.FRIEND, RelationStatus.LOVER)) {
            if (isNewPeak(peak, next)) {
                peak = next;
                ceremonies++;
            }
        }

        assertThat(ceremonies).isEqualTo(3);
        assertThat(peak).isEqualTo(RelationStatus.LOVER);
    }
}
