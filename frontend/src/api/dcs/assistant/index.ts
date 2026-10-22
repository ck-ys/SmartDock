/**
 * 助手 REST 端点封装（批E Task E1）——批G 三控制器对照：
 * AssistantStatusController / AssistantConversationController / AssistantFeedbackController。
 * 全部走 utils/request.ts（鉴权/防重/错误 toast 统一）。
 */
import request from '@/utils/request';

/** 悬浮球出现条件+今日概况数据源（G1 status 端点；灰球=enabled 无模型） */
export interface AssistantStatus {
  /** 快刀批 Q6：助手对外称呼（租户可改；后端两级默认合并后必有值）——nameState 全局态数据源 */
  name?: string;
  enabled: boolean;
  modelConfigured: boolean;
  degraded: boolean;
  dailyRoundLimitPerUser: number;
  dailyBudgetAmount: string;
  turnsUsedToday: number;
  remainingTurnsToday: number;
  /** 批B B2：带 threadId 时返回——该会话末次模型调用 tokens（上下文环分子；null=空态） */
  contextTokens?: number | null;
}

export interface AssistantConversation {
  id: number | string;
  threadId: string;
  title: string;
  lastMessageTime: string;
  status: string;
  /** Q4（业务批4）：后端瞬态回填——该会话有 RUNNING 中的 run（刷新后列表仍可标"正在回答…"） */
  running?: boolean;
}

export interface AssistantMessage {
  id: number | string;
  role: 'user' | 'assistant';
  content: string;
  model?: string;
  endReason?: string;
  totalTokens?: number;
  /** 工具步骤 JSON（批J-4）：[{id,name,argsText,brief,status}]——历史回放恢复回合 stat/步骤行 */
  toolSteps?: string | null;
  /** LLM 追问建议 JSON 数组（批5 Q7-3，assistant 行才有）：["q1","q2","q3"]，空=静态池 */
  followupSuggestions?: string | null;
  createTime: string;
}

export interface AssistantAction {
  id: number | string;
  runId: string;
  threadId: string;
  toolName: string;
  argsJson?: string;
  status: string;
  resultSummary?: string;
  createTime: string;
}

/** 助手三态+用量（悬浮球出现条件=enabled && modelConfigured；拍板⑤ 未开=零加载；
 *  批B B2：threadId 可选——带上则返回 contextTokens（合并环上下文占用分子） */
export function getAssistantStatus(threadId?: string) {
  return request<AssistantStatus>({
    url: '/dcs/assistant/status',
    method: 'get',
    params: threadId ? { threadId } : undefined
  });
}

/** 业务反馈批1015b Q14：联网搜索配置读（密钥只出掩码；含平台默认开关状态） */
export function getWebsearchInfo() {
  return request<{ configured: boolean; maskedKey: string; platformDefault: boolean; configKey: string }>({
    url: '/dcs/assistant/admin/websearch',
    method: 'get'
  });
}

/* ===== 稳流② Q8②：运行健康（run 终态分布 + 追问失败分类分布，进程内累计） ===== */
export interface AssistantHealthReport {
  /** 本次启动时刻（Asia/Shanghai ISO 串）——计数窗口起点 */
  since: string;
  /** run 终态分布：key=status|reason（如 FINISHED|normal / INTERRUPTED|client-disconnect） */
  runOutcomes: Record<string, number>;
  /** 追问分布：succeeded/answer-too-short/pool-rejected/budget-gated/empty-raw/parse-failed/timeout/other */
  followupOutcomes: Record<string, number>;
}

export function getAssistantHealth() {
  return request<AssistantHealthReport>({ url: '/dcs/assistant/admin/health', method: 'get' });
}

/* ===== 能力① Q4/Q7：用户视角技能目录（触发词一并下发；perm=use） ===== */
export function getAssistantSkillCatalog() {
  return request<
    Array<{ id: string; name: string; desc: string; minRole: 'member' | 'publisher'; triggers?: string[] }>
  >({ url: '/dcs/assistant/skills', method: 'get' });
}

