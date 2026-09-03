# 배포 선행 명령시트 — 3차 버그픽스 착수 전 (2026-09-03)

> 종원 확정: **푸시·배포 먼저**. 순서를 지켜라 — 0-2는 비가역 UPDATE의 사전 조건이고, 0-7 푸시 순서를 역전하면 유저가 '지급 대기'를 '결제 실패'로 본다.
> 서버: Vultr 서울 `141.164.37.146` · `/opt/lucid` · compose(app·postgres·caddy) · 키 `~/.ssh/lucid_deploy`

---

## 0-0. 로컬 전제 (종원이 브랜치를 되돌린 직후)

```bash
git branch --show-current && git rev-parse --short HEAD
# 기대: master / 4bc77eb
```

## 0-1 · 0-2. 프로드 실측 — **비가역 UPDATE의 사전 조건**

```bash
ssh -i ~/.ssh/lucid_deploy root@141.164.37.146 "docker compose -f /opt/lucid/docker-compose.prod.yml exec -T postgres psql -U postgres -d aichat -c \"
SELECT status, count(*) FROM character_creation_jobs
 WHERE status IN ('CONCEPT_PROCESSING','BASE_PROCESSING','EMOTIONS_PROCESSING','REVIEW_WAIT','POSTPROCESSING','BINDING')
   AND updated_at < now() - interval '30 minutes' GROUP BY 1;
SELECT user_id, count(*) FROM user_subscriptions WHERE active GROUP BY 1 HAVING count(*) > 1;
SELECT count(*) AS pending_illust, min(created_at) FROM user_illustrations WHERE status='PENDING';
\""
```

| 결과 | 판정 |
|---|---|
| 좀비 잡 두 자릿수↑ | `.env`에 `UGC_JOB_STALE_SWEEP_MINUTES=480` 넣고 배포 후 단계 하향 |
| **중복 활성 구독 1건↑** | **배포 중단.** 실유저 여부 확인 → 종원에게 보상 질의. V33·V35의 UPDATE는 되돌릴 수 없다 |
| PENDING 일러 다수 | D-2.k 일회성 정리 스크립트 대상 (24h 초과 → FAILED + 환불) |

## 0-3. V35 로컬 실기동 (인계문서가 약속했으나 기록 없음)

```bash
cd /c/Users/zapza/Desktop/MuseLab/aichat && JWT_SECRET_BASE64="$(node -e "console.log(Buffer.alloc(32,7).toString('base64'))")" ./gradlew bootRun --no-daemon --args='--server.port=8081'
```
성공 판정: `Started AichatApplication in` · 8080은 IntelliJ 점유 중이라 8081 고정.
prod는 `validate` — 구독 스냅샷 4컬럼이 없으면 **부팅 실패**한다.

## 0-4 · 0-5. 백업 + 현재 이미지 sha 기록

```bash
ssh -i ~/.ssh/lucid_deploy root@141.164.37.146 "/opt/lucid/backup.sh && docker inspect lucid-app --format '{{.Config.Image}}'"
```
deploy.sh에 사전 백업·자동 롤백이 없다. 헬스 90s 실패 시 구 컨테이너는 이미 제거된 뒤다 — sha를 손에 쥐고 시작하라.

## 0-6. `.env` 필수 시크릿 확인

```bash
ssh -i ~/.ssh/lucid_deploy root@141.164.37.146 "grep -c '^PORTONE_WEBHOOK_SECRET=' /opt/lucid/.env; grep -c '^MODELSLAB_WEBHOOK_SECRET=' /opt/lucid/.env; grep -c '^LUCID_WEBHOOK_BASE=' /opt/lucid/.env"
```
`MODELSLAB_WEBHOOK_SECRET`·`LUCID_WEBHOOK_BASE`의 실제 값은 **§6-1 ⑥ 실측 항목**이기도 하다 — D-18이 '현재 인증 없음'인지 '현재 웹훅 전량 401'인지를 가른다.

## 0-7. 푸시 — **순서 엄수: FE → Admin → BE**

