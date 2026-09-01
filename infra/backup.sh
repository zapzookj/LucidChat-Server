#!/usr/bin/env bash
# [2026-08-18 탈AWS] 일일 DB 백업 — pg_dump → gzip → R2 업로드, 로컬 14일 보관.
# cron 등록(루트): crontab -e →  20 18 * * *  /opt/lucid/backup.sh >> /opt/lucid/backups/backup.log 2>&1
#   (18:20 UTC = KST 03:20)
set -euo pipefail
cd /opt/lucid
set -a; source ./.env; set +a

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
