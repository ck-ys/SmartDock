import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 字号 / 圆角白名单扫描（2026-11-13 视觉批 Q4 案 1 落地）
 *
 * 为什么要有这个文件：`dcs-tokens.scss` 早就写着「六档字号、四档圆角、禁新增档外值」，
 * 但 2026-11-13 实测多出 12 种字号、17 种圆角——说明「写进文档」这条防线不 work。
 * 这份测试把规矩变成会失败的东西：命中就报 `文件:行`。
 *
 * 只扫两件事（先只开两条规则，别一次上全套）：
 *   1. 字面量 px 的 `font-size`（`var(--dcs-font-*)` 之类的令牌写法一律放行）
 *   2. 字面量 px 的 `border-radius`（含四角简写拆开逐个判；`50%` 这类百分比放行）
 *
 * 存量豁免在 `tokens-baseline.json`：**只减不增**。
 * 第二条用例会检查「基线里有没有已经修掉的残留行」，逼着修完就删，基线不会烂成一张白名单。
 */

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND = path.resolve(HERE, '../../..');
const SRC = path.join(FRONTEND, 'src');
const BASELINE = path.join(HERE, 'tokens-baseline.json');

const FONT_OK = new Set(['12px', '13px', '14px', '15px', '20px', '26px']);
const RADIUS_OK = new Set(['2px', '4px', '8px', '12px', '999px']);

type Hit = { file: string; line: number; kind: 'font' | 'radius'; value: string };

function walk(dir: string, out: string[] = []): string[] {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (/\.(vue|scss)$/.test(e.name)) out.push(p);
  }
  return out;
}

/** 只扫样式区：.vue 取 <style> 块，.scss 全文；返回块内容与它在原文件里的偏移 */
function styleChunks(text: string, isVue: boolean): Array<{ body: string; offset: number }> {
  if (!isVue) return [{ body: text, offset: 0 }];
  const chunks: Array<{ body: string; offset: number }> = [];
  const re = /<style[^>]*>([\s\S]*?)<\/style>/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) {
    const start = m.index + m[0].indexOf('>') + 1;
    chunks.push({ body: m[1], offset: start });
  }
  return chunks;
}

function lineOf(text: string, idx: number): number {
  let n = 1;
  for (let i = 0; i < idx && i < text.length; i++) if (text[i] === '\n') n++;
  return n;
}

function scan(): Hit[] {
  const hits: Hit[] = [];
  for (const abs of walk(SRC)) {
    const rel = path.relative(FRONTEND, abs).replace(/\\/g, '/');
    const text = fs.readFileSync(abs, 'utf8');
    for (const { body, offset } of styleChunks(text, abs.endsWith('.vue'))) {
      for (const m of body.matchAll(/font-size\s*:\s*([^;}\n]+)/g)) {
        const v = m[1].trim().replace(/\s+/g, ' ');
        if (/^\d/.test(v) && !FONT_OK.has(v)) {
          hits.push({ file: rel, line: lineOf(text, offset + (m.index ?? 0)), kind: 'font', value: v });
        }
      }
      for (const m of body.matchAll(/border-radius\s*:\s*([^;}\n]+)/g)) {
        for (const p of m[1].trim().split(/\s+/)) {
          if (/^\d/.test(p) && !RADIUS_OK.has(p)) {
            hits.push({ file: rel, line: lineOf(text, offset + (m.index ?? 0)), kind: 'radius', value: p });
          }
        }
      }
    }
  }
  return hits;
}

const key = (h: Hit) => `${h.file}|${h.kind}|${h.value}`;

describe('设计令牌白名单（字号 / 圆角）', () => {
  it('没有新增越界值（新代码必须用六档字号 / 四档圆角）', () => {
    const baseline = JSON.parse(fs.readFileSync(BASELINE, 'utf8')) as { allow: string[] };
    const allow = new Set(baseline.allow);
    const fresh = scan().filter((h) => !allow.has(key(h)));
    const report = fresh.map((h) => `${h.file}:${h.line}  ${h.kind} = ${h.value}`).join('\n');
    expect(report, `发现 ${fresh.length} 处档外值：\n${report}`).toBe('');
  });

  it('基线里没有已经修掉的残留行（只减不增）', () => {
    const baseline = JSON.parse(fs.readFileSync(BASELINE, 'utf8')) as { allow: string[] };
    const now = new Set(scan().map(key));
    const stale = baseline.allow.filter((k) => !now.has(k));
    expect(stale, `这些越界值已不存在，请从 tokens-baseline.json 删掉：\n${stale.join('\n')}`).toEqual([]);
  });

  it('白名单与基线文件里记的口径一致（防两处漂移）', () => {
    const baseline = JSON.parse(fs.readFileSync(BASELINE, 'utf8')) as {
      fontWhitelist: string[];
      radiusWhitelist: string[];
    };
    expect([...baseline.fontWhitelist].sort()).toEqual([...FONT_OK].sort());
    expect([...baseline.radiusWhitelist].sort()).toEqual([...RADIUS_OK].sort());
  });
});
