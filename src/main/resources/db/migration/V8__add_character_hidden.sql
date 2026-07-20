-- V8 · Phase 6 Phase G: 캐릭터 전역 숨김 필드
-- story_available/theater_available 는 모드별 지원 플래그일 뿐 전역 숨김이 아니므로 별도 컬럼 신설.
-- 기존 전체 행은 DEFAULT FALSE(노출)로 백필. 시드(YAML)는 이 컬럼을 건드리지 않아 admin 편집이 보존된다.
ALTER TABLE characters ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;
