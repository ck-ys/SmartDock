# 画像接入指南（10 分钟版）

> 本文是「怎么把你的业务系统接进画像」的**唯一真源**。系统内置的「任务转发」演示模块（`ruoyi-modules/ruoyi-dcs`）就是官方参考实现，本文所有写法与它的真实代码一致。
>
> 适用：当前版本（**单源口径已生效**：`source` 字段兼容保留，传任意值或不传都会被引擎统一收口，你的调用代码怎么写都能落库）。

## 0. 你将在 10 分钟里做什么

给业务代码加一个「事件上报」类，在关键动作发生时调一行方法。**成功判据**：触发一次业务动作后，打开「用户画像」页，几秒内能看到这个主体（人/订单/工单），点进去能看到这条事件。

先决条件：你的业务代码和 SmartDock 在同一个后端工程里（下文第 2 节）；不是 Java 或不想写代码的，走第 10 节 CSV 导入。

## 1. 主例子：照内置「任务转发」demo 抄

内置 demo 模块 `ruoyi-dcs`（多任务转发采集演示）就是为「给你抄」准备的，三个文件看懂即可照搬：

| 看什么 | 打开哪个文件 | 学什么 |
|---|---|---|
| 注入与调用 | `ruoyi-dcs/.../service/DcsTaskService.java` | 构造注入 `ProfileEventPublisher`，业务方法里发事件，失败只记日志不阻断业务 |
| 事件怎么填 | 同上 `publish()` 附近 | 事件名点分小写、属性数值放数字、显示名兜底 |
| 编号怎么定 | `ruoyi-profile-engine/.../ProfileUids.java` | 三段式/四段式幂等编号（第 5 节） |

## 2. 挂模块五步（新建业务模块必读）

1. `backend/ruoyi-modules/pom.xml` 的模块清单加一行你的模块名；
2. 你模块的 pom 依赖照抄 `ruoyi-dcs/pom.xml`，另加一行 `org.dromara:ruoyi-profile-engine`（版本由父工程统一管理，不用写版本号）；
3. `backend/ruoyi-admin/pom.xml` 加上你模块的依赖。**不加这行模块不会进安装包，且不会报错**；
4. **包名红线**：代码必须放 `com.smartdock.你的模块名` 包下。启动扫描和数据库映射扫描都按这个前缀写死，包名放错=功能静默失效；
5. Service 里注入 `ProfileEventPublisher`（普通 Spring 接口，构造注入），照第 3 节发事件。

## 3. 发第一条事件（整类可粘贴编译）

```java
@RequiredArgsConstructor
@Service
public class BizEventReporter {

    private final ProfileEventPublisher eventPublisher;  // 唯一入口

    /** 核心动作发生时，业务代码里调一行：reporter.onOrderPaid(orderNo, userId, amountFen, "wxpay") */
    public void onOrderPaid(String orderNo, Long userId, int amountFen, String channel) {
        ProfileEventBo e = new ProfileEventBo();
        e.setSource("myapp");            // 可不传：单源口径下引擎统一收口，传什么都归入内置源
        e.setEventName("order.paid");    // 点分小写：实体.动作
        e.setEventUid(ProfileUids.uid("myapp.order", "paid", orderNo)); // 编号第一段带模块名（第 5 节）
        e.setSubjectType("member");      // 主体类型（见第 6 节）；内部员工场景用 user（不填默认 user）
        e.setSubjectId(String.valueOf(userId));
        e.setOccurredTime(new Date());   // 可不填，默认=现在
        e.getProps().put("amountFen", amountFen);  // 数值必须放数字类型，不能放字符串
        e.getProps().put("payChannel", channel);
        try {
            eventPublisher.publish(e);   // 返回 false = 编号重复被幂等跳过，不算错误
        } catch (Exception ex) {
            log.warn("[MyApp] 画像事件发送失败 {}: {}", e.getEventUid(), ex.getMessage()); // 失败不阻断业务
        }
    }
}
```

## 4. 事件字段一张表

| 字段 | 必填 | 上限 | 说明 |
|---|---|---|---|
| `source` | 否 | — | 兼容保留：引擎统一收口，传任意值都归入内置源 |
| `eventName` | 是（与 eventType 二选一） | 64 字 | 点分小写 `实体.动作`；**只填这一个**，`eventType` 是旧名字别用 |
| `eventUid` | 是 | 128 字 | 幂等编号，第 5 节 |
| `subjectId` | 是 | 64 字 | 主体编号（字符串） |
| `subjectType` | 否（默认 user） | 32 字 | 统一小写不带空格：user/member/order/operator… |
| `subjectName` | 否 | 128 字 | 显示名，缺省从 traits 兜底 |
| `occurredTime` | 否 | — | 缺省=现在 |
| `props` | 否 | — | 业务属性；**数值放数字、布尔放 1/0**；键名用英文驼峰 |
| `context` / `traits` / `identifiers` | 否 | — | 环境信息/主体静态属性/强标识（邮箱电话请放哈希标识） |

## 5. 事件编号怎么定（最重要的一节）

编号是「同一件事只记一次」的凭据，规则三条：**①第一段永远带模块名**（防两个模块撞车）；②一对象一动作只发生一次的用三段式；③同一对象同一动作会发生多次的，必须加第四段（序号或时间戳）。

| 场景 | 写法 | 判定 |
|---|---|---|
| 支付成功（一笔订单付一次） | `myapp.order:paid:NO001` | 对（三段式） |
| 同一顾客把同一商品加购两次 | `myapp.cart:added:SKU9:C7-2` | 对（四段式，两次都是真行为） |
| 上例偷懒只写三段 | `myapp.cart:added:SKU9` | **错：第二次被静默吞掉**，画像永远少一次，无报错 |
| 随机 UUID 当编号 | — | **错：重试/补发无法去重，同一件事记两遍** |
| 不带模块名 | `order:paid:NO001` | **错：别的模块同名即撞车，后到的被吞** |

