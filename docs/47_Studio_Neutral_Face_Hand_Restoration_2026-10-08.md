# Neutral 얼굴·손 복원 개선 — 2026-10-08

> 후속 확정: 2026-10-16 사용자 승인으로 Neutral Face 세 태그0.4 + Hand0.3을 운영 배포했다. 아래는 당시 실험 기록이며 현재 구현/실행 검증은 [docs52](52_Studio_Neutral_Face_Hand_Release_2026-10-16.md)를 따른다.


> 후속 사용자 피드백과 [docs48 핵심 얼굴 태그 비교](48_Studio_Neutral_Core_Face_Tags_Bakeoff_2026-10-08.md): 범용 태그 하나로 모든 외형 정보를 빼는 대신 핵심 특성을 몇 개 남기는 방향으로 검토 중이다. 사용자4태그는 amber홍채를 유지했지만 화장선은 남았다. 이 문서의 generic1태그 미커밋 구현을 확정 릴리스안으로 그대로 배포하지 않는다. 캐릭터별 핵심 태그 선정 구현은 아직 미실행이다.

## 사용자 목적과 변경 범위

사용자는 docs/46 비교 후 수작업의 원래 목적을 설명했다. Qwen i2i의 캐릭터 특징을 유지하고 WF-2로 WAI 그림체·질감을 되돌린 다음, 흐려진 얼굴과 손을 국소 복원한다. 원화 품질은 수용됐으며 원화 생성 프롬프트를 다시 튜닝하지 않는다. 사용자 제안은 HandDetailer 추가와 FaceDetailer의 외형 태그 제거다. 이번 작업은 변경안 구현·후보 워커 검증이며 운영 배포는 아직 하지 않았다.

Neutral 전용 `wf2_neutral_refine.json`을 추가했다. WF-2 전역 sampler/LoRA/negative/기본 denoise0.4는 유지한다. FaceDetailer17은 독립 CLIP21의 `beautiful detailed eyes`만 사용하며 wildcard는 빈 값이다. 캐릭터 appearance/persona/표정 태그를 Face conditioning에 재주입하지 않는다. HandDetailer24는 같은 설치본의 FaceDetailer 노드에 손 detector22를 연결한 마스크 보정이며, 독립 CLIP23의 `detailed hands`만 사용한다. 얼굴 다음 손을 보정하고 SaveImage9는 손 출력24를 저장한다. cycle1·noise_mask=true, guide512/max1024·denoise0.4를 비교 출발값으로 사용한 뒤, 실제 비교를 근거로 **후보 기본값 Face0.2/Hand0.3**으로 낮췄다. 이는 운영 반영 또는 사용자 미관 수용 판정이 아니다.

파이프라인은 BASE_REFINE에서만 새 그래프를 사용한다. 원화 WF-1, GPT Image 감정 파생, 기존 작업 복구용 레거시 감정 WF-2는 보존한다. 남성 LoRA 재배선은 새 face/hand의 model/clip과 두 CLIP conditioning도 포함한다. 두 디테일러 seed도 독립 갱신한다. 어드민 API에 실제 neutral 얼굴·손 conditioning 값을 별도 제공한다.

## 워커와 외부 계약

