package com.spring.aichat.domain.enums;

/**
 * 주문 상태
 *
 * [상태 전이]
 * PENDING -> PAID (결제 완료, 검증 성공, 지급 완료)
 * PENDING -> PAID_UNDELIVERED (결제 검증 성공·markPaid 커밋됐으나 지급(deliverProduct) 실패 — 안건 4 (b))
 * PAID_UNDELIVERED -> PAID (재지급 성공 — /confirm 재호출·웹훅 재시도·관리자 재지급)
 * PENDING -> FAILED (결제 실패 or 위변조 감지)
 * PENDING -> EXPIRED (TTL 만료, 스케줄러가 정리)
 * PAID | PAID_UNDELIVERED -> REFUNDED (환불 처리 — 미지급분은 회수 없이 취소만)
 *
 * <p>[안건 4 · decisions_confirmed §A #4 = (b)+(c)] PAID_UNDELIVERED가 없던 시절엔 지급 실패가 트랜잭션 전체를
 * 롤백해 주문이 PENDING으로 남았다 — 돈은 나갔는데 지급도 환불도 실패 기록도 없고, 30분 뒤 EXPIRED로 조용히
 * 사라졌다(웹훅 재시도까지 실패하면). 이제 '돈의 흐름(PAID 커밋)'과 '지급의 흐름'을 분리해 사고가 상태로 남는다.
 * ⚠ enum 값 추가 = DB CHECK 제약 동기화(CLAUDE.md §2-7). 실측(2026-09-02): {@code orders_status_check}가
 *   Hibernate 생성으로 존재한다 — 값을 추가만 하면 컴파일·부팅·테스트 녹색인 채로 저장만 런타임에 죽는다.
 *   **V34**가 CHECK를 전수 드롭 후 6값으로 재생성한다(V31 chat_rooms 선례와 같은 멱등 형식).
 */
public enum OrderStatus {
    PENDING,
    PAID,
    /** 결제는 확정(imp_uid 붙음)됐으나 재화 지급이 실패한 상태 — 재지급 대상. 관리자 미지급 큐·감시 스케줄러 관측 대상. */
    PAID_UNDELIVERED,
    FAILED,
    EXPIRED,
    REFUNDED;

    /** 결제 대금이 우리 쪽으로 확정된 상태인가 (환불 가능 상태). */
    public boolean isPaidMoney() {
        return this == PAID || this == PAID_UNDELIVERED;
    }
}