#!/usr/bin/env node
// SmartDock 批2 MCP 试点服务（只读天气查询，stdio 传输）
// 零 npm 依赖：手写 MCP stdio 协议（逐行 JSON-RPC 2.0），node >= 18 即可运行。
// 用途：验证助手经 AgentScope registerMcpClient 真实调通外部 MCP 工具的全链路。
// 验收演练：停掉本进程（或管理台停用）= 服务下线，助手对话不受影响。
// 协议版本回显客户端请求值（兼容 2024-11-05 / 2025-03-26 / 2025-06-18）。
import { createInterface } from 'node:readline';

const rl = createInterface({ input: process.stdin });
const send = (obj) => process.stdout.write(JSON.stringify(obj) + '\n');

// 试点模拟数据（只读、零外网依赖；真实接入换 streamableHttp 传输连远程服务）
const WEATHER = {
  北京: { temp: 18, cond: '晴', aqi: 42 },
  上海: { temp: 22, cond: '多云', aqi: 55 },
  深圳: { temp: 27, cond: '阵雨', aqi: 30 },
};

rl.on('line', (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  if (!msg || typeof msg !== 'object' || typeof msg.method !== 'string') return; // 畸形输入守卫
  const { id, method, params } = msg;
  if (method === 'initialize') {
    send({ jsonrpc: '2.0', id, result: {
      protocolVersion: params?.protocolVersion || '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'smartdock-weather-demo', version: '1.0.0' },
    } });
  } else if (method === 'notifications/initialized' || method.startsWith('notifications/')) {
    // 通知不回应
  } else if (method === 'ping') {
    send({ jsonrpc: '2.0', id, result: {} });
  } else if (method === 'tools/list') {
    send({ jsonrpc: '2.0', id, result: { tools: [{
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
    }] } });
  } else if (method === 'tools/call') {
    const name = params?.name;
    const city = params?.arguments?.city || '';
    if (name !== 'get_weather') {
      send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: `未知工具：${name}` }], isError: true } });
      return;
    }
    const w = WEATHER[city];
    const text = w
      ? `${city}今天${w.cond}，气温 ${w.temp}℃，空气质量指数 ${w.aqi}（试点模拟数据源）`
      : `暂无「${city}」的试点数据（内置城市：${Object.keys(WEATHER).join('、')}）`;
    send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text }], isError: false } });
  } else if (id !== undefined) {
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: `Method not found: ${method}` } });
  }
});
