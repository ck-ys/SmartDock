import { describe, expect, it } from 'vitest';
import { renderMd } from './md';

/** 批E 视觉审查 FAIL 回归锁：md 表格必须渲染为 <table>（管道符原样输出=乱码不可读） */
describe('renderMd 表格白名单', () => {
  it('标准表格：表头+分隔行+数据行 → table/thead/tbody', () => {
    const html = renderMd('| 任务 | 截止 |\n| --- | --- |\n| 收集表 | 09-30 |');
    expect(html).toContain('<table><thead><tr><th>任务</th><th>截止</th></tr></thead>');
    expect(html).toContain('<tbody><tr><td>收集表</td><td>09-30</td></tr></tbody></table>');
  });

  it('对齐变体分隔行（:---/:---:）与无首尾管道形态都识别；全数字列挂 num 右对齐', () => {
    const html = renderMd('a | b\n:--- | :---:\n1 | 2');
    expect(html).toContain('<th class="num">a</th><th class="num">b</th>');
    expect(html).toContain('<td class="num">1</td><td class="num">2</td>');
  });

  it('表格优化：.tbl-scroll 独立滚动容器 + 混合列不误挂 num + 数字带短单位也识别', () => {
    const html = renderMd('| 部门 | 未交 | 备注 |\n| --- | --- | --- |\n| 信工 | 12人 | 待催 |\n| 冶金 | 3人 | 无 |');
    expect(html).toContain('<div class="tbl-scroll"><table>');
    expect(html).toContain('<th>部门</th><th class="num">未交</th><th>备注</th>');
    expect(html).toContain('<td class="num">12人</td>');
    expect(html).not.toContain('<td class="num">待催</td>');
  });

  it('单元格内行内语法（**粗体**/`code`）照常渲染，HTML 注入被转义', () => {
    const html = renderMd('| 名 | 值 |\n| --- | --- |\n| **合计** | `<x>` |');
    expect(html).toContain('<td><strong>合计</strong></td>');
    expect(html).toContain('<td><code>&lt;x&gt;</code></td>');
    expect(html).not.toContain('<x>');
  });

  it('业务批4 Q12：孤立 ** 清理——配对强调照常，残星不漏给用户', () => {
    const html = renderMd('**正常强调** 与 **开头忘收尾\n第二行');
    expect(html).toContain('<strong>正常强调</strong>');
    expect(html).not.toContain('**');
  });

  it('092：PII 掩码不被残星清理/粗体配对破坏（原样保留，不产"假明文"）', () => {
    const html = renderMd('联系 138****0000 或 139****0000，邮箱 ab****@x.com');
    expect(html).toContain('138****0000');
    expect(html).toContain('139****0000');
    expect(html).toContain('ab****@x.com');
    expect(html).not.toContain('<strong>');
    expect(html).not.toContain('1380000');
  });

  it('092：掩码外的孤立 ** 照常清理；行内代码里的掩码形态原样', () => {
    const html = renderMd('138****0000 **未收尾强调；示例 `138****0000`');
    expect(html).toContain('138****0000');
    expect(html).toContain('<code>138****0000</code>');
    expect(html).not.toContain('**未收尾');
  });

  it('092：表格单元格里掩码原样（cell 渲染链路同样受保护）', () => {
    const html = renderMd('| 联系人 | 电话 |\n| --- | --- |\n| 张三 | 138****0000 |');
    expect(html).toContain('<td>138****0000</td>');
  });

  it('业务批4 Q12：状态词升格 cell-tag（四色口径），非状态词纯文本', () => {
    const html = renderMd(
      '| 任务 | 状态 |\n| --- | --- |\n| 收集表 | 已完成 |\n| 汇总表 | 已逾期 |\n| 随访表 | 进行中 |\n| 归档表 | 未开始 |\n| 通知 | 发出去了 |'
    );
    expect(html).toContain('<span class="cell-tag ct-ok">已完成</span>');
    expect(html).toContain('<span class="cell-tag ct-bad">已逾期</span>');
    expect(html).toContain('<span class="cell-tag ct-mid">进行中</span>');
    expect(html).toContain('<span class="cell-tag ct-dim">未开始</span>');
    expect(html).toContain('<td>发出去了</td>');
  });

  it('业务批4 Q5-A 表头感知：非状态列头（备注/说明）下的整词状态格不再误染色', () => {
    const html = renderMd('| 任务 | 状态 | 备注 |\n| --- | --- | --- |\n| 收集表 | 已完成 | 完成 |\n| 汇总表 | 已逾期 | 还差两个班 |');
    // 状态列照常升格
    expect(html).toContain('<span class="cell-tag ct-ok">已完成</span>');
    expect(html).toContain('<span class="cell-tag ct-bad">已逾期</span>');
    // 备注列整格恰好=「完成」回到纯文本（不再误染色）
    expect(html).toContain('<td>完成</td>');
    expect(html).not.toContain('<span class="cell-tag ct-ok">完成</span>');
    expect(html).toContain('<td>还差两个班</td>');
  });

  it('业务批4 Q5-A 表头感知：变体列头（当前进度/审核结果）同样升格', () => {
    const html = renderMd('| 事项 | 当前进度 | 审核结果 |\n| --- | --- | --- |\n| 甲 | 进行中 | 通过 |');
    expect(html).toContain('<span class="cell-tag ct-mid">进行中</span>');
    expect(html).toContain('<span class="cell-tag ct-ok">通过</span>');
  });

  it('m3 回修：含状态词但非结尾的列头（情况说明/结果备注）不误染色', () => {
    const html = renderMd('| 事项 | 情况说明 | 结果备注 |\n| --- | --- | --- |\n| 甲 | 完成 | 已逾期 |');
    expect(html).toContain('<td>完成</td>');
    expect(html).not.toContain('<span class="cell-tag ct-ok">完成</span>');
    expect(html).toContain('<td>已逾期</td>');
    expect(html).not.toContain('<span class="cell-tag ct-bad">已逾期</span>');
  });

  it('m3 回修：任务名列下含状态词的任务名不染色（表头感知防误伤）', () => {
    const html = renderMd('| 任务 | 状态 |\n| --- | --- |\n| 已完成事项清理 | 已报完 |');
    expect(html).toContain('<td>已完成事项清理</td>'); // 纯文本，非 cell-tag
    expect(html).not.toContain('ct-ok">已完成事项清理');
    expect(html).toContain('<span class="cell-tag ct-ok">已报完</span>');
  });

  it('m3 回修：已终止对齐系统色板改中性灰，已退修/待发布/待完成按色板补齐', () => {
    const html = renderMd('| 任务 | 状态 |\n| --- | --- |\n| 关闭表 | 已终止 |\n| 修订表 | 已退修 |\n| 排期表 | 待发布 |\n| 装配表 | 待完成 |');
    expect(html).toContain('<span class="cell-tag ct-dim">已终止</span>');
    expect(html).not.toContain('ct-bad">已终止');
    expect(html).toContain('<span class="cell-tag ct-bad">已退修</span>');
    expect(html).toContain('<span class="cell-tag ct-mid">待发布</span>');
    expect(html).toContain('<span class="cell-tag ct-dim">待完成</span>');
  });

  it('孤立管道行（无分隔行跟随）不吞——按普通段落渲染', () => {
    const html = renderMd('| 这不是表格 |');
    expect(html).toContain('<p>| 这不是表格 |</p>');
    expect(html).not.toContain('<table');
  });

  it('097：分隔行列数与表头不一致（正文含管道后跟分割线）不升格表格', () => {
    const html = renderMd('订单号 | 20260001 已处理\n---');
    expect(html).not.toContain('<table');
    expect(html).toContain('<p>订单号 | 20260001 已处理</p>');
  });

  it('表格后的正文行不被表格块吃掉', () => {
    const html = renderMd('| a |\n| --- |\n| 1 |\n\n表格下面这段要正常。');
    expect(html).toContain('<table');
    expect(html).toContain('<p>表格下面这段要正常。</p>');
  });
});

