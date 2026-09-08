# aichat (백엔드) — 작업 지침

프론트 리포에는 UI/UX 작업 원칙이 별도로 있다(`LucidChat-Front/CLAUDE.md`). 이 문서는 **백엔드 작업의 검증 규율과 이 저장소 고유의 함정**을 다룬다.

---

## 1. 검증 규율 (2026-08-20 실패 사례에서 확립 — 종원 승인)

블록 D 작업 중 **게이트 2곳이 코드에 안 들어갔는데 "완료"로 보고된 사고**가 있었다. 원인 분석 결과 확립한 규칙이다. 배경: 실패는 판단력이 아니라 **검증 생략**에서 나왔고, 구조는 컴파일러가 잡지만 **의미는 아무도 안 잡는다**.

### 1-1. 구조적 편집은 Edit 도구로 한다

- **이 저장소는 CRLF다.** `node`/`sed`로 **여러 줄에 걸친 패턴**을 치환하면 조용히 실패한다.
- Edit 도구는 정확 매칭이고 실패하면 에러를 낸다. 구조를 바꾸는 편집(메서드 추가·시그니처 변경·가드 삽입)은 Edit를 쓴다.
- 스크립트가 꼭 필요하면 **치환마다 개별 assert**. 파일 단위 `if (s === o) throw`는 **금지** — 한 스크립트에 여러 치환을 묶으면 그중 하나(예: import 한 줄)만 성공해도 통과해 버린다. 실제로 이 패턴 때문에 위 사고가 났다.
- 라인 단위 처리(`split('\n')` 후 인덱스 조작)는 CRLF에 안전하다. 다중행 문자열 매칭만 위험하다.
- **블록 절단은 종료 "마커 문자열"이 아니라 태그·중괄호 균형으로 경계를 잡아라.** 2026-08-21에 JSX 블록을 자르면서 종료 마커(`{/* ━━━`)가 엉뚱한 곳에 걸려 **260줄을 과절단**했다 — 방금 되살린 기능까지 함께 날아갔다.
- 손상이 의심되면 국소 패치를 반복하지 마라. **HEAD 복원 + 편집마다 assert를 건 단일 스크립트로 재적용**이 빠르고 안전하다. (위 사고에서 국소 복구를 두 번 시도하다 더 망가뜨렸다.)

### 1-2. "완료"는 도구 반환이 아니라 최종 파일 상태로 판정한다

편집 후 **`grep`으로 목표 문자열이 실제로 있는지 확인하기 전엔 완료라고 보고하지 않는다.** 위 사고 두 건 모두 이 확인 하나로 막혔다.

### 1-3. 컴파일 통과를 완료로 취급하지 않는다

- 컴파일러는 **구조적 실패**(인자 개수, 타입)를 잡는다.
- 컴파일러는 **의미적 실패**를 못 잡는다 — 특히 **게이트·가드·플래그 검사**. 주입만 되고 안 쓰이는 필드는 정상 컴파일된다.
- 가드류를 넣었으면 **가드 문자열을 grep해서 개수를 세라.**

```bash
# 예: legacy 게이트가 실제로 걸렸는지
grep -c "legacy.get" src/main/java/.../AchievementService.java
```

### 1-4. 논리 단위마다 검증하고 상태를 보고한다

턴 길이가 아니라 **작업 단위**로 끊는다. 배칭 압력이 검증을 구조적으로 불가능하게 만드는 것이 문제의 뿌리다.
("한 턴 N줄 이상 금지" 같은 총량 상한은 쓰지 않는다 — 원인을 안 건드리면서 긴 맥락의 이점만 잃는다.)

### 1-5. 큰 변경 뒤에는 새 컨텍스트의 검토를 붙인다

위 사고를 잡아낸 것은 작업자 본인이 아니라 **별도 컨텍스트의 조사 에이전트**였다. 턴을 짧게 만드는 것보다 검사하는 눈을 하나 더 두는 쪽이 비용 대비 효과가 크다.

### 1-6. 세션 중 발견한 환경 함정은 즉시 메모리에 적는다

