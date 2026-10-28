# 用户画像引擎 · 深度文档

> 本文承接 README「引擎：用户画像」章节的实现细节：完整数据模型、通用接入协议、
> 评分模型与 RFM、标签分群与低分复核、行为分析中心。能力概览与接入三步见 README。

![用户画像：事件 → 指标 → 快照](images/profile-flow.svg)

## 1. 完整数据模型

| 表 | 作用 | 关键字段 |
|---|---|---|
| `profile_event` | 原始事件（追加写入） | `source`、`event_type`、`event_uid`、`subject_type`、`subject_id`、`occurred_time`、`props(jsonb)` |
| `profile_subject` | 主体登记（事件写入时 upsert） | 主体、首末事件时间、事件计数 |
| `profile_protocol` | 业务源接入协议清单（`profile.protocol/v1`） | `source`、事件清单、主体类型、保留期 |
| `profile_schema_registry` | 事件 Schema 注册与轻校验 | `event_name`、`schema_version`、`required`、`properties` |
| `profile_identity_link` / `profile_identity_merge` | 身份图谱：匿名 → 登录主体归并 | `identity_type`、`identity_value`、`subject_id`、合并记录 |
| `profile_score_dimension` | 维度治理（按 `subject_type` 分套） | `dim_code`、`source`、`subject_type`、`calc_mode`、`calc_config(jsonb)`、`score_max`、`enabled`、`builtin` |
| `profile_score_rule` | 评分规则/权重（按 `subject_type` 分套） | `rule_version`、`weights(jsonb)`、`subject_type`、`enabled` |
| `profile_recalc_batch` | 批量重算批次 | `status`、`total_subjects`、`done_subjects`、`last_subject_id`、`error_msg` |
| `profile_score_snapshot` | 版本化快照 | `total_score`、`dimension_detail(jsonb)`、`rule_version`、`prev_snapshot_id`、`calc_time` |
| `profile_score_model` / `profile_model_run` / `profile_model_snapshot` | 通用评分模型（RFM 只是预设；按 `subject_type` 分套） | `model_config(jsonb)`、分箱/权重、模型快照链 |
| `profile_segment` / `profile_segment_member` / `profile_segment_run` | 标签分群 | 规则 DSL、静态名单、成员快照、运行记录 |
| `profile_review_queue` | 低分复核队列 | 触发来源、分数、状态、确认/忽略人 |
| `profile_engine_config` | 按业务源的引擎开关 | 自动重算、复核、去抖窗口 |
| `analytics_session` / `analytics_daily` | 行为分析：会话与日汇总 | 访客、渠道、设备、地域、PV/UV/访问次数、跳出 |
| `analytics_funnel` | 漏斗定义 | 步骤 JSON、窗口天数 |

设计要点：

- **幂等**：`(tenant_id, source, event_uid)` 唯一；同一业务事件重复投递只写一次。
- **主体登记**：首次事件 upsert 主体；后续事件更新首末时间与计数，画像页无需回扫事件表。
- **快照账本**：快照只增不改，`prev_snapshot_id` 串起趋势；事件可按保留期清理，快照不清理。
- **多租户**：所有表带 `tenant_id`；租户隔离由 MyBatis-Plus 租户插件与显式租户上下文共同保证。

## 2. 通用接入协议

画像引擎不绑定具体业务。任何系统接入时，先登记一份协议（`profile_protocol`），
再用同构事件写入；协议版本为 `profile.protocol/v1`。

| 协议要素 | 说明 |
|---|---|
| `source` | 业务源标识（订单、供应链、工程、电商、租赁、用户……），维度、模型、分群均按它隔离 |
| `subjectTypes` | 主体类型清单，如 `user` / `order` / `supplier` / `project` / `tenant` |
| `events` | 事件名、Schema 版本、必填 `props` / `context` |
| `retentionDays` | 事件保留期；快照与模型结果不受影响 |

事件信封（写入 `profile_event`）：

