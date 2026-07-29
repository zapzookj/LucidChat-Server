-- V16 · 실시간 씬 일러 트랙 (additive only, 멱등 — 2단계 부팅 대응)
-- docs/09 §A-1 재피벗: 턴 단위 씬 렌더 상태·scene_hash 디덥·재사용 추적.
-- illustration.scene.enabled=false(기본)면 어떤 행도 생성되지 않는다.
CREATE TABLE IF NOT EXISTS scene_illustrations (
    id                      BIGSERIAL PRIMARY KEY,
    chat_room_id            BIGINT       NOT NULL,
    turn_index              INT          NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    scene_hash              VARCHAR(64),
    positive_prompt         TEXT,
    image_url               VARCHAR(1000),
    provider_request_id     VARCHAR(100),
    reused_illustration_id  BIGINT,
    error_message           VARCHAR(500),
    created_at              TIMESTAMP    NOT NULL,
    updated_at              TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_scene_illust_room ON scene_illustrations (chat_room_id, id);
CREATE INDEX IF NOT EXISTS idx_scene_illust_request ON scene_illustrations (provider_request_id);
