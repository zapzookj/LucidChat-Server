-- V14 · 프로필 카드 전용 한 줄 문장 (additive only, 멱등 — 2단계 부팅 대응)
-- 기존 행은 NULL → LobbyService가 firstGreeting 첫 문장으로 폴백 서빙(회귀 없음).
ALTER TABLE characters ADD COLUMN IF NOT EXISTS profile_quote VARCHAR(200);