```json
{
  "eventName": "order.paid",
  "eventUid": "order:paid:202610190001:v1",
  "subjectType": "order",
  "subjectId": "202610190001",
  "anonymousId": "visitor-a1b2",
  "sessionId": "s-9f8e",
  "occurredTime": "2026-10-19T10:30:00+08:00",
  "schemaVersion": "1-0-0",
  "props": { "amount": 399, "channel": "wechat" },
  "context": { "pageUrl": "/checkout", "deviceType": "mobile" }
}
```

事件信封字段一览（`ProfileEventBo`，发布接口与 CSV 导入共用同一套语义）：

| 字段 | 必填 | 说明 |
|---|---|---|
| `source` | ✅ | 业务源标识，与协议登记一致；维度 / 模型 / 分群 / 保留期按它隔离 |
| `eventName` | ✅ | 事件名，点分命名（`order.paid` / `page_view`） |
| `eventUid` | 发布必填 | 幂等键（≤128 字符）：`(tenant, source, eventUid)` 重复投递返回 `false` 不报错；CSV 留空自动生成 `csv-<uuid>` |
| `subjectType` / `subjectId` | `subjectId` 必填 | 主体 ID 必填；`subjectType` 可空回落 `user`；分析优先认登录主体 |
| `anonymousId` | 建议 | 未登录访客 ID（匿名事件建议携带）；与登录主体自动归并，绝不互搬身份 |
| `sessionId` | 建议 | 显式会话 ID；缺省按 30 分钟无操作切分 |
| `occurredTime` | 建议 | 事件发生时间；缺省取落库时间 |
| `schemaVersion` | 可选 | Schema 版本号（如 `1-0-0`），配合轻校验 |
| `props` / `context` / `traits` | 可选 | 业务属性 / 页面上下文 / 主体标签，均为 JSON 对象；派生属性（响应时长、质量分）由业务算好放 `props` |

Java 侧接入只依赖一个门面 Bean（Spring Boot 业务工程引 `ruoyi-profile-engine` 模块即可）：

```java
@RequiredArgsConstructor
@Service
public class OrderEventReporter {

    private final ProfileEventPublisher eventPublisher;   // 引擎门面, 幂等追加

    public void onOrderPaid(Order order) {
        ProfileEventBo event = new ProfileEventBo();
        event.setSource("order");                          // 与协议登记的 source 一致
        event.setEventName("order.paid");
        event.setEventUid("order:paid:" + order.getOrderNo()); // 幂等键: 重放/重试只写一次
        event.setSubjectType("order");
        event.setSubjectId(order.getOrderNo());
        event.setOccurredTime(order.getPaidAt());
        event.getProps().put("amount", order.getAmount());
        event.getProps().put("channel", order.getChannel());
        eventPublisher.publish(event);                     // 返回 false = eventUid 重复, 已幂等跳过
    }
}
```

- **身份归并**：同一 `anonymousId` 先产生匿名事件，登录后带 `userId`（`subjectType=user`）的事件
  会把身份链写入 `profile_identity_link`；分析会话优先采用归并后的登录主体。
- **Schema 轻校验**：只检查必填键和基础类型；未注册 Schema 的事件直接放行，保证旧接入零破坏。
- **三种扩展点**：`DimensionStrategy`（自定义维度口径）与 `ProfilePostProcessor`（快照落库后的
  后处理回调）是注册即用的 Spring Bean；`EventQueryPort`（事件查询端口）是引擎内部的
  查询收口接口，换存储或加缓存时改 `ProfileScoreEngine` 的适配器实现。三者均只读、不写表。

## 3. 评分模型与 RFM

`profile_score_model` 把「指标 → 分箱 → 权重 → 等级」做成 JSON 配置，RFM 只是内置预设之一。
同一张表可以配置订单金额模型、供应商履约模型、工程进度模型，无需新增 Java 类。

| 能力 | 说明 |
|---|---|
| 模型管理 | 新建/复制/启停/删除；内置模型不可删，可改分箱与权重 |
| 运行与快照 | 每次运行写 `profile_model_run` 与逐主体 `profile_model_snapshot`，`prev_snapshot_id` 串趋势 |
| 分箱 | `fixed`（固定边界）或 `percentile`（分位点）；方向 `higher` / `lower` |
| RFM 预设 | R/F/M 三维 × 2 分位 = 8 个中文分层（重要价值客户、一般保持客户……），另附高/中/低价值兜底 |
| 自动重算 | 新事件写入后按 `profile_engine_config.debounce_ms` 合并触发，同主体只算一次 |

