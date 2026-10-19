# SmartDock — 用户画像 + AI 助手双引擎参考实现

![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-success.svg)
![JDK](https://img.shields.io/badge/JDK-21-green.svg)
![Vue](https://img.shields.io/badge/Vue-3%20%2B%20TS-42b883.svg)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-14%2B-336791.svg)
![Tests](https://img.shields.io/badge/tests-447%20%2B%20200-brightgreen.svg)

SmartDock 围绕两个可独立复用的引擎构建：**用户画像引擎**（事件源架构的维度评分系统）与
**AI 助手**（带工具循环、技能库与完整安全防线的流式对话助手）。为了让两个引擎开箱即可运行、
且接得进真实业务，项目内置了一个**多任务数据采集 demo**（任务 → 填报 → 审核 → 通知微核心），
它既是画像引擎的事件源样例，也是 AI 助手的业务工具样例。

> 设计理念：引擎的难点从来不是算法，而是「接得进来、用得放心、看得明白」——
> 每个判定都有注释说明「为什么」，每个引擎都有回归测试锁住行为，整套系统可用一套基线 SQL 从空库拉起。

## 目录

- [引擎一：用户画像](#引擎一用户画像)
- [引擎二：AI 助手](#引擎二ai-助手)
- [内置 demo：多任务数据采集](#内置-demo多任务数据采集)
- [平台与配套能力](#平台与配套能力)
- [与通用脚手架的差异](#与通用脚手架的差异)
- [技术栈](#技术栈)
- [架构图](#架构图)
- [快速开始](#快速开始)
- [内置账号](#内置账号)
- [仓库布局](#仓库布局)
- [测试](#测试)
- [文档导航](#文档导航)
- [如何参与贡献](#如何参与贡献)
- [许可](#许可)

## 引擎一：用户画像

给任意业务主体（人/部门/设备……）持续打分，答案只有三张表：**事件进、规则算、快照出**。

### 事件源架构

- 一切业务动作先落 `profile_event`：`source`（事件来自哪个系统）+ `eventType`（发生了什么）+
  `主体`（给谁记）+ `属性`（JSON，携带数值/时长等计量字段）。
- 引擎**不关心事件来自哪里**——demo 微核心的任务发布/提交/审核动作是现成的接入样例；
  接你自己的系统只需发出同构事件，评分链路零改动。
- 标准事件保留天数可配（事件是原料可老化），快照是账本永不清理。

### 维度规则评分

- 每个维度一条规则，`EVENT_AGG` 聚合模式：分子/分母事件类型（RATE）、属性均值（AVG）、
  计数（COUNT）× 全窗口 / 滚动窗口（如近 30 天）× 线性/分桶打分方向。
- 内置四个维度可直接参考改写：**完成率**（通过÷发布）、**响应时效**（平均响应小时数反向）、
  **填报质量**（退回率反向）、**负面指标**（近 30 天退回次数分档）。
- 维度配置存 `profile_score_dimension`（JSON 规则体），租户可自定义增改，`enabled` 一键启停。

### 快照与展示

- 每轮评分落 `profile_score_snapshot`：各维度最新得分 + 历史走势。
- 「用户画像」页按事件源分页列出主体（事件数/首末事件时间），进详情看各维度得分与走势曲线。
- 数据来自引擎对任务事件的持续评分，无需人工维护。

### 复用方式

把 `ruoyi-profile-engine` 模块整体摘走 + 你的系统发事件，画像即接即用；
demo 微核心展示了最小接入面：业务动作 → 适配器 → `profile_event`，三步。

## 引擎二：AI 助手

一个敢放进生产对话场景的助手骨架：**会调工具、按角色裁剪、出网先脱敏、花钱有账本**。

### 流式对话

- SSE 流式输出，前端按 AGUI 协议渲染：思考中 / 执行中（带正在查询什么）/ 撰写中 / 等确认 / 完成。
- 会话级参数不丢参：思考档位、模型固定、联网开关、选中技能随轮次透传。

### 工具循环（Tool Loop)

- **平台工具**：注册表统一管理（待办查询、任务进度、记忆读写、联网搜索等），配置与角色双重门控。
- **业务工具 SPI**：实现 `BizToolProvider` 即可注册业务工具（demo 微核心注册了查待办/查进度/
  催办/按部门汇总等），工具描述与权限随 SPI 声明，主仓零侵入。
- **权限分层**：角色档位（member/publisher/admin）+ 权限串 + 通配三态（null 拒 / `*:*:*` 放行 /
  精确匹配）；低于档位的工具对模型**不可见**——不是置灰，是压根不进 schema。
- 写操作走 HITL 确认卡：模型只能起草，执行必须用户点确认。

### 角色与上下文感知

- 按登录身份（成员/发布人/管理员）裁剪：工具面、提示词纪律段（成员不背催办条款）、建议卡池
  （成员面彻底无催办词）、快捷命令（`/催办` 仅发布人可见）。
- 页面感知：悬浮球建议随当前页面（任务管理/我的待办/统计看板/通知中心……）切换。

### 技能库（渐进披露）

- Claude Code 式省 token 设计：系统提示词只注入**技能目录**（id+名称+一句话描述，有字符预算），
  正文仅在用户选中或模型调用 `view_skill` 时注入，目录与正文永不同时常驻。
- 三级加载：外部 `config/prompts/skills/`（部署期热覆写）→ classpath → 内嵌常量，同源护栏单测锁一致。

### 安全四件套

1. **PII 脱敏**：出网前对姓名（租户姓名字典，长名优先）/手机/证件/邮箱四要素掩码，
   抗全角数字与空格/连字符间隔变体，掩码打坏 JSON 数字字面量时自动修复；
2. **注入防御**：工具返回内容一律视为数据，命中注入特征打标并拒绝执行；
3. **输入闸**：超长输入直接拦截，不进模型；
4. **记忆脱敏**：长期记忆写入前过同一套过滤器。

### 计费与配额（ai-billing）

- 租户钱包 / 积分套餐（每周额度 + 整周期总额）/ 模型价格目录（按 token 输入/缓存/输出三价）。
- 调用按 token 计费入账本，配额闸先软后硬；用量查询页可看额度、次数与缓存明细。
- 管理员在「AI 配置」录入各供应商 base_url + API Key（OpenAI 兼容），多供应商优先级链自动回退。

## 内置 demo：多任务数据采集

一条刻意做小的业务主线，给两个引擎供血，也可整体当作采集功能直接使用：

| 环节 | 说明 |
|---|---|
| 任务管理 | 发布人建任务：动态字段（文本/数字/日期/单选/多选/附件）、指派成员；草稿→发布→关闭状态机 |
| 填报 | 成员按字段定义渲染表单（必填校验）；版本化提交：被退回可修订重提，历史全保留 |
| 审核 | 发布人直审或指定审核人；通过/退回（退回必填理由）；时间线回放历次决定 |
| 通知 | 站内通知（新任务/审核结果/催办），dedup_key 幂等，顶栏铃铛最近 8 条 + 未读数 |
| 统计 | 双视角看板：发布人（草稿/收集中/已关闭+待审队列）与成员（待办/待审/已通过/被退回） |

它的每个动作都会发画像事件、注册助手工具——读它就懂两个引擎「怎么接」。

## 平台与配套能力

| 能力 | 说明 |
|---|---|
| 文件安全 | OSS 上传事件监听 → 守卫规则（宏/伪装/敏感词）→ 人工审核队列 → 退回通知；白名单与类目豁免 |
| 风控 | 风险事件/规则引擎/白名单/行动留痕 + 验证码挑战 |
| 基座平台件 | 多租户（套餐/到期/账号数）、用户/部门/角色/菜单/字典/参数、操作与登录日志、OSS 配置、在线用户、接口文档（SpringDoc）、服务监控、SnailJob 调度 |

## 与通用脚手架的差异

| 维度 | 通用脚手架 | SmartDock |
|---|---|---|
| 定位 | 空壳起点，业务自己填 | 两个可整体摘走复用的引擎 + 一个供血 demo，边读边学 |
| 引擎可复用性 | 无 | 画像引擎只依赖事件流；助手业务工具走 SPI——摘除 demo 不伤引擎，摘除引擎不伤平台 |
| 工程注释 | API 用法说明 | 大量「判定序」注释：先查什么、再拦什么、为什么这个顺序 |
| 回归防线 | 示例代码 | 后端 447 + 前端 200 用例锁行为；权限矩阵、状态机、脱敏、注入防御均有专项测试 |
| 数据库 | 需自行拼装 | `backend/script/sql/baseline/` 一套基线从空库拉起（79 表 + 菜单 + 演示账号 + 可选演示数据） |
| 部署 | 文字描述 | `deploy/` compose 一键（postgres + redis + minio + backend + frontend） |

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.5 · Sa-Token · MyBatis-Plus · Redisson · PostgreSQL 14+ |
| 前端 | Vue 3 · TypeScript · Element Plus · Vite（[frontend/](frontend/README.md)） |
| 基座 | RuoYi-Vue-Plus 5.6.1（MIT，多租户/权限/字典/代码生成等框架件） |

## 架构图

```
                      ┌──────────────────────────────────────┐
                      │      frontend  Vue3 + ElementPlus    │
                      │   AI 助手 · 用户画像 · demo 任务中心  │
                      └────────────────┬─────────────────────┘
                                       │ REST + SSE  (/dev-api)
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                          ruoyi-admin  ·  Spring Boot 3.5                       │
│                                                                                │
│  ┌───────── 引擎一 · 画像 profile-engine ─────────┐  ┌────── 引擎二 ───────┐ │
│  │ profile_event → 维度规则评分 → score_snapshot  │  │ assistant           │ │
│  │        （事件进 · 规则算 · 快照出）             │  │ SSE · 工具循环 ·     │ │
│  └──────────────────▲───────────────────────────┘  │ 技能 · 脱敏 · 计费   │ │
│                     │ 业务事件                       └──────────▲──────────┘ │
│  ┌──────────────────┴─────────────────────────────┐        工具 SPI         │
│  │   demo 业务 ruoyi-dcs：任务→填报→审核→通知     │───────────────────────┘ │
│  │   （多任务数据采集，画像事件源 + 助手工具样例）  │                          │
│  └────────────────────────────────────────────────┘                          │
│  ┌────────────── 平台件（RuoYi-Vue-Plus 基座）─────┐                          │
│  │  多租户 · 权限(Sa-Token) · 字典 · OSS · 风控 · 监控 │                       │
│  └────────────────────────────────────────────────┘                          │
└───────┬──────────────────────┬──────────────────────────┬────────────────────┘
        ▼                      ▼                          ▼
   PostgreSQL 14+           Redis 7                    MinIO / S3
   业务 + 画像 + 账本        会话 / 缓存 / 限流          附件对象存储
```

## 快速开始

```bash
# 0. 依赖：PostgreSQL 14+ 与 Redis 7（后端 dev 配置默认连 127.0.0.1:5432 / 6379）
docker run -d --name smartdock-redis -p 6379:6379 redis:7

# 1. 数据库（空库一次装好，见 backend/script/sql/baseline/README.md）
export PGCLIENTENCODING=UTF8   # Windows/Git Bash 中文环境必设
createdb smartdock
psql -d smartdock -f backend/script/sql/baseline/01_schema.sql
psql -d smartdock -f backend/script/sql/baseline/02_reference.sql
psql -d smartdock -f backend/script/sql/baseline/03_identity.sql
psql -d smartdock -f backend/script/sql/baseline/04_role_menu.sql
psql -d smartdock -f backend/script/sql/baseline/05_demo_data.optional.sql   # 可选：一条演示任务

# 2. 后端（默认 dev profile 连 127.0.0.1:5432/smartdock，可用启动参数覆盖）
cd backend && mvn package -DskipTests
java -Duser.timezone=GMT+8 -jar ruoyi-admin/target/ruoyi-admin.jar --server.port=8090

# 3. 前端
cd frontend && npm i && npm run dev   # http://localhost:5188
```

## 内置账号

口令均为 `admin123`。

| 账号 | 角色 | 说明 |
|---|---|---|
| `admin` | 超级管理员 | 全部菜单与配置（AI 供应商 Key 在「AI 配置」录入） |
| `user001` ~ `user003` | 成员（张三/李四/王五） | 待办/消息/看板 + AI 助手，userId 为 `9100000001` ~ `9100000003` |

角色集：超级管理员（superadmin）/ 成员（member）/ 租户管理员（tenant_admin）/ 文件审核员（file_reviewer），
权限串与菜单绑定见 [docs/architecture.md](docs/architecture.md)。

## 仓库布局

```
backend/
  ruoyi-modules/
    ruoyi-profile-engine/ # 引擎一：画像（事件源 + 维度评分 + 快照）
    ruoyi-assistant/      # 引擎二：AI 助手（SSE 对话 + 工具 SPI + 技能资产）
    ruoyi-dcs/            # 内置 demo：任务/提交/审核/通知/统计
    ruoyi-file-security/  # 文件安全：守卫 + 审核
    ruoyi-risk/           # 风控：事件/规则/行动
    ruoyi-ai-billing/     # 计费：钱包/积分/模型目录
  script/sql/baseline/    # 单一基线 SQL（空库一次装好）
frontend/
deploy/                   # docker-compose + 双 Dockerfile + nginx
docs/                     # 架构 / 使用 / 部署 三套文档
```

## 测试

```bash
cd backend && mvn test -DskipTests=false   # 447 用例（画像规则/助手工具与脱敏/demo 状态机）
cd frontend && npm test                    # 200 用例（vitest）
```

## 文档导航

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 模块划分、权限串、事件流、状态机与判定序 |
| [docs/usage.md](docs/usage.md) | 按角色的一天走读全部功能入口 |
| [docs/deploy.md](docs/deploy.md) | 裸机与容器部署细节、参数覆盖 |
| [backend/script/sql/baseline/README.md](backend/script/sql/baseline/README.md) | 基线 SQL 说明、内置账号、Windows 编码坑 |
| [frontend/README.md](frontend/README.md) | 前端开发、测试与构建 |

## 如何参与贡献

1. Fork 仓库并拉取 feature 分支；
2. 改动请附测试（后端 JUnit / 前端 vitest，跑通全量用例再提交）；
3. 提交信息用 `type: 摘要` 风格（feat / fix / docs / refactor / test）；
4. 发起 Pull Request 并说明动机与影响面。

## 许可

Apache-2.0（见 [LICENSE](LICENSE)）。基于 RuoYi-Vue-Plus（MIT）衍生，署名见 [NOTICE](NOTICE)。
