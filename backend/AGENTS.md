# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository. 设计参照内部母仓的同名文件（只读；母仓定位只记录在 `.workshop/` 内部资料，绝不写入 git）并按 smart-dock 开源版实况改写。

## Project Overview

SmartDock backend — 基于 RuoYi-Vue-Plus 5.6.1 的多租户平台，定位**用户画像 + AI 助手双引擎**（原数据采集退为内置 demo）。

- Spring Boot 3.5 / Java 21 / PostgreSQL 17 / MyBatis-Plus / Sa-Token (JWT) / Redisson / Undertow
- 框架基础包 `org.dromara`；业务自研包 `com.smartdock`
- 本地开发端口 **8090**（8080/80 留给其它窗口，不得抢占）

## Build & Run

- **打包（跳过测试）**：用 `.workshop/tmp/package-backend.ps1`（内部已带本地 Maven 仓 + JDK21；系统 PATH 里的 mvn 3.6.1 太老不要直接用）
- **运行**：`.workshop/tmp/start-backend-8090.ps1`（演示库 + 快照 Redis 覆盖 + `-Duser.timezone=GMT+8`，连接参数见 `.workshop/`，永不写进 git）
- **入口**：`ruoyi-admin` → `org.dromara.DromaraApplication`；产物 `ruoyi-admin/target/ruoyi-admin.jar`
- **Active profile**：`dev`（Maven profiles: local/dev/prod，经 `@profiles.active@` 注入）
- ⚠️ **jar 会被运行中的后端锁住——必须先停进程再打包**
- ⚠️ 全量打包别用 `-pl` 跳模块（有失败历史）；编译单模块可以

## Testing

- JUnit 5，按 `@Tag("local"/"dev"/"prod")` 过滤；**默认 skipTests=true，跑测试必须带 `-DskipTests=false -Dsurefire.groups=local`**
- 测试分布在 `ruoyi-admin/src/test/java/org/dromara/`（系统/助手/守卫）与 `ruoyi-modules/ruoyi-profile-engine/src/test/java/com/smartdock/`（画像/分析纯函数）
- 当前基线：全量 local 组全绿（具体数字以最近一次收口记录为准，别引旧数）——新增功能必须配 `@Tag("local")` 纯函数单测（写法参照 `AnalyticsSessionizerTest`）
- ⚠️ 纯单测不能用 Spring 的 `JsonUtils`（静态初始化炸），自带 Jackson `ObjectMapper`
- ⚠️ 交叉验证用默认 Maven 仓；带空格的 `-Dmaven.repo.local` 会让 surefire 的 `-javaagent` 启动失败
- ⚠️ `MapstructUtils.CONVERTER` 是 static final 一次性绑定，纯 Mockito stub 无效时反射读出已绑定 mock 再 stub

## Module Architecture

```
ruoyi-admin/          # Spring Boot 入口（DromaraApplication），组装全部模块
ruoyi-common/         # 24 个基础设施子模块（core/mybatis/redis/satoken/security/tenant/web/
                      #   encrypt/sensitive/translation/excel/oss/sms/mail/social/idempotent/
                      #   ratelimiter/job/log/sse/websocket/json/doc/bom）
ruoyi-modules/        # 业务模块：
  ruoyi-system          # 系统管理（用户/角色/菜单/租户/字典…）
  ruoyi-profile-engine  # ★ 画像引擎 + 行为分析中心（com.smartdock.profile + com.smartdock.analytics）
  ruoyi-assistant       # AI 助手（AgentScope 管主链；Spring AI 只管后台任务）
  ruoyi-assistant-spi   # 助手扩展接口
  (采集demo模块)             # 多任务采集 demo（任务/待办/审计最小闭环）；模块目录名含内部代号，按文件名搜索定位
  ruoyi-ai-billing      # AI 计量计费
  ruoyi-file-security   # 文件审查
  ruoyi-risk            # 风险行为
  ruoyi-job             # SnailJob 定时任务
script/sql/           # baseline/（全量基线）+ migration/（增量，按文件名日期序）
```

## Key Architecture Patterns