画像 RFM 页面提供分层分布、维度质量、平均标准分趋势、主体明细和模型配置编辑。

## 4. 标签分群与低分复核

**分群**（`profile_segment`）支持两种成员来源：

1. **规则分群**：条件树 `all` / `any`，叶子为「主体属性 / 维度分 / 事件计数」比较，
   可带时间窗；先预览命中人数，再固化为成员快照（`profile_segment_member`）。
2. **静态名单**：CSV 或逐行主体 ID 导入，适合线下名单、白名单、实验组。

每次执行写 `profile_segment_run`，成员快照可追溯；分群结果可被助手工具、运营页面复用。

**复核**（`profile_review_queue`）用于低分主体的人工兜底：分数低于阈值时入队，
确认或忽略后，除非分数继续下降，否则不再重复入队（抑制重复打扰）。
复核、自动重算、去抖窗口都由 `profile_engine_config` 按业务源单独开关。

## 5. 行为分析中心

分析中心和分析画像共用同一本事件账本，不额外埋点：

```text
profile_event
  └─ 会话重建（analytics_session）
       ├─ 显式 sessionId 优先
       └─ 否则按访问者 + 30 分钟无操作切分
            └─ 日汇总（analytics_daily）→ 趋势 / 终端类型 / 浏览器 / 使用采用度
                 ├─ 漏斗（analytics_funnel）：按用户顺序匹配步骤，窗口限制相邻间隔
                 ├─ 留存：按首次使用日分组，统计第 N 天回访
                 ├─ 页面与搜索：page_view / keyword 下钻
                 ├─ 自定义分析：维度 × 指标（页面使用次数 / 活跃用户数 / 会话 / 事件数）
                 └─ 使用明细：会话 + 事件时间线
```

口径固定且可解释（2026-10-28 单源重构起，分析中心只看系统自己的使用）：

- **页面使用次数** = `page_view` 事件数；**活跃用户数** = 去重用户；**会话** = 去重会话。
  概览页已改用这些新名称；「页面与搜索」页签的列头和部分接口字段仍沿用历史名
  PV / UV（口径相同，只是叫法没换），后续版本统一。
- **单页离开** = 会话内有意义的事件不超过 1 个（`page_leave` 是收尾回执，不算有意义动作）。
  旧称「跳出率」已更名。
- **新同事** = 该系统内首次出现的用户；回访 = 之前已有会话。
- 漏斗步骤按用户「首次满足顺序」计数，跨会话超过窗口不累计。

数据进系统只有两条路（外部网站采集口与 SDK 已于 2026-10-23 移除）：

- **内部业务系统走事件协议**：服务端调用 `ProfileEventPublisher` 门面发布
  `ProfileEventBo`（事件名/主体/会话/上下文/属性），事件按 `eventUid` 幂等，
  大批量建议分批发布。
- **离线数据走 CSV 导入**：管理台「数据分析 → 数据导入」粘贴 CSV（最多 5000 行、5MB），
  事件同样走 Schema 校验与幂等写入。

  CSV 细则：

  | 规则 | 说明 |
  |---|---|
  | 必填列 | `eventName`（或 `event_name`）——唯一必填列，其余全可省 |
  | 幂等列 | `eventUid`——留空自动生成 `csv-<uuid>`；同一份文件重复导入想不重复计数，**必须自带稳定的 eventUid** |
  | 主体列 | `subjectType`（缺省 `user`）、`subjectId`、`anonymousId`、`subjectName` |
  | 会话/时间列 | `sessionId`、`occurredTime`（或 `time`，支持常见格式） |
  | 属性列 | `props` / `properties` / `context` 为 JSON 字符串；`pageUrl`、`keyword`、`referrer`、`channel`（或 `sourceChannel`）、`campaign`、`deviceType`、`os` 可作独立列，导入时自动并入 `context` |
  | 编码 | Excel/Windows 导出的首列 BOM 会自动剥掉；换行与引号按标准 CSV 转义 |
  | 演示数据 | 官方合成演示脚本写入的数据带 `demo-` 前缀 eventUid；跨天重播前先按前缀清旧数据，否则叠加 |

