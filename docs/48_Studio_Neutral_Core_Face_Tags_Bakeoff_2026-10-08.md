# Neutral FaceDetailer 핵심 태그 비교 — 2026-10-08

## 사용자 가설과 범위

사용자는 수작업 경험상 FaceDetailer wildcard가 다양하고 상세해지면 품질이 불안정해진다고 설명했다. 기본 복원 태그 하나만 남기는 것은 외형 보존 정보가 없어 지나치며, 핵심 외형·표정 2–4개를 남기는 쪽을 제안했다. 이번 강세라 예시는 `detailed beautiful eyes, sharp amber eyes, subtle smoky eye makeup, smug grin`이다. 태그 수에 따른 가중치 분산이 원인인지는 미확정이다. 태그 개수뿐 아니라 의미·중복·현재 얼굴과 지시의 충돌도 함께 변하므로 그 메커니즘을 단정하지 않는다.

앞선 [docs47](47_Studio_Neutral_Face_Hand_Restoration_2026-10-08.md)의 후보 구성/미배포 상태는 유지하고 추가 시각 비교만 한다. 원화 전체 프롬프트·LLM 모델/생성·WF-2 전역 sampler·Qwen·Hand·TTS·DB/에너지·운영 endpoint는 바꾸지 않는다. 샘플의 네 태그를 모든 캐릭터에 고정 적용하지 않는다.

## 실제 계약과 실험

설치본 Impact Pack8.28.3은 검출 얼굴 crop을 확대하고 프롬프트 기반 확산 보정을 한 뒤 mask로 합성한다. 단순 sharpen 필터가 아니다. 빈 wildcard는 입력 positive를 쓰고, 비어 있지 않은 일반 wildcard는 positive를 대체한다([고정 설치본 확인의 출처 docs47](47_Studio_Neutral_Face_Hand_Restoration_2026-10-08.md)). 현재 원화 WF-1 node17의 wildcard는 **빈 값**이고 전체 원화 positive12를 받는다. 따라서 원화 FaceDetailer가 기본 태그 하나만 받는다고 설명하면 잘못이다. Neutral 레거시는 별도 wildcard에 얼굴/무드/표정을 다시 조립하는 차이가 있다.

기존 저장 `legacyWildcard`는 쉼표 분리17개다. 앞선 독립 검토 대화의18개 표기를 정정하고 실제 문자열을 기준으로 삼았다. 입력은 job30 후보1에서 docs47 실행이 저장한 `beforeface.png`로 SHA를 원장과 대조했다. 새 Qwen/LLM/WF-2/Hand 실행은 없다.

한 번의 face-only graph에서 같은 image/model/clip/negative/seed260100802/steps20/CFG4/euler/simple/crop·mask 설정으로 네 조건을 실행한다.

| 조건 | 유효 wildcard | denoise |
|---|---|---|
| long | 저장 레거시17태그 그대로 | 0.4 |
| generic | detailed beautiful eyes | 0.4 |
| core | 사용자 네 태그 그대로 | 0.4 |
| corelow | 사용자 네 태그 그대로 | 0.2 |

Generic은 네 태그의 첫 태그와 어순을 맞췄다. 이전 docs47의 `beautiful detailed eyes`와 문자열이 다르므로 이전 generic의 정확 재현이라고 표현하지 않는다. Long/generic/core는 태그 구성 비교, core/corelow는 강도 비교다. 각각 LoadImage19를 직접 받는 별도 분기이므로 앞 조건의 출력을 다음 조건에 입력하지 않는다. 네 이미지와 core 검출 mask를 합쳐5PNG를 저장한다.

요청·전체 graph·입력 SHA와 1POST/5출력 슬롯을 동결했다. 정상1·wildcard/seed/denoise/input/image이름 변조5거부를 제출 전 실행했다. 접수 원장은 O_EXCL 생성하고 accepted ID가 있으면 GET만 재개한다. timeout/모호한 제출을 자동 재POST하지 않는다. 자원 생성 timeout은 unique name+image/template 연결로 읽기 복구하며 template-only/부분 삭제 정리도 지원한다.

## 실행 상태

기존 후보 image digest `sha256:6a9d54d6bdceb16cfc9d6b4c303867661c7efccc5fa6819acf17e10eb2d8e5f2`로 새 임시 endpoint/template를 생성했다. min1/max1·volume0·env빈값·운영 webhook없음, API 생성 결과와 제출 전 name/template/image를 대조했다. 후보 image 재빌드는 없다. 운영 UGC endpoint와 다른 ID를 확인했다. 운영 설정은 시작에 읽기 저장했고 종료 후 재대조한다. 키·provider ID·원본 payload는 Git 제외 `tools/tts/.local/neutral48`에만 보존한다.

