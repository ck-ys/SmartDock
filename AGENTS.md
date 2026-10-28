# AGENTS.md

这份文件约束 AI 编程代理在 smart-dock 项目里的工作方式，用来减少常见的 AI 编程失误。设计大范围参照内部母仓的同名文件（母仓定位与只读使用规则只记录在 `.workshop/` 内部资料，绝不写入 git，绝不动一个字），并按开源版定位改写。可在此基础上继续补充项目专属规则。
取舍说明：这些规则偏向谨慎和可验证，而非最快完成。遇到很小的简单任务时，可按实际情况简化流程。

## 启动 Checklist（任何新会话 / 切换模型开始前必跑）

1. 读本文件 AGENTS.md；改后端再读 `backend/AGENTS.md`，改前端再读 `frontend/AGENTS.md`
2. 读 `.workshop/plans/` 文件名内嵌日期最新的一组计划（同日多份都读；`ls -t .workshop/plans/ | head` 辅助定位）；二期权威计划是 `phase2-development-plan.html`
3. `git log --oneline -10` 对齐最近锚点——完成度一律以 git log 为准，禁止凭旧文档/旧快照推断当前状态
4. 读 `.workshop/logs/phase2-profile-analytics.md` 尾部两节（最近做了什么 / 下一步）
5. grep 验证当前阶段要动的方法 / 文件确实存在
6. 若本次是新需求/新批次：确认**拍板 HTML 已出并过自检**（§6 前置铁律：每题多方案+推荐、流程节+可点击问题清单、涉前端带模拟 UI、零黑话）——**没板不开工**
7. 报告："已读完 X，准备做 Y，已确认 Z 存在"

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
- **行为分析中心**（`com.smartdock.analytics`）：会话重建、日汇总、流量/页面/漏斗/留存/自定义分析、CSV 补录（外部网站公开采集口与 SDK 已于 2026-10-23 移除）
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
| 数据导入 | 外部网站接入（公开采集口/SDK/siteKey）已于 2026-10-23 移除；数据进系统只有两条路：业务系统走 `ProfileEventPublisher`，离线数据走 CSV 补录 |
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
| `profile/query/ProfileQueryController / ProfileDimensionController` | `profile/{index,studio,detail}.vue` + `core/profile.ts` |
| `profile/query/ProfileScoreModelController` | `profile/rfm.vue` |
| `profile/query/ProfileSegmentController` | `profile/segments.vue` |
| `profile/query/ProfileReviewController` | `profile/review.vue` |
| `profile/protocol/ProfileProtocol|SchemaController` | 画像管理页「协议与 Schema」页签（`profile/admin.vue`） |
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

## 数据面口径（PG / Redis）

> 本仓自带两个数据面 MCP：`dock-pg` / `dock-redis`（服务端本体在 `tools/mcp/`，随仓开源）。
> 连接信息永远只写进 gitignored 的 `.zcode/config.json`，绝不写进任何 git 追踪文件；
> 真实环境的地址与口令另见 `.workshop/plans/acceptance-prompt-*.md`（gitignored）。

**首次搭建（用户还没配过时，按此流程，不要跳步）：**

1. 检查 `.zcode/config.json` 是否已存在且含 `dock-pg`/`dock-redis`；`tools/mcp/*/dist/` 是否已构建。都在则直接用，跳过本流程。
2. **先向用户要连接信息再动手**，缺一不可：PG 的 host/port/库名/用户名/密码；Redis 的 host/port/db 索引/密码（可无）。禁止臆测地址、禁止拿其它项目的连接凑数。
3. 拿到后依次：`dist/` 缺失则进对应目录 `npm install && npm run build`（Node ≥ 20.19）→ 按两包 README 的模板写 `.zcode/config.json`（路径用仓库绝对路径）→ 提醒用户重启会话生效。
4. 装好后冒烟：`pg_query` 跑 `select current_database()`、`redis_command` 跑 `PING`，确认连的是用户给的库。

