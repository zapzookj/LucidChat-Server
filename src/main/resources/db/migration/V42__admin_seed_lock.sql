-- [2026-11-09 백오피스 콘텐츠 RUD] 필드 단위 시드 잠금
-- 공식 캐릭터·세계관은 부팅마다 YAML 시드로 덮인다(app.seed.update-existing=true).
-- 어드민이 편집한 필드명(엔티티 필드명 CSV)을 seed_locked_fields에 적고, 시더는 그 필드만 시드로 덮지 않는다.
-- 잠그지 않은 필드는 계속 YAML을 따른다. 어드민 '시드로 되돌리기'가 시드를 재적용하고 잠금을 비운다.
ALTER TABLE characters ADD COLUMN IF NOT EXISTS seed_locked_fields TEXT;
ALTER TABLE worlds     ADD COLUMN IF NOT EXISTS seed_locked_fields TEXT;
