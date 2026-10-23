# SmartDock 数据库基线

从零安装一个可运行的 SmartDock，只需 PostgreSQL 14+（基线自 PG17 导出；需要 `pg_trgm` 扩展，通常随发行版自带）。

> Windows 下用 psql 执行本目录 SQL 前，先 `set PGCLIENTENCODING=UTF8`（或 Git Bash `export`），
> 否则 psql 按 GBK 代码页读 UTF-8 文件会在中文字符上随机报编码错。

## 安装（空库一次装好）

```bash
createdb smartdock
psql -d smartdock -f 01_schema.sql      # 业务表 + agentscope 独立 schema
psql -d smartdock -f 02_reference.sql   # 框架与领域参考数据（菜单/字典/模型目录/画像维度/风控规则…）
psql -d smartdock -f 03_identity.sql    # 合成身份块（租户/部门/用户/角色/OSS/钱包）
psql -d smartdock -f 04_role_menu.sql   # 角色菜单绑定
psql -d smartdock -f 05_demo_data.optional.sql   # （可选）一条开箱演示任务；跳过即为干净空库
psql -d smartdock -f 06_profile_protocol.sql     # 画像接入协议与内置事件 Schema（可重复执行）
psql -d smartdock -f 07_analytics.sql            # 分析中心：会话/日汇总/漏斗（可重复执行）
```

## 已有库升级

基线只用于空库安装（本基线即完整安装来源，不依赖任何增量迁移）。已经在跑的库
请在备份后，按**文件名日期顺序**执行 `../migration/` 目录下的增量脚本——只跑
上次升级之后新增的那些即可（脚本幂等，全量重放也安全）：

```bash
# 示例：从未执行过任何增量的老库，按顺序执行 migration/ 下全部脚本
psql -d smartdock -f ../migration/2026-10-19_profile_protocol_v1.sql
psql -d smartdock -f ../migration/2026-10-19_profile_studio.sql
# ……依文件名顺序继续，直到目录内最后一个
```

升级脚本全部幂等，可重复执行；绝大多数只新增列/表/索引/权限。例外：
`2026-10-23_drop_external_collect.sql` 会删除外部网站接入的两张表
（`analytics_site` / `analytics_collect_log`，对应功能已于 2026-10-23 下线，
执行前请确认不再需要外部接入数据）。

## 内置账号（口令均为 `admin123`）

| 账号    | 角色         | 用途                     |
|---------|--------------|--------------------------|
| admin   | 超级管理员   | 全功能                   |
| user001~user003 | 成员   | 填报/消息/看板 + AI 助手 |

## 角色集

- `1` 超级管理员（superadmin）
- `5` 成员（member）：待办/消息/看板 + AI 助手族
- `6` 租户管理员（tenant_admin）：成员全部 + 任务管理/审核队列/用户画像
- `7` 文件审核员（file_reviewer）：文件安全审核族

## 说明

- 所有机构、人员、联系方式均为**合成演示数据**；OSS 密钥为占位符。
- AI 供应商接入（`dcs_ai_provider_access` / `dcs_ai_config`）**不含种子**——装好后在
  「AI 配置」页录入自己的 API Key；联网搜索 Key 走 `sys_config` 同样装后配置。
- 应用启动对库的额外要求：`agentscope` schema（01 内已建）。
- 本基线即完整安装来源，不依赖任何增量迁移。
