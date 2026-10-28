#!/usr/bin/env node
/*
 * 内部协作采用度演示数据（单源重构 E 片新口径）
 *
 * 旧 seed-analytics-demo.js 生成的是"网站访客"故事（page_view/渠道/地域），
 * E 片分析中心换算后那套指标已退役；本脚本改讲"系统内部使用"故事：
 * 4 个真实用户（管理员/张三/李四/王五）30 天的登录 + 发布/提交/审核/关闭。
 *
 * 故事线（day1 = N 天前，dayN = 今天，只排工作日）：
 *   admin  管理员 day1 起：每天登录、发任务、审核（通过/驳回+完成审核）、关任务
 *   张三   user001 day1 起：每天登录、提交数据；约三成天只登录看一眼（单页离开）
 *   李四   user002 day8 入职：头两天只登录没上手，第 3 天起提交（首周激活故事）
 *   王五   user003 day26 入职（手机端）：先登录两天，第 3 天开始提交（新同事+激活）
 *
 * 写入路径与管理员手工 CSV 补录完全同一条（/analytics/import）；
 * eventUid 按事件自己的日历日期 + 当日序号锚定（demo-io-<日期>-<当日序号>）——
 * 同一天内重复执行幂等跳过；但换 --days 或隔天重播时故事窗口平移，重叠日期
 * 的事件内容会漂移（uid 同、内容旧），要彻底重置演示库：
 * 先按 event_uid LIKE 'demo-%' 清掉再重跑本脚本 + 重建汇总。
 *
 * 用法：
 *   node backend/script/demo/seed-internal-ops-demo.js \
 *     --base http://localhost:8090 --days 30
 *   （账号默认 admin/admin123，可用 --username/--password 覆盖；
 *     环境变量 ANALYTICS_BASE / SEED_ADMIN_USER / SEED_ADMIN_PASS 同名覆盖）
 */
'use strict';

const CLIENT_ID = 'e5cd7e4891bf95d1d19206ce24a7b32e';
const DEFAULTS = {
  base: process.env.ANALYTICS_BASE || 'http://localhost:8090',
  username: process.env.SEED_ADMIN_USER || 'admin',
  password: process.env.SEED_ADMIN_PASS || 'admin123',
  days: 30
};

// 与 baseline 02_reference 的演示用户一一对应（user_id 为雪花串）
const PERSONAS = [
  { key: 'admin', subjectId: '1', device: ['desktop', 'Windows', 'Chrome'], startDay: 1 },
  { key: 'zhangsan', subjectId: '9100000001', device: ['desktop', 'Windows', 'Edge'], startDay: 1 },
  { key: 'lisi', subjectId: '9100000002', device: ['desktop', 'macOS', 'Safari'], startDay: 8 },
  { key: 'wangwu', subjectId: '9100000003', device: ['mobile', 'iOS', 'Safari'], startDay: 26 }
];

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

const random = mulberry32(20261028);

// ---------------------------------------------------------------- 参数
function parseArgs(argv) {
  const options = { ...DEFAULTS };
  for (let i = 2; i < argv.length; i += 2) {
    const key = String(argv[i]).replace(/^--/, '');
    const value = argv[i + 1];
    if (key === 'base') options.base = value;
    else if (key === 'username') options.username = value;
    else if (key === 'password') options.password = value;
    else if (key === 'days') options.days = Math.max(7, Math.min(90, Number(value) || 30));
  }
  return options;
}

