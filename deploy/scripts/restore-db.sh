#!/usr/bin/env bash
# 恢复一份 custom 格式备份到 compose 里的 postgres（会先停 backend，恢复完再拉起）
# 用法：./scripts/restore-db.sh backups/xxx.dump
# 注意：目标库已有同名对象时 pg_restore 会报重复错误（--clean 会先删再建，谨慎使用）
set -euo pipefail
cd "$(dirname "$0")/.."

FILE="${1:?用法: restore-db.sh <dump 文件>}"
[ -f "$FILE" ] || { echo "✗ 文件不存在：$FILE" >&2; exit 1; }
FILE_ABS="$(cd "$(dirname "$FILE")" && pwd)/$(basename "$FILE")"

echo "⚠ 即将把 $FILE 恢复到 compose 的 smartdock 库。"
echo "  已有数据会被覆盖/冲突（脚本用 --clean --if-exists），10 秒后开始，Ctrl+C 取消"
sleep 10

docker compose stop backend

docker run --rm -i \
  --network "$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' smartdock-postgres)" \
  -e PGPASSWORD="$(grep -E '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)" \
  -v "$FILE_ABS:/in.dump:ro" \
  postgres:17-alpine \
  pg_restore -h postgres -p 5432 -U postgres -d smartdock \
             --no-owner --no-privileges --clean --if-exists /in.dump

docker compose start backend
echo "✓ 恢复完成，backend 已拉起"
