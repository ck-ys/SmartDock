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

### 对外可见性（2026-11-02 加）

- **`.workshop/` 保持全量 gitignore，不开任何「只推一部分」的例外。** git 里删不掉：`git rm` 只删工作区，`git log -S` 照样翻得出来（本仓实例：`docs/decisions/20261027-style-direction-board.html` 里的内网 IP，内容已清、历史仍在）。真删需 `filter-repo` + force push，代价远大于收益。
- **想让人知道「接下来做什么 / 明确不做什么」→ 只改 `docs/roadmap.md` 一页**，状态用文字徽章（已完成 / 正在做 / 等开工 / 在讨论 / 明确不做）。不推未完成计划原件。
- **想让人知道「为什么这么设计」→ 只改 `docs/design-decisions.md`**（按主题组织，不按时间）。
- **精选决策板归档**：只有结论具备长期参考价值时才脱敏后进 `docs/decisions/`，并在 `docs/decisions/README.md` 索引加一行。脱敏 = 去掉内网地址、本机路径、内部代号（含沿用旧系统的名字）、内部协作语境。
- **允许出现的**：真实代码标识符（如 `dcs-assistant` 这个 agentId、`dcs_` 前缀表名）——它们是可运行的代码，不是内部代号；批 11B 改名后自然消失。
- **每批收口必做**：回刷 `docs/roadmap.md`（状态变更 ≤5 行）。

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
| eventUid 幂等 | (tenant, source, event_uid) 重复投递返回 false 不报错；事务内调用返回 true=已受理（提交后才落账、重复也在那时判），精确对账走 `publishBatchDetailed`；事件账本 append-only，重放可补齐 |
| 身份图谱 | 匿名 ID ↔ 登录主体经 `resolveAndLink` 归并；共享浏览器换账号只改挂匿名链接，**绝不搬走对方身份**；分析优先认登录人 |
| 会话口径 | 显式 sessionId 优先，否则 30 分钟无操作切分；隐式会话键**锚定首事件**；新访客=该源首次会话；跳出=单页会话（有搜索不算跳出；page_leave 不算"继续"） |
| 评分模型 | 指标→分箱→权重→等级，整份 JSON 配置；每次计算进快照，`prev_snapshot_id` 串趋势链；RFM 只是内置预设 |
| 分群 | 规则分群（条件树+预览）与静态名单（CSV/逐行）两种；成员快照可追溯 |
| 低分复核 | 低于阈值进队列；确认/忽略后除非分数继续下降，不重复打扰 |
| 数据导入 | 外部网站接入（公开采集口/SDK/siteKey）已于 2026-10-23 移除；数据进系统只有两条路：业务系统走 `ProfileEventPublisher`，离线数据走 CSV 补录 |
| 自动汇总 | 事件写入 → 2s 去抖 → 会话重建（锁+事务、读取窗口左移 30 分钟）→ 日汇总 |
| 演示基线 | 内部协作故事口径（2026-10-28 单源重构 E 片起，旧访客六数字 531/135/316/40/29.63%/2.34 作废）：事件 ~292 / 用户 4 / 会话 81 / 单页离开 28.4% / 周活 4 / 新同事 1 首周激活 100% / 两步上手 100%——**测试后复原到这组数**（自监控在跑会自然小幅漂移，±10% 内算复原；种子=seed-internal-ops-demo.js，uid 前缀 demo-io-） |

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
9. **安全密钥铁律（2026-11-02 密钥整改）**：加密密钥一律只从环境变量注入，**禁止写进源码 / yml / SQL / 种子 / 文档示例**
   - `SMARTDOCK_AI_CONFIG_KEY` → 保护 `dcs_ai_config.api_key`、`dcs_ai_provider_access.api_key`
   - `SMARTDOCK_MCP_HEADERS_KEY` → 保护 `dcs_mcp_server.headers`（第三方 MCP 请求头令牌）
   - 两把必须不同，16/24/32 位可见 ASCII；未配置 / 长度错 / 仍用旧版内置钥匙 → 服务**拒绝启动**（`EncryptKeyStartupRunner`）
   - `@EncryptField` 的 password 只写占位符 `${smartdock.encrypt.*}`，由框架运行时解析；新增加密字段照此办理，禁止字面量
   - 历史密文由 `EncryptKeyMigrationRunner` 启动时自动换锁（旧钥匙只解密、绝不加密）；迁移走原生 SQL，绕开 MyBatis 加解密拦截器
   - 轮换流程：换环境变量 → 启动一次让迁移跑完 → 到厂商后台把对应 API Key / 令牌也换新（旧钥匙已公开则必须换，清 git 历史无效）

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
3. 库分工（本机开发环境）：画像/分析取数、演示数据与截图 → `smartdock_shots`（演示基线见「领域概念速查」）；跑后端的实验库 → `smartdock_scratch`。同实例上除 `smartdock_` 前缀的库一律不许读写。
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
把任务转成可验证的目标，再循环到验证完成。"加校验"→"先写非法输入测试，再让测试通过"；"修 bug"→"先写复现 bug 的测试，再修到测试通过"；多步骤任务先写简短计划：`步骤 → 验证方式`。**修复类必须走完红绿闭环：修前稳定变红（同一测试连跑 2 次都失败）→ 修后同条件变绿 → 撤回修复又能变红（只在工作区临时还原被修文件取证，跑完立即恢复，不进提交）**——少这一步就分不清「修好了」和「这条测试本来就没在测它」。纯视觉、或写不出测试的修复，用 §5 的 DOM 断言或活体断言做等价的「红」（修前跑一遍必须失败），并按「前端修复全流程」进对比页。

