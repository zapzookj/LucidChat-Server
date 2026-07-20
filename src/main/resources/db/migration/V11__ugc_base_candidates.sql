-- V11 · UGC 개편(2026-07-20): 베이스 스탠딩 후보 선택/리롤 지원
-- 선택된 황금샷에서 서로 다른 seed로 병렬 파생한 스탠딩 후보 [{key, seed, status}] 저장
-- (IF NOT EXISTS: ddl-auto=update가 먼저 컬럼을 만든 로컬 DB와의 충돌 방지)
ALTER TABLE character_creation_jobs ADD COLUMN IF NOT EXISTS base_candidates_json TEXT;

-- BASE_WAIT 상태 신설 — 구버전 enum 목록으로 생성된 Hibernate CHECK 제약 제거
-- (V10의 current_outfit 제약과 동일한 이유. 앱 레벨 enum이 값을 보장.)
ALTER TABLE character_creation_jobs DROP CONSTRAINT IF EXISTS character_creation_jobs_status_check;
