# 06. 배포 런북 — GitHub Actions CI/CD + 재배포(데이터 초기화 포함)

> 작성: 2026-07-23. 이 문서는 백엔드(ECS) 자동 배포 파이프라인의 구조, 1회성 셋업, 데이터 전체 초기화 절차, 프론트/어드민 배포 절차를 다룬다.

---

## 1. 현행 인프라 (실측, 2026-07-23 기준)

| 구분 | 값 |
|---|---|
| AWS 계정 / 리전 | `063876841553` / `ap-northeast-2` |
| ECS 클러스터 | `lucid-cluster` (Fargate) |
| ECS 서비스 | `lucid-chat-server-service-2z3rryhh` (desired 1) |
| 태스크 정의 | `lucid-chat-server` (컨테이너 `lucid-chat-container`, 0.5vCPU/1GB, 8080) |
| ECR | `063876841553.dkr.ecr.ap-northeast-2.amazonaws.com/lucid-chat-backend` |
| ALB 타겟그룹 | `lucid-backend-tg` (헬스체크: `GET /health`) |
| RDS | `lucid-chat-rdb` (PostgreSQL, 퍼블릭 액세스 ON, DB명 `lucidchat`) |
| Redis | Upstash (SSL) |
| MongoDB | Atlas (`chat_logs`, `chat_log_deadletter`, `theater_scene_logs`) |
| S3 | `lucid-chat-assets-v2` (서비스 에셋), `lucidchat-ugc-gen` (UGC 워커 출력) |
| CDN | `d3gb5c1krrdbgj.cloudfront.net` (앱 생성 에셋 = CLOUDFRONT_ASSETS_URL), `d3578f1gfp49r6.cloudfront.net` (공식 시드 에셋) |
| 로그 | CloudWatch `/ecs/lucid-chat-server` |
| 도메인 | `api.lucid-chat.com`(백엔드), `lucid-chat.com`(프론트/Vercel), `admin.lucid-chat.com`(어드민/예정) |
| 운영 프로필 | `SPRING_PROFILES_ACTIVE=prod,characters,worlds,v2` |

환경변수는 **태스크 정의에 평문 주입** (24개). 시크릿이므로 태스크 정의 JSON을 리포에 커밋하지 말 것.
장기 과제: SSM Parameter Store 이관 + `AWS_ACCESS_KEY/SECRET` env 제거(Task Role 전환).

## 2. CI/CD 파이프라인 (.github/workflows/deploy.yml)

- **트리거**: `master` push (또는 수동 `workflow_dispatch`)
- **인증**: GitHub OIDC → `lucid-gha-deploy-role` assume. 장기 액세스키/GitHub Secrets 불필요.
  롤 신뢰 정책이 `repo:zapzookj/AI-CharacterChat-Server:ref:refs/heads/master`로 제한됨.
- **흐름**: JDK17 + gradle 캐시 → 유닛 테스트(`--tests '*Test'` — `@SpringBootTest`인 `AichatApplicationTests`는 제외) → `bootJar` → docker build → ECR push (`{git sha}` + `latest` 태그) → 현행 태스크 정의 다운로드 → 이미지만 교체해 신규 리비전 등록 → 서비스 업데이트 → 안정화 대기(최대 10분)
- **env 변경 방법**: 파이프라인은 이미지만 갈아끼우므로, 환경변수 변경은 콘솔/CLI로 태스크 정의 신규 리비전을 만들고 서비스에 반영하면 다음 배포부터 그 리비전을 베이스로 사용.

### 2.1 1회성 셋업 (IAM) — 미완이면 파이프라인 동작 불가

```bash
# ① GitHub OIDC 프로바이더 (계정에 1개만 있으면 됨)
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 1c58a3a8518e8759bf075b76b750d4f2df264fcd

# ② 배포 롤 (trust/permissions JSON은 scratchpad 또는 이 문서 부록 참고)
aws iam create-role --role-name lucid-gha-deploy-role \
  --assume-role-policy-document file://gha-trust-policy.json
aws iam put-role-policy --role-name lucid-gha-deploy-role \
  --policy-name lucid-gha-deploy-policy \
  --policy-document file://gha-permissions-policy.json
```

권한 요약: ECR push(lucid-chat-backend 한정) / taskdef describe·register / 해당 서비스 UpdateService / ecsTaskExecutionRole PassRole.

## 3. 신버전 배포 전 태스크 정의 env 갱신

| 변수 | 조치 | 비고 |
|---|---|---|
| `UGC_RUNPOD_API_KEY` | **추가** | RunPod Serverless API 키 |
| `UGC_RUNPOD_ENDPOINT_ID` | **추가** | worker-comfyui 엔드포인트 ID |
| `UGC_RUNPOD_WEBHOOK_SECRET` | **추가** | 임의 랜덤 문자열 (백엔드-RunPod 웹훅 검증) |
| `LUCID_WEBHOOK_BASE` | **수정** | 현재 빈 문자열 → `https://api.lucid-chat.com` |

부팅 필수 env는 전부 기존 리비전에 존재. UGC 3종은 빈 기본값이 있어 부팅은 되지만 UGC 스튜디오 이미지 생성이 호출 시점에 실패한다.

