-- [2026-09-11 자유모드 페르소나 해제] 자유(SANDBOX) 방의 결손 스냅샷을 현재 프로필로 1회 백필.
--
-- 배경: 블록 B(V25)가 페르소나를 '방 생성 시점 스냅샷'으로 바꿨는데, 방은 유저×캐릭터×모드로
-- idempotent라 두 번째 진입부터 스냅샷 분기를 타지 않았고, 대화 기록 초기화도 페르소나를
-- 보존했다. 그래서 블록 B 이전에 만들어진 방과 프로필을 채우기 전에 만든 방은
-- user_persona/persona_stats_json이 빈 채로 영구 고정됐다 — 화면만 공란인 게 아니라
-- 프롬프트에도 페르소나가 주입되지 않는 상태다(ChatRoom.getEffectivePersona는
-- room.userPersona ?? user.profile_description을 보는데 새 프로필은 user_personas에 있다).
--
-- 코드 쪽 항구 수리는 같은 릴리즈에 포함된다(초기화 시 자동 재적용 + 설정창 수동 재적용).
-- 이 스크립트는 이미 그 상태로 굳은 기존 방만 되살린다.
--
-- 범위 한정 — 자유 모드만:
--   스토리는 '시작 시점 고정'이 확정 정책이라 진행 중인 방의 페르소나를 서버가 바꾸지 않는다.
--   스토리 방은 기존 '현재 프로필로 새로 시작'(resetStory includePersona=true)으로 유저가 갱신한다.
--   극장(THEATER)도 chat_mode 조건으로 제외된다.
--
-- 멱등: 두 문장 모두 '비어 있는 칸만 채운다'라 재실행해도 결과가 같다.

-- ── (1) 페르소나 스냅샷 결손 보충 ───────────────────────────────────────────────
-- 이미 값이 있는 칸은 COALESCE로 보존한다. 블록 B 이전에는 유저가 방마다 페르소나를 직접
-- 써넣을 수 있었고(당시 PATCH /chat/rooms/{id}/persona), 그 방들은 user_persona가 채워진 채
-- persona_stats_json만 비어 있다 — 덮어쓰면 유저가 쓴 글이 사라지고, 현재 프로필 소개가
-- 비어 있으면 아예 NULL로 지워진다(복구 불가). 결손 칸만 메우는 게 이 백필의 범위다.
UPDATE chat_rooms r
SET user_persona        = COALESCE(NULLIF(r.user_persona, ''), NULLIF(p.persona_text, '')),
    persona_stats_json  = COALESCE(r.persona_stats_json, jsonb_build_object(
                              'allure',       p.lens_allure,
                              'friendliness', p.lens_friendliness,
                              'trust',        p.lens_trust,
                              'charisma',     p.lens_charisma,
                              'mystique',     p.lens_mystique
                          )::text),
    persona_gender      = COALESCE(r.persona_gender, p.gender, 'MALE'),
    story_user_nickname = COALESCE(NULLIF(r.story_user_nickname, ''), NULLIF(p.name, ''))
FROM user_personas p
WHERE p.user_id = r.user_id
  AND p.is_profile = TRUE
  AND r.chat_mode = 'SANDBOX'
  AND (r.user_persona IS NULL OR r.persona_stats_json IS NULL);

-- ── (2) 호칭 결손 보충 ────────────────────────────────────────────────────────
-- (1)과 분리하는 이유: 블록 B 이후 정상 생성된 자유 방은 페르소나 두 칸이 멀쩡하고
-- story_user_nickname만 비어 있다(그때 LobbyService가 이름을 안 찍었다). (1)의 술어로는
-- 안 걸리는데, 그냥 WHERE에 OR로 얹으면 멀쩡한 방의 페르소나까지 SET 대상이 된다.
-- 이 방들이 바로 종원이 보고한 '자유방과 스토리방의 호칭이 다르다'의 대다수 코호트다.
UPDATE chat_rooms r
SET story_user_nickname = p.name
FROM user_personas p
WHERE p.user_id = r.user_id
  AND p.is_profile = TRUE
  AND r.chat_mode = 'SANDBOX'
  AND (r.story_user_nickname IS NULL OR r.story_user_nickname = '')
  AND p.name IS NOT NULL
  AND p.name <> '';