/** 批C 代码卡：围栏首行语言名上卡条 + 复制钮（宿主事件委托取 code 文本） */
describe('renderMd 代码卡', () => {
  it('带语言围栏 → cc 容器 + 卡条语言名 + 复制钮 + pre/code', () => {
    const html = renderMd('```sql\nSELECT 1;\n```');
    expect(html).toContain(
      '<div class="cc"><div class="cc-bar"><span>sql</span><button class="cc-copy" type="button">复制</button></div><pre><code>'
    );
    expect(html).toContain('SELECT 1;');
    expect(html).toContain('</code></pre></div>');
  });

  it('无语言围栏 → 卡条回退「代码」；未闭合围栏结尾补齐闭合标签', () => {
    const html = renderMd('```\nplain\n');
    expect(html).toContain('<span>代码</span>');
    expect(html).toContain('</code></pre></div>');
  });
});

/** 批3 站内直达链白名单/任务卡/表格容器（安全边界回归锁：转义后白名单替换，注入面为零） */
describe('renderMd 站内链与任务卡（批3）', () => {
  it('白名单前缀转 a[data-dcs-link]（无 href 属性，防中键/右键绕过白名单）', () => {
    const html = renderMd('看[我的待办](/dcs/todo)');
    expect(html).toContain('<a class="dcs-link" role="link" tabindex="0" data-dcs-link="/dcs/todo">我的待办</a>');
    expect(html).not.toContain('href');
  });

  it('非白名单目标（外链/js 伪协议/站外路径/未知 dcs 子路径）一律降级纯文本', () => {
    const html = renderMd('a [x](https://evil.com) b [y](javascript:alert(1)) c [z](/admin) d [w](/dcs/other)');
    expect(html).not.toContain('<a');
    expect(html).toContain('[x](https://evil.com)');
  });

  it('title 元信息白名单内可带（行内混排场景）；含 & 或事件属性的整体降级', () => {
    const ok = renderMd('看 [名](/dcs/mission/detail/2092652290059091970 "截止 10-12 · 进度 3/5") 详情');
    expect(ok).toContain('title="截止 10-12 · 进度 3/5"');
    const bad = renderMd('看 [名](/dcs/todo "a&b")');
    expect(bad).not.toContain('<a');
    // meta 值进 title 属性是纯文本 tooltip（转义后文本上捕获，无 <>"& 无逃逸面），
    // 含事件样式的字面量也不构成注入——锁住「降级只发生在字符集越界时」
    const weird = renderMd('看 [名](/dcs/todo "onmouseover=x")');
    expect(weird).toContain('title="onmouseover=x"');
  });

  it('图片语法 ![x](/dcs/todo) 不转链（! 守卫，整段降级纯文本）', () => {
    const html = renderMd('看 ![图](/dcs/todo)');
    expect(html).not.toContain('<a');
    expect(html).toContain('![图](/dcs/todo)');
  });

  it('行内代码里的链语法不转链（占位符保护，code 内容原样）', () => {
    const html = renderMd('路径是 `[x](/dcs/todo)` 这样');
    expect(html).toContain('<code>[x](/dcs/todo)</code>');
    expect(html).not.toContain('dcs-link');
  });

  it('任务卡：独占段一条详情链（可带 meta）→ 卡片+打开详情钮；行内混排保持文字链', () => {
    const card = renderMd('[期末检查](/dcs/mission/detail/2092652290059091970 "截止 10-12 18:00 · 进度 3/5")');
    expect(card).toContain('class="task-card"');
    expect(card).toContain('data-dcs-link="/dcs/mission/detail/2092652290059091970"');
    expect(card).toContain('打开详情');
    const inlineCase = renderMd('先看 [期末检查](/dcs/mission/detail/2092652290059091970) 再说');
    expect(inlineCase).not.toContain('task-card');
    expect(inlineCase).toContain('dcs-link');
  });

  it('表格渲染进 .tbl 容器并带复制钮（宿主委托转 TSV）', () => {
    const html = renderMd('| a | b |\n| --- | --- |\n| 1 | 2 |');
    expect(html).toContain('class="tbl"');
    expect(html).toContain('tbl-copy');
  });
});

