/**
 * 助手回合状态机单测（批E Task E2）。
 * 判据：AGUI 事件桥（思考/工具/正文/终态/HITL interrupt）；打字机合帧追赶；
 * 队列六条（排队/草稿自动排队/暂停收敛/答完回执/提前/移除）；停止幂等；须知门拦截。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { AguiHandlers } from '@/api/dcs/assistant/types';

// 可控假客户端：测试手动驱动 handlers 与 done
let liveHandlers: AguiHandlers | null = null;
let resolveDone: ((r: 'finished' | 'error' | 'stopped' | 'disconnected') => void) | null = null;
let stopCalls = 0;
let lastInput: { resume?: Array<{ interruptId: string; status: string }> } | null = null;
vi.mock('@/api/dcs/assistant/aguiClient', () => ({
  createAguiClient: (_opts: unknown) => ({
    startTurn: (_input: unknown, handlers: AguiHandlers) => {
      liveHandlers = handlers;
      lastInput = _input as { resume?: Array<{ interruptId: string; status: string }> };
      const done = new Promise<'finished' | 'error' | 'stopped' | 'disconnected'>((resolve) => {
        resolveDone = resolve;
      });
      return {
        stop: () => {
          stopCalls += 1;
        },
        done
      };
    }
  })
}));
// 思考链路批(1008)：REST 桶 '@/api/dcs/assistant' 会拉起 utils/request→router→window
// （node 环境无 window，文件级导入失败=存量假红）——mock 掉恢复本文件可运行
// 稳流① Q8①：追问拉取可控应答器（聚焦补拉用例）
let todoResponder: (() => unknown) | null = null;
let followupResponder: (() => unknown) | null = null;
vi.mock('@/api/dcs/assistant', () => ({
  getPendingHitl: async () => ({ data: [] as unknown[] }),
  getConversationTodo: async () => (todoResponder ? todoResponder() : { code: 200, msg: 'ok', data: [] }),
  getFollowups: async () => (followupResponder ? followupResponder() : { code: 200, msg: 'ok', data: {} })
}));

import { useAssistant } from './useAssistant';

/** 手动帧时钟：收集 tick 由测试驱动（打字机合帧） */
function manualTicker() {
  const ticks: Array<() => void> = [];
  return {
    ticker: (fn: () => void) => {
      ticks.push(fn);
      return () => undefined;
    },
    drain: () => {
      // 同一 tick 反复执行到收敛（模拟 rAF 多帧；追赶 35%/帧）
      while (ticks.length > 0) {
        const fn = ticks.shift() as () => void;
        for (let i = 0; i < 200; i++) {
          fn();
        }
      }
    }
  };
}

function setup() {
  const t = manualTicker();
  const toasts: string[] = [];
  let draft = '';
  const a = useAssistant({
    ticker: t.ticker,
    onToast: (x) => toasts.push(x),
    getDraft: () => draft,
    clearDraft: () => {
      draft = '';
    }
  });
  return { a, toasts, drain: t.drain, setDraft: (s: string) => (draft = s) };
}

function finishTurn() {
  resolveDone?.('finished');
}

/** 手动停止：真实 client 停止路径 resolve 'stopped'，fake 同口径 */
function stopTurn() {
  resolveDone?.('stopped');
}

beforeEach(() => {
  todoResponder = null;
  followupResponder = null;
  lastInput = null;
  liveHandlers = null;
  resolveDone = null;
  stopCalls = 0;
  vi.clearAllTimers();
});

