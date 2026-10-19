# SmartDock 架构讲解

本文面向想"读懂这套代码"的开发者：先讲全局分层，再按模块拆解关键决策点。

## 1. 全局分层

```
┌─ frontend (Vue3 + Element Plus)
│    views/dcs/{mission,todo,audit,notice,dashboard,profile}   ← 微核心页面族
│    api/dcs/core/*                                            ← 契约层（与后端端点一一对应）
├─ ruoyi-admin（组合根）
│    web/adapter/*    ← 跨模块端口适配（通知桥/审核探针），依赖方向在此收口
├─ ruoyi-modules
│    ruoyi-dcs            微核心（不依赖任何业务模块）
│    ruoyi-profile-engine 画像引擎（只依赖公共件）
│    ruoyi-file-security  文件安全（依赖 dcs 的端口接口，不依赖实现）
│    ruoyi-risk / ruoyi-ai-billing / ruoyi-assistant
└─ ruoyi-common/*     RuoYi 框架公共件（mybatis/satoken/redis/oss/...）
```

**依赖铁律**：微核心不 import 任何兄弟模块；跨模块协作一律通过
「端口接口定义在消费方、实现落在 admin 组合根（web/adapter）」的单向依赖收口。

## 2. 微核心（ruoyi-dcs，包 com.smartdock.dcs）

### 2.1 数据模型（三张主表 + 通知）

- `dcs_task`：任务头。`status ∈ {DRAFT, COLLECTING, CLOSED}` 三态；
  `assignee_user_ids`（目标成员）、`auditor_user_ids`（审核人）为 JSONB 数组，
  `audit_mode = PUBLISHER`（发布人直审）。
- `dcs_task_field`：动态字段定义。`field_type ∈ {text, number, date, select, multi_select, attachment}`，
  select 族带 `options` 数组，`required` 为 0/1。
- `dcs_submission`：提交。同一 (task, user) 多行即**多版本**（version 递增），
  `status ∈ {PENDING_AUDIT, APPROVED, REJECTED}`；`data` JSONB 存字段值。
- `dcs_notice`：站内通知。`dedup_key` + 部分唯一索引（`uk_dcs_notice_dedup`）实现幂等投递。

### 2.2 状态机与关键不变量

```
任务: DRAFT ──publish──▶ COLLECTING ──close(需在途提交审毕)──▶ CLOSED
提交: (无) ──submit──▶ PENDING_AUDIT ──approve──▶ APPROVED
                                └──reject──▶ REJECTED ──重提(新版本)──▶ PENDING_AUDIT
```

- 只有 `COLLECTING` 期成员可提交；已有 PENDING_AUDIT/APPROVED 时不可重复提交。
- 退回后重提生成新版本，旧版本只读保留（审计轨迹）。
- `dcs_audit` 表只追加（append-only）记录每次决定，`trail` 端点按提交聚合回放。

### 2.3 权限锚（最小集）

| 权限串 | 用途 |
|---|---|
| `dcs:task:list/add/edit/publish` | 发布人侧 |
| `dcs:submission:submit` | 成员侧（提交/我的提交）|
| `dcs:audit:audit` | 审核决定与队列 |
| `dcs:notice:list` | 站内通知 |

`GET /dcs/task/{id}` 返回 `{task, fields, mySubmission}` 三段式，前端详情页一次拿全。

## 3. 画像引擎（ruoyi-profile-engine，包 com.smartdock.profile）

事件源架构三表：`profile_event`（原始事件，按 `source` 分域）→
`profile_score_dimension`/`profile_score_rule`（维度与规则配置）→
`profile_score_snapshot`（评分快照，保留历史）。重算以 `profile_recalc_batch` 批次留痕。

查询端点（锚 `profile:score:query`）：`/profile/subject/page`（主体分页）、
`/profile/score/{source}/{subjectId}`（最新快照 + 历史）、`/profile/dimension/list`。

接入新业务只需两步：向 `profile_event` 写事件（或走 admin 端点的 publish API），
配置维度规则后触发 recalc——不需要改引擎代码。

## 4. 文件安全（ruoyi-file-security，包 com.smartdock）

OSS 上传事件 → `OssUploadedEventListener` 建单，判定序（顺序不可调换）：

```
⓪ 守卫硬拦（宏/伪装/敏感词命中 → REJECTED）
⓪.5 反馈/助手类目豁免（EXEMPT）
① 总开关
② 管理员豁免
③ 类目需审配置
④ 其余 → PENDING（人工队列）
```

审核决定产生 `FILE_REVIEW_REJECT` 通知（经 admin 的 FileReviewNoticeAdapter 桥接到微核心通知表）。

## 5. AI 助手（ruoyi-assistant）

- SSE 流式（前端 `utils/sse.ts`），AG-UI 风格事件协议；
- 工具循环（查待办/任务进度/看板口径）通过 SPI 注入，页面上下文随对话框携带；
- 会话状态落 `agentscope` schema（JDBC state store，独立 schema 隔离）；
- 计费：ai-billing 的钱包/积分双轨（`dcs_tenant_wallet`、`dcs_ai_credit_ledger`），
  幂等键防重复落账。

## 6. 前端契约层

`frontend/src/api/dcs/core/` 与后端端点一一对应，是前后端的**单一事实源**：
RuoYi 拦截器对 `R<T>` 信封原样返回、对 `TableDataInfo` 返回裸 `{total, rows}`——
core 层内统一解包，页面只消费业务形状。

## 7. 测试地图

- 微核心状态机/三方鉴权：`ruoyi-dcs` 模块测试
- 引擎评分/重算：`ruoyi-profile-engine`
- 适配器桥接（租户穿越/幂等）：`ruoyi-admin/src/test/.../adapter`
- 前端：vitest（notice store 单飞守卫/助手上下文/风控呈现等 200 例）
