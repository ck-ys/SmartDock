import { Redis } from 'ioredis';

/**
 * Redis 连接缓存:按 db 索引缓存单例连接。
 *
 * 连接参数全部走环境变量 REDIS_HOST/REDIS_PORT/REDIS_DB/REDIS_PASSWORD,
 * 由 MCP 客户端在 server 配置的 env 字段注入。
 * 缺省指向本机默认实例: 127.0.0.1:6379 db=0 无密码;实际连接以部署方注入的 env 为准。
 */
const clients = new Map<number, Redis>();

function baseConfig(db?: number) {
  return {
    host: process.env.REDIS_HOST || '127.0.0.1',
    port: Number(process.env.REDIS_PORT || 16379),
    password: process.env.REDIS_PASSWORD || undefined,
    db: db ?? Number(process.env.REDIS_DB ?? 0),
    maxRetriesPerRequest: 3,
    enableReadyCheck: true,
    lazyConnect: false,
  };
}

export function getRedis(db?: number): Redis {
  const targetDb = db ?? Number(process.env.REDIS_DB ?? 0);
  let client = clients.get(targetDb);
  if (!client) {
    client = new Redis(baseConfig(targetDb));
    clients.set(targetDb, client);
  }
  return client;
}

export async function closeAll(): Promise<void> {
  await Promise.all([...clients.values()].map((c) => c.quit().catch(() => {})));
  clients.clear();
}