/** 业务反馈批1015b Q14：搜索连通测试（key 可选=先验新钥匙再替换；不带=测当前生效 key） */
export function testWebsearch(key?: string) {
  return request<{ ok: boolean; latencyMs?: number; message: string }>({
    url: '/dcs/assistant/admin/websearch/test',
    method: 'post',
    data: key ? { key } : {}
  });
}

/** 租户配置读（perm=dcs:assistant:config；批F 管理端页数据源） */
export interface AssistantEffectiveConfig {
  rowPresent: boolean;
  enabled: boolean;
  dailyRoundLimitPerUser: number;
  dailyBudgetAmount: string;
  memoryEnabled: string;
  compactionTriggerTokens: number;
  /** 每人每日联网搜索次数上限（业务批3-④；后端 effective 记录序列化，缺省平台 20） */
  websearchLimitPerUser?: number;
  /** 每日摘要开关（五需求批 R4b：'1'=每日 08:00 给发布人推未交摘要，'0'=关） */
  digestEnabled?: string;
  /** 助手对外称呼（快刀批 Q6；后端合并平台默认，非空=租户已自定义） */
  assistantName?: string;
}

export function getAssistantConfig() {
  return request<{ effective?: AssistantEffectiveConfig }>({ url: '/dcs/assistant/config', method: 'get' });
}

/**
 * 租户配置写载荷（AU-18）：entity DcsAssistantConfig.enabled 是 String 码值（'1'/'0'），
 * 与读侧 effective.enabled 的 boolean 不是一回事——按读侧签名发 boolean true 会被
 * Jackson 序列化成 "true"，后端 "1".equals 判不中=租户静默判关。写 enabled 必须发 '1'/'0'。
 */
export interface AssistantConfigUpdate {
  enabled?: '1' | '0';
  dailyRoundLimitPerUser?: number;
  /** 业务批3-④：租户联网默认（0=全租户关停；管理页输入框未上，先 API/DB 口径） */
  websearchLimitPerUser?: number;
  dailyBudgetAmount?: string;
  memoryEnabled?: string;
  compactionTriggerTokens?: number;
  /** 五需求批 R4b：每日摘要开关（'1'=每日 08:00 发布人未交摘要通知） */
  digestEnabled?: '1' | '0';
  /** 快刀批 Q6：助手称呼——非空=改；''=清除回落平台默认；不发/undefined=保持现值 */
  assistantName?: string;
  tenantId?: string;
}

/** 租户配置写（upsert；null 字段=保留现值——toggle 只传 enabled 安全） */
export function updateAssistantConfig(data: AssistantConfigUpdate) {
  // 2026-10-16 PUT→POST（铁律#21：WAF 只放行 GET/POST）
  return request({ url: '/dcs/assistant/config/edit', method: 'post', data });
}

/* ===== 上下文压缩（批17 Q2-A：手动通道；自动通道在后端中间件按阈值触发）===== */
/** 手动压缩结果：compacted=false 时 reason 给人话（too_short=对话太短不用压） */
export interface AssistantCompactResult {
  compacted: boolean;
  reason?: 'too_short' | 'empty' | 'skipped';
  beforeTokens?: number | null;
  afterTokens?: number | null;
  summarizedTurns?: number | null;
}
/** 手动压缩当前会话上下文（前文打包成结构化摘要，圆环回落） */
export function compactAssistantContext(threadId?: string) {
  return request<AssistantCompactResult>({ url: '/dcs/assistant/conversation/compact', method: 'post', data: { threadId }, timeout: 180000 });
}

/* ===== 一键汇报导出（五需求批 R4b / Q7-A）：Word=结论+表格 / Excel=表格明细 ===== */
export function exportAssistantReport(data: { format: 'docx' | 'xlsx'; question: string; content: string }) {
  return request<Blob>({ url: '/dcs/assistant/report/export', method: 'post', data, responseType: 'blob' });
}