"기억하고 있겠다"로는 같은 턴 안에서도 안 남는다. CRLF 함정이 실제로 여덟 번쯤 재발했다.

### 1-7. 문서를 인용할 때는 **원문 문장을 붙인다** (2026-09-08 사고에서 확립)

`§C#6`·`안건 18` 같은 **기호만 인용하면 다음 사람이 뜻을 지어낸다.** 실제로 그렇게 됐다:

- `docs/14` §C#6은 "극장 **유지**"(존속 결정)인데, 옆 조항의 "페르소나 개편이 극장 코드를 건드리면 안 된다"와 합쳐져 **"극장은 건드리지 않는다"**는 없던 금지가 만들어졌다.
- 그 오인용이 §2-5로 굳어 **극장 결함 13건을 5주간 붙잡았고**, 정작 코드는 이미 세 번 그 선을 넘어 배포돼 있었다.

**규칙**: 다른 문서의 결정·제약을 근거로 쓸 때는 **그 문장을 그대로 옮겨 적는다.** 기호는 좌표 표기로만 괄호에 남긴다.
요약으로 대체하지 마라 — 요약이 한 번 어긋나면 원문을 다시 여는 사람이 없다.

### 1-8. 결정은 **`19_assets/decisions_confirmed.md` 하나**가 정본이다

결정 목록을 만드는 문서(`21_FinalBugFix_Readiness.md` §F · `24_Decision_Briefs.md` · 세션 인계)는
**착수 전 그 파일을 먼저 대조한다.**

이 규약이 없어서 2026-09-08에 사고가 났다 — 종원이 2026-08-21에 답한 3건이 §F 표에 반영되지 않아 **미결로 재보고**됐고,
그중 하나는 **존재하지 않는 결정을 기다리며 라이브 표시 오류를 유보**시켰다(유료 구독 카드가 실지급의 2배를 광고).

**종원의 답을 받으면 그 자리에서 `decisions_confirmed.md`에 먼저 적고, 파생 문서를 갱신한다.** 순서를 뒤집지 마라.

---

## 2. 이 저장소 고유의 함정

### 2-0. ★ prod의 `ddl-auto`는 `validate`가 아니라 **`update`다** (2026-09-03 실측 정정)

`application-prod.yml:24`는 `ddl-auto: validate`라고 적혀 있지만, Vultr `.env`의
`SPRING_JPA_HIBERNATE_DDL_AUTO=update`가 compose `env_file`로 주입돼 **그 값을 덮는다.**
실행 중인 컨테이너에서 실측한 결과다:

```bash
ssh -i ~/.ssh/lucid_deploy root@141.164.37.146 \
  "docker exec lucid-app printenv | grep DDL_AUTO"   # → SPRING_JPA_HIBERNATE_DDL_AUTO=update
```

이게 바꾸는 것:

- **스키마 안전망이 없다.** validate라면 엔티티↔스키마 불일치가 부팅에서 잡히지만, update는 **조용히 ALTER한다.** 엔티티에 필드를 하나 추가하면 Flyway 없이도 프로드 컬럼이 생긴다 — §2-1이 말하는 "V2/Theater 테이블이 Hibernate로 생성됐다"가 과거형이 아니라 **현재도 그렇다**는 뜻이다.
- 반대로 **마이그레이션 누락이 배포를 죽이지 않는다.** V32~V35처럼 컬럼이 빠져도 Hibernate가 만들어 부팅은 된다 — 그래서 "부팅했으니 스키마가 맞다"는 판정이 성립하지 않는다.
- **§2-7의 CHECK 함정은 그대로다.** update도 **기존 CHECK를 갱신하지 않는다.** 이유만 바뀌었지 결론은 같다.
- yml만 보고 `validate`라고 단정하지 마라. **환경변수가 최종 권위다.**

### 2-1. Flyway — 컬럼을 엔티티에서 떼기 전에 NOT NULL을 먼저 풀어라