describe('AGUI 事件桥', () => {
  it('完整回合：思考→工具→正文→RUN_FINISHED 渲染 md', async () => {
    const { a, drain } = setup();
    a.state.gateDone = true;
    a.send('我有哪些待办？');
    await Promise.resolve();
    const turn = a.cur()!;
    expect(turn.phase).toBe('thinking');

    liveHandlers!.onReasoningDelta?.('m1', '用户想查待办，');
    liveHandlers!.onReasoningDelta?.('m1', '调用 query_my_todos');
    liveHandlers!.onReasoningEnd?.('m1');
    expect(turn.thinkText).toContain('query_my_todos');
    expect(turn.thinkDone).toBe(true);

    liveHandlers!.onToolStart?.('tc1', 'query_my_todos');
    liveHandlers!.onToolArgs?.('tc1', '{"status":"PENDING"}');
    liveHandlers!.onToolResult?.('tc1', '{"total":3,"items":[]}');
    expect(turn.steps).toHaveLength(1);
    expect(turn.steps[0].verb).toBe('查待办');
    expect(turn.steps[0].status).toBe('ok');

    liveHandlers!.onTextStart?.('m2', 'assistant');
    liveHandlers!.onTextDelta?.('m2', '你有 **3 条**待办');
    liveHandlers!.onTextEnd?.('m2');
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    drain();
    expect(turn.phase).toBe('done');
    expect(turn.answerHtml).toContain('<strong>3 条</strong>');
    expect(a.historyRef.filter((m) => m.role === 'user')).toHaveLength(1);
  });

  it('工具失败判定与 RUN_ERROR 终态', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('查逾期');
    await Promise.resolve();
    const turn = a.cur()!;
    liveHandlers!.onToolStart?.('tc9', 'query_deadline_alerts');
    liveHandlers!.onToolResult?.('tc9', '{"error":"TIMEOUT"}');
    expect(turn.steps[0].status).toBe('err');
    liveHandlers!.onRunError?.('查询超时了', 'TIMEOUT');
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    expect(turn.phase).toBe('error');
    expect(turn.errorMsg).toBe('查询超时了');
  });

  it('二站 T4：契约错误按 code 精确识别 → 人话 toast 指路重发（禁止文本匹配）', async () => {
    const { a, toasts } = setup();
    a.state.gateDone = true;
    a.send('继续催');
    await Promise.resolve();
    const turn = a.cur()!;
    // T0 实测帧：code=AGUI_INTERRUPT_CONTRACT_ERROR，message 是框架英文原文——不得透给用户
    liveHandlers!.onRunError?.('Thread has unresolved interrupts; RunAgentInput.resume must address all of them', 'AGUI_INTERRUPT_CONTRACT_ERROR');
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    expect(turn.phase).toBe('error');
    expect(turn.errorMsg).toBe('上一次的确认已失效，再发一遍就好');
    expect(toasts).toContain('上一次的确认已失效，再发一遍就好');
    // 普通错误话术不受影响（同 message 不同 code 不误判）
    const { a: a2 } = setup();
    a2.state.gateDone = true;
    a2.send('再问');
    await Promise.resolve();
    const turn2 = a2.cur()!;
    liveHandlers!.onRunError?.('Thread has unresolved interrupts; RunAgentInput.resume must address all of them', 'PROVIDER_ERROR');
    resolveDone?.('error');
    await vi.waitFor(() => expect(a2.state.sending).toBe(false));
    expect(turn2.errorMsg).toContain('unresolved interrupts');
  });

  it('业务批7 Q16-③：重启毒化暂停错（无 code 英文帧）→ 窄前缀兜底人话 + 收卡', async () => {
    const { a, toasts } = setup();
    a.state.gateDone = true;
    a.send('论文mcp有哪些');
    await Promise.resolve();
    const turn = a.cur()!;
    // 用户实测帧：CallExecution 深处抛出、不带结构化 code——code 精确匹配够不到
    liveHandlers!.onRunError?.(
      'Agent is paused for human-in-the-loop confirmation: the following tool call(s) are in ASKING state [web_search (id=call_ee727af)]',
      'RUN_ERROR'
    );
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    expect(turn.phase).toBe('error');
    expect(turn.errorMsg).toBe('上一步的确认卡住了，再发一遍就好');
    expect(toasts).toContain('上一步的确认卡住了，再发一遍就好');
    // 相似但不同源的英文错误不误判（前缀必须严格命中）
    const { a: a3 } = setup();
    a3.state.gateDone = true;
    a3.send('再问');
    await Promise.resolve();
    const turn3 = a3.cur()!;
    liveHandlers!.onRunError?.('An agent is paused for maintenance window', 'OTHER');
    resolveDone?.('error');
    await vi.waitFor(() => expect(a3.state.sending).toBe(false));
    expect(turn3.errorMsg).toContain('maintenance window');
  });

  it('HITL interrupt：RUN_FINISHED 真实形态 interrupts[].id → waiting + 确认卡数据', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('催一下 022 班');
    await Promise.resolve();
    const turn = a.cur()!;
    // 真实 SSE 帧形态（agentscope 2.0.3 Jackson）：outcome.interrupts[0].id = threadId:toolCallId
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [{ id: 'th-1:call_00_abc', toolCallId: 'call_00_abc', reason: 'act_remind', message: '将给 12 人发催办' }]
    });
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    expect(turn.phase).toBe('waiting');
    // resume 契约键=Interrupt.id 复合键（E2E 实证：纯 toolCallId 会被服务端 must-cover 拒）
    expect(turn.hitl?.interruptId).toBe('th-1:call_00_abc');
    expect(turn.hitl?.pendingIds).toEqual(['th-1:call_00_abc']);
    expect(turn.hitl?.reason).toBe('act_remind');
    expect(turn.hitl?.message).toContain('12 人');
  });

  it('弃卡改问防御分支：新回合创建须扫掉旧 pending 确认卡（E2E 2026-10-10 实证；稳流① 2026-10-20 改道）', async () => {
    const { a, toasts } = setup();
    a.state.gateDone = true;
    a.send('催一下 022 班');
    await Promise.resolve();
    const turnA = a.cur()!;
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [{ id: 'th-1:call_00_z', toolCallId: 'call_00_z', reason: 'act_remind', message: '确认执行' }]
    });
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    expect(turnA.phase).toBe('waiting');
    expect(turnA.hitl?.cardState).toBe('pending');
    // 波B(d7753e3 2026-10-19)起 send() 有 pending 卡直接拦（停靠区锁输入框口径）——
    // 先验证拦截生效，再改走仍开放的队列直发口（queueGo→runTurn 不过 send 闸）
    a.send('直接再问一句');
    expect(a.cur()).toBe(turnA);
    expect(toasts).toContain('请先确认或取消上面的操作，再继续提问');
    a.state.queue.push({ text: '一句话回答：天空为什么是蓝色', pendingId: 9001 });
    a.queueGo(0);
    await Promise.resolve();
    const turnB = a.cur()!;
    expect(turnB).not.toBe(turnA);
    resolveDone?.('finished');
    await vi.waitFor(() => expect(turnB.phase).toBe('done'));
    // 旧 pending 卡不得留屏成僵尸（runTurn 入口 sweep——glm53 P2-4 双待确认卡防线）
    expect(turnA.hitl?.cardState).toBe('done');
    expect(turnA.hitl?.cancelled).toBe(true);
  });

  it('CF-1：interrupt metadata 带 toolName/toolInput（字符串形态归一）→ 确认卡富卡数据', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('催一下数学作业');
    await Promise.resolve();
    const turn = a.cur()!;
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [
        {
          id: 'th-1:call_00_rem',
          toolCallId: 'call_00_rem',
          reason: 'tool_call',
          message: '确认执行 act_remind',
          metadata: {
            'agentscope.interruptKind': 'permission_confirm',
            toolName: 'act_remind',
            toolInput: '{"mission_id":"2092652290059091970","topic_id":"204","message":"请尽快提交"}'
          }
        }
      ]
    });
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    expect(turn.hitl?.toolName).toBe('act_remind');
    expect(turn.hitl?.toolInput).toEqual({
      mission_id: '2092652290059091970',
      topic_id: '204',
      message: '请尽快提交'
    });
  });

  it('CF-1 精度防线：dcsToolInput 字符串镜像优先；无镜像且 19 位 id 是 JSON 数字（前端必被取整）→ toolInput 置空降级通用卡', async () => {
    // ① 镜像在场：topic_id 数字形态也不影响，取镜像的精确字符串
    const { a } = setup();
    a.state.gateDone = true;
    a.send('催一下数学作业');
    await Promise.resolve();
    const turn = a.cur()!;
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [
        {
          id: 'th-1:call_00_rem',
          metadata: {
            toolName: 'act_remind',
            toolInput: { mission_id: '2092652290059091970', topic_id: 2080604413476536300 },
            dcsToolInput: { mission_id: '2092652290059091970', topic_id: '2080604413476536326' }
          }
        }
      ]
    });
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    expect(turn.hitl?.toolInput).toEqual({
      mission_id: '2092652290059091970',
      topic_id: '2080604413476536326'
    });

    // ② 镜像缺失 + topic_id 数字：editedArgs 回声不可信 → toolInput 必须为空（富卡降级）
    const { a: a2 } = setup();
    a2.state.gateDone = true;
    a2.send('再催');
    await Promise.resolve();
    const turn2 = a2.cur()!;
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [
        {
          id: 'th-2:call_00_rem',
          metadata: {
            toolName: 'act_remind',
            toolInput: { mission_id: '2092652290059091970', topic_id: 2080604413476536300 }
          }
        }
      ]
    });
    finishTurn();
    await vi.waitFor(() => expect(a2.state.sending).toBe(false));
    expect(turn2.hitl?.toolInput).toBeUndefined();
  });

  it('批W3 回修：审核卡（镜像全字符串形态）remark 编辑 → editedArgs 全量替换回传 remark 键', async () => {
    // 三审 dsflash P0 语境补充：dcsToolInput 镜像把 pass 变 "false" 字符串——
    // 本用例钉住镜像形态下 remark 编辑链（editedArgs 以镜像为底座全量替换）
    const { a } = setup();
    a.state.gateDone = true;
    a.send('帮我退回这条提交');
    await Promise.resolve();
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [
        {
          id: 'th-1:call_00_aud',
          toolCallId: 'call_00_aud',
          reason: 'tool_call',
          message: '确认执行',
          metadata: {
            toolName: 'act_audit',
            dcsToolInput:
              '{"mission_id":"2102341964054724610","trace_id":"2102341982388027394","submission_batch_id":"2102605923840184321","expected_submission_version":"1","pass":"false","remark":""}'
          }
        }
      ]
    });
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    const hitl = a.cur()!.hitl!;
    expect(hitl.toolName).toBe('act_audit');
    // 镜像全字符串：pass 到卡层是 "false"（ConfirmCard 判定归一在组件层，此处钉数据形态）
    expect(hitl.toolInput?.pass).toBe('false');
    void a.resolveHitl(true, false, { remark: '表格第3行工号有误，请核对后重新提交' });
    await Promise.resolve();
    expect(lastInput?.resume).toEqual([
      {
        interruptId: 'th-1:call_00_aud',
        status: 'resolved',
        payload: {
          approved: true,
          editedArgs: {
            mission_id: '2102341964054724610',
            trace_id: '2102341982388027394',
            submission_batch_id: '2102605923840184321',
            expected_submission_version: '1',
            pass: 'false',
            remark: '表格第3行工号有误，请核对后重新提交'
          }
        }
      }
    ]);
  });

  it('批F2 表单卡：act_create_mission typed 镜像五字段 → formCreate 全量替换（蛇形键+mission_type 保持 number）', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('帮我建一个信息收集任务');
    await Promise.resolve();
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [
        {
          id: 'th-1:call_00_cre',
          toolCallId: 'call_00_cre',
          reason: 'tool_call',
          metadata: {
            toolName: 'act_create_mission',
            dcsToolInput: '{"mission_name":"办公用品需求表","mission_type":1,"begin_time":"2026-10-13 09:00","end_time":"","description":"季度需求摸底"}'
          }
        }
      ]
    });
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    const hitl = a.cur()!.hitl!;
    expect(hitl.toolName).toBe('act_create_mission');
    // 批W3 镜像保型：mission_type 是 number（字符串会绑 @ToolParam Integer 静默 null）
    expect(hitl.toolInput?.mission_type).toBe(1);
    void a.resolveHitl(true, false, {
      formCreate: {
        missionName: '办公用品需求表（修订）',
        missionType: 2,
        beginTime: '',
        endTime: '2026-10-20 18:00:00',
        description: ''
      }
    });
    await Promise.resolve();
    // 五键蛇形全量替换（editedArgs 整体替换语义；空串=字段留空，后端 lenient 判空）
    expect(lastInput?.resume).toEqual([
      {
        interruptId: 'th-1:call_00_cre',
        status: 'resolved',
        payload: {
          approved: true,
          editedArgs: {
            mission_name: '办公用品需求表（修订）',
            mission_type: 2,
            begin_time: '',
            end_time: '2026-10-20 18:00:00',
            description: ''
          }
        }
      }
    ]);
  });

  it('批F 回修 P2-2：表单卡镜像缺失（无 dcsToolInput/toolInput）→ formCreate 五键仍构造完整 editedArgs，不静默丢弃', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('帮我建任务');
    await Promise.resolve();
    // 镜像缺失形态：metadata 只有 toolName（无 toolInput 镜像）——用户卡面编辑的字段
    // 若依赖镜像做底会被静默丢弃，按模型原始参数落库（glm53 批F 审查 P2-2）
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [
        {
          id: 'th-1:call_00_nomirror',
          toolCallId: 'call_00_nomirror',
          reason: 'tool_call',
          metadata: { toolName: 'act_create_mission' }
        }
      ]
    });
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    const hitl = a.cur()!.hitl!;
    expect(hitl.toolName).toBe('act_create_mission');
    expect(hitl.toolInput).toBeUndefined();
    void a.resolveHitl(true, false, {
      formCreate: {
        missionName: '无镜像场景任务',
        missionType: 1,
        beginTime: '2026-10-14 08:00:00',
        endTime: '2026-10-30 23:59:00',
        description: '全量直构'
      }
    });
    await Promise.resolve();
    // formCreate 五键即工具完整参数面——脱离 toolInput 前置条件直接构造
    expect(lastInput?.resume).toEqual([
      {
        interruptId: 'th-1:call_00_nomirror',
        status: 'resolved',
        payload: {
          approved: true,
          editedArgs: {
            mission_name: '无镜像场景任务',
            mission_type: 1,
            begin_time: '2026-10-14 08:00:00',
            end_time: '2026-10-30 23:59:00',
            description: '全量直构'
          }
        }
      }
    ]);
  });

  it('CF-1：确认卡编辑（删人+附言）→ resume payload.editedArgs 全量替换（键与 @ToolParam 同名）', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('催一下数学作业');
    await Promise.resolve();
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [
        {
          id: 'th-1:call_00_rem',
          toolCallId: 'call_00_rem',
          metadata: {
            toolName: 'act_remind',
            toolInput: { mission_id: '2092652290059091970', topic_id: '204', message: '原始附言' }
          }
        }
      ]
    });
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    a.resolveHitl(true, false, { message: '改过的附言', targetUserIds: [101, 102] });
    await Promise.resolve();
    expect(lastInput?.resume).toEqual([
      {
        interruptId: 'th-1:call_00_rem',
        status: 'resolved',
        payload: {
          approved: true,
          editedArgs: {
            mission_id: '2092652290059091970',
            topic_id: '204',
            message: '改过的附言',
            target_user_ids: '101,102'
          }
        }
      }
    ]);
  });

  it('三审 P1-2：一 run 挂 2 interrupt → resume 全量覆盖（点击卡 resolved，未展示卡 cancelled）', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('催办并发通知');
    await Promise.resolve();
    const turn = a.cur()!;
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [
        { id: 'th-1:call_00_aaa', toolCallId: 'call_00_aaa', reason: 'act_remind', message: '将给 12 人发催办' },
        { id: 'th-1:call_01_bbb', toolCallId: 'call_01_bbb', reason: 'act_notify', message: '将发 3 条通知' }
      ]
    });
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    expect(turn.hitl?.pendingIds).toEqual(['th-1:call_00_aaa', 'th-1:call_01_bbb']);
    // 服务端 resume 集合全等校验（must-cover）：只回一张卡的 id 会被拒
    a.resolveHitl(true);
    await Promise.resolve();
    expect(lastInput?.resume).toEqual([
      { interruptId: 'th-1:call_00_aaa', status: 'resolved', payload: { approved: true } },
      { interruptId: 'th-1:call_01_bbb', status: 'cancelled' }
    ]);
    // 取消路径：全部 cancelled
    const { a: a2 } = setup();
    a2.state.gateDone = true;
    a2.send('再催');
    await Promise.resolve();
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [
        { id: 'th-2:call_00_ccc', toolCallId: 'call_00_ccc' },
        { id: 'th-2:call_01_ddd', toolCallId: 'call_01_ddd' }
      ]
    });
    finishTurn();
    await vi.waitFor(() => expect(a2.state.sending).toBe(false));
    a2.resolveHitl(false);
    await Promise.resolve();
    expect(lastInput?.resume).toEqual([
      { interruptId: 'th-2:call_00_ccc', status: 'cancelled' },
      { interruptId: 'th-2:call_01_ddd', status: 'cancelled' }
    ]);
  });

  it('打字机：delta 入 buffer，合帧追赶至全文后渲染', async () => {
    const { a, drain } = setup();
    a.state.gateDone = true;
    a.send('长回答');
    await Promise.resolve();
    const turn = a.cur()!;
    liveHandlers!.onTextStart?.('m1', 'assistant');
    liveHandlers!.onTextDelta?.('m1', '一'.repeat(100));
    liveHandlers!.onTextEnd?.('m1');
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    drain();
    expect(turn.shownLen).toBe(100);
    expect(turn.answerHtml).toContain('一'.repeat(100));
  });
});

