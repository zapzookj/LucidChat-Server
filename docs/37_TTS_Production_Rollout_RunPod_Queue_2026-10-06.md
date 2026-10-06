# TTS 운영 배포·RunPod 큐 복구 — 2026-10-06

관측일·기록일 2026-10-06. 사용자 “푸시하고 프로드에 배포해라”와 UGC 첫 요청 큐 정체·씬 서버리스 점검 요청을 근거로 집행했다. TTS 구현 정본은 [docs/35](35_TTS_Implementation_Contract_2026-10-06.md), 외형 교정은 [docs/36](36_Official_Appearance_Tags_Correction_2026-10-06.md), 승인 기록은 [§L 운영 배포](19_assets/decisions_confirmed.md#2026-10-06-tts-운영-배포-승인서버리스-점검)이다.

## 실제 배포와 활성화

- BE master `50b2aaccb59b229bfe12c7334a2cbf7936674586`(TTS 초기 `abf5016` 포함) 푸시. [Actions 37439279972](https://github.com/zapzookj/LucidChat-Server/actions/runs/37439279972) 성공. CI가 정확한 Git checkout에서 `clean test --tests '*Test' bootJar` 후 이미지를 빌드하고 Vultr에 배포했다. 별도 디오라마 미커밋 소스를 포함하는 로컬 JAR는 배포하지 않았다.
- FE master `98fa3a21b159a3cbb77be8f3464382dadfab9d11` 푸시. GitHub Production deployment `6879907762`·요청 SHA·최신 상태 success를 대조했다. [Vercel 배포](https://vercel.com/zapzookjs-projects/lucid-chat-front/5XKDHGWXHTofwVmeJnT1gWzSA2LP) 성공. 실제 [운영 도메인](https://lucid-chat.com/) HTTP200 및 새 `index-CnKXujoI.js`의 TTS API/자동 보이스 문구를 확인했다. 브라우저 공개 로비·캐릭터/세계관 목록도 로딩됐다. 로그인한 채팅의 TTS 버튼 실동작과는 구별한다.
- 서버 비밀 `.env`에 `TTS_ENABLED=true`, `TTS_MODEL=eleven_v4`, `TTS_ENERGY_COST=1`, formatter 기본값과 API 키·공식10 voice·별도 private R2 키/버킷/endpoint를 적용했다. 파일 기반 override 두 값은 공란이다. 컨테이너 런타임 **21개 설정 전부 기대값 일치**를 값 노출 없이 검사했다. compose의 기존 env_file을 사용하고 기타32줄은 보존했다.
- 실행 이미지 RepoDigest가 commit tag의 registry manifest digest `sha256:7185a97d7dbeadda9fe010ad834317cd5aad6392700a37704cd272cc409bdfc4`와 일치한다. 외부 `/health` HTTP200/OK. Docker healthcheck 자체는 설정되지 않아 health 필드가 null이며 서비스 readiness와 혼동하지 않는다. [BE 검증](37_assets/backend-verification.json), [FE 검증](37_assets/frontend-verification.json), [실제 번들](37_assets/frontend-bundle.json).

## 데이터·오디오 경계 검증

배포 전 `/opt/lucid/backups/tts-20261006T085247Z`에 env/compose와 PostgreSQL 전체 dump 1,130,837바이트를 보존했다. 디렉터리700·비밀/덤프600이다. [독립 권한 재조회](37_assets/backup-permissions.json). 운영은 V38→**V39**로 올라갔다.

- 기존 캐릭터26행의 외형26/26·다른 컬럼 해시26/26 일치. 최신 시더 재기동 뒤에도 앞서 교정한9종과 연화/나머지17행이 유지됐다.
- 기존 방46개 모두 존재하며 자동 TTS OFF/승인 가격 null. 레거시 생성 잡22개 모두 기존 null pipeline/catalog 유지. 배포 직후 `tts_response_audio`0행. 이후 정상 사용으로 바뀔 수 있는 실시간 값이다.
- 운영 공개 firstGreeting API **10/10 HTTP200**, 응답 바이트 SHA가 사용자 선정 MP3와 전부 일치. `audio/mpeg`, `no-store`, `nosniff` 확인. 미등록 slug404, 익명 방/응답/clip 경로401. [오디오 검증](37_assets/greetings-verification.json).
- 같은 private 키로 기존 객체와 별개인 opaque canary를 PUT→GET 원본 대조→DELETE→HEAD404까지 확인하고 제거했다. [저장소 삭제 경계](37_assets/storage-canary.json). 사용자 선정 파일·이전 유료 영수증/복구 객체는 건드리지 않았다.
- BE 로컬 최종 검증46클래스/318검사(별도 디오라마10 제외 릴리스308), TTS29·공유 JSON 개인정보3, FE 기존 build/TDZ139/진입69/씬 식별12가 통과했다. 이번 배포 CI의 테스트·빌드도 성공했다. 관련 소스 변화 없는 검사를 추가 반복하지 않았다.

독립 컨텍스트 검토에서 운영 helper의 임시 env 파일을 **비밀 쓰기 전에600으로 생성**하도록 하고, UGC 최소 워커 복원값을 원본 snapshot에서 읽도록 보완했다. 실제 적용 파일도 최종600이다. FE 조회는 요청 SHA의 Production success를 명시적으로 검사하도록 보완하고 실제 운영 번들을 추가 대조했다. 현재 성공 실측은 허용하고, 실제 조회한 이전 SHA 배포를 현재 대상의 성공으로 오인하는 입력은 거부하는 것도 확인했다. 검토자는 비밀값 조회·운영 쓰기·검사 재실행을 하지 않았다.

미실행: 로그인한 계정의 운영 유료 자동/수동 생성·정산·환불·삭제 E2E, 다른 소유자의 실제 READY clip 접근, Turbo 품질/실청구, Cloudflare 제어판의 모든 공개 도메인 설정. 과금·소유권·취소 경계는 로컬 회귀검사와 독립 소스 검토를 통과했으나 운영에서 유료 음성을 추가 합성하지 않았다. r2.dev/커스텀 도메인 비활성은 앞서 사용자에게 요청한 조건이며 익명 S3 검사와 구별한다.

## UGC 큐 정체와 복구

초기 17:49 KST(08:49 UTC) 관측에서 UGC는 **min0/max1**, 큐2·진행0·실제 워커0이었다. 씬은 **min0/max2**, 큐0·ready1로 `max=0` 장애가 없었다. 이 최초 health는 세션 도구 출력에서 옮긴 관측이며 독립 원시 health 파일은 남지 않았다. min/max는 보존한 원본 endpoint GET과도 대조했다. 전후 JSON의 `initial`은 이를 명시하고, 별도 파일이 보존된17:55 이후 관측은 `afterAllocation`/`after`로 구분한다. 두 endpoint의 과거 max0 상태를 오늘의 원인으로 그대로 가져오지 않았다.

17:54경 UGC workersMin만 잠시1로 올려 할당을 유도했다. 최대1·GPU/template/volume/scaler/timeout은 보존했다. INITIALIZING→RUNNING 관측 뒤 17:58경 min0으로 복원했다. **RUNNING 표시만으로 handler 처리 성공이라고 판정하지 않았다.** 워커 로그에는 이미지 pull과 17:59:46의 `error creating container ... context deadline exceeded`가 있었고 당시 container 로그는 없었다. 이후 공급자 재시도로 container/ComfyUI가 기동해 요청을 처리했다. 임의 요청 재제출·취소·새 유료 테스트는 하지 않았다.

- 18:05 KST UGC 큐 **2→0**, 누적 completed **1241→1243**, failed9 유지.
- 앱 생성 잡23은 `CONCEPT_PROCESSING`→**GACHA_WAIT**, 후보 이미지2개로 진행했다. 기존 scratch의 GOLDEN 요청을 별도 `/status`로 조회해 **COMPLETED**, 오류 없음·output 있음 확인. 공급자 기록상 대기 **5,001,859ms(83분21.859초)**, 실제 실행 **34,074ms(34.074초)**였다. 긴 지연의 주된 부분은 생성 연산보다 실행 전 대기였다.
- 최종 UGC **min0/max1**, 씬 **min0/max2·ready2·큐0**. 최소 워커를 상시1로 남기지 않았다. 두 endpoint의 raw worker 목록과 health 집계는 시점·범위가 달라 숫자가 일치하지 않을 수 있으며, 원시 워커 수를 최대 동시 처리 수로 단정하지 않았다.
- [전후 관측](37_assets/runpod-queue-recovery.json), [앱 상태](37_assets/ugc-job-status.json), [원래 요청 완료](37_assets/ugc-original-status.json).

이번에는 **최대 워커가 정상인데 실제 워커0인 큐 정체와, 기동 중 일시적인 컨테이너 생성 타임아웃**을 관측했다. 최소 워커 변경 후 기존 요청이 완료됐다는 복구 증거는 있으나, 최초83분 정체 전 구간의 공급자 scheduler/audit 이력이 없어 전체 원인을 확정하거나 재발 방지를 완료했다고 표현하지 않는다. 모델/워크플로 오류·영구적인 GPU 변경 필요성은 확인되지 않았다.

## 무요청 자동 축소 정책과 운영 참고

[RunPod 현재 공식 문서](https://docs.runpod.io/serverless/endpoints/endpoint-configurations#idle-endpoint-scale-down)는 무요청3일 뒤 max2, **7일 뒤 max0**로 자동 축소하며, 새 요청이 타이머를 초기화해도 축소된 최대값은 수동 복원해야 한다고 명시한다. 따라서 사용자 추정의2주가 현재 기준은 아니다. 과거 실험 시 max0의 원인으로 이 정책은 가능하지만 계정 audit/알림 이력을 확인하지 않아 확정하지 않는다. 오늘은 UGCmax1·씬max2로 관측됐다. 정기 keepalive·상시 최소 워커·자동 설정 복원 서비스는 이번 요청에서 추가하지 않았다.

재발 시 먼저 두 endpoint의 max/min·queue/inProgress·worker health를 읽고, 실제 worker 로그의 container 기동/handler 처리를 구분한다. max0이면 기존 비용 상한 내 max를 복원하고, max가 정상인데 큐가 멈췄다면 초기화/이미지 pull/container 타임아웃을 살펴본다. 유료 요청을 재제출하기 전에 원래 요청 상태와 앱의 재시도 상태를 대조한다.

TTS를 중단할 때는 `TTS_ENABLED=false`로 새 생성/자동 차감을 끄고 현재 진행 잡·환불·cleanup 상태를 먼저 확인한다. 이전 앱으로 롤백하면 TTS 정산/cleanup 스케줄러도 없어지므로 처리 중인 과금 잡을 그대로 두고 이전 이미지로 복귀하지 않는다. V39는 기존 방에 additive 변경이지만 실제 유료 잡이 생긴 이후의 롤백은 배포 직후0행 상태와 구별한다.