/* ===== 用户额度管理（业务批3-③ #16）===== */
/** 用户额度行（联表：用户/部门 + 覆盖行 + 今日回合 + 本月费用） */
export interface AssistantQuotaRow {
  userId: number | string;
  nickName: string;
  userName: string;
  deptName: string;
  roundLimitOverride: number | null;
  websearchLimitOverride: number | null;
  disabled: string;
  turnsUsedToday: number;
  effectiveRoundLimit: number;
  monthCost: number;
}

/** 用户额度分页（perm=dcs:assistant:config） */
export function pageAssistantQuota(params: { keyword?: string; disabledOnly?: string; pageNum?: number; pageSize?: number }) {
  return request<{ rows: AssistantQuotaRow[]; total: number }>({ url: '/dcs/assistant/quota/page', method: 'get', params });
}

/** 重置某用户今日额度（perm=dcs:assistant:quota:edit） */
export function resetAssistantQuota(userId: number | string) {
  return request({ url: '/dcs/assistant/quota/reset', method: 'post', data: { userId } });
}

/** 单人限额覆盖（null=清空跟随租户默认） */
export function updateAssistantQuotaLimits(data: { userId: number | string; roundLimit?: number | null; websearchLimit?: number | null }) {
  // 2026-10-16 PUT→POST（铁律#21：WAF 只放行 GET/POST）
  return request({ url: '/dcs/assistant/quota/limits', method: 'post', data });
}

/** 禁用/启用 */
export function setAssistantQuotaDisabled(userId: number | string, disabled: boolean) {
  return request({ url: `/dcs/assistant/quota/${disabled ? 'disable' : 'enable'}`, method: 'post', data: { userId } });
}

/** status：ACTIVE=默认（抽屉/悬浮球兼容）；ARCHIVED=归档折叠条；ALL=会话页本地分组（批H P1-1） */
export function listConversations(params: { pageNum?: number; pageSize?: number; status?: 'ACTIVE' | 'ARCHIVED' | 'ALL' }) {
  return request({ url: '/dcs/assistant/conversation/list', method: 'get', params });
}

export function listMessages(conversationId: number | string, params?: { limit?: number; pageNum?: number }) {
  return request<AssistantMessage[]>({
    url: `/dcs/assistant/conversation/${conversationId}/messages`,
    method: 'get',
    params
  });
}

/** 业务批4 Q1：@ 引用候选任务（七路可见+relation 关系标注，query_my_missions 同源） */
export function mentionTasks(keyword?: string) {
  return request<Array<{ missionId: string; missionName: string; relation: string }>>({
    url: '/dcs/assistant/mention-tasks',
    method: 'get',
    params: keyword ? { keyword } : undefined
  });
}

/** 归档会话（软通道 status=ARCHIVED） */
export function archiveConversation(conversationId: number | string) {
  // 2026-10-16 DELETE→POST（铁律#21：WAF 只放行 GET/POST）
  return request({ url: `/dcs/assistant/conversation/${conversationId}`, method: 'post' });
}

/** 重命名会话（批D P2-7：PATCH title，1-60 字符） */
export function renameConversation(conversationId: number | string, title: string) {
  return request({ url: `/dcs/assistant/conversation/${conversationId}/title`, method: 'post', data: { title } });
}

/** 恢复已归档会话（批D：ARCHIVED→ACTIVE） */
export function restoreConversation(conversationId: number | string) {
  return request({ url: `/dcs/assistant/conversation/${conversationId}/restore`, method: 'post' });
}

/** 会话 TODO 清单（ZCode 对齐 Q2，批A：打开会话恢复状态面板任务清单分区） */
export interface AssistantTodoItem {
  content: string;
  status: 'pending' | 'in_progress' | 'completed';
  priority: 'high' | 'medium' | 'low';
}

export function getConversationTodo(threadId: string) {
  return request<AssistantTodoItem[]>({ url: '/dcs/assistant/conversation/todo', method: 'get', params: { threadId } });
}