describe('队列六条（v3.18）', () => {
  it('BN-3 暂停后续发=立即派发队首（runTurn 直发，不等下一轮）', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('第一问');
    await Promise.resolve();
    a.send('第二问');
    expect(a.state.queue.map((q) => q.text)).toEqual(['第二问']);
    // 模拟用户中断：队列暂停
    a.state.queuePaused = true;
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    // 续发：queueResume 立即取队首发送
    a.queueResume();
    expect(a.state.queue.length).toBe(0);
    expect(a.state.queuePaused).toBe(false);
    expect(a.state.turns[a.state.turns.length - 1].question).toBe('第二问');
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
  });

  it('③发送中排队不静默吞；答完自动逐条补发；⑥全部答完回执', async () => {
    const { a, toasts } = setup();
    a.state.gateDone = true;
    a.send('第一问');
    await Promise.resolve();
    a.send('第二问');
    a.send('第三问');
    expect(a.state.queue.map((q) => q.text)).toEqual(['第二问', '第三问']);
    expect(toasts[0]).toContain('已排队');

    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    // 200ms 后补发队首
    await new Promise((r) => setTimeout(r, 260));
    expect(a.state.queue.map((q) => q.text)).toEqual(['第三问']);
    expect(a.cur()!.question).toBe('第二问');
    // 收尾第二问 → 第三问 → 全部答完
    for (let i = 0; i < 2; i++) {
      liveHandlers!.onFinished?.(undefined);
      finishTurn();
      await vi.waitFor(() => expect(a.state.sending).toBe(false));
      await new Promise((r) => setTimeout(r, 260));
    }
    expect(toasts.some((t) => t.includes('已全部答完'))).toBe(true);
    expect(a.state.queue).toHaveLength(0);
  });

  it('①答完补发队首前草稿自动排进队尾（字永远不丢）', async () => {
    const { a, toasts, setDraft } = setup();
    a.state.gateDone = true;
    a.send('第一问');
    await Promise.resolve();
    a.send('排队问');
    setDraft('我正在打的半句话');
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    await new Promise((r) => setTimeout(r, 260));
    expect(a.state.queue.map((q) => q.text)).toEqual(['我正在打的半句话']);
    expect(toasts.some((t) => t.includes('已自动排队，不会丢'))).toBe(true);
  });

  it('②暂停态删光队列自动收敛恢复发送', async () => {
    const { a, toasts } = setup();
    a.state.gateDone = true;
    a.send('第一问');
    await Promise.resolve();
    a.send('q1');
    a.send('q2');
    a.stop(true);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    expect(a.state.queuePaused).toBe(true);
    a.queueDrop(1);
    a.queueDrop(0);
    expect(a.state.queuePaused).toBe(false);
    expect(toasts.some((t) => t.includes('队列已清空'))).toBe(true);
  });

  it('④✎ 撤回取回文本；↗ 回答中提前=挪队首', async () => {
    const { a, toasts } = setup();
    a.state.gateDone = true;
    a.send('第一问');
    await Promise.resolve();
    a.send('q1');
    a.send('q2');
    expect(a.queueEdit(0)).toBe('q1');
    a.send('q1b');
    a.queueGo(1); // sending 中：q1b 挪到队首
    expect(toasts.some((t) => t.includes('已挪到队首'))).toBe(true);
    expect(a.state.queue.map((q) => q.text)).toEqual(['q1b', 'q2']);
  });
});