- **Auth**：Sa-Token + JWT；`LoginHelper` 静态取当前用户；登录 ID 格式 `userType:userId`（如 `"00:1"`）
- **分层**：`controller/` + `domain/{bo,vo,dto}/` + `mapper/` + `service/{impl}/`；Entity 继承 `BaseEntity`；Mapper 继承 `BaseMapperPlus<T>`
- **对象映射**：MapStruct-Plus（编译期生成于 `target/generated-sources/annotations/`）
- **分页**：`BaseMapperPlus.selectPageList(PageQuery)` → `TableDataInfo<T>`
- **多租户**：MyBatis-Plus 租户插件按 `tenant_id` 过滤；跨租户操作走 `TenantHelper`（⚠️ `dynamic` 会删超管切换态，先存后还）
- **DI**：`@RequiredArgsConstructor` + `private final`（禁 `@Autowired`）
- **响应**：一律 `R<T>`（200=成功）；`ServiceException` 经全局处理器返回 **HTTP 200 + R.fail(code)**——客户端判定必须看 body.code，不能只看 res.ok
- **限流**：`@RateLimiter`（分布式，ruoyi-common-ratelimiter）；**幂等**：`@RepeatSubmit`（同一接口 5s 内重复 POST 会被拒，验证要 sleep ≥ 7s）
- **容器**：Undertow；**时区**：固定 GMT+8

## Profile Engine (`ruoyi-profile-engine`) — 本仓核心模块

`com.smartdock.profile`（画像）+ `com.smartdock.analytics`（分析），构成"任意业务接入协议 → 模型/分群/复核 → 流量分析"全链路。

### 子域

