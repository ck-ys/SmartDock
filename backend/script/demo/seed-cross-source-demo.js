#!/usr/bin/env node
/*
 * 跨业务源合成演示数据（批8 全域总览）：order / supply 两个业务源的小撮事件。
 *
 * 直插 profile_event（幂等：event_uid 前缀 xsrc- + ON CONFLICT DO NOTHING），
 * 直插事件账本，不经任何采集入口；web 源口径六数字不受影响。
 * 固定种子 mulberry32(20261020)，可重复执行、结果一致。
 *
 * 用法（连接串必须显式传入，仓库不落任何内网地址）：
 *   node backend/script/demo/seed-cross-source-demo.js \
 *     --pg "postgres://postgres:postgres@127.0.0.1:5432/smartdock"
 * 或环境变量 XSRC_PG_CONN。
 *
 * 清理（复原到只有 web 源）：
 *   DELETE FROM profile_event WHERE event_uid LIKE 'xsrc-%';
 */
'use strict';

const argIndex = process.argv.findIndex((a) => a === '--pg' || a.startsWith('--pg='));
const CONN = argIndex > -1
  ? (process.argv[argIndex].startsWith('--pg=') ? process.argv[argIndex].slice(5) : process.argv[argIndex + 1])
  : process.env.XSRC_PG_CONN;
if (!CONN) {
  console.error('缺少 --pg "postgres://user:pass@host:port/db" 或 XSRC_PG_CONN');
  process.exit(1);
}

// 与 seed-analytics-demo.js 同款确定性随机
function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(20261020);
const pick = (arr) => arr[Math.floor(rand() * arr.length)];
const round2 = (n) => Math.round(n * 100) / 100;

const DAY = 24 * 3600_000;
const NOW = Date.now();
const iso = (ms) => new Date(ms).toISOString().replace('T', ' ').slice(0, 19);

const rows = [];
let seq = 0;
const nextId = () => `73${String(10_000_000_000_000 + ++seq).padStart(14, '0')}`;

function addEvent(source, eventName, subjectId, daysAgo, props) {
  rows.push({
    eventId: nextId(),
    source,
    eventName,
    subjectId,
    occurredTime: iso(NOW - daysAgo * DAY - Math.floor(rand() * DAY * 0.8)),
    props
  });
}

// ---- order 源：8 个客户 × 14 天，下单/支付/退款，金额带梯度 ----
const customers = Array.from({ length: 8 }, (_, i) => `xsrc-customer-${String(i + 1).padStart(2, '0')}`);
const skus = ['SD-PRO', 'SD-TEAM', 'SD-ENTERPRISE', 'SD-ADDON'];
let orderNo = 1000;
while (rows.length < 62) {
  const daysAgo = Math.floor(rand() * 14);
  const customer = pick(customers);
  const amount = round2(99 + rand() * 1900);
  orderNo += 1;
  addEvent('order', 'order_created', customer, daysAgo, { orderId: `XO-${orderNo}`, sku: pick(skus), amount });
  if (rand() < 0.82) {
    addEvent('order', 'order_paid', customer, daysAgo, { orderId: `XO-${orderNo}`, amount });
    if (rand() < 0.08) {
      addEvent('order', 'order_refunded', customer, daysAgo, { orderId: `XO-${orderNo}`, amount });
    }
  }
}
// ---- supply 源：3 个仓管员 × 14 天，入库/出库/库存告警 ----
const keepers = ['xsrc-keeper-a', 'xsrc-keeper-b', 'xsrc-keeper-c'];
const warehouses = ['WH-BJ-01', 'WH-SH-02'];
let supply = 0;
while (supply < 18) {
  const daysAgo = Math.floor(rand() * 14);
  const keeper = pick(keepers);
  const qty = 10 + Math.floor(rand() * 240);
  const kind = rand();
  if (kind < 0.45) {
    addEvent('supply', 'stock_in', keeper, daysAgo, { warehouse: pick(warehouses), sku: pick(skus), qty });
  } else if (kind < 0.85) {
    addEvent('supply', 'stock_out', keeper, daysAgo, { warehouse: pick(warehouses), sku: pick(skus), qty });
  } else {
    addEvent('supply', 'stock_alert', keeper, daysAgo, { warehouse: pick(warehouses), sku: pick(skus), threshold: 50 });
  }
  supply += 1;
}

async function main() {
  const { Client } = require('pg');
  const client = new Client({ connectionString: CONN });
  await client.connect();
  let inserted = 0;
  for (const r of rows) {
    const res = await client.query(
      `INSERT INTO profile_event
         (event_id, tenant_id, source, event_type, event_name, event_uid, subject_type, subject_id,
          anonymous_id, session_id, schema_version, occurred_time, props, context, traits, identifiers, create_time)
       VALUES ($1, '000000', $2, $3, $3, $4, 'user', $5, $5, NULL, '1-0-0', $6, $7::jsonb, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, CURRENT_TIMESTAMP)
       ON CONFLICT (tenant_id, source, event_uid) DO NOTHING`,
      [r.eventId, r.source, r.eventName,
       `xsrc-${r.source}-${String(rows.indexOf(r) + 1).padStart(3, '0')}`,
       r.subjectId, r.occurredTime, JSON.stringify(r.props)]
    );
    inserted += res.rowCount;
  }
  const summary = await client.query(
    `SELECT source, count(*) FROM profile_event WHERE tenant_id='000000' GROUP BY source ORDER BY source`);
  console.log(`生成 ${rows.length} 条, 新写入 ${inserted} 条（其余 event_uid 幂等跳过）`);
  console.log('当前各源事件数:', summary.rows.map((r) => `${r.source}=${r.count}`).join('  '));
  await client.end();
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
