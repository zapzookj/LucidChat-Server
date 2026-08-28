package com.spring.aichat.service.scheduler;

import com.spring.aichat.domain.payment.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [적대적 리뷰 P1] 미지급 결제 감시 스케줄러.
 *
 * <p><b>왜 필요한가.</b> 같은 리뷰에서 웹훅 응답을 재분류해 복구 가능한 실패에는 503을 내고
 * PortOne 재시도(최대 5회)를 유도하도록 고쳤다. 그러나 <b>재시도가 전부 소진된 뒤에는
 * 아무도 모르는 상태</b>가 남는다 — 유저는 돈을 냈고, 우리 DB에는 지급 기록이 없다.
 * 그 상태를 주기적으로 훑어 ERROR 로그로 띄우는 것이 이 클래스의 전부다.
 *
 * <p><b>알림 연동은 하지 않는다</b>(이번 범위 밖). ERROR 로그는 CloudWatch Logs 필터 →
 * 알람으로 이어붙이면 되고, 그 배선은 docs/18 런북의 인프라 항목이다.
 *
 * <p><b>스캔 A는 "불변식 트립와이어"다 — 지금 코드에서는 거의 발화하지 않는다.</b>
 * {@code Order.impUid}는 {@link Order#markPaid(String)}에서만 쓰이고 그 메서드가 상태를
 * 동시에 PAID로 올리므로, 정상 경로에서 "impUid는 있는데 PAID/REFUNDED가 아닌" 행은
 * 만들어지지 않는다({@code deliverProduct} 도중 예외가 나면 트랜잭션이 통째로 롤백되어
 * impUid 자체가 남지 않는다). 그래도 두는 이유:
 * <ul>
 *   <li>CS 대응 중의 <b>수동 DB 조작</b>이 남기는 반쪽 상태를 잡는다.</li>
 *   <li>{@code Order.markFailed}에는 상태 가드가 없다 — PAID 이후에 호출하는 코드가 미래에
 *       생기면 즉시 이 스캔에 걸린다(회귀 감지).</li>
 *   <li>RefundService가 취소 성공 후 DB 전이에 실패하는 경로가 늘어날 때의 안전망.</li>
 * </ul>
 * ⚠ 반대로 <b>정말로 흔한 미지급</b>(리다이렉트도 웹훅도 실패해 주문이 PENDING/EXPIRED로 남고
 * imp_uid는 아예 기록되지 않은 경우)은 이 스캔으로 잡히지 않는다. 잡으려면 검증 실패 시점의
 * imp_uid를 별도 컬럼에 남겨야 하며, 그것은 {@code Order} 엔티티 + 마이그레이션 변경이라
 * 이번 배정 범위 밖이다(보고서 needsOwnerChange 참조).
 *
 * <p><b>스캔 B</b>는 그 공백을 부분적으로 메운다. 검증에 실패해 FAILED로 확정된 주문 중
 * <b>PortOne 쪽에는 실제 결제가 존재하는</b> 두 사유(금액 불일치·merchant_uid 불일치)를
 * 골라낸다. 자동 환불의 성공/실패는 DB에 전혀 남지 않으므로 이 행들은 운영자 확인이 필요하다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UndeliveredPaymentScheduler {

    /**
     * 개별 행 로그 상한. 사고가 대량으로 번졌을 때 로그를 채워 다른 신호를 덮지 않도록 자른다
     * (총 건수는 별도 요약 라인으로 남는다).
     */
    private static final int DETAIL_LIMIT = 20;

    /** 스캔 B 조회 창. 무기한으로 훑으면 과거 공격 시도가 영구히 재로깅된다. */
    private static final int SCAN_B_LOOKBACK_DAYS = 7;

    /** 조회는 OrderRepository의 전용 쿼리 메서드를 쓴다 — 이 저장소에 EntityManager 직접 사용 선례가 없다. */
    private final com.spring.aichat.domain.payment.OrderRepository orderRepository;

    /** 15분 주기. 기동 직후 5분은 건너뛴다(부팅 직후 DB/커넥션 풀 안정화 전 스캔 회피). */
    @Scheduled(fixedRate = 15 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    @Transactional(readOnly = true)
    public void scanUndeliveredPayments() {
        scanImpUidWithoutDelivery();
        scanFailedWithRealPayment();
    }

    /**
     * 스캔 A — 결제 식별자(imp_uid)는 붙었는데 지급도 환불도 되지 않은 주문.
     *
     * <p>REFUNDED를 제외하는 이유: 환불된 주문은 impUid를 그대로 보존한 채 PAID→REFUNDED로
     * 전이하므로, 제외하지 않으면 정상 환불 건이 전부 오탐으로 올라온다.
     */
    private void scanImpUidWithoutDelivery() {
        List<Order> rows = orderRepository.findImpUidWithoutDelivery(
            PageRequest.of(0, DETAIL_LIMIT + 1));

        if (rows.isEmpty()) return;

        log.error("[UNDELIVERED] ★ imp_uid는 있으나 PAID/REFUNDED가 아닌 주문 {}건{} — "
                + "결제는 성립했는데 지급도 환불도 안 된 상태다. 주문 상태 불변식 위반이므로 "
                + "수동 DB 조작 또는 회귀를 의심하라.",
            Math.min(rows.size(), DETAIL_LIMIT), rows.size() > DETAIL_LIMIT ? "+" : "");

        rows.stream().limit(DETAIL_LIMIT).forEach(o ->
            log.error("[UNDELIVERED] merchantUid={}, impUid={}, status={}, product={}, amount={}, "
                    + "createdAt={}, failedReason={}",
                o.getMerchantUid(), o.getImpUid(), o.getStatus(), o.getProductType(),
                o.getAmount(), o.getCreatedAt(), o.getFailedReason()));
    }

    /**
     * 스캔 B — 검증 실패로 FAILED 확정됐지만 PortOne 쪽에는 실제 결제가 존재하는 주문.
     *
     * <p>사유 문자열은 {@code PaymentService.verifyAndDeliver}가 {@code markFailed}에 넣는 값과
     * 맞춰야 한다:
     * <ul>
     *   <li>{@code "Amount mismatch: expected=... actual=..."} — 유저가 실제로 돈을 냈고 자동 환불을
     *       시도했다. 환불 실패는 로그에만 남으므로 DB만 보면 구분이 불가능하다.</li>
     *   <li>{@code "merchant_uid mismatch: ..."} — 타인/타주문의 정상 결제가 이 주문에 붙으려 했다.
     *       의도적으로 환불하지 않는 경로이므로, 원 결제가 제대로 지급됐는지 별도 확인이 필요하다.</li>
     * </ul>
     * ⚠ PortOne status != paid로 인한 FAILED는 <b>일부러 제외</b>한다 — 그쪽은 애초에 돈이 나가지
     * 않았고, 결제창 이탈이 흔해 양이 많다(오탐으로 스캔 전체를 무의미하게 만든다).
     */
    private void scanFailedWithRealPayment() {
        LocalDateTime since = LocalDateTime.now().minusDays(SCAN_B_LOOKBACK_DAYS);

        List<Order> rows = orderRepository.findFailedWithRealPayment(
            since, PageRequest.of(0, DETAIL_LIMIT + 1));

        if (rows.isEmpty()) return;

        log.error("[UNDELIVERED] ★ 실결제가 존재하는데 FAILED로 종결된 주문 {}건{} (최근 {}일) — "
                + "자동 환불 성공 여부는 DB에 남지 않는다. PortOne 콘솔에서 취소 상태를 확인하라.",
            Math.min(rows.size(), DETAIL_LIMIT), rows.size() > DETAIL_LIMIT ? "+" : "",
            SCAN_B_LOOKBACK_DAYS);

        rows.stream().limit(DETAIL_LIMIT).forEach(o ->
            log.error("[UNDELIVERED] merchantUid={}, status=FAILED, product={}, amount={}, "
                    + "createdAt={}, failedReason={}",
                o.getMerchantUid(), o.getProductType(), o.getAmount(),
                o.getCreatedAt(), o.getFailedReason()));
    }
}
