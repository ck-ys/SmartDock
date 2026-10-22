# AGENTS.md

这份文件约束 AI 编程代理在 smart-dock 项目里的工作方式，用来减少常见的 AI 编程失误。设计大范围参照内部母仓的同名文件（母仓定位与只读使用规则只记录在 `.workshop/` 内部资料，绝不写入 git，绝不动一个字），并按开源版定位改写。可在此基础上继续补充项目专属规则。
取舍说明：这些规则偏向谨慎和可验证，而非最快完成。遇到很小的简单任务时，可按实际情况简化流程。

## 启动 Checklist（任何新会话 / 切换模型开始前必跑）

1. 读本文件 AGENTS.md；改后端再读 `backend/AGENTS.md`，改前端再读 `frontend/AGENTS.md`
2. 读 `.workshop/plans/` 文件名内嵌日期最新的一组计划（同日多份都读；`ls -t .workshop/plans/ | head` 辅助定位）；二期权威计划是 `phase2-development-plan.html`
3. `git log --oneline -10` 对齐最近锚点——完成度一律以 git log 为准，禁止凭旧文档/旧快照推断当前状态
4. 读 `.workshop/logs/phase2-profile-analytics.md` 尾部两节（最近做了什么 / 下一步）
5. grep 验证当前阶段要动的方法 / 文件确实存在
6. 报告："已读完 X，准备做 Y，已确认 Z 存在"

---

## 工作区目录结构

```
D:\Git\git-gitee\smart-dock\   # 单仓：前端 + 后端（gitee 开源）
├── backend/                  # 后端 (Spring Boot 3.5 + Java 21 + PostgreSQL 17, RuoYi-Vue-Plus 5.6.1 底座)
│   └── AGENTS.md             # 后端详细规则 ← 改后端前必读
├── frontend/                 # 前端 (Vue 3 + Vite + Element Plus + TypeScript, Node>=20.19)
│   └── AGENTS.md             # 前端详细规则 ← 改前端前必读
├── .workshop/                # 施工会话专属工作区（整体 gitignore，内部资料永不进 git）
│   ├── plans/                #   计划 HTML/MD、拍板页、验收提示词、设计文档
│   ├── logs/                 #   人话施工日志（做了什么/为什么/怎么验证的）
│   ├── tmp/                  #   临时脚本/工程/命令日志——删此目录即全净
│   ├── scripts/              #   常驻运维脚本（启停/打包/重置）
│   ├── evidence/  shots/     #   证据截图 / 演示数据种子
│   └── BLUEPRINT.md / BOOT_PROMPT.md / HANDOFF_PROMPT.md ...  # 历史施工资料
├── docs/                     # 对外文档（README 体系、架构/使用/部署）
└── README.md
```

---

## 产物归置铁律（用户明确要求，违反即返工）

**一切 agent 产物只能放在本仓 `.workshop/` 下的专属子目录，禁止到处乱放。**

| 产物类型 | 规范路径 | 说明 |
|---------|---------|------|
| 计划 HTML / 计划 MD / 拍板页 / 验收提示词 / 设计文档 / 决策记录 | `.workshop/plans/` | gitignored，不提交 |
| 人话施工日志 | `.workshop/logs/` | 每批收口必追加 |
| 临时脚本、临时工程（node/playwright 等）、命令日志、验证输出 | `.workshop/tmp/` | 命名带日期+用途，用完即删 |
| 常驻运维脚本（启停/打包/重置） | `.workshop/scripts/` | |
| 证据截图、对账快照 | `.workshop/evidence/` | |
| 演示数据种子 | `.workshop/shots/` | |
| 会话可视化 HTML（拍板页/前后对比图） | **双写**：会话可视化目录（渲染中转）+ `.workshop/plans/`（最终版） | 会话目录随时可能失效，仓内副本才是权威 |
| Playwright `take_screenshot` 默认输出 | `frontend/.playwright-mcp/` | gitignored，不提交 |

### 禁止行为

