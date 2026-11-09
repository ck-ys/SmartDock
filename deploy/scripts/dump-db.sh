#!/usr/bin/env bash
# 备份「正在运行的 compose 库」（日常运维用；跨机迁移用 export-remote-db.sh）
# 用法：./scripts/dump-db.sh [outfile]
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-backups/smartdock-$(date +%Y%m%d-%H%M).dump}"
mkdir -p "$(dirname "$OUT")"

docker compose exec -T postgres pg_dump -U postgres -d smartdock \
  --no-owner --no-privileges -Fc > "$OUT"

echo "✓ 备份完成：$(du -h "$OUT" | cut -f1)  $OUT"
