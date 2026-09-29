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
COMPOSE=(docker compose -f "$DEPLOY_DIR/docker-compose.yml")

cd "$REPO_DIR"

if [[ ! -f "$DEPLOY_DIR/.env" ]]; then
  echo "deploy/.env is missing: copy deploy/.env.example and fill it in." >&2
  exit 1
fi

# The loopback port the backend listens on. nginx is pointed at the same one.
PORT="$(grep -E '^BACKEND_PORT=' "$DEPLOY_DIR/.env" | cut -d= -f2 | tr -d '[:space:]')"
PORT="${PORT:-8080}"

echo "==> Updating the checkout"
git pull --ff-only

# Another program on the backend's port would take its traffic: the VPS is
# shared, so check before starting rather than find out from a broken sign-in.
OWNER="$(ss -ltnpH "( sport = :$PORT )" 2>/dev/null | grep -v docker-proxy || true)"
if [[ -n "$OWNER" ]] && ! "${COMPOSE[@]}" ps --status running backend 2>/dev/null | grep -q backend; then
  echo "Port $PORT is already used by another program on this server:" >&2
  echo "$OWNER" >&2
  echo "Choose a free port for BACKEND_PORT in deploy/.env (e.g. 8085) and run this again." >&2
  exit 1
fi

echo "==> Building and starting the database and backend (port $PORT)"
"${COMPOSE[@]}" up -d --build

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

echo "==> Updating the nginx site"
NGINX_SITE=/etc/nginx/sites-available/thawemarine
RENDERED="$(mktemp)"
sed "s/127\.0\.0\.1:8080/127.0.0.1:$PORT/g" "$DEPLOY_DIR/nginx-thawemarine.conf" > "$RENDERED"
if ! sudo cmp -s "$RENDERED" "$NGINX_SITE"; then
  sudo cp "$NGINX_SITE" "$NGINX_SITE.previous" 2>/dev/null || true
  sudo cp "$RENDERED" "$NGINX_SITE"
  sudo ln -sf "$NGINX_SITE" /etc/nginx/sites-enabled/thawemarine
  if sudo nginx -t; then
    sudo systemctl reload nginx
  else
    echo "The new nginx config was rejected; the previous one is back in place." >&2
    sudo cp "$NGINX_SITE.previous" "$NGINX_SITE"
    rm -f "$RENDERED"
    exit 1
  fi
fi
rm -f "$RENDERED"

# Healthy means *our* backend answers: the sign-in endpoint replies with this
# application's own error shape. Any program answering /actuator/health would
# pass a health check alone.
echo "==> Waiting for the backend to report healthy"
for _ in $(seq 1 60); do
  if "${COMPOSE[@]}" ps --status running backend 2>/dev/null | grep -q backend \
     && curl -fs "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null | grep -q '"UP"' \
     && curl -s -X POST -H 'X-Requested-With: SeaStella' "http://127.0.0.1:$PORT/api/v1/auth/refresh" 2>/dev/null \
          | grep -q '"code"'; then
    echo "Backend is up. Open https://thawemarine.seastella.in/"
    exit 0
  fi
  sleep 3
done

echo "The backend did not start within 3 minutes. Logs:" >&2
"${COMPOSE[@]}" ps >&2
"${COMPOSE[@]}" logs --tail 80 backend >&2
exit 1