分析页面在「画像与分析 → 数据分析」，包含数据导入、使用概览、页面与搜索、
路径分析、转化漏斗、持续使用、活跃分析、使用明细、自定义分析和实时看板十个页签
（2026-10-28 单源换算后按「系统使用」口径统计；页面浏览类指标只对接入页面埋点的数据有数）。

## 6. 魔改指南：把画像改成你的业务（三层路径）

想改画像的口径，从轻到重有三层路。绝大多数需求停在第一层就够了。

| 想改什么 | 用哪层 | 在哪动手 |
|---|---|---|
| 维度算什么、怎么打分 | 第一层 | 画像工作室 → 维度配置 |
| 各维度占多少分 | 第一层 | 画像工作室 → 权重规则（草稿 → 校验 → 影响评估 → 发布） |
| 圈人条件 | 第一层 | 画像 → 分群（规则分群 / 静态名单） |
| 漏斗步骤、自定义分析 | 第一层 | 数据分析 → 漏斗维护 / 自定义分析 |
| 一整套维度+权重+分群+漏斗 | 第二层 | 画像工作室 → 场景模板（套用 / 导入 / 导出） |
| 事件聚合算子表达不了的口径 | 第三层 | 写 Java：`DimensionStrategy` 扩展点 |

### 第一层：零代码改配置

页面上点一点，不写一行代码：

- **维度 = 指标口径**。工作室 → 维度配置里新建 EVENT_AGG 维度：选算子
  （次数 / 去重数 / 求和 / 平均 / 比率 / 最近值）→ 绑定事件名和属性键 → 定打分方式
  （线性区间或分档）。新建对话框里有单维度模板，点一下预填全部字段。
- **权重 = 版本化的配方**。改权重走草稿：保存 → 校验（覆盖全部启用维度且合计为 1）→
  影响评估（多少主体分数会变）→ 发布。发布本身不重算历史，触发重算后新权重才进快照。
- **分群条件字段白名单**：`totalScore` / `eventCount` / `lastEventDays` /
  `dimension.<维度码>` / `property.<属性键>` / `tag.<码>.<键>`，比较符等于/大于/包含等，
  不支持任意 SQL——规则只在应用侧对候选集求值，天然防注入。
  **注意**：`dimension.<维度码>` 取的是该维度的 **0~100 归一分数**，不是业务原值——
  想按「客单价 ≥ 300 元」圈人，要把 300 元按该维度的打分区间换算成分数阈值再填。

### 第二层：模板 JSON（导入导出分享）

工作室 → 场景模板提供 7 套内置推荐场景（内部协作采用度为推荐默认，另有零售商城、
外卖平台、文旅景点、社区内容、SaaS 工具、MES 操作工），每套点齐「维度 + 权重 + 分群 +
漏斗」四件套：

- **套用不覆盖**：同编码的维度 / 分群 / 漏斗一律跳过；权重只生成草稿，去「权重规则」
  核对发布后才影响打分。
- **事件名是示例**：行业模板里的事件名（如 `order.paid`）是通用示例，套用后要按
  [接入指南](profile-integration.md)换成你业务的真实事件名。
- **导出 → 改 → 导入**：任何模板可导出成 JSON 文件，改完（或发给同事改完）在
  「我的模板」里导入，导入即租户内共享。

模板 JSON 的形状（导出文件即此格式；下面是一份**完整可导入**的最小模板——权重必须
覆盖全部维度且合计≈1、漏斗至少 2 步、分群阈值用分数口径）：