describe('停止与门', () => {
  it('停止幂等：连点无害，回合收敛 stopped 且部分回答保留', async () => {
    const { a, drain } = setup();
    a.state.gateDone = true;
    a.send('慢问题');
    await Promise.resolve();
    const turn = a.cur()!;
    liveHandlers!.onTextStart?.('m1', 'assistant');
    liveHandlers!.onTextDelta?.('m1', '部分回答');
    a.stop();
    a.stop();
    a.stop();
    stopTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    drain();
    expect(turn.phase).toBe('stopped');
    expect(turn.bufText).toBe('部分回答');
  });

  it('▶ 继续说完=按原问题重发', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('原问题');
    await Promise.resolve();
    a.stop();
    stopTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    a.resumeSay();
    await Promise.resolve();
    expect(a.state.turns).toHaveLength(2);
    expect(a.cur()!.question).toBe('原问题');
  });

  it('须知门拦截首问，同意后原问题自动继续', async () => {
    const { a } = setup();
    a.send('我的待办');
    expect(a.state.turns).toHaveLength(0);
    expect(a.state.gatePendQ).toBe('我的待办');
    a.gateOk();
    await Promise.resolve();
    expect(a.state.gateDone).toBe(true);
    expect(a.cur()!.question).toBe('我的待办');
  });

  it('会话重置清理点：先清队列再停回合', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('在途问');
    await Promise.resolve();
    a.send('排队问');
    a.resetForNewSession();
    expect(a.state.queue).toHaveLength(0);
    expect(a.state.queuePaused).toBe(false);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
  });

  it('批H P1-3：流式中切会话——旧回合迟到的收尾不写进新会话', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('旧会话问题');
    await Promise.resolve();
    liveHandlers!.onTextDelta?.('m1', '旧会话回复'); // 旧回复已流到（切会话前最后一帧）
    a.send('旧会话排队问'); // sending 中进队列
    a.rebuildFromHistory(
      [
        { role: 'user', content: '新会话首问' },
        { role: 'assistant', content: '新会话答' }
      ],
      'newthread-abcdef123456'
    );
    // 切换即收敛：队列清空、sending 立即复位（abort settle 可能滞后数秒）
    expect(a.state.queue).toHaveLength(0);
    expect(a.state.sending).toBe(false);
    expect(a.historyRef).toHaveLength(2);
    finishTurn(); // 旧回合 abort 退避后才 settle 的迟到收尾
    await Promise.resolve();
    await Promise.resolve();
    // 旧回复不落账新 history；排队消息不以新 thread 补发；sending 不被翻动
    expect(a.historyRef).toHaveLength(2);
    expect(a.historyRef.every((m) => m.content.startsWith('新会话'))).toBe(true);
    expect(a.state.sending).toBe(false);
  });
});

