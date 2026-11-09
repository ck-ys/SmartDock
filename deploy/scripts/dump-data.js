#!/usr/bin/env node
/**
 * 数据导出（无 pg_dump 时的兜底）：把整库数据导成「可直接灌进基线库」的 SQL。
 *
 * 为什么是「只导数据」：DDL 由仓库里的 backend/script/sql/baseline + migration 保证完整，
 * 而从 pg_catalog 反推 DDL 容易漏（触发器/视图/权限/扩展）。所以云端流程是：
 *   1) 空库跑 baseline（compose 首次启动自动做）
 *   2) psql -f data-<db>-<时间>.sql 灌数据
 *
 * 用法：
 *   node deploy/scripts/dump-data.js
 *   PGHOST=<数据库主机> PGPORT=<端口> PGDATABASE=smartdock_shots PGPASSWORD=<口令> node deploy/scripts/dump-data.js
 *   参数覆盖：node dump-data.js --out=/path/x.sql --schemas=public,agentscope
 *
 * 依赖：仓库自带 tools/mcp/dock-pg-mcp/node_modules/pg（无需 npm install）
 */
'use strict';

const fs = require('fs');
const path = require('path');

function loadPg() {
  const candidates = [
    'pg',
    path.resolve(__dirname, '../../tools/mcp/dock-pg-mcp/node_modules/pg'),
    path.resolve(__dirname, '../../../tools/mcp/dock-pg-mcp/node_modules/pg')
  ];
  for (const c of candidates) {
    try {
      return require(c);
    } catch (e) {
      /* 继续找下一个 */
    }
  }
  throw new Error('找不到 pg 模块：请在仓库根目录运行，或 npm i pg');
}

const { Client } = loadPg();

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, ...rest] = a.replace(/^--/, '').split('=');
    return [k, rest.join('=')];
  })
);

const host = args.host || process.env.PGHOST || '127.0.0.1';
const port = Number(args.port || process.env.PGPORT || 5432);
const database = args.db || process.env.PGDATABASE || 'smartdock';
const user = args.user || process.env.PGUSER || 'postgres';
const password = args.password || process.env.PGPASSWORD || '';
const schemas = (args.schemas || 'public,agentscope').split(',').map((s) => s.trim()).filter(Boolean);
const batching = Number(args.batch || 200);

const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 12);
const outFile =
  args.out || path.resolve(__dirname, `../backups/data-${database}-${stamp}.sql`);
fs.mkdirSync(path.dirname(outFile), { recursive: true });

/** SQL 字面量（standard_conforming_strings=on：反斜杠是普通字符，只需双写单引号） */
function lit(v) {
  if (v === null || v === undefined) return 'NULL';
  if (typeof v === 'number') return Number.isFinite(v) ? String(v) : 'NULL';
  if (typeof v === 'boolean') return v ? 'TRUE' : 'FALSE';
  if (Buffer.isBuffer(v)) return `'\\x${v.toString('hex')}'`;
  if (v instanceof Date) return `'${v.toISOString()}'`;
  if (typeof v === 'object') return `'${JSON.stringify(v).replace(/'/g, "''")}'`;
  return `'${String(v).replace(/'/g, "''")}'`;
}

const ident = (s) => `"${String(s).replace(/"/g, '""')}"`;

(async () => {
  const client = new Client({ host, port, database, user, password, connectionTimeoutMillis: 15000 });
  await client.connect();
  const out = fs.createWriteStream(outFile, { encoding: 'utf8' });
  const w = (s) => out.write(s);

  w(`-- SmartDock 数据导出（只导数据；DDL 由 backend/script/sql/baseline 提供）\n`);
  w(`-- 源：${user}@${host}:${port}/${database}\n`);
  w(`-- 时间：${new Date().toISOString()}\n`);
  w(`-- 恢复：psql -v ON_ERROR_STOP=1 -d <目标库> -f <本文件>\n\n`);
  w(`SET client_encoding = 'UTF8';\n`);
  w(`SET standard_conforming_strings = on;\n`);
  // 关掉外键/触发器检查，表顺序就不必拓扑排序；结束后复位（超级用户才允许，本项目连接即超级用户）
  w(`SET session_replication_role = replica;\n\n`);

  const { rows: tables } = await client.query(
    `select schemaname, tablename, rowsecurity
       from pg_tables
      where schemaname = any($1::text[])
      order by schemaname, tablename`,
    [schemas]
  );

  let totalRows = 0;
  const seqJobs = [];

  for (const t of tables) {
    const full = `${ident(t.schemaname)}.${ident(t.tablename)}`;
    const { rows: cols } = await client.query(
      `select column_name, data_type, is_identity, column_default
         from information_schema.columns
        where table_schema = $1 and table_name = $2
        order by ordinal_position`,
      [t.schemaname, t.tablename]
    );
    if (!cols.length) continue;

    const res = await client.query(`select * from ${full}`);
    const colList = cols.map((c) => ident(c.column_name)).join(', ');
    w(`-- ${t.schemaname}.${t.tablename}：${res.rows.length} 行\n`);
    if (t.rowsecurity === 'YES' || t.rowsecurity === true) {
      w(`-- 注意：该表启用了行级安全（RLS），恢复后请按需重设策略\n`);
    }
    if (res.rows.length) {
      for (let i = 0; i < res.rows.length; i += batching) {
        const chunk = res.rows.slice(i, i + batching);
        const values = chunk
          .map((r) => `(${cols.map((c) => lit(r[c.column_name])).join(', ')})`)
          .join(',\n  ');
        w(`INSERT INTO ${full} (${colList}) VALUES\n  ${values};\n`);
      }
      totalRows += res.rows.length;
      // 有自增/序列列时记一条 setval，避免恢复后插入撞主键
      const seqCols = cols.filter(
        (c) => c.is_identity === 'YES' || (c.column_default || '').startsWith('nextval(')
      );
      for (const c of seqCols) {
        seqJobs.push({ full, col: c.column_name });
      }
    }
    w('\n');
  }

  // 序列对齐：pg_get_serial_sequence 找不到时返回 null，用 COALESCE 兜底跳过
  if (seqJobs.length) {
    w(`-- 序列对齐（恢复后新增行不会撞主键）\n`);
    for (const j of seqJobs) {
      const tblLiteral = `${j.full.replace(/"/g, '')}`.split('.')[0];
      const tblName = j.full.split('.').map((x) => x.replace(/"/g, '')).join('.');
      w(
        `select setval(pg_get_serial_sequence('${tblName}', '${j.col}'), coalesce((select max(${ident(
          j.col
        )}) from ${j.full}), 1), true) where pg_get_serial_sequence('${tblName}', '${j.col}') is not null;\n`
      );
      void tblLiteral;
    }
    w('\n');
  }

  w(`SET session_replication_role = origin;\n`);
  w(`-- 共 ${tables.length} 张表 / ${totalRows} 行\n`);

  await new Promise((r) => out.end(r));
  await client.end();

  const size = fs.statSync(outFile).size;
  console.log(`✓ 导出完成：${outFile}`);
  console.log(`  表 ${tables.length} 张 / 行 ${totalRows} / 体积 ${(size / 1024 / 1024).toFixed(2)} MB`);
})().catch((e) => {
  console.error('✗ 导出失败：', e.message);
  process.exit(1);
});
