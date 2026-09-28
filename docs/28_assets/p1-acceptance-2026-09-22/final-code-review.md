# 최종 P1 코드 독립 검토 — 2026-09-22

검토자: `p1_code_review`. 기준은 `master` / `ca27320`의 현재 작업 트리다. 이번 검토는 읽기 전용이며 생성 API 호출, 생산 소스 수정, Gradle 재실행은 하지 않았다. 현재 diff·테스트 소스·실행 XML·runtime hash·생산 JAR·메시지 일치 기록을 읽고 대조했다.

## 결론

최종 변경은 앞서 검토한 P1 수리와 이벤트 속마음 블록의 조건 수리로 한정되어 있다. 이번 범위에서 새 배포 차단 결함은 발견하지 못했다. 기존 수리와 범위가 달라지는 큰 리팩터링이나 게임/과금/모델/DB 처리 변경은 없다. 새 이벤트 가드는 기존 출력의 `inner_thought=null` 계약과 일반 속마음 생성 지침을 맞춘 국소 수리다.

이 코드 판정과 실제 모델 출력의 무오류 보장은 다르다. root가 별도 source hash에서 수행하는 이벤트 4회 확인과 비용 정산을 최종 완료 보고에 결합해야 한다. 이 보고서는 그 실행 결과를 선취하지 않는다.

## 최종 생산 소스 확인

`git diff --name-only -- src/main`은 두 prompt assembler만 포함한다.

| 파일 | 현재 LF 정규화 SHA-256 | 9/18 저장 수리안과의 차이 |
|---|---|---|
| CharacterPromptAssembler.java | `e3aac27c1f0b81cf952098339b1022a7f2b5fcfea197778bab72599bbbb23f78` | 이벤트 가드 `&& !room.isEventActive()` 한 조건 |
| StoryDirectorPromptAssemblerV2.java | `3e7f49516473734ad1c7c1d2fc295876c0b39daec83576988448b847d7f38d1e` | 없음 |

V1의 새 조건만 메모리 상에서 되돌려 계산한 hash는 `71d380d613affcceaad210b36d72d47e8e0b642342f30343b8c50f32e0be844f`로 9/18 복구 manifest와 정확히 일치했다. V2도 해당 manifest와 일치한다. 실제 파일을 되돌리지는 않았다.

따라서 외형·기본 복장, 행동/대사 구간 해석, 사용자 선택권, 중복 배경 제거, V2 무화자/OPENING의 사용자 내면 지시 충돌 제거, 유효 JSON 예시/원 필드 설명 분리라는 P1 범위가 유지된다. 모델/provider, moderation, DTO/parser, stream, 인가, 해금/과금, DB/state/memory 후처리, Theater는 이 생산 diff에 없다.

## 이벤트 가드의 정합성과 경계

- 현재 V1 line 246은 `supportsInnerThought(mode) && !room.isEventActive()`를 검사한다. 이벤트가 활성인 요청에서는 일반 속마음 생성 조건/예시가 빠지고 output의 이벤트 null 지침은 남는다.
- 이벤트가 아닌 director interlude에는 일반 속마음이 유지된다. `hasActiveDirectorConstraint`까지 차단 조건으로 넓히지 않았다.
- ChatRoom의 실제 `startDirectorEvent` → `updateEventStatus("RESOLVED")` 전이에 따라 다음 일반 턴의 프롬프트 전체가 원래대로 복원된다. 현재 해결 중인 응답은 요청 당시 이벤트가 활성이라 null이라는 기존 계약을 따른다.
- 일반/시크릿의 5/8 스탯과 속마음 기능 정의를 유지한다. 시크릿 일반 콘텐츠 지침이나 유료 속마음 해금 기능을 전역적으로 끄지 않는다.
- V2의 scene.inner_thought에는 이 V1 조건이 적용되지 않는다.
- 이 충돌은 ca27320에도 존재했다. 관측된 1건을 새 P1 때문에 생긴 회귀로 단정하지 않는다.
- 파서와 저장 단계가 이벤트 속마음을 강제로 null 처리하는 변경은 없다. 생성 모델이 여전히 계약을 어길 가능성은 남는다.

## 실행 검증 대조

