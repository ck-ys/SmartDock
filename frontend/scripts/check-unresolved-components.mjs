/*
 * 组件解析守卫（收口门禁）：登录后把路由表里的叶子路由逐个打开，
 * 只要控制台出现「Failed to resolve component」就以非 0 退出。
 *
 * 防的是这类静默事故：批量改名时模板标签和 <script setup> 里的变量名没对上
 * （例：<smartdock-assistant /> 对不上 const Assistant），Vue 找不到组件不报错，
 * 只在控制台留一条 warn，那个位置被画成一个空标签——vite build 过、单测全绿，页面上只是少一块。
 *
 * 只能对着开发服务跑（`npx vite --port 5188 --host` 直起）：Vue 只在开发构建里发这条 warn。
 * 用法：SHOTS_PASS=<演示账号口令> npm run check:components
 * 环境变量：CHECK_BASE（默认 http://127.0.0.1:5188）/ CHECK_USER（默认 admin）/ SHOTS_PASS（必填）
 * 退出码：0 干净；1 有解析失败 / 有路由打不开 / 阳性对照没过；2 脚本自身问题（没登录上等）
 */
import { chromium } from 'playwright';

const BASE = process.env.CHECK_BASE || 'http://127.0.0.1:5188';
const USER = process.env.CHECK_USER || 'admin';
const PASS = process.env.SHOTS_PASS || process.env.CHECK_PASS || '';
const WAIT_MS = Number(process.env.CHECK_WAIT_MS || 3000);
/** 阳性对照用的假组件名：脚本最后故意打一条同格式的 warn，必须被记到 */
const CONTROL = '__positive_control__';

const log = (...a) => console.log('[check]', ...a);
if (!PASS) {
  log('缺演示账号口令：用 SHOTS_PASS=<口令> 传入（口令不写进仓库）');
  process.exit(2);
}

const RESOLVE_WARN = /Failed to resolve component: ([^\s.]+)/;
const ROUTER_WARN = /Couldn't resolve component "([^"]+)"/;

const browser = await chromium.launch({ headless: true });
try {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  let hits = [];
  let controlHits = 0;
  page.on('console', (m) => {
    const t = m.text();
    const a = t.match(RESOLVE_WARN);
    const b = a ? null : t.match(ROUTER_WARN);
    const name = a ? a[1] : b ? b[1] : null;
    if (!name) return;
    if (name === CONTROL) controlHits++;
    else hits.push({ kind: a ? '模板标签' : '路由页面', name });
  });

  // 登录（选择器与 readme-screenshots.mjs 同一套）
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#login-username', { timeout: 30000 });
  await page.fill('#login-username', USER);
  await page.fill('#login-password', PASS);
  const agree = page.locator('.agreement-checkbox__box .el-checkbox__inner');
  if (await agree.count()) await agree.first().click();
  await page.locator('.login-submit').click();
  await page.waitForFunction(() => !location.pathname.includes('/login'), null, { timeout: 30000 });
  log('登录:', USER);

  // 路由清单：直接读跑着的应用的路由表（登录后动态路由已注册）；
  // 只巡「叶子 + 以 / 开头 + 不带参数 + 不是跳转」的，带参数的详情页不在这条守卫的范围内
  const got = await page.evaluate(() => {
    const router = document.querySelector('#app')?.__vue_app__?.config?.globalProperties?.$router;
    if (!router) return null;
    const all = router.getRoutes();
    const keep = all.filter((r) => !(r.children && r.children.length) && !r.redirect && r.path.startsWith('/') && !/:/.test(r.path));
    return { total: all.length, list: keep.map((r) => r.path).sort() };
  });
  if (!got) {
    log('没拿到路由表（CHECK_BASE 指的不是本仓前端？）');
    process.exit(2);
  }
  const routes = [...new Set(got.list.map((p) => p.split('?')[0]))];
  log(`路由表 ${got.total} 条 → 巡检 ${routes.length} 条（带子路由 / 跳转 / 带参数的不巡）`);

  const byName = new Map();
  const failedRoutes = [];
  let shellPages = 0;
  let done = 0;
  for (const p of routes) {
    hits = [];
    try {
      await page.goto(`${BASE}${p}`, { waitUntil: 'domcontentloaded', timeout: 30000 });
      // 先等页面外框真的画出来再计时：解析失败的警告是外框渲染那一刻发出的，
      // 外框还没画出来就结束等待，会把红等成绿。不带外框的页面（登录 / 协议 / 回调）等不到，照常往下走
      const shellUp = await page
        .locator('.app-wrapper')
        .first()
        .waitFor({ state: 'attached', timeout: 6000 })
        .then(
          () => true,
          () => false
        );
      if (shellUp) shellPages++;
      await page.waitForTimeout(WAIT_MS);
    } catch {
      failedRoutes.push(p);
    }
    for (const h of hits) {
      const key = `${h.kind}：${h.name}`;
      if (!byName.has(key)) byName.set(key, new Set());
      byName.get(key).add(p);
    }
    done++;
    if (done % 15 === 0 || done === routes.length) log(`进度 ${done}/${routes.length}`);
  }

  // 阳性对照：故意打一条同格式的 warn，必须被记到——否则上面的「0」只是监听器没在工作
  await page.evaluate((c) => console.warn(`[Vue warn]: Failed to resolve component: ${c}`), CONTROL);
  await page.waitForTimeout(500);

  const names = [...byName.entries()].sort((a, b) => b[1].size - a[1].size);
  log(`阳性对照: ${controlHits}（应为 1）`);
  log(`解析失败: ${names.length} 种 / ${new Set(names.flatMap(([, s]) => [...s])).size} 条路由`);
  for (const [key, set] of names) {
    log(`  ${key}  ×${set.size} 条路由，例如 ${[...set].slice(0, 3).join('，')}`);
  }
  log(`打不开的路由: ${failedRoutes.length}${failedRoutes.length ? `（${failedRoutes.slice(0, 5).join('，')}）` : ''}`);
  log(`外框画出来的路由: ${shellPages} 条（不带外框的登录 / 协议 / 回调页不算）`);

  // 外框一条都没画出来 = 这一轮什么都没看到，不能算绿
  const ok = controlHits === 1 && names.length === 0 && failedRoutes.length === 0 && shellPages > 0;
  log(ok ? 'RESULT: PASS' : 'RESULT: FAIL');
  process.exitCode = ok ? 0 : 1;
} catch (e) {
  log('脚本自身出错（多半是没登录上）:', String(e.message).split('\n')[0]);
  process.exitCode = 2;
} finally {
  await browser.close();
}
