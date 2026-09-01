# 20. 탈AWS 인프라 이관 런북 — Vultr + Cloudflare R2 (2026-08-18)

> 결정 배경·빌링 실측은 세션 기록(2026-08-18) 참조. **이 문서가 이관 실행의 정본**이다.
> 완료된 선행 작업: AWS 데이터 전량 반출(`C:\Users\zapza\Desktop\MuseLab\infra-migration\` — DB 덤프 + S3 6버킷 13.3GB),
> AWS 과금원 철거(ALB·EIP·RDS×2·ECS desired 0 — 잔여 월 $1 미만), Cloudflare NS 이전 Active.

## 0. 목표 아키텍처

| 구성요소 | 이전(AWS) | 이후 |
|---|---|---|
| 컴퓨트 | ECS Fargate + ALB | **Vultr 서울 VPS 1대** — docker compose(app·postgres·caddy) |
| RDB | RDS PostgreSQL 17 | **VPS 내 postgres:17 컨테이너** + 일일 덤프→R2 |
| 에셋 저장/CDN | S3 + CloudFront×2 | **Cloudflare R2 + 커스텀 도메인 CDN** (이그레스 0원) |
| TLS/DNS | ACM + Route53 | **Caddy 자동 TLS + Cloudflare DNS** |
| CI/CD | GHA→ECR→ECS | **GHA→GHCR→SSH** (`.github/workflows/deploy.yml`) |
| Redis/Mongo/이미지생성/프론트 | Upstash/Atlas/RunPod/Vercel | 무변경 |

예상 월비용: VPS ~$24 + R2 ~$0.2 = **~₩3.5만** (기존 ₩9~11만).

## 1. 종원 선행 액션 (계정 권한 필요한 것들)

1. **Vultr 인스턴스 생성**: Seoul 리전 · Cloud Compute(Shared) · **2 vCPU / 4GB RAM** · Ubuntu 24.04 LTS · SSH 키 등록(새로 만들면 개인키 보관). 생성 후 **IP를 알려줄 것**.
2. **R2 활성화 + 버킷 3개** (Cloudflare 대시보드 → R2): `lucid-chat-assets-v2`(서비스 에셋 — AWS와 동명으로 설정 단순화), `lucidchat-ugc-gen`(워커 출력), `lucid-chat-backups`(DB 백업 — 비공개).
3. **R2 커스텀 도메인**: `lucid-chat-assets-v2` 버킷 → Settings → Custom Domains → **`assets.lucid-chat.com`** 연결(자동으로 DNS 레코드 생성·프록시됨).
4. **R2 API 토큰 발급**: R2 → Manage R2 API Tokens → Create — 권한 **Object Read & Write**(3버킷). Access Key ID / Secret / 계정 ID 엔드포인트가 나온다 → **`infra/.env.prod.example`을 복사한 `.env`에 기입**(리포 커밋 금지).
5. **GHCR PAT**: GitHub → Settings → Developer settings → PAT(classic) — `read:packages` 권한만. 서버의 `docker login ghcr.io` 용.
6. **GitHub Secrets** (LucidChat-Server 리포 → Settings → Secrets → Actions): `DEPLOY_HOST`(VPS IP) · `DEPLOY_USER`(root) · `DEPLOY_SSH_KEY`(개인키 전문).

## 2. 에셋 업로드 (로컬 반출본 → R2)

로컬에서 실행(R2 토큰 필요). AWS CLI의 S3 호환 모드 사용:

```bash
export AWS_ACCESS_KEY_ID=<R2 AccessKey> AWS_SECRET_ACCESS_KEY=<R2 Secret>
R2="--endpoint-url https://<ACCOUNT_ID>.r2.cloudflarestorage.com --region auto"
cd /c/Users/zapza/Desktop/MuseLab/infra-migration/s3
aws s3 sync lucid-chat-assets-v2  s3://lucid-chat-assets-v2  $R2 --only-show-errors
aws s3 sync lucidchat-ugc-gen     s3://lucidchat-ugc-gen     $R2 --only-show-errors
```

- `comfy`(7.9G)·`models`(491M)는 **RunPod 워커용** — 서비스 CDN 불요. 워커가 S3에서 직접 읽는 구조라면 R2에 동명 버킷으로 올리고 워커 env를 교체, 로컬 캐시 구조면 생략. (워커 리포 확인 후 결정 — §5-3)
- `frontend-assets`(832M)는 **시드 CDN(d3578f)의 오리진이다** — 캐릭터 스탠딩·시드 썸네일·배경·사운드·월드 이미지 전부. ⚠ 초기 판정("Vercel 잔재")은 오판이었고 2026-09-01 실측으로 정정. **`lucid-chat-assets-v2` R2 버킷에 병합 업로드**한다(두 CF 도메인 → assets 단일 도메인 통합 설계와 정합):

```bash
aws s3 sync lucid-chat-frontend-assets s3://lucid-chat-assets-v2 $R2 --exclude "ngrok.yml" --only-show-errors
```

- ⚠ `frontend-assets/ngrok.yml`에 ngrok authtoken이 평문 노출돼 있었다(퍼블릭 버킷) — 업로드 제외 + **ngrok 대시보드에서 해당 토큰 폐기**할 것.

## 3. VPS 셋업

```bash
# 로컬 → 서버 파일 전송 (IP·키 교체)
scp -i <개인키> infra/docker-compose.prod.yml infra/Caddyfile infra/setup-server.sh infra/deploy.sh infra/backup.sh root@<VPS_IP>:/opt/lucid/
scp -i <개인키> <실값 .env> root@<VPS_IP>:/opt/lucid/.env
```
```bash
# 서버에서
chmod +x /opt/lucid/*.sh && /opt/lucid/setup-server.sh
docker login ghcr.io -u zapzookj   # 비밀번호 = read:packages PAT
```

첫 이미지는 CI가 밀어주기 전이므로: GitHub Actions에서 `workflow_dispatch`로 1회 실행(빌드+push만 성공하면 SSH 단계 실패해도 무방) 후 서버에서 `docker compose -f docker-compose.prod.yml up -d`.

## 4. DB 임포트 + 에셋 URL 재작성

```bash
# 덤프 전송 후 서버에서 (postgres 컨테이너 기동 상태)
docker cp lucidchat-premigration-0812.dump lucid-postgres:/tmp/
docker exec lucid-postgres pg_restore -U postgres -d lucidchat --no-owner /tmp/lucidchat-premigration-0812.dump
```

DB 안의 절대 에셋 URL(CloudFront 2도메인)을 새 CDN으로 전량 치환 — 멱등 DO 블록:

```sql
DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN
    SELECT table_name, column_name FROM information_schema.columns
    WHERE table_schema='public' AND data_type IN ('character varying','text')
  LOOP
    EXECUTE format(
      'UPDATE %I SET %I = replace(replace(%I,
         ''https://d3gb5c1krrdbgj.cloudfront.net'', ''https://assets.lucid-chat.com''),
         ''https://d3578f1gfp49r6.cloudfront.net'', ''https://assets.lucid-chat.com'')
       WHERE %I LIKE ''%%cloudfront.net%%''',
      r.table_name, r.column_name, r.column_name, r.column_name);
  END LOOP;
END $$;
-- 검증: 0이어야 함
SELECT count(*) FROM (
  SELECT 1 FROM characters WHERE thumbnail_url LIKE '%cloudfront%'
) x;
```

**시드 yml도 동일 치환** (코드 커밋 — 절환 시점에):
```bash
grep -rl "d3578f1gfp49r6.cloudfront.net" src/main/resources | xargs sed -i "s|https://d3578f1gfp49r6.cloudfront.net|https://assets.lucid-chat.com|g"
```

## 5. 절환

1. **DNS**: Cloudflare에서 `api.lucid-chat.com` A 레코드 → VPS IP, **DNS only(회색 구름)** — Caddy가 LE 인증서를 직접 발급(SSE 스트리밍에 프록시 변수 제거). 이후 안정화되면 프록시 전환 검토(그때 SSL 모드 Full(strict)).
2. **Vercel env**(프론트·어드민): 에셋 베이스 URL 변수(`VITE_ASSET_BASE_URL`)를 `https://assets.lucid-chat.com`으로. API URL은 무변경. 리포의 `.env`(git 추적)도 동일 수정 후 재배포.
3. **RunPod 워커 env**: UGC/씬 워커 엔드포인트의 S3 관련 env(액세스키·시크릿·엔드포인트·버킷)를 R2 값으로 교체 — 워커가 R2에 결과를 쓰고, 백엔드 웹훅(`LUCID_WEBHOOK_BASE=https://api.lucid-chat.com`)은 무변경.
4. **검증 체크리스트**: `/health` 200 → OAuth 로그인 → 홈 피드(시드 썸네일 = R2 커스텀 도메인) → 자유 채팅 1턴(SSE) → 씬 일러 1회(워커→R2→노출) → UGC 스튜디오 생성 1회 → 결제 ready(모의) → `backup.sh` 수동 1회 실행·R2 업로드 확인 → cron 등록.

## 6. CI/CD

`master` push → GHA: 유닛 테스트 → bootJar → GHCR push(`:sha`+`:latest`) → SSH로 `deploy.sh`(pull→up→헬스 90s 게이트). 시크릿 3개는 §1-6. 롤백 = 서버에서 `docker compose up -d` 전에 이미지 태그를 직전 sha로 고정.

## 7. 마무리 정리 (절환 검증 후)

- AWS: S3 6버킷 비우고 삭제 → CloudFront 배포 삭제 → ECR 리포 삭제 → CloudWatch 로그그룹 삭제 → Route53 호스팅 존 삭제 → 수동 스냅샷 2종은 **신인프라 2주 안정 후** 삭제 → 도메인을 Cloudflare Registrar로 이전(연 $15→$10, 이전 완료 후 AWS 계정 해지 가능).
- 로컬 `infra-migration/` 반출본은 외장/클라우드에 1부 사본 후 보관.
- 모니터링: Uptime Kuma(같은 VPS 컨테이너) 또는 UptimeRobot 무료로 `/health` 감시 — 절환 주간에 설정.

## 부록 — 트러블슈팅

- **R2 403/SignatureDoesNotMatch**: `S3_REGION=auto` 확인, 엔드포인트에 버킷명 포함 금지(경로 스타일은 SDK가 처리).
- **Caddy 인증서 실패**: `api` 레코드가 프록시(주황)면 LE 검증이 꼬일 수 있음 — DNS only 확인.
- **pg_restore 권한 에러**: `--no-owner` 플래그 확인(로컬 롤 부재).
- **Flyway**: 덤프에 `flyway_schema_history` 포함 — 앱이 그대로 이어서 검증하므로 추가 조치 불요(V31까지 적용된 상태 기준).
