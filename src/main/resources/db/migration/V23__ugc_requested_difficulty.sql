-- [2026-08-05 난이도] 위저드 난이도 지정 (additive·멱등) — null=미지정(바인딩 미설정 → NORMAL 폴백 유지)
-- 바인딩(Stage 4)에서 Character.difficulty로 주입 — 지정 잡만 설정, 레거시/미지정 잡은 무접촉
ALTER TABLE character_creation_jobs ADD COLUMN IF NOT EXISTS requested_difficulty VARCHAR(16);
ALTER TABLE character_creation_jobs DROP CONSTRAINT IF EXISTS character_creation_jobs_requested_difficulty_check;