- ❌ 写系统 `/tmp`（含 `C:\Users\<user>\AppData\Local\Temp`）、家目录、其它盘根、仓外任意位置
- ❌ 产物平铺在仓库根或 `.workshop/` 根——新类型产物没有对应目录时，在 `.workshop/` 下**新建专门子目录**
- ❌ 把 `.workshop/` 内部资料提交进 git
- ❌ 拍板页只留在会话可视化目录、不归档仓内
- ❌ 把内网 IP、密码、真实人名/公司/校名/邮箱写进任何 git 追踪文件（含本文件）

---

## 开始任何工作前：必须先读

1. **业务定位** → `README.md`（画像 + AI 助手双引擎，多任务采集仅作内置 demo）
2. **当前阶段计划** → `.workshop/plans/` 最新一组（二期权威：`phase2-development-plan.html`）
3. **改后端** → `backend/AGENTS.md`
4. **改前端** → `frontend/AGENTS.md`
5. **命令/环境/验证口径** → `.workshop/plans/acceptance-prompt-*.md`（含工具降级五件套与服务启停；连接串等敏感信息只在 .workshop 内，永不进 git）

---

## 项目简介

**SmartDock** — 内部数据采集系统的开源改造版，基于 RuoYi-Vue-Plus 5.6.1。
定位：**用户画像 + AI 助手双引擎**的通用平台；原多级任务转发采集退为内置 demo 能力。

核心子系统：
- **画像引擎**（`ruoyi-profile-engine` 的 `com.smartdock.profile`）：通用事件协议 v1 → 身份图谱 → 评分模型/RFM/分群/低分复核，快照链可追溯
- **行为分析中心**（`com.smartdock.analytics`）：会话重建、日汇总、流量/页面/漏斗/留存/自定义分析、CSV 补录、公开采集口 + 零依赖 SDK
- **AI 助手**（`ruoyi-assistant` + `ruoyi-assistant-spi`）：AgentScope 管主链，Spring AI 只管后台任务
- **多任务采集 demo**（内置 demo 模块）：保留任务/待办/审计最小闭环

技术栈：Spring Boot 3.5 / Java 21 / PostgreSQL 17 / MyBatis-Plus / Sa-Token / Redisson + Vue 3 / Vite / Element Plus / Pinia / TypeScript。

## SmartDock 领域概念速查（防新会话重蹈覆辙）

> 改画像/分析/采集前先过一遍。口径错了，统计数字全错。

| 概念 | 一句话口径 |
|------|-----------|
| 事件信封 | `ProfileEventBo`：eventName/eventUid/主体/会话/上下文/属性；一切业务喂事件只走 `ProfileEventPublisher` 门面 |
| eventUid 幂等 | (tenant, source, event_uid) 重复投递返回 false 不报错；事件账本 append-only，重放可补齐 |
| 身份图谱 | 匿名 ID ↔ 登录主体经 `resolveAndLink` 归并；共享浏览器换账号只改挂匿名链接，**绝不搬走对方身份**；分析优先认登录人 |
| 会话口径 | 显式 sessionId 优先，否则 30 分钟无操作切分；隐式会话键**锚定首事件**；新访客=该源首次会话；跳出=单页会话（有搜索不算跳出；page_leave 不算"继续"） |
| 评分模型 | 指标→分箱→权重→等级，整份 JSON 配置；每次计算进快照，`prev_snapshot_id` 串趋势链；RFM 只是内置预设 |
| 分群 | 规则分群（条件树+预览）与静态名单（CSV/逐行）两种；成员快照可追溯 |
| 低分复核 | 低于阈值进队列；确认/忽略后除非分数继续下降，不重复打扰 |
| 采集口 | `POST /analytics/collect` 公开免登；siteKey→租户/业务源；单批 ≤200；限流 300/分/IP；siteKey 不授予管理权限 |
| 自动汇总 | 事件写入 → 2s 去抖 → 会话重建（锁+事务、读取窗口左移 30 分钟）→ 日汇总 |
| 演示基线 | 合成演示库固定口径：531 事件 / 135 会话 / 316 PV / 40 UV / 跳出 29.63% / 2.34 页每次——**测试后必须复原到这六个数** |

## DDL 与部署口径

`backend/script/sql/` 下：
- `baseline/01..07_*.sql` — 全量基线（01 schema 为权威结构，07 为分析中心）
- `migration/*.sql` — 增量迁移（按文件名日期顺序执行）

