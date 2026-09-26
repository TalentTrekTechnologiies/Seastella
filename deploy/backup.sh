#!/usr/bin/env bash
# Nightly backup of Thawe Marine: the database and the uploaded files.
# Keeps 30 days in /var/backups/thawemarine. Install once, as root:
#
#   echo '30 2 * * * root /opt/thawemarine/deploy/backup.sh >> /var/log/thawemarine-backup.log 2>&1' \
#     > /etc/cron.d/thawemarine-backup
#
# These copies are on the same VPS, so they protect against mistakes, not
# against losing the server: copy the folder off the VPS as well (docs/09 s4).
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE=(docker compose -f "$DEPLOY_DIR/docker-compose.yml")
OUT=/var/backups/thawemarine
STAMP="$(date +%F-%H%M)"
DB_USER="$(grep -E '^DB_USERNAME=' "$DEPLOY_DIR/.env" | cut -d= -f2)"

mkdir -p "$OUT"
chmod 700 "$OUT"

echo "$(date -Is) database"
"${COMPOSE[@]}" exec -T db pg_dump -U "$DB_USER" --format=custom --no-owner --no-privileges thawemarine \
  > "$OUT/db-$STAMP.dump"

echo "$(date -Is) uploads"
"${COMPOSE[@]}" exec -T backend tar -C /var/seastella/documents -czf - . > "$OUT/uploads-$STAMP.tar.gz"

find "$OUT" -type f -mtime +30 -delete
echo "$(date -Is) done: $(du -sh "$OUT" | cut -f1) in $OUT"
