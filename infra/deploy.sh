#!/usr/bin/env bash
# [2026-08-18 탈AWS] 서버측 배포 스크립트 — CI(SSH)가 호출. /opt/lucid에서 실행.
set -euo pipefail
cd /opt/lucid

echo "== pull =="
docker compose -f docker-compose.prod.yml pull app

echo "== up =="
docker compose -f docker-compose.prod.yml up -d app

echo "== health (최대 90s) =="
for i in $(seq 1 18); do
  if docker exec lucid-app wget -qO- http://localhost:8080/health >/dev/null 2>&1; then
    echo "healthy after $((i*5))s"
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 5
done
echo "!! health check failed — 로그 확인: docker logs lucid-app --tail 100"
exit 1