**新增表/字段/索引/触发器/视图时必须同步写 DDL**：baseline 对应文件 + 新建 migration 一份（可重复执行：IF NOT EXISTS / ON CONFLICT），否则新环境部署失败。

## 历史完成度声明

任何历史阶段完成度**一律以 `git log` 与 `.workshop/` 归档为准**，本文件不记录历史清单；禁止凭旧文档/旧快照推断当前状态。

---

## 跨仓库（前后端）协作规则

凡是涉及以下情况，**前后端必须同时改**：

| 触发条件 | 后端要改 | 前端要改 |
|---------|---------|---------|
| 新增 API 接口 | Controller + Service + BO/VO | `src/api/**` 对应业务目录 + TypeScript 类型 |
| BO 字段增删/改名 | BO 类 + Validation 注解 | Form 的 model + rules |
| VO 字段增删/改名 | VO 类 | Table 列定义 + TS 接口 |
| 新增权限字符串 | `@SaCheckPermission("a:b:c")` | `sys_menu` 表 perms + `v-hasPermi="['a:b:c']"` |
| 新增菜单/页面 | `sys_menu` SQL（baseline + migration 同步） | 后端驱动动态路由，无需手注册 |
| 新增表/字段 | Entity + Mapper + DDL 沉淀 | — |

### 前后端关键路径对照

| 后端 | 前端 |
|------|------|
| `analytics/query/AnalyticsController.java` | `profile/analytics.vue` + `core/analytics.ts` |
| `analytics/query/AnalyticsCollectController.java` | `public/sdk/smartdock-analytics.js`（采集 SDK） |
| `profile/query/ProfileQueryController / ProfileDimensionController` | `profile/{index,studio,detail}.vue` + `core/profile.ts` |
| `profile/query/ProfileScoreModelController` | `profile/rfm.vue` |
| `profile/query/ProfileSegmentController` | `profile/segments.vue` |
| `profile/query/ProfileReviewController` | `profile/review.vue` |
| `profile/protocol/ProfileProtocol|SchemaController` | 协议/Schema 管理 UI（批 12 交付） |
| 采集 demo 任务 Controller（demo 模块内） | `mission/todo` 页 + `core/task.ts` |

---

## 跨栈通用架构约束

1. **ID 用雪花算法** — `ASSIGN_ID`，不用自增不用 UUID
2. **PostgreSQL 方言** — 禁止反引号 / `LIMIT offset,n` / `GROUP_CONCAT`，用 `string_agg`
3. **Sa-Token 登录 ID 格式**：`userType:userId`（如 `"00:1"`），用 `LoginHelper.getUserId()` 解析
4. **前端 API 必须走 `src/utils/request.ts`** — 不要裸用 axios
5. **前端权限用 `v-hasPermi`** — 权限串必须**三段式**（如 `analytics:center:edit`）；两段式匹配不上超管 `*:*:*`
6. **多租户**：业务表带 `tenant_id`；跨租户操作走 `TenantHelper`（注意 `TenantHelper.dynamic` 会删超管切换态，先存后还）；手写 SQL 被租户插件注入报"列不存在"时，加 `@InterceptorIgnore(tenantLine="true")` 且 SQL 内自带租户条件
7. **表结构变更必须沉淀 DDL**（见上文「DDL 与部署口径」）
8. **时区铁律**：后端固定 GMT+8（启动参数 `-Duser.timezone=GMT+8`）；种子/测试数据按 UTC+8 墙上时间生成

---

## 分支与提交策略

1. **默认只提交到 `master`**（开源主干）；新会话启动先 `git branch --show-current` 确认。
2. **显式例外**：仅当用户在当次提示词中明确要求「新建分支 / 开 PR / 隔离实验」时，才允许创建临时分支：
   - 从最新 `origin/master` 切出，命名 `<type>/<简短主题>`（如 `docs/readme-slim`）；
   - 只提交本任务涉及的文件，PR 目标分支为 `master`；
   - 合并后删除分支并切回 `master`，不长期驻留；
   - 未收到明确指令时，不得自行开分支、长期 feature 分支或 Git worktree。
