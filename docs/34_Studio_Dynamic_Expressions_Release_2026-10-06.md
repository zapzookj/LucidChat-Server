# 스튜디오 동적 바리에이션·감정 편집 워크플로 릴리스

관측일·기록일: 2026-10-06. 최종 사용자 결정은 이 문서가 [마지막 비교 실험](33_Studio_Final_Base_Quality_Current_Angry_2026-10-06.md)의 잠정 권고보다 우선한다.

## 확정된 제품 결정

- 원화→neutral은 기존 Qwen 편집과 WF-2/FaceDetailer를 유지한다.
- neutral→감정 파생은 `openai/gpt-image-2.5/sunburst/edit`, 기본 Low, 사용자 리롤 High다. 자동 실패 재시도는 기존 요청의 quality를 유지한다.
- 얼굴 표정 강도를 제한하고 얼굴 비율·인상을 보존한다. 캐릭터와 감정에 맞는 기존 수준의 상체 동작은 허용한다. 자세 고정이나 모든 동작의 작은 제스처 제한은 적용하지 않는다.
- GPT 감정 출력에는 WF-2/FaceDetailer를 거치지 않는다. 최종 누끼 WF-3는 유지한다.
- 캐릭터 생성마다 neutral과 고유 파생 8–12종(총 9–13종)을 구성한다. 서버가 안정 ID를 부여하고 이름·선택 조건·의미 감정·이미지용 표정/자세를 작업과 캐릭터에 영속화한다. 같은 의미 감정에 서로 다른 고유 연출이 있을 수 있다.
- 사용자는 이 구현의 커밋·푸시·프로덕션 배포와 이후 TTS 작업 진행을 명시 승인했다.

## 구현·호환성

V38은 캐릭터와 생성 작업에 nullable catalog, 생성 작업에 nullable pipeline version을 추가한다. 새 작업은 생성 트랜잭션에서 version 2를 확정한다. 기존 캐릭터와 진행 중인 기존 작업의 null version/catalog는 고정 15종·기존 Qwen 공정을 유지하며 기존 자산을 재생성하지 않는다.

새 catalog는 감정 단계에서 한 번 확정한다. 완료는 catalog의 ID 집합과 실제 자산 집합의 정확한 일치 및 모든 자산의 완료 상태로 판단한다. 알 수 없는 ID, 중복 ID, 수량 경계 위반은 거부한다. 고유 ID와 의미 감정을 분리해 LLM이 같은 캐릭터의 허용 목록에서 `expression_id`를 선택한다. 서버가 해당 화자 캐릭터의 자산 URL을 결정하며 알 수 없는 ID는 해당 캐릭터 neutral로 돌아간다.

자유/스토리 V1·V2, 극장 생성·종료·이력, SSE 최초/최종 씬, 로그의 scenes JSON과 화면 재생에 고유 ID/URL을 연결했다. 스튜디오의 기존 생성/리뷰 카드와 관리자 심사는 실제 catalog의 순서·이름·개수를 사용한다. 큰 화면 구조 변경은 없다. 구 화면 캐시와 의미 감정 전용 경로를 위해 기존 enum 파일명도 이미 생성된 누끼 자산을 복사해 제공하며 추가 이미지 생성은 없다.

기존 생성 에너지 총 20E와 단계 분할(6/4/8/2E), 사용자 감정 리롤 2E, 서버 실패의 무료 재시도 정책은 유지한다. catalog 개수에 따라 에너지 가격을 바꾸지 않았다. 공급자 실제 비용은 생성 개수와 실패율에 따라 달라지며 실청구 집계는 별도다.

## 요청 복구·과금 경계

- fal POST 전에 슬롯별 제출 예약을 저장하고, 접수되면 request receipt를 저장한 뒤 비동기로 같은 요청을 조회한다. 네트워크/폴링 타임아웃은 receipt를 보존해 재개한다. 접수가 불명확한 POST를 자동 재접수하지 않는다.
- receipt와 현재 슬롯 상태를 확인한 결과만 반영한다. 이전 리롤의 늦은 결과나 알 수 없는 슬롯은 반영하지 않는다. 중복 리롤은 잠금 아래 한 번만 차감한다. 리롤 진행 중 버전 선택을 막는다.
- WF-3도 제출 예약과 RunPod request ID를 비교한다. 소실된 요청은 실패 예산을 먼저 소비하고 재개하며, 오래된 접수 불명 예약은 자동 재접수 대신 실패 정산한다. 늦게 접수된 정상 요청을 오래된 예약으로 오판하지 않도록 잠금 아래 재확인한다.
- catalog 확정 실패는 전체 실패·기존 환불 경로로 처리한다. 동시 worker에서 이미 catalog가 확정됐다면 뒤늦은 실패로 작업을 되돌리지 않는다.

## 실행한 검증

