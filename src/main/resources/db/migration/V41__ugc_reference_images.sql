-- [docs/56 §10 레퍼런스 빌더] 유저가 올린 캐릭터 그림(레퍼런스)과 그 사용처 추적
-- ugc_reference_images            : 업로드·검사 결과. 재인코딩(EXIF 제거·≤1536px) PNG 1장 = 1행. 에너지 무과금.
--   verdict  : OK | UNCLEAR | MULTIPLE | POLICY  (OK만 생성에 쓸 수 있다)
--   features_json : VLM 판독 {features_ko[], appearance_ko, clothing_ko, appearance_en, gender}
--   deleted_at    : 보관 정책 정리(캐릭터 삭제 동반·미사용 7일) — 행은 남기고 스토리지 객체만 지운다
-- character_creation_jobs.reference_image_id / adjustments_json : 그림 방식 잡 표식 + 복장·체형 조정값
-- characters.reference_image_id   : 그림 방식 캐릭터 표식 — 실시간 장면 일러 잠금·공개 심사 원본 열람 기준
CREATE TABLE IF NOT EXISTS ugc_reference_images (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    storage_key    VARCHAR(300) NOT NULL,
    sha256         VARCHAR(64)  NOT NULL,
    width          INT          NOT NULL,
    height         INT          NOT NULL,
    verdict        VARCHAR(20)  NOT NULL,
    features_json  TEXT,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_uri_user_created ON ugc_reference_images (user_id, created_at);

ALTER TABLE character_creation_jobs ADD COLUMN IF NOT EXISTS reference_image_id BIGINT;
ALTER TABLE character_creation_jobs ADD COLUMN IF NOT EXISTS adjustments_json TEXT;
ALTER TABLE characters ADD COLUMN IF NOT EXISTS reference_image_id BIGINT;