3. 提交按业务批次拆分并写清 subject（人话标题）——"只用一个分支"不等于把多个不相关需求混成一个提交。
4. **多窗口并行纪律**：只 `git add` 自己改的文件；禁止 `git add -A` / `git add .`；push 前自查 `git diff --cached` 只含本批文件；他人正在改的文件（如前端壳层 index.html / layout / changelog / AI 助手组件 / vite 配置）不碰。
5. jar 会被运行中的后端锁住：**先停进程再打包**。

## AI 协作规则

### 1. 写代码前先想清楚
不要假装已经理解，也不要把不确定性藏起来。明确写出假设；不确定就问。需求有多种理解时全部列出，不要悄悄选一个。有更简单的做法要主动说明。该反对时要反对。

### 2. 简单优先
只写解决当前问题所需的最少代码，不做猜测式扩展。不添加用户没要求的功能；不为只用一次的代码设计抽象层；200 行能改成 50 行就回头简化。自检：资深工程师看到会不会觉得过度设计？会就删减。

### 3. 外科手术式修改
只碰必须修改的地方，只清理自己造成的问题。不顺手"优化"旁边的代码、注释或格式；不重构没坏的部分；匹配项目现有风格。发现无关废代码可以提一句，不要直接删。检查标准：每一行改动都应能追溯到这次请求。

### 4. 目标驱动执行
把任务转成可验证的目标，再循环到验证完成。"加校验"→"先写非法输入测试，再让测试通过"；"修 bug"→"先写复现 bug 的测试，再修到测试通过"；多步骤任务先写简短计划：`步骤 → 验证方式`。

### 5. 质量门禁（每批收口必过，详版移植自母仓 QUALITY_GATES.md）

**四条红线（结论）：**

1. 后端编译 + 单测全绿（新增功能必须配 `@Tag("local")` 纯函数单测，参照 `AnalyticsSessionizerTest`）
2. 前端 `vite build` 过
3. **活体验证**：起服务实测，禁止「API 通 = UI 可用」；浏览器**双视角**（超管 + 普通用户）权限实测；切账号先清 localStorage
4. 测试数据用独立 eventUid 前缀，验完删除并 rebuild，**口径复原到演示基线六数字**；统计断言落到具体数字，不许「看起来有数据」

**配套细则：**

- **样式改动必须视觉验收**：改 CSS/样式/图表/颜色/布局，写完后走 DOM 断言（`getComputedStyle` 实际值，attribute 值≠渲染值）→ 截图 → 视觉确认"肉眼看得到" → 至少 2 个不同数据规模回归，缺一不可。SVG 样式一律 `:style` CSS 对象（见 frontend/AGENTS.md 铁律）
- **防重复验收 / 双视角矩阵**：凡写进交付验收清单的步骤（哪个账号→哪个页面→点什么→应看到什么），交付前必须自己按**完全相同的步骤**走一遍，每个"应看到"亲眼确认；涉及权限/可见性/状态的改动至少验两个视角（超管 + 普通用户），只测超管等于只测一半；**首次路径**（清缓存冷启动）也要走
- **缺陷定性必须交叉实证**：亲读代码 + git log + 存量数据 + 运行实证四路对质——审查报告（多席审查/子智能体）是**线索不是结论**，未经实证不得当 bug 修，也不得把未复现的问题谎报为已修
- **替换已用顺手的界面须满足五条**：零操作默认价值 / 旧能力不倒退 / 核心路径 ≤2 次点击 / 真人可用 / 并行灰度过渡——不满足就别替换
- 修复类提交必须在 `frontend` 更新日志数据文件（`changelog/changelog.ts`）头部加人话条目（≤3 条）并验收首位徽标
- 人话日志追加 `.workshop/logs/`；选择题一律做 HTML 拍板页（双写归档），**答案唯一通道 = 用户在对话里明说**

### 6. 拍板页与计划文档规范（总-子两层 + 全程可见，用户硬性要求）