현재 `build/test-results/test` XML을 직접 읽었다.

| 검사 | 실행 수 | failures/errors/skipped |
|---|---:|---|
| CharacterPromptAssemblerContractTest | 23 | 0 / 0 / 0 |
| StoryDirectorPromptAssemblerContractTest | 8 | 0 / 0 / 0 |
| 합계 | 31 | 0 / 0 / 0 |

XML timestamp는 각각 `2026-09-22T14:00:04.248Z`, `2026-09-22T14:00:11.784Z`다. 역사적 9/18 검사 결과를 재사용한 것이 아니다.

기존 23건에 추가된 8건은 다음을 검사한다.

1. 이벤트 true/false × normal/secret 4조건에서 속마음 블록과 출력 계약의 정합 및 유효 JSON 예시.
2. 비이벤트 director constraint 2조건에서 정상 속마음 유지.
3. 실제 이벤트 종료 후 normal/secret 프롬프트 전체 복원 2조건.

새 검사는 가드 없이 이벤트 조건 2건에서 일반 속마음 블록이 남아 실패할 수 있는 회귀 검사이며, 단순 예시 JSON 파싱만 반복한 검사가 아니다. 기존 모드/이벤트/시크릿 스탯·화자·enum·설정·static 캐시 계약 검사도 그대로 남는다.

root는 prepareBakeoff, 13후보 smoke, bootJar 성공을 보고했다. 이 검토자는 이를 별도 재실행하지 않았다. 다만 현재 `build/libs/aichat-0.0.1-SNAPSHOT.jar`를 ZIP으로 열어 1,187개 entry 중 bakeoff/Mockito/JUnit/계약 테스트/역사 프롬프트 자원에 해당하는 항목이 없음을 독립 확인했다. `git diff --check`도 통과했다.

## 동결 시험과 최종 소스의 연결

현재 runtime source hash는 `114c7ce08bcc85313552f0e8a8478acf9448e828c8736aec984ba133195a6ece`다.

[final-message-equivalence.json](final-message-equivalence.json)의 64개 관측을 집계해 header와 분해합을 대조했다.

- 64개 중 비이벤트 62개: oldPromptHash와 newPromptHash가 전부 같다.
- 이벤트 2개(X04-r1, X04-r2): 각각 일반 속마음 블록 2,955문자만 제거됐다는 기록.
- 비이벤트 hash 차이 0, 누락 관측 0.

이 재조립은 root 실행 자료이고 본 검토자는 64개 Java 조립을 또 실행하지 않았다. 독립적으로 확인한 생산 소스의 한 조건 차이 및 runtime hash 일치와 부합한다. 따라서 최종 가드 때문에 앞서 수집한 비이벤트 메시지의 실험 대상을 새 후보로 바꾸었다고 볼 근거가 없다. 수정된 이벤트 결과는 새 해시의 별도 재검증과 구분해 보존해야 한다.

## 남는 불확실성과 완료 표현

- API 표본이 작아 사용자 행동 선취, 사실 일관성, 대사 품질을 모든 캐릭터·긴 대화에 일반화할 수 없다. 익명 평가에서도 일부 잔존 오류를 기록했다.
- 이번 fixture는 공식 시드와 고정 상태를 사용한다. 실제 DB, 전체 FE/SSE 연결, 인가·나이 검증, 서버 상태/기억의 장기 진전은 시험하지 않았다.
- 시크릿 경로는 비노골적 일상 대화의 모드/스탯 계약 확인이다. 콘텐츠 허용 범위나 성적 표현 품질을 검증한 것이 아니다.
- 복장 충돌·비표준 괄호 표기 사례는 한계가 명시된 stress case다. 실제 서버 복장 변경이나 모든 괄호 문법 지원을 성공시켰다는 표현은 피한다.
- 최종 코드는 여전히 로컬 미커밋이며 이 검토는 커밋/푸시/배포 완료를 의미하지 않는다.

좁힌 이번 범위의 코드 검토를 완료로 판정할 수 있다. 모델 무오류·대사 대폭 향상·배포까지 완료한 것으로 확장해서는 안 된다. 후속 데이터 전달/기억 개선은 별도 과제로 관리해도 된다.