### 5. 质量门禁（每批收口必过，详版移植自母仓 QUALITY_GATES.md）

**四条红线（结论）：**

1. 后端编译 + 单测全绿（新增功能必须配 `@Tag("local")` 纯函数单测，参照 `AnalyticsSessionizerTest`）
2. 前端 `vite build` 过
3. **活体验证**：起服务实测，禁止「API 通 = UI 可用」；浏览器**双视角**（超管 + 普通用户）权限实测；切账号先清 localStorage
4. 测试数据用独立 eventUid 前缀，验完删除并 rebuild，**口径复原到演示基线（见速查表该行）**；统计断言落到具体数字，不许「看起来有数据」

**配套细则：**

- **样式改动必须视觉验收**：改 CSS/样式/图表/颜色/布局，写完后走 DOM 断言（`getComputedStyle` 实际值，attribute 值≠渲染值）→ 截图 → **当前模型直接用 Read 打开图片目检**（模型支持图片输入时不调视觉 MCP）→ 至少 2 个不同数据规模回归，缺一不可。仅当模型不支持图片输入或用户明确要求时才降级到视觉 MCP。SVG 样式一律 `:style` CSS 对象（见 frontend/AGENTS.md 铁律）
- **防重复验收 / 双视角矩阵**：凡写进交付验收清单的步骤（哪个账号→哪个页面→点什么→应看到什么），交付前必须自己按**完全相同的步骤**走一遍，每个"应看到"亲眼确认；涉及权限/可见性/状态的改动至少验两个视角（超管 + 普通用户），只测超管等于只测一半；**首次路径**（清缓存冷启动）也要走
- **界面变了顺带刷 README 截图**：动了用户看得见的界面（壳层/首页/画像/分析/助手），收口前跑 `cd frontend && node scripts/readme-screenshots.mjs` 重拍六张（1440×900 亮色）并更新图注版本号；演示库用 `backend/script/demo/seed-internal-ops-demo.js --days 30` + 「数据分析→重建汇总」刷新（内部协作故事，uid 前缀 demo-io-），跨天/换参重播前先按 `demo-io-` 前缀清旧数据，口径回到新基线（见「领域概念速查·演示基线」行）。旧 seed-analytics-demo.js 是退役的访客故事版，仅留档不再作为基线
- **替换已用顺手的界面须满足五条**：零操作默认价值 / 旧能力不倒退 / 核心路径 ≤2 次点击 / 真人可用 / 并行灰度过渡——不满足就别替换
- 修复类提交必须在更新日志页补人话条目（≤3 条，类型=新增/优化/修复）并验收首位徽标；2026-10-23 起日志改为数据库驱动（`dcs_changelog`，草稿→发布→通知），操作口径见 `frontend/AGENTS.md`「更新日志铁律」
- 人话日志追加 `.workshop/logs/`；选择题一律做 HTML 拍板页（双写归档），**答案唯一通道 = 用户在对话里明说**

**取证与结论口径（审查报告 / 子智能体交付 / 缺陷定性适用，界面与文案微修豁免）：**

