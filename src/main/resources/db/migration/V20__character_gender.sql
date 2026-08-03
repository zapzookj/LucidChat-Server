-- [2026-08-04 남캐 파이프라인] 캐릭터 성별 (additive·멱등) — null=FEMALE(레거시 전 캐릭터)
ALTER TABLE characters ADD COLUMN IF NOT EXISTS gender VARCHAR(10);
ALTER TABLE characters DROP CONSTRAINT IF EXISTS characters_gender_check;
-- 위저드 명시 선택값 보관 — Stage0·이미지 파이프라인·바인딩이 이 값을 단일 기준으로 소비
ALTER TABLE character_creation_jobs ADD COLUMN IF NOT EXISTS gender VARCHAR(10);
ALTER TABLE character_creation_jobs DROP CONSTRAINT IF EXISTS character_creation_jobs_gender_check;
