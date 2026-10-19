import { describe, expect, it } from 'vitest';
import { EMO2IC, IC, emoToSvgHtml, ic } from './icons';

/**
 * 批I-1 图标体系快照锁：字典从原型 L1082/L1086 生成（.codex/gen_*.py），
 * 这里锁「数量下限 + 关键映射 + 渲染产物形态」，防后续手改字典静默丢条或换形。
 */
describe('icons.ts 批I-1 图标地基', () => {
  it('IC 字典规模下限：≥84 枚（原型口径）', () => {
    expect(Object.keys(IC).length).toBeGreaterThanOrEqual(84);
  });
  it('EMO2IC 映射规模下限：≥121 条（原型口径）', () => {
    expect(Object.keys(EMO2IC).length).toBeGreaterThanOrEqual(121);
  });
  it('Q2 六字符映射 + 🛡 照搬原型：📌→map-pin/◎→scale/⌄→arrow-down/☆→star/✦→sparkles/🔐→lock/🛡→power', () => {
    expect(EMO2IC['\u{1F4CC}']).toBe('map-pin');
    expect(EMO2IC['\u25CE']).toBe('scale');
    expect(EMO2IC['\u2323']).toBe('arrow-down');
    expect(EMO2IC['\u2606']).toBe('star');
    expect(EMO2IC['\u2726']).toBe('sparkles');
    expect(EMO2IC['\u{1F510}']).toBe('lock');
    expect(EMO2IC['\u{1F6E1}']).toBe('power');
  });
  it('stamp 通道：🧠/⚡/🛠 均有映射（Composer stampMap 与 TurnBlock ts-t 依赖）', () => {
    expect(EMO2IC['\u{1F9E0}']).toBe('brain');
    expect(EMO2IC['\u26A1']).toBe('zap');
    expect(EMO2IC['\u{1F527}']).toBe('wrench');
  });
  it('ic() 渲染形态：svg.icn + stroke=currentColor + stroke-width 2（原型 .iswap svg 同款）', () => {
    const s = ic('bot');
    expect(s).toContain('<svg');
    expect(s).toContain('class="icn"');
    expect(s).toContain('stroke="currentColor"');
    expect(s).toContain('stroke-width="2"');
    expect(s).toContain(IC['bot']);
  });
  it('未知图标名：ic 返回无 path 的空骨架（IconSpan 渲染空位不炸）', () => {
    const s = ic('no-such-icon');
    expect(s).toContain('<svg');
    expect(s).not.toContain('<path');
    expect(s).not.toContain('<circle');
    expect(s).not.toContain('<rect');
  });
  it('emoToSvgHtml：映射 emoji 替换为 span.iswap，未映射原样保留', () => {
    const html = emoToSvgHtml('🧠 深入 · 🛡 等你确认');
    expect(html).toContain('<span class="iswap">');
    expect(html).toContain(IC['brain']);
    expect(html).toContain(IC['power']);
    expect(html).toContain(' 深入 · ');
    expect(html).toContain('等你确认');
  });
  it('FE0F 变体选择符剥壳后仍命中（⚠️=⚠+FE0F）', () => {
    const html = emoToSvgHtml('⚠️ 逾期 1 项');
    expect(html).toContain(IC['warn']);
    expect(html).not.toContain('\uFE0F');
  });
  it('纯文本零替换：原样返回（快路径不误伤）', () => {
    expect(emoToSvgHtml('普通中文 abc 123')).toBe('普通中文 abc 123');
  });
  // P2 修复（2026-10-09 Q6）：v-html 渲染面防注入——html 特殊字符必须转义，emoji 替换不受影响
  it('含 <>& 的动态文本先转义再替换，伪装标签不被拼进 html', () => {
    const html = emoToSvgHtml('🧠 <img src=x onerror=alert(1)> 任务「A&B」');
    expect(html).not.toContain('<img');
    expect(html).toContain('&lt;img src=x onerror=alert(1)&gt;');
    expect(html).toContain('A&amp;B');
    expect(html).toContain('<span class="iswap">');
  });
});
