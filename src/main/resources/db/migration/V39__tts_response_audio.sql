ALTER TABLE chat_rooms ADD COLUMN IF NOT EXISTS tts_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_rooms ADD COLUMN IF NOT EXISTS tts_energy_cost_accepted INTEGER;
CREATE TABLE IF NOT EXISTS tts_response_audio (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL, room_id BIGINT NOT NULL, log_id VARCHAR(100) NOT NULL,
    source_hash VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL,
    attempt_id VARCHAR(40) NOT NULL, request_key VARCHAR(100) NOT NULL, model VARCHAR(100) NOT NULL,
    clips_json TEXT NOT NULL, cleanup_json TEXT NOT NULL DEFAULT '[]', from_free INTEGER NOT NULL, from_paid INTEGER NOT NULL,
    refunded BOOLEAN NOT NULL DEFAULT FALSE, failure_code VARCHAR(30), cleanup_after TIMESTAMP,
    created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_tts_response UNIQUE(user_id, room_id, log_id),
    CONSTRAINT ck_tts_charge CHECK (from_free >= 0 AND from_paid >= 0),
    CONSTRAINT ck_tts_status CHECK (status IN ('QUEUED','GENERATING','READY','FAILED','CANCELLED'))
);
CREATE INDEX IF NOT EXISTS idx_tts_status_updated ON tts_response_audio(status, updated_at);
CREATE INDEX IF NOT EXISTS idx_tts_room ON tts_response_audio(room_id);