운영 읽기 관측: UGC endpoint min0/max1, image `zapzook/lucid-comfy-worker:0.3.0`, template disk40GB. 현재 로컬 worker Dockerfile은 얼굴 detector만 포함한다. 손 모델을 추가한 후보 브랜치 `codex/neutral-hand-detailer`/커밋 `bb1215833e0a59a771fb9f235dc89c3fd47e0be3`를 푸시해 기존 GitHub Actions 빌드를 실행했다. 이 후보 push는 BE master push/운영 endpoint 변경이 아니다. [후보 빌드](https://github.com/zapzookj/lucid-comfy-worker/actions/runs/37646877939).

설치 고정 Impact Pack8.28.3 registry archive를 읽고 wildcard 기본 동작이 positive 대체임을 확인했다. 새 neutral은 wildcard를 비우고 positive 자체를 generic CLIP에 분리한다. FaceDetailer는 detector가 찾은 영역을 crop·mask 보정하며 미검출이면 입력 이미지를 반환한다. HandDetailer는 별도 새로운 node class가 아니라 같은 노드와 손 BBOX detector의 조합이다. [공식 노드 구현](https://github.com/ltdrdata/ComfyUI-Impact-Pack/blob/Main/modules/impact/impact_pack.py).

손 모델은 [Bingsu/adetailer hand_yolov8n.pt](https://huggingface.co/Bingsu/adetailer/blob/53cc19de382014514d9d4038601d261a7faa9b7b/hand_yolov8n.pt). revision `53cc19de382014514d9d4038601d261a7faa9b7b`, SHA256 `3991202eb69e9ddcb3b9ba80cdeb41e734ffaf844403d6c9f47d515cd88c6f29`, 6,237,883bytes를 고정했다. Docker build는 SHA와 YOLO label `hand`를 검사한다. 기존 S3 대형 모델 자산과 씬 워커는 바꾸지 않는다.

## 검증 상태

로컬 관련 테스트63건 성공/실패0/skip0. 새 회귀검증은 얼굴·손 conditioning 분리, 전역 appearance에 강한 makeup/nail 태그가 있어도 generic CLIP/wildcard로 유입되지 않음, 출력·검출 연결, seed3개 갱신·template deepCopy, 남성 LoRA 재배선과 WF-1/legacy 감정 경로 보존이다. 모든 테스트의 성공은 미관 개선 판정과 구별한다.

실험은 기존 job30 강세라 후보1의 저장 Qwen 패스2를 사용한다. 새로운 Qwen/LLM 요청은 없고 최대2개 고정 RunPod 제출 슬롯으로 구성한다. 하나의 WF-2 결과를 기존 Face와 generic Face가 공유하고 face seed도 같다. WF-2/기존Face/genericFace/Hand/faceMask/handMask 6출력을 저장한다. 두 번째 슬롯은 흰 이미지의 hand-only 미검출 passthrough와 mask를 저장한다. 요청·입력·전체 graph SHA, seed·출력 경계, 슬롯 집합을 확인하고 unknown 제출은 재POST하지 않는다.

후보 image digest를 고정한 임시 endpoint는 운영과 다른 ID, env빈값/운영 webhook없음/volume0/min0/max1/idle10초다. 실제 worker list가 빈 배열임을 확인한 뒤 endpoint와 template를 삭제한다. 생성 결과가 불명확하면 이름·image·template 대조로 읽기 복구하고 생성 요청을 자동 반복하지 않는다. 키·원본 payload/base64·provider 영수증은 Git 제외 `tools/tts/.local/neutral47`에만 둔다.

후보 빌드는 2026-10-07T15:47:58Z–16:09:11Z에 success로 완료됐다(약21.2분). Docker build에서 `deps OK`·`hand detector OK` stdout를 확인했다. amd64 digest `sha256:6a9d54d6bdceb16cfc9d6b4c303867661c7efccc5fa6819acf17e10eb2d8e5f2`를 임시 endpoint에 고정했다. 태그는 `neutral-hands-20261008-bb12158`이다. 운영 worker image 변경은 없었다.

첫2슬롯은 accepted2/DONE2/산출8파일로 완료됐다. main은 WF-2/기존Face/genericFace/Hand/faceMask/handMask, blank는 출력/마스크다. main 실행53,360ms·큐230,530ms·관측290.047초, blank 실행242ms·큐26,979ms·관측31.344초. main은 기존 Face 추가 대조와 마스크 출력까지 포함하므로 운영 neutral 지연의 측정값으로 재사용하지 않는다.

Generic Face0.4는 눈 아래 검은 선·과한 속눈썹을 줄였으나 주황 홍채를 붉게 만들고 눈매·볼색·미소 인상을 재구성했다. Face 전후13,430px, bbox[460,107,590,216]. Hand0.4는 손가락 윤곽·관절을 정리했으나 Qwen의 지목 동작이 WF-2에서 바뀐 것을 복원하지 못했다. 허리 손의 반지·손톱도 재구성됐다. 변경12,929px, bbox[338,190,640,428]. Hand 후 얼굴 ROI RGB는 동일하며 변경은 raw detector mask 밖2,440px/6px확장 밖0px다. Raw mask는 feather 전의 사각영역이므로 밖 변경을 즉시 누수로 판정하지 않는다. 흰 입력 미검출은 RGB 완전 일치/마스크0을 확인했다.

같은 저장 WF-2 출력과 같은 Face/Hand seed에 Face0.2·Hand0.3을 적용한 추가1슬롯도 DONE/2파일로 완료됐다. 추가 low 첫 POST는 HTTP409로 거절됐고 ID가 없었다. 오류 본문은 보존되지 않아 원인은 미확정이다. 거절 이후 health completed2/failed0/inProgress0/inQueue0/retried0를 확인해 거절 원장을 보존한 후 별도 O_EXCL 슬롯으로 같은 payload를 수동 접수했다. **POST시도4 = accepted3/DONE3 + 명시409거절1; 산출10 = main6 + blank2 + low2**로 대조했다. 모호한 접수·timeout을 새 POST로 반복하지 않았다. 관측600초 종료 뒤에도 같은 ID를 GET으로만 재개했다. 임시 endpoint에서 UNHEALTHY/THROTTLED와 장기 queue가 관측돼 min1/max1로 기동 유도했다. 다른 프로덕션 endpoint는 수정하지 않았다.

Low Face0.2는 눈을 덜 크게 재구성하고 기존 눈매에 좀 더 가까우나 붉은 홍채·미소 변화는 남았다. Hand0.3은 관절 분리가 좋아지고0.4보다 재구성이 덜하나 지목 동작을 복원하지 못했다. 얼굴13,492px/손13,212px라 변경 면적 감소로 설명하지 않는다. RGB 절대변화합은 face469,334→323,060, hand372,815→306,370이다. 이는 원본 대비 픽셀 변화 세기이며 미관 품질 지표가 아니다. 손 후 얼굴 ROI RGB 보존과 main 손 mask6px확장 밖 변화0을 확인했다. Low 자체의 mask는 따로 저장하지 않았으므로 main mask를 이용한 공간 대조와 실제 low 검출 계약 검증을 구별한다.

Low 실행27,206ms·provider queue1,106,193ms(약18분26초), 접수 원장 시작→완료1,139.660초(18분59.66초). ledger `elapsedSeconds=492.078`은 GET 재개 이후 관측 구간으로 전체 wall time이 아니다. main/low는 같은 image digest/입력/seed이지만 다른 worker이며 low는 RTX A4500이다. GPU 할당은 통제하지 않아 엄밀한 동일 GPU 결정성 실험은 아니다. 긴 소요의 주요 관측은 Docker image 빌드21.2분과 두 번째 기동/압축 해제·queue이며, 3개 job의 GPU 실행합은80.808초다. 실제 GPU 청구액은 미확인이고 대기·초기화·min1 유지 비용은 실행시간과 별개다.

최종 기본값 변경 후 Factory14건 실패0/skip0 및 bootJar를 재실행했다. 앞선 관련63건과 중복 합산하지 않는다. JAR 내부4workflow가 소스 byte와 일치하며 기존 WF-1/레거시 WF-2/WF-3는 HEAD와 같다. 첫0.4 template SHA의 로컬 보존본과 최종 template의 변경은 Face/Hand denoise 두 필드뿐임을 확인했다. 키·원본 요청·원장은 Git 제외 로컬 폴더, 비밀 없는 요약은 [검증 증빙](47_assets/neutral-restoration-validation.json)에 저장했다.

실험 종료 뒤 임시 endpoint min0/max0→실제 workers목록0→endpoint/template삭제를 순서대로 실행했다. 두 자원의 GET404/목록 부재와 운영 static설정·image `0.3.0` 불변을 다시 확인했다. BE 변경은 미커밋/미푸시/미배포, worker 후보 브랜치만 빌드 목적으로 푸시했다. [비교 화면](http://127.0.0.1:18769/round7-neutral-detailers/index.html)에는 원화/Qwen/WF-2/기존Face/기본Face0.4/Hand0.4/Face0.2/Hand0.3 및 얼굴·양손 확대를 둔다.

향후 운영 반영 시 손 detector가 없는 worker `0.3.0`에 새 BE만 배포하면 안 된다. UGC 전용 worker image에 pinned 손 모델을 먼저 포함해 새 그래프 실행 가능성을 확인한 뒤 BE를 반영해야 한다. 씬 worker는 이번 변경 대상이 아니다. 현재 후보 digest/브랜치 push는 그 순서의 사전 검증이며 운영 배포 승인을 의미하지 않는다.

**남은 불확실성:** 한 캐릭터·한 seed 관측이며 overlapping/얼굴 바로 옆 손, 남성 캐릭터 실제 유료 이미지, 앱 전체 neutral 생성·누끼/선택/리롤 E2E는 미실행이다. WF-2 전역 positive는 기존 외형/persona를 유지하므로 makeup 표현이 그 단계에서 생기는 것은 이번 변경으로 해결되지 않는다. 범용 Face는 홍채 색을 고정하지 않고, Hand는 WF-2가 바꾼 제스처를 복구하지 않는다. 이 변경을 neutral 품질의 완전 해결로 보고하지 않는다.

WF-2에서 먼저 생긴 눈 표현·손 제스처 변경을 generic Face/Hand가 원화 그대로 되돌린다는 보장은 없다. 손 검출 마스크는 픽셀 segmentation이 아닌 사각 bbox이므로 소매·장신구에도 영향을 줄 수 있다. 변경영역과 얼굴/손동작/해부학을 나누어 판정한다.

## 독립 검토

`studio_preflight` 별도 컨텍스트가 설계와 graph/입력/원장을 읽기 검토했다. neutral/legacy 분리, positive/wildcard 동시 분리, bbox 주변 변경, 미검출 및 임시 자원 정리 경계를 지적해 반영했다. 10파일/SHA/갤러리복사본·4POST/accepted3/DONE3/409거절1·저강도 시각/변화합을 독립 대조했다. 일반 태그0.4의 홍채/눈매 변화와 Hand가 제스처를 복구하지 못하는 한계, raw detector mask와 feather 차이, 낮은 강도에서 픽셀 수가 감소하지 않은 점, low 전체wall18분59.66초를 확인해 보고에 반영했다. 최종 min/max0·실제workers0·두DELETE204·GET404/목록부재·운영설정/이미지 불변도 독립 검토했고 blocking 문제는 없다고 보고했다. 기록일2026-10-08 KST, provider UTC timestamp는2026-10-07로 보존한다.