- <s>prod는 `ddl-auto: validate`</s> → **§2-0 참조: 실제로는 `update`다.** 어느 쪽이든 **매핑되지 않은 잉여 컬럼을 문제 삼지 않으므로** 엔티티 필드만 떼도 부팅은 된다(아래 논리는 그대로 유효).
- 그러나 **NOT NULL + DEFAULT 없음** 컬럼은 필드를 떼는 순간 신규 INSERT가 NOT NULL 위반으로 죽는다.
- V2/Theater 계열 테이블 상당수가 **Flyway가 아니라 Hibernate `ddl-auto=update`로 생성**됐다(`chat_room_heroines` 등). 즉 DEFAULT가 없다.
- **선행 마이그레이션으로 `DROP NOT NULL`만 먼저 하고, 실제 `DROP COLUMN`은 다음 릴리즈로 미룬다**(롤백 여지). 선례: `V26__drop_bpm_not_null.sql`.

### 2-2. 마이그레이션 번호는 쓰기 전에 확인한다

- 이미 적용된 마이그레이션 파일은 **절대 수정하지 않는다**(checksum mismatch로 부팅 실패). 주석 한 줄도 안 된다.
- 같은 번호를 새로 만들면 **로컬은 checksum mismatch로 죽고 프로드는 조용히 통과**해 스키마가 갈린다 — 최악의 분기다.
- 착수 전 `ls src/main/resources/db/migration/`로 다음 가용 번호를 확인하라.

### 2-3. `application.yml`의 `flyway.enabled`

`feature/diorama` 작업 때 `false`로 바꿔 두는 일이 있었다. **이 줄이 커밋에 섞이면 프로드가 Flyway 없이 부팅한다** — 마이그레이션이 조용히 미적용되어 "고쳤다고 믿는데 안 고쳐진" 상태가 된다.

- Flyway를 꺼야 하면 yml이 아니라 **환경변수**로: `$env:SPRING_FLYWAY_ENABLED="false"; .\gradlew.bat bootRun`
- 커밋 전 `git status --porcelain`로 이 파일이 스테이징되지 않았는지 확인. **`git add .` / `git commit -a` 금지.**

### 2-4. 레거시 게이트 규약 (`legacy.*`)

블록 D에서 도입한 노브 체계(`LegacyFeatureProperties`). 원칙은 **"코드 보존, 진입만 차단"**이다.

- **게이트는 반드시 서버측**이다. 프론트 진입점만 지우면 API가 소유권 검사만으로 열린 채 남는다 — `/users/beta-activate`가 정확히 그 실수였다.
- 노브는 기존 관례대로 `${ENV:default}` 형태로 yml에 두고 기본값은 꺼짐.
- 게이트를 넣었으면 §1-3대로 grep으로 개수를 확인한다.

### 2-5. 극장(Theater) — **버그 수리는 허용, 개편은 금지** (2026-09-08 종원 확정)

**경계 문장** — 이대로 적용한다:

> 극장의 **유저 체감 동작(게임 규칙·스탯 체계·기능 범위)은 불변**. 정합·안전·과금·스키마 수리는 **허용**.
> **엔드포인트 삭제는 금지** — 막아야 하면 노브로 차단한다(되돌릴 여지 보존).

기능을 **살릴지 접을지**는 여전히 별도 결정이다(미리 만들기 · 리롤 화면 · 난입 · 엔딩 모델 원가 — `19_assets/decisions_confirmed.md` §H).

**⚠ 종전에 여기 있던 "극장은 건드리지 않는다"는 금지는 오해였다.** 5주간 극장 결함 13건을 붙잡았다. 경위:

- `docs/14` §C#6 **원문에 '무변경'이라는 말이 없다** — "엔딩 = 자유·스토리만 게이트 오프(코드 보존·**극장 유지**)". 극장을 **존속**시키는 결정이다.
- "극장 코드를 한 줄도 건드리면 안 된다"는 `docs/14_assets/impl_spec_details.md`에 있고 주어가 **"페르소나 개편이"**다.
- **종원 확정(2026-09-08)**: 그 지시의 뜻은 *"페르소나 개편이 추가하는 유저 스탯(스토리·자유 모드)과 극장 모드의 스탯은 **별개의 스탯**"* 이었다. **극장 버그 수정과는 완전 무관하다.**
- 이 지침이 두 조항을 합쳐 일반화하면서 그 원래 뜻이 사라졌다.

