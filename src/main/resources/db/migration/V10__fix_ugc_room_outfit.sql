-- V10 · UGC v1 폴리싱: Outfit.DEFAULT 부재로 MAID 폴백 저장된 기존 UGC 방 보정
--
-- 선행: Hibernate(ddl-auto)가 생성한 스키마는 enum 컬럼에 값 목록 CHECK 제약을 만든다.
--   이 제약이 Outfit.DEFAULT 추가 "이전" enum으로 생성된 DB에서는 아래 UPDATE는 물론
--   런타임의 UGC 방 생성(current_outfit='DEFAULT' 저장)까지 전부 막히므로 제거한다.
--   (값 검증은 앱 레벨 enum 타입이 보장. Flyway가 만든 스키마에는 이 제약이 없어 no-op.)
ALTER TABLE chat_rooms DROP CONSTRAINT IF EXISTS chat_rooms_current_outfit_check;

UPDATE chat_rooms
SET current_outfit = 'DEFAULT'
WHERE current_outfit = 'MAID'
  AND character_id IN (SELECT character_id FROM characters WHERE source = 'UGC');