- **缺陷定性必须交叉实证——亲读代码 + git log + 存量数据 + 运行实证四路对质；结论按证据等级分四档**：①测试确认（附一个在当前主干上失败的测试）②测试证伪（有效产出，同样写进报告）③源码追踪确认（写明为什么写不成测试）④未验证（写明缺的具体条件，不许笼统写"需要环境"）。四档不许越级：静态推理只能说「存在这条路径」，测试只能说「给定输入下确实会错」，**只有运行时日志能说「线上真的发生过、多频繁、多大」**。审查报告（多席审查/子智能体）是**线索不是结论**，未经实证不得当 bug 修，也不得把未复现的问题谎报为已修。
- **档位必须落纸，不标 = 没验**：每条缺陷结论、每个修复提交，都要在人话日志 / 审查报告里写明四档中的哪一档 + 红绿闭环结果。各次评审攒下的「未验证」清单 = 测试基础设施的待办，不许当成"AI 能力不行"糊过去。
- **「无问题」结论必须可审计**：写「无问题 / 已确认安全」时，同条列出已检查路径（带行号）和**明确没查的高风险区**（没有就写「无已知未覆盖区」，不许留空、也不许用「看起来没问题」这类模糊话术替代——那是另一种谎报）；越权 / 注入 / 数据损坏类安全结论，还要说明最明显的反例为何不成立。
- **多席一致 ≠ 正确**：多个席位读同一份 diff、缺同一份背景，会在同一个地方错得一致——「N 席一致」只是重合度，不是通过标准，每条仍按上面四档逐条实证。机制级防法是**给各席不同视角的检查单**（并发 / 契约 / 事务 / 越权各一席）；同题多模型复算可以做交叉验证，但不能当验讫章。
- **审查报告必须写清两件事**：①**审的是哪份代码**——开头写基准 commit hash（或注明「含未提交工作区改动」），仓库状态断言（谁被纳管 / 是否已提交 / 有无冲突）必须附在真实分支上执行过的命令与输出，PR 网页 diff 只显示部分真实改动，评审前先在本地三方 diff 核验；②**检索有没有退化**——索引失败退化成 grep、或只读了部分文件就下结论，要在报告里写明（这是检索退化，与「常用命令」节的工具降级是两回事）。
- **测试变绿 ≠ 问题解决**：改断言、把依赖换成永远成功的替身、跳过用例，都会**改变验证范围**——必须写明理由和覆盖变化，并**至少再举一个同类输入 / 场景验证修复同样生效**（或写明为什么全仓只有这一处），确认修的是一类问题、不是一个 case。测试通过只证明给定环境与断言内的行为成立，既不能推出线上已发生，也不能推出系统整体正确。

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
> **进程卫生铁律（2026-11-01 用户追问后加）**：ZCode 每开一个会话/子智能体会拉起一套
> `cmd → npx → node` MCP（vision/z_ai/playwright/context7/dcs-pg/dcs-ssh），Windows 下
> 子智能体结束后包装器不一定回收；多席审查/工作流跑多了会堆出上百个孤儿进程。
> 每批收口/长会话结束必须跑 `.workshop/scripts/cleanup-stale-mcp.ps1 -Hours 6`
> （默认只清 npx 系、保留当前会话；连旧 dcs-pg/dcs-ssh 一起清加 `-IncludeDcs`），
> 并 `netstat` 复核除 5188/8090 外无项目监听。
> **禁止**用 `python -m http.server` 等临时静态服务看拍板页——直接 `file://` 打开，
> Playwright 用 `page.goto('file:///...')`；此类服务既违反端口铁律，也会留孤儿进程。
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

## 外部智能体源码参照库（本机只读）

