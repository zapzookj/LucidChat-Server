-- V15 · 실시간 일러 정체성 태그 영속화 (additive only, 멱등 — 2단계 부팅 대응)
-- docs/07 §B-3 / docs/09 §A-4: UGC appearance_tags가 job JSON에만 있어 실시간 일러가
-- 공식 4slug 하드코딩 맵 + airi 폴백에 갇혀 있던 문제의 선결 마이그레이션.
-- 기존 행은 NULL → IllustrationPromptAssembler가 하드코딩 맵 폴백 서빙(회귀 없음).
-- TEXT 채택 이유: appearance_tags 40~60개 CSV는 varchar 상한 예측이 어렵고,
-- 최종 바인딩에서 varchar 초과로 완주한 잡을 죽인 전례(2026-07-22)가 있다.
ALTER TABLE characters ADD COLUMN IF NOT EXISTS appearance_tags TEXT;
ALTER TABLE characters ADD COLUMN IF NOT EXISTS persona_tags TEXT;
ALTER TABLE characters ADD COLUMN IF NOT EXISTS base_pose TEXT;
