#!/usr/bin/env bash
# Builds and (re)starts Thawe Marine on the VPS. Run from anywhere in the
# checkout, as a user who can run docker and sudo:
#
#   ./deploy/deploy.sh
#
# First time: create deploy/.env from .env.example and install
# nginx-thawemarine.conf as the site config (docs/09-deployment.md).
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(dirname "$DEPLOY_DIR")"
WEB_ROOT=/var/www/thawemarine

cd "$REPO_DIR"

if [[ ! -f "$DEPLOY_DIR/.env" ]]; then
  echo "deploy/.env is missing: copy deploy/.env.example and fill it in." >&2
  exit 1
fi

echo "==> Updating the checkout"
git pull --ff-only

echo "==> Building and starting the database and backend"
docker compose -f "$DEPLOY_DIR/docker-compose.yml" up -d --build

echo "==> Building the frontend (in a Node container; nothing to install on the host)"
docker run --rm \
  -u "$(id -u):$(id -g)" \
  -e HOME=/tmp \
  -v "$REPO_DIR/frontend:/app" \
  -w /app \
  node:20-alpine \
  sh -c "npm ci --no-audit --no-fund && npm run build"

echo "==> Publishing the frontend to $WEB_ROOT"
sudo mkdir -p "$WEB_ROOT"
sudo rsync -a --delete "$REPO_DIR/frontend/dist/" "$WEB_ROOT/"

echo "==> Waiting for the backend to report healthy"
PORT="$(grep -E '^BACKEND_PORT=' "$DEPLOY_DIR/.env" | cut -d= -f2)"
PORT="${PORT:-8080}"
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$PORT/actuator/health" | grep -q '"UP"'; then
    echo "Backend is up. Open https://thawemarine.seastella.in/"
    exit 0
  fi
  sleep 3
done

echo "The backend did not report healthy within 3 minutes. Logs:" >&2
docker compose -f "$DEPLOY_DIR/docker-compose.yml" logs --tail 80 backend >&2
exit 1
