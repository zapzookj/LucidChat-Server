# 첫 씬 보이스·이벤트 설명 수정 배포와 UGC 큐 분석

관측·기록일: 2026-10-07, 운영 조회 19:51–20:06 KST. 출처: 사용자 운영 재현 보고와 1·2번 즉시 배포/3번 상세 분석 요청, FE master diff·로컬 실제 MP3 fixture, 운영 DB 읽기, RunPod REST/health/workers/status/logs 읽기, Docker Registry manifest/config, 최신 RunPod 공식 문서, 독립 컨텍스트 읽기 검토. 아래 UGC 분석에서 운영 설정·요청·데이터를 변경하지 않았다.

## 1·2번 수정과 배포

FE `90c9dbef283e2b007832720c741132b3b0ca2625`을 master에 푸시하고 해당 SHA의 Vercel Production success를 확인했다. 실제 운영 `/assets/index-O1dihXO-.js`가 운영 공개 환경으로 빌드한 전체 내용과 일치한다(Windows template literal의 escaped CR+LF55곳 정규화, raw hash 일치와 구별). BE 코드/이미지·DB migration/env 변경은 없다. [배포 상태](40_assets/deployment.json), [전체 번들 비교](40_assets/frontend-bundle.json).

첫 씬의 기존6초 gate가 `first_scene`부터 시작되어, 전체 응답/저장 ID를 기다리는 시간과 음성 생성 시간을 함께 소진했다. 운영 READY 행의 생성→완료 시간은5.715·14.054·11.375·15.888·10.214초였다. 이보다 짧은 deadline이 텍스트 폴백과 자동재생 차단을 의도대로 발동시켜 사용자 보고의 ‘첫 씬 미재생·두 번째 씬은 준비된 음성 재생’을 설명한다. 이 수치는 서버 저장 음성 작업 전체 시간이며 공급자 합성만의 지연이 아니다. 이전 검증은2초 fixture로 이 정상 운영 지연을 놓쳤다.

- V1/V2에서 `awaitingFinalResult`를 전달한다. 저장된 response key 전에는 기존 SSE 요청 deadline을 따르고, key 도착 이후부터 음성 준비 최대30초를 기다린다. 실패/미지원/모드OFF/hidden/삭제/비활성 및 무ID로 응답 종료는 폴백하며, 한 번 폴백한 씬은 늦게 재숨기거나 autoplay하지 않는다.
- 실제 `Audio.play()` 성공 후에만 청취 사실을 기록한다. READY만으로 ‘다시 듣기’를 표시하지 않고, 미청취 음성은 ‘듣기’다. 과금/서버 생성·환불 정책은 변경하지 않았다.
- ‘다음 씬’ 이벤트 설명은 body portal에 fixed 배치하여 `.aurora-dialogue-panel`의 `overflow-y:auto`를 벗어난다. 화면 가장자리 보정, hover/focus, Escape, 실제 버튼 `aria-describedby`를 적용했다. 설명 문구·버튼 기능을 유지했다.

검증: FE build·TDZ142·entry69·TTS 식별16·음성 캐시/BGM 동작 검사 통과. 실제 첫인사 MP3 fixture의 응답7초+음성14초에서 저장 ID7001ms, 재생21543ms, 첫 글자21581ms(38ms차이). 생성32초 fixture는 ID704ms→첫 글자30771ms로30초 이후 텍스트 폴백, READY가 뒤에 도착해도 첫 씬 autoplay 없음·버튼‘듣기’. 이는 로컬 재현이며 유료 공급자 추가 호출/운영 과금 E2E가 아니다.

Tooltip은1280×720/390×844에서 body 자식·viewport 내 표시를 확인했고 실제 버튼 설명 연결과 Escape 해제를 검사했다. 독립 검토의 접근성 지적을 반영 후 재확인했다. TTS 권한·과금·데이터 경계 회귀 blocker는 발견되지 않았다. [데스크톱](40_assets/tooltip-desktop.png), [390px](40_assets/tooltip-mobile.png).

