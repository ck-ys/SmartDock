/**
 * 助手回合状态机（批E Task E2，原型 §2.2 回合生命周期的 Vue 化）。
 *
 * <p>职责：AGUI SSE 事件 → 回合模型（thinking/acting/writing/done/stopped/error/waiting）；
 * 队列六条（v3.18）；停止幂等；打字机 S-23（buffer+60ms 合帧追赶）；HITL 等待态。
 * 视觉与 DOM 全部在组件层，本文件只做状态。</p>
 *
 * <p>纪律锚点：</p>
 * <ul>
 *   <li>AGUI 每轮重传全量 messages 历史（本地 turns 镜像维护）；runId 每轮 uuid；</li>
 *   <li>RUN_FINISHED outcome.type=interrupt = HITL 暂停（确认卡数据源，E3）；</li>
 *   <li>四处清理点：会话切换/重置前先清队列再停回合（防 drain 定时器把排队消息带进新会话）；</li>
 *   <li>onUnmounted 由组件层调用 dispose()——断流+清定时器（S-38）。</li>
 * </ul>
 */
import { reactive } from 'vue';
import { ElMessageBox } from 'element-plus';
import { readWebOn } from './webOnState';
import { readSkillId } from './skillState';
import { createAguiClient, type AguiClientOptions, type AguiTurnHandle } from '@/api/dcs/assistant/aguiClient';
import { getPendingHitl, getConversationTodo, getFollowups, type PendingHitlItem } from '@/api/dcs/assistant';
import { humanizeAiError } from '@/utils/aiErrorText';
import type { AguiHandlers, AguiMessage, AguiRunInput } from '@/api/dcs/assistant/types';
import { renderMd } from './md';

/** 批5 Q7-3：追问拉取递进间隔（侧车写库实测比 done 慢 2~8s）。
 *  业务反馈批1015b Q2：累计窗口 18s→30s——侧车生成允许跑到 45s，前端 18s 就放弃
 *  会永远等不到慢生成（刷新反而看得到）。
 *  稳流① Q8①（2026-10-20）：30s→90s——侧车 45s 超时 × 单线程池 8 深队列，忙时
 *  30s 窗口在队列里还没轮到；90s 兜住两轮生成周期，且配合聚焦/回页补拉（见
 *  scheduleFollowupPull），离开页面期间到手的建议回来立刻可见 */
const FOLLOWUP_PULL_DELAYS = [3500, 5500, 9000, 12000, 16000, 20000, 24000];

/** 工具名 → 人话动词（前端展示层映射；未知工具退化为去下划线）。
 *  二站① AU-52 起导出共用——管理端「运行记录」动作列与回合步骤行同词典（批I 账本列同源） */
export const ACT: Record<string, string> = {
  // T-1(1008 拍板B)：工具中文名统一「动词+宾语 3~5 字」——去掉"你的/情况"水分
  // （数据范围本就是用户自己的）；read_attachment 为补漏新增。新工具上线须同步登记此处，
  // 否则 fallback name.replace(/_/g,' ') 英文直出（AU 词典漏登记教训）
  query_my_todos: '查待办',
  query_my_missions: '查任务',
  query_mission_detail: '查任务详情',
  query_submission_status: '查提交进度',
  query_deadline_alerts: '查逾期',
  query_unsubmitted: '查未交名单',
  web_search: '联网搜索',
  act_remind: '发催办',
  read_attachment: '读附件',
  query_unsubmitted_by_dept: '查未交汇总',
  submit_plan: '出计划',
  save_memory: '存记忆',
  // ZCode 对齐 Q2 批A 引入（k3 P1-2 回修：漏登记致面板步骤行英文 todo write 直出）
  todo_write: '更新清单',
  // 批W1 写工具三件（2026-10-12）：同上漏登记=英文直出教训
  query_pending_reviews: '查待审批次',
  act_forward: '转发任务',
  act_audit: '审核提交',
  // 批F2 二站表单卡（2026-10-12）：建任务草稿
  act_create_mission: '创建任务',
  // 同族 TemplateCard 早就在用，但步骤行/账本一直英文直出（漏登记教训）
  act_create_template: '创建模板'
};

/** 账本状态→中文（批I 账本两列：真实落库值 DONE/EXECUTING/FAILED/REJECTED/PENDING——
 * 会话页旧按不存在的 SUCCESS 判色永不命中+英文直出；admin 页与 session 页共用此表） */
export const LOG_ST: Record<string, { label: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  DONE: { label: '已完成', type: 'success' },
  EXECUTING: { label: '执行中', type: 'warning' },
  FAILED: { label: '失败', type: 'danger' },
  REJECTED: { label: '已拒绝', type: 'info' },
  PENDING: { label: '待执行', type: 'info' }
};
export function logSt(status: string): { label: string; type: 'success' | 'warning' | 'danger' | 'info' } {
  return LOG_ST[status] ?? { label: status || '—', type: 'info' };
}

export interface AssistantStep {
  id: string;
  name: string;
  verb: string;
  argsText: string;
  brief: string;
  /** 全量工具结果（批C：业务卡/来源折叠条/图表卡渲染地基；20KB 封顶防长回合内存膨胀） */
  resultFull: string;
  status: 'run' | 'ok' | 'err';
  /** Q10 单步耗时：创建记 startedAt，终态结算 durMs；回放重建行恒 0（无真值不显示） */
  startedAt: number;
  durMs: number;
  /** R3(2026-10-10 拍板A) 全局事件序：与 ThinkSeg.seq 同一计数器，时间线交错渲染的排序键；
   *  回放重建行无值（undefined）= 排在 live 项之后按插入序兜底 */
  seq?: number;
}

/** R3(2026-10-10 拍板A) 思考分段：AGUI 的 REASONING_START/END 可与工具调用交错多次到达，
 *  旧单累加器把所有段拼成一坨且先后顺序丢失；每段独立计时/收合，seq 驱动与工具行交错。
 *  旧数据/回放无 thinkSegs → 渲染层用 thinkText 合成单段兜底（零迁移）。 */
export interface ThinkSeg {
  id: string;
  text: string;
  done: boolean;
  /** 段起点（REASONING_START 时刻）；回放合成段恒 0 */
  startedAt: number;
  /** 段耗时（REASONING_START→END）；0=未知（回放/无标记） */
  durMs: number;
  /** 全局事件序（模块级单调递增，跨 resume 轮不回卷） */
  seq: number;
}

/** HITL 确认卡数据（RUN_FINISHED interrupt outcome 提取；resume=interruptId+status 契约） */
export interface HitlAsk {
  /** resume 契约键=Interrupt.id（threadId:toolCallId 复合，服务端 keySet 逐字匹配；E2E 实证纯 toolCallId 会被 must-cover 拒） */
  interruptId: string;
  /** 本轮全部悬挂 id（三审 P1-2：一 run 可挂 2 个，服务端要求 resume 集合全等——只回一张卡的 id 会被拒） */
  pendingIds: string[];
  /** 框架 interrupt metadata 自带工具名（CF-1）：act_remind → 富卡形态（chips/附言/证据行） */
  toolName?: string;
  /** 模型原始工具入参（CF-1）：metadata.toolInput（Map 或 JSON 字符串两形态，提取时归一） */
  toolInput?: Record<string, unknown>;
  reason?: string;
  message?: string;
  /** 确认卡状态：pending 待确认 → run 已确认执行中（本轮 resume 的 turn 完成后收 done） */
  cardState: 'pending' | 'run' | 'done';
  /** 取消路径标记（卡态 done 时区分「已执行/已取消」） */
  cancelled?: boolean;
  /** 079：确认后点停止标记——工具是否真执行未知，卡面不得断言「已执行完成」 */
  stopped?: boolean;
}

/** CF-1 确认卡编辑回执：经框架 editedArgs 全量替换回传工具入参（javap 实证 AguiMessageConverter） */
export interface HitlEdits {
  /** 附言（催办留言）最终文本 */
  message?: string;
  /** 删人后的保留名单（subjectUserId 集合）；undefined=不改动（全量） */
  targetUserIds?: Array<number | string>;
  /** 批W2：转发备注 / 审核备注·退回原因（act_forward / act_audit 卡） */
  remark?: string;
  /** 批F2 表单卡（act_create_mission）：五字段即工具完整参数面，直接构造 editedArgs
   *  整体替换（不依赖 toolInput 镜像做底——glm53 批F 审查 P2-2 回修）；键名蛇形对齐
   *  @ToolParam；空串=该字段留空 */
  formCreate?: {
    missionName: string;
    missionType: number;
    beginTime: string;
    endTime: string;
    description: string;
  };
  /** 五需求批 R4a 表单卡（act_create_template）：四键即工具完整参数面，直接构造
   *  editedArgs 整体替换（formCreate 同模式）；fieldsJson 由 TemplateCard 序列化，
   *  键名蛇形对齐 @ToolParam */
  formTemplate?: {
    templateName: string;
    fieldsJson: string;
    description: string;
    visibility: 'PRIVATE' | 'PUBLIC';
  };
}

export interface AssistantTurn {
  id: string;
  question: string;
  phase: 'thinking' | 'acting' | 'writing' | 'waiting' | 'done' | 'stopped' | 'error';
  /** 思考行（档位只改请求参数，展示数据驱动：模型真返回 reasoning 才有内容） */
  thinkText: string;
  thinkDone: boolean;
  /** 思考链路批(1008 用户报障)：REASONING_START 已到——闸关/模型无思考内容时
   *  也凭结构标记显示「正在思考…」占位，不再要求 thinkText 非空才渲染 */
  thinkActive: boolean;
  /** 批8-3 回修（k3 P1-5）：纯思考段耗时（REASONING_START→END），0=未知（回放/无思考）——
   *  「已思考 · N 秒」曾用整轮 durMs（含工具+撰写）系统性夸大；
   *  R3(2026-10-10) 多段化后=各段 durMs 之和 */
  thinkDurMs: number;
  /** R3(2026-10-10 拍板A) 思考分段：与工具步按事件序交错渲染；thinkText 仍同步维护全量拼接
   *  （tpeek 折叠偷看等旧消费方零改动） */
  thinkSegs: ThinkSeg[];
  steps: AssistantStep[];
  /** 打字机：bufText=网络增量累积；shownLen=已显示长度（60ms 合帧追赶） */
  bufText: string;
  shownLen: number;
  streamDone: boolean;
  answerHtml: string;
  errorMsg: string;
  /** AU-35(1008)：AGUI RUN_ERROR 的结构化 code（错误卡按 code+文案双保险判定挂起中断类） */
  errorCode: string;
  /** 业务批(1015) Q9：人话化错误背后的原始报错（超时/内部类映射后原文收进折叠详情，排错可查现场） */
  errorDetail?: string;
  costYuan: number | null;
  tokens: number | null;
  stamp: string;
  startedAt: number;
  durMs: number;
  toolCalls: number;
  hitl: HitlAsk | null;
  /** 计划卡（P1-3）：CUSTOM plan 事件驱动；后端无触发源时恒 null 不渲染 */
  plan: { title?: string; steps: Array<{ label: string; state: 'pending' | 'run' | 'done' | 'edit' }> } | null;
  /** G3-A1：MCP elicitation 反问卡（CUSTOM 'elicitation' 帧，run 内挂起随本回合走）；
   *  作答走独立端点不走 resolveHitl；过期前端本地收卡（后端超时 CANCEL 兜底闭环） */
  elicitations: import('@/api/dcs/assistant').AssistantElicitationCard[];
  /** 批5 Q7-3：LLM 生成的贴题追问（侧车异步回写消息行）；空=前端回退角色静态池 */
  followups?: string[];
  /** 自查回修：追问侧车三次拉取全部落空才置 true——「本轮未生成贴题追问」灰字只在这之后显示，
   *  否则每次回答后 3.5s+ 窗口内都会误报"未生成"（dspro 实测侧车普遍慢于 done+3.5s） */
  followupsSettled?: boolean;
  /** 业务批(1015) Q6：联网搜索来源卡（live 回合从 web_search 结果结构化提取；
   *  历史回放不落 resultFull 故不重建——与 DataCard 同限） */
  citations?: Array<{ title: string; url: string; siteName?: string }>;
  /** 批6-#2：回答被中断标记——历史回放消费 end_reason（partial=仍在生成/刚断连，
   *  truncated=终局中断），气泡尾部亮「回答被中断」徽标；正常轮恒 false */
  interrupted?: boolean;
  /** 批17 Q2：本回合之前发生过上下文压缩（role=system 行驱动）——回合上方渲染
   *  「已压缩」分隔条；beforeTokens/afterTokens 供"省下约 X%"人话 */
  compactedBefore?: { beforeTokens?: number | null; afterTokens?: number | null } | null;
}