/** G3-A1：MCP elicitation 反问卡（CUSTOM 'elicitation' 帧载荷，bridge cardPayload 同构） */
export interface AssistantElicitationCard {
  id: string;
  serverName: string;
  message: string;
  requestedSchema: Record<string, unknown>;
  /** 服务端下发的剩余可答时长（毫秒）——相对时长免疫两端时钟偏差，前端以 Date.now()+remainingMs 定本地到期时刻 */
  remainingMs: number;
}

/** G3-A1：反问作答——与权限 HITL 的 resume 分轨，独立端点回填三态
 *  （accept=提交表单 / decline=拒绝提供 / cancel=关闭） */
export function answerElicitation(threadId: string, id: string, action: 'accept' | 'decline' | 'cancel', content?: Record<string, unknown>) {
  return request({ url: '/dcs/assistant/elicitation/answer', method: 'post',
    data: { threadId, id, action, content: content ?? {} } });
}

/** 批5 Q7-3：本会话最新回答的 LLM 追问建议（回答完成后延时拉一次；空=回退静态池）。
 *  泛型=整个 body（request 层不脱壳，getConversationTodo 同款）；messageId 是字符串——
 *  雪花 19 位超 JS 安全整数，后端 BigNumberSerializer 序列化为 string */
export function getFollowups(threadId: string) {
  return request<{ code: number; msg: string; data: { messageId: number | string; followups: string[] } }>({
    url: '/dcs/assistant/conversation/followups',
    method: 'get',
    params: { threadId }
  });
}

/** 动作账本（断线重连恢复/催办进度数据源，G3） */
export function listActions(params: { runId?: string; threadId?: string }) {
  return request<AssistantAction[]>({ url: '/dcs/assistant/action/list', method: 'get', params });
}

/** 业务反馈批1015c Q15b：创建模板卡「猜你要建」推荐项 */
export interface TemplateRecommendItem {
  name: string;
  reason: string;
  /** true=应季系统预设兜底（零成本）；false=轻快模型生成（贴本租户品类） */
  preset: boolean;
}

/**
 * 猜你要建推荐（轻快模型按本租户近期模板+应季预设生成；失败后端回退预设清单，永不空）。
 * 模块级 60s 去重缓存（三审 P2）：创建卡在抽屉/会话页可能双实例挂载，重复开关卡
 * 也只打一次接口——期间数据不会变（后端按近期模板生成，分钟级新鲜度足够）。
 */
let templateRecCache: { at: number; key: string; req: Promise<{ data?: TemplateRecommendItem[] }> } | null = null;
const TEMPLATE_REC_TTL = 60_000;

export function getTemplateRecommendations(threadId?: string) {
  const now = Date.now();
  const key = threadId ?? '';
  // 键含 threadId（三审 P2）：透传 threadId 的调用不会被别的会话的缓存误喂
  if (templateRecCache && now - templateRecCache.at < TEMPLATE_REC_TTL && templateRecCache.key === key) {
    return templateRecCache.req;
  }
  const req = request<TemplateRecommendItem[]>({
    url: '/dcs/assistant/conversation/template-recommendations',
    method: 'get',
    params: threadId ? { threadId } : undefined
  }) as Promise<{ data?: TemplateRecommendItem[] }>;
  // 失败清缓存须核对还是同一个 promise（三审 P2）：无条件清会把更新的在途请求误踢出缓存
  req.catch(() => {
    if (templateRecCache?.req === req) {
      templateRecCache = null;
    }
  });
  templateRecCache = { at: now, key, req };
  return req;
}

/** 管理端运行记录行（二站① AU-52：动作账本按租户分页+操作人昵称） */
export interface AssistantActionLogRow {
  id: number | string;
  toolName: string;
  status: string;
  resultSummary?: string;
  threadId?: string;
  userName?: string;
  createTime?: string;
}

