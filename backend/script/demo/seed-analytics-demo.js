#!/usr/bin/env node
/*
 * 分析中心合成演示数据（可重复执行，事件按 eventUid 幂等）
 *
 * 只通过公开采集口写入，不直连数据库；生成 30 天左右、多访客/多会话/
 * 多渠道/多设备的访问数据，便于演示流量趋势、漏斗与留存。
 *
 * 用法：
 *   node backend/script/demo/seed-analytics-demo.js \
 *     --endpoint http://localhost:8090/analytics/collect \
 *     --siteKey sd_demo_site --days 30 --visitors 40
 *
 * 可选环境变量：ANALYTICS_ENDPOINT / ANALYTICS_SITE_KEY
 */
'use strict';

const DEFAULTS = {
  endpoint: process.env.ANALYTICS_ENDPOINT || 'http://localhost:8090/analytics/collect',
  siteKey: process.env.ANALYTICS_SITE_KEY || 'sd_demo_site',
  days: 30,
  visitors: 40
};

const CHANNELS = [
  { channel: 'baidu', referrer: 'https://www.baidu.com/', weight: 26 },
  { channel: 'direct', referrer: '', weight: 20 },
  { channel: 'weibo', referrer: 'https://weibo.com/', weight: 16 },
  { channel: 'wechat', referrer: 'https://mp.weixin.qq.com/', weight: 14 },
  { channel: 'google', referrer: 'https://www.google.com/', weight: 12 },
  { channel: 'zhihu', referrer: 'https://www.zhihu.com/', weight: 7 },
  { channel: 'bing', referrer: 'https://www.bing.com/', weight: 5 }
];

const GEOS = [
  { country: '中国', province: '北京', city: '北京', weight: 24 },
  { country: '中国', province: '上海', city: '上海', weight: 22 },
  { country: '中国', province: '广东', city: '深圳', weight: 20 },
  { country: '中国', province: '浙江', city: '杭州', weight: 16 },
  { country: '中国', province: '四川', city: '成都', weight: 12 },
  { country: '中国', province: '湖北', city: '武汉', weight: 6 }
];

const DEVICES = [
  { deviceType: 'desktop', os: 'Windows', browser: 'Chrome', weight: 52 },
  { deviceType: 'desktop', os: 'macOS', browser: 'Safari', weight: 12 },
  { deviceType: 'mobile', os: 'iOS', browser: 'Safari', weight: 20 },
  { deviceType: 'mobile', os: 'Android', browser: 'Chrome', weight: 12 },
  { deviceType: 'tablet', os: 'iPadOS', browser: 'Safari', weight: 4 }
];

const PAGES = [
  { path: '/home', title: 'SmartDock · 首页' },
  { path: '/pricing', title: '定价方案' },
  { path: '/docs', title: '接入文档' },
  { path: '/features', title: '能力清单' },
  { path: '/blog', title: '实践博客' },
  { path: '/integrations', title: '集成列表' },
  { path: '/changelog', title: '更新日志' }
];

const SEARCHES = ['crm', 'analytics', 'pricing', 'dashboard', 'api', 'export', 'retention', 'funnel'];