**日常使用口径：**

1. 本机没有 5432 的 PG、也没有 6379 的 Redis；yml 里 `127.0.0.1` 是模板默认，起后端必须带覆盖参数（见验收提示词铁律）。
2. 查本仓数据面**一律用 `dock-pg` / `dock-redis`**；会话里若同时挂载了默认库不属于本项目的其它 PG/Redis 工具，一律不用。
3. 库分工（本机开发环境）：画像/分析取数、演示数据与截图 → `smartdock_shots`（基线六数字见「领域概念速查」）；跑后端的实验库 → `smartdock_scratch`。同实例上除 `smartdock_` 前缀的库一律不许读写。
4. MCP 纪律：先列清单、再看列定义、后写 SQL；`pg_query` 每次一条、参数 `$1/$2` 绑定；DDL/DML 前先同条件 SELECT 验证影响面并等用户确认；行数估算不作数，真数用 `count(*)`。

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

- **样式改动必须视觉验收**：改 CSS/样式/图表/颜色/布局，写完后走 DOM 断言（`getComputedStyle` 实际值，attribute 值≠渲染值）→ 截图 → **当前模型直接用 Read 打开图片目检**（模型支持图片输入时不调视觉 MCP）→ 至少 2 个不同数据规模回归，缺一不可。仅当模型不支持图片输入或用户明确要求时才降级到视觉 MCP。SVG 样式一律 `:style` CSS 对象（见 frontend/AGENTS.md 铁律）
- **防重复验收 / 双视角矩阵**：凡写进交付验收清单的步骤（哪个账号→哪个页面→点什么→应看到什么），交付前必须自己按**完全相同的步骤**走一遍，每个"应看到"亲眼确认；涉及权限/可见性/状态的改动至少验两个视角（超管 + 普通用户），只测超管等于只测一半；**首次路径**（清缓存冷启动）也要走
- **界面变了顺带刷 README 截图**：动了用户看得见的界面（壳层/首页/画像/分析/助手），收口前跑 `cd frontend && node scripts/readme-screenshots.mjs` 重拍六张（1440×900 亮色）并更新图注版本号；演示库先用 `backend/script/demo/seed-analytics-demo.js --days 30 --visitors 40` + 「数据分析→重建汇总」刷新，跨天重播前必须先按 `demo-` 前缀清旧数据，口径回到 531/135/316/40/29.63%/2.34
- **缺陷定性必须交叉实证**：亲读代码 + git log + 存量数据 + 运行实证四路对质——审查报告（多席审查/子智能体）是**线索不是结论**，未经实证不得当 bug 修，也不得把未复现的问题谎报为已修
- **替换已用顺手的界面须满足五条**：零操作默认价值 / 旧能力不倒退 / 核心路径 ≤2 次点击 / 真人可用 / 并行灰度过渡——不满足就别替换
- 修复类提交必须在更新日志页补人话条目（≤3 条，类型=新增/优化/修复）并验收首位徽标；2026-10-23 起日志改为数据库驱动（`dcs_changelog`，草稿→发布→通知），操作口径见 `frontend/AGENTS.md`「更新日志铁律」
- 人话日志追加 `.workshop/logs/`；选择题一律做 HTML 拍板页（双写归档），**答案唯一通道 = 用户在对话里明说**

### 6. 拍板页与计划文档规范（总-子两层 + 全程可见，用户硬性要求）

**文档分两层**：
- **总拍板 HTML + 总计划 MD**（每条工作线各一套，如 `20261021-ability2-master-plan.md`、`phase2-master-plan.md`）：跨批次路线图与唯一总账。**每批收口必须回刷总计划状态（打勾+提交锚点）**——只回填拍板页/日志、漏刷总账 = 与漏交代码同罪（2026-10-22 两份总账停更被用户实锤，教训在案）。
- **多个子拍板 HTML + 子计划 MD**（每批开工前各出一份，双写 `.workshop/plans/` + 会话 viz 目录）。