## UGC 실제 상태와 장애 범위

| 항목 | UGC | 실시간 씬 |
|---|---|---|
| min/max | 0/1 | 0/2 |
| 첫 조회 queue/progress | 1/0 | 0/0 |
| 첫 조회 health ready | 0 | 1 |
| 실제 worker 목록 | 0 | 6개 기록(동시 처리 슬롯 수와 구별) |
| FlashBoot / queue delay | true / 4초 | true / 4초 |
| GPU/CUDA | 8종 fallback·최소12.6 | 같은8종 집합·최소12.6, 우선순위 다름 |
| network volume | 없음 | 있음 |
| image / 압축 layer 합 | comfy-worker:0.3.0 /22.645GiB | diorama-worker:0.2.0 /22.833GiB |
| workersStandby 관측값 | 0 | 2 |

`workersStandby`는 이번 REST 출력의 관측 필드이며 공개 설정 계약/정확한 의미를 확인하지 못했다. 직접 수정할 설정이나 상시 유료 워커 수로 단정하지 않는다. [초기](40_assets/runpod-initial.json), [마지막](40_assets/runpod-final.json), [제약](40_assets/constraints.json), [템플릿](40_assets/templates.json), [registry 압축 용량](40_assets/images.json).

UGC는14분 간격 재조회에서도 completed1243/failed9/retried2·queue1·실제worker0으로 그대로였다. 현재 `max0` 또는7일 무요청 자동 축소가 원인은 아니다. 초기화중/실행중 워커도 없어 **실행 전 할당·기동이 정체된 상태**까지는 확인했다. scheduler/GPU할당/image pull/container 생성 중 정확한 세부 원인은 현재 워커 로그가 없어 미확인이다. RunPod 콘솔은 signup/login 화면으로 연결되어90일 endpoint 이력도 읽지 못했다. [공식 로그 보존/접근 계약](https://docs.runpod.io/serverless/development/logs).

이전 [docs/37](37_TTS_Production_Rollout_RunPod_Queue_2026-10-06.md)에서는 임시min1 할당 뒤 container 생성 `context deadline exceeded`, 이후 원래 요청 완료(대기83분·실행34초)를 관측했다. 동일 계열의 기동 문제를 뒷받침하지만 이번 내부 원인을 증명하지 않는다. 양쪽 이미지 크기가 비슷하고 모델은 worker Dockerfile에서 image에 COPY되므로 ‘UGC만 이미지가 커서’, ‘volume이 없어 매번 모델을 다운로드해서’라고 단정할 수 없다. 압축 용량만으로 containerDisk40GB 부족도 확정하지 못했다.

마지막 조회 중 씬에도 queue1·raw worker THROTTLED/UNHEALTHY가 있었으나, 로그에서 새4090 워커가20:05:34에 시작해20:06:01에1장 작업 완료했다. 씬은 실제 처리 가능한 워커가 존재하며, raw health/목록은 시점·범위가 달라 총 기록 수를 최대 동시 워커로 읽지 않는다. [정제한 씬 기동/완료 로그](40_assets/scene-worker-logs.json). 새 씬 요청을 분석자가 제출한 것이 아니라 기존 운영 요청을 읽은 것이다.

## 별도로 확인한 앱 결함

현재 queue1은 앱 job24의 GOLDEN 원래 요청이다. 앱 생성00:33:16 KST, 사용자가00:41:59에 중도포기해 FAILED가 되었지만20:03 조회에서도 provider `IN_QUEUE`였다(약19시간30분). 서버와 DB의 timezone은UTC임을 읽기로 대조했다. **새 활성 빌드가 계속 처리중인 것과 종료된 빌드의 큐 잔존은 구별해야 한다.** [안전한 앱/원래 요청 대조](40_assets/job-status.json).

소스에서 `CharacterCreationService.abandon`은 앱 상태만 실패 처리하고, `UgcComfyClient`에 cancel 메서드/호출이 없다. FAILED 잡은 폴러/스테일 스윕 대상에서 빠진다. `failAndRefund`도 외부 요청을 취소하지 않는다. 기본90분 hardStale 재시도는 아직 IN_QUEUE인 ID도 scratch에서 제거하고 합성FAILED를 주입해 재접수할 수 있어, 이전 요청이 나중에 실행될 때 중복 GPU 비용 가능성이 있다. 이번 job24는8분43초에 사용자가 포기했으므로90분 재시도가 실제 발동한 사례는 아니다.

현재 제출 body에는 per-request `policy.ttl`이 없다. `executionTimeoutMs=300000`은 실행 시간 제한이므로 장기 큐 대기를 제한하지 않는다. 공급자 기본 TTL24시간과 완료 결과 보존30분도 구분한다. job23의404는 앞서 완료했던 요청의 결과 보존 종료와 부합하며 새 실패 증거로 취급하지 않는다. [공식 timeout/TTL/retention 계약](https://docs.runpod.io/serverless/endpoints/endpoint-configurations#job-ttl-time-to-live).

## 권고 조치와 미실행

1. 종료된 job24의 원래 외부 ID를 대조해 취소·최종 상태 확인한다. 재제출하거나 전체 queue를 purge하지 않는다.
2. 앱의 포기/실패/만료/하드 재시도에 provider cancel/receipt 정리를 연결한다. 아직 살아 있는 IN_QUEUE를 ‘소실’로 판단해 ID만 버리는 경로를 고친다. 시스템 귀책 실패 환불과 기존 사용자 중도포기 무환불 정책은 별도로 유지하며, 늦은 완료/취소 실패/중복 webhook 경계를 검증해야 한다.
3. 큐 대기 상한과 provider TTL을 stage별 전체 예상 시간에 맞춰 정하고, 상태 화면에 생성 연산과 워커 준비 대기를 구분한다. 지금30분 stale/90분 hard retry 조합은 인터랙티브 빌더의 장애 안내로 너무 느리다. 적정 값은 새 기동 측정으로 확정한다.
4. RunPod endpoint scheduler/할당 이력을 확보한다. 이어 비용 상한을 둔 임시min1 단일 기동에서 할당→pull→container→handler 준비 각 시각을 남기고 원래min0으로 복원한다. 먼저 종료 요청을 정리해야 이 진단이 포기한 빌드의 불필요한 실행을 일으키지 않는다. 과거의 임시 변경으로 복구됐다는 사실만으로 영구 해결이라고 보지 않는다.
5. 기동 병목이 확인되면 이미지 레이어 경량화·cached model/volume·GPU 우선순위 등을 해당 병목에 맞춰 비교한다. Network volume은DC를 제한하므로 즉시 부착이 정답은 아니다. FlashBoot는 상태 복원 최적화로, 무조건 빠른 최초 기동 보장은 아니다. [공식 최적화](https://docs.runpod.io/serverless/development/optimization), [endpoint 설정](https://docs.runpod.io/serverless/endpoints/endpoint-configurations).

상시min1은콜드스타트를 줄이지만24시간 유료다. 현재 공개16GB군$0.58/h를30일720h로 단순 환산하면약$417.60+storage(활성 워커 계약할인/실제 계정 요율 미확인)다. [공개 요금](https://www.runpod.io/pricing), [과금 설명](https://docs.runpod.io/serverless/pricing). 이미지 pull/cached model 다운로드는 worker 시작 전 GPU미과금이며 전체 대기를 GPU청구로 계산하지 않는다. 이번3번 요청은 상세 분석이므로 상시 워커·volume·queue 요청·환불·RunPod 설정을 변경하지 않았다.

독립 읽기 검토도 max0 배제, 세부 공급자 원인 미확정, 이미지/volume 원인 단정 금지, 종결·하드재시도의 외부 취소 누락을 대조했다. 정본·증빙에는 키/환경 값/원본 프롬프트·대화·사용자ID를 넣지 않았다. 별도 UIUX23/디오라마/실험 도구 미커밋 변경은 보존했다.