describe('S-23 帧压测门槛（3000+ 帧放行路径）', () => {
  it('TEXT 3000 delta + REASONING 3000 delta 洪流：buffer 不丢字、合帧追赶收敛', async () => {
    const { a, drain } = setup();
    a.state.gateDone = true;
    a.send('压测问题');
    await Promise.resolve();
    const turn = a.cur()!;
    const t0 = Date.now();
    for (let i = 0; i < 3000; i++) {
      liveHandlers!.onTextDelta?.('m1', '字帧' + i + '；');
    }
    for (let i = 0; i < 3000; i++) {
      liveHandlers!.onReasoningDelta?.('m0', '思' + i);
    }
    const ingestMs = Date.now() - t0;
    liveHandlers!.onTextEnd?.('m1');
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    const t1 = Date.now();
    drain();
    const convergeMs = Date.now() - t1;
    // 6000 帧全量入 buffer（不丢字），追赶渲染收敛
    expect(turn.bufText.startsWith('字帧0；')).toBe(true);
    expect(turn.bufText.endsWith('字帧2999；')).toBe(true);
    expect(turn.bufText.length).toBeGreaterThan(19000);
    expect(turn.thinkText.endsWith('思2999')).toBe(true);
    expect(turn.thinkText.length).toBeGreaterThan(13000);
    expect(turn.shownLen).toBe(turn.bufText.length);
    expect(turn.answerHtml.length).toBeGreaterThan(0);
    // 粗门槛：摄入与收敛各 <5s（node 环境 CI 余量；真实浏览器 rAF 60ms/帧由合帧比例保证）
    expect(ingestMs).toBeLessThan(5000);
    expect(convergeMs).toBeLessThan(5000);
  });
});

