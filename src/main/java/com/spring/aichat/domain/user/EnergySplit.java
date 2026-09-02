package com.spring.aichat.domain.user;

/**
 * [D-1.1 · docs/17_assets/defect_register.md §D-1] 에너지 차감의 무료/유료 분할분.
 *
 * <p>{@link User#consumeEnergy(int)}가 반환하고 {@link User#refundEnergy(EnergySplit)}가 그대로
 * 되돌린다. 총액(int)만 들고 다니면 환불 시점에 유료분을 알 수 없어 <b>유료 에너지가 무료로
 * 강제 변환돼 순소멸</b>했다(free는 스케줄러가 상한까지 공짜로 채우므로 경제 가치 0).
 * 반대로 총액을 paid 우선으로 되돌리면 free만 쓰던 유저가 실패를 유발해 free→paid 승급을
 * 파밍할 수 있다. 그래서 분할분 없이 한 줄로 못 고치고 이 타입이 필요하다.
 *
 * <p>지연 환불(씬 일러 실패·UGC 잡 실패·실패 장소 삭제)은 유료분을 행에 영속한다
 * (V32 {@code energy_charged_paid}). 구 행은 유료분 0 → 전액 free 복원 폴백(보수적).
 *
 * @param fromFree 무료 에너지에서 차감된 양
 * @param fromPaid 유료 에너지에서 차감된 양
 */
public record EnergySplit(int fromFree, int fromPaid) {

    public static final EnergySplit ZERO = new EnergySplit(0, 0);

    public EnergySplit {
        if (fromFree < 0 || fromPaid < 0) {
            throw new IllegalArgumentException(
                "에너지 분할분은 음수일 수 없습니다: free=" + fromFree + ", paid=" + fromPaid);
        }
    }

    public int total() {
        return fromFree + fromPaid;
    }

    public boolean isZero() {
        return fromFree == 0 && fromPaid == 0;
    }

    /** 누적 과금(단계 진입·리롤)용 합산. */
    public EnergySplit plus(EnergySplit other) {
        if (other == null) return this;
        return new EnergySplit(fromFree + other.fromFree, fromPaid + other.fromPaid);
    }

    /**
     * 영속 (총액, 유료분)에서 복원. 유료분은 {@code [0, total]}로 클램프한다 — 구 행(paid=0)은
     * 전액 free로, 손상 행(paid &gt; total)은 total까지만 paid로 해석해 환불이 차감을 넘지 않게 한다.
     */
    public static EnergySplit of(int total, int paidPortion) {
        int t = Math.max(0, total);
        int p = Math.max(0, Math.min(paidPortion, t));
        return new EnergySplit(t - p, p);
    }
}
