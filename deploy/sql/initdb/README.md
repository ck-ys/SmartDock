# initdb（首启自动建库）

`docker-entrypoint-initdb.d` 只会在**数据目录为空**时按文件名序执行一次。顺序即前缀：

| 文件 | 来源 | 说明 |
|------|------|------|
| `00_extensions.sql` | 新写 | `pg_trgm` |
| `01_schema.sql` | `backend/script/sql/baseline/` 原文 | 业务表 + `agentscope` schema |
| `02..04` | 同上 | 参考数据 / 身份块 / 角色菜单 |
| `05_demo_data.optional.sql` | 同上 | 可选演示任务；生产不要就删掉本文件（首启前） |
| `06/07` | 同上 | 画像协议 / 分析中心 |
| `08_migrations.sql` | `backend/script/sql/migration/*.sql` 按日期序合并 | **必跑** |

## 为什么 08 必跑

批 9（2026-11-21）起 baseline 01..07 已是**全量新世界结构**（含分析中心 / 画像 /
自动化 / 记忆等全部表），本文件在全新库上预期全部幂等 no-op，仍保留两个作用：
①增量留档——表/列/索引怎么一步步长出来有据可查；②新名库重放核对——按新 baseline 建过的库可整份重放（预期幂等 no-op）；**旧名存量库（表名仍带 dcs_）不要重放本文件**，升级一律走 `2026-11-21_b9_strip_dcs_prefix.sql`。历史迁移里的 `dcs_` 前缀已随批 9 统一改写为新名（原文见
git 历史），保证「新 baseline + 本文件」重放自洽；存量旧库升级不走本文件，走
`2026-11-21_b9_strip_dcs_prefix.sql`。

## 自检

```bash
# 容器已起
../scripts/verify-schema.sh
# 或手动
docker compose exec -T postgres psql -U postgres -d smartdock -f - < ../sql/verify-schema.sql
```

`verify-schema.sql` 输出 0 行 = 120 张表（agentscope 8 + public 112）齐全。

## ✅ 实测记录（2026-11-21 批 9 重生成，本地 PG 全新库）

`00..08` 按序执行**全部成功，零冲突**（08 共 559 条语句）。`verify-schema.sql`
输出 0 行缺失：期望 120 张全部建成。表名残留检查：`dcs_` 前缀表仅白名单
`dcs_changelog` + 迁移自建的 `dcs_assistant_conversation_title_bak_*` 备份表；
`sys_menu` 权限串/组件/路由 `dcs:`/`dcs/` 残留 0；`dcs_` 序列 0。

## ✅ 实测记录（2026-11-08，本地 PG 17.10 全新库，历史留档）

`00..08` 按序执行**全部成功，零冲突**。当时基线快照缺 batch10 的 4 张表
（analytics_rebuild_log / analytics_replay_task / analytics_visitor_first_seen /
profile_event_archive），靠 08 补齐——该缺口在批 9 全量基线后已不存在。

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