// ---------------------------------------------------------------- 确定性随机
function mulberry32(seed) {
  let a = seed >>> 0;
  return function random() {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const random = mulberry32(20261019);
const pick = (list) => list[Math.floor(random() * list.length)];
const weighted = (list) => {
  const total = list.reduce((sum, item) => sum + item.weight, 0);
  let roll = random() * total;
  for (const item of list) {
    roll -= item.weight;
    if (roll <= 0) return item;
  }
  return list[list.length - 1];
};
const intBetween = (min, max) => min + Math.floor(random() * (max - min + 1));

// ---------------------------------------------------------------- 参数
function parseArgs(argv) {
  const out = { ...DEFAULTS };
  for (let i = 2; i < argv.length; i += 2) {
    const key = argv[i];
    const value = argv[i + 1];
    if (key === '--endpoint') out.endpoint = value;
    else if (key === '--siteKey') out.siteKey = value;
    else if (key === '--days') out.days = Math.max(1, Number(value) || 1); // days=0 曾在 intBetween(0,-1) 上死循环(批10 顺手修)
    else if (key === '--visitors') out.visitors = Number(value);
  }
  return out;
}

// ---------------------------------------------------------------- 事件
/**
 * 按权重铺一张固定序列再确定性洗牌：40 个访客也能得到接近权重的分布，
 * 避免单次随机抽样把某个渠道抽爆（演示数据要稳定）。
 */
function weightedSequence(list, count) {
  const pool = [];
  for (const item of list) {
    for (let i = 0; i < item.weight; i++) pool.push(item);
  }
  for (let i = pool.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [pool[i], pool[j]] = [pool[j], pool[i]];
  }
  return Array.from({ length: count }, (_, index) => pool[index % pool.length]);
}

function isoAt(daysAgo, hour, minute) {
  // 按 UTC+8 的墙上时间生成，避免演示机时区与后端 JVM(GMT+8) 错位
  const now = new Date();
  const shanghai = new Date(now.getTime() + 8 * 3600 * 1000);
  const utcMs = Date.UTC(
    shanghai.getUTCFullYear(),
    shanghai.getUTCMonth(),
    shanghai.getUTCDate() - daysAgo,
    hour - 8,
    minute
  );
  return new Date(utcMs).toISOString();
}

function event(name, visitor, session, occurredTime, props, context) {
  return {
    eventName: name,
    eventUid: `demo-${visitor}-${session}-${name}-${occurredTime}`,
    anonymousId: visitor,
    sessionId: session,
    occurredTime,
    props,
    context
  };
}

function buildEvents(options) {
  const events = [];
  const signupUsers = new Set();
  const channelPlan = weightedSequence(CHANNELS, options.visitors);
  const geoPlan = weightedSequence(GEOS, options.visitors);
  const devicePlan = weightedSequence(DEVICES, options.visitors);

  for (let v = 1; v <= options.visitors; v++) {
    const visitor = `demo-v-${String(v).padStart(2, '0')}`;
    const channel = channelPlan[v - 1];
    const geo = geoPlan[v - 1];
    const device = devicePlan[v - 1];

    // 老访客更可能多次回访：访问次数 1~6 次
    const sessions = intBetween(1, 6);
    const sessionDays = new Set();
    for (let s = 0; s < sessions; s++) {
      let daysAgo = intBetween(0, options.days - 1);
      while (sessionDays.has(daysAgo)) {
        daysAgo = intBetween(0, options.days - 1);
      }
      sessionDays.add(daysAgo);
    }

    let sessionIndex = 0;
    for (const daysAgo of Array.from(sessionDays).sort((a, b) => b - a)) {
      sessionIndex++;
      const session = `demo-s-${v}-${sessionIndex}`;
      const hour = intBetween(8, 22);
      let minute = intBetween(0, 50);
      const baseContext = {
        referrer: sessionIndex === 1 ? channel.referrer : '',
        sourceChannel: channel.channel,
        utm: sessionIndex === 1 ? { source: channel.channel, campaign: 'demo-seed' } : undefined,
        deviceType: device.deviceType,
        os: device.os,
        browser: device.browser,
        country: geo.country,
        province: geo.province,
        city: geo.city
      };

      const pageCount = weighted([
        { value: 1, weight: 34 },
        { value: 2, weight: 28 },
        { value: 3, weight: 20 },
        { value: 4, weight: 12 },
        { value: 5, weight: 6 }
      ]).value;
      const firstPage = sessionIndex === 1 ? pick(PAGES) : pick(PAGES);
      let path = firstPage;

      for (let p = 0; p < pageCount; p++) {
        const page = path;
        const pageUrl = page.path;
        const occurredTime = isoAt(daysAgo, hour, minute);
        events.push(
          event(
            'page_view',
            visitor,
            session,
            occurredTime,
            { pageUrl, title: page.title },
            { ...baseContext, pageUrl, title: page.title }
          )
        );

        // 约三分之一的页面停留后发起站内搜索
        if (random() < 0.34 && p < pageCount - 1) {
          minute += intBetween(1, 3);
          const keyword = pick(SEARCHES);
          events.push(
            event(
              'search',
              visitor,
              session,
              isoAt(daysAgo, hour, minute),
              { keyword },
              { ...baseContext, pageUrl: `/search?q=${encodeURIComponent(keyword)}`, searchTerm: keyword }
            )
          );
        }

        // 首访或回访的一次会话中，小部分访客完成注册
        if (random() < 0.12 && !signupUsers.has(visitor)) {
          signupUsers.add(visitor);
          minute += intBetween(1, 4);
          events.push(
            event(
              'signup',
              visitor,
              session,
              isoAt(daysAgo, hour, minute),
              { plan: random() < 0.6 ? 'basic' : 'pro' },
              { ...baseContext, pageUrl: '/signup' }
            )
          );
        }

        minute += intBetween(1, 6);
        path = pick(PAGES);
      }

      // 会话结束事件
      minute += intBetween(1, 4);
      events.push(
        event(
          'page_leave',
          visitor,
          session,
          isoAt(daysAgo, hour, minute),
          { pageUrl: path.path, durationMs: intBetween(30, 600) * 1000 },
          { ...baseContext, pageUrl: path.path }
        )
      );
    }
  }

  return events;
}

// ---------------------------------------------------------------- 上报
async function sendBatch(endpoint, siteKey, batch) {
  const res = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ siteKey, events: batch })
  });
  const text = await res.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error(`collect 返回非 JSON（HTTP ${res.status}）：${text.slice(0, 200)}`);
  }
  if (body.code !== 200) {
    throw new Error(`collect 失败：${body.code} ${body.msg || ''}`);
  }
  return body.data;
}

(async () => {
  const options = parseArgs(process.argv);
  const events = buildEvents(options);
  console.log(
    `[seed] endpoint=${options.endpoint} siteKey=${options.siteKey} visitors=${options.visitors} events=${events.length}`
  );

  let accepted = 0;
  let skipped = 0;
  for (let i = 0; i < events.length; i += 200) {
    const batch = events.slice(i, i + 200);
    const result = await sendBatch(options.endpoint, options.siteKey, batch);
    accepted += Number(result.accepted || 0);
    skipped += Number(result.skipped || 0);
    process.stdout.write(`\r[seed] ${Math.min(i + 200, events.length)}/${events.length}`);
  }
  console.log(`\n[seed] done accepted=${accepted} skipped=${skipped}`);
  console.log('[seed] 提示：到 数据分析 → 重建汇总 刷新会话/日汇总');
})().catch((error) => {
  console.error('[seed] failed:', error.message);
  process.exit(1);
});
