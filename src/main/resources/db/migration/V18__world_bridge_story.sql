-- [2026-07-31 에픽 A] World enum PK 브리지 — UGC 월드 STORY/THEATER 개방 스키마 (additive·멱등)
--
-- 설계(종원 확정): enum 유지 + 브리지. chat_rooms에 ugc_world_id 병행 컬럼(Character의
-- worldId/ugcWorldId XOR 관례를 방 레벨로 확장). 프로드 데이터 마이그레이션 없음 — 전부 additive.
-- 실제 개방은 서비스 게이트(ugc.modes.story-enabled / theater-enabled, 기본 off)가 통제한다.

-- chat_rooms: UGC 월드 STORY 방 (world_id enum FK와 앱 레벨 XOR)
ALTER TABLE chat_rooms ADD COLUMN IF NOT EXISTS ugc_world_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_room_ugc_world ON chat_rooms (ugc_world_id);
-- 'UGC 월드당 1방' — uk_user_world_mode와 동일하게 NULLS DISTINCT 전제(ugc_world_id NULL 행 무영향)
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_ugc_world_mode ON chat_rooms (user_id, ugc_world_id, chat_mode);

-- theater_states: UGC 월드 병행 컬럼 + world_id NOT NULL 해제 (THEATER 개방 선행 스키마)
ALTER TABLE theater_states ADD COLUMN IF NOT EXISTS ugc_world_id BIGINT;
ALTER TABLE theater_states ALTER COLUMN world_id DROP NOT NULL;
-- Hibernate(ddl-auto=update) enum CHECK 선제 제거 (V10/V11/V12 관례)
ALTER TABLE theater_states DROP CONSTRAINT IF EXISTS theater_states_world_id_check;

-- UGC 캐릭터 STORY 개방 백필 — UGC 월드 연결 캐릭터만(격리 확정 2026-07-31:
-- 공식 월드 연결 UGC 캐릭터는 SANDBOX 유지 — 공식 캐스트 자동 파생 오염 원천 차단).
-- 루틴 자동생성(1단, 08e6ad2)이 이미 깔아둔 휴면 데이터가 이 백필로 소비 가능해진다.
-- 서비스 게이트가 기본 off라 이 백필 자체는 아직 휴면이다.
-- world_id IS NULL 가드: 앱 XOR 가드 위반 행(있어선 안 되나 DB 강제 아님)이 존재해도
-- 공식 STORY 풀(findByWorldIdAndStoryAvailableTrue...)에 UGC 캐릭터가 오르는 오염을 원천 차단.
UPDATE characters SET story_available = TRUE
 WHERE source = 'UGC' AND ugc_world_id IS NOT NULL AND world_id IS NULL AND story_available = FALSE;
-- THEATER도 동일 백필 — 실개방은 서비스 게이트(ugc.modes.theater-enabled, 기본 off)가 통제한다.
UPDATE characters SET theater_available = TRUE
 WHERE source = 'UGC' AND ugc_world_id IS NOT NULL AND world_id IS NULL AND theater_available = FALSE;
