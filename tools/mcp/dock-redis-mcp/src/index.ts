#!/usr/bin/env node
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { getRedis, closeAll } from './redis.js';

const server = new McpServer({
  name: 'dock-redis',
  version: '1.0.0',
});

// ------------------------------------------------------------------
// redis_command: 执行任意 Redis 命令(调试队列/key/pubsub 等)
// ------------------------------------------------------------------
server.tool(
  'redis_command',
  '对已配置的 Redis 执行【单条】命令(全权限)。'
  + '\n连接由环境变量 REDIS_HOST/REDIS_PORT/REDIS_DB/REDIS_PASSWORD 决定'
  + '(缺省 127.0.0.1:6379 db=0 无密码)。'
  + '\n用 args 传命令参数数组,如 ["LRANGE","my:queue","0","-1"]。'
  + '\n\n⚠️ 安全:FLUSHDB/DEL 不可逆,操作前务必确认。',
  {
    args: z.array(z.string()).min(1).describe('Redis 命令及参数数组,如 ["GET","mykey"] 或 ["LRANGE","list","0","-1"]'),
    db: z.number().int().optional().describe('Redis db 索引,缺省走环境变量 REDIS_DB(默认 0)'),
  },
  {
    readOnlyHint: false,
    destructiveHint: true,
    idempotentHint: false,
    openWorldHint: true,
  },
  async (req) => {
    const redis = getRedis(req.db);
    try {
      const result = await (redis.call as any)(...req.args);
      const text = formatResult(result);
      return { content: [{ type: 'text', text }] };
    } catch (e: any) {
      return { content: [{ type: 'text', text: `ERROR: ${e.message}` }], isError: true };
    }
  },
);

// ------------------------------------------------------------------
// redis_type: 查看 key 的类型 + 长度(快速判断队列/列表/哈希状态)
// ------------------------------------------------------------------
server.tool(
  'redis_type',
  '只读:查看 key 的数据类型 + 大小(LLEN/LLEN/SCARD/HLEN/ZCARD/STRLEN)。'
  + '\n用于快速判断队列是否有积压消息。',
  {
    key: z.string().min(1).describe('Redis key 名'),
    db: z.number().int().optional().describe('Redis db 索引,缺省走环境变量 REDIS_DB(默认 0)'),
  },
  { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  async (req) => {
    const redis = getRedis(req.db);
    const type = await redis.type(req.key);
    let size: number | string = 'N/A';
    if (type === 'list') size = await redis.llen(req.key);
    else if (type === 'set') size = await redis.scard(req.key);
    else if (type === 'hash') size = await redis.hlen(req.key);
    else if (type === 'zset') size = await redis.zcard(req.key);
    else if (type === 'string') size = await redis.strlen(req.key);
    const ttl = await redis.ttl(req.key);
    return { content: [{ type: 'text', text: `key=${req.key}\ntype=${type}\nsize=${size}\nttl=${ttl >= 0 ? ttl + 's' : '永生/不存在'}` }] };
  },
);

// ------------------------------------------------------------------
// redis_keys: 按模式列出 key
// ------------------------------------------------------------------
server.tool(
  'redis_keys',
  '只读:按模式匹配列出 key(默认 *,即全部)。'
  + '\n大库建议带具体前缀(如 "myapp:*")避免全库扫描。',
  {
    pattern: z.string().optional().describe('key 匹配模式,默认 *(全部);建议带前缀如 "myapp:*"'),
    db: z.number().int().optional().describe('Redis db 索引,缺省走环境变量 REDIS_DB(默认 0)'),
  },
  { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  async (req) => {
    const redis = getRedis(req.db);
    const keys = await redis.keys(req.pattern || '*');
    keys.sort();
    return { content: [{ type: 'text', text: `匹配 ${keys.length} 个 key:\n${keys.join('\n')}` }] };
  },
);

// ------------------------------------------------------------------
// redis_get_json: 取 string key 的值并尝试 JSON 美化
// ------------------------------------------------------------------
server.tool(
  'redis_get_json',
  '只读:取 string 类型 key 的值,尝试 JSON 美化输出。'
  + '\n适合查看缓存里存的 JSON 值。',
  {
    key: z.string().min(1).describe('Redis key 名'),
    db: z.number().int().optional().describe('Redis db 索引,缺省 4'),
  },
  { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  async (req) => {
    const redis = getRedis(req.db);
    const val = await redis.get(req.key);
    if (val === null) return { content: [{ type: 'text', text: '(nil)' }] };
    let pretty = val;
    try { pretty = JSON.stringify(JSON.parse(val), null, 2); } catch { /* 非 JSON 原样 */ }
    return { content: [{ type: 'text', text: pretty }] };
  },
);

/** 递归格式化 Redis 返回值为可读文本 */
function formatResult(result: unknown): string {
  if (result === null || result === undefined) return '(nil)';
  if (typeof result === 'string') {
    try { return JSON.stringify(JSON.parse(result), null, 2); } catch { return result; }
  }
  if (Buffer.isBuffer(result)) return result.toString('utf8');
  if (Array.isArray(result)) {
    return result.map((v, i) => `${i + 1}) ${formatResult(v)}`).join('\n');
  }
  return JSON.stringify(result, null, 2);
}

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

process.on('SIGINT', async () => { await closeAll(); process.exit(0); });
process.on('SIGTERM', async () => { await closeAll(); process.exit(0); });

main().catch((e) => {
  console.error('dock-redis-mcp fatal:', e);
  process.exit(1);
});
