-- V36 · chat_rooms.peak_status_level — 승급 세리머니 히스테리시스 (G-3 · 안건 18 · blockd §A-8)
--
-- ■ 무엇을 고치는가
--   관계 수치가 단계 경계(예: 39↔40)에서 오르내릴 때마다 '관계 상승' 축하 연출이 무제한 반복된다.
--   강등은 무연출이라 유저에게는 '올라감'만 계속 보인다. 블록 D가 구 완충(임계 스냅 + 5턴 시험)을
--   걷어내고 대체를 넣지 않아 생긴 회귀다(blockd_regressions §A-8).
--
--   결정 원문 — 19_assets/decisions_confirmed.md §G-3:
--     "| G-3 | **승급 세리머니 억제 형태** (= 안건 18 변형) | **(peak) 최고 도달 단계 1컬럼** |
--      2026-08-21 확정안 (b) 단계별 이력에서 **변경**. 컬럼 1개 + 리셋 경로 3곳 초기화. V36 |"
--
--   즉 단계(status_level)는 계속 오르내리되, **연출은 '지금까지 도달한 최고 단계'를 넘어설 때만** 낸다.
--
-- ■ 왜 CHECK 제약을 붙이지 않는가  ★ 이 파일의 핵심 주의사항 (CLAUDE.md §2-7)
--   peak_status_level은 @Enumerated(EnumType.STRING) 신규 컬럼이다. Hibernate 6.2+는 그런 컬럼에
--   **값 목록 CHECK를 자동 생성**하는데, ddl-auto는 update든 validate든 **기존 CHECK를 갱신하지 않는다.**
--   그래서 CHECK를 한 번 박아 두면 훗날 RelationStatus에 값을 추가하는 순간
--   V31과 똑같은 사고가 재현된다 — 컴파일 통과·부팅 성공·테스트 녹색인 채로 저장만 런타임에 죽는다.
--
--   반대로 이 마이그레이션 자체를 생략하면 안 된다. prod는 ddl-auto=update이므로(§2-0 실측:
--   Vultr .env의 SPRING_JPA_HIBERNATE_DDL_AUTO=update가 yml의 validate를 덮는다)
--   Flyway가 컬럼을 안 만들면 **Hibernate가 만들면서 CHECK를 붙인다.**
--   Flyway가 먼저 만들어 두면 Hibernate update는 그 컬럼을 건드리지 않는다.
--   → 컬럼은 Flyway가 만들고, CHECK는 만들지 않는다. 이것이 이 파일이 존재하는 이유다.
--
--   참고: 같은 테이블의 chat_rooms_status_level_check는 실재한다(2026-09-08 프로드 실측 —
--   STRANGER/ACQUAINTANCE/FRIEND/LOVER/ENEMY 5값). 그 컬럼은 Hibernate가 만든 것이다.
--
-- ■ 백필
--   기존 방은 '지금 단계까지는 이미 도달했다'로 본다. 안 그러면 배포 직후 모든 방이
--   peak=STRANGER에서 시작해 **이미 겪은 승급의 연출이 한 번씩 다시 터진다.**
--   status_level이 NULL인 행(생성 직후·비 SANDBOX 모드)은 STRANGER로 채운다.
--
--   ★ ENEMY는 peak로 저장하지 않고 STRANGER로 내린다. 서열상 ENEMY는 STRANGER보다 **아래**이므로
--     (RelationStatusPolicy.rank: ENEMY=-1 · STRANGER=0) peak=ENEMY를 그대로 넣으면
--     **peak가 바닥보다 낮은 상태**가 되어, 그 방이 나중에 재상승할 때 이미 겪은 단계에서도
--     연출이 다시 터진다 — 이 마이그레이션이 없애려는 바로 그 증상이다.
--     실측(2026-09-09): 프로드 chat_rooms 34행 중 ENEMY 5행 · 로컬 dev 2행이 여기 걸린다.
--     ENEMY 방의 진짜 과거 최고 단계는 알 수 없으므로 바닥(STRANGER)에서 다시 세는 쪽을 택한다.
--
-- ■ NOT NULL을 걸지 않는 이유 (CLAUDE.md §2-1)
--   NOT NULL + DEFAULT 없음 컬럼은 훗날 엔티티에서 필드를 떼는 순간 신규 INSERT가 죽는다.
--   nullable로 두고 엔티티 기본값(STRANGER)과 코드의 null 안전 처리로 덮는다.
--   롤백 시에도 컬럼을 남겨 두면 무해하다 — 구 코드는 이 컬럼을 읽지 않는다.
--
-- 멱등: ADD COLUMN IF NOT EXISTS · 백필은 IS NULL 조건.
DO $$
BEGIN
    IF to_regclass('public.chat_rooms') IS NOT NULL THEN
        ALTER TABLE chat_rooms ADD COLUMN IF NOT EXISTS peak_status_level VARCHAR(30);

        UPDATE chat_rooms
           SET peak_status_level = CASE
                   WHEN status_level IS NULL      THEN 'STRANGER'
                   WHEN status_level = 'ENEMY'    THEN 'STRANGER'   -- 서열상 바닥보다 아래 (위 설명)
                   ELSE status_level
               END
         WHERE peak_status_level IS NULL;
    END IF;
END $$;
