#!/usr/bin/env bash
# [2026-08-18 탈AWS] 일일 DB 백업 — pg_dump → gzip → R2 업로드, 로컬 14일 보관.
# cron 등록(루트): crontab -e →  20 18 * * *  /opt/lucid/backup.sh >> /opt/lucid/backups/backup.log 2>&1
#   (18:20 UTC = KST 03:20)
set -euo pipefail
cd /opt/lucid
# .env를 source하지 않는다 — OPENAI_PRO-MODEL처럼 하이픈 든 키는 bash 변수로 불가
# (docker env_file은 허용). 필요한 5키만 추출한다.
envval() { grep -E "^$1=" ./.env | head -1 | cut -d= -f2- | tr -d '\r'; }
DB_USERNAME=$(envval DB_USERNAME)
DB_NAME=$(envval DB_NAME)
AWS_ACCESS_KEY=$(envval AWS_ACCESS_KEY)
AWS_SECRET_KEY=$(envval AWS_SECRET_KEY)
S3_ENDPOINT=$(envval S3_ENDPOINT)
BACKUP_BUCKET=$(envval BACKUP_BUCKET)

TS=$(date +%F)
OUT="backups/lucidchat-${TS}.dump"

echo "== [$(date -Is)] pg_dump =="
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U "${DB_USERNAME}" -Fc "${DB_NAME}" > "${OUT}"
ls -lh "${OUT}"

echo "== R2 업로드 =="
docker run --rm -v /opt/lucid/backups:/backups \
  -e AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY}" \
  -e AWS_SECRET_ACCESS_KEY="${AWS_SECRET_KEY}" \
  amazon/aws-cli s3 cp "/backups/lucidchat-${TS}.dump" \
  "s3://${BACKUP_BUCKET}/db/lucidchat-${TS}.dump" \
  --endpoint-url "${S3_ENDPOINT}" --region auto

echo "== 로컬 14일 초과분 정리 =="
find backups -name "lucidchat-*.dump" -mtime +14 -delete
echo "== done =="