// ---------------------------------------------------------------- 日期（UTC+8 墙上时间）
const pad = (n) => String(n).padStart(2, '0');
// 返回 N 天前（0=今天）在 UTC+8 的 Date（仅日期部分有意义）
function dayAgo(n, now) {
  const wall = new Date(now.getTime() + 8 * 3600_000);
  const d = new Date(wall.getTime() - n * 86_400_000);
  return d;
}
const fmt = (d, hhmmss) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${hhmmss}`;
const isWorkday = (d) => d.getUTCDay() !== 0 && d.getUTCDay() !== 6;

// ---------------------------------------------------------------- 故事生成
function buildEvents(options) {
  const now = new Date();
  const rows = [];
  let taskId = 9000; // 故事里的任务号/提交号: 只需自洽, 不指向真实任务表
  let submissionId = 7000;
  const TASK_TITLES = ['客户走访清单', '门店补货单', '活动物料核对', '会员回访名单', '库存盘点表'];
  // CSV props 列是含逗号的 JSON——按 RFC4180 双引号包裹、内部 " 翻倍
  const csvCell = (v) => (typeof v === 'object' && v !== null ? `"${JSON.stringify(v).replace(/"/g, '""')}"` : v);
  // 三席回修: 序号按"日期内自增"——同一天的事件跨运行拿到稳定 uid(全局序号会因窗口起点平移整体错位)
  let seqByDay = {};
  const push = (persona, date, hhmmss, eventName, props) => {
    const day = `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}`;
    seqByDay[day] = (seqByDay[day] || 0) + 1;
    rows.push([
      eventName,
      `demo-io-${day}-${pad(seqByDay[day])}`,
      'user',
      persona.subjectId,
      fmt(date, hhmmss),
      persona.device[0],
      persona.device[1],
      persona.device[2],
      props ? csvCell(props) : ''
    ]);
  };
  const clock = (h, m) => `${pad(h)}:${pad(m + Math.floor(random() * 3))}:${pad(Math.floor(random() * 60))}`;
  // 三席回修: 由基点时间推 +N 秒, 不再独立随机——否则同分钟内秒数倒挂, 审核动作会早于审核结果
  const plusSeconds = (hhmmss, seconds) => {
    const [h, m, s] = hhmmss.split(':').map(Number);
    const total = h * 3600 + m * 60 + s + seconds;
    return `${pad(Math.floor(total / 3600))}:${pad(Math.floor((total % 3600) / 60))}:${pad(total % 60)}`;
  };

  for (let n = options.days; n >= 1; n--) {
    const date = dayAgo(n, now);
    if (!isWorkday(date)) continue;
    const dayIndex = options.days - n + 1; // day1..dayN

    for (const persona of PERSONAS) {
      if (dayIndex < persona.startDay) continue;

      if (persona.key === 'admin') {
        push(persona, date, clock(8, 50), 'user.login');
        const publishes = 1 + Math.floor(random() * 2);
        for (let i = 0; i < publishes; i++) {
          taskId += 1;
          push(persona, date, clock(9, 2 + i * 6), 'task.published', {
            taskId,
            title: TASK_TITLES[Math.floor(random() * TASK_TITLES.length)],
            assigneeCount: 1 + Math.floor(random() * 3),
            deadline: fmt(new Date(date.getTime() + 5 * 86_400_000), '18:00:00').slice(0, 10)
          });
        }
        const audits = 1 + Math.floor(random() * 2);
        for (let i = 0; i < audits; i++) {
          const reject = random() < 0.25;
          submissionId += 1;
          const auditTaskId = 9001 + Math.floor(random() * (taskId - 9000 || 1));
          const auditAt = clock(9, 20 + i * 4);
          push(persona, date, auditAt, reject ? 'data.audit_rejected' : 'data.audit_passed',
            { taskId: auditTaskId, submissionId, version: 1 });
          push(persona, date, plusSeconds(auditAt, 30 + Math.floor(random() * 60)), 'data.audited',
            { submissionId }); // 审核岗自己的动作事件, 晚于审核结果 30-90 秒
        }
        if (random() < 0.5) {
          push(persona, date, clock(14, 10), 'task.closed', { taskId: 9000 + Math.floor(random() * 50) + 1 });
        }
      } else if (persona.key === 'zhangsan') {
        push(persona, date, clock(9, 0), 'user.login');
        if (random() < 0.3) continue; // 三成天只登录看一眼 → 单页离开
        const submits = 1 + Math.floor(random() * 3);
        for (let i = 0; i < submits; i++) {
          submissionId += 1;
          push(persona, date, clock(9, 10 + i * 7), 'data.submitted',
            { taskId: 9001 + Math.floor(random() * 40), submissionId, version: 1 });
        }
        if (random() < 0.25) {
          submissionId += 1;
          push(persona, date, clock(14, 25), 'data.submitted',
            { taskId: 9001 + Math.floor(random() * 40), submissionId, version: 1 }); // 午后补一单, 30 分钟外成新会话
        }
      } else if (persona.key === 'lisi') {
        push(persona, date, clock(9, 30), 'user.login');
        if (dayIndex < persona.startDay + 2) continue; // 入职头两天没上手
        const submits = 1 + Math.floor(random() * 2);
        for (let i = 0; i < submits; i++) {
          submissionId += 1;
          push(persona, date, clock(9, 40 + i * 6), 'data.submitted',
            { taskId: 9001 + Math.floor(random() * 40), submissionId, version: 1 });
        }
      } else if (persona.key === 'wangwu') {
        push(persona, date, clock(10, 5), 'user.login');
        if (dayIndex < persona.startDay + 2) continue; // 新同事先看两天
        submissionId += 1;
        push(persona, date, clock(10, 15), 'data.submitted',
          { taskId: 9001 + Math.floor(random() * 40), submissionId, version: 1 });
      }
    }
  }
  return rows;
}

// ---------------------------------------------------------------- HTTP
async function login(options) {
  const res = await fetch(`${options.base}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      tenantId: '000000',
      username: options.username,
      password: options.password,
      clientId: CLIENT_ID,
      grantType: 'password'
    })
  });
  const body = await res.json();
  if (body.code !== 200) throw new Error(`登录失败: ${body.msg}`);
  return body.data.access_token;
}

async function importBatch(options, token, events) {
  const csv = ['eventName,eventUid,subjectType,subjectId,occurredTime,deviceType,os,browser,props']
    .concat(events.map((e) => e.join(',')))
    .join('\n');
  const res = await fetch(`${options.base}/analytics/import`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, clientid: CLIENT_ID },
    body: JSON.stringify({ source: 'dcs', csv })
  });
  const body = await res.json();
  if (body.code !== 200) throw new Error(`导入失败: ${body.msg}`);
  return body.data;
}

// ---------------------------------------------------------------- 主流程
(async () => {
  const options = parseArgs(process.argv);
  const events = buildEvents(options);
  console.log(`生成内部协作故事事件 ${events.length} 条（${options.days} 天 · ${PERSONAS.length} 位真实用户）`);

  const token = await login(options);
  let written = 0;
  let skipped = 0;
  for (let i = 0; i < events.length; i += 1000) {
    const result = await importBatch(options, token, events.slice(i, i + 1000));
    written += result.written;
    skipped += result.skipped;
    console.log(`批次 ${Math.floor(i / 1000) + 1}: 写入 ${result.written} / 跳过 ${result.skipped}`);
  }
  console.log(`完成：写入 ${written} 条，幂等跳过 ${skipped} 条`);
  console.log('下一步：分析中心 → 重建汇总（或 POST /analytics/rebuild?source=dcs），再看使用概览。');
})().catch((e) => {
  console.error('种子失败:', e.message);
  process.exit(1);
});
