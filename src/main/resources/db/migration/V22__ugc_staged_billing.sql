-- [2026-08-04 단계 과금] 캐릭터 빌더 과금 모드 (additive·멱등) — 선차감 20 → 단계 진입 시 차감(6/4/8/2)
-- 'STAGED'=단계 차감 잡 / null=레거시 선차감 잡(단계 차감 전부 스킵 — energy_charged 누적·환불 로직은 공용)
ALTER TABLE character_creation_jobs ADD COLUMN IF NOT EXISTS billing_mode VARCHAR(16);
