-- [2026-07-31 에픽 B] 씬 일러 수동 트리거 전환 — 유저 요청 추적·과금·환불 컬럼 (additive·멱등)
--
-- 배경: 씬 렌더 트리거를 '채팅 LLM 인밴드 매턴'에서 '유저 수동 요청 + 전용 프롬프트 라이터'로
-- 전환(종원 확정 2026-07-31). 수동 요청은 에너지를 차감하므로 행 단위로 과금·환불 상태를 추적한다.
--   trigger_source : AUTO(기존 인밴드 휴면 경로) | MANUAL(유저 요청)
--   requested_by   : 수동 요청 유저 id (FK 미설정 — characters.owner_user_id 관례와 동일)
--   energy_charged : 요청 시점 차감량 (실패 환불 정산 기준)
--   energy_refunded: 환불 완료 여부 (failRender 멱등 가드)

ALTER TABLE scene_illustrations ADD COLUMN IF NOT EXISTS trigger_source VARCHAR(10) NOT NULL DEFAULT 'AUTO';
ALTER TABLE scene_illustrations ADD COLUMN IF NOT EXISTS requested_by BIGINT;
ALTER TABLE scene_illustrations ADD COLUMN IF NOT EXISTS energy_charged INT NOT NULL DEFAULT 0;
ALTER TABLE scene_illustrations ADD COLUMN IF NOT EXISTS energy_refunded BOOLEAN NOT NULL DEFAULT FALSE;