export interface AssistantState {
  turns: AssistantTurn[];
  sending: boolean;
  /** 队列（v3.18）：排队消息同时上屏灰气泡（Q2A），pending=尚未转正 */
  queue: { text: string; pendingId: number }[];
  queuePaused: boolean;
  /** R1（2026-10-13）：disabled=管理员已停用该用户的助手（批③ 用户额度禁用闸）——
   *  后端 /status 早已返回 quotaDisabled，此前前端零消费（被禁用户打开助手零提示） */
  banner: '' | 'offline' | 'auth' | 'quota' | 'degraded' | 'disabled';
  gateDone: boolean;
  gatePendQ: string;
  newbieUse: number;
  threadId: string;
  /** 当前会话是否归档（AU-36）：三宿主共享的发送闸——宿主打开会话时写入，resetForNewSession 复位 */
  curConvArchived: boolean;
  /** 同类免确认积累（档1「同类只问一次」）：存确认过的工具名（如 act_remind）；切档/新会话即清 */
  autoApproved: string[];
  /** 三档自主权（批I-2 原型 autoPop）：always=每次都问 / once=同类只问一次 / auto=全自动+事后通知 */
  autoLevel: 'always' | 'once' | 'auto';
  /** T-2(1008 拍板B)：思考过程总显示开关——false=每回合思考块默认收起（点哪条开哪条），
   *  true=全部默认展开；按用户持久化，由 Composer 🧠 弹层「过程显示」行驱动 */
  showThink: boolean;
  /** 会话级 TODO 清单（ZCode 对齐 Q2，批B）：todo CUSTOM 帧实时更新 / 打开会话
   *  GET /conversation/todo 恢复；新会话清空。不随回合清——它跨回合存活直到整表替换 */
  todoItems: import('@/api/dcs/assistant').AssistantTodoItem[];
  /** 批17 Q2：尾部悬挂的压缩标记（system 行是最后一条消息、其后还没有新回合时，
   *  MsgFlow 在消息流末尾渲染分隔条；一旦有新回合即转为该回合的 compactedBefore） */
  lastCompaction?: { beforeTokens?: number | null; afterTokens?: number | null } | null;
}

export interface UseAssistantOptions extends AguiClientOptions {
  /** 会话持久化键位（传 userId —— E5 刷新/重进按人恢复 threadId） */
  persistKey?: string;
  /** 组件层注入：输入框草稿（队列六条①「草稿自动排队」用） */
  getDraft?: () => string;
  clearDraft?: () => void;
  onToast?: (text: string, kind?: 'ok' | 'err' | 'info') => void;
  /** 打字机帧时钟（默认 rAF 不可用时 setInterval；测试注入 immediate） */
  ticker?: (fn: () => void, ms: number) => () => void;
  /** 会话重载注入：历史消息重建（E5） */
  history?: AguiMessage[];
}

/**
 * 组件层可变绑定（单例化后组件挂卸时可重设；工厂模式下=options 的可变子集）。
 * 全屏覆盖层/会话页/抽屉三宿主共用同一 store 时，草稿与 toast 归「当前可见宿主」。
 */
export type AssistantBindings = Pick<UseAssistantOptions, 'getDraft' | 'clearDraft' | 'onToast' | 'onAuthFailure'>;

function uuid(): string {
  return typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'r-' + Date.now() + '-' + Math.random().toString(36).slice(2);
}

