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

---

## 2. 이 저장소 고유의 함정

### 2-1. Flyway — 컬럼을 엔티티에서 떼기 전에 NOT NULL을 먼저 풀어라

- prod는 `ddl-auto: validate`(`application-prod.yml`). validate는 **매핑되지 않은 잉여 컬럼을 문제 삼지 않으므로** 엔티티 필드만 떼도 부팅은 된다.
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

### 2-5. 극장(Theater)은 건드리지 않는다

docs/14 §C#6에서 **극장 무변경**이 확정됐다. 공용 메서드에 플래그 파라미터를 추가할 때 극장 호출부는 **현행 동작으로 고정**하라(예: `getAllowedOutfits(..., /* relationGated */ true)`).

### 2-6. 하위호환 오버로드를 남기지 마라

시그니처를 바꿀 때 2-arg 구버전을 남기면 **호출부가 조용히 낡은 경로로 컴파일된다.** 오버로드를 없애면 컴파일러가 호출부를 전수로 드러내 준다 — 그게 검증 수단이다.

---

## 3. 검증 명령

```bash
./gradlew compileJava --no-daemon -q
./gradlew test --tests '*Test' --no-daemon -q
```

- 테스트는 23개 파일 / 순수 유닛(Mockito·POJO)뿐이다. **통합·리포지토리·컨트롤러 테스트는 0건.**
- `AichatApplicationTests`(`@SpringBootTest`)는 `src/test/resources` 부재로 CI가 `*Test` 글롭으로 의도적으로 제외한다 — 사실상 죽은 테스트다.
- 즉 **자동 테스트가 잡아주는 범위가 좁다.** 서비스 로직 변경은 수동 재현 시나리오를 함께 남겨라.
- 베이스라인 실측(2026-08-21, `cleanTest` 강제 재실행): `*Test` 글롭 **21클래스 / 116건 전부 녹색**.
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
| **결함 상태·좌표 정본** (블록 D 재판정 델타 245행) | `docs/19_assets/rejudgment_delta.md` |
| **버그픽스 결정 안건 정본** (22건 + 결정 불요 33건) | `docs/19_assets/decision_agenda.md` · 상위 판단 `docs/19_Register_Rejudgment.md` |
| 블록 D 회귀·미등재 신규 결함 | `docs/19_assets/blockd_regressions.md` |
| 버그픽스 배치 계획 | `docs/17_BugFix_Session_Readiness.md` §D |
| 런칭 행정·인프라 실행 | `docs/18_Launch_Admin_Runbook.md` (§4 결정 안건은 docs/19가 대체) |
| 상태창 개편 설계 | `docs/17_assets/hud_redesign_mockup.html` |

`docs/13`의 파일:라인은 낡았다 — **docs/17 레지스터가 정본 좌표**다.

---

## 5. 커밋

- 커밋·푸시는 **종원이 요청할 때만** 한다.
- 마이그레이션은 무관한 코드 변경과 같은 커밋에 넣지 않는다(롤백 단위 분리).
- 메시지는 기존 관례를 따른다: `feat : …` / `fix : …` / `docs : …` / `refactor: …`, 본문에 근거 문서·결정 출처를 남긴다.
