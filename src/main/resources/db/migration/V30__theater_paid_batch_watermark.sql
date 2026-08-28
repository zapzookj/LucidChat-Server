-- V30 · theater_states 과금 워터마크 (버그픽스 B-5.2)
--
-- ■ 무엇을 막는가
--   POST /theater/rooms/{id}/prefetch 는 **과금 없이** 배치를 만든다(202·본문 없음).
--   그 중복 가드는 existsBatch(roomId, currentBatchId + 1)을 보는데 TheaterBatchGenerator는
--   putBatch(roomId, state.getCurrentBatchId()) 즉 **N 키**에 저장한다(기존 결함 D-5.1/5.2).
--   가드가 항상 통과해 배치 N이 생기고, onBatchConsumed는 batchId 일치 + 캐시 존재만 봤다.
--   → /prefetch → /batch-consumed 반복으로 /next-batch를 한 번도 부르지 않고 에너지 0에
--     Act 1~4를 완주해 엔딩(90~100E 가치)까지 갈 수 있었다. 씬 본문만 못 볼 뿐 호감도·씬수·
--     화자·분기 마커·advanceBatch()가 전부 정상 진행됐다.
--   이 컬럼이 "이번 Chapter에서 실제로 에너지가 차감된 최대 batchId"를 영속해 소비를 검사한다.
--
-- ■ 왜 Redis가 아니라 DB 컬럼인가 (docs/17_assets/defect_register.md §B-5.2 "결정 필요")
--   레지스터의 초안은 TheaterBatchCacheService(Redis, TTL)를 제안했다. 그러나 **돈 판정을
--   휘발 저장소에 두면** Redis 유실 시 정상 유저의 소비가 전부 거부되고, 그렇다고 유실 시
--   관대 모드로 폴백하면 착취면이 그대로 남아 게이트가 무의미해진다. 영속 컬럼을 택한다.
--
-- ■ NOT NULL을 걸지 않는다 (CLAUDE.md §2-1)
--   prod는 ddl-auto=validate다. NOT NULL + DEFAULT 없음은 그 자체로 INSERT 사고의 원인이고,
--   기존 행에 채울 **진실값이 없다** — 배포 이전 세션이 어느 배치까지 지불했는지 알 방법이 없다.
--   그래서 기존 행은 NULL로 남긴다. NULL은 애플리케이션에서 **grandfather 신호**로 쓴다:
--   TheaterService.onBatchConsumed가 NULL이면 통과시키고(전 유저 장애 방지) 그 자리에서
--   워터마크를 세운다. 신규 세션은 엔티티 초기값 -1("이번 Chapter에서 아직 미지불")로
--   생성되므로 NULL과 구분되고, 첫 소비부터 게이트가 실효한다.
--   ⚠ 여기서 UPDATE로 기존 행을 -1이나 currentBatchId로 채우면 안 된다.
--      -1로 채우면 지금 재생 중인 배치를 가진 정상 유저가 즉시 막히고(P0),
--      currentBatchId로 채우면 무과금 취득분까지 소급 승인해 버린다.
--
-- ■ 왜 theater_states에 CREATE TABLE 이력이 없는가
--   이 테이블은 Flyway가 아니라 Hibernate ddl-auto로 생성됐다(V18이 ALTER만 하고 있는 이유).
--   CLAUDE.md §2-1 참조. prod는 validate이므로 엔티티에 필드를 다는 순간 컬럼이 없으면
--   부팅이 죽는다 — 그래서 이 마이그레이션이 컬럼을 먼저 만든다.
--
-- 멱등: IF NOT EXISTS — 로컬(ddl-auto=update) 재실행 방어 (V11/V12/V24/V28/V29 관례).
ALTER TABLE theater_states ADD COLUMN IF NOT EXISTS last_paid_batch_id INT;

COMMENT ON COLUMN theater_states.last_paid_batch_id IS
    'B-5.2 과금 워터마크: 이번 Chapter에서 실제 에너지가 차감된 최대 batchId. '
    'NULL = 배포 이전 세션(grandfather, 첫 소비에서 승격). -1 = 이번 Chapter 미지불. '
    'Chapter/Act 전환 시 -1로 리셋(TheaterState.completeChapter/advanceToNextAct).';