```json
{
  "code": "my_retail",
  "name": "我的零售模板",
  "scenario": "零售商城",
  "description": "买得多、买得贵、退款少的顾客分高。事件名请换成你商城的真实事件名。",
  "dimensions": [
    {
      "dimCode": "purchase_frequency",
      "dimName": "购买频次",
      "calcMode": "EVENT_AGG",
      "calcConfig": {
        "score": { "type": "linear", "lo": 0, "hi": 20, "direction": "higher" },
        "metric": { "operator": "COUNT", "target": { "eventType": "order.paid" } },
        "window": { "type": "rolling", "days": 90 },
        "missing": "zero"
      },
      "scoreMax": 100,
      "sortOrder": 1
    },
    {
      "dimCode": "my_avg_order",
      "dimName": "客单价",
      "calcMode": "EVENT_AGG",
      "calcConfig": {
        "score": { "type": "linear", "lo": 50, "hi": 500, "direction": "higher" },
        "metric": { "operator": "AVG", "prop": "amount", "target": { "eventType": "order.paid" } },
        "window": { "type": "all" },
        "missing": "neutral"
      },
      "scoreMax": 100,
      "sortOrder": 2
    }
  ],
  "weights": { "purchase_frequency": 0.5, "my_avg_order": 0.5 },
  "segments": [
    {
      "segmentCode": "high_value",
      "segmentName": "高价值顾客",
      "description": "客单价维度分≥56, 约对应客单价 300 元(dimension.字段取 0~100 分数)",
      "ruleConfig": {
        "match": "ALL",
        "conditions": [{ "field": "dimension.my_avg_order", "op": "GTE", "value": 56 }]
      }
    }
  ],
  "funnels": [
    {
      "funnelCode": "mall_conversion",
      "funnelName": "逛购下单",
      "steps": [
        { "eventName": "page_view", "name": "看商品" },
        { "eventName": "cart.add", "name": "加入购物车" },
        { "eventName": "order.paid", "name": "支付下单" }
      ],
      "windowDays": 7
    }
  ]
}
```

导入校验会拦住这些坏模板：编码不是字母开头 / 权重合计不为 1、缺维度权重或引用了
不存在的维度 / 维度只允许 `EVENT_AGG`（STRATEGY 引用宿主环境的 Bean，搬不走）/
维度名超 100 字、scoreMax 超出 1~1000 / 分群字段不在白名单 / 漏斗步骤数不在 2~10、
窗口天数超出 1~90 / 模板定义超过 256KB。

权限说明：套用/导入/删除按钮用 `profile:scenario:edit` 权限——它一次性预填维度、权重、
分群、漏斗四类配置，因此授这个权限等于同时给了这四类配置的编辑入口，请只授给负责
画像配置的管理员。

### 第三层：代码扩展点

事件聚合表达不了的口径，写 Java。前两个是注册即用的 Spring Bean；第三个是引擎内部
的查询收口接口（改它要动引擎代码）。三者都只读、不写表：

1. **`DimensionStrategy`**：自定义维度算子——实现 `source()` / `dimCode()` 与求值方法，
   工作室的 STRATEGY 维度会自动发现它。
2. **`ProfilePostProcessor`**：快照落库**之后**的回调——对新快照的标签字段做补充加工
   （比如按外部系统回填等级）；分数与总分此时已定，接口约定不改它们。
3. **`EventQueryPort`**：引擎内部的事件查询端口——把策略类对事件账本的查询收口到
   一个接口；换存储或加缓存时改 `ProfileScoreEngine` 里的适配器实现。

```java
@Component
public class LoyaltyDimension implements DimensionStrategy {

    @Override
    public String source() { return "dcs"; }

    @Override
    public String dimCode() { return "loyalty"; }

    @Override
    public DimensionResult calc(DimensionContext ctx) {
        // 只读: ctx 提供该主体的事件查询; 禁止写表
        // 抛异常由引擎捕获并按维度的 missing 策略处理, 不中断其他维度
        DimensionResult result = new DimensionResult();
        result.setScore(java.math.BigDecimal.valueOf(80));
        result.getTags().put("basis", "近 90 天持续活跃");
        return result;
    }
}
```

写完在工作室建一个 STRATEGY 维度指向它即可，其余（权重、发布、重算）与普通维度完全一样。