/** 批4 Q5 ```chart 协议：合法 JSON → 占位 div（宿主挂 ECharts）；畸形降级代码卡 */
describe('renderMd chart 围栏（批4）', () => {
  it('合法 spec → .dcs-chart 占位（data-chart 属性含压缩 JSON，无 canvas 假象）', () => {
    const html = renderMd('```chart\n{"type":"bar","title":"各部门未交","data":[{"name":"计算机","value":3},{"name":"机械","value":1}]}\n```');
    expect(html).toContain('<div class="dcs-chart" data-chart="{');
    expect(html).toContain('&quot;type&quot;:&quot;bar&quot;');
    expect(html).not.toContain('<canvas');
    expect(html).not.toContain('<pre>');
  });

  it('畸形 JSON → 降级普通代码卡（chart（格式错误）标签 + 原文展示，不炸不吞）', () => {
    const html = renderMd('```chart\n{type: bar 缺引号}\n```');
    expect(html).toContain('chart（格式错误）');
    expect(html).toContain('<pre><code>');
    expect(html).toContain('{type: bar 缺引号}');
    expect(html).not.toContain('dcs-chart');
  });

  it('未闭合 chart 围栏（流中断）也降级代码卡，不悬挂', () => {
    const html = renderMd('```chart\n{"type":"pie"');
    expect(html).toContain('chart（格式错误）');
    expect(html).toContain('</code></pre></div>');
  });

  it('普通代码围栏不受 chart 分支影响（回归）', () => {
    const html = renderMd('```sql\nSELECT 1;\n```');
    expect(html).toContain('<span>sql</span>');
    expect(html).toContain('cc-copy');
  });
});
