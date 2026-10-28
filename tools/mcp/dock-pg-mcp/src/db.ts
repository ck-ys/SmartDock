import { Pool, QueryResult, QueryResultRow } from 'pg';

/**
 * 连接池缓存:按数据库名缓存单例 Pool,避免每次工具调用重建连接。
 *
 * 连接参数全部走 PG 标准环境变量(PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD),
 * 由 MCP 客户端在 server 配置的 env 字段注入,代码里不硬编码任何主机/密码。
 *
 * 缺省指向本机默认实例: 127.0.0.1:5432,库名 postgres;实际连接以部署方注入的 env 为准。
 */
const pools = new Map<string, Pool>();

const STATEMENT_TIMEOUT_MS = 30_000; // 30s,防手滑写错 SQL 卡住连接

function baseConfig(): {
  host: string;
  port: number;
  user: string;
  password: string;
  database: string;
} {
  return {
    host: process.env.PGHOST || '127.0.0.1',
    port: Number(process.env.PGPORT || 5432),
    user: process.env.PGUSER || 'postgres',
    password: process.env.PGPASSWORD || '',
    database: process.env.PGDATABASE || 'postgres',
  };
}

/** 取(或创建)指向指定库的连接池;database 缺省走 PGDATABASE。 */
export function getPool(database?: string): Pool {
  const cfg = baseConfig();
  const db = database || cfg.database;
  let pool = pools.get(db);
  if (!pool) {
    pool = new Pool({ ...cfg, database: db, max: 4, idleTimeoutMillis: 30_000 });
    // 首次连接时下发 statement_timeout,避免慢查询/死锁卡住整个 server
    pool.on('connect', (client) => {
      client.query(`SET statement_timeout = ${STATEMENT_TIMEOUT_MS}`).catch(() => {
        /* 忽略:超时设置失败不阻塞业务查询 */
      });
    });
    pools.set(db, pool);
  }
  return pool;
}

/** 统一查询入口:透传 pg 的 query,类型宽松化由调用方处理。 */
export async function runQuery<T extends QueryResultRow = QueryResultRow>(
  sql: string,
  params: unknown[] | undefined,
  database?: string,
): Promise<QueryResult<T>> {
  const pool = getPool(database);
  return pool.query<T>(sql, params as unknown[]) as Promise<QueryResult<T>>;
}

/** 进程退出时优雅关闭所有连接池。 */
export async function closeAllPools(): Promise<void> {
  const tasks = Array.from(pools.values()).map((p) => p.end().catch(() => {}));
  pools.clear();
  await Promise.all(tasks);
}