describe('E5 会话重建', () => {
  it('rebuildFromHistory：user/assistant 交替回放只读回合+history 镜像同步', () => {
    const { a } = setup();
    a.rebuildFromHistory([
      { role: 'user', content: '我有哪些待办？' },
      { role: 'assistant', content: '你有 **3 条**待办' },
      { role: 'user', content: '第二个是什么？' },
      { role: 'assistant', content: '是一条**逾期**任务' }
    ]);
    expect(a.state.turns).toHaveLength(2);
    const t1 = a.state.turns[0];
    expect(t1.question).toBe('我有哪些待办？');
    expect(t1.answerHtml).toContain('<strong>3 条</strong>');
    expect(t1.phase).toBe('done');
    expect(t1.shownLen).toBe(t1.bufText.length);
    expect(a.state.turns[1].bufText).toBe('是一条**逾期**任务');
    // history 镜像=4 条（下轮 AGUI 全量重传底账）
    expect(a.historyRef).toHaveLength(4);
    expect(a.historyRef[0].role).toBe('user');
    expect(a.historyRef[3].content).toBe('是一条**逾期**任务');
    // 重建后：新手毕业+须知门视为已同意
    expect(a.state.newbieUse).toBe(2);
    expect(a.state.gateDone).toBe(true);
    // 重建后续问：历史追加重传
    a.state.gateDone = true;
    a.send('第三个呢？');
    expect(a.historyRef).toHaveLength(5);
  });

  it('批J-4：assistant 消息带 toolSteps 时恢复回合步骤与调用计数（stat 不再「0/1 未调用」）', () => {
    const { a } = setup();
    a.rebuildFromHistory([
      { role: 'user', content: '谁还没交？' },
      {
        role: 'assistant',
        content: '未交 3 人',
        toolSteps: JSON.stringify([
          { id: 'call-1', name: 'query_my_missions', argsText: '{"status":"PENDING_COMPLETE"}', brief: '{"total":2', status: 'ok' },
          { id: 'call-2', name: 'query_unsubmitted', argsText: '{"mission_id":"1506"}', brief: '{"error":"TIMEOUT"', status: 'err' }
        ])
      }
    ]);
    const t = a.state.turns[0];
    expect(t.steps).toHaveLength(2);
    expect(t.toolCalls).toBe(2);
    expect(t.steps[0].verb).toBe('查任务');
    expect(t.steps[0].status).toBe('ok');
    expect(t.steps[1].name).toBe('query_unsubmitted');
    expect(t.steps[1].status).toBe('err');
    // 非法 JSON / 缺 name 的行静默跳过，不炸回放
    a.rebuildFromHistory([
      { role: 'user', content: 'x' },
      { role: 'assistant', content: 'y', toolSteps: 'not-json' }
    ]);
    expect(a.state.turns[0].steps).toHaveLength(0);
    expect(a.state.turns[0].toolCalls).toBe(0);
  });

  it('Q5-A：toolSteps 对象形态恢复思考段（seq 交错+总耗时=各段和）；旧纯数组不带思考段', () => {
    const { a } = setup();
    a.rebuildFromHistory([
      { role: 'user', content: '查一下逾期' },
      {
        role: 'assistant',
        content: '逾期 2 条',
        toolSteps: JSON.stringify({
          steps: [{ id: 'call-1', name: 'query_overdue', argsText: '', brief: 'ok', status: 'ok', seq: 1 }],
          thinkSegs: [
            { id: 'rm-1', text: '先查逾期名单', startMs: 1000, durMs: 3000, seq: 0 },
            { id: 'rm-2', text: '再交叉核对截止时间', startMs: 5000, durMs: 2000, seq: 2 }
          ]
        })
      }
    ]);
    const t = a.state.turns[0];
    expect(t.thinkSegs).toHaveLength(2);
    expect(t.thinkSegs[0].text).toBe('先查逾期名单');
    expect(t.thinkSegs[0].seq).toBe(0);
    expect(t.steps[0].seq).toBe(1);
    expect(t.thinkSegs[1].seq).toBe(2);
    expect(t.thinkDurMs).toBe(5000);
    // 旧纯数组形态：steps 恢复、思考段恒空（零迁移兼容）
    a.rebuildFromHistory([
      { role: 'user', content: 'x' },
      { role: 'assistant', content: 'y', toolSteps: JSON.stringify([{ id: 'c1', name: 'n', argsText: '', brief: '', status: 'ok' }]) }
    ]);
    expect(a.state.turns[0].steps).toHaveLength(1);
    expect(a.state.turns[0].thinkSegs).toHaveLength(0);
    expect(a.state.turns[0].thinkDurMs).toBe(0);
  });

  it('newSession 逃生口：turns 清空且 history 镜像同步清空（旧指令带入新 thread=幽灵重放，E2E 实证）', () => {
    const { a } = setup();
    a.rebuildFromHistory([
      { role: 'user', content: '请调用 act_remind 催办 1506' },
      { role: 'assistant', content: '已安排' }
    ]);
    expect(a.historyRef).toHaveLength(2);
    const oldThread = a.state.threadId;
    a.newSession();
    expect(a.state.turns).toHaveLength(0);
    expect(a.historyRef).toHaveLength(0);
    expect(a.state.threadId).not.toBe(oldThread);
    // 新会话续问：history 只含本轮 user 消息，不带旧会话指令
    a.state.gateDone = true;
    a.send('你好');
    expect(a.historyRef).toHaveLength(1);
    expect(a.historyRef[0].content).toBe('你好');
  });

  describe('HITL 取消 resume 契约（E2E 实证回归锁）', () => {
    it('取消也发 cancelled resume 清服务端 interrupt（纯本地收卡=下轮全拒）', async () => {
      const { a } = setup();
      a.state.gateDone = true;
      a.send('催一下');
      await Promise.resolve();
      liveHandlers!.onFinished?.({
        type: 'interrupt',
        interrupts: [{ id: 'th-1:tc1', toolCallId: 'tc1', message: '确认催办' }]
      });
      finishTurn();
      await vi.waitFor(() => expect(a.state.sending).toBe(false));
      a.resolveHitl(false);
      await Promise.resolve();
      expect(lastInput?.resume).toEqual([{ interruptId: 'th-1:tc1', status: 'cancelled' }]);
      // 服务端确认取消（模型简短回复）
      liveHandlers!.onFinished?.(undefined);
      finishTurn();
      await vi.waitFor(() => expect(a.state.sending).toBe(false));
      // 原等待回合（倒数第二个）的确认卡收敛为「已取消」
      const waitingTurn = a.state.turns[a.state.turns.length - 2];
      expect(waitingTurn.hitl?.cardState).toBe('done');
      expect(waitingTurn.hitl?.cancelled).toBe(true);
    });

    it('确认发 resolved resume + 新回合渲染', async () => {
      const { a } = setup();
      a.state.gateDone = true;
      a.send('催一下');
      await Promise.resolve();
      liveHandlers!.onFinished?.({
        type: 'interrupt',
        interrupts: [{ id: 'th-2:tc2', toolCallId: 'tc2', message: '确认催办' }]
      });
      finishTurn();
      await vi.waitFor(() => expect(a.state.sending).toBe(false));
      a.resolveHitl(true);
      await Promise.resolve();
      expect(lastInput?.resume).toEqual([{ interruptId: 'th-2:tc2', status: 'resolved', payload: { approved: true } }]);
      expect(a.state.turns[a.state.turns.length - 1].question).toContain('（确认执行）');
      liveHandlers!.onFinished?.(undefined);
      finishTurn();
      await vi.waitFor(() => expect(a.state.sending).toBe(false));
    });
  });
});

