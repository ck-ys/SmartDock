# SmartDock 部署包

照 DCS 交付目录（`D:\Git\git-se\dcs\deploy`）同款结构：**四容器 compose + 一套构建/迁移脚本 + 一份安装说明**。
与 DCS 的差异只有两处：本项目用 **PostgreSQL**（DCS 同款 pg17）、前端网关去掉 monitor / warm-flow 两段（本仓没有这两个服务）。

## 目录

```
deploy/
├── docker-compose.yml          # postgres / redis / minio / backend / frontend
├── .env.example                # 复制成 .env 后填
├── sql/
│   ├── initdb/                 # 首启自动执行：00 扩展 + 01..07 基线 + 08 迁移合并版
│   └── verify-schema.sql       # 建表自检（输出 0 行 = 117 张表齐全）
├── scripts/
│   ├── package.sh              # 打交付包（jar + dist + 配方 → release/*.tar.gz）
│   ├── deploy.sh               # 一键起（首次自动建库）
│   ├── verify-schema.sh        # 建表 + 种子自检
│   ├── dump-db.sh              # 备份运行中的库
│   ├── export-remote-db.sh     # 从任意可达 PG 导出（跨机迁移用）
│   ├── dump-data.js            # 无 pg_dump 时的数据导出（只导数据）
│   └── restore-db.sh           # 恢复备份
├── backend/Dockerfile          # JDK21 + GMT+8 + exec 顶 PID1
├── frontend/{Dockerfile,nginx.conf}
└── data/ logs/ backups/        # 运行时生成，已 gitignore
```

## 快速开始（云服务器）

```bash
# 0) 服务器装 docker + compose 插件（已有可跳过）
curl -fsSL https://get.docker.com | sh

# 1) 上传并解包交付包（本机先 ./scripts/package.sh 打出 tar.gz）
tar -xzf smartdock-<日期>.tar.gz && cd smartdock-<日期>/deploy

# 2) 配置
cp .env.example .env
vi .env          # 改 POSTGRES_PASSWORD / REDIS_PASSWORD / 两把 SMARTDOCK_* 钥匙

# 3) 起服务
./scripts/deploy.sh
```

首次启动 postgres 会自动执行 `sql/initdb/` 下全部 SQL（按文件名序）：

| 文件 | 内容 |
|------|------|
| `00_extensions.sql` | `pg_trgm` |
| `01..04` | 业务表 + agentscope schema、菜单/字典/模型目录、租户/部门/用户、角色菜单 |
| `05_demo_data.optional.sql` | 可选：一条开箱演示任务（生产不要就首启前移走） |
| `06/07` | 画像接入协议、分析中心 |
| `08_migrations.sql` | **`backend/script/sql/migration/` 全部 59 个脚本按日期序合并** |

> ⚠ `08` 不能省：基线是某次导出的结构快照，**不含 batch10 之后新增的 4 张表**
> （`analytics_rebuild_log` / `analytics_replay_task` / `analytics_visitor_first_seen` /
> `profile_event_archive`），只跑 01..07 时后端 Mapper 会报 relation does not exist。
>
> **✅ 已活体验证（2026-11-08，本地 PG 17.10）**：全新空库按序执行 `00..08` 十个文件
> 全部成功、零冲突；`verify-schema.sql` 输出 0 行缺失（期望 116 张全在，实际 118 张，
> 多出的 2 张是迁移自建的备份表）；种子齐全（用户 4 / 菜单 229 / 角色 4 / 维度 4）。
> 装完执行 `./scripts/verify-schema.sh` 复核即可。

### 没有旧库备份时，表怎么来（重建路）

**结构全部来自仓库历史**：`01..07` 基线 + `08` 迁移原文，不依赖线上库；`08` 里的每条
都是当年对线上库执行过的原文，`BEGIN/COMMIT` 配平、无 psql 元命令、`CREATE/DROP` 均带
`IF NOT EXISTS`，可整份 `psql -f`。
**数据**只有两类能回来：仓库种子（`05` + `backend/script/demo/seed-*.js`）与用户自己重录；
业务数据（历史会话/事件/画像快照）没有备份就不可恢复。

## 从旧库迁移数据（两种路）

### A. 旧库还在跑 → 直接导全量备份（推荐）

任意一台能同时访问旧库和装有 docker 的机器上：

```bash
PGPASSWORD=<旧库密码> ./scripts/export-remote-db.sh <旧库IP> <端口> smartdock_shots postgres
# → backups/smartdock_shots-<时间>.dump

# 服务器上：先起空库，再恢复（会覆盖同名对象）
./scripts/deploy.sh
./scripts/restore-db.sh backups/smartdock_shots-<时间>.dump
```

### B. 旧库已经不在 → 用仓库基线 + 演示种子重建

```bash
# 基线（deploy.sh 已自动执行）
# 演示数据（内部协作故事，uid 前缀 demo-io-）：
node backend/script/demo/seed-internal-ops-demo.js --days 30
# 然后在「数据分析 → 重建汇总」点一次，口径回到演示基线
```

> 没有全量备份时，**AI 模型配置里的 api_key 无法恢复**（库里存的是密文，且用旧的两把钥匙加密）。
> 到「AI 助手管理 → 模型与钥匙」重新填一次即可；同理 MCP 请求头令牌要重填。

## 备份与恢复（日常）

```bash
./scripts/dump-db.sh                       # → backups/smartdock-<时间>.dump
./scripts/restore-db.sh backups/xxx.dump   # 停 backend → pg_restore --clean → 拉起
```

建议加一条 crontab：`0 3 * * * cd /opt/smartdock/deploy && ./scripts/dump-db.sh`。

## 升级

```bash
# 新版本交付包解到别处后：
docker compose stop backend
cp <新包>/backend/ruoyi-admin/target/ruoyi-admin.jar ../backend/ruoyi-admin/target/
cp -r <新包>/frontend/dist/* ../frontend/dist/
docker compose up -d --build backend frontend
```
数据库增量脚本在 `backend/script/sql/migration/`，按文件名日期序在库上执行（全部幂等）。

## 排错

| 现象 | 原因 / 处理 |
|------|-------------|
| backend 反复重启，日志报加密钥匙 | `.env` 里两把钥匙为空/相同/长度不是 16·24·32；重新生成 |
| 页面能开、接口 502 | backend 未就绪或崩了：`docker compose logs -f backend` |
| 登录验证码不显示 | 反向代理把 `/prod-api/code` 拦了；本 nginx 已放行，检查外层代理 |
| 中文乱码 | 时区已固定 `Asia/Shanghai` + `-Duser.timezone=GMT+8`；数据库用 `--encoding=UTF8` 初始化 |
| 附件打不开 | MinIO 的 `sys_oss_config.endpoint` 不能带 `http://` 前缀（框架会再拼一次） |
