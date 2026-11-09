#!/usr/bin/env bash
# 一键启动 SmartDock（首次会自动建库：postgres 首次初始化时执行 ../backend/script/sql/baseline/*.sql）
# 用法：cd deploy && cp .env.example .env && 改密码与两把密钥 && ./scripts/deploy.sh
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "✗ 缺少 deploy/.env —— 先执行：cp .env.example .env 并填好密码与两把加密钥匙" >&2
  exit 1
fi

# 两把钥匙必须非空且不同（EncryptKeyStartupRunner 会拒绝启动，提前拦住比等容器崩更快）
set -a; . ./.env; set +a
if [ -z "${SMARTDOCK_AI_CONFIG_KEY:-}" ] || [ -z "${SMARTDOCK_MCP_HEADERS_KEY:-}" ]; then
  echo "✗ .env 里 SMARTDOCK_AI_CONFIG_KEY / SMARTDOCK_MCP_HEADERS_KEY 不能为空" >&2
  echo "  生成：openssl rand -base64 24（各生成一次，两把必须不同）" >&2
  exit 1
fi
if [ "$SMARTDOCK_AI_CONFIG_KEY" = "$SMARTDOCK_MCP_HEADERS_KEY" ]; then
  echo "✗ 两把钥匙不能相同" >&2
  exit 1
fi

command -v docker >/dev/null || { echo "✗ 未安装 docker（见 INSTALL.md 第一步）" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "✗ 缺少 docker compose 插件（docker-compose-plugin）" >&2; exit 1; }

mkdir -p data/postgres data/redis data/minio logs/backend logs/nginx backups

# 构建产物检查：backend jar / frontend dist 不存在时提示先构建
if [ ! -f ../backend/ruoyi-admin/target/ruoyi-admin.jar ]; then
  echo "✗ 缺少 backend/ruoyi-admin/target/ruoyi-admin.jar —— 先执行 ./scripts/package.sh" >&2
  exit 1
fi
if [ ! -f ../frontend/dist/index.html ]; then
  echo "✗ 缺少 frontend/dist —— 先执行 ./scripts/package.sh" >&2
  exit 1
fi

echo "→ 构建并启动容器（首次较慢）…"
docker compose up -d --build

echo "→ 等待 backend 健康（最长 120s）…"
for i in $(seq 1 40); do
  if docker compose ps --format '{{.Service}} {{.State}}' | grep -q '^backend running'; then
    # 容器 running 不等于 Spring 就绪，探测端口
    if docker compose exec -T backend bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' 2>/dev/null; then
      echo "✓ backend 已就绪"
      break
    fi
  fi
  sleep 3
  if [ "$i" = 40 ]; then
    echo "⚠ backend 120s 内未就绪，看日志：docker compose logs -f backend" >&2
  fi
done

PORT="$(grep -E '^HTTP_PORT=' .env | cut -d= -f2)"
PORT="${PORT:-80}"
echo
echo "✓ 完成。访问 http://<服务器IP>:${PORT}/"
echo "  默认账号见 backend/script/sql/baseline/03_identity.sql（首次登录后请立即改密）"
echo "  日志：docker compose logs -f backend / frontend"
