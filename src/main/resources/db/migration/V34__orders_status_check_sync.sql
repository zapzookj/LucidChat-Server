-- V34 · orders.status CHECK 제약 동기화 — PAID_UNDELIVERED 추가 (안건 4 (b) · decisions_confirmed §A #4)
--
-- ■ 무엇을 위한 것인가
--   결제는 PortOne에서 확정됐는데 재화 지급(deliverProduct)이 실패하면, 종전엔 트랜잭션 전체가 롤백돼
--   주문이 PENDING으로 남았다 — 돈은 나갔는데 지급도 환불도 실패 기록도 없고, 웹훅 재시도까지 실패하면
--   30분 뒤 EXPIRED로 조용히 사라졌다. OrderStatus.PAID_UNDELIVERED("돈은 확정, 지급은 아직")를 추가해
--   돈의 흐름(markPaid 커밋)과 지급의 흐름(별도 TX)을 분리하고, 지급 실패를 상태로 남긴다.
--   /confirm 재호출·웹훅 재시도·관리자 재지급이 이 상태에서 지급만 다시 시도한다.
--
-- ■ 왜 마이그레이션이 필요한가 (CLAUDE.md §2-7 — enum 값 추가 = CHECK 동기화)
--   실측(로컬 aichat DB, 2026-09-02):
--     orders_status_check = CHECK (status IN ('PENDING','PAID','FAILED','EXPIRED','REFUNDED'))
--   Hibernate 6.6이 @Enumerated(STRING) 컬럼에 자동 생성한 제약이며 ddl-auto는 update든 validate든 이를
--   갱신하지 않는다. 값만 추가하면 컴파일·부팅·테스트 녹색인 채로 **결제 확정 UPDATE가 런타임에 죽는다**
--   (V31 chat_rooms 사고와 동형 — 이번엔 착수 시 실측했다).
--
-- ■ 형식은 V31과 동일 — 제약 이름을 가정하지 않고 status 컬럼을 참조하는 CHECK를 전수로 떨군 뒤 하나로 재생성.
--   멱등: 재실행하면 방금 만든 제약도 떨어지고 같은 정의로 다시 붙는다.
--
-- ■ 값 목록은 OrderStatus.java 전량(6종)과 1:1이다. enum에 값을 추가하면 여기도 함께 늘려야 한다.
-- ■ 빈 DB 가드 — orders는 Hibernate 산물이라 완전히 빈 DB에서는 Flyway가 먼저 돌아 테이블이 없다(V33과 같은 형식).
DO $$
DECLARE r record;
BEGIN
    IF to_regclass('public.orders') IS NULL THEN
        RETURN;
    END IF;
    FOR r IN
        SELECT c.conname
          FROM pg_constraint c
         WHERE c.conrelid = 'orders'::regclass
           AND c.contype  = 'c'
           AND EXISTS (
               SELECT 1 FROM pg_attribute a
                WHERE a.attrelid = c.conrelid
                  AND a.attnum   = ANY (c.conkey)
                  AND a.attname  = 'status')
    LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', r.conname);
    END LOOP;

    ALTER TABLE orders
        ADD CONSTRAINT orders_status_check
        CHECK (status IN (
            'PENDING',
            'PAID',
            'PAID_UNDELIVERED',   -- 안건 4 (b) 신규
            'FAILED',
            'EXPIRED',
            'REFUNDED'
        ));
END $$;
