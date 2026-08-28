-- V29 · theater_branch_choices 중복 확정 차단 (적대적 리뷰 P2-b)
--
-- ■ 왜 필요한가 — check-then-delete가 비원자적이라 동시 2중 과금이 열린다.
--   applyBranchChoice는 readBranchOffer(비파괴 조회) → 검증 → 과금 → evict 순서로 돈다.
--   거의 동시에 도착한 두 /branches/choose가 둘 다 조회 단계를 통과한다. 게다가 씬 분기는
--   TheaterState를 수정하지 않아 @Version 낙관적 락 충돌도 나지 않으므로, CLIMAX 2E가
--   4E로 두 번 빠지고 분기 기록도 2행이 남는다. 애플리케이션 레벨의 순서 조정으로는
--   원리적으로 닫히지 않는 레이스라 DB가 거부하게 한다.
--
-- ■ 유니크 키를 (room, act, chapter, branch_level)로 잡으면 안 되는 이유
--   MINOR는 한 Chapter에 **정상적으로 3~4회** 발생한다
--   (TheaterDirectorEngine.decideBranchAfterBatch §4 — "그 외 모든 배치 끝은 MINOR").
--   레벨을 키로 쓰면 정상 유저의 두 번째 MINOR가 DB에서 거부된다 —
--   착취를 남기는 것보다 나쁜 회귀다. 분기 1회를 유일하게 식별하는 축은
--   **그 분기를 실은 배치**이므로 source_batch_id를 키에 넣는다.
--   (LOCATION은 배치가 아니라 Chapter 진입에 묶이므로 -1 자리표시자.)
--
-- ■ 왜 theater_branch_choices에 Flyway 이력이 없는가
--   이 테이블은 Flyway가 아니라 Hibernate ddl-auto로 생성됐다
--   (`grep -rn "theater_branch_choices" src/main/resources/db/migration/*.sql` → 0건).
--   CLAUDE.md §2-1 참조. prod는 ddl-auto=validate이므로 엔티티에 필드를 다는 순간
--   컬럼이 없으면 부팅이 죽는다 — 그래서 이 마이그레이션이 컬럼을 먼저 만든다.
--
-- ■ NULL 취급 (V28 uk_order_imp_uid와 같은 설계)
--   PostgreSQL의 유니크 인덱스는 NULL을 서로 다른 값으로 취급한다. 배포 이전 적재분은
--   출처 배치를 알 수 없어 NULL로 남기므로, 기존 데이터가 인덱스 생성을 실패시키지 않는다.
--   신규 행은 항상 non-null이라 유니크가 실효한다.

-- (1) 컬럼 추가 — nullable + DEFAULT 없음.
--     ⚠ CLAUDE.md §2-1: NOT NULL을 걸면 안 된다. 기존 행에 채울 진실값이 없고,
--        NOT NULL + DEFAULT 없음은 그 자체로 INSERT 사고의 원인이 된다.
ALTER TABLE theater_branch_choices ADD COLUMN IF NOT EXISTS source_batch_id INT;

-- (2) 중복 선처리 — 새 유니크 키에 걸릴 행을 미리 없앤다.
--     배포 시점엔 source_batch_id가 전부 NULL이라 **no-op**이다. 그럼에도 두는 이유는
--     V28에서 확인한 실패 양식 때문이다: CREATE UNIQUE INDEX가 중복 1건에 실패하면
--     prod는 flyway 활성이므로 **마이그레이션 실패 = 부팅 실패**이고,
--     flyway_schema_history에 failed 엔트리가 남아 `flyway repair` 없이는 롤백 배포로도
--     복구되지 않는다. 인덱스 생성이 데이터 상태에 의존하지 않도록 못 박는다.
--     남기는 기준: 같은 키 안에서 id가 가장 작은 것(먼저 확정된 1건).
DELETE FROM theater_branch_choices c
 WHERE c.source_batch_id IS NOT NULL
   AND c.id <> (
        SELECT k.id FROM theater_branch_choices k
         WHERE k.room_id        = c.room_id
           AND k.act_number     = c.act_number
           AND k.chapter_number = c.chapter_number
           AND k.branch_level   = c.branch_level
           AND k.source_batch_id = c.source_batch_id
         ORDER BY k.id
         LIMIT 1
   );

-- (3) 유니크 인덱스. (2)가 중복을 0으로 만들었으므로 실패하지 않는다.
--     멱등: IF NOT EXISTS — 로컬(ddl-auto=update) 재실행 방어 (V11/V12/V24/V28 관례).
CREATE UNIQUE INDEX IF NOT EXISTS uk_theater_branch_choice_offer
    ON theater_branch_choices (room_id, act_number, chapter_number, branch_level, source_batch_id);
