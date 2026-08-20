-- [블록 D · §G-8] BPM 게이지 폐지 — 엔티티 필드 제거 선행 스키마 (additive·멱등)
--
-- 결정(종원 2026-08-20, docs/17_assets/hud_redesign_mockup.html 결정 1 = B안):
--   LLM에게 bpm을 묻는 것을 그만두고, 박동은 이미 페이로드에 있는 emotion에서 클라이언트가 파생한다.
--   V1 프롬프트 턴당 ~205-235 토큰 회수. V2는 애초에 LLM이 bpm을 출력하지 않아 상수 65가 박혀 있었다.
--
-- 왜 DROP COLUMN이 아니라 DROP NOT NULL인가:
--   chat_room_heroines는 Flyway가 아니라 Hibernate(ddl-auto=update)가 만든 테이블이고
--   current_bpm/base_bpm이 NOT NULL + DEFAULT 없음이다. 엔티티에서 필드를 떼는 순간
--   INSERT가 NOT NULL 위반으로 죽는다. 그래서 제약만 먼저 푼다.
--   컬럼 자체는 한 릴리즈 유지한다 — 롤백 시 구 코드가 그대로 쓸 수 있어야 하기 때문.
--   실제 DROP COLUMN은 다음 릴리즈에서 별도 마이그레이션으로.
--
-- chat_rooms.current_bpm은 이미 nullable이라 손댈 것이 없다.

ALTER TABLE chat_room_heroines ALTER COLUMN current_bpm DROP NOT NULL;
ALTER TABLE chat_room_heroines ALTER COLUMN base_bpm    DROP NOT NULL;