**文档分两层**：
- **总拍板 HTML + 总计划 MD**（每条工作线各一套，如 `20261021-ability2-master-plan.md`、`phase2-master-plan.md`）：跨批次路线图与唯一总账。**每批收口必须回刷总计划状态（打勾+提交锚点）**——只回填拍板页/日志、漏刷总账 = 与漏交代码同罪（2026-10-22 两份总账停更被用户实锤，教训在案）。
- **多个子拍板 HTML + 子计划 MD**（每批开工前各出一份，双写 `.workshop/plans/` + 会话 viz 目录）。

**子拍板页硬规矩（缺一即返工）：**
1. 每题头脑风暴 **2-3 案 + ★推荐 + 人话 why/risk**，禁止单案直接给推荐（2026-10-22 压缩板 4 题单案被用户抓到后补齐）。
2. **零黑话**：面向用户全用大白话；术语要么不写，要么括号一句白话解释。
3. 出板前**先调研美化 skill**（如 design-taste-frontend）按其法则排版：手机档零横向溢出、状态用文字徽章、视觉模型读图验收后才发。
4. **涉前端的题必须带「修复前后模拟 UI」对比**（手绘 HTML/CSS mock 即可，标注"示意图，落地后换真机截图"）。
5. 板尾写修订记录（何时补过什么、为什么）。

**前端修复全流程可见（三步缺一不可）：**
①拍板/计划阶段给修复前后**模拟 UI** 对比 → ②动手前先截**真机修复前**截图存 `.workshop/evidence/` → ③修完截**真机修复后**截图，出**前后对比 HTML**（双写 plans+viz）+ 人话描述改了什么。

**踩坑登记与防再犯：**
- 每个坑（遗漏/假绿灯/静默失败/文档停更）登记人话日志「坑位」节，必须写**机制级防法**（硬门禁/断言/checklist 项），不接受"下次小心"。
- 已固化教训示例：总账停更→收口 checklist 加「回刷总计划」；单案题/缺 mock→本节硬规矩 1/4；收口承诺的交付物（如 PR 链接）逐条对照子计划勾销，防口头漏给；测试数字双计数（478）→一律以去重后真数（239）为准勿引旧数；vitest 时长突变=死循环探测器。

---

---

## 常用命令与工具清单

> 环境变量、后端构建/启停、单测、前端命令、测试账号、连接串，全部见
> `.workshop/plans/acceptance-prompt-*.md`（gitignored，敏感信息只在 .workshop 内）。
> **端口铁律（本机）**：后端统一 **8090**、前端统一 **5188**——8080/80 被另一窗口占用；
> 前端一律 `npx vite --port 5188 --host` 直起，**不存在 5189 口径**，任何文档/提示词/验收步骤写端口只写 5188。
> 高频入口：`.workshop/tmp/package-backend.ps1`（mvn 打包，内部已带本地仓+JDK21）、
> `.workshop/tmp/start-backend-8090.ps1`、前端 `npx vite --port 5188 --host` 直起。
> 工具降级（默认无 MCP/skill）：数据库 node+pg 或 psql（`PGCLIENTENCODING=UTF8`）、HTTP curl/node fetch、
> 浏览器优先 playwright MCP 否则 .workshop/tmp 自建、调研 fetch 官方文档站；选型结论写人话日志。

---

## 🔥 当前入口（新会话先看）

- 唯一工作区：`D:\Git\git-gitee\smart-dock`；默认开发分支：`master`（用户明确要求时可开临时分支，见「分支与提交策略」）
- 二期计划入口：`.workshop/plans/phase2-development-plan.html`（批 7-12 + 一期遗留清零表）
- 最近发生了什么：`git log --oneline -10` + `.workshop/logs/phase2-profile-analytics.md` 尾部
- 每批开工前先出拍板页（选择题进 HTML，不弹问答、不臆测；**出板规范见「AI 协作规则 §6」**）；批 7-12：采集质量 → 分析深度 → 画像运营化 → 多实例 → 安全合规 → 体验交付

---

## 兜底提醒

- 切到弱模型时，强约束已是 checklist / 编号列表形式，不易遗漏
- 用户偏好 / 项目记忆写到 ZCode 记忆目录（feedback、project 类型）
- 大决策同时反映到 commit message，不只留在对话里
