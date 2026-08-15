-- [2026-08-15 블록 B 페르소나 개편] 인식 렌즈 5축 + 프로필 문법 (docs/14 #4, impl_spec §1-2)
-- 개념 역전: "카드 라이브러리에서 골라 쓰기" → "항상 존재하는 단일 활성 프로필 + 카드=저장 슬롯"(무료 3/구독 10).
-- 구 5축(Theater 유래 능력 스탯 — 자기인식 vs 객관현실 Hybrid)은 기각, 렌즈 5축
-- ("캐릭터가 유저를 어떻게 인식하는가")으로 교체. 극장 아바타 5축(TheaterState)은 완전 별개 — 무접촉.
-- 기존 카드: 본문·이름·성별 보존, 스탯은 중립값(5)으로 리셋 (종원 확정 2026-08-15).

ALTER TABLE user_personas DROP COLUMN IF EXISTS stat_charm;
ALTER TABLE user_personas DROP COLUMN IF EXISTS stat_wit;
ALTER TABLE user_personas DROP COLUMN IF EXISTS stat_boldness;
ALTER TABLE user_personas DROP COLUMN IF EXISTS stat_intellect;
ALTER TABLE user_personas DROP COLUMN IF EXISTS stat_empathy;

-- 렌즈 5축 — 축당 0~10, 총점 25(전 유저 공통, 서비스 계층 검증). DEFAULT 5 = 중립 인식 베이스라인
-- (부트스트랩 프로필·리셋된 구 카드 공용 — 전 축 5는 총점 25를 정확히 소진해 제로섬 재배분 문법 성립).
ALTER TABLE user_personas ADD COLUMN IF NOT EXISTS lens_allure       INT NOT NULL DEFAULT 5;  -- 매력: 끌린다
ALTER TABLE user_personas ADD COLUMN IF NOT EXISTS lens_friendliness INT NOT NULL DEFAULT 5;  -- 친근함: 편하다
ALTER TABLE user_personas ADD COLUMN IF NOT EXISTS lens_trust        INT NOT NULL DEFAULT 5;  -- 신뢰감: 믿음직하다
ALTER TABLE user_personas ADD COLUMN IF NOT EXISTS lens_charisma     INT NOT NULL DEFAULT 5;  -- 카리스마: 어렵다/우러러본다
ALTER TABLE user_personas ADD COLUMN IF NOT EXISTS lens_mystique     INT NOT NULL DEFAULT 5;  -- 신비: 궁금하다

-- 나이: 자유 입력(미성년 유저의 SFW 이용 정당 — 프로필 단계 차단은 과잉으로 기각).
-- 하드 게이트는 시크릿 활성 시점 단 한 곳: null 또는 <19 → 활성 불가 (SecretModeService).
ALTER TABLE user_personas ADD COLUMN IF NOT EXISTS age INT;

-- 유저당 1행의 활성 프로필 마커 — 기존 행은 전부 카드(FALSE)로 남고 프로필은 최초 접근 시 lazy 생성.
ALTER TABLE user_personas ADD COLUMN IF NOT EXISTS is_profile BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_persona_profile ON user_personas (user_id) WHERE is_profile;

-- chat_rooms.persona_stats_json 컬럼은 재사용: 신규 방 스냅샷은 렌즈 키({"allure":..}),
-- 구 방의 구 키({"charm":..}) 스냅샷은 프롬프트 무주입으로 전환 (PersonaLensPromptBlock 키 감지).
