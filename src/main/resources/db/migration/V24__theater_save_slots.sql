-- V24 · theater_save_slots 생성 복구 (additive only, 멱등 — 2단계 부팅 대응)
-- 원인: TheaterSaveSlot.snapshot_json의 columnDefinition "LONGTEXT"(MySQL 전용 타입)를
--       PostgreSQL이 거부 → ddl-auto가 테이블·인덱스 생성에 실패(부팅은 계속)
--       → 세이브/로드·퀵세이브 기능 사용 시 런타임 실패하던 확정 결함 (docs/12 §E).
-- 엔티티는 TEXT로 교정됨. 이 마이그레이션이 로컬·프로드 공통으로 테이블을 확정 생성한다.
-- (FK 미설정 — V2 이후 신규 테이블 관례. room_id 정합성은 앱 레벨 책임)
CREATE TABLE IF NOT EXISTS theater_save_slots (
    id              BIGSERIAL PRIMARY KEY,
    room_id         BIGINT       NOT NULL,
    slot_number     INT          NOT NULL,
    label           VARCHAR(100),
    preview_text    VARCHAR(500),
    act_number      INT          NOT NULL,
    chapter_number  INT          NOT NULL,
    lead_heroine_id BIGINT,
    snapshot_json   TEXT         NOT NULL,
    is_quick_save   BOOLEAN      NOT NULL DEFAULT FALSE,
    saved_at        TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_theater_save_slot ON theater_save_slots (room_id, slot_number);
CREATE INDEX IF NOT EXISTS idx_theater_save_room ON theater_save_slots (room_id);