## 6. 事件怎么设计（通用四步法，任何系统都能套）

1. **定主体**：你想看「谁/什么对象」的画像？内部系统=员工(user)；面向客户的系统=顾客(member)；也可同时给订单/工单(order/workorder)开画像，各算各的；
2. **列核心动作**：起步 5-8 个就够，想清楚「哪个动作代表用户真的在用」；
3. **定属性**：每个动作带 2-5 个属性，数值型的一律放数字（金额、数量、分钟数）；
4. **连漏斗**：挑 3-4 个事件名连成一条主路径（如 浏览→下单→支付），接完当天就能在「数据分析 → 转化漏斗」看到图。

**场景速查表**（起手式，不是限制——任何客源系统都能按四步法自定义；「画像工作室」为常见场景备了打分模板，也可完全自建）：

| 场景 | 主体类型 | 核心事件参考 |
|---|---|---|
| 内部协作/办公 | user（员工） | 登录成功 · 文档发布 · 审批提交 · 审批通过 |
| 零售商城 | member（顾客）+ order | 商详浏览 · 加购 · 下单 · 支付 |
| 外卖 | member（用户）+ order | 浏览 · 下单 · 支付 · 确认收货 · 提交评价 |
| 文旅景点 | member（游客）+ ticket | 浏览 · 购票 · 入园 · 二次消费 · 提交评价 |
| 社区/内容 | member（用户）+ post | 浏览 · 发布 · 评论 · 点赞 · 收藏 |
| SaaS 工具 | user（成员） | 登录成功 · 核心功能使用 · 邀请同事 · 数据导出 |
| MES/制造 | operator（操作工）+ workorder | 开工报工 · 完工报工 · 质检提交 |

> 单个教学点：一个事件只有一个主体。比如质检事件主体=质检员、工单号放属性；想按工单分析就用属性分组。

## 7. 发完之后去哪看（三条验证路）

1. **画像页**（最快）：主体几秒内出现并带这条事件；分数若显示约 2 秒内刷新（事件触发自动重算）；
2. **测试发送页**：想先验证格式，「引擎与运维 → 引擎运维 → 引擎管理 → 测试发送」粘一段 JSON 事件数组发一条，发完点「去画像页看效果」；
3. **查库**：`SELECT event_name, subject_id, occurred_time FROM profile_event WHERE event_uid LIKE 'myapp.%' ORDER BY occurred_time DESC;`

## 8. 常见坑对照表

| 坑 | 错误写法 | 后果 | 正确做法 |
|---|---|---|---|
| 后台任务里发事件 | 定时任务/消息监听里直接 publish | 整条被拒（缺租户标记），只留一行日志 | 先用 `TenantHelper.dynamic(租户号, () -> ...)` 包住再发（参考 `RiskBehaviorSinkAdapter` 写法） |
| 编号撞车 | 两个模块都用 `order:paid:1` | 后到的被静默吞掉 | 编号第一段带模块名 |
| 数值写成文字 | `props.put("amount","99.9元")` | 算分时被跳过，不报错 | 放数字；带单位的自己换算 |
| 主体大小写混用 | "Member" / " member " 混发 | 同一个人裂成多个主体 | 统一小写无空格，建议定义常量 |
| 新旧字段双写 | eventName 和 eventType 填不同值 | 三个子系统各认一个，数据时有时无 | 只填 eventName |
| CSV 重复导入 | 同文件导两遍且没带编号列 | 每次生成新编号，数据双计 | CSV 带 eventUid 列，或只导一次 |

## 9. 想要分数：50 分真相

事件发进来后画像页**立刻能看到主体**（这步真的零配置）。但**没配打分规则时每人都是中性 50 分**——不是自动算分，也不是坏掉。想要能区分好坏的分数：

1. 去「画像工作室」新建维度，从模板卡（活跃度/完成率/内容消费/流失风险）挑一张一键预填，把模板里的示例事件名换成你自己的；
2. 配好权重发布 → 触发重算 → 约 2 秒内分数更新。

> **警告**：不配打分规则就大量发事件，50 分低于复核线 60，会把「低分复核」队列刷满（全是假警报）。要么先配规则，要么先在引擎运维把复核暂停。

## 10. 不写 Java 也能进数据

「数据分析 → 数据导入」贴 CSV：表头必须含 `eventName`，可选 17 列；单次 ≤5000 行 / ≤5MB；**建议带 `eventUid` 列**（不带则每次生成新编号，重复导入同一文件会双计）。

## 附录 A：长度与命名红线

eventName ≤64 · eventUid ≤128 · subjectType ≤32 · subjectId ≤64 · anonymousId/sessionId ≤128 · schemaVersion ≤32（source 已被单源收口，不校验）。属性键名英文驼峰（`^[A-Za-z][A-Za-z0-9_]{0,62}$`）。超长直接抛错拒收。

## 附录 B：术语一句话

- **主体**：被画像的对象（一个人/一张订单），由 `subjectType + subjectId` 唯一确定；
- **事件**：主体做的一件事，append-only 只增不改，记错只能靠新事件覆盖口径；
- **属性（props）**：事件的业务细节，数值型给算分用。

## 附录 C：想深度魔改

画像引擎是配置驱动的：维度/权重/分群/自定义分析全部不用写代码；进阶扩展点（自定义维度算子、事件后处理器、外部查询口三个 Spring Bean 接口）见 [profile-engine.md](profile-engine.md) 的扩展点章节；打分模板支持导入导出分享（模板库见画像工作室）。
