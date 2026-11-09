#!/usr/bin/env bash
# 建表自检：期望 117 张表（含 agentscope 8 张）都建出来了没有
# 用法：./scripts/verify-schema.sh           # 跑 compose 里的 postgres
#      ./scripts/verify-schema.sh <dump容器外的连接>   # 暂不支持，跨机请手动 psql -f ../sql/verify-schema.sql
set -euo pipefail
cd "$(dirname "$0")/.."

echo "→ 期望表是否齐全（输出 0 行 = 齐全）"
docker compose exec -T postgres psql -U postgres -d smartdock -v ON_ERROR_STOP=1 \
  -f - < sql/verify-schema.sql

echo
echo "→ 实际表数（public + agentscope）"
docker compose exec -T postgres psql -U postgres -d smartdock -tA -c \
  "select table_schema||' '||count(*) from information_schema.tables
    where table_schema in ('public','agentscope') and table_type='BASE TABLE'
    group by 1 order by 1"

echo
echo "→ 关键种子数据是否在（应为 1 行/有数）"
docker compose exec -T postgres psql -U postgres -d smartdock -c \
  "select (select count(*) from sys_user) as users,
          (select count(*) from sys_menu) as menus,
          (select count(*) from sys_role) as roles,
          (select count(*) from profile_score_dimension) as dimensions"
