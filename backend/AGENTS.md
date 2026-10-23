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
- 当前基线：**75 个测试全绿**——新增功能必须配 `@Tag("local")` 纯函数单测（写法参照 `AnalyticsSessionizerTest`）
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

## Security Rules

- 新接口必须 `@SaCheckPermission`（确需匿名白名单的接口才用 `@SaIgnore` + 服务层自查 + `@RateLimiter`，当前无此类接口）
- 权限串**必须三段式**（`analytics:center:query`），两段式匹配不上超管 `*:*:*`
- 永不 log token / 密码 / 明文 IP
- 不信任客户端传的 `createBy`/`updateBy`/`tenantId`——框架自动填
- 租户隔离是硬边界：不做跨租户数据合并

## Database Rules

- ID 雪花 `ASSIGN_ID`，禁自增；业务表逻辑删除 `del_flag`，禁物理删
- 新表必须带 `tenant_id`（除非进 `tenant.excludes`）
- **DDL 必须沉淀**：`script/sql/baseline/` 对应文件 + `script/sql/migration/` 新增迁移（可重复执行）
- 大表操作分批限轮（参照 `ProfileRetentionJob`：单轮 1 万行、封顶 100 轮）

## API Design

- 现存约定：GET 查询 / POST 写入；DELETE 存量存在于采集 demo 模块——新接口跟随现有风格，改动词前先查同路径冲突
- 分页：`PageQuery` 入 → `TableDataInfo<T>` 出；导出用 FastExcel（ruoyi-common-excel）
