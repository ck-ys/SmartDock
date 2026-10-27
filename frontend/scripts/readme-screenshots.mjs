#!/usr/bin/env node
/*
 * README 六张真机截图：统一 1440×900 / 亮色 / 同一套演示数据。
 *
 * 前置
 *   1. 后端 8090、前端 5188 已启动（端口铁律，勿改）。
 *   2. 演示库已播合成数据：
 *        node backend/script/demo/seed-analytics-demo.js --base http://localhost:8090 --days 30 --visitors 40
 *      并在「数据分析 → 重建汇总」跑一次全量重建（或直接 POST /analytics/rebuild?source=web&days=45）。
 *   3. 助手那张依赖可用的模型 Key；模型不可用时仍会出图，但画面停在提问气泡上。
 *
 * 用法
 *   cd frontend && node scripts/readme-screenshots.mjs
 *   产物覆盖 ../docs/images/screenshots/01..06-*.png
 *
 * 注意
 *   Sa-Token 同账号重复登录会顶掉旧 token：拍图期间别用同一账号跑别的脚本，
 *   否则页面接口批量 401、助手会卡在「执行中」。
 */
import { mkdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '../../docs/images/screenshots');

const BASE = process.env.SHOTS_BASE || 'http://127.0.0.1:5188';
const USER = process.env.SHOTS_USER || 'admin';
const PASS = process.env.SHOTS_PASS || 'admin123';
const ASK = process.env.SHOTS_ASK || '我发布的任务提交进度怎么样？';
/** 11 号图用：一条「要写库」的请求，用来让助手弹出操作确认卡（只截卡，不点确认）。 */
const HITL_ASK =
  process.env.SHOTS_HITL_ASK ||
  '帮我建一个任务草稿：任务名「演示文稿评审」，填给 stu001，截止日期 2026-11-05，类型填表，说明：演示用';
/** 首次使用「三条承诺」须知门按用户持久化；演示超管 admin 的 userId 固定为 1。 */
const GATE_UIDS = (process.env.SHOTS_GATE_UIDS || '1').split(',').map((s) => s.trim()).filter(Boolean);
/** SHOTS_ONLY=05 只重拍某几张（改版后补拍用），不填=全拍。 */
const ONLY = (process.env.SHOTS_ONLY || '').split(',').map((s) => s.trim()).filter(Boolean);
const want = (code) => ONLY.length === 0 || ONLY.includes(code);

const VIEWPORT = { width: 1440, height: 900 };
const DEV_OVERLAY_CSS =
  '#__vue-devtools-container__, #vue-inspector-container, .vite-plugin-vue-devtools__panel { display: none !important; }';

const log = (...a) => console.log('[shots]', ...a);

async function login(page) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#login-username', { timeout: 30000 });
  await page.fill('#login-username', USER);
  await page.fill('#login-password', PASS);
  const agree = page.locator('.agreement-checkbox__box .el-checkbox__inner');
  if (await agree.count()) await agree.first().click();
  await page.locator('.login-submit').click();
  await page.waitForFunction(() => !location.pathname.includes('/login'), null, { timeout: 30000 });
  await page.waitForTimeout(1500);
  // 干净的浏览器上下文每次都会重新弹须知门；用同一把存储键预先放行（见 useAssistant 的 gateOk）
  await page.evaluate((uids) => {
    for (const id of uids) {
      try {
        localStorage.setItem(`dcs:assistant:gateOk:${id}`, '1');
      } catch {
        /* 存储不可用就算了，askNewSession 还会兜底点按钮 */
      }
    }
  }, GATE_UIDS);
  log('login ok:', USER);
}

/** 每张图拍前统一：隐藏 dev 浮层 + 等图表动画落定。 */
async function settle(page, ms = 1200) {
  await page.addStyleTag({ content: DEV_OVERLAY_CSS });
  await page.waitForTimeout(ms);
}

async function shot(page, file) {
  const path = join(OUT, file);
  await page.screenshot({ path });
  const kb = Math.round(statSync(path).size / 1024);
  log('shot', file, `${kb}KB`);
}

async function goto(page, path, waitText, settleMs = 1200) {
  await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded' });
  if (waitText) await page.getByText(waitText, { exact: false }).first().waitFor({ timeout: 30000 });
  await settle(page, settleMs);
}

async function openAssistant(page) {
  const fab = page.locator('.dcs-fab');
  await fab.waitFor({ state: 'visible', timeout: 15000 });
  // 单击=快捷菜单，再单击才开抽屉（见布局组件注释）
  await fab.click();
  await page.waitForTimeout(600);
  await fab.click();
  await page.locator('aside.dcs-dw.open').waitFor({ state: 'visible', timeout: 15000 });
}

async function askNewSession(page, question) {
  // Alt+N = 新建会话，避免历史消息挤进画面
  await page.keyboard.press('Alt+n');
  await page.waitForTimeout(900);
  const input = page.locator('.dcs-dw textarea').first();
  await input.click();
  await input.fill(question);
  await input.press('Enter');
  const gate = page.locator('.dcs-gate .gok');
  try {
    await gate.first().waitFor({ state: 'visible', timeout: 6000 });
    await gate.first().click();
    log('gate accepted');
  } catch {
    /* 本会话已同意过则无门 */
  }
}