/** 管理端运行记录（二站① AU-52 分页 + 2026-10-11 日常查询：keyword 模糊命中动作/结果摘要/操作人，status 精确，时间闭区间） */
export function listActionLog(params?: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  status?: string;
  beginTime?: string;
  endTime?: string;
}) {
  return request({ url: '/dcs/assistant/action/log', method: 'get', params });
}
/** 二站④ AU-53：本人长期记忆（记忆页真数据；memory:manage 权限） */
export interface AssistantMemoryRow {
  id: number | string;
  content: string;
  source?: string;
  createTime?: string;
}

export function listAssistantMemory() {
  return request({ url: '/dcs/assistant/memory/list', method: 'get' });
}

export function removeAssistantMemory(id: number | string) {
  // 2026-10-16 DELETE→POST（铁律#21：WAF 只放行 GET/POST）
  return request({ url: '/dcs/assistant/memory/' + id, method: 'post' });
}

/** 点赞点踩（Q13：写 dcs_assistant_feedback，toast 由调用方确认） */
export function submitFeedback(data: { messageId?: number | string | null; rating: 'UP' | 'DOWN'; note?: string }) {
  return request({ url: '/dcs/assistant/feedback', method: 'post', data });
}

export function listFeedback(params?: { pageNum?: number; pageSize?: number }) {
  return request({ url: '/dcs/assistant/feedback/list', method: 'get', params });
}

/** AI 缺口批 Q10：反馈聚合统计（好评率/点踩原因分布/近4周趋势） */
export function getFeedbackStats(days = 28) {
  return request({ url: '/dcs/assistant/feedback/stats', method: 'get', params: { days } });
}

/** 会话附件（附件批 ATTACH：挂会话多轮追问；正文不回传，只回状态） */
export interface AssistantAttachmentItem {
  id: number | string;
  threadId: string;
  fileName: string;
  suffix: string;
  parseStatus: 'PENDING' | 'PARSED' | 'TRUNCATED' | 'FAILED';
  parseError?: string | null;
  lineCount?: number | null;
  createTime?: string;
}

/** 登记并同步解析（上传两步的第二步：通用 OSS 上传拿 ossId 后调这里） */
export function registerAssistantAttachment(data: { ossId: number | string; threadId: string }) {
  return request<AssistantAttachmentItem>({ url: '/dcs/assistant/attachment/register', method: 'post', data });
}

/** 会话附件清单（旧→新；文件卡回放渲染） */
export function listAssistantAttachments(threadId: string) {
  return request<AssistantAttachmentItem[]>({ url: '/dcs/assistant/attachment/list', method: 'get', params: { threadId } });
}

/** 移除会话附件（AU-05A：腾挂载位；属主软删——超限提示「先移除旧文件」的兑现通道） */
export function removeAssistantAttachment(attachmentId: number | string) {
  // 2026-10-16 DELETE→POST（铁律#21：WAF 只放行 GET/POST）
  return request({ url: `/dcs/assistant/attachment/${attachmentId}`, method: 'post' });
}

/** C2 收藏跨设备：用户常用问句（新→旧，上限 6） */
export function listAssistantFavorites() {
  return request<string[]>({ url: '/dcs/assistant/favorite/list', method: 'get' });
}

/** 新增收藏（返回最新清单；上限/超长由服务层校验） */
export function addAssistantFavorite(content: string) {
  return request<string[]>({ url: '/dcs/assistant/favorite/add', method: 'post', data: { content } });
}

/** 删除收藏（返回最新清单） */
export function removeAssistantFavorite(content: string) {
  return request<string[]>({ url: '/dcs/assistant/favorite/remove', method: 'post', data: { content } });
}

/** G1 乙类#5：停止生成=服务端真取消（定向 interrupt 在跑 run；断连不打断不受影响） */
export function stopAssistantRun(threadId: string) {
  return request({ url: '/dcs/assistant/conversation/stop', method: 'post', params: { threadId } });
}