**그래서 이 항목이 §1-1의 사례가 된다** — 인용 규약(§1-7)이 여기서 나왔다.

공용 메서드에 플래그 파라미터를 추가할 때 극장 호출부를 현행 동작으로 고정하는 것(예: `getAllowedOutfits(..., /* relationGated */ true)`)은 **여전히 맞다** — 그건 '체감 동작 불변'의 적용이지 금지가 아니다.

### 2-6. 하위호환 오버로드를 남기지 마라

시그니처를 바꿀 때 2-arg 구버전을 남기면 **호출부가 조용히 낡은 경로로 컴파일된다.** 오버로드를 없애면 컴파일러가 호출부를 전수로 드러내 준다 — 그게 검증 수단이다.

### 2-7. enum에 값을 추가하면 DB CHECK 제약도 함께 넓혀라

Hibernate 6.2+(Boot 3.4.2 = Hibernate 6.6)는 `@Enumerated(EnumType.STRING)` 컬럼에 **값 목록 CHECK 제약을 자동 생성**한다(`<table>_<column>_check`). 그런데 `ddl-auto`는 **update든 validate든 기존 CHECK를 갱신하지 않는다.**

- 즉 enum에 값을 추가하면 **컴파일 통과·부팅 성공·테스트 녹색인 채로** 신규 값 저장만 런타임에 죽는다(`PSQLException: ... _check 제약 조건을 위반`). 2026-08-29에 `Location` 5종 추가(안건 11 (a))가 정확히 이렇게 터졌다 — 해당 캐릭터의 **모든 응답이 500**. 수정: `V31__chat_rooms_location_check_sync.sql`.
- **"Flyway에 CREATE TABLE 이력이 없다 = CHECK가 없다"는 오판이다.** Hibernate가 만든 테이블일수록 CHECK가 붙어 있다. 반드시 실측하라.
- prod는 **`update`다(§2-0 — yml의 `validate`는 환경변수에 덮인다)**. `update`도 **기존 CHECK를 갱신하지 않고**, 애초에 CHECK를 검증하지도 않는다. 배포해도 경고 하나 없이 런타임에만 재현된다 — validate였을 때와 결론이 같다.

```bash
# 실측: 특정 컬럼의 CHECK 제약 정의
psql -tAc "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
           WHERE conrelid='chat_rooms'::regclass AND contype='c';"
```

마이그레이션은 제약 이름을 가정하지 말고(환경별 접미 숫자) 해당 컬럼의 CHECK를 전수로 떨군 뒤 하나로 재생성한다 — V31이 그 형식이다(멱등).

---

## 3. 검증 명령

```bash
./gradlew compileJava --no-daemon -q
./gradlew test --tests '*Test' --no-daemon -q
```

- 테스트는 **33개 파일**(2026-09-08 실측) / 순수 유닛(Mockito·POJO)뿐이다. **통합·리포지토리·컨트롤러 테스트는 0건.**
- `AichatApplicationTests`(`@SpringBootTest`)는 `src/test/resources` 부재로 CI가 `*Test` 글롭으로 의도적으로 제외한다 — 사실상 죽은 테스트다.
- 즉 **자동 테스트가 잡아주는 범위가 좁다.** 서비스 로직 변경은 수동 재현 시나리오를 함께 남겨라.
- 베이스라인 실측(**2026-09-08 갱신**): `*Test` 글롭 **31클래스 / 197건 전부 녹색**(테스트 파일 33개). <s>2026-08-21: 21클래스 / 116건</s> — 배치 1~8이 그 사이에 들어갔다. **"116건 녹색"을 기준선으로 인용하지 마라.**
- ✅ **컨텍스트 기동 검증은 가능하다 — 반드시 하라.** 2026-08-21에 `MongoConfig`의 `@EnableMongoRepositories` basePackages 누락(`domain.ending` 빠짐)으로 **애플리케이션이 부팅되지 않는 상태**가 컴파일 통과 + 116건 녹색인 채로 master에 올라갔다(docs/19 §C-1). 리포지토리·설정 클래스를 신설하면 **패키지가 스캔 범위 안인지** 확인하고, 아래로 실제 기동을 확인하라.