> 位置：`D:\Git\git-github\`。这是本机参照环境，不是构建依赖——目录不存在就跳过，不要写进 CI / 脚本 / pom。
> 用途：改 AI 助手、智能体引擎、会话 UI 前，先到对应上游真源码看成熟做法，再动手。
> 三条纪律：① **只读**——不改上游仓、不把上游代码复制进本仓；② **许可证分层**——Apache-2.0 / MIT 可读可借鉴，**AGPL-3.0（cherry-studio）、GPL-3.0（AIClient-2-API）、Open WebUI 自定义许可只读，严禁把代码转录进本仓**（本仓 Apache-2.0）；③ **版本只作对照**——API 以本仓实际依赖为准，尤其 AgentScope：本地源码 HEAD 是 `2.1.0-SNAPSHOT`，本仓 `backend/pom.xml` 钉的是 `agentscope-bom` **2.0.4**。

| 目录 | 是什么 | 版本锚点 | 本仓对应位置（改这里先看那里） |
|------|--------|---------|------------------------------|
| `agentscope-java` | 本仓助手实际依赖的 Java 智能体框架 | HEAD `997c60ac`（revision `2.1.0-SNAPSHOT`，最近 tag `v2.0.2`） | 后端：`backend/ruoyi-modules/ruoyi-assistant` 的 `core`（Agent 工厂、中间件、工具、技能、压缩、HITL）与 `ruoyi-assistant-spi`（`BizToolProvider`）。改实现前先读 `agentscope-core` 的 `agent` / `tool` / `permission` / `skill` / `memory` / `session` / `interruption` / `middleware` 子包 |
| `ZCode` | ZCode 客户端与 CLI 真源码（TS monorepo） | `v3.17.1` / `a60ba6a7` | 前端会话：`frontend/src/components/DcsAssistant`（Composer、TurnBlock、MsgFlow、PlanDock、HitlDock）；后端压缩与会话：`org.dromara.assistant.core` 的 `AssistantCompactionPolicy`、`DcsCompactionMiddleware`。读 `packages/services/src/{zcode-agent,zcode-session,subagents,skills}`、`packages/ui/src/lib/contextCompactionRuntime.ts`、`apps/zcode-cli/docs/design/v2/compact/` |
| `LibreChat` | 多用户聊天平台，含 Agents SDK 4.2.1 / MCP / HITL / 权限 | `v0.8.9-rc1` / `f4154b649` | 后端 Agent 控制链与 HITL：`ruoyi-assistant` 的 `controller`、`core`（AGUI 流、run 状态、审批闸）。行为规格优先读 `api/server/controllers/agents/__tests__`（askUserQuestion、hitlCheckpoint、resumeMetadata、eventActorPause、memory），实现读 `api/server/controllers/agents`、`routes/agents`、`packages/chat` |
| `assistant-ui` | 聊天 UI 基元库（React / Vue 绑定） | HEAD `e8ec559d8`，`@assistant-ui/vue` `0.0.7` | 前端 `frontend/src/components/DcsAssistant` 的流式消息、输入框、工具卡片结构。Vue 绑定看 `packages/vue`，状态机看 `packages/core/src/{runtime,store}`，交互范式看 `packages/react/src/primitives`；只参照结构，不引入依赖 |
| `open-webui` | FastAPI + Svelte 的 AI 平台 | `v0.12.5` / `f7ac7ee10` | 后端会话、压缩、追问、权限中间件：`ruoyi-assistant` 的 `core` 与 `controller`。读 `backend/open_webui/utils/{context_compaction,ask_user,asgi_middleware}.py`、`routers/{groups,chats,folders}.py`、`utils/access_control`；前端 Svelte 会话只作结构参照（`src/lib/components/chat`） |
| `cherry-studio` | Electron 桌面 AI 客户端（AGPL-3.0） | `2.1.10` / `8e13399fa2` | 工具审批、技能、MCP、提示注入防护：`src/main/ai/{toolApproval,skills,mcp,agentSession}`、`untrustedContent.ts`、`steerReminder.ts`；对应本仓 `AssistantSkillGateMiddleware`、`AssistantPlanGateMiddleware`、MCP 注册与工具开关。**只读，禁转录** |
| `cline` | 编码智能体（VS Code / CLI / SDK，Apache-2.0） | HEAD `a6507a62a`（本地 git 索引为空，直接读工作区文件） | 工具循环、计划-执行模式、检查点与回滚：`sdk/packages/{core,agents,llms}`、`apps/vscode/src/core`；对应本仓 PlanGate、计划/待办信号、工具审批与运行恢复 |
| `NextChat` | 轻量 Next.js 聊天前端（MIT） | `v2.16.1` / `1d3dc331` | 多模型提供商适配：`app/client/platforms/*`（deepseek、glm、siliconflow 等）、`app/mcp`、`app/store`；对应本仓模型配置页与 provider 选择逻辑 |
| `AIClient-2-API` | 订阅账号转 OpenAI 兼容 API 的网关（GPL-3.0） | `v2.13.7` / `d511ba1` | 渠道池、OAuth 凭据、模型路由与降级：`src/providers/*`、`provider-pool-manager.js`；对应本仓模型密钥与渠道路由（选路逻辑）。**只读，禁转录** |

**按任务速查**：改会话界面 → `assistant-ui` + `ZCode/packages/ui` + `open-webui` 聊天组件；改 Agent 主链 / 工具 / 技能 → `agentscope-java` + `LibreChat` 的 agents 控制器；改压缩 / 记忆 → `ZCode` 的 compact 设计文档 + `open-webui` 的 `context_compaction.py`；改审批 / HITL → `LibreChat` 的 hitlCheckpoint + `cherry-studio` 的 toolApproval；改模型 / 渠道 → `NextChat` 的 platforms + `AIClient-2-API` 的 providers。

**不纳入本库**：`glm-coding-grabber` 是油猴抢购脚本，不是智能体源码；`shadcn-admin` / `vue-pure-admin` 是 UI 观感参照（已登记在 `frontend/AGENTS.md`）；其余目录（SpringBoot-Labs、mall-cloud-alibaba、yudao-cloud、newbee-mall-cloud、dddsample-core、pg_jieba、rocketmq-dashboard 等）与智能体无关。

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
- **三句判断**（2026-11-10 引自美团技术团队《分布式存储 AI Coding 实践与边界》）：AI 把瓶颈从「写」移到「验证」；AI 的错误大多不在代码里，而在代码之外（运维流程、设计决策、团队约定）；流畅不是证据、一致不是正确、变绿不是解决——后两条已固化为 §5 规则。
