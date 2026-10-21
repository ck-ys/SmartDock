#!/usr/bin/env node
// SmartDock 批2 MCP 试点服务（只读天气查询，stdio 传输）
// 零 npm 依赖：手写 MCP stdio 协议（逐行 JSON-RPC 2.0），node >= 18 即可运行。
// 用途：验证助手经 AgentScope registerMcpClient 真实调通外部 MCP 工具的全链路。
// 验收演练：停掉本进程（或管理台停用）= 服务下线，助手对话不受影响。
// 协议版本回显客户端请求值（兼容 2024-11-05 / 2025-03-26 / 2025-06-18）。
// G3-A1（2026-10-21）：新增 ask_weather 工具——缺城市时向客户端发 elicitation/create
// 反问（用户在反问卡作答），验证「elicitation 与权限闸正交：readOnlyHint 只读放行，
// 反问发生在 tools/call 在飞期间」。客户端未声明 elicitation 能力则不反问直接人话返回。
import { createInterface } from 'node:readline';

const rl = createInterface({ input: process.stdin });
const send = (obj) => process.stdout.write(JSON.stringify(obj) + '\n');

// 客户端是否声明 elicitation 能力（initialize 时记录；不声明则 ask_weather 不反问）
let clientElicitation = false;

// 试点模拟数据（只读、零外网依赖；真实接入换 streamableHttp 传输连远程服务）
const WEATHER = {
  北京: { temp: 18, cond: '晴', aqi: 42 },
  上海: { temp: 22, cond: '多云', aqi: 55 },
  深圳: { temp: 27, cond: '阵雨', aqi: 30 },
};

// server→client 反问请求的等待表：请求 id → { resolve, timer }
const pendingElicits = new Map();
let elicitSeq = 0;

/** 发 elicitation/create 并等待客户端作答（超时随请求方 requestTimeout，这里 280s 兜底防悬挂） */
function elicit(message, requestedSchema) {
  return new Promise((resolve) => {
    const id = `elicit-${++elicitSeq}`;
    const timer = setTimeout(() => {
      pendingElicits.delete(id);
      resolve({ action: 'cancel', content: {} });
    }, 280_000);
    pendingElicits.set(id, {
      resolve: (result) => {
        clearTimeout(timer);
        resolve(result);
      },
    });
    send({ jsonrpc: '2.0', id, method: 'elicitation/create', params: { message, requestedSchema } });
  });
}

async function callAskWeather(params) {
  const city = params?.arguments?.city || '';
  const cities = Object.keys(WEATHER);
  // city 缺失或查不到 → 反问（仅对声明了 elicitation 能力的客户端）
  if (!WEATHER[city] && clientElicitation) {
    const answer = await elicit(
      city ? `试点数据里没有「${city}」，换一个内置城市查询吗？` : '请告诉我要查询哪个城市的天气？',
      {
        type: 'object',
        properties: {
          city: {
            type: 'string',
            title: '城市',
            description: '试点内置城市三选一',
            enum: cities,
          },
          note: {
            type: 'string',
            title: '备注（可选）',
            description: '想附带给工具的说明，可不填',
          },
        },
        required: ['city'],
      },
    );
    if (answer?.action === 'accept' && WEATHER[answer.content?.city]) {
      const w = WEATHER[answer.content.city];
      const note = answer.content?.note ? `（用户备注：${answer.content.note}）` : '';
      return `${answer.content.city}今天${w.cond}，气温 ${w.temp}℃，空气质量指数 ${w.aqi}（试点模拟数据源，经用户反问补充${note}）`;
    }
    if (answer?.action === 'decline') return '用户拒绝提供城市，本次查询未执行';
    return '用户关闭了反问，本次查询未执行';
  }
  const w = WEATHER[city];
  return w
    ? `${city}今天${w.cond}，气温 ${w.temp}℃，空气质量指数 ${w.aqi}（试点模拟数据源）`
    : clientElicitation
      ? '缺城市且未能完成反问，查询未执行'
      : `暂无「${city}」的试点数据（内置城市：${cities.join('、')}）`;
}

rl.on('line', (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  if (!msg || typeof msg !== 'object') return; // 畸形输入守卫
  // 客户端对反问请求的作答回包（jsonrpc response，id 命中等待表）
  if (typeof msg.method !== 'string' && msg.id !== undefined && pendingElicits.has(String(msg.id))) {
    pendingElicits.get(String(msg.id)).resolve(msg.result ?? { action: 'cancel', content: {} });
    pendingElicits.delete(String(msg.id));
    return;
  }
  if (typeof msg.method !== 'string') return;
  const { id, method, params } = msg;
  if (method === 'initialize') {
    clientElicitation = !!params?.capabilities?.elicitation;
    send({ jsonrpc: '2.0', id, result: {
      protocolVersion: params?.protocolVersion || '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'smartdock-weather-demo', version: '1.1.0' },
    } });
  } else if (method === 'notifications/initialized' || method.startsWith('notifications/')) {
    // 通知不回应
  } else if (method === 'ping') {
    send({ jsonrpc: '2.0', id, result: {} });
  } else if (method === 'tools/list') {
    send({ jsonrpc: '2.0', id, result: { tools: [
      {
        name: 'get_weather',
        description: '查询指定城市的天气（试点模拟数据源，只读）',
        // readOnlyHint 让 AgentScope 的 McpTool.checkPermissions 直接放行（写类工具才会走确认）；
        // 权限规则注入随会话状态持久化存在新旧态差异，注解是声明式的、每轮挂载都自带
        annotations: { readOnlyHint: true, title: '查天气' },
        inputSchema: {
          type: 'object',
          properties: { city: { type: 'string', description: '城市名，如 北京' } },
          required: ['city'],
        },
      },
      {
        name: 'ask_weather',
        description: '查询城市天气；缺城市或城市不在试点数据时，会向用户反问（弹出反问卡补充城市）—— elicitation 试点工具，只读',
        // 只读注解与反问正交：权限闸放行后，反问发生在本调用在飞期间（run 不中断）
        annotations: { readOnlyHint: true, title: '查天气（可反问）' },
        inputSchema: {
          type: 'object',
          properties: { city: { type: 'string', description: '城市名，可留空触发反问' } },
        },
      },
    ] } });
  } else if (method === 'tools/call') {
    const name = params?.name;
    if (name === 'get_weather') {
      const city = params?.arguments?.city || '';
      const w = WEATHER[city];
      const text = w
        ? `${city}今天${w.cond}，气温 ${w.temp}℃，空气质量指数 ${w.aqi}（试点模拟数据源）`
        : `暂无「${city}」的试点数据（内置城市：${Object.keys(WEATHER).join('、')}）`;
      send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text }], isError: false } });
    } else if (name === 'ask_weather') {
      // 反问期间请求在飞——async 处理，作答回包走同一 stdin 流
      callAskWeather(params)
        .then((text) => send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text }], isError: false } }))
        .catch((e) => send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: `反问处理异常：${e?.message ?? e}` }], isError: true } }));
    } else {
      send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: `未知工具：${name}` }], isError: true } });
    }
  } else if (id !== undefined) {
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: `Method not found: ${method}` } });
  }
});