**前置铁律（2026-10-27 用户加规）**：**每次开发新需求，动手写代码前必须先出拍板 HTML**——没有板不开工；板的每道题（含纯后端题）都要**头脑风暴 2-3 个方案**比出最佳推荐，不是只有界面题才给选项。很小的修补可豁免，但「新需求/新批次/改口径/改交互」一律不豁免。

**子拍板页硬规矩（缺一即返工）：**
1. 每题头脑风暴 **2-3 案 + ★推荐 + 人话 why/risk**，禁止单案直接给推荐（2026-10-22 压缩板 4 题单案被用户抓到后补齐）。**后端题同样适用**：接口设计/表结构/并发策略/迁移路径都要摆出候选方案对比后再推荐，写清每案代价与风险。
2. **零黑话**：面向用户全用大白话；术语要么不写，要么括号一句白话解释。问题清单、流程图、表格单元格全都要过这一关。
3. 出板前**先调研美化 skill**（如 design-taste-frontend）按其法则排版：手机档零横向溢出、状态用文字徽章、**模型原生读图验收**（Read 打开截图）后才发。
4. **涉前端的题必须带「修复前后模拟 UI」对比**（手绘 HTML/CSS mock 即可，标注"示意图，落地后换真机截图"）。
5. 板尾写修订记录（何时补过什么、为什么）。
6. **板结构必备（2026-10-27 用户加规）**：①**执行流程节**（这批活按什么顺序做、每步怎么验收）；②**问题清单列表且可点击**（锚点导航，从清单点题号直达对应小节，答完可回看）；③题号/结论/已修复状态用文字徽章一目了然。

**前端修复全流程可见（三步缺一不可，收口硬门禁）：**
①拍板/计划阶段给修复前后**模拟 UI** 对比 → ②动手前先截**真机修复前**截图存 `.workshop/evidence/` → ③修完截**真机修复后**截图，出**前后对比 HTML**（双写 plans+viz）+ 人话描述改了什么。**修复收口 checklist 必含「对比页已出」一项**——2026-10-27 五审批漏过对比页被用户点名补拍，教训在案；纯后端修复无界面变化的，以单测/活体断言锚点在对比页中说明。

**踩坑登记与防再犯：**
- 每个坑（遗漏/假绿灯/静默失败/文档停更）登记人话日志「坑位」节，必须写**机制级防法**（硬门禁/断言/checklist 项），不接受"下次小心"。
- 已固化教训示例：总账停更→收口 checklist 加「回刷总计划」；单案题/缺 mock→本节硬规矩 1/4；收口承诺的交付物（如 PR 链接）逐条对照子计划勾销，防口头漏给；测试数字双计数（478）→一律以去重后真数（239）为准勿引旧数；vitest 时长突变=死循环探测器；五审漏对比页→收口 checklist 加「前后对比页」。

---

---

## 常用命令与工具清单

> 环境变量、后端构建/启停、单测、前端命令、测试账号、连接串，全部见
> `.workshop/plans/acceptance-prompt-*.md`（gitignored，敏感信息只在 .workshop 内）。
> **端口铁律（本机，2026-10-24 用户收紧）**：前端**一律 5188**、后端**一律 8090**——
> 除这两个端口外**任何端口一律不得开**（含 5189/5190/5192/5193 等跳号/避让口径全部作废）；
> 前端一律 `npx vite --port 5188 --host --strictPort` 直起（strictPort：端口被占就报错清理，不许自动跳号）；
> 重启前端前先确认旧实例进程已退（Windows 下 npx 的 vite 子进程会残留占端口，导致新实例跳号）；
> 后端也固定 8090 单实例。任何文档/提示词/验收步骤写端口只写 5188 / 8090。
> 高频入口：`.workshop/tmp/package-backend.ps1`（mvn 打包，内部已带本地仓+JDK21）、
> `.workshop/tmp/start-backend-8090.ps1`、前端 `npx vite --port 5188 --host` 直起。
> 工具降级（默认无 MCP/skill）：数据库 node+pg 或 psql（`PGCLIENTENCODING=UTF8`）、HTTP curl/node fetch、
> 浏览器优先 playwright MCP 否则 .workshop/tmp 自建、调研 fetch 官方文档站；选型结论写人话日志。
> **读图铁律**：当前模型支持图片输入时，截图核验直接用 `Read` 打开 PNG/JPG，禁止默认绕道视觉 MCP；仅当模型不支持图片或用户明确要求时才调用视觉 MCP。

