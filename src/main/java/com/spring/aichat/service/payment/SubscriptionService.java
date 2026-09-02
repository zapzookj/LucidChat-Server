package com.spring.aichat.service.payment;

import com.spring.aichat.domain.enums.SubscriptionType;
import com.spring.aichat.domain.payment.UserSubscription;
import com.spring.aichat.domain.payment.UserSubscriptionRepository;
import com.spring.aichat.domain.user.User;
import com.spring.aichat.domain.user.UserRepository;
import com.spring.aichat.exception.BusinessException;
import com.spring.aichat.exception.ErrorCode;
import com.spring.aichat.exception.NotFoundException;
import com.spring.aichat.service.cache.RedisCacheService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 구독 관리 서비스
 *
 * [구독 활성화 로직]
 * - 기존 활성 구독이 없으면: 새 구독 생성 (now + 30일)
 * - 기존 활성 구독이 있으면:
 *   - 같은 티어: 잔여 기간 보존 +30일 연장 (D-4.1) — 직전 (만료, 주문번호)를 스냅샷(환불 시 그 회차분만 회수)
 *   - 상위 티어: 기존 비활성화 + 새 구독 생성, 하위 티어 잔여 기간을 금액 비례로 이월 (안건 5 (b)) — 이월 출처 기록
 *   - 하위 티어(다운그레이드): 상위 구독이 활성·미만료면 <b>거부</b>(정상 유저 1클릭 실수로 최대 24,900원치 소멸 방지).
 *     관리자 지급(merchantUid=null)은 예외. 만료 후 변경은 신규 생성과 동일.
 *
 * [환불 회수 — 안건 6 (c)+(나) · 적대적 리뷰 P1/P2]
 * - 최신 유료 회차만 환불 가능. 스냅샷이 있으면 그 회차분만 되돌리고(이전 회차 보존), 없으면 행 전체 비활성화.
 * - 업그레이드 주문 환불 → 이전 행 복원. 이월 원천(하위 회차) 주문 환불 → 사전 거부.
 *
 * [동시성 — D-4.5]
 * - 유저 행 비관적 잠금({@code findByIdForUpdate})으로 유저 단위 직렬화. 주문 단위 락은 서로 다른
 *   merchant_uid 2건(더블 결제·PASS+MIDNIGHT 동시·관리자 지급과 결제 동시)을 직렬화하지 못했다.
 * - 활성 구독 조회는 List — 활성 2행이어도 500 대신 만료일 최장 행으로 degrade + error 로그.
 * - DB 최후 방어선은 V33 부분 유니크 {@code uq_sub_user_active}.
 *   ⚠ 레지스터 D-4.5 ③(제약 위반을 catch해 renew로 전환)은 넣지 않는다 — 같은 TX 안에서 flush가 제약 위반으로
 *   실패하면 리포지토리 프록시가 공유 TX를 rollback-only로 표시해 커밋 시 UnexpectedRollbackException이 난다.
 *   ①의 락으로 애플리케이션 경로에서는 위반이 발생하지 않으며, 발생한다면(외부 DB 조작) 실패시키는 것이 맞다.
 *
 * [구독 혜택 적용]
 * - User.subscriptionTier: 현재 활성 구독 타입 (에너지 리젠/부스트 비용 계산용)
 * - 구독 활성화/비활성화 시 User.subscriptionTier 동기화
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionService {

    private final UserSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final RedisCacheService cacheService;

    /**
     * 구독 활성화 (결제 지급·관리자 지급 공용).
     *
     * @param merchantUid 결제 주문번호. <b>null = 관리자 지급</b> — 유료 회차 키·스냅샷을 덮지 않고 다운그레이드 거부도 면제.
     */
    @Transactional
    public UserSubscription activateSubscription(User user, SubscriptionType type, String merchantUid) {
        // [D-4.5 ①] 유저 행 잠금 — 이 아래의 read-then-write를 유저 단위로 직렬화한다.
        User locked = userRepository.findByIdForUpdate(user.getId())
            .orElseThrow(() -> new NotFoundException("User not found: " + user.getId()));

        UserSubscription current = pickCurrent(locked.getId());
        LocalDateTime now = LocalDateTime.now();
        boolean adminGrant = merchantUid == null;
        UserSubscription subscription;

        if (current == null || current.isExpired()) {
            if (current != null) {
                // 만료됐는데 스케줄러가 아직 안 끈 행 — 새 행과 활성 2행이 되지 않게 먼저 끈다(V33)
                current.deactivate();
                subscriptionRepository.saveAndFlush(current);
            }
            subscription = subscriptionRepository.save(
                UserSubscription.create(locked, type, merchantUid, now.plusDays(UserSubscription.PERIOD_DAYS)));
            log.info("[SUB] Activated: user={}, type={}, expiry={}, admin={}",
                locked.getUsername(), type, subscription.getExpiresAt(), adminGrant);
        } else if (current.getType() == type) {
            // [D-4.1] 같은 티어: 잔여 기간 보존 +30일 (renew 내부 max(now, expiresAt) 기준). 유료 회차면 직전 값 스냅샷.
            LocalDateTime before = current.renew(merchantUid);
            subscription = current;
            log.info("[SUB] Renewed: user={}, type={}, expiry {} → {}, admin={}",
                locked.getUsername(), type, before, current.getExpiresAt(), adminGrant);
        } else {
            int fromPrice = current.getType().getMonthlyPriceKrw();
            int toPrice = type.getMonthlyPriceKrw();
            if (!adminGrant && toPrice < fromPrice) {
                // [적대적 리뷰 P2] 다운그레이드 거부 — 상위 구독 잔여가 이월 0으로 소멸한다. 종원 확정(안건 5)은 '이월 산식
                //   범위 밖'이지 '무경고 소각 허용'이 아니다. FE도 하위 카드를 비활성화한다.
                long daysLeft = Math.max(1, ChronoUnit.DAYS.between(now, current.getExpiresAt()));
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "현재 " + current.getType().getDisplayName() + "이(가) " + daysLeft + "일 남아 있어요. "
                        + "만료 후에 " + type.getDisplayName() + "(으)로 변경할 수 있어요.");
            }
            // [안건 5 (b)] 상위 티어 — 하위 잔여 기간을 금액 비례로 이월. 관리자 지급 다운그레이드는 이월 0(현행).
            Duration remaining = Duration.between(now, current.getExpiresAt());
            Duration carried = carryover(remaining, current.getType(), type);
            LocalDateTime previousExpiry = current.getExpiresAt();

            // ★ 새 행 INSERT보다 먼저 flush — Hibernate는 flush 시 INSERT를 UPDATE보다 앞에 실행하므로
            //   여기서 flush하지 않으면 V33 부분 유니크(active=true)가 순간 2행을 보고 위반한다.
            current.deactivate();
            subscriptionRepository.saveAndFlush(current);

            UserSubscription created = UserSubscription.create(
                locked, type, merchantUid, now.plusDays(UserSubscription.PERIOD_DAYS).plus(carried));
            created.markCarriedFrom(current.getId(), carried.getSeconds());   // 상위 주문 환불 시 이전 행 복원의 키
            subscription = subscriptionRepository.save(created);
            log.info("[SUB] Tier changed: user={}, {} → {}, previousExpiry={}, carriedOver={}, newExpiry={}, admin={}",
                locked.getUsername(), current.getType(), type, previousExpiry,
                carried.isZero() ? "0" : carried, subscription.getExpiresAt(), adminGrant);
        }

        // User 엔티티에 구독 타입 동기화
        locked.activateSubscription(type);
        userRepository.save(locked);
        cacheService.evictUserProfile(locked.getUsername());

        return subscription;
    }

    /**
     * [안건 5 (b) · decisions_confirmed §A #5] 업그레이드 시 하위 티어 잔여 기간의 <b>금액 비례</b> 이월분.
     * {@code 잔여 × (하위 월액 / 상위 월액)} — 잔여 10일 LUCID_PASS(14,900)를 MIDNIGHT(24,900)로 올리면
     * 10 × 14,900 / 24,900 ≈ 5.98일이 상위 티어 30일에 가산된다. 초 단위 정수 나눗셈(절사).
     *
     * <p>ZERO를 돌려주는 경우: 잔여 없음(만료 후 변경) · 다운그레이드 · 동일가(종원 확정: 다운그레이드는 범위 밖,
     * 현행 유지). (c) '잔여일 그대로 가산'을 택하지 않은 이유 — 저가 티어를 사서 즉시 업그레이드하는 차익 경로가 생긴다.
     */
    static Duration carryover(Duration remaining, SubscriptionType from, SubscriptionType to) {
        if (remaining == null || remaining.isNegative() || remaining.isZero()) return Duration.ZERO;
        int fromPrice = from.getMonthlyPriceKrw();
        int toPrice = to.getMonthlyPriceKrw();
        if (toPrice <= fromPrice) return Duration.ZERO;
        return Duration.ofSeconds(remaining.getSeconds() * fromPrice / toPrice);
    }

    /**
     * [Phase 6] 관리자 수동 구독 해제 — 활성 UserSubscription 전부 비활성화 + User.subscriptionTier 초기화.
     * ([D-4.5] 활성이 2행이어도 하나만 끄고 나머지를 남기지 않는다)
     */
    @Transactional
    public void deactivateForUser(User user) {
        for (UserSubscription sub : findActives(user.getId())) {
            sub.deactivate();
            subscriptionRepository.save(sub);
        }
        user.clearSubscription();
        userRepository.save(user);
        cacheService.evictUserProfile(user.getUsername());
    }

    /**
     * [안건 6 (c) · 적대적 리뷰 P2] 환불 회수 선검증 — PortOne 취소 <b>전</b>에 호출. 거부는 예외(돈이 나가기 전).
     * <ul>
     *   <li>주문번호로 행을 못 찾음 → 과거 회차(renew가 merchant_uid를 덮음) — 회수 대상 없음, 거부</li>
     *   <li>비활성 행이고 그 행을 이월 출처로 갖는 활성 행이 있음 → 이월 원천 회차 — 이월분이 회수 없이 유출되므로 거부
     *       ('상위 티어 주문 환불로 처리')</li>
     * </ul>
     */
    public void assertRefundableRound(String merchantUid) {
        UserSubscription row = subscriptionRepository.findByMerchantUid(merchantUid).orElseThrow(() ->
            new BusinessException(ErrorCode.BAD_REQUEST,
                "과거 회차 구독 결제는 환불할 수 없습니다 — 최근 결제 회차만 환불 가능합니다. "
                    + "(구독 갱신은 회차별 이력을 남기지 않아 회수할 대상이 없습니다) merchantUid=" + merchantUid));
        if (!row.isActive()) {
            boolean carriedInto = findActives(row.getUser().getId()).stream()
                .anyMatch(a -> row.getId().equals(a.getCarriedFromId()) && a.getCarriedSeconds() != null && a.getCarriedSeconds() > 0);
            if (carriedInto) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "이 회차의 잔여 기간은 상위 티어로 이월됐습니다 — 상위 티어 주문을 환불해 주세요. merchantUid=" + merchantUid);
            }
        }
    }

    /**
     * [Phase 6 · D-4.3 · 적대적 리뷰 P1/P2] 환불 회수 — 주문번호의 회차분을 되돌린다.
     * <ul>
     *   <li>활성 행 + 최신 회차 + 스냅샷 있음 → 그 회차분만 되돌림(이전 회차 보존). 되돌린 만료가 지났으면 비활성화.</li>
     *   <li>티어 변경 행(carriedFrom 있음) → 이 행을 끄고 이전 행이 미만료면 복원(V33 유니크 — 끄기 flush 후 복원).</li>
     *   <li>그 외(단일 회차·구 행) → 행 전체 비활성화(종전 동작).</li>
     *   <li>이미 비활성 행 → 회수할 혜택 없음, true(돈만 돌려줌).</li>
     * </ul>
     *
     * @return 회수 대상을 찾았으면 true. 미발견은 false — RefundService가 REFUND_CLAWBACK_FAILED로 승격한다.
     */
    @Transactional
    public boolean clawbackRound(String merchantUid) {
        Optional<UserSubscription> found = subscriptionRepository.findByMerchantUid(merchantUid);
        if (found.isEmpty()) {
            log.error("[SUB] Clawback target NOT FOUND: merchantUid={} — 과거 회차이거나 이미 정리된 구독", merchantUid);
            return false;
        }
        UserSubscription sub = found.get();
        User user = sub.getUser();
        LocalDateTime now = LocalDateTime.now();

        if (!sub.isActive()) {
            log.info("[SUB] Clawback on inactive row — nothing to revoke: merchantUid={}", merchantUid);
            return true;
        }

        if (sub.getCarriedFromId() != null) {
            // 상위 티어 주문 환불 — 이 행을 끄고 이전 행 복원
            sub.deactivate();
            subscriptionRepository.saveAndFlush(sub);
            Optional<UserSubscription> previous = subscriptionRepository.findById(sub.getCarriedFromId());
            if (previous.isPresent() && previous.get().getExpiresAt().isAfter(now)) {
                UserSubscription prev = previous.get();
                prev.reactivate();
                subscriptionRepository.save(prev);
                user.activateSubscription(prev.getType());
                log.info("[SUB] Upgrade refunded — previous tier restored: merchantUid={}, restored={}, expiry={}",
                    merchantUid, prev.getType(), prev.getExpiresAt());
            } else {
                clearTierIfNoActive(user);
                log.info("[SUB] Upgrade refunded — previous tier expired, subscription cleared: merchantUid={}", merchantUid);
            }
        } else if (sub.revertLatestRound()) {
            // 같은 티어 재결제(더블 결제 등)의 최신 회차 회수 — 이전 회차 보존
            if (sub.getExpiresAt().isAfter(now)) {
                subscriptionRepository.save(sub);
                log.info("[SUB] Latest round reverted — previous round kept: merchantUid={}, expiry={}", merchantUid, sub.getExpiresAt());
            } else {
                sub.deactivate();
                subscriptionRepository.save(sub);
                clearTierIfNoActive(user);
                log.info("[SUB] Latest round reverted — previous round already expired, deactivated: merchantUid={}", merchantUid);
            }
        } else {
            sub.deactivate();
            subscriptionRepository.save(sub);
            clearTierIfNoActive(user);
            log.info("[SUB] Deactivated by merchantUid (single round): merchantUid={}", merchantUid);
        }
        userRepository.save(user);
        cacheService.evictUserProfile(user.getUsername());
        return true;
    }

    private void clearTierIfNoActive(User user) {
        boolean hasOtherActive = findActives(user.getId()).stream().anyMatch(s -> !s.isExpired());
        if (!hasOtherActive) {
            user.clearSubscription();
        }
    }

    /**
     * 구독 만료 처리 (스케줄러에서 호출)
     */
    @Transactional
    public void deactivateExpired() {
        int count = subscriptionRepository.deactivateExpiredSubscriptions(java.time.LocalDateTime.now());
        if (count > 0) {
            log.info("[SUB] Deactivated {} expired subscriptions", count);
            // 만료된 유저들의 subscriptionTier를 null로 리셋
            // (벌크 업데이트로 처리)
            userRepository.clearExpiredSubscriptionTiers();
        }
    }

    /**
     * 현재 활성 구독 조회 — [D-4.5] 활성 2행이어도 500 대신 만료일 최장 행으로 degrade.
     */
    public Optional<UserSubscription> getActiveSubscription(Long userId) {
        return Optional.ofNullable(pickCurrent(userId))
            .filter(sub -> !sub.isExpired());
    }

    /**
     * 구독자 여부 확인
     */
    public boolean isSubscriber(Long userId) {
        return getActiveSubscription(userId).isPresent();
    }

    /**
     * 특정 티어 이상 구독 여부
     */
    public boolean hasSubscriptionTier(Long userId, SubscriptionType minimumTier) {
        return getActiveSubscription(userId)
            .map(sub -> sub.getType().ordinal() >= minimumTier.ordinal())
            .orElse(false);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private List<UserSubscription> findActives(Long userId) {
        return subscriptionRepository.findByUser_IdAndActiveTrueOrderByExpiresAtDesc(userId);
    }

    /**
     * [D-4.5 ②] 활성 구독 0/1/2+ 행을 하나로 — 2행 이상이면 불변식 위반이므로 error 로그를 남기고
     * 만료일이 가장 늦은 행(유저 유리)을 쓴다. 종전 Optional 조회는 이 상태에서 예외 → 500이었다.
     */
    private UserSubscription pickCurrent(Long userId) {
        List<UserSubscription> actives = findActives(userId);
        if (actives.isEmpty()) return null;
        if (actives.size() > 1) {
            log.error("[SUB] INVARIANT BROKEN — user={} has {} active subscriptions (V33 uq_sub_user_active 미적용?) "
                + "— 만료일 최장 행으로 degrade", userId, actives.size());
        }
        return actives.get(0);
    }
}
