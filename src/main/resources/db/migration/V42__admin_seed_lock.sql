-- [2026-11-09 백오피스 콘텐츠 RUD] 시드 잠금
-- 공식 캐릭터·세계관은 부팅마다 YAML 시드로 덮인다(app.seed.update-existing=true).
-- 어드민이 편집한 행은 seed_locked=true가 되고 시더는 그 행을 갱신하지 않는다(경고 로그만).
-- 어드민의 '시드로 되돌리기'가 시드를 즉시 재적용하고 잠금을 푼다.
ALTER TABLE characters ADD COLUMN IF NOT EXISTS seed_locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE worlds     ADD COLUMN IF NOT EXISTS seed_locked BOOLEAN NOT NULL DEFAULT FALSE;
