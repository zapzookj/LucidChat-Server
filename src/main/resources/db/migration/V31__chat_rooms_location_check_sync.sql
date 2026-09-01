-- V31 · chat_rooms.current_location CHECK 제약 동기화 (안건 11 (a) 후속 회귀)
--
-- ■ 무엇이 깨졌는가
--   6758b0e(안건 11 (a))가 Location enum에 CATHEDRAL·TERRACE·STREET·LIBRARY·ANCIENT_SHRINE
--   5종을 추가했다. 그런데 chat_rooms.current_location에는 **Hibernate가 만든 CHECK 제약이
--   이미 있었고**(구값 14종), ddl-auto는 update든 validate든 기존 CHECK를 갱신하지 않는다.
--   → 5종을 기본 장소로 쓰는 클레어·로제타·강채린·에델·류설아 방은 INSERT/UPDATE가 전부
--     PSQLException("chat_rooms_current_location_check 위반")으로 죽는다. 방 상태 UPDATE는
--     매 응답마다 flush되므로 **해당 캐릭터의 모든 응답이 500**이 된다.
--
-- ■ Location.java의 주석("CHECK 제약이 없다 → 마이그레이션 불요")은 오판이었다.
--   Hibernate 6.2+는 @Enumerated(STRING) 컬럼에 값 목록 CHECK를 자동 생성한다(Boot 3.4.2 =
--   Hibernate 6.6). Flyway에 CREATE TABLE 이력이 없다고 CHECK가 없는 것이 아니다.
--   실측(로컬 aichat DB, 2026-08-29):
--     chat_rooms_current_location_check = CHECK (current_location IN (구값 14종))
--   ⚠ prod는 ddl-auto=validate — validate는 CHECK 제약을 검증하지 않으므로 부팅은 성공하고
--     런타임에만 죽는다. 즉 **배포해도 아무 경고 없이 같은 사고가 재현된다.**
--
-- ■ 왜 DO 블록으로 기존 제약을 훑어 지우는가
--   제약 이름은 Postgres가 자동 부여한 것이라 환경별로 접미 숫자가 붙었을 수 있다(prod 미실측).
--   이름을 가정하지 않고 current_location을 참조하는 CHECK를 전수로 떨군 뒤 하나로 재생성한다.
--   멱등 — 재실행하면 방금 만든 제약도 함께 떨어지고 같은 정의로 다시 붙는다.
--
-- ■ 값 목록은 Location.java 전량(19종)과 1:1이다. enum에 값을 추가하면 여기도 함께 늘려야
--   한다. 드리프트 점검:
--     SELECT pg_get_constraintdef(oid) FROM pg_constraint
--      WHERE conname = 'chat_rooms_current_location_check';
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT c.conname
          FROM pg_constraint c
         WHERE c.conrelid = 'chat_rooms'::regclass
           AND c.contype  = 'c'
           AND EXISTS (
               SELECT 1 FROM pg_attribute a
                WHERE a.attrelid = c.conrelid
                  AND a.attnum   = ANY (c.conkey)
                  AND a.attname  = 'current_location')
    LOOP
        EXECUTE format('ALTER TABLE chat_rooms DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;

ALTER TABLE chat_rooms
    ADD CONSTRAINT chat_rooms_current_location_check
    CHECK (current_location IN (
        -- 저택 내부
        'LIVINGROOM', 'BALCONY', 'STUDY', 'BATHROOM', 'GARDEN',
        'KITCHEN', 'BEDROOM', 'ENTRANCE',
        -- 저택 외부
        'FOREST', 'BEACH', 'DOWNTOWN', 'BAR', 'CLUB_ROOM', 'CONVENIENCE_STORE',
        -- 안건 11 (a) 신규 5종
        'CATHEDRAL', 'TERRACE', 'STREET', 'LIBRARY', 'ANCIENT_SHRINE'
    ));