```bash
cd /c/Users/zapza/Desktop/LucidChat-Front/LucidChat-Front && git push origin master
```
```bash
cd /c/Users/zapza/Desktop/LucidChat-Front/LucidChat-Admin && git push origin master
```
```bash
cd /c/Users/zapza/Desktop/MuseLab/aichat && git push origin master
```
두 프론트는 순수 가산 변경이라 구 BE와 호환된다. 역순이면 BE가 `PAID_UNDELIVERED`를 내보내는데 구 FE가 그걸 '결제 실패'로 표시해 **유저가 재구매**한다.

---

## 배포 후 즉시 실측 (§G-2 ③ 확장 + §6-1)

```bash
ssh -i ~/.ssh/lucid_deploy root@141.164.37.146 "docker compose -f /opt/lucid/docker-compose.prod.yml exec -T postgres psql -U postgres -d aichat -c \"
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='orders'::regclass AND contype='c';
SELECT indexname FROM pg_indexes WHERE indexname='uq_sub_user_active';
SELECT id, free_energy, paid_energy FROM users WHERE free_energy<0 OR paid_energy<0;
\" ; docker compose -f /opt/lucid/docker-compose.prod.yml exec -T postgres psql -U postgres -d aichat -c '\d user_subscriptions'"
```
기대: v35 · `orders_status_check` **6값** · `uq_sub_user_active` 존재 · 음수 에너지 **0건** · 스냅샷 4컬럼 존재.

### ★ D-5.6 확정 — 30초. 이것 하나가 결함 5건의 처분을 가른다

```bash
ssh -i ~/.ssh/lucid_deploy root@141.164.37.146 "docker compose -f /opt/lucid/docker-compose.prod.yml logs app --since 720h | grep -c 'PREFETCH. Done'; docker compose -f /opt/lucid/docker-compose.prod.yml logs app --since 720h | grep 'PREFETCH. Failed' | tail -20"
```

| 결과 | 판정 |
|---|---|
| Done ≈ 0 · Failed에 `could not initialize proxy` / `no Session` | **D-5.6 확정.** 배치 4가 `THEATER_PREFETCH_ENABLED=false` 1줄로 끝나고 D-5.1/5.2/5.3/5.4/5.7이 전부 '잠복'으로 재분류 |
| Done 다수 | prefetch가 살아 있다 → D-5.1~5.4 전부 **실발현**. 배치 4를 재설계해야 한다 |
| 로그 부재(배포 직후라 이력 없음) | 극장 1세션 수동 플레이 후 재측정 |

### 나머지 실측 (§6-1 ③~⑧)

```bash
ssh -i ~/.ssh/lucid_deploy root@141.164.37.146 "docker compose -f /opt/lucid/docker-compose.prod.yml exec -T postgres psql -U postgres -d aichat -c \"
SELECT id, slug, secret_eligible FROM characters WHERE source='OFFICIAL' AND secret_eligible=false;
SELECT id, slug, world_id FROM characters WHERE source='UGC' AND world_id IS NOT NULL AND story_available=true;
\""
```
④ 0건이어야 9-A′【B】 착수 가능 · ⑤ 비어 있지 않으면 9-A′【C】 전에 데이터 정리 선행.

---

## ★ 롤백 절차 (인계문서 §G-2 ②를 이것으로 교체)

인계문서는 "롤백 전 `PAID_UNDELIVERED`가 0인지 **확인**"이라 적었으나, **구 코드로는 그 행을 지급도 환불도 할 수 없다**(`markPaid`는 PENDING만, 구 `markRefunded`는 PAID만). 확인이 아니라 **소진**이다.

1. **신 코드가 도는 동안** `POST /admin/payments/orders/{uid}/redeliver` 또는 환불로 `PAID_UNDELIVERED`를 0으로 소진
2. `DROP INDEX IF EXISTS uq_sub_user_active;`
3. 이미지 태그를 0-5의 sha로 고정 후 `docker compose up -d`

> 롤백 시 500 나는 범위는 어드민 목록·감시 스캔만이 아니라 **유저의 `/payments/confirm` 재시도와 주문 이력**까지다.
> V33이 끈 중복 구독 행과 V35의 tier 덮어쓰기는 **되돌아가지 않는다.** V32·V35의 잉여 컬럼은 남겨도 무해(prod=validate).
