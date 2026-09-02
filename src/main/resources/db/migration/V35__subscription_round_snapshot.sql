-- V35 · user_subscriptions 회차 스냅샷·이월 출처 + users.subscription_tier 정합 (배치 3 적대적 리뷰 P1·P2·P3)
--
-- ■ 회차 스냅샷 (P1 — 더블 결제 유저의 이전 회차 소멸)
--   D-4.1(잔여 기간 보존 갱신)로 구독 1행이 여러 회차의 유상 기간을 누적하게 됐다. 그런데 최신 회차 환불(안건 6 (c)가
--   허용한 유일한 환불 경로)의 회수는 행 전체를 비활성화해, 가장 흔한 사유인 '실수 더블 결제'(M1 → 수초 뒤 M2)에서
--   M2를 환불하면 M1의 30일까지 잃었다(M1 환불은 '과거 회차'로 거부). renew가 직전 (만료, 주문번호)를 스냅샷하고
--   회수가 그 회차분만 되돌린다.
--     prev_expires_at / prev_merchant_uid : 최신 회차 적용 직전 값 (신규 행·되돌린 뒤·구 행 = NULL → 행 전체 비활성화, 종전 동작)
--
-- ■ 이월 출처 (P2 — 업그레이드 이월과 환불의 비대칭)
--   안건 5 (b) 이월은 새 행에 시간으로 녹아드는데 환불은 주문번호 단위였다: 하위 회차(이월 원천) 환불이 통과해 이월분이
--   회수 없이 남거나, 상위 주문 환불 시 이전 행이 복원되지 않아 이미 지불한 잔여가 소멸했다.
--     carried_from_id / carried_seconds : 티어 변경으로 생긴 행의 이전 활성 행 id와 이월 초. 상위 주문 환불 → 이전 행 복원,
--                                         이월 원천 주문 환불 → 사전 거부(RefundService.assertRefundableRound).
--   구 행은 NULL(출처 미상) — 복원·거부 없이 종전 동작.
--
-- ■ users.subscription_tier 정합 (P3 — V33 정리 가드 후속)
--   V33이 중복 활성 행을 만료일 최장 1행만 남길 때 users.subscription_tier는 손대지 않았다. 살아남은 행의 티어와 유저
--   컬럼이 어긋나면 극장 초기 스탯 등 User.subscriptionTier 기반 분기와 행 기반 hasMidnightPass가 다른 답을 낸다.
--   활성 행이 있는 유저의 tier를 그 행으로 맞춘다(멱등 — 어긋난 행이 없으면 0행).
--
-- ■ 전부 nullable, DEFAULT 없음 — Hibernate 생성 테이블(CLAUDE.md §2-1). 엔티티는 래퍼 타입(LocalDateTime/String/Long)이라
--   prod validate 정합. 롤백: 컬럼은 남겨도 무해(구 코드가 안 읽음). V33 uq_sub_user_active는 별도(핸드오프 롤백 절차 참조).
-- 멱등: IF NOT EXISTS · 정합 UPDATE는 IS DISTINCT FROM 조건.
DO $$
BEGIN
    IF to_regclass('public.user_subscriptions') IS NOT NULL THEN
        ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS prev_expires_at    TIMESTAMP;
        ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS prev_merchant_uid  VARCHAR(50);
        ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS carried_from_id    BIGINT;
        ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS carried_seconds    BIGINT;

        IF to_regclass('public.users') IS NOT NULL THEN
            UPDATE users u
               SET subscription_tier = s.type
              FROM user_subscriptions s
             WHERE s.user_id = u.id
               AND s.active = true
               AND u.subscription_tier IS DISTINCT FROM s.type;
        END IF;
    END IF;
END $$;