/** 工具结果失败判别（B0 契约：TIMEOUT 也出 JSON 不抛异常——按 error 字段判） */
function resultFailed(content: string): boolean {
  // AU-47(1008)：web_search 族错误信封=顶层 "status" 非 OK（DISABLED/BAD_REQUEST/UPSTREAM_ERROR/ERROR），
  // 与 MissionTools 的 error/errorType 键家族不同——不能对整串正则 "status"：
  // 任务行级字段也叫 status（IN_PROGRESS 等任务态），会误伤正常步骤。只解析顶层信封判定
  try {
    const env = JSON.parse(content) as { status?: unknown };
    if (typeof env.status === 'string' && env.status !== 'OK') {
      return true;
    }
  } catch {
    /* 非合法 JSON（纯文本/截断）走下方旧启发式 */
  }
  return /"(error|code)"\s*:\s*("(?!(0|OK|ok)[^0-9a-zA-Z])|TIMEOUT|5\d\d)/.test(content) && /error|超时|失败/.test(content);
}

/** 批J-4+Q5-A（2026-10-10 拍板）：toolSteps 持久化 JSON → 步骤+思考段。
 *  旧纯数组=仅步骤（零迁移）；新对象 {"steps":[...],"thinkSegs":[...]} 双取；
 *  seq 透传（后端同 thread 单调计数器）——时间线按真实时序交错恢复；
 *  verb 走 ACT 词典同款映射；resultFull 留空（持久化侧不落）。 */
function parsePersistedTurnSteps(raw: unknown): { steps: AssistantStep[]; thinks: ThinkSeg[] } {
  if (typeof raw !== 'string' || !raw) {
    return { steps: [], thinks: [] };
  }
  try {
    const parsed = JSON.parse(raw) as unknown;
    const root = parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null;
    const stepsArr = (Array.isArray(parsed) ? parsed : root?.steps) as Array<Record<string, unknown>> | undefined;
    const steps: AssistantStep[] = Array.isArray(stepsArr)
      ? stepsArr
          .filter((s) => s && typeof s.name === 'string' && s.name)
          .map((s) => ({
            id: typeof s.id === 'string' && s.id ? s.id : 'h-' + Math.random().toString(36).slice(2),
            name: String(s.name),
            verb: ACT[String(s.name)] ?? String(s.name).replace(/_/g, ' '),
            argsText: typeof s.argsText === 'string' ? s.argsText : '',
            brief: typeof s.brief === 'string' ? s.brief : '',
            resultFull: '',
            status: (s.status === 'ok' || s.status === 'err' ? s.status : 'run') as AssistantStep['status'],
            startedAt: 0,
            durMs: 0,
            seq: typeof s.seq === 'number' ? s.seq : undefined
          }))
      : [];
    const thinksArr = root?.thinkSegs as Array<Record<string, unknown>> | undefined;
    const thinks: ThinkSeg[] = Array.isArray(thinksArr)
      ? thinksArr
          .filter((s) => s && (typeof s.id === 'string' || typeof s.text === 'string'))
          .map((s, i) => ({
            id: typeof s.id === 'string' && s.id ? s.id : 'ht' + i,
            text: typeof s.text === 'string' ? s.text : '',
            done: true,
            // durMs 无真值（未闭合段）时不用 epoch startMs——否则 segLabel 的
            // Date.now()-startedAt 兜底会显示天文秒数（三席审查回修；后端已同步补实耗）
            startedAt: typeof s.startMs === 'number' && typeof s.durMs === 'number' && s.durMs > 0 ? s.startMs : 0,
            durMs: typeof s.durMs === 'number' ? s.durMs : 0,
            seq: typeof s.seq === 'number' ? s.seq : i
          }))
      : [];
    return { steps, thinks };
  } catch {
    return { steps: [], thinks: [] };
  }
}

let seq = 0;

/** R3(2026-10-10) 全局事件序：thinkSegs 与 steps 共用的单调计数器——模块级而非回合级，
 *  防 HITL resume 轮新建 handlers 闭包后 seq 回卷与打断前的段/步碰撞（时间线乱序） */
let evSeq = 0;

/** 新回合骨架（runTurn / resolveHitl 共用） */
function emptyTurn(question: string): AssistantTurn {
  return {
    id: 't' + ++seq,
    question,
    phase: 'thinking',
    thinkText: '',
    thinkDone: false,
    thinkActive: false,
    thinkDurMs: 0,
    thinkSegs: [],
    steps: [],
    bufText: '',
    shownLen: 0,
    streamDone: false,
    answerHtml: '',
    errorMsg: '',
    errorCode: '',
    errorDetail: '',
    costYuan: null,
    tokens: null,
    stamp: '',
    startedAt: Date.now(),
    durMs: 0,
    toolCalls: 0,
    hitl: null,
    plan: null,
    elicitations: []
  };
}

/**
 * 批6-#2 回修（m3 P0-2）：远端轮询跨组件去重——抽屉（layout 常驻）与会话页
 * 可能先后对同一 threadId 挂 3s 轮询（双发 listConversations + 双份重建互相顶替）。
 * 模块级占用表：先到者盯，后到者跳过挂载（对方收敛时重建的是共享 turns，
 * 本组件响应式自动跟随，功能无损）。
 */
const remoteWatchThreads = new Set<string>();
export function tryAcquireRemoteWatch(threadId: string): boolean {
  if (remoteWatchThreads.has(threadId)) {
    return false;
  }
  remoteWatchThreads.add(threadId);
  return true;
}
export function releaseRemoteWatch(threadId: string): void {
  remoteWatchThreads.delete(threadId);
}

export function useAssistant(options: UseAssistantOptions = {}) {
  const history: AguiMessage[] = options.history ?? [];
  // E5 会话重建：threadId 按 persistKey 持久化（刷新/重进续同一会话——后端按 threadId 记忆）
  const persistKey = options.persistKey;
  const savedThread = persistKey ? localStorage.getItem(persistKey) : null;
  // 组件层可变绑定（工厂模式=直接取 options；单例模式=bind 栈顶）：
  // 批H P1-5 栈化——会话页 bind 抢占后路由离开，抽屉（layout 常驻不重挂）拿不回
  // getDraft。改为 push/pop：卸载方 unbind() 弹栈自动归还上一任；空栈=无宿主接管
  const bindings: AssistantBindings = { ...options };
  const bindStack: AssistantBindings[] = [];
  function applyBindTop() {
    const top = bindStack[bindStack.length - 1];
    bindings.getDraft = top?.getDraft;
    bindings.clearDraft = top?.clearDraft;
    bindings.onToast = top?.onToast;
    bindings.onAuthFailure = top?.onAuthFailure;
  }
  const state = reactive<AssistantState>({
    turns: [],
    sending: false,
    queue: [],
    queuePaused: false,
    banner: '',
    gateDone: (() => (persistKey ? localStorage.getItem(persistKey.replace(':thread:', ':gateOk:')) === '1' : false))(),
    gatePendQ: '',
    newbieUse: 0,
    threadId: savedThread && savedThread.length > 8 ? savedThread : uuid(),
    autoApproved: [],
    // 自主权档位持久化（Q9 拍板）：按用户分键（与 persistKey 同源派生），刷新不回 always
    autoLevel: (() => {
      const saved = persistKey ? localStorage.getItem(persistKey.replace(':thread:', ':autoLevel:')) : null;
      return saved === 'once' || saved === 'auto' ? saved : 'always';
    })(),
    // T-2(1008)：思考过程总显示——同源派生键持久化，默认收起（思考是配菜答案是主菜）
    showThink: (() => (persistKey ? localStorage.getItem(persistKey.replace(':thread:', ':thinkShow:')) === '1' : false))(),
    // ZCode 对齐 Q2（批B）：会话 TODO 清单（todo CUSTOM 帧/打开会话恢复写）
    todoItems: [],
    curConvArchived: false
  });
  if (persistKey) {
    try {
      localStorage.setItem(persistKey, state.threadId);
    } catch {
      /* 存储不可用忽略 */
    }
  }
  let handle: AguiTurnHandle | null = null;
  let wasStopped = false;
  let qDrained = 0;
  let pendingIdSeq = 1;
  let stopTicker: (() => void) | null = null;
  // 会话代数（批H P1-3）：newSession/rebuildFromHistory 时递增。在途回合收尾发现代数
  // 已变=会话已被切换/重开，旧回合的 history 落账/队列补发/sending 复位整体作废——
  // 否则退避重试数秒后才 settle 的旧收尾会把旧会话内容写进新 thread（消息串会话）
  let convGen = 0;

  const toast = (t: string, kind?: 'ok' | 'err' | 'info') => bindings.onToast?.(t, kind);
  const ticker =
    options.ticker ??
    ((fn: () => void, ms: number) => {
      if (typeof requestAnimationFrame === 'function') {
        let alive = true;
        const loop = () => {
          if (!alive) {
            return;
          }
          fn();
          setTimeout(loop, ms);
        };
        setTimeout(loop, ms);
        return () => {
          alive = false;
        };
      }
      const id = setInterval(fn, ms);
      return () => clearInterval(id);
    });

  const client = createAguiClient({
    ...options,
    onRetry: (attempt: number, delayMs: number, reason?: 'network' | 'stall') => {
      // AU-10: 断流重试=同一轮全量重播，attempt1 已 append 的 delta 会整段再来一遍——
      // 重置本轮累积（文本/思考分段/工具步骤/打字机游标），否则回答双拼
      const t = cur();
      if (t) {
        t.bufText = '';
        t.shownLen = 0;
        t.answerHtml = '';
        t.thinkText = '';
        t.thinkDone = false;
        t.thinkActive = false;
        t.thinkDurMs = 0;
        t.thinkSegs = [];
        t.steps = [];
        t.toolCalls = 0;
      }
      // goal 加固 G1：停滞（连接活着但无帧）与断网分开话术——停滞常意味着上游模型挂起
      toast(`${reason === 'stall' ? '连接停滞' : '网络断了'}，正在重连（第 ${attempt} 次）…`, 'info');
      options.onRetry?.(attempt, delayMs, reason);
    },
    onAuthFailure: (d) => {
      // R1（回修 dspro P2-1）：禁用态是管理员意志，网络/鉴权事件不得静默解除只读
      if (state.banner !== 'disabled') {
        state.banner = 'auth';
      }
      bindings.onAuthFailure?.(d);
    },
    onDisconnected: () => {
      if (state.banner !== 'disabled') {
        state.banner = 'offline';
      }
      options.onDisconnected?.(0);
    }
  });

  /** R1（回修 dspro P1-3）：禁用统一闸——队列续发/立即发送/继续说完/须知门/确认卡等
   *  路径不走组件层 onSend，在此收口（runTurn 入口 + resolveHitl 批准前置），
   *  后端 assertEnabled 429 兜底不变；toast 与红横幅同文案 */
  function assertUsable(): boolean {
    if (state.banner === 'disabled') {
      toast('管理员已暂停你的 AI 助手使用，如有疑问请联系管理员', 'err');
      return false;
    }
    return true;
  }

  /** 当前回合（打字机/统计面板消费） */
  const cur = (): AssistantTurn | null => (state.turns.length > 0 ? state.turns[state.turns.length - 1] : null);

  /** 打字机推进：60ms 合帧追赶（每帧 ≥2 字、按剩余 35% 追赶，3000 帧不积压）；流收尾后全量 md 渲染 */
  const tick = () => {
    const t = cur();
    if (!t) {
      return;
    }
    if (t.shownLen < t.bufText.length) {
      const remain = t.bufText.length - t.shownLen;
      t.shownLen = Math.min(t.bufText.length, t.shownLen + Math.max(2, Math.ceil(remain * 0.35)));
    }
    if (t.streamDone && t.shownLen >= t.bufText.length && !t.answerHtml) {
      t.answerHtml = renderMd(t.bufText);
      stopTypingLoop();
    }
  };
  let typingRunning = false;
  const ensureTypingLoop = () => {
    if (!typingRunning) {
      typingRunning = true;
      stopTicker = ticker(tick, 60);
    }
  };
  const stopTypingLoop = () => {
    if (typingRunning) {
      typingRunning = false;
      stopTicker?.();
      stopTicker = null;
    }
  };

  /** AGUI 事件 → 回合模型 */
  /** H3：旧 pending 确认卡清扫——新一轮正常收尾（或契约失效报错）时，此前回合残留的
   * pending 卡已不可能再被 resume（服务端只认最新轮 must-cover 全等），置 done+cancelled
   * 防「待确认」僵尸卡永久挂着（刷新回放还会被 restorePendingHitl 误当悬挂轮复活）。 */
  function sweepStaleHitlCards(exceptTurn?: AssistantTurn) {
    for (const t of state.turns) {
      if (t !== exceptTurn && t.hitl && t.hitl.cardState === 'pending') {
        t.hitl.cardState = 'done';
        t.hitl.cancelled = true;
      }
    }
  }

  function handlers(turn: AssistantTurn): AguiHandlers {
    return {
      // R3(2026-10-10 拍板A)：思考分段——START 到达即开新段（上一段已 done 才开，防重复 START 双段）；
      // 段的 seq 取模块级 evSeq（与 onToolStart 同一计数器，跨 resume 轮单调不回卷），
      // 渲染层据此把每段思考插在它发生的时刻（think①→工具①→think②），不再拼成一坨置顶
      onReasoningStart: () => {
        const last = turn.thinkSegs[turn.thinkSegs.length - 1];
        if (!last || last.done) {
          turn.thinkSegs.push({ id: 'th' + ++seq, text: '', done: false, startedAt: Date.now(), durMs: 0, seq: evSeq++ });
        }
        turn.thinkActive = true;
      },
      onReasoningDelta: (_id, delta) => {
        let last = turn.thinkSegs[turn.thinkSegs.length - 1];
        if (!last) {
          // 协议容错：delta 先于 START 到达（或重试重置后）——补建段，不丢内容
          last = { id: 'th' + ++seq, text: '', done: false, startedAt: Date.now(), durMs: 0, seq: evSeq++ };
          turn.thinkSegs.push(last);
        }
        last.text += delta;
        turn.thinkText += delta; // 旧拼接口径保留（tpeek 折叠偷看等消费方零改动）
        turn.thinkActive = true;
      },
      onReasoningEnd: () => {
        const last = turn.thinkSegs[turn.thinkSegs.length - 1];
        if (last && !last.done) {
          last.done = true;
          last.durMs = last.startedAt > 0 ? Date.now() - last.startedAt : 0;
        }
        turn.thinkDone = true;
        turn.thinkActive = false;
        // R3：总耗时=各段之和（旧实现只记最后一段）
        turn.thinkDurMs = turn.thinkSegs.reduce((sum, s) => sum + (s.durMs || 0), 0) || turn.thinkDurMs;
      },
      onToolStart: (id, name) => {
        turn.phase = turn.phase === 'writing' ? 'writing' : 'acting';
        turn.steps.push({
          id,
          name,
          verb: ACT[name] ?? name.replace(/_/g, ' '),
          argsText: '',
          brief: '',
          resultFull: '',
          status: 'run',
          startedAt: Date.now(),
          durMs: 0,
          seq: evSeq++
        });
        turn.toolCalls += 1;
      },
      onToolArgs: (id, delta) => {
        const s = turn.steps.find((x) => x.id === id);
        if (s && s.argsText.length < 400) {
          s.argsText += delta;
        }
      },
      onToolResult: (id, content) => {
        let s = turn.steps.find((x) => x.id === id);
        if (!s) {
          // HITL resume 轮：结果事件的 toolCallId 属于被打断的原回合步骤——回填原步骤，
          // 否则确认型 rows 工具（query_unsubmitted）的 resultFull 永远落空，DataCard 不渲染
          for (let i = state.turns.length - 1; i >= 0 && !s; i--) {
            s = state.turns[i].steps.find((x) => x.id === id);
          }
        }
        if (s) {
          s.status = resultFailed(content) ? 'err' : 'ok';
          s.brief = content.slice(0, 120);
          s.resultFull = content.length <= 20000 ? content : content.slice(0, 20000) + '…';
          // Q10 单步耗时：终态结算（startedAt 0=回放/跨轮回填行无真值，保持 0 不显示）
          s.durMs = s.startedAt > 0 ? Date.now() - s.startedAt : 0;
          // 业务批(1015) Q6：web_search 结果提取来源卡数据（title/url/siteName）——协议白名单
          // （http/https）+8 条上限+解析失败静默；正文外链维持纯文本，受控来源卡是唯一外链出口
          if (s.name === 'web_search') {
            try {
              const j = JSON.parse(content) as { rows?: Array<{ name?: string; url?: string; siteName?: string }> };
              const picked = (j.rows ?? [])
                .filter((r) => typeof r?.url === 'string' && /^https?:\/\//i.test(r.url))
                .map((r) => ({
                  title: String(r.name ?? r.url ?? ''),
                  url: r.url as string,
                  siteName: typeof r.siteName === 'string' ? r.siteName : undefined
                }));
              // 自查回修(dspro P2)：一轮多次 web_search（先宽搜再补搜是常见行为）按 URL 合并去重，
              // 覆盖式赋值会把前一组来源整丢；解析失败保持已有来源不清空
              const merged = [...(turn.citations ?? [])];
              for (const c of picked) {
                if (!merged.some((o) => o.url === c.url)) {
                  merged.push(c);
                }
              }
              turn.citations = merged.slice(0, 8);
            } catch {
              /* 非法 JSON：保持已有来源 */
            }
          }
        }
      },
      onTextStart: () => {
        if (turn.phase !== 'writing') {
          turn.phase = 'writing';
        }
        ensureTypingLoop();
      },
      onTextDelta: (_id, delta) => {
        turn.bufText += delta;
      },
      onTextEnd: () => {
        // PD-1(2026-10-07 试点走查): 不在此置 streamDone——多段消息(卡前句+卡后总结)每段
        // 都触发 TEXT_MESSAGE_END, 第一段就定稿(answerHtml 一旦生成不再刷新)会把卡后段
        // 锁在 bufText 里永不渲染; 定稿唯一驱动=onFinished(interrupt/normal 两分支都已置位)
      },
      onCustom: (name, value) => {
        if (name === 'cost_meta' && value && typeof value === 'object') {
          const v = value as { yuan?: number; tokens?: number };
          turn.costYuan = v.yuan ?? null;
          turn.tokens = v.tokens ?? null;
        } else if (name === 'plan' && value && typeof value === 'object') {
          // 计划卡（P1-3 拍板补做）：CUSTOM plan 事件带步骤数组；后端无触发源期间组件就绪不出现
          const v = value as { title?: string; steps?: Array<{ label?: unknown; state?: unknown }> };
          const steps = (Array.isArray(v.steps) ? v.steps : [])
            .filter((s) => s && typeof s.label === 'string' && s.label)
            .slice(0, 8)
            .map((s) => ({
              label: String(s.label),
              state: (['pending', 'run', 'done', 'edit'].includes(String(s.state)) ? String(s.state) : 'pending') as
                | 'pending'
                | 'run'
                | 'done'
                | 'edit'
            }));
          if (steps.length > 0) {
            turn.plan = { title: typeof v.title === 'string' ? v.title : undefined, steps };
          }
        } else if (name === 'elicitation' && value && typeof value === 'object') {
          // G3-A1：MCP 外部工具执行中反问（server 侧在飞挂起，run 保持 RUNNING）——
          // 卡随本回合渲染，作答走独立端点（不走 resolveHitl/resume）；多 server 可同时反问
          const v = value as { id?: unknown; serverName?: unknown; message?: unknown; requestedSchema?: unknown; expiresAt?: unknown };
          if (typeof v.id === 'string' && v.id) {
            turn.elicitations.push({
              id: v.id,
              serverName: typeof v.serverName === 'string' ? v.serverName : '',
              message: typeof v.message === 'string' ? v.message : '',
              requestedSchema: (v.requestedSchema && typeof v.requestedSchema === 'object' ? v.requestedSchema : {}) as Record<string, unknown>,
              expiresAt: typeof v.expiresAt === 'number' ? v.expiresAt : Date.now() + 300_000
            });
          }
        } else if (name === 'todo' && value && typeof value === 'object') {
          // ZCode 对齐 Q2（批B）：todo_write 整表替换清单——enricher 转发帧，
          // 会话级状态（跨回合存活，新清单整体替换）
          const v = value as { items?: Array<{ content?: unknown; status?: unknown; priority?: unknown }> };
          const items = (Array.isArray(v.items) ? v.items : [])
            .filter((it) => it && typeof it.content === 'string' && it.content)
            .slice(0, 20)
            .map((it) => ({
              content: String(it.content),
              status: (['pending', 'in_progress', 'completed'].includes(String(it.status)) ? String(it.status) : 'pending') as
                | 'pending'
                | 'in_progress'
                | 'completed',
              priority: (['high', 'medium', 'low'].includes(String(it.priority)) ? String(it.priority) : 'medium') as 'high' | 'medium' | 'low'
            }));
          // k3 P1-1 回修：todo 帧写的是会话级全局槽（非 turn 对象级）——切换会话的
          // 同批残余帧不得写入（resetForNewSession 后 sending 已复位）
          if (items.length > 0 && state.sending) {
            state.todoItems = items;
          }
        }
      },
      onStall: async () => {
        // 稳流① Q1（拍板 A②）：600s 无帧（含 20s 心跳都没到=链路真断）不再自动重发
        // ——重发撞 409 防双计费；裁决交用户。继续等=再挂一个看门狗窗；停止=按手动停止收口
        try {
          await ElMessageBox.confirm(
            '等了很久还没有新内容，连接可能出了问题。继续等待，还是停止这一轮？',
            '还在等待回复',
            { confirmButtonText: '继续等待', cancelButtonText: '停止', type: 'warning' }
          );
          toast('继续等待中——若仍无进展可随时点「停止」结束本轮', 'info');
          return 'keep-waiting';
        } catch {
          // 三审回修（dsflash P2-5）：看门狗停止与手动停止同口径——走 stop() 才会发服务端
          // 真取消（只 return 'stop' 是本地断流，后端还要吃满 60s 收割窗）
          stop();
          return 'stop';
        }
      },
      onFinished: (outcome) => {
        // PD-5(2026-10-07 试点走查): SDK 错误=RAW 帧转 onRunError 后仍会紧跟 RUN_FINISHED
        // 收尾(如准入闸限流 429), error 态在此直接收尾——不得被 FINISHED 盖回 done「已回答」
        if (turn.phase === 'error') {
          ensureTypingLoop();
          return;
        }
        turn.streamDone = true;
        if (outcome && (outcome as { type?: string }).type === 'interrupt') {
          // HITL 暂停：确认卡数据提取。真实形态（agentscope 2.0.3 Jackson）=
          // {type:'interrupt', interrupts:[{id:'threadId:toolCallId', reason, message, toolCallId…}]}
          // resume 契约键=id（E2E 实证：取顶层 toolCallId 得空串 → must-cover 拒）；顶层形态仅旧 mock 兼容
          turn.phase = 'waiting';
          const o = outcome as Record<string, unknown>;
          const interrupts = Array.isArray(o.interrupts) ? (o.interrupts as Array<Record<string, unknown>>) : [];
          const first = interrupts.length > 0 ? interrupts[0] : o;
          // 三审 P1-2：全量收集本轮悬挂 id（服务端 resume 集合全等；卡只展示第一张，
          // 未展示的确认在 resume 轮被拒后模型会重新询问出新卡）
          const allIds = interrupts.map((it) => String(it?.id ?? '')).filter((id) => id !== '');
          // CF-1：框架 PermissionConfirmEventConverter 把 toolName/toolInput 放进 interrupt
          // metadata（javap 实证）。入参取值优先 dcsToolInput（后端 enricher 注入的全字符串
          // 镜像——19 位雪花 ID 以 JSON 数字形态到达时前端必被取整，E2E 实证 …326→…300）；
          // 无镜像且 toolInput 含不安全数字时富卡降级（editedArgs 回声必须逐字精确）
          const meta = (first.metadata ?? {}) as Record<string, unknown>;
          const normalizeInput = (raw: unknown): Record<string, unknown> | undefined => {
            if (typeof raw === 'string') {
              try {
                const parsed = JSON.parse(raw);
                if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                  return parsed as Record<string, unknown>;
                }
              } catch {
                /* 非法 JSON 视为无入参 */
              }
              return undefined;
            }
            return raw && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : undefined;
          };
          let toolInput = normalizeInput(meta.dcsToolInput) ?? normalizeInput(meta.toolInput);
          if (toolInput && !meta.dcsToolInput) {
            const unsafeId = ['mission_id', 'topic_id'].some((k) => typeof toolInput?.[k] === 'number' && !Number.isSafeInteger(toolInput[k]));
            if (unsafeId) {
              toolInput = undefined;
            }
          }
          turn.hitl = {
            interruptId: String(first.id ?? o.toolCallId ?? ''),
            pendingIds: allIds.length > 0 ? allIds : [String(first.id ?? o.toolCallId ?? '')],
            toolName: typeof meta.toolName === 'string' ? meta.toolName : undefined,
            toolInput,
            reason: (first.reason ?? o.toolCallName) as string | undefined,
            message: (first.message ?? o.message) as string | undefined,
            cardState: 'pending'
          };
        } else {
          turn.phase = 'done';
          sweepStaleHitlCards(turn);
        }
        ensureTypingLoop();
      },
      onRunError: (msg, code) => {
        // 二站 T4：契约错误=thread 悬挂 interrupt 未被 resume 覆盖（后端入口自动解锁的
        // 残余窗口，预期极少）——按结构化 code 精确识别（T0 实测帧字段），禁止文本匹配；
        // 人话指路「再发一遍」（拍板 Q3=A），重发时后端自动解锁接管
        if (code === 'AGUI_INTERRUPT_CONTRACT_ERROR') {
          toast('上一次的确认已失效，再发一遍就好', 'err');
          turn.errorMsg = '上一次的确认已失效，再发一遍就好';
          // H3：契约失效=服务端已不认旧卡，本轮之外挂着的 pending 卡一并收掉（含 resume 轮
          // 失败时原回合的卡——否则卡回 pending 再点还是 409 死循环）
          sweepStaleHitlCards(turn);
        } else if (msg && msg.startsWith('Agent is paused for human-in-the-loop')) {
          // 业务批7 Q16-③：重启后旧确认挂起毒化 thread（CallExecution 深处抛出、无结构化
          // code 可依）——T4 的 code 精确匹配够不到这里，窄前缀兜底不误伤其它报错。
          // 后端 Q16-① 启动自愈上线后此帧属残余极态；文案指路「再发一遍」与 T4 同口径
          toast('上一步的确认卡住了，再发一遍就好', 'err');
          turn.errorMsg = '上一步的确认卡住了，再发一遍就好';
          sweepStaleHitlCards(turn);
        } else {
          // 业务批(1015) Q9：AgentScope/Reactor 库层超时与内部错误原文（英文长串）不再直出吓
          // 用户；业务反馈批1015c Q9 收敛为全站公共映射 humanizeAiError（聊天错误卡与 AI
          // 用量管理页错误列共用）——超时优先、INTERNAL_ERROR 兜底码不误报网络问题
          const h = humanizeAiError(msg, code);
          turn.errorMsg = h.chat;
          turn.errorDetail = h.detail;
        }
        turn.errorCode = code || '';
        turn.phase = 'error';
        // AU-23: 错误轮同样收尾——否则打字机光标永久闪烁、部分文本永不渲染 md、ticker 不停
        turn.streamDone = true;
        ensureTypingLoop();
      }
    };
  }

  /** 思考链路批(1008 用户报障)：thinkTier 单一真源注入——档位存于 Composer 的
   *  dcs:assistant:thinkLevel:{userId}（与 persistKey 同源派生）。此前只有 Composer
   *  正常发送链带 thinkTier，快捷提问/欢迎卡/队列补发/须知门放行/resume 等内部路径
   *  全丢（10-08 日志实证 38 个请求 forwardedProps 全空）→ 统一在 runTurn 出口注入，
   *  调用方显式传入的 fwd 优先 */
  function currentThinkTier(): string {
    if (!persistKey) {
      return 'balanced';
    }
    try {
      const saved = Number(localStorage.getItem(persistKey.replace(':thread:', ':thinkLevel:')));
      const tiers = ['fast', 'balanced', 'deep'];
      return Number.isInteger(saved) && saved >= 0 && saved < tiers.length ? tiers[saved] : 'balanced';
    } catch {
      return 'balanced';
    }
  }

  /** 批7 Q12-④ 同款单一真源：模型 pin 存于 Composer 的
   *  dcs:assistant:modelPin:{userId}:{threadId}（与 thinkLevel 同源派生 userId）——
   *  须知门放行/队列补发/快捷提问等内部路径不走 Composer fwdProps，统一在 runTurn
   *  出口注入（与 currentThinkTier 同坑同修，2026-10-09 E2E 实证放行路径丢参） */
  function currentModelPin(): string | null {
    if (!persistKey) {
      return null;
    }
    try {
      const uid = persistKey.split(':').pop() ?? '';
      const v = localStorage.getItem('dcs:assistant:modelPin:' + uid + ':' + state.threadId);
      return v && v.length > 0 ? v : null;
    } catch {
      return null;
    }
  }

  /**
   * 业务批3-④ #17：联网开关单一真源注入——Composer 开关行写
   * dcs:assistant:webOn:{userId}:{threadId}（webOnState 模块），快捷提问/须知门放行/
   * 队列补发等内部路径不走 Composer，统一在 runTurn 出口注入（currentModelPin 同坑同修）
   */
  function currentWebOn(): boolean {
    if (!persistKey) {
      return false;
    }
    const uid = persistKey.split(':').pop() ?? '';
    return readWebOn(uid, state.threadId, localStorage);
  }

  /**
   * 五需求批 R1：技能选择单一真源注入——➕面板技能组写
   * dcs:assistant:skill:{userId}:{threadId}（skillState 模块），选中后本会话每轮都带、
   * 摘掉即不带；后端 SkillInjectModelDecorator 按它当轮注入技能正文（正文不下发前端）
   */
  function currentSkillId(): string | null {
    if (!persistKey) {
      return null;
    }
    const uid = persistKey.split(':').pop() ?? '';
    return readSkillId(uid, state.threadId, localStorage);
  }

  /** 发起一轮（runTurn；history 镜像同步 user 消息）。fwd=请求级标记（思考档位等，AGUI forwardedProps 通道） */
  async function runTurn(q: string, stamp = '', fwd: Record<string, unknown> = {}) {
    // R1：禁用统一闸（队列续发/立即发送/继续说完/须知门均汇入此处，见 assertUsable）
    if (!assertUsable()) {
      return;
    }
    const turn = reactive<AssistantTurn>(emptyTurn(q));
    turn.stamp = stamp;
    state.turns.push(turn);
    // glm53 P2-4：新回合开始=此前回合的 pending 卡已不可能再被 resume（服务端只认最新轮
    // must-cover 全等）——入口即扫，防「弃卡改问后新回合又挂 interrupt」双待确认卡同屏
    sweepStaleHitlCards(turn);
    state.sending = true;
    state.newbieUse += 1;
    history.push({ id: 'u' + seq, role: 'user', content: q });
    if (persistKey) {
      try {
        localStorage.setItem(persistKey, state.threadId);
      } catch {
        /* 忽略 */
      }
    }

    const input: AguiRunInput = {
      runId: uuid(),
      threadId: state.threadId,
      messages: [...history],
      state: {},
      forwardedProps: {
        thinkTier: currentThinkTier(),
        ...(currentModelPin() ? { modelConfigId: currentModelPin() } : {}),
        ...(currentWebOn() ? { webSearchEnabled: true } : {}),
        ...(currentSkillId() ? { skillId: currentSkillId() } : {}),
        ...fwd
      }
    };
    const myGen = convGen;
    const myHandle = client.startTurn(input, handlers(turn));
    handle = myHandle;
    const result = await myHandle.done;
    // 归属守卫：仅当 handle 仍属于本回合才清（新回合可能已接管共享槽位）
    if (handle === myHandle) {
      handle = null;
    }
    // 代数守卫：会话已切换/重开 → 旧回合收尾整体作废（不落账/不补发/不动 sending）
    if (myGen !== convGen) {
      return;
    }
    turn.durMs = Date.now() - turn.startedAt;
    // assistant 回复入 history 镜像（AGUI 每轮全量重传——多轮上下文；E2E 实证缺此步二轮即失忆）
    if (turn.bufText && !turn.hitl) {
      history.push({ id: 'a' + seq, role: 'assistant', content: turn.bufText });
    }
    if (result === 'stopped') {
      turn.phase = wasStopped ? 'stopped' : turn.phase;
      turn.streamDone = true;
      ensureTypingLoop();
    } else if (result === 'finished' && !['done', 'waiting', 'error', 'stopped'].includes(turn.phase)) {
      // 无终态事件的静默完成（防御口径）
      turn.phase = 'done';
      turn.streamDone = true;
      // E2E 实证（弃卡改问+SSE 重连，2026-10-10）：文本帧到达但终态 handler 未触发走此
      // 分支——此前回合挂着的 pending 确认卡同步收掉（服务端同 thread 已自动解锁跑完
      // 新轮，旧卡必失效；不扫=僵尸卡留屏直到手动刷新）
      sweepStaleHitlCards(turn);
      ensureTypingLoop();
    } else if (result === 'disconnected') {
      if (!turn.errorMsg) {
        turn.errorMsg = '连接已断开，内容可能不完整';
        if (turn.phase !== 'done' && turn.phase !== 'error') {
          turn.phase = 'error';
        }
      }
      // AU-23: 断线轮收尾（部分文本定稿渲染 md+停光标），与错误轮同口径
      turn.streamDone = true;
      ensureTypingLoop();
    }
    state.sending = false;
    // 三档自主权（原型 autoPop）：档1=该工具确认过一次即放行；档2=确认卡一律自动确认。
    // 后端仍走完整 resume 契约+审计，前端只省掉「问你」这一步
    const lastTurn = cur();
    // 批5 Q7-3：正常完结的回合延时拉 LLM 追问建议（侧车异步回写，通常 2~4s 出；
    // 空/失败/超时一律保持静态池——追问是锦上添花）
    if (lastTurn && lastTurn.phase === 'done') {
      scheduleFollowupPull(lastTurn);
    }
    const hitlKey = lastTurn?.hitl ? hitlToolKey(lastTurn.hitl) : '';
    // 批W2（SPEC §3.1'）：act_ 前缀动作类永不自动放行——「全自动」档与「同类只问一次」
    // 对写操作整条关闭（确认卡必弹），防自主权档位放大误操作面。
    // 判定双源：metadata.toolName（真实帧）与 reason（旧形态帧 reason=工具名）都认
    const lastIsActTool = isActToolHitl(lastTurn?.hitl ?? null);
    const autoOk =
      !lastIsActTool &&
      lastTurn?.hitl &&
      lastTurn.hitl.cardState === 'pending' &&
      // 业务批7 Q14：勾过「本会话不再逐次确认」的 key 直接放行——不再叠加档位门槛
      //（原 `autoLevel==='once' &&` 门槛使"每次都问"档勾选形同虚设；勾选升档会改写全局
      // 偏好、与卡面「本会话内」文案冲突——resolveHitl 里的升档段已同步摘除，k3 P2-3 修正史实）
      (state.autoLevel === 'auto' || (!!hitlKey && state.autoApproved.includes(hitlKey)));
    if (result === 'finished' && autoOk) {
      toast(state.autoLevel === 'auto' ? '已按「全自动」直接执行，完成见回答（可回面板调回）' : '同类操作只问一次：已自动确认执行', 'ok');
      resolveHitl(true);
      return;
    }
    const stoppedThisTurn = wasStopped;
    wasStopped = false;
    consumeQueue(stoppedThisTurn);
  }

  /** 确认卡 → 工具名键（批W2：metadata.toolName 优先=稳定同类键；旧形态 reason=工具名；
   *  都没有才落 toolCallId 段——跨轮次会变，仅作最后兜底） */
  function hitlToolKey(h: HitlAsk): string {
    if (h.toolName) {
      return h.toolName;
    }
    if (h.reason && ACT[h.reason]) {
      return h.reason;
    }
    const seg = h.interruptId.split(':')[1];
    return seg || h.reason || '';
  }

  /**
   * 批W2（SPEC §3.1'）：动作类工具判定——act_ 前缀永不走免确认通道。
   * 双源判定：metadata.toolName（真实帧 reason=tool_call 时唯一工具名来源）+
   * reason（旧形态帧直接放工具名）；reason='tool_call' 不是工具名不误伤。
   */
  function isActToolHitl(h: HitlAsk | null): boolean {
    return (h?.toolName ?? '').startsWith('act_') || (h?.reason ?? '').startsWith('act_');
  }

  /** 队列六条④前的统一消费口（v3.18）：草稿自动排队→补发队首→全部答完回执 */
  /** 是否存在未处理的确认（停靠区期间输入框锁定） */
  function hasPendingHitl(): boolean {
    return state.turns.some((t) => t.hitl?.cardState === 'pending');
  }

  /** 与停靠区同源的取卡口径——反向找最近一张 pending 卡。
   *  resume 失败时原卡回 pending，而 cur() 是刚 push 的 resume 回合（hitl=null）——
   *  旧口径 cur() 会静默 return，卡点不动+输入框被锁=会话死锁。 */
  function pendingHitlTurn(): AssistantTurn | null {
    for (let i = state.turns.length - 1; i >= 0; i--) {
      if (state.turns[i].hitl?.cardState === 'pending') return state.turns[i];
    }
    return null;
  }

  function consumeQueue(wasStoppedTurn = false) {
    // 待确认期间冻结队列——否则 interrupt 刚挂起就被下一条消息冲掉
    if (hasPendingHitl()) {
      return;
    }
    // 暂停态不自动续发（批H P2-13）：「队列已暂停待手动恢复」的 UI 承诺与自动补发矛盾
    if (state.queuePaused) {
      return;
    }
    if (state.queue.length === 0) {
      if (qDrained > 0 && !state.queuePaused && !wasStoppedTurn) {
        toast(`排队 ${qDrained} 条已全部答完 ✓`);
      }
      qDrained = 0;
      return;
    }
    // ①答完补发队首前，输入框草稿自动排进队尾（字永远不丢）
    const draft = (bindings.getDraft?.() ?? '').trim();
    if (draft) {
      state.queue.push({ text: draft, pendingId: pendingIdSeq++ });
      bindings.clearDraft?.();
      toast(`你打的「${draft.slice(0, 16)}」已自动排队，不会丢`);
    }
    const next = state.queue.shift()!;
    qDrained += 1;
    const myGen = convGen;
    setTimeout(() => {
      if (myGen !== convGen) {
        return; // 排定补发期间会话被切换 → 该条作废（rebuild 已清队列，勿以新 thread 发出）
      }
      if (state.sending) {
        // 上一轮未收敛完又塞回去（防丢）
        state.queue.unshift(next);
        return;
      }
      runTurn(next.text);
    }, 200);
  }

  /**
   * 发送入口（队列语义）：sending 中=排队不静默吞；灰气泡同步上屏（Q2A）。
   * fwd=请求级标记（思考档位等，随 forwardedProps 透传）。
   */
  function send(question: string, stamp = '', fwd: Record<string, unknown> = {}) {
    const q = question.trim();
    if (!q) {
      return;
    }
    // 有未处理的确认时禁止新消息——发新轮会冲掉服务端 interrupt，
    // 停靠区只能确认或取消（输入框此时已收起，这里挡快捷问/主动卡/程序化入口）
    if (hasPendingHitl()) {
      toast('请先确认或取消上面的操作，再继续提问', 'info');
      return;
    }
    // 全屏「今日概况」行的 🕒 上次使用时间（抽屉/全屏/会话页统一走本入口）
    try {
      localStorage.setItem('dcs:assistant:lastUse', new Date().toISOString());
    } catch {
      /* 存储不可用忽略 */
    }
    if (!state.gateDone) {
      state.gatePendQ = q;
      return; // 组件层弹须知门，gateOk() 后重发
    }
    // DP-6 原型 lastAsk：最近一问（hero 召回行「上次你问到」；按用户分键，与 persistKey 同源）
    if (persistKey) {
      try {
        localStorage.setItem(persistKey.replace(':thread:', ':lastAsk:'), q);
      } catch {
        /* 存储不可用忽略 */
      }
    }
    if (state.sending) {
      state.queue.push({ text: q, pendingId: pendingIdSeq++ });
      toast('已排队——不打断当前回答，答完自动发出', 'ok');
      return;
    }
    runTurn(q, stamp, fwd);
  }

  /**
   * 停止（幂等，无拒绝窗）：误触兜底=部分回答保留+可继续；pauseQueue 后队列冻结待手动恢复。
   */
  function stop(pauseQueue = true) {
    if (!state.sending) {
      return;
    }
    wasStopped = true;
    if (pauseQueue && state.queue.length > 0) {
      state.queuePaused = true;
    }
    handle?.stop();
    // G1 乙类#5：服务端真取消（fire-and-forget——本地断流照旧先行，后端秒级落终态
    // 而非等 60s 收割；失败静默：会话已不在/已停=幂等无事发生）
    if (state.threadId) {
      import('@/api/dcs/assistant').then((m) => m.stopAssistantRun(state.threadId)).catch(() => {});
    }
  }

  /** 「▶ 继续说完」：按原问题重新请求（SSE 停止=流中断，服务端无续帧协议） */
  function resumeSay() {
    const t = cur();
    if (!t) {
      return;
    }
    send(t.question);
  }

  /** 队列条三项操作：✎ 撤回改 / ↗ 提前（回答中=挪队首）/ ✕ 移除；②暂停态收敛 */
  function queueEdit(i: number, draft = ''): string {
    const [item] = state.queue.splice(i, 1);
    collapsePausedIfEmpty();
    // BN-4 原型口径：回填前先护住用户正在打的字（入队不丢）
    const d = draft.trim();
    if (d) {
      state.queue.push({ text: d, pendingId: pendingIdSeq++ });
      toast('你打的「' + d.slice(0, 10) + (d.length > 10 ? '…' : '') + '」已自动排队，不会丢', 'ok');
    }
    toast('已撤回到输入框——改完再发', 'ok');
    return item.text;
  }
  function queueGo(i: number) {
    if (state.queue.length === 0) {
      return;
    }
    const [item] = state.queue.splice(i, 1);
    if (state.sending) {
      state.queue.unshift(item);
      toast('已挪到队首，答完就发', 'ok');
    } else {
      runTurn(item.text);
    }
    collapsePausedIfEmpty();
  }
  function queueDrop(i: number) {
    state.queue.splice(i, 1);
    collapsePausedIfEmpty();
    // BN-18 原型口径：移除有回执（队列清空恢复的 toast 由 collapsePausedIfEmpty 出）
    if (state.queue.length > 0) {
      toast('已从队列移除', 'ok');
    }
  }
  function queueResume() {
    state.queuePaused = false;
    // BN-3 原型口径：恢复即取队首立即发送（此刻必不在发送中——暂停态=已停）；队空则只复位
    if (!state.sending && state.queue.length > 0) {
      const [item] = state.queue.splice(0, 1);
      runTurn(item.text);
    }
  }
  function collapsePausedIfEmpty() {
    if (state.queuePaused && state.queue.length === 0) {
      state.queuePaused = false;
      toast('队列已清空，恢复正常发送', 'ok');
    }
  }

  /** 须知门放行（同意后原问题自动继续）。思考链路批(1008)：同意结果按用户落
   *  localStorage（同源派生键）——此前只存内存，每次刷新/重开浏览器首问必再弹门 */
  function gateOk() {
    state.gateDone = true;
    if (persistKey) {
      try {
        localStorage.setItem(persistKey.replace(':thread:', ':gateOk:'), '1');
      } catch {
        /* 存储不可用忽略——内存态本会话仍有效 */
      }
    }
    const q = state.gatePendQ;
    state.gatePendQ = '';
    if (q) {
      send(q);
    }
  }

  /**
   * HITL 确认/取消（E3）：resume 契约=RunAgentInput.resume[{interruptId=Interrupt.id, status}]。
   * 确认 → resume 轮（不 push 新 user 消息——ConfirmResult 由服务端 converter 合成），
   * 原确认卡转 run 态；resume 轮完成时回执 done。取消 → cancelled 不报错，卡收回执。
   * remember=true 且批准 → 工具名入 state.autoApproved（本会话同工具不再逐次确认）。
   */
  async function resolveHitl(approved: boolean, remember = false, edits?: HitlEdits) {
    // 波 0.1（多审 P1-B）：确认卡可能不属于最后一个回合——H2 确认轮失败回滚 pending 后，
    // cur() 是失败的 resume 回合（hitl 为空），盲取会让停靠区唯一可见的确认/取消静默失效。
    // 取卡口径与停靠区同源：优先最近一张 pending 卡，取不到再回退 cur()；
    // 两头都没有时给一次人话提示而非静默吞掉点击
    const turn = pendingHitlTurn() ?? cur();
    if (!turn?.hitl) {
      toast('确认卡已处理或失效，稍等片刻再试', 'info');
      return;
    }
    if (state.sending) {
      return;
    }
    // R1：批准类确认卡也走禁用闸（本路径 client.startTurn 不经 runTurn；取消放行——
    //  清服务端 interrupt 属收尾不是新发送）
    if (approved && !assertUsable()) {
      return;
    }
    // 取消也必须发 cancelled resume 清服务端 interrupt——纯本地收卡会让 thread 挂着
    // unresolved interrupt，后续全轮被「RunAgentInput.resume must address all of them」拒（E2E 实证）
    turn.hitl.cardState = 'run';
    if (!approved) {
      turn.hitl.cancelled = true;
    } else {
      const key = hitlToolKey(turn.hitl);
      // 批W2（SPEC §3.1'）：act_ 动作类不入 autoApproved——即使「同类只问一次」档
      // 或卡面勾选（卡面对 act_* 已不渲染勾选，此处是数据层二道闸）
      if (!isActToolHitl(turn.hitl) && key && !state.autoApproved.includes(key)) {
        // 档1 确认即记同类（第二次起免问）；档0 勾「本会话内不再逐次确认」也记
        //（k3 P2-3：勾选不再升档改全局偏好——消费端 autoOk 已按 autoApproved 放行，
        // 升档+persistAutoLevel 属「本会话内」文案之外的越权副作用，整段摘除）
        if (state.autoLevel === 'once' || remember) {
          state.autoApproved.push(key);
        }
      }
    }
    const resumeTurn = reactive<AssistantTurn>({
      ...emptyTurn(turn.question),
      question: (approved ? '（确认执行）' : '（已取消）') + turn.question
    });
    state.turns.push(resumeTurn);
    state.sending = true;
    const input: AguiRunInput = {
      runId: uuid(),
      threadId: state.threadId,
      messages: [...history],
      state: {},
      // 业务批3-④：HITL 确认后的补跑也是一次 /agui/run——不带 webSearchEnabled 会让
      // resolver 误判开关翻转而失效重建 agent（开着联网的会话在确认后掉 web_search）。
      // R4a E2E 实锤同款缺陷扩面：modelPin/thinkTier 不注入=确认补跑静默回落默认模型
      // （用户 pin 了可用模型、默认模型故障时确认必挂；批7 快捷提问丢参同族）——
      // 与 runTurn 出口同款四件套统一注入
      forwardedProps: {
        thinkTier: currentThinkTier(),
        ...(currentModelPin() ? { modelConfigId: currentModelPin() } : {}),
        ...(currentWebOn() ? { webSearchEnabled: true } : {}),
        ...(currentSkillId() ? { skillId: currentSkillId() } : {})
      }
    };
    const myGen = convGen;
    // 三审 P1-2：resume 必须覆盖本轮全部悬挂（服务端集合全等校验）——点击卡按用户意图
    // resolved/cancelled，其余未展示卡一律 cancelled（确认轮被拒的工具模型会重新询问）。
    // payload.approved=true 是批准的必要条件（jar 反汇编 isApproved：仅 status=resolved 不带
    // payload 判 denied → 工具不执行，模型回复「已取消」——E2E 实证）；取消走 isCancelled 短路
    const hitlIds = [...new Set([...turn.hitl.pendingIds, turn.hitl.interruptId])].filter((x) => x !== '');
    // CF-1：确认卡编辑（删人/附言）→ payload.editedArgs 全量替换工具入参
    // （javap 实证 AguiMessageConverter：editedArgs 非空即覆盖，键须与 @ToolParam 同名）。
    // editedArgs 是整体替换——CF-1 路径必须有完整精确的原始入参做底（toolInput 缺失/
    // 不安全时不带 editedArgs，服务端按模型原始参数执行）。
    // 表单卡（formCreate）例外：五键即工具完整参数面，不依赖 toolInput 镜像做底
    // （glm53 批F 审查 P2-2：镜像缺失时卡面编辑被静默丢弃，用户改的字段没生效）
    const formCreate = approved ? edits?.formCreate : undefined;
    // R4a 表单卡（act_create_template）：与 formCreate 同模式——四键即完整参数面
    const formTemplate = approved ? edits?.formTemplate : undefined;
    const editedArgs: Record<string, unknown> | undefined = formCreate
      ? {
          mission_name: formCreate.missionName,
          // mission_type 发 number——字符串绑 @ToolParam Integer 静默 null（批W3 镜像保型教训）
          mission_type: formCreate.missionType,
          begin_time: formCreate.beginTime,
          end_time: formCreate.endTime,
          description: formCreate.description
        }
      : formTemplate
        ? {
            template_name: formTemplate.templateName,
            fields_json: formTemplate.fieldsJson,
            description: formTemplate.description,
            visibility: formTemplate.visibility
          }
        : approved && edits && turn.hitl.toolInput
          ? (() => {
              const merged: Record<string, unknown> = { ...turn.hitl.toolInput };
              if (edits.message !== undefined) {
                merged.message = edits.message;
              }
              if (edits.targetUserIds) {
                merged.target_user_ids = edits.targetUserIds.join(',');
              }
              if (edits.remark !== undefined) {
                merged.remark = edits.remark;
              }
              return merged;
            })()
          : undefined;
    // 把实际执行的参数回写到卡上——停靠区收卡后卡组件销毁，回执详情是新实例，
    // 只能靠 toolInput 还原「当时改成了什么」（否则回执只显示模型原始值，改过的附言/名单丢失）
    if (approved && editedArgs) {
      turn.hitl.toolInput = { ...(turn.hitl.toolInput ?? {}), ...editedArgs };
    }
    const resumePayload = (hitlIds.length > 0 ? hitlIds : [turn.hitl.interruptId]).map((id) =>
      id === turn.hitl!.interruptId
        ? {
            interruptId: id,
            status: approved ? 'resolved' : 'cancelled',
            ...(approved ? { payload: { approved: true, ...(editedArgs ? { editedArgs } : {}) } } : {})
          }
        : { interruptId: id, status: 'cancelled' }
    );
    const myHandle = client.startTurn(
      {
        ...input,
        resume: resumePayload
      },
      handlers(resumeTurn)
    );
    handle = myHandle;
    const result = await myHandle.done;
    if (handle === myHandle) {
      handle = null;
    }
    if (myGen !== convGen) {
      return;
    }
    resumeTurn.durMs = Date.now() - resumeTurn.startedAt;
    // 确认轮的工具结果由 onToolResult 回填到原回合步骤（被打断时步骤建在原回合）——
    // 来源条/DataCard 随答案走：把带结果的步骤并入 resume 轮（引用同对象，不双写）
    for (const s of turn.steps) {
      if (s.resultFull && !resumeTurn.steps.some((x) => x.id === s.id)) {
        resumeTurn.steps.push(s);
        resumeTurn.toolCalls += 1;
      }
    }
    if (resumeTurn.bufText) {
      history.push({ id: 'a' + seq, role: 'assistant', content: resumeTurn.bufText });
    }
    if (result === 'stopped') {
      resumeTurn.phase = 'stopped';
    } else if (result === 'finished' && !['done', 'waiting', 'error', 'stopped'].includes(resumeTurn.phase)) {
      resumeTurn.phase = 'done';
      resumeTurn.streamDone = true;
      ensureTypingLoop();
    } else if (result === 'disconnected' && !resumeTurn.errorMsg) {
      resumeTurn.errorMsg = '连接已断开，执行结果未知，请到系统页面核实';
      resumeTurn.phase = 'error';
      resumeTurn.streamDone = true; // AU-23: 同主轮口径收尾
      ensureTypingLoop();
    }
    // H2：确认轮失败（非契约类，如断网/限流）时卡回 pending 可重试——无条件置 done 会把
    // 「待确认」吞成「已确认执行」，用户以为执行了实际没有；取消路径（cancelled=true）不回弹
    if (resumeTurn.phase === 'error' && !turn.hitl.cancelled) {
      turn.hitl.cardState = 'pending';
    } else {
      turn.hitl.cardState = 'done';
      // 079：确认后点停止——执行结果未知，卡面按 stopped 标记走中性文案，不断言已完成
      turn.hitl.stopped = resumeTurn.phase === 'stopped';
    }
    // 批5 回修（审查 P2）：HITL 确认轮是写操作主路径，done 后同样拉追问——
    // 原实现只在 runTurn 主轮拉，确认轮生成的追问本会话永不显示（刷新才见）
    if (resumeTurn.phase === 'done') {
      scheduleFollowupPull(resumeTurn);
    }
    state.sending = false;
    consumeQueue(false);
  }

  /** 四处清理点（会话切换/重置）：先清队列再停回合（防 drain 定时器带进新会话） */
  /**
   * 新建会话逃生口：thread 卡死（刷新丢 pending 确认卡→「unresolved interrupts」全拒，
   * E2E 实证）时换 threadId 重开。挂起 interrupt 的服务端恢复=后端 pending 端点（P2 登记）。
   */
  function newSession() {
    resetForNewSession();
    stopTypingLoop();
    state.turns = [];
    state.newbieUse = 0;
    state.autoApproved = []; // 工具级免确认积累随会话重置
    state.todoItems = []; // ZCode 对齐 Q2：新会话不带旧清单
    // autoLevel 是跨会话的全局偏好（原型口径），新会话不重置
    history.length = 0; // 历史镜像同步清空——旧会话指令带入新 thread 会幽灵重放（E2E 实证）
    state.threadId = uuid();
    if (persistKey) {
      try {
        localStorage.setItem(persistKey, state.threadId);
      } catch {
        /* 忽略 */
      }
    }
  }

  /** ZCode 对齐 Q2（批B）：打开会话恢复 TODO 清单（宿主切换 thread 时调；失败静默=空清单兜底） */
  async function loadTodo(threadId: string) {
    // k3 P1-1 回修：代数守卫（restorePendingHitl 同款）——连点/跨宿主/慢响应下，
    // A 会话的晚归响应不得把清单写进已切换的 B 会话面板（catch 清空同理只许落在同代会话）
    const myGen = convGen;
    try {
      // request 层返回整个 body（{code,msg,data}——与宿主 listMessages 同款取法）
      const body = (await getConversationTodo(threadId)) as unknown as {
        data?: Array<{ content?: unknown; status?: unknown; priority?: unknown }>;
      };
      const arr = body?.data;
      const items = (Array.isArray(arr) ? arr : [])
        .filter((it) => it && typeof it.content === 'string' && it.content)
        .map((it) => ({
          content: String(it.content),
          status: (['pending', 'in_progress', 'completed'].includes(String(it.status)) ? String(it.status) : 'pending') as
            | 'pending'
            | 'in_progress'
            | 'completed',
          priority: (['high', 'medium', 'low'].includes(String(it.priority)) ? String(it.priority) : 'medium') as 'high' | 'medium' | 'low'
        }));
      if (myGen === convGen) {
        state.todoItems = items;
      }
    } catch {
      if (myGen === convGen) {
        state.todoItems = [];
      }
    }
  }

  /** 追问定论：侧车拉取窗口走完仍无结果即定论（业务反馈批1015c Q10 删静态兜底池后，
   *  "未生成"不再显示任何灰字/通用问题——贴题追问宁缺毋滥，followupsSettled 保留给回放与测试判定） */
  function settleFollowups(turn: AssistantTurn) {
    turn.followupsSettled = true;
  }

  function resetForNewSession() {
    convGen += 1;
    // k3 P1-1 回修：切会话/新会话即清任务清单——旧会话清单不得残留到新面板
    // （loadTodo 晚归响应被上面的代数守卫拦下，不会回填旧值）
    state.todoItems = [];
    state.queue = [];
    state.queuePaused = false;
    qDrained = 0;
    stop(false);
    // 旧回合收尾已被代数守卫拦下（不再复位 sending）——重置即复位，UI 不停留发送态
    state.sending = false;
    state.curConvArchived = false;
  }

  /** 三档自主权切换（批I-2 原型 autoPop）：切档即重置同类积累（原型 autoDid=false 同义） */
  function setAutoLevel(lv: AssistantState['autoLevel']) {
    state.autoLevel = lv;
    state.autoApproved = [];
    persistAutoLevel();
  }

  /** 档位持久化（Q9）：切档与「记住我的选择」隐式降档都落盘 */
  function persistAutoLevel() {
    if (!persistKey) {
      return;
    }
    try {
      localStorage.setItem(persistKey.replace(':thread:', ':autoLevel:'), state.autoLevel);
    } catch {
      /* 存储不可用忽略 */
    }
  }

  /** T-2(1008 拍板B)：思考过程总显示开关——落偏好 + 即时生效（现存回合的思考块随 watch 联动展开/收起） */
  function setThinkShow(v: boolean) {
    state.showThink = v;
    if (persistKey) {
      try {
        localStorage.setItem(persistKey.replace(':thread:', ':thinkShow:'), v ? '1' : '0');
      } catch {
        /* 存储不可用忽略 */
      }
    }
  }

  /**
   * 分支（原型 branchHere）：从某回合切新会话——原会话在库里原样保留（新 threadId 不动它），
   * 前文完整带过来（只读回放到该回合+history 镜像同步，下轮模型有上下文）。
   */
  function branchFrom(turn: AssistantTurn) {
    const idx = state.turns.indexOf(turn);
    if (idx < 0) {
      return;
    }
    const msgs: Array<{ role: string; content: string }> = [];
    for (let i = 0; i <= idx; i++) {
      const t = state.turns[i];
      msgs.push({ role: 'user', content: t.question });
      if (t.bufText && !t.hitl) {
        msgs.push({ role: 'assistant', content: t.bufText });
      }
    }
    newSession();
    rebuildFromHistory(msgs);
    toast('已从这里分支——原会话保留，前文我记住了', 'ok');
  }

  /**
   * E5 会话重建：按后端消息回放只读回合 + history 镜像同步（供下轮 AGUI 全量重传）。
   *
   * <p>批J-4：assistant 消息带 toolSteps（enricher 落库的工具步骤 JSON）时恢复
   * turn.steps 与 toolCalls——历史回合 stat 不再「0/1 未调用工具」；resultFull
   * 持久化侧不落库（体积/PII 面），回放后业务卡不重渲染，属已知边界。</p>
   *
   * <p>threadId 同步（glm53 审查 P1-4）：切换到另一条会话时必须连同换 threadId 并重写持久键，
   * 否则恢复的是新会话内容、发送仍落旧 thread——消息写串会话。</p>
   */
  /** 批5 Q7-3：done 回合延时拉取追问建议；侧车写库普遍比 done+3.5s 慢（E2E 实测差 2s），
   * 一次拉取常拿空 → 3.5s/9s/18s 三次递进重试，拉到即停。
   * 七席审查 P1 回修——结构判定替代活性 phase 判定：
   * ①stillMine=本回合仍是 turns 尾巴且处终态——重连把 phase 翻 error 不再断链（生成结果白丢）；
   * ②用户重发/切会话后本回合不再是尾巴 → 在途回调全废（防旧回合错挂新回合的追问），
   * 顺带涵盖 convGen 守卫语义（切会话重建 turns，旧 turn 必非尾巴）。 */
  function scheduleFollowupPull(turn: AssistantTurn) {
    const tid = state.threadId;
    if (!tid || tid.length <= 8) {
      return;
    }
    const stillMine = () => state.turns[state.turns.length - 1] === turn && ['done', 'error', 'stopped'].includes(turn.phase);
    const done = () => !stillMine() || !!turn.followups?.length;
    // 稳流① Q8①：聚焦/回页即拉（90s 窗口内切回来不等下一个轮询间隔）；声明前置供 pull 引用
    const detachFocusPull = () => {
      if (typeof globalThis.removeEventListener === 'function') {
        globalThis.removeEventListener('focus', onFocusPull);
        globalThis.removeEventListener('visibilitychange', onFocusPull);
      }
    };
    /** 单次拉取：onEmpty=空结果继续策略；onFail=请求失败收尾策略；均由调用方按轮次给 */
    const pull = (onEmpty?: () => void, onFail?: () => void) => {
      // 全防御：同步抛（测试环境无 request）与异步 rejection 都不得逃逸——
      // 假定时器场景一个抛错会打断同批 timer 队列（队列补发实测被打断的教训）
      try {
        getFollowups(tid)
          .then((res) => {
            if (done()) {
              detachFocusPull();
              return;
            }
            // request 层返回整个 body（{code,msg,data}——getConversationTodo 同款取法）
            const items = (res as unknown as { data?: { followups?: string[] } })?.data?.followups;
            if (Array.isArray(items) && items.length > 0) {
              turn.followups = items.filter((x) => typeof x === 'string').slice(0, 3);
              detachFocusPull();
              return;
            }
            onEmpty?.();
          })
          .catch(() => onFail?.());
      } catch {
        onFail?.();
      }
    };
    const attempt = (idx: number) => {
      // globalThis 而非 window：vitest 是 node 环境（queue 用例实锤 window 引用同步抛）
      globalThis.setTimeout(() => {
        if (done()) {
          detachFocusPull();
          return;
        }
        // 侧车尚未写完（常见）或模型输出不合规（少数）：空结果还有额度就再等一轮；
        // 最后一轮仍空 → 定论"未生成"，灰字标注才允许显示，不再糊弄；
        // 请求失败=静默停轮（回退静态池，最后一次失败也不再试）——attempt 语义与旧版逐字对齐
        const last = idx + 1 >= FOLLOWUP_PULL_DELAYS.length;
        const settleIfEmpty = () => {
          if (!turn.followups?.length) {
            settleFollowups(turn);
          }
          detachFocusPull();
        };
        pull(
          () => (last ? settleIfEmpty() : attempt(idx + 1)),
          () => (last ? settleIfEmpty() : undefined)
        );
      }, FOLLOWUP_PULL_DELAYS[idx]);
    };
    const onFocusPull = () => {
      if (done() || turn.followupsSettled) {
        detachFocusPull();
        return;
      }
      // 拉到即写；空/失败不打断 attempt 序列自己的收尾节奏
      pull();
    };
    if (typeof globalThis.addEventListener === 'function') {
      globalThis.addEventListener('focus', onFocusPull);
      globalThis.addEventListener('visibilitychange', onFocusPull);
    }
    attempt(0);
  }

  function rebuildFromHistory(
    messages: Array<{
      role: string;
      content: string;
      totalTokens?: number;
      toolSteps?: string | null;
      followupSuggestions?: string | null;
      endReason?: string | null;
    }>,
    threadId?: string
  ) {
    // 先按新会话收敛（批H P1-3）：清队列+代数递增（在途回合收尾作废）+sending 复位
    // （stop 的 abort settle 可能滞后数秒——重建的是只读视图，UI 不应停留在旧回合的发送态）
    resetForNewSession();
    if (threadId && threadId.length > 8) {
      state.threadId = threadId;
      if (persistKey) {
        try {
          localStorage.setItem(persistKey, threadId);
        } catch {
          /* 存储不可用忽略 */
        }
      }
    }
    state.turns = [];
    history.length = 0;
    let cur: AssistantTurn | null = null;
    // 批17 Q2：role=system 的压缩标记行——挂到下一个回合（compactedBefore 上方渲染分隔条）；
    // 若其后暂无新回合则挂在 state.lastCompaction（尾部悬挂分隔条）。content 形如
    // {"type":"compaction","beforeTokens":N,"afterTokens":N}；解析失败静默忽略（非压缩 system 行）
    let pendingCompaction: { beforeTokens?: number | null; afterTokens?: number | null } | null = null;
    state.lastCompaction = null;
    for (const m of messages) {
      const content = m.content ?? '';
      if (m.role === 'system') {
        try {
          const parsed = JSON.parse(content) as { type?: string; beforeTokens?: number | null; afterTokens?: number | null };
          if (parsed?.type === 'compaction') {
            pendingCompaction = { beforeTokens: parsed.beforeTokens ?? null, afterTokens: parsed.afterTokens ?? null };
          }
        } catch {
          /* 非压缩标记的 system 行忽略 */
        }
      } else if (m.role === 'user') {
        cur = reactive<AssistantTurn>(emptyTurn(content));
        cur.phase = 'done';
        cur.bufText = '';
        if (pendingCompaction) {
          cur.compactedBefore = pendingCompaction;
          pendingCompaction = null;
        }
        state.turns.push(cur);
        history.push({ id: 'hu' + history.length, role: 'user', content });
      } else if (m.role === 'assistant' && cur) {
        // N-05（业务批4 回修）：连续 assistant 行（侧车分段落库/停顿续写）不再互相覆盖——
        // 追加合并（\n\n 分段），shownLen/answerHtml 按合并后重算；首行走原路径
        cur.bufText = cur.bufText ? cur.bufText + '\n\n' + content : content;
        cur.shownLen = cur.bufText.length;
        cur.streamDone = true;
        // 批6-#2：中断回答可判（end_reason=partial/truncated）——徽标+不再假装完整答案
        cur.interrupted = cur.interrupted || m.endReason === 'partial' || m.endReason === 'truncated';
        // m3 P0-1 回修：渲染合并后全文——首条 message 时 renderMd(content) 与 bufText 等价，
        // 第二条起若只渲 content 会把首段回答从气泡里无声吃掉
        cur.answerHtml = renderMd(cur.bufText);
        cur.durMs = 0;
        const { steps, thinks } = parsePersistedTurnSteps(m.toolSteps);
        // m3 P1-4 回修：段/步合并而非覆盖（seq=后端同 thread 单调计数器，跨消息不回卷不碰撞），
        // 覆盖会让首段的思考段与工具步在重建视图里丢失
        cur.steps = [...cur.steps, ...steps];
        cur.toolCalls = cur.steps.length;
        // 批5 Q7-3：LLM 追问建议随历史回放（脏数据忽略回退静态池）
        if (m.followupSuggestions) {
          try {
            const arr = JSON.parse(m.followupSuggestions);
            if (Array.isArray(arr) && arr.length > 0) {
              cur.followups = arr.filter((x) => typeof x === 'string').slice(0, 3);
            }
          } catch {
            /* 回退静态池 */
          }
        }
        // Q5-A：思考段随回放恢复（seq 与 steps 同计数器，时间线交错）；总耗时=各段和
        // （m3 P1-4/P2-3 回修：思考段合并累加不覆盖、总耗时=各段和；镜像推合并后全文，
        // 否则第二段覆盖首段思考、下一轮 AGUI 重传缺首段上下文）
        cur.thinkSegs = [...cur.thinkSegs, ...thinks];
        cur.thinkDurMs = cur.thinkDurMs + thinks.reduce((sum, s) => sum + (s.durMs || 0), 0);
        history.push({ id: 'ha' + history.length, role: 'assistant', content: cur.bufText });
      }
    }
    // 批17 Q2：循环结束时仍挂着的压缩标记=system 行之后还没有新回合——尾部悬挂渲染
    if (pendingCompaction) {
      state.lastCompaction = pendingCompaction;
    }
    state.newbieUse = state.turns.length;
    // 须知同意是粘性的（AU-16）：空会话重建（保 threadId 首发落原行）不得把已同意用户打回首见门
    state.gateDone = state.gateDone || state.turns.length > 0;
    // 二站-B（C2，2026-10-07）：换设备/刷新后确认卡随会话内存态丢失——查悬挂缓存
    // （与框架 pending 同寿）重建尾部回合的确认卡；分支场景查新 threadId 天然为空。
    // 未显式传 threadId（抽屉重开恢复）时用 persistKey 已还原的 state.threadId
    const restoreTid = threadId && threadId.length > 8 ? threadId : state.threadId;
    if (restoreTid && restoreTid.length > 8) {
      void restorePendingHitl(restoreTid);
    }
  }

  /**
   * 二站-B 悬挂卡重建：尾部 user 回合（无 assistant 跟随=悬挂轮）恢复 turn.hitl
   * 待确认态。resume 契约照旧走 resolveHitl（interruptId=复合键集合全等）；
   * argsJson 截断非法 JSON 时不带编辑底（服务端按模型原始参数执行）。
   */
  async function restorePendingHitl(threadId: string) {
    // H4：代数必须在 await 之前捕获——旧位置在 await 之后取恒等于当前值，
    // 后续比对永假=守卫失效（会话切换期间恢复的卡会挂到新会话头上）
    const myGen = convGen;
    try {
      // request 拦截器成功路径返回 R 包装——data ?? 本体双兼容（Composer 同款口径）
      const raw = (await getPendingHitl(threadId)) as unknown as { data?: unknown };
      const items = (Array.isArray(raw?.data) ? raw.data : raw) as PendingHitlItem[];
      if (!Array.isArray(items) || items.length === 0) {
        return;
      }
      const last = state.turns[state.turns.length - 1];
      // 悬挂缓存有卡=thread 末态即悬挂（后续新消息会被 resume must-cover 拒），
      // 尾部 turn 就是卡归属轮——ACT 类卡常先流名单文本再挂（bufText 非空不排除）；
      // 已有卡/无回合才跳过
      if (!last || last.hitl) {
        return;
      }
      if (myGen !== convGen) {
        return;
      }
      let toolInput: Record<string, unknown> | undefined;
      try {
        const parsed = items[0].argsJson ? (JSON.parse(items[0].argsJson) as unknown) : null;
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
          toolInput = parsed as Record<string, unknown>;
        }
      } catch {
        /* args 超 400 截断非合法 JSON：不带编辑底 */
      }
      last.hitl = {
        interruptId: items[0].interruptId,
        pendingIds: items.map((i) => i.interruptId).filter(Boolean),
        toolName: items[0].toolName || undefined,
        toolInput,
        reason: items[0].reason,
        cardState: 'pending'
      };
      last.phase = 'waiting';
    } catch (e) {
      // AU-41(1008)：403=他人确认卡/越权探测（BusinessError.code），与"无挂起卡"（空数组静默）
      // 必须可区分——不判码会被当成无卡继续走，排错时两态无法分辨；记 warn 不阻塞回放
      const code = (e as { code?: number })?.code;
      if (code) {
        console.warn(`[assistant] 挂起确认探测被拒(code=${code})，按无卡继续回放`);
      }
    }
  }

  /**
   * AU-35(1008 拍板A)：错误卡「恢复挂起的确认」——LangGraph 同款 re-surface 模式。
   * 复用悬挂缓存 peek（restorePendingHitl）把挂着的确认卡拉回当前错误回合重建；
   * 成功=错误卡撤下转等待确认（会话不废）；无可恢复=提示走「新建会话」兜底。
   */
  async function recoverInterrupt(): Promise<void> {
    const turn = cur();
    if (!turn || !state.threadId || state.sending) {
      return;
    }
    const before = turn.hitl;
    await restorePendingHitl(state.threadId);
    if (turn.hitl && turn.hitl !== before) {
      turn.errorMsg = '';
      turn.errorCode = '';
      turn.errorDetail = '';
      turn.phase = 'waiting';
      toast('已恢复挂起的确认，接着答就行', 'ok');
    } else if (!turn.hitl) {
      toast('没有找到可恢复的确认——请用「新建会话」重新开始', 'err');
    }
  }

  /**
   * S-38 硬销毁（登出/换人）：断流+清定时器+清回合。
   * 单例模式下抽屉关闭【不】调它——收起面板回答照常在后台收敛，重开即续（原型 stash 语义）。
   */
  function dispose() {
    resetForNewSession();
    stopTypingLoop();
    state.turns = [];
  }

  return {
    state,
    cur,
    send,
    stop,
    resumeSay,
    resolveHitl,
    setAutoLevel,
    queueEdit,
    queueGo,
    queueDrop,
    queueResume,
    gateOk,
    rebuildFromHistory,
    recoverInterrupt,
    setThinkShow,
    newSession,
    loadTodo,
    branchFrom,
    resetForNewSession,
    dispose,
    /** 单例多宿主切换：bind 压栈生效（当前可见宿主接管草稿/toast/鉴权回调） */
    bind: (b: AssistantBindings) => {
      bindStack.push(b);
      applyBindTop();
    },
    /** bind 的逆操作（组件卸载时调）：按身份弹（宿主持有 bind 时的引用传回），
     * 不匹配的卸载序不再误弹别人的绑定（会话页先卸载时旧盲 pop 会把还开着的抽屉弹掉）；
     * 不传参数=弹栈顶（兜底兼容） */
    unbind: (b?: AssistantBindings) => {
      if (b) {
        const i = bindStack.lastIndexOf(b);
        if (i >= 0) {
          bindStack.splice(i, 1);
        }
      } else {
        bindStack.pop();
      }
      applyBindTop();
    },
    /** 测试/重建用：历史镜像 */
    historyRef: history
  };
}

/**
 * 单例池（PROTO PARITY 批A0）：抽屉/全屏覆盖层/会话页三宿主共享同一会话状态机。
 * 按 userId 缓存；disposeAssistant=登出硬销毁。工厂函数 useAssistant 保持可独立实例化（测试用）。
 */
const assistantPool = new Map<string, ReturnType<typeof useAssistant>>();

export function getAssistant(userId: string | number): ReturnType<typeof useAssistant> {
  const key = String(userId ?? '0');
  let a = assistantPool.get(key);
  if (!a) {
    a = useAssistant({ persistKey: 'dcs:assistant:thread:' + key });
    assistantPool.set(key, a);
  }
  return a;
}

export function disposeAssistant(userId: string | number) {
  const key = String(userId ?? '0');
  assistantPool.get(key)?.dispose();
  assistantPool.delete(key);
}
