-- V32 · 에너지 분할 환불 — 지연 환불 4경로의 유료 분할분 영속 (버그픽스 D-1.2 · D-1.6 · D-1.7 · D-1.8)
--
-- ■ 무엇을 고치는가 (docs/17_assets/defect_register.md §D-1)
--   User.consumeEnergy는 free 우선 → 부족분만 paid로 차감하는데, refundEnergy(int)는 총액만 받아
--   free 상한까지 채우고 나머지를 paid로 돌렸다. 환불이 차감과 같은 요청 안에서 즉시 돌면 문제가
--   작지만, 지연 환불(씬 일러 렌더 실패 콜백·UGC 잡 실패·실패 장소 삭제)은 수십 초~수 시간 뒤라
--   그 사이 free가 낮으면 **유료로 낸 에너지가 free로 흡수돼 통째로 소각**됐다(free는 스케줄러가
--   상한까지 공짜로 채우므로 경제 가치 0). 20E 캐릭터 잡 실패 한 번이면 비구독 상한(30) 대비
--   거의 전량이다.
--   → 차감 시점의 유료분을 행에 남기고, 환불은 그 분할을 그대로 되돌린다(User.refundEnergy(EnergySplit)).
--
-- ■ 왜 4 테이블인가
--   scene_illustrations      : 수동 씬 일러(5E) 실패 환불 — SceneRenderWriteService.refundManualCharge (D-1.2)
--   character_creation_jobs  : 단계 과금 누산(최대 20E+) 실패 환불 — UgcPipelineWorker.failAndRefund (D-1.6)
--   ugc_world_creation_jobs  : 기본 패키지·리롤 누산(10E+) 실패 환불 — UgcWorldPipelineWorker.failAndRefund (D-1.7)
--   ugc_world_locations      : 사후 추가 장소(1E) 실패 삭제 환불 — UgcWorldService.deleteFailedLocation (D-1.8)
--                              이 테이블만 총액 컬럼이 없었으므로 energy_charged도 함께 만든다(나머지 3 테이블과 동형 —
--                              총액을 삭제 시점 가격표에서 가져오면 가격 개정 사이에 유료분이 클램프로 잘린다).
--   동일 요청 내 즉시 환불(씬 일러 동기 실패·V1/V2 스트림 보상)은 지역 변수로 분할을 운반하므로 컬럼 불요.
--
-- ■ NOT NULL DEFAULT 0 (CLAUDE.md §2-1)
--   DEFAULT가 있으므로 기존 행은 0으로 채워지고 구 코드의 INSERT도 깨지지 않는다. prod(validate)는
--   엔티티 int ↔ INT NOT NULL 정합. 0 = 애플리케이션에서 '전액 free 환불' 폴백(보수적 — paid로 승급시키면
--   free→paid 세탁 파밍면이 열린다).
--
-- ■ 배포 시점 진행 중 행 백필 (적대적 리뷰 P2 — 두 렌즈가 독립적으로 지적)
--   '구 행 = 전액 free 폴백'은 배포 이전 동작과 **같지 않다**. 구 refundEnergy(int)는 free가 상한이면 초과분을
--   paid로 돌려줬으므로, 배포 전에 유료로 차감된 잡이 배포 후 실패하면 신 코드는 유료분을 통째로 버린다
--   (예: free 0/paid 50 유저가 6+4+8=18E를 전부 paid로 낸 채 REVIEW_WAIT → 배포 → 실패 → regen으로 free는 이미
--   상한 → 환불 0). 지연 환불은 대부분 상한 상태에서 일어나므로 이 창은 확정적으로 열린다.
--   → 마이그레이션 시점에 **비종결·미환불** 행에 한해 유료분 = 총액으로 1회 백필한다. 파밍면은 없다(1회성·유한 집합·
--   동시 1잡 정책상 유저당 최대 1건). free로 낸 유저는 소액을 paid로 받는 방향(유저 유리)이고, 실제 paid로 낸 유저는
--   정확히 돌려받는다. 종결 행(환불 완료·READY·EXPIRED)은 건드리지 않는다 — 다시 환불되지 않는 행이라 의미 없다.
--
-- ■ 자유 분할분의 상한 초과 처리 (레지스터 D-1.1 "❓ 결정 필요" — (a) 버림으로 확정)
--   지연 환불 시점에 free가 이미 상한이면 초과분은 버린다. 스케줄러가 그 사이 free를 채웠으므로
--   유저가 쓴 free분은 **이미 회복돼 있다** — 얹으면 상한 초과 순증(공짜 발행)이고, paid로
--   승급시키면 파밍면이다. "(a)는 유저 손해"라는 레지스터 서술은 regen을 빼고 센 것이다.
--   ⚠ 단 '버킷 기준 정확 복원'은 경제적 중립이 아니다 — 지연 환불 대기 중 유저가 free를 소진하고 paid까지 쓰면
--   그 paid 소비분은 돌아오지 않는다(상한 = 해당 요청의 free 차감분). 구 코드도 같은 결과였고, 닫으려면
--   유저 단위 에너지 원장이 필요하다 — 이 배치의 범위 밖(User.refundEnergy Javadoc에 기록).
--
-- 멱등: IF NOT EXISTS + 백필 UPDATE는 paid=0인 비종결 행만 대상이라 재실행 시 0행 — 로컬(ddl-auto=update) 재실행 방어
-- (V11/V12/V17/V24/V28~V31 관례).
ALTER TABLE scene_illustrations     ADD COLUMN IF NOT EXISTS energy_charged_paid INT NOT NULL DEFAULT 0;
ALTER TABLE character_creation_jobs ADD COLUMN IF NOT EXISTS energy_charged_paid INT NOT NULL DEFAULT 0;
ALTER TABLE ugc_world_creation_jobs ADD COLUMN IF NOT EXISTS energy_charged_paid INT NOT NULL DEFAULT 0;
ALTER TABLE ugc_world_locations     ADD COLUMN IF NOT EXISTS energy_charged      INT NOT NULL DEFAULT 0;
ALTER TABLE ugc_world_locations     ADD COLUMN IF NOT EXISTS energy_charged_paid INT NOT NULL DEFAULT 0;