**1POST/accepted1/DONE1/산출5PNG**로 완료했다. 실행37,689ms·provider queue833,059ms(13분53.059초)·원장 시작→완료875.447초(14분35.447초). 큐 대기600초 뒤에도 같은 접수 ID로 GET만 재개했다. raw PNG/RGB SHA와 gallery5복사본을 대조했으며, 입력1024²·출력5파일·네 condition·mask1에 누락/중복이 없다. 실제 GPU 청구액은 미확인이고 대기/초기화/min1 유지는 실행시간과 별도다.

큐 대기 중 임시 worker가 READY/IDLE로 잠시 관측된 뒤 UNHEALTHY/THROTTLED/INITIALIZING으로 돌아왔다. 일부 system log는 cached image `up to date` 이후 `start container ... begin` 반복과 container stop/remove를 보였으나 Comfy/handler의 구체 오류는 확보되지 않았다. 임시 endpoint만 FlashBoot true→false로 변경해 새 컨테이너 기동을 유도했다. 같은 접수 ID를 GET 재개하며 새 POST는 없다. FlashBoot를 실제 원인으로 단정하지 않는다.

그 뒤 Docker registry의 pinned amd64 image config를 읽어 `CUDA_VERSION=12.6.3`, NVIDIA 요구 조건의 `cuda>=12.6`을 확인했다. 운영 endpoint의 `minCudaVersion=12.6`과 달리 임시 생성에서 CUDA제약을 지정하지 않아 실제 최소값이12.0이었다. 이는 에이전트의 실험 설정 누락이다. 관측된 EndpointUpdateInput 계약의 minCudaVersion12.6으로 임시 endpoint만 PATCH하고 반환값12.6을 확인했다. `candidate-cuda.json`/`cuda-match.json`을 로컬 증빙으로 보존한다. 코드의 향후 create분기에도 명시 minCudaVersion12.6을 추가했다(현재 resources 원장은 당시 설정 그대로 보존). 보정 후 같은 요청이 실행/완료됐으나 비정상 워커의 실제 driver/container 오류가 확보되지 않아 이 차이를 전체 queue 원인의 확정으로 보고하지 않으며, 운영 장기큐 원인과도 구별한다.

종료 뒤 임시 min/max0→실제 workers목록0→endpoint/template DELETE204→각 GET404/목록부재를 확인했다. 시작 snapshot 대비 운영 설정에 minCudaVersion까지 포함해 대조하고 운영 image 불변을 확인했다. [비밀 없는 실행·정리 증빙](48_assets/core-face-tags-validation.json)에 요약을 저장했다. 이번 라운드에는 제품 소스/DB·BE/FE push·배포가 없다. docs47의 미커밋 후보 변경은 그대로 보존했다.

## 시각 결과와 해석

