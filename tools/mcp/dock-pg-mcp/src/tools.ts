import { runQuery } from './db.js';
import type { DatabaseError } from 'pg';

/** 工具返回的统一结构:走 structuredContent + text 双通道,客户端两种解析方式都能用。 */
export type ToolResult = {
  content: { type: 'text'; text: string }[];
  // 注意:structuredContent 必须是 object(带索引签名),不能用 unknown,
  // 否则 McpServer.tool 的返回类型签名会拒绝(SDK 要求 { [x: string]: unknown })。
  structuredContent?: Record<string, unknown>;
};

/** 把 PG 错误转成对 AI 友好的可操作信息,引导修正而非报完就断。 */
function describePgError(err: unknown): { code: string; message: string; detail?: string; hint?: string; where?: string } {
  const e = err as Partial<DatabaseError> & Error;
  return {
    code: (e as any).code || 'UNKNOWN',
    message: e.message || String(err),
    detail: (e as any).detail,
    hint: (e as any).hint,
    where: (e as any).where,
  };
}

/** 主力工具:执行任意单条 SQL(全权限,含 SELECT/DML/DDL)。 */
export async function pgQuery(args: {
  sql: string;
  params?: (string | number | boolean | null)[];
  database?: string;
}): Promise<ToolResult> {
  try {
    const res = await runQuery(args.sql, args.params || [], args.database);

    // 有字段描述符 → 结果集(SELECT / INSERT...RETURNING / WITH 等)
    if (res.fields && res.fields.length > 0) {
      const columns = res.fields.map((f) => f.name);
      const rows = res.rows;
      const payload = { columns, rows, rowCount: res.rowCount ?? rows.length };
      return {
        content: [{ type: 'text', text: JSON.stringify(payload) }],
        structuredContent: payload,
      };
    }

    // 无结果集(INSERT/UPDATE/DELETE 未带 RETURNING / DDL / SET 等)
    const payload = { command: res.command, rowCount: res.rowCount ?? 0 };
    return {
      content: [{ type: 'text', text: JSON.stringify(payload) }],
      structuredContent: payload,
    };
  } catch (err) {
    const info = describePgError(err);
    // 常见错误码给一句人话提示,帮 AI 自助修正
    const guidance: Record<string, string> = {
      '42P01': '表不存在 — 先用 pg_list_tables 查实际表名。',
      '42703': '列不存在 — 先用 pg_describe_table 查实际列名。',
      '42601': '语法错误 — 检查 SQL 拼写/引号/逗号。',
      '23505': '唯一约束冲突 — 该值已存在。',
      '23503': '外键约束冲突 — 引用的值在父表不存在。',
      '42P07': '表/对象已存在 — 如需重建请先 DROP。',
      '55006': '对象正在被使用 — 可能有活跃连接占用。',
    };
    const tip = guidance[info.code];
    const text = tip ? `${info.message} [${info.code}] — ${tip}` : JSON.stringify(info);
    return { content: [{ type: 'text', text }] };
  }
}

/** 只读元数据:列出某 schema 下的表/视图/物化视图 + 行数估算。 */
export async function pgListTables(args: {
  schema?: string;
  database?: string;
}): Promise<ToolResult> {
  const schema = args.schema || 'public';
  const sql = `
    SELECT n.nspname AS schema,
           c.relname AS name,
           CASE c.relkind
             WHEN 'r' THEN 'TABLE'
             WHEN 'v' THEN 'VIEW'
             WHEN 'm' THEN 'MATERIALIZED VIEW'
             WHEN 'p' THEN 'PARTITIONED TABLE'
             ELSE c.relkind::text
           END AS type,
           COALESCE(s.n_live_tup, 0) AS row_estimate
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_stat_user_tables s
           ON s.relid = c.oid AND s.schemaname = n.nspname
    WHERE n.nspname = $1
      AND c.relkind IN ('r','v','m','p')
      AND c.relpersistence <> 't'   -- 排除临时表
    ORDER BY c.relkind, c.relname`;
  const res = await runQuery(sql, [schema], args.database);
  const rows = res.rows as { schema: string; name: string; type: string; row_estimate: number }[];
  const payload = { schema, tables: rows };
  return {
    content: [{ type: 'text', text: JSON.stringify(payload) }],
    structuredContent: payload,
  };
}

/** 只读元数据:列出某表的列定义 + 注释。 */
export async function pgDescribeTable(args: {
  schema?: string;
  table: string;
  database?: string;
}): Promise<ToolResult> {
  const schema = args.schema || 'public';
  const sql = `
    SELECT c.column_name,
           c.data_type,
           c.udt_name AS pg_type,
           c.character_maximum_length AS max_length,
           c.numeric_precision AS num_precision,
           c.is_nullable,
           c.column_default,
           col_description((quote_ident($1) || '.' || quote_ident($2))::regclass, c.ordinal_position) AS comment
    FROM information_schema.columns c
    WHERE c.table_schema = $1
      AND c.table_name = $2
    ORDER BY c.ordinal_position`;
  const res = await runQuery(sql, [schema, args.table], args.database);
  const columns = res.rows as {
    column_name: string;
    data_type: string;
    pg_type: string;
    max_length: number | null;
    num_precision: number | null;
    is_nullable: 'YES' | 'NO';
    column_default: string | null;
    comment: string | null;
  }[];
  const payload = { schema, table: args.table, columns };
  return {
    content: [{ type: 'text', text: JSON.stringify(payload) }],
    structuredContent: payload,
  };
}
