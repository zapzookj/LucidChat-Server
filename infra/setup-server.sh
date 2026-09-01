#!/usr/bin/env bash
# [2026-08-18 탈AWS] Vultr VPS 1회성 초기화 — Ubuntu 24.04 기준, root로 실행.
# 사용: 서버에 이 파일과 docker-compose.prod.yml, Caddyfile, .env(실값)를 /opt/lucid에 두고 실행.
set -euo pipefail

echo "== [1/4] Docker 설치 =="
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
docker --version && docker compose version

echo "== [2/4] 방화벽 (SSH/HTTP/HTTPS만) =="
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable
ufw status

echo "== [3/4] 디렉토리 =="
mkdir -p /opt/lucid/backups
cd /opt/lucid
ls -la

echo "== [4/4] GHCR 로그인 (프라이빗 이미지 pull용) =="
echo "  수동 1회: docker login ghcr.io -u zapzookj  (비밀번호 = read:packages 권한 PAT)"
echo ""
echo "다음 단계: docker compose -f docker-compose.prod.yml up -d  (정본: docs/20 §4)"