-- 배포 시점 진행 중 행 백필 — 유료분 = 총액 (위 '배포 시점 진행 중 행 백필' 참조)
UPDATE scene_illustrations
   SET energy_charged_paid = energy_charged
 WHERE trigger_source = 'MANUAL' AND energy_refunded = false AND energy_charged > 0 AND energy_charged_paid = 0
   AND status NOT IN ('COMPLETED', 'FAILED', 'SKIPPED');
UPDATE character_creation_jobs
   SET energy_charged_paid = energy_charged
 WHERE energy_charged > 0 AND energy_charged_paid = 0
   AND status NOT IN ('READY', 'FAILED', 'EXPIRED');
UPDATE ugc_world_creation_jobs
   SET energy_charged_paid = energy_charged
 WHERE energy_charged > 0 AND energy_charged_paid = 0
   AND status NOT IN ('READY', 'FAILED', 'EXPIRED');
-- 사후 추가 장소: 구 행은 총액 컬럼이 없었으므로 배포 시점 가격표(reroll-cost 1)로 백필. 빌더 잡 산출 장소는
-- 항상 READY로 태어나 실패 삭제 경로에 도달하지 않으므로 status <> 'READY'가 곧 '사후 추가 후 미완'이다.
UPDATE ugc_world_locations
   SET energy_charged = 1, energy_charged_paid = 1
 WHERE status <> 'READY' AND energy_charged = 0;

COMMENT ON COLUMN scene_illustrations.energy_charged_paid IS
    'D-1.2 energy_charged 중 유료(paid) 분할분. 실패 환불 시 paid로 정확 복원, 나머지는 free(상한 캡). 구 행 0 = 전액 free 폴백.';
COMMENT ON COLUMN character_creation_jobs.energy_charged_paid IS
    'D-1.6 energy_charged 누산 중 유료(paid) 분할분 누산. failAndRefund가 분할 복원에 사용. 구 행 0 = 전액 free 폴백.';
COMMENT ON COLUMN ugc_world_creation_jobs.energy_charged_paid IS
    'D-1.7 energy_charged 누산 중 유료(paid) 분할분 누산. failAndRefund가 분할 복원에 사용. 구 행 0 = 전액 free 폴백.';
COMMENT ON COLUMN ugc_world_locations.energy_charged IS
    'D-1.8 사후 장소 추가 차감 총액(추가 시점 가격). 0 = 빌더 잡 산출 장소(과금 없음) 또는 배포 이전 구 행(삭제 시 현재 가격표 폴백).';
COMMENT ON COLUMN ugc_world_locations.energy_charged_paid IS
    'D-1.8 사후 장소 추가 차감 중 유료(paid) 분할분. 실패 장소 삭제 환불에 사용.';