- `./gradlew clean test --tests '*Test' bootJar --no-daemon` 성공. 로컬 40클래스/286검사, 실패·오류·건너뜀 0. 이 중 별도 미커밋 디오라마 검사 10건은 이번 릴리스에 포함하지 않는다. 릴리스 대상 검사는 276건이며 새 경계 검사는 22건이다.
- 새 검사: catalog count/중복/정확 집합, 레거시 파싱과 화자 소유권, 실제 공급자 입력 계약·receipt URL, Low 최초·Low 자동 재시도·High 리롤/자동 재시도, accepted receipt 재개/ambiguous POST 차단, 이전 결과 무시, 중복 차감, WF-3 제출 중 재진입·lost request 예산·오래된 예약·늦은 정상 접수.
- Astra `npm run build`, `npm run check:tdz`(135파일), `npm run check:entry`(69검사), 관리자 `npm run build`, 변경 소스 `git diff --check` 성공.
- 별도 컨텍스트에서 이미지/과금/복구 경계와 채팅·프런트 연결을 각각 독립 검토했다. 지적된 receipt·High 보존·정확 집합·cutout race·speaker null·이력 복원 문제를 수리하고 재검증했다. 최종 읽기 검토의 확인된 배포 차단 이슈는 없다.

실제 DB 다중 인스턴스 락 경쟁, 새 동적 캐릭터의 전체 유료 생성/누끼/심사/게시 시연, 운영 채팅에서 고유 바리에이션의 LLM 선택 품질은 아직 미실행이다. 단위 검사와 배포 health를 이러한 종단 검증으로 간주하지 않는다. 기존 V1 채팅을 V2 화면에서 재생할 때의 SYSTEM role 분류는 이전 코드 동작이며 이번에는 expression 필드 전달만 보존했다.

## 배포 기록

배포 전 운영 RunPod 관측: 최소 0·최대 0, 실제 worker 0, 대기/진행 작업 0. Neutral 생성과 WF-3 누끼가 작동할 수 있도록 최소 0·최대 1로 복구한다. 이는 요청 시 worker가 뜨는 설정이며 상시 최소 worker를 두지 않는다. 최대 0으로 변경된 과거 경위는 확인되지 않았다.

### 운영 반영 완료

- BE `48eef00f1b5e9f3334622e9c3c8ac9ce18a5d7a0`, FE `e24a97e1aae176392c2157d5370fd3aba58f7979`, Admin `321a5d446235990b3d066d13f3d47b4a8bafe90c`를 각각 master에 커밋·푸시했다. 디오라마·UIUX 연구의 별도 미커밋 변경은 제외했다.
- [Actions 37420830732](https://github.com/zapzookj/LucidChat-Server/actions/runs/37420830732) 전체 단계 성공. 실제 운영 이미지의 RepoDigest와 해당 커밋 태그의 registry manifest digest가 모두 `sha256:391e2415254e4abbf7f6acdc05f0c965ce1ca12addc3401b63edd543cd4448cb`로 일치했다. 서버는 latest 태그만 pull하므로 커밋 태그가 로컬에 없다는 이유로 배포 실패로 판단하면 안 된다. 현재 Docker의 `.Image`는 manifest digest와 일치하므로 config digest 대신 RepoDigest로 대조했다.
- Flyway 최신 `38|character expression catalog|success`, 새 컬럼 3개 nullable 확인. 기존 캐릭터 26개 catalog null, 기존 작업 22개 pipeline version null로 유지됨을 읽기 전용으로 확인했다. 배포 전 V37 DB dump 423,839bytes는 서버 접근 제한 백업 디렉터리에 보존했다.
- 컨테이너 `/health`와 공개 `https://api.lucid-chat.com/health` 모두 OK. fal key·RunPod key/endpoint는 값 노출 없이 설정 존재만 확인했다.
- [FE Vercel Production](https://vercel.com/zapzookjs-projects/lucid-chat-front/Csve6WJcNwjs2xAo5FKm9qXJALU7) 성공, GitHub deployment `6876785122` SHA 일치. [관리자 Vercel Production](https://vercel.com/zapzookjs-projects/lucid-chat-admin/6MiFwfUiSGrVkBSm72QThpi2ckSd) 성공, deployment `6876787568` SHA 일치.
- 운영 FE `/`, `/story`, `/login`, `/studio`, 새 JS `/assets/index-wvrA6K3C.js` HTTP 200. 운영 관리자 `/`와 JS `/assets/index-Sa9Cvesl.js` HTTP 200. 배포 번들에서 새 catalog/표시 필드를 확인했고 운영 브라우저에서 로비·스튜디오 로그인 진입을 확인했다. 로그인 후 생성 시연은 미실행이다.
- RunPod 최소 0·최대 1 적용, 다른 설정 변경 없음. 대기·진행 작업 0인 관측 시점에 실제 worker 0은 정상적인 요청 대기 상태다. 최대 0으로 된 과거 변경 경위는 미확인이다.

관측은 2026-10-06 05:55–05:58 UTC(한국 14:55–14:58) 배포 조회에 근거한다. 유료 이미지 생성·대화 추가 호출 없이 검증했다. 이 배포 이후 TTS 계약·UI 검토/구현으로 넘어간다.
