# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository. 设计参照内部母仓的同名文件（只读；母仓定位只记录在 `.workshop/` 内部资料，绝不写入 git）并按 smart-dock 开源版实况改写。

## Project Overview

SmartDock frontend — Vue 3 + Vite + Element Plus + TypeScript 管理端，与后端 `../backend`（Spring Boot 3.5 + Java 21）配套。

Tech stack: Vue 3 (Composition API + `<script setup>`), Vite, Element Plus, Pinia, TypeScript, UnoCSS, Axios, ECharts。

Node >= 20.19.0 required。

## Build & Run

- **开发服务**：`npx vite --port 5188 --host` **直起**——⚠️ 不要用 npm run dev 脚本（本机有卡死历史）；**前端端口统一 5188**（不存在 5189 口径），80 留给其它窗口
- **生产构建**：`npm run build:prod`（交付前必过）
- **Lint**：`npm run lint:eslint` / `lint:eslint:fix`；**Format**：`npm run prettier`
- **单测**：`npm run test`（Vitest，`vitest.config.ts`）——已有测试集中在助手 API 客户端（`api/**/assistant/aguiClient.test.ts`）与 AI 助手组件（`components/*Assistant*/*.test.ts`）；新增纯函数逻辑优先补 Vitest

## Auto-Import Behavior

`unplugin-auto-import` / `unplugin-vue-components` 已配置，以下**全局可用无需显式导入**：

- Vue / Vue Router / Pinia APIs（`ref`、`useRouter`、`defineStore`…）
- VueUse（`@vueuse/core` 全量）
- Element Plus 组件与 `ElMessage` / `ElMessageBox` / `ElNotification` / `ElLoading`
- Element Plus 图标：`<i-ep-*>` 模式

声明文件生成在 `src/types/auto-imports.d.ts` 与 `src/types/components.d.ts`。

## Backend Companion Project

后端在 `../backend`。改前端页面时**必须对照后端契约**：

| Frontend | Backend |
|----------|---------|
| `src/api/**/core/analytics.ts` | `ruoyi-profile-engine/.../analytics/query/AnalyticsController.java` |
| `src/api/**/core/profile.ts` | `ruoyi-profile-engine/.../profile/query/*Controller.java` |
| `src/api/**/core/task.ts` | 采集 demo 模块 `.../controller/` 的任务 Controller |
| Form model & rules | 后端 BO 字段 + Validation 注解 |
| Table columns / TS 接口 | 后端 VO 字段 |
| DictTag 渲染 | `sys_dict_data` |

**联动检查清单**：新页面 → 后端有对应 Controller + 菜单注册；字段改名 → BO/VO 与表单/表格/TS 三处同步；新权限串 → `sys_menu` perms + `v-hasPermi`。

## Architecture

### 路由：后端驱动动态路由

侧边栏菜单由 `GET /system/menu/getRouters` 下发；权限 store 用 `import.meta.glob('./../../views/**/*.vue')` 映射组件。`src/router/index.ts` 的 `constantRoutes` 恒存在（登录/注册/401/404/首页/个人中心等）。

### 请求封装（`src/utils/request.ts`）

- Bearer token + `clientid` 头；**禁止裸用 axios**
- 500ms 内同 URL+数据的 POST 重复提交会被拦（幂等验证注意）
- 响应 `{ code, msg, data }`：非 200 触发 `ElMessage`；401 触发重登
- 下载走导出的 `download()`

### 权限体系

1. 路由级：`permissions`/`roles` 数组经 `auth.hasPermiOr()` 过滤
2. 元素级：`v-hasPermi="['analytics:center:edit']"`——**权限串三段式**，通配 `*:*:*`；不要手写权限判断

### 字典

Pinia dict store 缓存；`<DictTag :options>` 渲染。

## SmartDock 模块页面速查

| 页面 | 路径 | 说明 |
|------|------|------|
| 画像总览/详情 | `profile/{index,detail}.vue` | 主体列表/排行/详情 |
| 画像工作室 | `profile/studio.vue` | 维度/权重规则 CRUD，即时生效 |
| 评分模型(RFM) | `profile/rfm.vue` | 模型配置/快照 |
| 分群 | `profile/segments.vue` | 规则分群+静态名单 |
| 低分复核 | `profile/review.vue` | 复核队列 |
| 分析中心 | `profile/analytics.vue` | 流量/页面/漏斗/留存/访问明细/自定义分析/数据导入（多 Tab 大页） |
| AI 助手 | AI 助手组件 + `assistant` 页 | 停靠抽屉 + 会话 |
| 采集 demo | `mission/todo` 页 | 任务/待办最小闭环 |
| 更新日志 | `changelog/changelog.ts` | 见下方铁律 |

API 文件在 `src/api/**`（core/assistant/…，子目录含 `index.ts` 与类型）。

