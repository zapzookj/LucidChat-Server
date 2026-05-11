-- [Phase 6 Tier 4 / H-19, H-20, H-21] Optimistic Lock 컬럼 추가
--
-- 목적:
--   User / ChatRoom / TheaterState 3개 엔티티에 @Version 매핑된 version 컬럼 추가.
--   동시 수정 시 lost update를 차단한다.
--
-- 적용 대상: PostgreSQL (application-prod.yml dialect 기준)
-- 실행 시점: 사용자 직접 실행 (psql/pgAdmin).
--
-- 안전성:
--   - DEFAULT 0 + NOT NULL → 기존 행은 0으로 채워짐, 기능적 무중단.
--   - IF NOT EXISTS 가드 사용 → 중복 실행 안전.
--   - 컬럼이 추가된 후 Spring Boot 재기동하면 JPA가 @Version으로 자동 인식.
--
-- 호출 흐름 영향:
--   - 동시 차감(채팅 -2 + 일러스트 -10 등) → 한쪽이 OptimisticLockingFailureException 발생.
--   - 해당 호출자는 retry 정책으로 대응(향후 별도 작업으로 retry 강화 검토).
--   - 단일 호출은 영향 없음.
--
-- 롤백:
--   ALTER TABLE users          DROP COLUMN IF EXISTS version;
--   ALTER TABLE chat_rooms     DROP COLUMN IF EXISTS version;
--   ALTER TABLE theater_states DROP COLUMN IF EXISTS version;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE chat_rooms
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE theater_states
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.version          IS 'JPA @Version — optimistic lock';
COMMENT ON COLUMN chat_rooms.version     IS 'JPA @Version — optimistic lock';
COMMENT ON COLUMN theater_states.version IS 'JPA @Version — optimistic lock';