```bash
# 로컬 기동 검증. JWT_SECRET_BASE64가 없으면 Base64 디코드에서 죽으니(Illegal base64 character 24) 더미로 채운다.
JWT_SECRET_BASE64="$(node -e "console.log(Buffer.alloc(32,7).toString('base64'))")" ./gradlew bootRun --no-daemon
# 성공 판정 문자열: "Started AichatApplication in"
```

- ⚠ **프론트: `vite build` 통과를 검증으로 믿지 마라.** 존재하지 않는 **named export**를 import하면 rollup은 **경고만** 내고 exit 0이다(`"X" is not exported by ...`). 반면 `npm run dev`는 모듈 로드 시점에 던져 **페이지가 즉시 죽는다.** 2026-08-21에 `/events/select` 삭제 후 `sendEventSelectStream` import가 `ChatPage.jsx`·`ChatPageV2.jsx`에 남아 정확히 이렇게 master에 올라갔다.

```bash
# 심볼을 지웠으면 빌드 로그의 이 경고를 반드시 훑어라 (build만 보면 놓친다)
npm run build 2>&1 | grep -i "not exported"
```

---

## 4. 문서 정본

| 주제 | 위치 |
|---|---|
| 제품 결정 (로비·페르소나·BM·레거시 처분 §G 21건) | `docs/14_ProductDecisions_Session_Handoff.md` + `14_assets/impl_spec_details.md` |
| 시크릿 모드 전략 (핵심 BM) | `docs/16_SecretMode_Pivot_Directive.md` |
| 버그 레지스터 (원자 245건 — 근거·수정안 **본문**) | `docs/17_assets/defect_register.md` |
| **★★ 마무리 착수 지시 정본** (실측 기반 배치 계획) | `docs/26_Wrapup_Execution_Readiness.md` — 2026-09-08. **원장·인계와 어긋나면 이쪽이 우선**(원장 낡은 행을 실측으로 정정한 문서다). 결정 자체는 여전히 `19_assets/decisions_confirmed.md`가 정본 |
| **★ 최종 버그픽스 스코프 정본** (코드 85 · 설정 18 · 결정 12) | `docs/21_FinalBugFix_Readiness.md` + `docs/21_assets/scope_ledger.md` — 2026-09-04 전수 재판정. **델타와 어긋나면 이쪽이 우선** |
| 결함 상태·좌표 (블록 D 재판정 델타 245행) | `docs/19_assets/rejudgment_delta.md` — docs/21이 덮는다 |
| **버그픽스 결정 안건 정본** (22건 + 결정 불요 33건) | `docs/19_assets/decision_agenda.md` · 상위 판단 `docs/19_Register_Rejudgment.md` |
| 블록 D 회귀·미등재 신규 결함 | `docs/19_assets/blockd_regressions.md` |
| 버그픽스 배치 계획 | `docs/21_FinalBugFix_Readiness.md` §E (구: `docs/17_BugFix_Session_Readiness.md` §D · `docs/19_assets/round3_plan.md` §3) |
| 런칭 행정·인프라 실행 | `docs/18_Launch_Admin_Runbook.md` (§4 결정 안건은 docs/19가 대체) |
| 상태창 개편 설계 | `docs/17_assets/hud_redesign_mockup.html` |

`docs/13`의 파일:라인은 낡았다 — **docs/17 레지스터가 정본 좌표**다.

---

## 5. 커밋

- 커밋·푸시는 **종원이 요청할 때만** 한다.
- 마이그레이션은 무관한 코드 변경과 같은 커밋에 넣지 않는다(롤백 단위 분리).
- 메시지는 기존 관례를 따른다: `feat : …` / `fix : …` / `docs : …` / `refactor: …`, 본문에 근거 문서·결정 출처를 남긴다.
