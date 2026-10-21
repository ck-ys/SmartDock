# AI 辅助开发环境建议（可选）

> 本文是给使用 Claude Code / ZCode / Cursor 等 AI 编程客户端的贡献者的**建议配置**，不是项目运行依赖。
> 不安装任何 MCP 或 skill 也可以正常开发和提交。

## 1. 推荐 MCP

| MCP | 用途 | 本项目注意事项 |
|---|---|---|
| PostgreSQL MCP | 查 schema、核对种子数据、验证统计口径、对账测试结果 | 只连本地 `smartdock` 库；用**只读账号**；禁止接入生产库 |
| Playwright MCP | 浏览器 E2E、双视角（管理员 / 普通用户）、截图与 SSE 流式验证 | 后端 8090、前端 5188；切账号先清 localStorage |
| Context7 / 文档 MCP | 查 AgentScope、Spring AI、Vue 等依赖的最新文档 | 版本以 `pom.xml` / `package.json` 为准，文档只作参考 |
| 视觉 MCP（可选） | 纯文本模型查看截图、做 UI 验收 | 只读本地截图；不要把带敏感数据的截图外传 |

MCP 配置放在 AI 客户端的本地配置里（如 `.mcp.json`），**不要提交**；连接串里的口令用环境变量或本地占位值。

## 2. 推荐 skills（按工作流顺序）

| skill | 什么时候用 | 对应项目纪律 |
|---|---|---|
| `brainstorming` | 需求不明确、有多种做法 | 先列选项再动手，别偷偷选一个 |
| `writing-plans` / `executing-plans` | 多步骤任务 | 先写「步骤 → 验证方式」，再按计划执行 |
| `grill-me` / `grilling` | 方案看起来太顺时 | 动手前质询假设，主动找反例 |
| `test-driven-development` | 修 bug / 加功能 | 先写失败测试，再改到通过 |
| `systematic-debugging` | 缺陷定位 | 代码 / git log / 存量数据 / 运行实证 四路对质 |
| `verification-before-completion` | 收口前 | 编译、单测、构建、活体验证全过再报完成 |
| `requesting-code-review` / `subagent-driven-development` | 批次收口 | 多席审查；结论必须交叉实证 |

**优先级**：如果只装三个，选 `writing-plans` + `test-driven-development` + `verification-before-completion`。

skills 与 `AGENTS.md` 冲突时，以 `AGENTS.md` 为准。

## 3. 最小可用组合

- 只做后端 / 数据：PostgreSQL MCP + `test-driven-development`
- 要做 UI / 联调：再加 Playwright MCP + 视觉 MCP（可选）
- 长任务：再加 `writing-plans` / `executing-plans` / `verification-before-completion`

## 4. 验证命令

```bash
# 后端（根 pom 默认 skipTests=true，必须显式覆盖）
cd backend && mvn test -DskipTests=false

# 前端
cd frontend && npm test && npm run build:prod
```

活体验证：后端 8090、前端 5188；至少走一遍「管理员 + 普通用户」双视角，按 README 的验收步骤逐条确认。

## 5. 安全边界

- MCP 只读优先；数据库连接使用只读账号，生产库一律不接。
- 不要把 API Key、数据库口令、会话 Cookie 写进 MCP 配置或提交到仓库。
- 浏览器 MCP 使用演示账号；截图前确认画面里没有真实个人信息。
- AI 生成的结论必须落到代码、测试或运行证据上，不以模型自述为准。
