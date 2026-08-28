-- [버그픽스 B-1.2 · docs/17_assets/defect_register.md §B-1.2 · docs/19 D-17]
-- orders.imp_uid 유니크 인덱스 — 결제 1건(imp_uid) → 지급 N건 재사용의 DB 레벨 최후 방어선.
--
-- 왜 애플리케이션 검증(B-1.1 merchant_uid 대조)만으로 부족한가:
--   PaymentService는 merchantUid 단위 비관적 락(findByMerchantUidForUpdate)을 쓰는데,
--   imp_uid 재사용 공격은 *서로 다른* 주문 행을 대상으로 하므로 두 요청이 서로 다른 행을
--   잠그고 병렬로 통과한다. 행 락으로는 원리적으로 막을 수 없는 레이스다.
--
-- 왜 orders 테이블에 Flyway 이력이 없는가:
--   orders는 Flyway가 아니라 Hibernate ddl-auto로 생성된 테이블이다
--   (`grep -rn "imp_uid" src/main/resources/db/migration/*.sql` → 0건). CLAUDE.md §2-1 참조.
--
-- NULL 취급: PostgreSQL의 유니크 인덱스는 NULL을 서로 다른 값으로 취급하므로
--   PENDING 주문(imp_uid IS NULL) 다중 행은 이 인덱스에 걸리지 않는다.
--
-- ▼ 적용 전 중복 점검 (인덱스 생성이 실패하면 이 쿼리로 대상을 찾아 수동 정리할 것)
--   SELECT imp_uid, COUNT(*) AS dup, ARRAY_AGG(id) AS order_ids, ARRAY_AGG(status) AS statuses
--     FROM orders
--    WHERE imp_uid IS NOT NULL
--    GROUP BY imp_uid
--   HAVING COUNT(*) > 1
--    ORDER BY dup DESC;
--   -- 정리 원칙: 실제 PortOne 결제에 대응하는 1건만 PAID로 남기고, 나머지 중복 지급분은
--   --   status='FAILED' + failed_reason='imp_uid reuse cleanup (B-1.2)'로 마킹한 뒤
--   --   imp_uid = NULL 로 비운다(주문 이력 자체는 감사용으로 보존).
--
-- 멱등: IF NOT EXISTS — 로컬(ddl-auto=update)에서 Hibernate가 먼저 만든 경우와 재실행 방어.
--   (V11/V12 관례)
--
-- ⚠ [적대적 리뷰 P1] IF NOT EXISTS는 '인덱스 부재'만 방어할 뿐 **중복 데이터를 방어하지 못한다.**
--    중복이 1건이라도 있으면 CREATE UNIQUE INDEX가 실패하고, prod는 flyway 활성이므로
--    **마이그레이션 실패 = 애플리케이션 부팅 실패**다. 게다가 flyway_schema_history에 failed
--    엔트리가 남아 `flyway repair` 없이는 재배포도 안 된다 — 롤백 배포로도 자동 복구되지 않는다.
--    docs/19 §C-1의 MongoConfig 부팅 블로커와 같은 계열이라, 주석 안내가 아니라 **DML로 선처리**한다.

-- (1) 중복 정리 — 각 imp_uid에서 '살릴 1건'만 남기고 나머지의 imp_uid를 비운다.
--     살리는 기준: PAID 우선 → 그다음 id가 가장 작은 것(먼저 확정된 주문).
--     주문 행 자체는 감사용으로 보존하고, 상태와 사유만 남긴다.
UPDATE orders o
   SET imp_uid = NULL,
       status = 'FAILED',
       failed_reason = 'imp_uid reuse cleanup (B-1.2 / V28)'
 WHERE o.imp_uid IS NOT NULL
   AND o.id <> (
        SELECT k.id FROM orders k
         WHERE k.imp_uid = o.imp_uid
         ORDER BY (CASE WHEN k.status = 'PAID' THEN 0 ELSE 1 END), k.id
         LIMIT 1
   );

-- (2) 그제서야 유니크 인덱스. (1)이 중복을 0으로 만들었으므로 실패하지 않는다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_imp_uid ON orders (imp_uid);