### Pinia Stores

`user` / `permission` / `dict` / `settings` / `app` / `tagsView` / `notice` / 助手会话等——新增全局状态先看现有 store 是否可挂。

## Key Patterns

- 分页：`<Pagination>` 组件，查询参数 `PageQuery`（pageNum/pageSize/orderByColumn/isAsc）
- 表单：`el-form` + `:rules`，与后端 BO 约束一致
- 路径别名 `@` → `src/`

## 图表与样式铁律

1. **ECharts 隐藏 tab 渲染 0 尺寸**：非首屏 Tab 一律 `lazy`，图表实例在激活后再建/resize
2. **SVG 样式一律用 `:style` CSS 对象**，禁止 `:stroke`/`:opacity` 等 Vue attribute 绑定 SVG presentation attribute（浏览器会把数值错误缩放导致视觉不可见）：

```vue
<!-- ❌ 禁止 -->
<line :stroke="color" :opacity="0.65" />
<!-- ✅ 正确 -->
<line :style="{ stroke: color, strokeOpacity: '0.65' }" />
```

3. **样式/图表/布局改动必须视觉验收**：computed style 实际值 + 截图 + 肉眼确认 + 至少 2 个数据规模回归；attribute 值 ≠ 渲染值
4. 同值曲线重合是数据问题不是样式问题——先查数据再改样式

## 更新日志铁律（用户硬性要求）

**任何修复类提交，必须在更新日志数据文件（`changelog/changelog.ts`）头部加人话版条目（≤3 条）**，并验收它在更新日志页首位正常渲染（含徽标）。没有条目的修复提交视为未收口。

## 多窗口并行纪律

可能有其它窗口正在改壳层文件（`index.html` / 全局样式 / layout / changelog / AI 助手组件 / `vite.wave0.config.ts`）——**只 git add 自己改的文件，禁 `git add -A`**，不碰他人文件。

## E2E 已知坑（跑浏览器验收前必读，全量移植自母仓实战沉淀）

### 登录与账号

- 登录页必须**先勾「我已阅读并同意《用户协议》」**再点「登 录」；「指定单位登录」是下拉不是按钮
- 换账号走登录页 UI（清 localStorage → 表单填写 → 点登录）；直接 setItem token 不可靠
- 切账号先清 `localStorage`

### Playwright / 浏览器自动化

- **EP 弹窗内按钮的坐标点击（page.mouse.click）常失效**——优先 browser_find + browser_click 标准 ref；run_code 里用 getByRole
- **动画页/登录页 locator 点击会永久超时**（背景动画导致元素稳定性判定永不满足，`{force:true}` 也救不了）
- **真实鼠标事件（CDP Input.dispatchMouseEvent / browser_click）才能触发 EP 交互**；JS `el.click()` 对 EP 组件多数无效。分级细则：EP checkbox/radio 用 `input.closest('label').click()`（JS 点击有效）；**表格行勾选框 label 点击无效，必须 evaluate 取 getBoundingClientRect 中心再做坐标点击**；native button（登录/确认）用 evaluate `btn.click()` 直接过
- **el-select 下拉选项不必打开就能读**——弹层常驻 body，`querySelectorAll('.el-select-dropdown__item')` 直读全部选项；多下拉并存按"独有选项文本"分辨弹层归属；选值用 evaluate `opt.click()` 有效
- `wait_for` 文本匹配可能**假通过**——断言要落到具体文本/数值，不能只等元素出现

### Element Plus 组件行为

- **el-dialog `@opened` 可迟达数秒**（等动画）——初始化用 `watch(modelValue)`；`v-if` 挂载即 visible=true 时须 `{ immediate: true }` 且被引用函数定义在 watch **之前**（TDZ 坑）
- **ElMessageBox 确认弹窗必须点「确定」才算完成**——流程验证时漏点等于取消
- **表格 `reserve-selection` 翻页后 selection 只含当前页**——全量勾选集自己维护 + 按页合并，勿信 EP 传回的 selection 覆盖全量
- **`destroy-on-close` 只销毁弹窗 DOM，不销毁组件 setup 状态**——重开弹窗须手动重置勾选集/页码
- ECharts 隐藏 tab 渲染 0 尺寸：非首屏 Tab 一律 `lazy`（同上文图表铁律）

### 响应式验收口径

- 断点 = **992px**（壳层响应式定案）；移动端验收 390×844 无横向溢出

## Code Style

- `<script setup lang="ts">` + Composition API；TS 接口定义 API 请求/响应类型
- `noImplicitAny: false`（宽松模式）——跟随现有宽松风格，不擅自加 strict
- ESLint flat config（`eslint.config.ts`）+ Prettier
- SFC 里展示 `</script>` 字符串必须 `'</' + 'script>'` 拼，否则 Vue 解析炸
