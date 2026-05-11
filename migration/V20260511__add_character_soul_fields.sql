-- [Phase 6 도그푸딩 #3] 캐릭터 영혼 필드 추가
--
-- 목적:
--   Character 엔티티에 5개 정체성 필드 신설.
--   "유저바라기" 현상 차단을 위한 살아있는 영혼의 콘텐츠 슬롯.
--
-- 적용 대상: PostgreSQL (application-prod.yml dialect 기준)
-- 실행 시점: 사용자 직접 실행 (psql/pgAdmin).
--
-- 안전성:
--   - 모든 컬럼 nullable → 기존 행은 NULL로 자동 채워짐, 기능적 무중단.
--   - IF NOT EXISTS 가드 사용 → 중복 실행 안전.
--   - 콘텐츠 입력은 별도 단계 (사용자 직접 진행).
--
-- 롤백:
--   ALTER TABLE characters
--     DROP COLUMN IF EXISTS backstory,
--     DROP COLUMN IF EXISTS core_values,
--     DROP COLUMN IF EXISTS flaws,
--     DROP COLUMN IF EXISTS behavioral_anchors,
--     DROP COLUMN IF EXISTS speech_quirks;

ALTER TABLE characters
    ADD COLUMN IF NOT EXISTS backstory          TEXT,
    ADD COLUMN IF NOT EXISTS core_values        TEXT,
    ADD COLUMN IF NOT EXISTS flaws              TEXT,
    ADD COLUMN IF NOT EXISTS behavioral_anchors TEXT,
    ADD COLUMN IF NOT EXISTS speech_quirks      TEXT;

-- 컬럼 설명 (운영/디버깅 참조용)
COMMENT ON COLUMN characters.backstory          IS '캐릭터 과거사 (3~5문단). 어떤 사건이 지금의 가치관을 형성했는가';
COMMENT ON COLUMN characters.core_values        IS '가치관/철학 (5~7개 bullet). 무엇을 옳다/그르다 여기는가';
COMMENT ON COLUMN characters.flaws              IS '약점·두려움·모순 (3~5개 bullet)';
COMMENT ON COLUMN characters.behavioral_anchors IS '절대 하지 않는 것 (5~10개 bullet) — 영혼의 기둥';
COMMENT ON COLUMN characters.speech_quirks      IS '어휘 습관·말버릇 (구체 예시 포함)';
