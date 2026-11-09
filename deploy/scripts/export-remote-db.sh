#!/usr/bin/env bash
# 从「任意可达的 PostgreSQL」导出一份可移植的全量备份（custom 格式，含 schema+数据+序列）。
# 场景：把开发机/内网库搬到云服务器；本机没有 pg_dump 时也没关系——用 postgres:17 官方镜像跑。
#
# 用法：
#   ./scripts/export-remote-db.sh <host> <port> <db> [user] [outfile]
# 例：
#   PGPASSWORD=<口令> ./scripts/export-remote-db.sh <数据库主机> <端口> smartdock_shots postgres
#   → deploy/backups/smartdock_shots-20261107-HHMM.dump
#
# 恢复（云服务器）：
#   ./scripts/restore-db.sh deploy/backups/xxx.dump
set -euo pipefail

cd "$(dirname "$0")/.."

HOST="${1:?用法: export-remote-db.sh <host> <port> <db> [user] [outfile]}"
PORT="${2:?缺少端口}"
DB="${3:?缺少库名}"
USER_NAME="${4:-postgres}"
OUT="${5:-backups/$DB-$(date +%Y%m%d-%H%M).dump}"

mkdir -p "$(dirname "$OUT")"
OUT_ABS="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"

echo "→ 导出 $USER_NAME@$HOST:$PORT/$DB → $OUT_ABS"
docker run --rm -i \
  -e PGPASSWORD="${PGPASSWORD:-}" \
  -v "$OUT_ABS:/out.dump" \
  postgres:17-alpine \
  pg_dump -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB" \
          --no-owner --no-privileges -Fc -f /out.dump

echo "✓ 完成：$(du -h "$OUT_ABS" | cut -f1)  $OUT_ABS"
echo "  校验清单：docker run --rm -i -v \"$OUT_ABS:/out.dump\" postgres:17-alpine pg_restore -l /out.dump | head"