/** 二站-B 悬挂确认卡视图（换设备/刷新后重建 turn.hitl；空数组=无悬挂不重建） */
export interface PendingHitlItem {
  interruptId: string;
  toolName: string;
  argsJson: string;
  reason?: string;
}

/** 查询当前 thread 悬挂的 HITL 确认卡 */
export function getPendingHitl(threadId: string) {
  return request<PendingHitlItem[]>({ url: '/dcs/assistant/hitl/pending', method: 'get', params: { threadId } });
}

/** 批7 Q5/Q12-④：用户侧模型自选视图（dcs:assistant:use 普通用户权限）
 *  开关关时 options 空（防探测）；开时=本租户可用 CHAT 行（★排最前+单价，key 全清空不下发——
 *  批8-3 口径，管理端 /options 才有掩码） */
export interface AssistantModelChoice {
  allowUserSwitch: string;
  assistantDefaultConfigId: number | string | null;
  /** 批B step5 P1-1：默认模型 context_window（自选关时 options 空仍下发，环分母不悬空；
   *  后端 Map.of 不收 null，-1=未知哨兵，<=0 视作 null） */
  defaultContextWindow?: number;
  /** G2 问题②：默认模型名（自选关时 options 恒空仍下发——只读徽标展示用，空串=未配置） */
  defaultModelName?: string;
  /** 默认行真不可用标记（批B 回修语义：默认行被裁出自选白名单仍可调用，非告警态） */
  defaultSelectable?: boolean;
  options: import('@/api/dcs/ai/types').DcsAiConfigVO[];
}

/** 拉取用户侧模型自选视图（输入条模型选择器数据源，批7） */
export function getAssistantModelChoice() {
  return request<AssistantModelChoice>({ url: '/dcs/assistant/model-setting/model-choice', method: 'get' });
}

/** 管理页统计头（ZCode 对齐 Q4 批C）：全租户口径四计数 + 本月费用（perm=config） */
export interface AssistantAdminStats {
  conversations: number;
  messages: number;
  actions: number;
  feedbacks: number;
  /** 本月助手费用（元，钱包流水 OUT 合计） */
  monthCost: number | string;
}

export function getAssistantAdminStats() {
  return request<AssistantAdminStats>({ url: '/dcs/assistant/admin/stats', method: 'get' });
}

/** 能力全景（批A 端点，批C 管理页消费）：工具清单 + 系统提示词 + 租户配置只读 */
export interface AssistantPanoramaTool {
  name: string;
  displayName: string;
  category: string;
  /** 1=一期基础 / 2=二站 / 3=本期 */
  station: number;
  /** 需确认 / 自动执行 */
  behavior: string;
  minRoleLevel: number;
  gateDesc: string;
  /** 批1 Q1=B：租户覆盖档（空=未覆盖走默认） */
  toolGate?: string | null;
  /** 批1 Q1=B：生效档（-1=本租户已关闭，0=全员，1=发布人及以上，2=仅管理员） */
  effective?: number;
}

export interface AssistantPanorama {
  tools: AssistantPanoramaTool[];
  sysPrompt: string;
  /** 业务批7 Q9：技能库（➕ 面板技能目录，管理端只读展示） */
  skills?: AssistantPanoramaSkill[];
  tenantConfig: Record<string, unknown>;
}

/** 业务批7 Q9：技能库全景行（注入方式=会话选中才注入） */
export interface AssistantPanoramaSkill {
  id: string;
  name: string;
  description: string;
  minRoleLevel: number;
  injectNote: string;
  body: string;
}

export function getAssistantPanorama() {
  return request<AssistantPanorama>({ url: '/dcs/assistant/capability/panorama', method: 'get' });
}

/* ============ 批1 Q1=B：工具开关矩阵（租户级覆盖 off/member/admin，未配=默认内置档） ============ */

