-- [2026-07-31 난이도] 캐릭터 공략 난이도 (additive·멱등) — 하이브리드 이중 게이트(종원 확정)
-- EASY(1.5x)/NORMAL(1.0)/HARD(0.6)/EXTREME(0.35) — 양수 스탯 델타 확률 반올림 배율 + 프롬프트 성격 지시.
ALTER TABLE characters ADD COLUMN IF NOT EXISTS difficulty VARCHAR(20);
-- Hibernate(ddl-auto=update) enum CHECK 선제 제거 (V10/V11/V12 관례 — 값 추가 함정 예방)
ALTER TABLE characters DROP CONSTRAINT IF EXISTS characters_difficulty_check;
