# UGC 파이프라인 E2E 런북 (은발 교수 관통 검증)

> 작성 2026-07-17 · 백엔드 구현 완료 시점 기준. 아래 선행조건이 채워지면 이 문서 순서대로 관통한다.

## 1. 선행조건 체크리스트 (사람 작업 — 종원)

### 1-1. 인프라
- [ ] **워커 이미지 재빌드**: lucid-comfy-worker의 Dockerfile이 `5.8.6-base` + `birefnet.safetensors`로 수정됨 (2026-07-17).
  - S3 `assets-build/background_removal/birefnet.safetensors` 업로드 (원본: https://huggingface.co/Comfy-Org/BiRefNet/resolve/main/background_removal/birefnet.safetensors · 444,473,596 bytes · sha256 `9ab37426…356a154`)
  - GHA `build-comfy-worker` 실행 → 태그 `0.2.0` → Serverless 엔드포인트 이미지 교체
  - ⚠ 재빌드 후 wf1/wf2도 재검증 필요 (베이스 이미지 교체 = 5.7 체크리스트 재실행 원칙)
- [ ] 전용 출력 버킷 `lucidchat-ugc-gen` 생성 + lifecycle 30일 + `lucid-gen-writer` IAM(Put/Get) → 엔드포인트 환경변수(`BUCKET_ENDPOINT_URL` = `https://lucidchat-ugc-gen.s3.ap-northeast-2.amazonaws.com` — 버킷명 포함 필수) 설정 후 웜 워커 terminate
- [ ] **기존 버킷 퍼블릭 액세스 차단 원복** (검증 중 임시 허용 상태 — 스펙 §5-1 필수)

### 1-2. 백엔드 환경변수 (미설정 시 기동은 되나 UGC 호출 시점 실패)
```
UGC_RUNPOD_API_KEY=          # RunPod API 키
UGC_RUNPOD_ENDPOINT_ID=      # Serverless 엔드포인트 ID
UGC_RUNPOD_WEBHOOK_SECRET=   # 임의 시크릿 (webhook URL 쿼리 검증용)
LUCID_WEBHOOK_BASE=          # 공개 백엔드 베이스 URL (기존 modelslab과 공용)
FAL_API_KEY=                 # 기존 키 재사용 (Qwen 편집도 이 키)
```
- fal 계정 동시성 한도 확인 (기본 2 — 감정 14종이 순차 소화됨. 출시 전 10~20 상향 권장)

### 1-3. 새(빈) 로컬 DB 초기화 — 2단계 부팅 (2026-07-20 추가)

빈 DB에서는 Flyway(V2가 users ALTER)가 Hibernate(users 생성)보다 먼저 돌아 항상 실패한다.
기존 스키마가 있는 DB면 이 절차 불필요 — V9만 자동 적용된다.

```powershell
# 1단계: Flyway 끄고 1회 부팅 → Hibernate ddl-auto가 전체 스키마 생성 (기동 확인 후 종료)
$env:SPRING_FLYWAY_ENABLED = "false"; .\gradlew.bat bootRun

# 2단계: V9를 기준선으로 1회 부팅 → V1~V9 스킵 기록 생성. 이후엔 환경변수 없이 평소대로.
Remove-Item Env:\SPRING_FLYWAY_ENABLED
$env:SPRING_FLYWAY_BASELINE_VERSION = "9"; .\gradlew.bat bootRun
```

근본 해결(전체 스키마의 Flyway baseline화)은 런칭 전 폴리싱 목록 — prod validate 배포 불가 이슈와 동일 뿌리.

## 2. 자동 검증 (구현 완료분)

```powershell
# 치환 엔진·클라이언트·모더레이션 계약 테스트
.\gradlew.bat test --tests "com.spring.aichat.service.ugc.*" --tests "com.spring.aichat.external.UgcComfyClientTest"
```

## 3. 관통 시나리오 (은발 교수)

로컬 기동(local 프로파일, V9 Flyway 자동 적용) 후 프런트 `/studio` 또는 curl:

1. **컨셉 제출** — `POST /api/v1/ugc/characters`
   ```json
   { "name": "설하", "concept": "안개 낀 마법학원의 은발 교수. 붉은 아이섀도와 나른한 눈매, 흑색 터틀넥과 학자 클로크. 차갑고 무심해 보이지만 밤의 도서관에서만 보이는 외로움이 있다. 금서를 연구하다 금기에 닿은 과거." }
   ```
   → 202 `{jobId}` · 에너지 20 차감 확인
2. **가챠 대기** — `GET /ugc/characters/{jobId}` 폴링: `CONCEPT_ANALYZING` → `GOLDEN_GENERATING` → `GACHA_WAIT` + goldenShots 4장 URL (CloudFront). 소요/콜드 delayTime 기록.
3. **선택** — `POST .../golden-shot {"selectedIndex":0}` → BASE_PROCESSING → (Qwen 2패스 ~1분) → EMOTIONS_PROCESSING
4. **감정 그리드** — emotionAssets 15키가 DERIVING→REFINING→READY로 채워지는지. (동시성 2면 14종 순차 — 총 소요 기록이 핵심 실측치)
5. **검수** — REVIEW_WAIT에서 1컷 리롤(2 에너지) + FAILED 컷 무료 재시도 동작 확인 → `POST .../confirm`
6. **누끼·바인딩** — POSTPROCESSING(15× WF-3)→BINDING→READY + characterId. `characters/ugc-{jobId}/default_*.png` 15장 + `thumbnail.png` CDN 확인 (RGBA 투명 배경).
7. **대화** — 로비 방 생성(SANDBOX) → 첫인사/감정 스탠딩 스왑 확인. Secret 토글 시도 → 승인 전 차단 확인.
8. **승인 큐** — `POST .../publish-request` → admin `GET /api/v1/admin/characters/ugc/queue` → 상세 → 승인(체크박스 2개) → explore 피드 노출 + Secret 대화 허용 확인.
9. **실패 경로 1건** — 존재하지 않는 모델명 등으로 강제 실패 유도 → FAILED + **전액 환불** 확인.

## 4. 실측 기록 (2026-07-20 · jobId=4 → characterId=11, 첫 완주)

| 항목 | 값 |
|---|---|
| Stage0 LLM 소요 | 27s |
| WF-1 (배치 4장) | GPU 62s(첫 잡 모델 로드 포함) · 제출→감지 2m09s(폴링 지연 포함) |
| Qwen 2패스 소요 | 8s + 7s |
| WF-2 1건 | GPU ~15s |
| 감정 14종 Qwen 편집 | 전부 53s 내 완료 (동시성 우려 실측상 무문제) |
| 감정 14종 WF-2 총 소요 | ~5m — 보조 워커 crash-loop로 워커 1대가 전담 + 폴링 1분 간격 |
| WF-3 15종 | GPU 각 ~0.6s · 총 ~1.5m |
| 바인딩+승격 | 2s |
| 총 관통 시간 | 14m42s (유저 대기 ~2.5m 포함, 순 처리 ≈ 12m) |
| 폴링 낭비 (웹훅 미도달) | 전 이벤트 폴백 처리 — 추정 3~4m |
| fal 청구액 / RunPod 크레딧 소모 | 대시보드 확인 필요 (추정: Qwen 16콜 ≈ $0.5, GPU 합계 ≈ 5.5분) |
| → 캐릭터 1건 총원가 | 추정 $0.6~0.8 (추정 밴드 $0.5~1.5 하단 — 실청구로 확정 필요) |

## 5. 확정 정책 요약 (구현 반영값)

- 에너지: 기본 패키지 **20** / 황금샷 배치 리롤 **2** / 감정 1컷 리롤 **2** / 실패 컷 자동·수동 재시도 무과금
- 환불: 파이프라인 귀책 FAILED = **전액 환불** / 72h 방치 EXPIRED·중도 포기 = 무환불
- NEUTRAL 컷 리롤 불가 (스타 토폴로지 원점 보호)
- 검수 확정은 15컷 전부 READY일 때만
- 동시 진행 잡은 유저당 1개
- UGC는 SANDBOX 전용 · 기본 PRIVATE · 공개/Secret 각각 독립 심사 (PRIVATE 유지 + Secret 단독 신청 가능)