/** 管理端工具开关行（SPECS 内置档 + 租户覆盖 + 生效档合成；多工具同档共进退） */
export interface AssistantToolGateRow {
  toolNames: string[];
  displayName: string;
  category: string;
  gateDesc: string;
  builtinLevel: number;
  /** 租户覆盖：null/空=未覆盖走默认；off=关闭；member=全员；admin=仅管理员 */
  override: string | null;
  /** 生效档：-1=本租户已关闭，0=全员，1=发布人及以上，2=仅管理员 */
  effective: number;
  /** 代码强制项（内置 admin 档，UI 不可调） */
  locked: boolean;
  /** 执行前需用户确认（ASK 档） */
  askConfirm: boolean;
}

export function getAssistantToolGate() {
  return request<AssistantToolGateRow[]>({ url: '/dcs/assistant/toolgate', method: 'get' });
}

export function saveAssistantToolGate(gate: Record<string, string>) {
  return request({ url: '/dcs/assistant/toolgate', method: 'post', data: gate });
}

/* ============ 批2 Q2=A：MCP 外部工具试点（管理台「外部工具」页签） ============ */

/** 服务行（headers 只回键名——G4-#1 起值 AES 加密落库，键名是管理面唯一可见信息） */
export interface AssistantMcpServerRow {
  id: string;
  serverName: string;
  displayName: string | null;
  transportType: 'STDIO' | 'STREAMABLE_HTTP' | 'SSE';
  command: string | null;
  args: string | null;
  url: string | null;
  headerKeys: string;
  enabled: string;
  remark: string | null;
  updateTime: string | null;
}

/** 审计行（挂载/调用双态） */
export interface AssistantMcpCallLog {
  id: string;
  tenantId: string;
  userId: string;
  serverName: string;
  toolName: string | null;
  action: string;
  status: string;
  durationMs: number | null;
  errorMsg: string | null;
  createTime: string;
}

export function listAssistantMcpServers() {
  return request<AssistantMcpServerRow[]>({ url: '/dcs/assistant/mcp/list', method: 'get' });
}

export function saveAssistantMcpServer(data: Partial<AssistantMcpServerRow> & { headers?: string }) {
  return request({ url: '/dcs/assistant/mcp/save', method: 'post', data });
}

export function toggleAssistantMcpServer(id: string, enabled: string) {
  return request({ url: '/dcs/assistant/mcp/toggle', method: 'post', params: { id, enabled } });
}

export function deleteAssistantMcpServer(id: string) {
  return request({ url: '/dcs/assistant/mcp/delete', method: 'post', params: { id } });
}

export function pageAssistantMcpCallLog(params: { serverName?: string; pageNum: number; pageSize: number }) {
  return request<{ rows: AssistantMcpCallLog[]; total: number }>({ url: '/dcs/assistant/mcp/calllog', method: 'get', params });
}

/* ============ 批4 Q11：助手公告（管理台发布/撤回 + 用户侧横幅） ============ */

/** 用户侧：当前租户最新一条生效公告（无=null 不渲染横幅） */
export interface AssistantAnnouncement {
  id: string;
  title: string;
  content: string;
  /** NORMAL=普通 / MAINTENANCE=维护（警示色调） */
  style: 'NORMAL' | 'MAINTENANCE';
  publishTime: string;
}

/** 管理端公告行（含已撤回） */
export interface AssistantAnnouncementRow extends AssistantAnnouncement {
  status: string;
  publishBy?: string;
}

export function getAssistantAnnouncementActive() {
  return request<AssistantAnnouncement | null>({ url: '/dcs/assistant/announcement/active', method: 'get' });
}

export function listAssistantAnnouncements() {
  return request<{ rows: AssistantAnnouncementRow[]; total: number }>({
    url: '/dcs/assistant/announcement/admin/list',
    method: 'get'
  });
}

export function publishAssistantAnnouncement(data: { id?: string; title: string; content?: string; style?: string }) {
  return request({ url: '/dcs/assistant/announcement/admin/publish', method: 'post', data });
}

export function revokeAssistantAnnouncement(id: string) {
  return request({ url: '/dcs/assistant/announcement/admin/revoke', method: 'post', params: { id } });
}
