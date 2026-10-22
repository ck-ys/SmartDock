# SmartDock Backend

Java 21 · Spring Boot 3.5 · Sa-Token · MyBatis-Plus · Redisson · PostgreSQL 17。

## 模块

| 模块 | 职责 |
|---|---|
| `ruoyi-admin` | 启动入口与运行配置 |
| `ruoyi-modules/ruoyi-dcs` | 微核心：任务 / 填报 / 审核 / 通知 / 统计 |
| `ruoyi-modules/ruoyi-profile-engine` | 画像引擎：事件源 + 维度评分 + 快照 |
| `ruoyi-modules/ruoyi-assistant` | AI 助手：SSE 对话 + 工具 SPI + 技能资产 |
| `ruoyi-modules/ruoyi-file-security` | 文件安全：上传守卫 + 审核队列 |
| `ruoyi-modules/ruoyi-risk` | 风控：事件 / 规则 / 白名单 / 行动 |
| `ruoyi-modules/ruoyi-ai-billing` | 计费：钱包 / 积分 / 模型目录 |

## 构建与测试

```bash
mvn package -DskipTests      # 产物 ruoyi-admin/target/ruoyi-admin.jar
mvn test -DskipTests=false   # 全量单测
```

数据库初始化见 [script/sql/baseline/README.md](script/sql/baseline/README.md)，
默认运行配置见 [ruoyi-admin/src/main/resources/application.yml](ruoyi-admin/src/main/resources/application.yml)。

MCP 外部工具演示服务（`mcp-demo/weather-server.mjs`，零依赖 node ≥ 18）的接入、
配置项与安全边界见仓库根 [docs/usage.md](../docs/usage.md) §5.1。

## 许可

基于 [RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus)（MIT）裁剪重整，
许可与署名见仓库根目录 [LICENSE](../LICENSE)、[NOTICE](../NOTICE) 与本目录 `LICENSE`。
