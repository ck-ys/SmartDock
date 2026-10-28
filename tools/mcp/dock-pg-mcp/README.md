# dock-pg-mcp

SmartDock 的 PostgreSQL 查询 MCP server（stdio，全权限含 DDL）。

让 AI 助手（ZCode 等）直接查询/写入你的 PostgreSQL 库，替代每次手敲长 psql 命令。

## 工具清单

| 工具 | 权限 | 用途 |
|---|---|---|
| `pg_query` | 全权限 | 执行单条 SQL（SELECT/INSERT/UPDATE/DELETE/DDL/TRUNCATE/DROP），参数用 `$1/$2` 绑定 |
| `pg_list_tables` | 只读 | 列某 schema 下的表/视图/物化视图/分区表 + 行数估算 |
| `pg_describe_table` | 只读 | 列某表的完整列定义（含注释） |

安全设计：每连接 `statement_timeout=30s`（防慢 SQL 卡死）；PG 错误码翻成人话提示（如 42P01 表不存在→引导先列清单）；进程时区钉死 `Asia/Shanghai`（防 naive timestamp 按宿主时区偏移）。

## 构建

`dist/` 不入 git，clone 后先构建一次（Node >= 20.19）：

```bash
cd tools/mcp/dock-pg-mcp
npm install
npm run build
```

## 接入 ZCode

> 连接信息（地址/口令）只写进 gitignored 的 `.zcode/config.json`，绝不写进任何 git 追踪文件。

在仓库根新建 `.zcode/config.json`：

```json
{
  "mcp": {
    "servers": {
      "dock-pg": {
        "type": "stdio",
        "command": "node",
        "args": ["<仓库绝对路径>\\tools\\mcp\\dock-pg-mcp\\dist\\index.js"],
        "env": {
          "PGHOST": "<你的 PG 地址>",
          "PGPORT": "5432",
          "PGDATABASE": "<你的库名>",
          "PGUSER": "<用户名>",
          "PGPASSWORD": "<密码>"
        }
      }
    }
  }
}
```

改完**重启 ZCode 会话**，新会话自动连接，出现 `mcp__dock-pg__pg_query` 等三个工具。

## 环境变量

| 变量 | 缺省 | 说明 |
|---|---|---|
| `PGHOST` | `127.0.0.1` | 主机 |
| `PGPORT` | `5432` | 端口 |
| `PGDATABASE` | `postgres` | 默认库（调用时可传 `database` 参数临时切同实例其它库） |
| `PGUSER` / `PGPASSWORD` | `postgres` / 空 | 认证 |
