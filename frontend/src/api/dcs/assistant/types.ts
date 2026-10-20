/**
 * AGUI 协议类型（批E Task E1）。
 * 事件枚举与后端 io.agentscope.core.agui.event.AguiEventType（2.0.3，28 项）
 * 逐字对齐——javap 实证；payload 字段与 AguiEvent 各 record 访问器对齐
 * （threadId/runId/messageId/delta/toolCallId/…，E2E 实测帧形态同源）。
 */

export const AGUI_EVENT = {
  RUN_STARTED: 'RUN_STARTED',
  RUN_FINISHED: 'RUN_FINISHED',
  RUN_ERROR: 'RUN_ERROR',
  STEP_STARTED: 'STEP_STARTED',
  STEP_FINISHED: 'STEP_FINISHED',
  TEXT_MESSAGE_START: 'TEXT_MESSAGE_START',
  TEXT_MESSAGE_CONTENT: 'TEXT_MESSAGE_CONTENT',
  TEXT_MESSAGE_END: 'TEXT_MESSAGE_END',
  TEXT_MESSAGE_CHUNK: 'TEXT_MESSAGE_CHUNK',
  TOOL_CALL_START: 'TOOL_CALL_START',
  TOOL_CALL_ARGS: 'TOOL_CALL_ARGS',
  TOOL_CALL_END: 'TOOL_CALL_END',
  TOOL_CALL_RESULT: 'TOOL_CALL_RESULT',
  TOOL_CALL_CHUNK: 'TOOL_CALL_CHUNK',
  STATE_SNAPSHOT: 'STATE_SNAPSHOT',
  STATE_DELTA: 'STATE_DELTA',
  MESSAGES_SNAPSHOT: 'MESSAGES_SNAPSHOT',
  ACTIVITY_SNAPSHOT: 'ACTIVITY_SNAPSHOT',
  ACTIVITY_DELTA: 'ACTIVITY_DELTA',
  RAW: 'RAW',
  CUSTOM: 'CUSTOM',
  REASONING_START: 'REASONING_START',
  REASONING_END: 'REASONING_END',
  REASONING_MESSAGE_START: 'REASONING_MESSAGE_START',
  REASONING_MESSAGE_CONTENT: 'REASONING_MESSAGE_CONTENT',
  REASONING_MESSAGE_END: 'REASONING_MESSAGE_END',
  REASONING_MESSAGE_CHUNK: 'REASONING_MESSAGE_CHUNK',
  REASONING_ENCRYPTED_VALUE: 'REASONING_ENCRYPTED_VALUE'
} as const;

export type AguiEventType = (typeof AGUI_EVENT)[keyof typeof AGUI_EVENT];

/** AGUI 线上帧（SSE data: 行反序列化产物，字段按事件类型可选存在） */
export interface AguiEventFrame {
  type: AguiEventType | string;
  threadId?: string;
  runId?: string;
  messageId?: string;
  role?: string;
  delta?: string;
  toolCallId?: string;
  toolCallName?: string;
  content?: string;
  message?: string;
  code?: string;
  name?: string;
  value?: unknown;
  outcome?: { type?: string; [k: string]: unknown };
  event?: unknown;
  [k: string]: unknown;
}

/** 发送侧消息（AGUI 每轮重传全量历史；当轮=最后一条 user——后端同口径） */
export interface AguiMessage {
  id?: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
}

/** 一轮运行输入（RunAgentInput 前端形态；state 必须是对象，禁数组——框架反序列化红线） */
export interface AguiRunInput {
  runId: string;
  threadId: string;
  messages: AguiMessage[];
  state?: Record<string, unknown>;
  forwardedProps?: Record<string, unknown>;
  /** HITL 恢复（agentscope 2.0.3 javap+E2E 实证）：interruptId=RUN_FINISHED outcome
   *  interrupts[].id（threadId:toolCallId 复合键，服务端 AguiResumeCoordinator 按 keySet 逐字匹配；
   *  纯 toolCallId 会被「must cover all open interrupts」拒）；status resolved/cancelled →
   *  服务端 converter 合成 ConfirmResult Msg（参数由服务端 interrupt 还原） */
  resume?: AguiResume[];
}

export interface AguiResume {
  interruptId: string;
  status: 'resolved' | 'cancelled';
  payload?: Record<string, unknown>;
}

/** 事件处理器（E2 组件按需挂；全量帧走 onEvent 兜底） */
export interface AguiHandlers {
  onEvent?: (ev: AguiEventFrame) => void;
  onTextStart?: (messageId: string, role?: string) => void;
  onTextDelta?: (messageId: string, delta: string) => void;
  onTextEnd?: (messageId: string) => void;
  onReasoningStart?: (messageId: string) => void;
  onReasoningDelta?: (messageId: string, delta: string) => void;
  onReasoningEnd?: (messageId: string) => void;
  onToolStart?: (toolCallId: string, toolCallName: string) => void;
  onToolArgs?: (toolCallId: string, delta: string) => void;
  onToolEnd?: (toolCallId: string) => void;
  onToolResult?: (toolCallId: string, content: string) => void;
  onStepStarted?: (stepName?: string) => void;
  onStepFinished?: (stepName?: string) => void;
  /** RUN_FINISHED（outcome.type=interrupt 为 HITL 暂停——确认卡数据源） */
  onFinished?: (outcome?: AguiEventFrame['outcome']) => void;
  /** RUN_ERROR（服务端话术已人话化） */
  onRunError?: (message: string, code?: string) => void;
  onCustom?: (name: string, value: unknown) => void;
  /**
   * 稳流① Q1（2026-10-20 拍板 A②）：停滞超窗（默认 600s 无任何字节）裁决——
   * 返回 'keep-waiting'=继续等（同一 read 续竞速并重挂看门狗）；'stop'=按手动停止收口。
   * 停滞不自动重发：服务端可能仍在生成，重发撞 409 防双计费。无此回调时退回旧自动重试口径。
   */
  onStall?: () => Promise<'keep-waiting' | 'stop'> | 'keep-waiting' | 'stop';
}