## 4. 데이터 전체 초기화 + 재구축 (파괴적 — 실행 전 최종 확인)

**핵심 제약**: Flyway(V1~V13)는 코어 스키마를 만들지 않는다(신규 오브젝트만 관리). 빈 DB에서 Flyway를 켠 채 부팅하면 V2(`ALTER TABLE users`)에서 즉사. 반드시 **2단계 부팅**:

```
[0] ECS 서비스 desired-count 0 (앱 정지)
[1] 데이터 삭제
    RDS   : DROP SCHEMA public CASCADE; CREATE SCHEMA public;
    Mongo : db.dropDatabase()  (URI의 DB 대상)
    Redis : FLUSHDB (Upstash 콘솔 Flush 버튼이 가장 간단)
    S3(선택): illustrations/, backgrounds/, ugc/jobs/, ugc/world-jobs/ 프리픽스 삭제.
             ⚠ characters/, worlds/ 는 공식 시드 에셋과 섞여 있으므로 건드리지 말 것.
[2] 1차 부팅: 태스크 정의에 SPRING_FLYWAY_ENABLED=false 추가 (SPRING_JPA_HIBERNATE_DDL_AUTO는 이미 update)
    → desired-count 1 → Hibernate가 39개 엔티티 전체 스키마 생성, 시더(월드→로케이션→페르소나→캐릭터→루틴) 자동 주입
[3] 2차 부팅: SPRING_FLYWAY_ENABLED 제거한 리비전으로 교체
    → baseline-on-migrate가 스키마를 baseline 9로 기록, 멱등인 V10~V13만 적용
[4] Mongo 인덱스: prod는 auto-index-creation=false — 첫 채팅 전에 mongosh로 수동 생성
    (chat_logs의 idx_room_created 등 @CompoundIndex 정의 참조) 또는 일시적으로 auto-index-creation=true
[5] 검증: /health 200, OAuth 로그인, 시드 캐릭터 목록, 채팅 1턴
[6] 관리자 재승격: zapzook 계정으로 OAuth 로그인 → 재부팅(다음 배포) 시 AdminBootstrapRunner가 ROLE_ADMIN 부여
```

RDS는 퍼블릭 액세스 ON이므로 로컬에서 psql/docker로 직접 접속 가능(보안그룹 인바운드에 현재 IP 필요).
후속 권장: `pg_dump --schema-only`로 V0 베이스라인을 만들어 두면 다음부터 Flyway 단독 재구축 가능.

## 5. 프론트(유저 앱) — Vercel

- `.env`(git 추적됨)에 운영 URL이 있고, 하드코딩됐던 localhost 4곳(axios.js, UseChatStream.js, UseStoryV2Stream.js, LoginPage.jsx)은 env 참조로 복원됨 (2026-07-23).
- 로컬 개발은 `.env.development`(신규)가 localhost로 오버라이드.
- 배포 = `master` push → Vercel 자동 빌드. 미푸시 커밋 3개 + 월드빌더 작업분 커밋 필요.

## 6. 어드민 SPA — Vercel 신규 프로젝트

1. 로컬 git 리포 초기화 완료(2026-07-23), `vercel.json`(SPA fallback) + `.env.production`(운영 API URL) 추가됨.
2. GitHub에 **private 리포 생성 필요** (예: `zapzookj/LucidChat-Admin`) → push.
3. Vercel에서 해당 리포 import → 도메인 `admin.lucid-chat.com` 연결 (DNS CNAME).
4. 백엔드 CORS는 `https://admin.lucid-chat.com` 기허용 — 도메인만 정확하면 백엔드 변경 불필요.
5. 관리자 계정: `app.admin.bootstrap-usernames=zapzook` (기동 시 승격, §4[6] 참고).

## 7. 런칭 전 보안 체크리스트

- [ ] **ModelsLab API 키 로테이션** — `register.sh`에 하드코딩된 키가 커밋 79f0b7d로 git 이력에 영구 포함(원격 push됨). 파일 수정만으론 불충분, 키 자체를 재발급.
- [ ] `register.sh`에서 하드코딩 라인 제거
- [ ] `src/main/resources/application.zip` 제거 — 구 설정 백업(2026-04-21)이 jar 안에 포함되어 배포됨
- [ ] 태스크 정의 `AWS_ACCESS_KEY/SECRET` → ECS Task Role 전환 (S3Properties가 정적 키 대신 default credentials provider 사용하도록 수정 필요)
- [ ] 태스크 정의 평문 env → SSM Parameter Store 이관
- [ ] `lucid-key.pem` 로테이션 및 구 EC2 인스턴스 정리(현행 운영은 ECS — EC2가 아직 떠 있으면 비용/공격면)
- [ ] S3 버킷 퍼블릭 액세스 차단 원복 확인 (docs/03 런북 미완 항목)
- [ ] RDS 퍼블릭 액세스 OFF 전환 검토 (초기화 작업 이후)
- [ ] 프론트 `ngrok-skip-browser-warning` 헤더 등 개발 잔재 제거 검토