## 常用推荐 skill 清单（按场景，实战检验过的优先）

> 用法：Skill 工具按名调用；本清单是「常用+推荐」不是全集。凡标 ★ 的是本项目用户硬性要求或高频必用。

| 场景 | 推荐 skill | 一句话说明 |
|------|-----------|-----------|
| ★ 改前端 UI / 出拍板页 | `design-taste-frontend` | 用户硬规：每波动前端 UI 必先读（管理台走 app-screens 模式：安静边框/小标签，禁渐变辉光） |
| 写/改 Element Plus 组件 | `el-dialog` `el-form` `el-table` `el-select` `el-date-picker` `el-drawer` `el-upload` `el-tabs` `el-message-box` | 每个部件一页坑谱（props 静默降级/teleport 样式/空串当空值等，都是本仓踩过的） |
| EP 主题与设计语言 | `element-plus-design-color` / `-typography` / `-layout` / `-border` / `element-plus-theming` | 动主题令牌/字号档位前先过一遍，防半像素字号回潮 |
| Vue 代码质量 | `vue-best-practices` `vue-pinia-best-practices` `vue-router-best-practices` | 改 store/路由/组件前扫一眼，少走弯路 |
| 调 Vue 疑难 | `vue-debug-guides` | 响应性/生命周期类怪象先查这里再动手 |
| ★ 出拍板页前头脑风暴 | `brainstorming` | 每题 2-3 案的素材来源；配合 §6 出板规范 |
| ★ 自我 grill / 审查自己 | `grill-me` `grilling` `grill-with-docs` | 收口前拿这三把刀拷问自己的方案与 diff（用户点名要求） |
| 代码审查协作 | `requesting-code-review` `receiving-code-review` `review-agent` | 派审查席/接审查报告时的姿势（报告是线索不是结论） |
| E2E / 活体验证 | `webapp-testing` | Playwright 走查、断言、截图的套路 |
| 调试系统性问题 | `systematic-debugging` `verification-before-completion` | 先复现再修；完成前逐条验证不靠感觉 |
| 计划与执行 | `writing-plans` `executing-plans` `planning-with-files-zh` | 批次计划/跨会话续作时用 |
| 截图还原成代码 | `image-to-code` | 拿设计稿/竞品截图还原页面 |
| 会话内可视化 | `visualize` | 出 HTML 可视化卡片（对比页/示意图），配合双写归档 |
| 文档协作 | `documents:docx` `doc-coauthoring` | 交付 Word 类材料 |
| 工作流编排 | `dynamic-workflows`（先读 skill 再写脚本）`dispatching-parallel-agents` `subagent-driven-development` | 多席并行审查/跑批时用；工作流必须用户明说才开 |
| 造轮子 | `skill-creator`（做新 skill）`mcp-builder`（做 MCP 工具） | 沉淀可复用能力时 |
| 物料图 | `imagegen` `banner-design` `brand` | README banner/图标类 |

**纪律**：skill 是参考输入不是挡箭牌——结论仍要落到「计算样式断言 + 截图 + 原生读图」三件套；调用了什么 skill、采纳/拒绝了哪条要写进人话日志。

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