describe('三档自主权（批I-2，原型 autoPop：always/once/auto）', () => {
  function interruptRemind() {
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [{ id: 'th-1:call_00_abc', toolCallId: 'call_00_abc', reason: 'act_remind', message: '将给 12 人发催办' }]
    });
  }

  /** 批W2：非 act_ 的 ASK 工具帧（真实 metadata 形态，reason=tool_call）——自主权
   *  机器只对这类工具生效（act_* 写操作 SPEC §3.1' 一律强制确认，见下方专项用例） */
  function interruptAskTool(toolName: string, callId: string) {
    liveHandlers!.onFinished?.({
      type: 'interrupt',
      interrupts: [
        {
          id: `th-1:${callId}`,
          toolCallId: callId,
          reason: 'tool_call',
          message: '确认执行',
          metadata: { toolName, toolInput: {} }
        }
      ]
    });
  }

  it('档2 auto：非 act 工具 interrupt 到达即自动确认执行（resume 带 approved payload+toast 提示）', async () => {
    const { a, toasts } = setup();
    a.state.gateDone = true;
    a.state.autoLevel = 'auto';
    a.send('确认导出这份报表');
    await Promise.resolve();
    interruptAskTool('confirm_export', 'call_00_abc');
    finishTurn();
    // runTurn 尾部检测 autoOk → 自动 resolveHitl(true)：sending 回升（resume 轮启动）
    await vi.waitFor(() => expect(a.state.turns[a.state.turns.length - 1].question).toContain('（确认执行）'));
    expect(lastInput?.resume).toEqual([{ interruptId: 'th-1:call_00_abc', status: 'resolved', payload: { approved: true } }]);
    expect(toasts.some((t) => t.includes('全自动'))).toBe(true);
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
  });

  it('档1 once：同类确认过一次后，下一次 interrupt 自动放行（toolName=稳定同类键，批W2）', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.state.autoLevel = 'once';
    a.send('确认导出这份报表');
    await Promise.resolve();
    interruptAskTool('confirm_export', 'call_00_x1');
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    // 档1 下不勾 remember 直接确认，也算「同类问过一次」→ 入 autoApproved（键=工具名）
    void a.resolveHitl(true, false);
    await Promise.resolve();
    expect(a.state.autoApproved).toContain('confirm_export');
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));

    // 第二轮同工具 interrupt（新 callId）→ 按工具名匹配自动确认
    a.send('再导出一次');
    await Promise.resolve();
    interruptAskTool('confirm_export', 'call_00_x2');
    finishTurn();
    await vi.waitFor(() => expect(lastInput?.resume).toEqual([{ interruptId: 'th-1:call_00_x2', status: 'resolved', payload: { approved: true } }]));
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
  });

  it('档0 勾「本会话内不再逐次确认」：只入 autoApproved 不升全局档，下一轮自动放行（业务批7 Q14：勾选不再改写跨会话偏好）', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('确认导出这份报表');
    await Promise.resolve();
    interruptAskTool('confirm_export', 'call_00_y1');
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    void a.resolveHitl(true, true);
    await Promise.resolve();
    expect(a.state.autoApproved).toContain('confirm_export');
    // k3 P2-3：勾选承诺「本会话内」——autoLevel 保持 always（升档+persist 属越权副作用，已摘除）
    expect(a.state.autoLevel).toBe('always');
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));

    a.send('再导出一次');
    await Promise.resolve();
    interruptAskTool('confirm_export', 'call_00_y2');
    finishTurn();
    await vi.waitFor(() => expect(lastInput?.resume).toEqual([{ interruptId: 'th-1:call_00_y2', status: 'resolved', payload: { approved: true } }]));
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
  });

  it("批W2 SPEC §3.1'：act_* 写操作 auto 档不自动放行——卡停 pending，不入 autoApproved（数据层二道闸）", async () => {
    const { a, toasts } = setup();
    a.state.gateDone = true;
    a.state.autoLevel = 'auto';
    a.send('帮我通过这条提交');
    await Promise.resolve();
    // metadata 形态（真实帧）：toolName=act_audit
    interruptAskTool('act_audit', 'call_00_aud');
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    await new Promise((r) => setTimeout(r, 30));
    expect(a.cur()!.hitl?.cardState).toBe('pending');
    expect(lastInput?.resume).toBeUndefined();
    expect(toasts.some((t) => t.includes('全自动'))).toBe(false);
    // 手动确认（带 remember=true 模拟绕过卡面）：不入 autoApproved，resume 正常带 approved
    void a.resolveHitl(true, true);
    await Promise.resolve();
    expect(a.state.autoApproved).not.toContain('act_audit');
    expect(lastInput?.resume).toEqual([{ interruptId: 'th-1:call_00_aud', status: 'resolved', payload: { approved: true } }]);
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
  });

  it("批W2 SPEC §3.1'：旧形态帧（reason=act_remind，无 metadata）同样不自动放行", async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.state.autoLevel = 'auto';
    a.send('催一下 022 班');
    await Promise.resolve();
    interruptRemind();
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    await new Promise((r) => setTimeout(r, 30));
    expect(a.cur()!.hitl?.cardState).toBe('pending');
    expect(lastInput?.resume).toBeUndefined();
  });

  it("批F2 SPEC §3.1'：act_create_mission（表单卡）auto 档同样不自动放行", async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.state.autoLevel = 'auto';
    a.send('帮我建个任务');
    await Promise.resolve();
    interruptAskTool('act_create_mission', 'call_00_cre');
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    await new Promise((r) => setTimeout(r, 30));
    expect(a.cur()!.hitl?.cardState).toBe('pending');
    expect(lastInput?.resume).toBeUndefined();
    expect(a.state.autoApproved).not.toContain('act_create_mission');
  });

  it('默认 always 且未 remember：interrupt 停在确认卡不自动执行', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('催一下 022 班');
    await Promise.resolve();
    interruptRemind();
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    await new Promise((r) => setTimeout(r, 30));
    expect(a.cur()!.phase).toBe('waiting');
    expect(a.cur()!.hitl?.cardState).toBe('pending');
  });

  it('newSession 清 autoApproved 但不重置 autoLevel（全局偏好）；setAutoLevel 切档即清积累', () => {
    const { a } = setup();
    a.state.autoApproved.push('act_remind');
    a.state.autoLevel = 'auto';
    a.newSession();
    expect(a.state.autoApproved).toEqual([]);
    expect(a.state.autoLevel).toBe('auto'); // 原型口径：自主权跨会话保留
    a.state.autoApproved.push('act_remind');
    a.setAutoLevel('always');
    expect(a.state.autoLevel).toBe('always');
    expect(a.state.autoApproved).toEqual([]); // 切档即重置（autoDid=false 同义）
  });
});

