-- V33 · user_subscriptions 유저당 활성 구독 1행 — 부분 유니크 인덱스 (버그픽스 D-4.4 · D-4.5 최후 방어선)
--
-- ■ 무엇을 막는가 (docs/17_assets/defect_register.md §D-4.4 · §D-4.5)
--   엔티티 주석은 '유저당 활성 구독 최대 1개'를 선언하는데 인덱스(idx_sub_user_active)는 비유니크였고
--   activateSubscription은 락 없는 read-then-write라, 서로 다른 merchant_uid 2건(더블 결제·PASS+MIDNIGHT 동시·
--   관리자 지급과 결제 동시)이 둘 다 '활성 없음'을 보고 각각 INSERT하면 활성 2행이 커밋됐다.
--   그러면 findByUser_IdAndActiveTrue(Optional)가 IncorrectResultSizeDataAccessException → 500이고,
--   /users/subscription·재결제·환불 회수·isSubscriber 전부가 만료일까지(최대 30일) 죽어 있었다.
--   애플리케이션 측은 D-4.5(유저 행 비관적 락 + List 조회 degrade + 제약 위반 시 renew 전환)로 막고,
--   이 인덱스가 DB 레벨 최후 방어선이다.
--
-- ■ 기존 중복 행 정리 가드 (안건 3 · decisions_confirmed §A #3)
--   종원 확정: 프로드의 구독 행은 실유저가 아니라 베타·본인 테스트분 → 보상 산식 불요, 부분 유니크만.
--   그래도 중복이 1건이라도 있으면 CREATE UNIQUE INDEX 자체가 실패해 부팅이 죽으므로, 유저당 만료일이 가장
--   늦은 1행만 active로 남기고 나머지를 비활성화하는 **가드**를 앞에 둔다(중복이 없으면 0행 갱신 — 무해).
--   이는 보상이 아니라 인덱스 생성 전제조건이다. 살리는 행 기준(expires_at DESC)은 유저에게 유리한 쪽.
--
-- ■ 왜 DO 블록인가
--   이 테이블은 Flyway가 아니라 Hibernate ddl-auto 산물이다(CREATE TABLE 이력 0건 — CLAUDE.md §2-1).
--   완전히 빈 DB에서는 Flyway가 Hibernate DDL보다 먼저 돌아 테이블이 없을 수 있으므로 존재할 때만 실행한다.
--   기존 로컬·프로드에는 테이블이 있어 정상 적용된다. 부분 인덱스는 JPA @Index로 표현할 수 없어 엔티티에는
--   주석만 남긴다(validate는 인덱스를 검증하지 않으므로 충돌 없음).
--
-- 멱등: IF NOT EXISTS + 정리 UPDATE는 재실행 시 0행.
DO $$
BEGIN
    IF to_regclass('public.user_subscriptions') IS NOT NULL THEN
        -- 1) 활성 중복 정리 가드 — 유저당 만료일 최장 1행만 유지
        WITH ranked AS (
            SELECT id,
                   ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY expires_at DESC, id DESC) AS rn
            FROM user_subscriptions
            WHERE active = true
        )
        UPDATE user_subscriptions s
        SET active = false
        FROM ranked r
        WHERE s.id = r.id AND r.rn > 1;

        -- 2) 유저당 활성 1행 — 부분 유니크 인덱스
        CREATE UNIQUE INDEX IF NOT EXISTS uq_sub_user_active
            ON user_subscriptions (user_id)
            WHERE active = true;
    END IF;
END $$;
