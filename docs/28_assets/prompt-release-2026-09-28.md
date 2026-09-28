# 프롬프트 정합 수리와 Gemini 3 Flash 복귀 릴리스

기록일: 2026-09-28. 사용자가 이번 작업의 커밋·푸시·프로드 배포를 명시 승인했다. 기본 모델은 `google/gemini-3-flash-preview`다. [9/22 P1 확인](p1-acceptance-2026-09-22.md)의 미배포 상태 이후 기록이며, 해당 실험의 표본·한계는 그대로 유지한다.

## 범위

- 서비스의 두 assembler에 외형·행동 해석·스토리 배경 중복·사용자 내면·출력 예시·이벤트 속마음 충돌 수리를 반영한다.
- 비교실 코드·계약 검사·실험 보고서를 버전 관리한다. 비교실과 테스트 의존성/역사 프롬프트는 생산 JAR에 포함하지 않는다. 로컬 키·대화 원문·과금 원장은 Git 제외 상태로 보존한다.
- 9/24 로컬 3.8 변경을 3 Flash로 복귀했다. 7개 기존 설정/시드/Java/검사 파일은 ca27320의 모델 상태와 같다. Pro·명시 Stage0 모델·가격·라우팅 정책은 유지한다.
- 디오라마 소스·검사, docs/23 UI 자료 및 관련 미커밋 변경은 이번 커밋과 배포에서 제외한다. 별도 checkout에서 실제 배포 파일만으로 빌드·검사한다.

## 배포 전 운영 관측

- 원격 master와 로컬 기반은 `ca27320c1b788fe8dd88edb320cfcc2ce735aa89`, 마지막 성공 배포는 [Actions 34507413222](https://github.com/zapzookj/LucidChat-Server/actions/runs/34507413222).
- Vultr 실행 이미지 `sha256:dd44603df12124d0a795f3f2178abfac8a07d64975f6c397dfd68dcc1cb95810`, 컨테이너 running, 내부 `/health` 응답 OK.
- Flyway 최신 V37 성공, V38 없음. `characters` 모델 집계는 `google/gemini-3-flash-preview` 26행이며 다른 모델은 없었다.
- 전역/보조/Pro/VLM/씬 디렉터/캐릭터·월드 Stage0의 해당 모델 환경변수 override는 없었다. 키·사용자 대화·개인정보는 조회하지 않았다.
- 미적용 `V38__upgrade_gemini_flash_model.sql`과 전용 migration 검사를 활성 소스에서 제거하고 로컬 build 백업에 보존했다. **이번 릴리스에는 새 DB 마이그레이션이 없다.**

## 검증·배포 결과

격리 checkout에서 `test --tests '*Test'` **36클래스/254검사**, Node 비교실 **107검사**, `prepareBakeoff`·13후보 `bakeoffSmoke`·`bootJar`가 성공했다. Java 249개 기존 검사에 실제 YAML·16개 시드·극장/Boost/VLM 선택을 다루는 새 모델 계약 검사 5개를 합친 결과다. 외부 DB를 요구하는 전체 앱 컨텍스트 기동 검사는 CI와 동일하게 제외했다.

독립 코드 검토에서 두 assembler가 9/22 최종 P1과 LF 정규화 기준으로 동일함을 확인했다. 전체 sourceHash는 현재 파일 집합/줄바꿈에 따라 과거 실험과 다르며, 이를 과거 실험 전체 소스의 동일성으로 확대하지 않는다. 생산 JAR 1,184개 항목에는 bakeoff·Mockito·JUnit·spring-test·역사 프롬프트·V38이 없고, 기본/보조/VLM 및 일반 10+시크릿 6개 시드는 3 Flash다. 로컬 JAR SHA-256은 `75ba0cfbdfddb4dcb9d1829d0578a43b334b394b20728b7fac0c485002191f7d`다. CI에서는 같은 커밋으로 다시 빌드한다.

커밋 전 비밀값 검사에서 미추적 `.env.example`의 채워진 API 키를 발견해 빈값으로 교체했다. 실제 `.env.local`과 원장은 보존·Git 제외한다. 키가 들어 있던 예제 파일은 커밋하거나 푸시하지 않았다. 배포 소스의 whitespace 검사는 통과하며, 보존된 과거 patch 2개와 실험용 PromptRoundThree text block의 공백 경고는 원문 유지 항목으로 구분한다.

실제 커밋·Actions·운영 확인 결과는 배포 후 아래에 덧붙인다.