describe('HITL 跨 run 步骤归属（批J 三审补测）', () => {
  it('resume 轮的 onToolResult 回填被打断的原回合步骤', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('查未交名单');
    await Promise.resolve();
    const turn1 = a.cur()!;
    liveHandlers!.onToolStart?.('tcH', 'query_unsubmitted');
    expect(turn1.steps).toHaveLength(1);
    // 中断轮结束（确认卡待决，步骤无结果）
    liveHandlers!.onFinished?.(undefined);
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    // resume 轮：新回合（自身无步骤）
    a.send('确认执行');
    await Promise.resolve();
    const turn2 = a.cur()!;
    expect(turn2).not.toBe(turn1);
    expect(turn2.steps).toHaveLength(0);
    // 结果事件的 toolCallId 属于被打断的原回合——必须跨回合回填而非丢弃
    liveHandlers!.onToolResult?.('tcH', '{"total":145,"rows":[]}');
    expect(turn1.steps[0].status).toBe('ok');
    expect(turn1.steps[0].resultFull).toContain('"total":145');
    expect(turn2.steps).toHaveLength(0); // 不得错挂到 resume 回合
  });
});

describe('TODO 清单（ZCode 对齐 Q2；k3 P2-5 补测）', () => {
  it('onCustom todo 整表替换：白名单归一 + 缺字段过滤 + 20 条截断', async () => {
    const { a } = setup();
    a.state.gateDone = true;
    a.send('分三步做');
    await Promise.resolve();
    liveHandlers!.onCustom?.('todo', {
      items: [
        { content: '查名单', status: 'in_progress', priority: 'high' },
        { content: '汇总', status: 'bogus', priority: 'nope' },
        { status: 'pending', priority: 'low' },
        42
      ]
    });
    expect(a.state.todoItems).toHaveLength(2);
    expect(a.state.todoItems[0]).toMatchObject({ content: '查名单', status: 'in_progress', priority: 'high' });
    expect(a.state.todoItems[1]).toMatchObject({ content: '汇总', status: 'pending', priority: 'medium' });
    // 二帧整表替换：25 条 → 只留 20（后端 MAX_ITEMS 同上限，双保险）
    const many = Array.from({ length: 25 }, (_, i) => ({ content: '第' + i + '步', status: 'pending', priority: 'low' }));
    liveHandlers!.onCustom?.('todo', { items: many });
    expect(a.state.todoItems).toHaveLength(20);
    expect(a.state.todoItems[0].content).toBe('第0步');
    // 非数组载荷：不炸不清
    liveHandlers!.onCustom?.('todo', { items: 'not-array' });
    expect(a.state.todoItems).toHaveLength(20);
    // 终态后（sending=false）残余帧不写会话级槽（k3 P1-1 守卫）
    finishTurn();
    await vi.waitFor(() => expect(a.state.sending).toBe(false));
    liveHandlers!.onCustom?.('todo', { items: [{ content: '迟到的旧帧', status: 'pending', priority: 'low' }] });
    expect(a.state.todoItems).toHaveLength(20);
  });

  it('loadTodo 解包 res.data（request 整包 body 取法）', async () => {
    const { a } = setup();
    todoResponder = () => ({
      code: 200,
      msg: '操作成功',
      data: [
        { content: '查未交名单', status: 'completed', priority: 'high' },
        { content: '给催办建议', status: 'pending', priority: 'medium' }
      ]
    });
    await a.loadTodo('th-x');
    expect(a.state.todoItems).toHaveLength(2);
    expect(a.state.todoItems[0]).toMatchObject({ content: '查未交名单', status: 'completed' });
  });

  it('loadTodo 代数守卫：会话已切换的晚归响应不写（k3 P1-1）', async () => {
    const { a } = setup();
    let release!: (v: unknown) => void;
    todoResponder = () => new Promise((r) => (release = r));
    const p = a.loadTodo('th-old');
    a.newSession(); // 切会话：convGen+1 且 todoItems 已清
    expect(a.state.todoItems).toHaveLength(0);
    release({ code: 200, data: [{ content: '旧会话清单', status: 'pending', priority: 'low' }] });
    await p;
    expect(a.state.todoItems).toHaveLength(0);
  });
});

describe('追问拉取（稳流① Q8①：90s 窗口 + 聚焦/回页补拉）', () => {
  it('focus 事件在轮询间隔内触发 → 立即补拉写入，不等下一轮 attempt', async () => {
    vi.useFakeTimers();
    // node 的 globalThis 非 EventTarget（生产代码的 addEventListener 守卫正是为此）——
    // 挂一个事件总线替身驱动 focus/visibilitychange 路径，测毕还原
    const bus = new EventTarget();
    const g = globalThis as unknown as Record<string, unknown>;
    const savedAdd = g.addEventListener;
    const savedRemove = g.removeEventListener;
    const savedDispatch = g.dispatchEvent;
    g.addEventListener = bus.addEventListener.bind(bus);
    g.removeEventListener = bus.removeEventListener.bind(bus);
    g.dispatchEvent = bus.dispatchEvent.bind(bus);
    try {
      const { a } = setup();
      a.state.gateDone = true;
      // 场景=侧车已写完（库里已有数据），轮询 3.5s 间隔还没到点——用户回页面立刻可见
      let calls = 0;
      followupResponder = () => {
        calls += 1;
        return { code: 200, msg: 'ok', data: { followups: ['查未交名单汇总', '怎么催信息部', '对比上周进度'] } };
      };
      a.send('查一下进度');
      await Promise.resolve();
      const turn = a.cur()!;
      resolveDone?.('finished');
      await vi.waitFor(() => expect(turn.phase).toBe('done'));
      // attempt#1 的 3.5s 间隔只走过 1s——轮询还没到点，用户回到页面触发 focus
      vi.advanceTimersByTime(1_000);
      expect(turn.followups).toBeUndefined();
      globalThis.dispatchEvent(new Event('focus'));
      await vi.waitFor(() => expect(turn.followups).toHaveLength(3));
      expect(turn.followups).toEqual(['查未交名单汇总', '怎么催信息部', '对比上周进度']);
      expect(calls).toBe(1);
      // 到手后监听自摘：再派 focus 不再发请求
      globalThis.dispatchEvent(new Event('focus'));
      await Promise.resolve();
      expect(calls).toBe(1);
    } finally {
      if (savedAdd === undefined) {
        delete g.addEventListener;
      } else {
        g.addEventListener = savedAdd;
      }
      if (savedRemove === undefined) {
        delete g.removeEventListener;
      } else {
        g.removeEventListener = savedRemove;
      }
      if (savedDispatch === undefined) {
        delete g.dispatchEvent;
      } else {
        g.dispatchEvent = savedDispatch;
      }
      vi.useRealTimers();
    }
  });
});
