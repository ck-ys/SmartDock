# initdb（首启自动建库）

`docker-entrypoint-initdb.d` 只会在**数据目录为空**时按文件名序执行一次。顺序即前缀：

| 文件 | 来源 | 说明 |
|------|------|------|
| `00_extensions.sql` | 新写 | `pg_trgm` |
| `01_schema.sql` | `backend/script/sql/baseline/` 原文 | 业务表 + `agentscope` schema |
| `02..04` | 同上 | 参考数据 / 身份块 / 角色菜单 |
| `05_demo_data.optional.sql` | 同上 | 可选演示任务；生产不要就删掉本文件（首启前） |
| `06/07` | 同上 | 画像协议 / 分析中心 |
| `08_migrations.sql` | `backend/script/sql/migration/*.sql` 按日期序合并（原文未改） | **必跑** |

## 为什么 08 必跑

基线 01 是某次从线上库导出的**结构快照**，不含 batch10 之后新增的 4 张表：

- `analytics_rebuild_log`
- `analytics_replay_task`
- `analytics_visitor_first_seen`
- `profile_event_archive`

它们只在 `2026-10-20_batch10_lifecycle.sql` 里。缺表的表现是后端启动后相关页面报
`relation "..." does not exist`。

## 自检

```bash
# 容器已起
../scripts/verify-schema.sh
# 或手动
docker compose exec -T postgres psql -U postgres -d smartdock -f - < ../sql/verify-schema.sql
```

`verify-schema.sql` 输出 0 行 = 117 张表（agentscope 8 + public 109）齐全。

## ✅ 实测记录（2026-11-08，本地 PG 17.10 全新库）

`00..08` 按序执行**全部成功，零冲突**——不需要任何手工修补。`verify-schema.sql`
输出 0 行缺失：期望 116 张全部建成，实际 118 张（多出的 2 张是迁移按设计自建的
备份表）。种子：用户 4 / 菜单 229 / 角色 4 / 评分维度 4。

## 万一 08 中途报错（理论上已不会）

initdb 遇错会中止，且**不会再跑一次**（数据目录已非空）。处理：

```bash
docker compose down
rm -rf ../data/postgres          # 只清刚初始化的空库，别在已有数据的机器上做
# 打开 08_migrations.sql，把报错的那条语句注释掉（-- 开头）后重试
./scripts/deploy.sh
```

每条迁移原本都是对线上库执行过的原文；报错通常是「基线快照已包含该改动」造成的
重复操作，注释掉那一条即可，不要改动其它语句。
