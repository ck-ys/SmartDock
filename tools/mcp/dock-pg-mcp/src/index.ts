#!/usr/bin/env node
import './set-tz.js'; // 必须第一位: 进程时区钉死(见 set-tz.ts 注释)
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { pgQuery, pgListTables, pgDescribeTable } from './tools.js';
import { closeAllPools } from './db.js';

const server = new McpServer({
  name: 'dock-pg',
  version: '1.0.0',
});

// 单条 SQL 参数绑定的值类型;用 zod 的 union 表达
const sqlParam = z.union([z.string(), z.number(), z.boolean(), z.null()]);

server.tool(
  'pg_query',
  '对已配置的 PostgreSQL 库执行【单条】SQL,全权限(含 SELECT / INSERT / UPDATE / DELETE / DDL / TRUNCATE / DROP)。'
  + '\n\n连接的目标库由环境变量 PGDATABASE 决定(缺省 postgres);可用 database 参数临时切到同实例其它库。'
  + '\n参数绑定用 $1/$2/...,params 按序对应。每次只执行一条语句;多条请分多次调用。'
  + '\n\n⚠️ 安全:DROP/TRUNCATE/DELETE 不可逆,操作前务必与用户确认。建议破坏性操作先 SELECT 验证影响范围。',
  {
    sql: z.string().min(1).describe('单条 SQL 语句。参数占位符用 $1/$2/...'),
    params: z.array(sqlParam).optional().describe('按 $1,$2 顺序的参数值,与 SQL 里的占位符一一对应'),
    database: z.string().optional().describe('目标库名,缺省走环境变量 PGDATABASE'),
  },
  {
    readOnlyHint: false,
    destructiveHint: true,
    idempotentHint: false,
    openWorldHint: true,
  },
  async (args) => pgQuery(args),
);

server.tool(
  'pg_list_tables',
  '只读:列出某 schema(默认 public)下的所有表/视图/物化视图/分区表,含行数估算(来自 pg_stat)。'
  + '\n用于快速了解库里有哪些对象,先看结构再写查询。',
  {
    schema: z.string().optional().describe('schema 名,默认 public'),
    database: z.string().optional().describe('目标库名,缺省走环境变量 PGDATABASE'),
  },
  {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  async (args) => pgListTables(args),
);

server.tool(
  'pg_describe_table',
  '只读:列出某张表的完整列定义(列名/数据类型/PG 内部类型/长度/精度/可空/默认值/注释)。'
  + '\n写 SQL 前用它确认列名和类型,避免 42703(column does not exist)错误。',
  {
    table: z.string().min(1).describe('表名(不含 schema 前缀)'),
    schema: z.string().optional().describe('schema 名,默认 public'),
    database: z.string().optional().describe('目标库名,缺省走环境变量 PGDATABASE'),
  },
  {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  async (args) => pgDescribeTable(args),
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  // 优雅退出:关连接池,避免句柄泄漏告警
  const shutdown = async () => {
    await closeAllPools();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((err) => {
  console.error('[dock-pg-mcp] fatal:', err);
  process.exit(1);
});
