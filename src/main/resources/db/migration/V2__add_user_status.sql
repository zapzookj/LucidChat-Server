-- V2 · Phase 6 Phase C: 계정 상태(수동 정지/차단) 컬럼 추가
-- users 테이블은 기존 스키마(baseline)에 존재하므로 ALTER 로 컬럼만 추가한다.
-- DEFAULT 'ACTIVE' 로 기존 전체 행을 백필하고 NOT NULL 을 만족시킨다.
-- (Hibernate 엔티티도 status 기본값 ACTIVE 를 세팅하므로 신규 insert 와도 정합)

ALTER TABLE users ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN status_reason VARCHAR(300);
ALTER TABLE users ADD COLUMN status_changed_at TIMESTAMP;

CREATE INDEX idx_user_status ON users (status);
