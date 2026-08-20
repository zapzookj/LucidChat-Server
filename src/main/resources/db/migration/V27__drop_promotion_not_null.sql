-- [블록 D · docs/14 §G-1] V1 승급 '시험' 폐지 — 엔티티 필드 제거 선행 스키마 (additive·멱등)
--
-- 결정(종원 2026-08-20): (b)안 — 5턴 시험을 제거하고 임계 도달 시 즉시 승급.
--   V2 이중게이트(자격 활성 + LLM 자율 발동) 이식은 하지 않는다. 승급 세리머니는 유지.
--   ENEMY 회복은 단계만 조용히 복원하고 세리머니를 띄우지 않는다.
--
-- 시험이 사라지면서 죽는 상태값 4종:
--   promotion_pending / promotion_mood_score / promotion_turn_count / promotion_waiting_for_topic
--   (pending_target_status · promotion_waiting_target은 이미 nullable이라 손댈 것이 없다.)
--
-- 왜 DROP COLUMN이 아니라 DROP NOT NULL인가 — V26과 동일한 이유:
--   이 컬럼들은 NOT NULL + DEFAULT 없음이라, 엔티티에서 필드를 떼는 순간 신규 방 INSERT가
--   NOT NULL 위반으로 죽는다. 제약만 먼저 풀고 컬럼은 한 릴리즈 유지한다(롤백 여지).
--   실제 DROP COLUMN은 다음 릴리즈에서 V26 대상과 함께.

ALTER TABLE chat_rooms ALTER COLUMN promotion_pending          DROP NOT NULL;
ALTER TABLE chat_rooms ALTER COLUMN promotion_mood_score       DROP NOT NULL;
ALTER TABLE chat_rooms ALTER COLUMN promotion_turn_count       DROP NOT NULL;
ALTER TABLE chat_rooms ALTER COLUMN promotion_waiting_for_topic DROP NOT NULL;
