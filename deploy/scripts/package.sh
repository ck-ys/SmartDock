#!/usr/bin/env bash
# 打完整交付包：backend jar + frontend dist + deploy 配方 → deploy/release/smartdock-<日期>/
# 用法：cd deploy && ./scripts/package.sh [版本标签]
# 产物可直接 scp 到服务器，服务器上 ./scripts/deploy.sh 即可
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(cd .. && pwd)"
TAG="${1:-$(date +%Y%m%d)}"
OUT="$ROOT/deploy/release/smartdock-$TAG"

echo "→ 1/3 构建后端（跳过测试；要跑测试去掉 -DskipTests）"
mvn -f "$ROOT/backend/pom.xml" -q clean package -DskipTests

echo "→ 2/3 构建前端"
npm --prefix "$ROOT/frontend" install --no-audit --no-fund
npm --prefix "$ROOT/frontend" run build:prod

echo "→ 3/3 组装交付目录 $OUT"
rm -rf "$OUT"
mkdir -p "$OUT"

# 只带运行必需件：配方 + 产物，不带 node_modules / target / .git
rsync -a --exclude data --exclude logs --exclude backups --exclude release --exclude .env \
  "$ROOT/deploy/" "$OUT/deploy/"
mkdir -p "$OUT/backend/ruoyi-admin/target"
cp "$ROOT/backend/ruoyi-admin/target/ruoyi-admin.jar" "$OUT/backend/ruoyi-admin/target/"
mkdir -p "$OUT/frontend"
cp -r "$ROOT/frontend/dist" "$OUT/frontend/dist"
# 数据库基线仍是 backend/script/sql（compose 里以相对路径挂载）
mkdir -p "$OUT/backend/script"
rsync -a "$ROOT/backend/script/sql/" "$OUT/backend/script/sql/"

tar -C "$ROOT/deploy/release" -czf "$ROOT/deploy/release/smartdock-$TAG.tar.gz" "smartdock-$TAG"
echo "✓ 交付包：$ROOT/deploy/release/smartdock-$TAG.tar.gz"
echo "  服务器上：tar -xzf smartdock-$TAG.tar.gz && cd smartdock-$TAG/deploy && cp .env.example .env && vi .env && ./scripts/deploy.sh"