[비교 화면](http://127.0.0.1:18769/round8-core-face-tags/index.html)에 원화/Qwen/공통WF-2입력과 같은 요청의 네 Face출력을 배치했다.

- 기본1태그는 붉은 홍채와 더 열린 눈매로 바뀌었다. 사용자4태그는 주황/amber 홍채와 smug 인상을 유지해, 외형 식별 정보를 남겨야 한다는 방향을 지지한다.
- 그러나 핵심4태그0.4에도 눈 아래로 늘어진 검은 선·강한 아이라인이 남고, 긴17태그0.4와 비슷하다. `subtle smoky eye makeup`은 두 조건에 공통으로 남아 있다. 태그 구성이 줄었다고 그 화장 표현까지 없어지는 것은 아니다. 해당 태그 단독 원인으로 확정할 수는 없으며, 이미 공통 WF-2 입력에도 짙은 눈 표현이 있다.
- 핵심4태그0.2는 눈매/미소 재구성이 덜하고 입력에 가까우나 화장선은 남는다. 보정 강도만 낮추면 기존 WF-2의 문제가 잔존할 수 있다.
- 네 조건 모두 변경 bbox[460,107,590,216]·core mask6px확장 밖 변화0·y250아래 몸RGB동일이다. 변화량은 품질 점수가 아니며, core mask 하나만 저장해 다른 분기의 독립 mask 동일성을 직접 측정한 것은 아니다.

결론은 **범용 태그 하나로 외형 정보를 모두 없애는 대신, 캐릭터의 실제 얼굴을 유지하는 핵심 정보만 남기는 방향**이다. 단일 캐릭터/seed에서 안정성이나 태그 개수의 보편적 상한을 입증한 것은 아니다. 현재4태그는 눈색 보존에 도움 됐지만 메이크업 문제를 완전히 해결하지 않았다. 다음 최소 비교 후보는 `detailed beautiful eyes, sharp amber eyes, smug grin`으로 makeup태그만 빼고 같은 조건을 대조하는 것이다. 이 추가 비교/제품의 캐릭터별 핵심태그 선정 구현은 아직 하지 않았다. 원화 전체 프롬프트를 다시 제한할 근거로 사용하지 않는다.

## 독립 검토

`studio_preflight`가 별도 컨텍스트에서 graph의 같은 입력/seed/모델/negative·wildcard/denoise만 다름·KSampler/VAEEncode/Hand 없음·1POST/5출력 경계를 확인했다. 17태그 정정과 기본 태그 어순 정렬을 반영했다. 생성 영수증 유실 시 min1 자원 정리를 못 하는 경계를 지적해 읽기 복구/template-only/부분 삭제 상태 기록을 보완했다. 독립 정상guard1/변조6거부·자원복구/부분삭제/운영ID/참조중 삭제 등의 memory mock10경계를 확인했다. 실제 시각 관측도4태그의amber보존·검은화장선잔존·0.2의변화세기축소와 일치했다. 최종5PNG/SHA/1024크기·1POST/DONE1·mask공간변화/몸RGB보존·minmax0/실제worker0/두DELETE204/GET404/목록부재·운영설정/image불변을 독립 대조했다. 한캐릭터/seed의 관측이며 실청구/일반 안정성은 미확인이다. 관측·기록2026-10-08 KST, provider UTC2026-10-07.

## 2026-10-16 후속: 기본·눈 색·표정 세 태그 1장

사용자는 기본 복원·눈 색·표정 세 요소만 남기는 조건으로 새 샘플1장을 요청했고, 품질 확인 뒤 운영 반영하자고 했다. 이번에는 `detailed beautiful eyes, sharp amber eyes, smug`를 사용했다. 이전 round8 core0.4와 같은 저장WF-2 입력/SHA·모델digest·seed260100802·denoise0.4·sampling/crop/negative를 유지한 Face-only 단일 분기다. 원화/Qwen/WF-2/Hand/LLM 신규 실행과 제품 구현·배포는 없다. 이전 core 대비 `subtle smoky eye makeup` 제거와 `smug grin`→`smug`를 함께 변경했으므로 태그 수나 makeup 단독 인과를 확정하지 않는다.

1POST/accepted1/DONE1/PNG1, 실행17.140초·provider대기323.710초로 완료했다. 입력과 출력1024²·파일SHA·변경영역·y250아래 몸RGB 동일을 대조했다. 출력에는 호박색 홍채와 치아가 보이는 웃는 표정이 관측된다. 원입력에도 눈 화장선이 있으며, 완전한 결함 해결이나 다른 캐릭터 일반성은 주장하지 않는다. 사용자 미관 확인 전 캐릭터별 세 태그 선정 구현과 프로덕션 반영은 보류했다. 새 HTML·비교표는 만들지 않고 [단일 PNG](http://127.0.0.1:18769/round9-three-face-tags.png)를 제공한다.

별도 임시 자원은 pinned 후보이미지·CUDA최소12.6·FlashBootfalse·min/max1로 생성했다. 컨테이너 이미지 다운로드 로그 뒤 실행됐으며, 완료 후 min/max0·실제worker0→endpoint/template DELETE204→각 GET404·목록부재를 확인했다. 시작 대비 운영 정적설정·worker이미지는 불변이다. 실제 청구액은 미확인이다. 민감하지 않은 [검증 원장](48_assets/three-face-tags-validation-2026-10-16.json), 비공개 원본은 Git 제외 `tools/tts/.local/neutral52`에 저장했다. 관측·기록2026-10-16 KST, 출처 실제 요청/완료응답·PNG·RunPod 읽기/정리 응답.

별도 컨텍스트 `studio_preflight`가 동일 입력/요청/출력SHA·1POST/DONE1/PNG1·Face1/Save1·seed/.4·변경bbox[460,107,590,216]/몸영역변경0·종단347.787초와 최종 자원정리/운영불변 증빙을 독립 대조했다. 미관 판단과 제품 배포는 수행하지 않았다.
