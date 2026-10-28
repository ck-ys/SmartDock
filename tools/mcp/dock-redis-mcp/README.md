# dock-redis-mcp

SmartDock 的 Redis 调试 MCP server（stdio）。

让 AI 助手（ZCode 等）直接查看/操作你的 Redis：调试队列、看缓存内容、查 key。

## 工具清单

| 工具 | 权限 | 用途 |
|---|---|---|
| `redis_command` | 全权限 | 执行单条 Redis 命令（`args` 传参数数组，如 `["GET","mykey"]`） |
| `redis_type` | 只读 | 查 key 的类型 + 大小（LLEN/SCARD/HLEN/ZCARD/STRLEN）+ TTL |
| `redis_keys` | 只读 | 按模式列出 key（默认 `*`；大库建议带前缀避免全库扫描） |
| `redis_get_json` | 只读 | 取 string key 的值并尝试 JSON 美化 |

## 构建

`dist/` 不入 git，clone 后先构建一次（Node >= 20.19）：

```bash
cd tools/mcp/dock-redis-mcp
npm install
npm run build
```

## 接入 ZCode

> 连接信息（地址/口令）只写进 gitignored 的 `.zcode/config.json`，绝不写进任何 git 追踪文件。

在仓库根的 `.zcode/config.json` 里（与 dock-pg 同一个 `mcp.servers` 对象）加：

```json
"dock-redis": {
  "type": "stdio",
  "command": "node",
  "args": ["<仓库绝对路径>\\tools\\mcp\\dock-redis-mcp\\dist\\index.js"],
  "env": {
    "REDIS_HOST": "<你的 Redis 地址>",
    "REDIS_PORT": "6379",
    "REDIS_DB": "0"
  }
}
```

改完**重启 ZCode 会话**，新会话自动连接，出现 `mcp__dock-redis__redis_command` 等四个工具。

## 环境变量

| 变量 | 缺省 | 说明 |
|---|---|---|
| `REDIS_HOST` | `127.0.0.1` | 主机 |
| `REDIS_PORT` | `6379` | 端口 |
| `REDIS_DB` | `0` | 默认 db 索引（调用时可传 `db` 参数临时切） |
| `REDIS_PASSWORD` | 空 | 有密码时填 |