- **event/** — 事件发布实现：schema 校验（**未注册的事件一律放行**，只校验必填/类型）→ 身份解析 → `insertIgnore` 幂等写入 → 主体登记 upsert；逐条独立事务（REQUIRES_NEW，单条坏数据不拖垮整批）
- **identity/** — 身份图谱：`resolveAndLink` 匿名↔登录归并；共享浏览器换账号**绝不搬走对方身份**
- **engine/** — 评分引擎、维度 SPI、重算触发器（按业务源合并去抖）、模型快照链（`prev_snapshot_id`）
- **model/ + operator/** — 模型 JSON 配置、分箱/评分算子
- **protocol/ + schema/** — 接入协议 manifest、Schema 注册表
- **segment/ + review/** — 分群（规则+静态名单，成员快照）、低分复核（去重不重复打扰）
- **analytics/** — 会话重建（显式 sessionId 优先/30 分钟切分/隐式键锚定首事件）、日汇总、漏斗/留存/自定义分析、CSV 补录

### 本模块硬规则（连环 bug 换来的）

1. **Mapper 必须放 `.mapper` 结尾的包**——放错包静默不注册（MapperScan 按包名匹配）
2. **jsonb 写入**：自定义 SQL 必须显式 `::jsonb`；JSON 列出参是字符串时在 API 层归一
3. **MyBatis 注解**：`<script>` 内 `<` 写 `&lt;`；非 script 注解用字面 `<`
4. **手写 SQL 被租户插件注入报"列不存在"**：加 `@InterceptorIgnore(tenantLine="true")` 且 SQL 内自带 `tenant_id` 条件（参照 `AnalyticsSessionMapper`）
5. **eventUid 幂等**：`(tenant, source, event_uid)` 重复返回 false 不报错；账本 append-only
6. **会话重建锁+事务**：锁必须包住事务提交边界（TransactionTemplate 在锁内），否则并发重建撞唯一键；读取窗口左移 30 分钟
7. **自动汇总是 2s 去抖**：事件写入后验证要 sleep ≥ 5s
8. `com.smartdock` 扫描需在配置三处登记（启动类/组件扫描/yarn——以现有配置为准，别删）
9. PG `GROUP BY` 别名大小写敏感：`GROUP BY 1,2` 而非 `GROUP BY dayOffset`

## Configuration Notes

- 端口 8090；PostgreSQL 17；演示库与 Redis 连接参数只在 `.workshop/`（gitignored）
- SQL 监控：P6Spy；API 文档 `/api-docs`；SSE `/resource/sse`
- 雪花 ID（`ASSIGN_ID`）；逻辑删除 `del_flag`；审计字段自动填充（createDept/createBy/createTime/updateBy/updateTime），不要手动 set

## Frontend Companion Project

前端在 `../frontend`（Vue 3 + Vite + Element Plus + TS）。改后端接口时**必须同步检查前端**：

| Backend | Frontend |
|---------|----------|
| `ruoyi-profile-engine/.../query/*Controller` | `../frontend/src/api/**/core/{profile,analytics}.ts` |
| 采集 demo 模块 `.../controller/` | `../frontend/src/api/**/core/task.ts` |
| BO/VO 字段变更 | `../frontend/src/views/**` 对应页面 表单 rules / 表格列 / TS 接口 |
| 新权限串 | `sys_menu` perms + 前端 `v-hasPermi`（必须三段式） |

## Code Style

- Java 21；构造注入 `@RequiredArgsConstructor` + `private final`
- 全部返回 `R<T>`：`R.ok()` / `R.fail()` / `R.warn()`
- 新代码用 Jackson（ruoyi-common-json），不用 Fastjson
- 注释风格：中文 Javadoc，讲"为什么"不讲"是什么"

## 写码红线（2026-11-17，只收「失败是静默错误」的）

来自 p3c 56 条规则 + 手册的逐条实测筛选；排版/命名类一律不收（2026-11-13 命名拍板已管）。

- **`@Transactional` 一律写 `rollbackFor = Exception.class`**：默认只回滚 `RuntimeException`，受检异常会**提交半条数据**。现存 112 处全带（2026-11-22 实测：109 处 import 写法 + 3 处带包名写法），新代码别破例。
- **禁 `Executors.new*`**（`newScheduledThreadPool` / `newSingleThreadScheduledExecutor` 除外）：线程池必须 `ThreadPoolExecutor` 显式参数 + **命名 ThreadFactory**；无界队列在任务堆积时不是拒绝而是无限排队，thread dump 里全是 `pool-1-thread-N`。违禁写法现存 0 处（批 11，2026-11-22：原 1 处 `AssistantAttachmentService.PARSE_EXECUTOR` 已等价改写为 `ThreadPoolExecutor(1, 1, 0L, MILLISECONDS, LinkedBlockingQueue, 工厂)`，行为不变；豁免的两个调度工厂另有 4 处）。JDK 21 的虚拟线程工厂 `newVirtualThreadPerTaskExecutor` / `newThreadPerTaskExecutor` 同在禁用之列——要用先改这条规矩。
- **禁 `new BigDecimal(double)`**：二进制误差会让金额对账差一分；用 `new BigDecimal(String)` 或 `BigDecimal.valueOf`。
- **foreach 里不改被遍历集合**：要删用 `removeIf`，要改先 `new ArrayList<>(src)`；否则运行时 CME。
- **可空变量别写 `变量.equals("字面量")`**：反过来写 `"字面量".equals(变量)`。**这条不做成门禁**（批 11 拍板 Q3=1，2026-11-22）：原报「现存 3 处待清」是误报——`TenantHelper:200` 前面有 `isNotBlank` 守卫、`AiBillingService:283` 的值来自 `substring`、`SvgXssScanner:68` 的值是 `toLowerCase` 的返回值，三处都不会为 null；而文本扫描分不清「可能为 null」与「必然非空」，保留为 review 口径。
- **`SimpleDateFormat` 不做静态共享字段**：内部 Calendar 非线程安全，并发下会串日期；新代码优先 `DateTimeFormatter`（现存 0 处静态、8 处局部 new，保持现状：3 处 import 写法 `DateUtils:133/146`、`AiUsageController:166`，5 处 `java.text.` 全限定名写法 `AiCreditController:219`、`AssistantActionService:222`、`AnalyticsService:1513/1952/1955`）。

上面 6 条里，**第 1 / 2 / 3 / 4 / 6 条自批 11（2026-11-22）起是会失败的测试**：`org.dromara.test.arch.CodeRedlineTest`（`@Tag("local")`，随全量 local 组一起跑），零容忍、无白名单无基线，违反当场红并报到 `文件:行`。扫描面只有 `src/main/java`（测试代码里建临时线程池是合法的）。**靠 review 补的漏报面**：`new BigDecimal(变量)`（变量是 double 时文本扫描看不出来）、`for (x : 服务.取列表())` 体内改集合（接收者追不到）、线程池构造参数（门禁只认「有没有用快捷工厂」——`new ThreadPoolExecutor(1, 1, 0L, MS, 队列)` 不传命名工厂也能过，不满足上面「显式参数 + 命名 ThreadFactory」的要求）。**已知误报面**：foreach 循环体里的 lambda / 异步任务改集合（其实是稍后才执行）、局部变量与被遍历集合重名——门禁报了先看是不是这两种；门禁没有豁免注释，是就改写法。判据自带常驻自检（该报的样例必须报、合法的必须放行），正则改坏了会当场红。

## 包卫生与分层门禁（2026-11-17 起强制）

六条规矩跑在 `ruoyi-admin` 的单测里（`org.dromara.test.arch.*`，`@Tag("local")`；`PackageMigrationGuardTest` 是 `@Tag("dev")`，门禁全跑加 `-Dsurefire.groups=local,dev`），**红线是「只减不增」**：

1. **新代码只进 `com.smartdock.<域>.<层>`**；`com.smartdock.assistant.legacy` 是 legacy（2026-11-19 批 2 由旧根整棵改名而来，旧根已清零，改名记录见 git log），**只许改、不许新增文件**（**批 9 已整树清零为 0 文件，禁止复活**——历史基线段 `legacyOrgAssistantFiles` 已随清零删除；防回潮靠 `PackageMigrationGuardTest.batch9LegacyTreeMustBeAbsentFromClasspath` 整树缺席守卫 + `PackageHygieneTest` 双树 0 文件断言）。
2. **禁止行内全限定名**：源码行里不许出现 `org.dromara.` / `com.smartdock.` / `java.util.` 的整条包名（import、注释、字符串不算）。改 import；确实没有替代写法时行尾写 `// arch-ignore-fqn` 并说明原因。
3. **Controller 只做权限/参数/租户/返回包装**：方法超过 40 行就抽 Service；Controller 不许直接注入 `*Mapper`，也不许借用助手模块以外的 `*Service`（ArchUnit 按字节码查，字段/返回值/参数/调用都算）。
4. **接口出参不许 `R<Map<String, Object>>`**（含 List 包一层）：定义 `*Vo` 强类型；契约冻结，改字段算破坏性变更。
5. **domain 不 import 上层**（批 1 规则 7，零容忍）：`com.smartdock.*` 的 domain 包不许 import service / controller / infrastructure / mapper——跨层收口建 `domain/gateway` 接口、`infrastructure` 实现；底座（`org.dromara.common` 等）不算上层。
6. **新根 service 对 legacy 只减不增**（批 1 规则 8，2026-11-17 已清零；批 3 起代码行**内联**全限定名同样记账）：`com.smartdock.*` 的 service 包 import 或内联 `com.smartdock.assistant.legacy.*` 都会直接红（内联仅 `PackageHygieneTest#RULE8_INLINE_ALLOWED` 点名豁免的批 3 既有债务，带次数、两头红）；旧根 `org.dromara.assistant` 已批 2 整棵改名消灭，防回潮靠源码零断言（`PackageHygieneTest` 旧根四路径计数）+ classpath 守卫（`PackageMigrationGuardTest`）；legacy 依赖一律经 gateway 收口进 infrastructure。

存量违规冻结在 `backend/ruoyi-admin/src/test/resources/arch-baseline.json`。**修掉一条就删一行**（残留会红，防清单腐烂）；不许往清单里加新行。

```bash
# 跑门禁（五个测试类：写码红线 + 包卫生 + 分层 + 权限串 + 改名守卫；最后一个是 dev 标签，groups 要带上）
mvn -P local -DskipTests=false -pl ruoyi-admin -am test \
  -Dtest=CodeRedlineTest,PackageHygieneTest,LayeringTest,PermSegmentGuardTest,PackageMigrationGuardTest \
  -Dsurefire.groups=local,dev -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
# 搬迁/大清理后整份刷新基线（会把当前违规全部冻结，谨慎）
ARCH_BASELINE_UPDATE=1 mvn -P local -DskipTests=false -pl ruoyi-admin -am test -Dtest=PackageHygieneTest,LayeringTest
```

### 四层落点（新代码放哪）

| 放什么 | 包 | 允许 import |
|---|---|---|
| 入口 / 权限 / 返回包装 | `controller/` | service、`domain/bo`、`domain/vo` |
| 用例编排 | `service/` | `domain/*`、gateway 接口、mapper（仅自有表） |
| 实体 / 值对象 | `domain/model/` | 不 import service、controller、infrastructure（MyBatis 注解允许） |
| 接口菜单 | `domain/gateway/` | 只 import domain 类型 + 端口契约所需的框架类型（如 `Flux` / `AguiEvent`；不许碰本模块 service/mapper/legacy） |
| 菜单实现 | `infrastructure/` | gateway 接口、legacy、第三方客户端 |
| 表访问 | `mapper/` | MyBatis-Plus |

### gateway 建不建

- **建**：跨模块调用、流式/长连接、外部服务（会换实现）
- **不建**：自有单表 CRUD（`BaseMapper` 已经是抽象）、只有一个实现的内部方法

### 引擎内核（冻结，不按业务线搬）

`core`(54) / `tools`(12) / `automation`(14) 是助手引擎；业务层 controller/service/mapper/bo/vo 按触碰式搬空。引擎包是否搬，批 9 单独拍板；旧根改名见拍板板 12D（批 2）。

### 参照仓只读清单

结构参照仓在 `D:\Git\git-github\java-study`（本机只读）。许可分层：Apache-2.0 / MIT（ArchUnit、cargotracker、dddsample 等）可借鉴思路；COLA（LGPL-2.1）与 halo（GPL-3.0）只读结构、**严禁转录代码**；JavaNote 无 LICENSE，只读概念、禁转录。完整清单见根 `AGENTS.md`「外部智能体源码参照库」。

## Security Rules

- 新接口必须 `@SaCheckPermission`（确需匿名白名单的接口才用 `@SaIgnore` + 服务层自查 + `@RateLimiter`；框架自带匿名端点仅登录/验证码/SSE 关闭等，业务模块当前无匿名接口）
- 权限串**必须三段式**（`analytics:center:query`），两段式匹配不上超管 `*:*:*`
- 永不 log token / 密码 / 明文 IP
- 不信任客户端传的 `createBy`/`updateBy`/`tenantId`——框架自动填
- 租户隔离是硬边界：不做跨租户数据合并
- **按 id 取「属于某人的」资源必须二次校归属**：`@SaCheckPermission` 只管「能不能调这个接口」，不管「这条数据是不是你的」；读出后要校 `tenantId` + `userId`（先例 `AssistantMemoryService#edit`、`RunLedgerService#ensureConversation`），否则换个 id 就能读别人的。
- **展示层也要脱敏**：手机号 / 邮箱 / 证件号挂 `@Sensitive`（模板 `SysUserVo:64,70`）；**存储加密 ≠ 展示脱敏**。

## Database Rules

- ID 雪花 `ASSIGN_ID`，禁自增；业务表逻辑删除 `del_flag`，禁物理删
- 新表必须带 `tenant_id`（除非进 `tenant.excludes`）
- **DDL 必须沉淀**：`script/sql/baseline/` 对应文件 + `script/sql/migration/` 新增迁移（可重复执行）
- 大表操作分批限轮（参照 `ProfileRetentionJob`：单轮 1 万行、封顶 100 轮）
- **唯一性必须落 DB 唯一索引**，不许「先查再插」：并发下必出脏数据（正例 `uk_profile_event_uid` = `(tenant_id, source, event_uid)`）。
- **禁外键与级联**：关联在应用层做（现库 0 个外键，保持）；外键会卡逻辑删除、分批清理与按租户建库。
- **SQL 参数一律 `#{}`**：`${}` 只允许 MyBatis-Plus 生成的 `${ew.xxx}`（现存 3 处全是框架注入）；手写 SQL 出现 `${}` 就是注入面。

## API Design

- 现存约定：GET 查询 / POST 写入；DELETE 存量存在于采集 demo 模块——新接口跟随现有风格，改动词前先查同路径冲突
- 分页：`PageQuery` 入 → `TableDataInfo<T>` 出；导出用 FastExcel（ruoyi-common-excel）
- **分页必须封顶**：`PageQuery.DEFAULT_PAGE_SIZE = Integer.MAX_VALUE`（`PageQuery.java:74`），不传 `pageSize` 等于查全表；新接口一律 `Math.min(…, 100)`（参照 `AssistantActionController:65`、`ProfileQueryService:157`）。