/** 轮询抽屉里出现「已完成」；模型不可用会超时，超时后照样出图。 */
async function waitAnswer(page, timeoutMs = 120000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const text = await page.evaluate(() => document.querySelector('aside.dcs-dw')?.innerText || '');
    if (/已完成/.test(text)) {
      await page.waitForTimeout(1200);
      return true;
    }
    await page.waitForTimeout(1000);
  }
  log('warn: 等回答超时（模型可能不可用），按当前画面出图');
  return false;
}

/** 会话页发一条「要写库」的请求，等到操作确认卡出现（不点确认——要的就是卡本身）。 */
async function startHitl(page, timeoutMs = 150000) {
  const box = page.locator('textarea').first();
  await box.waitFor({ state: 'visible', timeout: 15000 });
  await box.click();
  await box.fill(HITL_ASK);
  await box.press('Enter');
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const text = await page.evaluate(() => document.body.innerText || '');
    if (/确认要执行这个操作吗/.test(text)) {
      await page.waitForTimeout(1500);
      return true;
    }
    await page.waitForTimeout(1200);
  }
  log('warn: 等操作确认卡超时（模型或工具不可用），按当前画面出图');
  return false;
}

const main = async () => {
  mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: VIEWPORT,
    deviceScaleFactor: 1,
    colorScheme: 'light',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(30000);

  try {
    await login(page);

    if (want('01')) {
      await goto(page, '/index', '总览', 1500);
      await shot(page, '01-overview.png');
    }

    if (want('02')) {
      await goto(page, '/insight/profile-analytics', '数据分析中心', 2000);
      await shot(page, '02-analytics.png');
    }

    if (want('03')) {
      await goto(page, '/insight/profile-detail/dcs/9100000002', '维度雷达', 1500);
      await shot(page, '03-profile-detail.png');
    }

    if (want('04')) {
      await goto(page, '/insight/profile-studio', '画像工作室', 1500);
      await shot(page, '04-profile-studio.png');
    }

    if (want('05')) {
      await goto(page, '/tenant/ai-config', 'AI 模型配置', 1200);
      // 默认停在 DeepSeek（演示库那把钥匙是失效的），切到可用的厂商再拍
      const vendor = process.env.SHOTS_VENDOR || 'OpenRouter';
      const card = page.getByRole('button', { name: new RegExp(vendor, 'i') }).first();
      if (await card.count()) {
        await card.click();
        await page.waitForTimeout(1000);
        // 点卡片会把它滚进视口，标题与四张统计卡被顶出画面——拍照前回到顶部
        await page.evaluate(() => {
          window.scrollTo(0, 0);
          document.querySelectorAll('.app-main, .el-scrollbar__wrap').forEach((el) => { el.scrollTop = 0; });
        });
        await page.waitForTimeout(600);
      } else {
        log('warn: 未找到厂商卡片', vendor);
      }
      await shot(page, '05-model-config.png');
    }

    if (want('06')) {
      await goto(page, '/index', '总览', 1200);
      await openAssistant(page);
      await askNewSession(page, ASK);
      await waitAnswer(page);
      await shot(page, '06-assistant.png');
    }

    if (want('07')) {
      await goto(page, '/insight/profile-analytics', '数据分析中心', 1500);
      const funnelTab = page.locator('.el-tabs__item', { hasText: '转化漏斗' }).first();
      if (await funnelTab.count()) {
        await funnelTab.click();
        await page.waitForTimeout(2200);
      } else {
        log('warn: 未找到「转化漏斗」页签');
      }
      await shot(page, '07-analytics-funnel.png');
    }

    if (want('08')) {
      await goto(page, '/insight/profile-segments', '标签分群', 1600);
      await shot(page, '08-profile-segments.png');
    }

    if (want('09')) {
      await goto(page, '/insight/profile-review', '低分复核', 1600);
      await shot(page, '09-profile-review.png');
    }

    if (want('10')) {
      await goto(page, '/insight/compliance', '合规中心', 1600);
      await shot(page, '10-compliance.png');
    }

    if (want('11')) {
      await goto(page, '/ai/assistant', '我的助手会话', 1600);
      await startHitl(page);
      await shot(page, '11-assistant-hitl.png');
    }

    if (want('12')) {
      // 运行健康是进程内累计：先跑两轮正常问答，让「收尾方式」只出现正常完成
      await goto(page, '/index', '总览', 1200);
      await openAssistant(page);
      for (const q of [ASK, '查询待办事项']) {
        await askNewSession(page, q);
        await waitAnswer(page);
      }
      await goto(page, '/ai/assistant-admin', 'AI 助手管理', 2400);
      await shot(page, '12-assistant-admin.png');
    }

    log('done ->', OUT);
  } finally {
    await browser.close();
  }
};

main().catch((e) => {
  console.error('[shots] failed:', e);
  process.exit(1);
});
