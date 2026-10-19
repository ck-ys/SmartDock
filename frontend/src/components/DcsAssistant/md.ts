/**
 * AI 正文迷你 markdown 渲染（批E，原型 mdLite 翻译）。
 *
 * <p>口径：先整体 HTML 转义（textContent 语义，天然防注入），再做白名单标签替换。
 * 支持：代码块 / 行内代码 / 粗体 / h4-h5 标题 / 无序列表 / 引用 / 换行 / 表格 / 站内直达链。
 * 链接白名单（批3 Q6）：仅 [text](/dcs/...) 且前缀∈{mission/detail/数字, mission, todo,
 * template, dashboard} 才转 <a data-dcs-link>（宿主事件委托导航）——外链/js 伪协议/图片 !
 * 前缀/畸形链接一律不转可点，降级为纯文本；整段只含一条任务详情链时升格为任务卡（Q7-1）。
 * 行内代码内容先换占位符再跑链/粗体替换（批3回修：code 里的链语法不该变成可点链）。</p>
 * 图片等富元素不支持——工作面信息密度优先。表格=AI 数据回答刚需，批3 起包 .tbl 容器
 * 带「复制」钮（TSV 进剪贴板，Q7-2），窄容器横向滚。批4 Q5：```chart 围栏（简化 JSON，
 * type∈bar/line/pie/gauge）转 .dcs-chart 占位 div 由宿主挂 ECharts，畸形 JSON 降级代码卡。</p>
 */

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/** 站内链白名单（在转义后的文本上匹配：标题引号已变 &quot;；URL 字符集不含引号/尖括号，
 *  产物直接可进属性）。meta=title 元信息，任务卡第二行灰字用。 */
const DCS_LINK_DEST = '(\\/dcs\\/mission\\/detail\\/\\d{6,25}|\\/dcs\\/mission|\\/dcs\\/todo|\\/dcs\\/template|\\/dcs\\/dashboard)';
const DCS_LINK_RE = new RegExp('(?<!!)\\[([^\\]\\n]{1,120})\\]\\(' + DCS_LINK_DEST + '(?:\\s+&quot;([^&\\n]{0,160})&quot;)?\\)', 'g');
/** 任务卡形态：整段恰好一条任务详情链（独占段落才升格卡片，行内混排仍是文字链） */
const TASK_CARD_RE = new RegExp('^\\[([^\\]\\n]{1,120})\\]\\((\\/dcs\\/mission\\/detail\\/\\d{6,25})(?:\\s+&quot;([^&\\n]{0,160})&quot;)?\\)$');

/** 行内白名单：行内代码 / 粗体 / 站内链（先转义后替换，替换产物不受再转义影响；
 *  行内代码先换成 \x00N\x00 占位符，链/粗体替换不吞 code 内容，最后还原成 <code>） */
function inline(s: string): string {
  const codes: string[] = [];
  const masks: string[] = [];
  const stashMask = (m: string) => {
    masks.push(m);
    return `\x01${masks.length - 1}\x01`;
  };
  return (
    s
      .replace(/`([^`\n]+)`/g, (_m, c: string) => {
        codes.push(c);
        return `\x00${codes.length - 1}\x00`;
      })
      // 092：后端 PII 掩码先抽 \x01N\x01 占位符再跑加粗/残星管线——否则 138****0000 被
      // 残星清理吃成 1380000「假明文」、同行两掩码被 BOLD_RE 跨配成 <strong>。两形态=
      // 数字夹星（手机/证件）与字母数字夹星尾随 @（邮箱前缀）；CJK 姓名掩码（张**）与
      // 合法加粗闭合符结构不可辨，不在此列（掩码契约留档：PiiFilter 勿新增含 ** 掩码形态）
      .replace(/\d\*{2,}\d/g, stashMask)
      .replace(/[A-Za-z0-9]\*{2,}@/g, stashMask)
      .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
      // 业务批4 Q12 基线修：模型偶发输出孤立 **（开头强调忘收尾），配对替换后残星
      // 原样漏给用户看——清掉（合法强调已全被上一步配对消费，此处只剩星号噪音）。
      // m3 P2-1 核验后维持全清：教学示例的 ** 都裹在行内代码里（上方 codes 占位已
      // 先行抽走，不进本链）；无反引号的成对 ** 会被配对消费——"词中裸星"无真实场景。
      // PII 掩码同样已在上方抽走（092），本链只清真正的星号噪音
      .replace(/\*\*/g, '')
      .replace(DCS_LINK_RE, (_m, text: string, url: string, meta?: string) =>
        meta
          ? `<a class="dcs-link" role="link" tabindex="0" data-dcs-link="${url}" title="${meta}">${text}</a>`
          : `<a class="dcs-link" role="link" tabindex="0" data-dcs-link="${url}">${text}</a>`
      )
      .replace(/\x00(\d+)\x00/g, (_m, i: string) => `<code>${codes[Number(i)]}</code>`)
      .replace(/\x01(\d+)\x01/g, (_m, i: string) => masks[Number(i)])
  );
}

/** 表格状态词 → 小标签（业务批4 Q12 基线修：状态列一串灰字挤在一起分不清，
 *  恰为整格状态词时升格彩色 tag；映射与 statusColor 人话口径对齐，未命中=纯文本） */
const CELL_TAG: Record<string, string> = {
  // 绿：完成/通过/可用
  '已完成': 'ok',
  // 已提交=SUBMITTED 色板是蓝(primary)，但 COMPLETED 对用户显示「已提交(最终态)」
  // （traceStatusPresentation 有意设计）——绿=终态达成口径，与任务列表一致，非对齐错误
  '已提交': 'ok',
  '完成': 'ok',
  '已通过': 'ok',
  '通过': 'ok',
  '可用': 'ok',
  '已接受': 'ok',
  '已发布': 'ok',
  // 绿：声明口径终态（DECLARED_DONE=已报完，m3 席补词 2026-10-13）
  '已报完': 'ok',
  // 红：逾期/退回/失败（RETURNED=danger 系）
  '已逾期': 'bad',
  '逾期': 'bad',
  '已退修': 'bad',
  '已退回': 'bad',
  '退回': 'bad',
  '失败': 'bad',
  '不可用': 'bad',
  '已驳回': 'bad',
  // 橙：在途/待办（PENDING_PUBLISH/REVISING=warning 系，m3 席补词）
  '进行中': 'mid',
  '填写中': 'mid',
  '待审核': 'mid',
  '待审批': 'mid',
  '待提交': 'mid',
  '待填写': 'mid',
  '待发布': 'mid',
  '待修改': 'mid',
  '审核中': 'mid',
  '生成中': 'mid',
  '已转发': 'mid',
  // 灰：未开始/草稿/中性态——已终止=TERMINATED 是正常关闭非异常（statusColor=info 灰；
  // m3 P1-1 回修：原误标红与系统色板打架，用户会误以为系统出错）
  '未开始': 'dim',
  '未提交': 'dim',
  '草稿': 'dim',
  '已归档': 'dim',
  '已停用': 'dim',
  '已终止': 'dim',
  '终止': 'dim',
  '待完成': 'dim'
};

/** 状态类列头判定（业务批4 Q5-A 表头感知，2026-10-13 拍板）：列头**以这些词结尾**的列，
 *  整词状态格才升格彩色 tag——「备注」列整格恰好写了「完成」不再误染色（md 表格本无列语义，
 *  靠列头文字近似）；m3 P1 回修改结尾锚定：「情况说明/结果备注」含状态词但非状态列不误染，
 *  「当前状态/进度情况/审核结果」等变体仍命中 */
const STATUS_HEADER_RE = /(状态|进度|情况|结果)\s*$/;

/** 单元格渲染：状态列头下的状态词升格 tag；长文本（>12 字）挂原生 title（窄屏省略号悬停看全文，
 *  ↓避让配套）——短格保持裸文本不包 span（省 DOM，统计表数字格照旧紧凑） */
function cell(content: string, header: string): string {
  const tag = CELL_TAG[content.trim()];
  if (tag && !content.includes('<') && STATUS_HEADER_RE.test(header)) {
    return `<span class="cell-tag ct-${tag}">${content.trim()}</span>`;
  }
  if (content.length > 12 && !content.includes('<')) {
    return `<span title="${content}">${content}</span>`;
  }
  return content;
}

/** 拆单元格：`| a | b |` → ['a','b']（容忍首尾管道缺失与单元格内转义后的内容） */
function cells(line: string): string[] {
  let s = line.trim();
  if (s.startsWith('|')) {
    s = s.slice(1);
  }
  if (s.endsWith('|')) {
    s = s.slice(0, -1);
  }
  return s.split('|').map((c) => c.trim());
}

/** 表格分隔行：`| --- | :---: |`（至少一列有连字符） */
function isDivider(line: string): boolean {
  if (!line.includes('-')) {
    return false;
  }
  const cs = cells(line);
  return cs.length > 0 && cs.every((c) => /^:?-{2,}:?$/.test(c));
}

/** 表格数据行：至少 1 个管道分隔（单列无管道的行不算） */
function isRow(line: string): boolean {
  const t = line.trim();
  return t.length > 0 && t.includes('|') && cells(t).length >= 1;
}

/** 渲染为 HTML 片段（流式期不用此函数——完成态才全量解析，S-23） */
export function renderMd(text: string): string {
  const lines = esc(text ?? '').split('\n');
  const out: string[] = [];
  let inCode = false;
  let inList = false;
  let inQuote = false;
  const closeList = () => {
    if (inList) {
      out.push('</ul>');
      inList = false;
    }
  };
  const closeQuote = () => {
    if (inQuote) {
      out.push('</blockquote>');
      inQuote = false;
    }
  };
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith('```')) {
      closeList();
      closeQuote();
      if (inCode) {
        out.push('</code></pre></div>');
        inCode = false;
      } else {
        const lang = line.slice(3).trim();
        // 批4 Q5 图表协议：```chart 围栏收 JSON 体 → 占位 div（宿主挂 ChartBlock/ECharts）。
        // 体在整体转义后的文本上收集，parse 前反转义；畸形 JSON 降级普通代码卡（不炸不吞）
        if (lang === 'chart') {
          let j = i + 1;
          const body: string[] = [];
          while (j < lines.length && !lines[j].startsWith('```')) {
            body.push(lines[j]);
            j++;
          }
          const unescaped = body
            .join('\n')
            .replace(/&quot;/g, '"')
            .replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>')
            .replace(/&amp;/g, '&');
          let spec: unknown;
          try {
            spec = JSON.parse(unescaped);
          } catch {
            spec = undefined;
          }
          if (spec && typeof spec === 'object') {
            out.push(`<div class="dcs-chart" data-chart="${esc(JSON.stringify(spec))}"></div>`);
          } else {
            // 回修 P2：补复制钮（与普通代码卡同款）——畸形场景正是用户最需要抄原文修正的时候
            out.push(
              `<div class="cc"><div class="cc-bar"><span>chart（格式错误）</span><button class="cc-copy" type="button">复制</button></div><pre><code>`
            );
            out.push(...body);
            out.push('</code></pre></div>');
          }
          i = j;
          continue;
        }
        // 代码卡（批C）：围栏首行语言名上卡条，配「复制」钮（点击由宿主事件委托处理）
        out.push(
          `<div class="cc"><div class="cc-bar"><span>${lang || '代码'}</span><button class="cc-copy" type="button">复制</button></div><pre><code>`
        );
        inCode = true;
      }
      continue;
    }
    if (inCode) {
      out.push(line);
      continue;
    }
    // 表格块：表头行 + 分隔行 + 0..n 数据行（向 lookahead 取行，不消费非表格行）。
    // 097：分隔行列数须与表头一致才升格——正文行恰好含管道、后跟 md 分割线（---）时
    // 列数对不上，升格会把正文吃成错位表格；GFM 同口径
    if (isRow(line) && i + 1 < lines.length && isDivider(lines[i + 1]) && cells(lines[i + 1]).length === cells(line).length) {
      closeList();
      closeQuote();
      const head = cells(line);
      let j = i + 2;
      const rows: string[][] = [];
      while (j < lines.length && isRow(lines[j])) {
        rows.push(cells(lines[j]));
        j++;
      }
      // 数字列探测（表格优化 2026-10-11）：跳过表头，某列正文全为数字（可带 %/人/次 等短单位）
      // → 该列 th/td 挂 num 类右对齐 + 等宽数字，统计表个位可比；全空列不算
      const NUM_CELL = /^\s*[-+]?[\d,.，．]+\s*(%|‰|人|条|次|个|天|时|分|秒|元|名|项)?\s*$/;
      const numCols: boolean[] = head.map((_, ci) => {
        const col = rows.map((r) => r[ci] ?? '').filter((c) => c.trim() !== '');
        return col.length > 0 && col.every((c) => NUM_CELL.test(c));
      });
      const ths = head.map((c, ci) => `<th${numCols[ci] ? ' class="num"' : ''}>${inline(c)}</th>`).join('');
      const trs = rows
        .map((r) => `<tr>${r.map((c, ci) => `<td${numCols[ci] ? ' class="num"' : ''}>${cell(inline(c), head[ci] ?? '')}</td>`).join('')}</tr>`)
        .join('');
      // 表格优化（参考 ZCode markdown-table）：宽度由内容决定（fit-content 动态收缩，
      // 窄表不再 width:100% 强拉出大片空白），外层 .tbl-scroll 独立承载横向滚动——
      // table 不再 display:block（块化会丢表格布局语义）；复制钮沿用 .tbl-bar
      out.push(
        `<div class="tbl"><div class="tbl-bar"><span>表格</span><button class="tbl-copy" type="button">复制</button></div><div class="tbl-scroll"><table><thead><tr>${ths}</tr></thead><tbody>${trs}</tbody></table></div></div>`
      );
      i = j - 1;
      continue;
    }
    const h = /^(#{1,5})\s+(.*)$/.exec(line);
    if (h) {
      closeList();
      closeQuote();
      const level = Math.max(4, Math.min(5, h[1].length + 3)); // 原型降档：h4/h5
      out.push(`<h${level}>${inline(h[2])}</h${level}>`);
      continue;
    }
    const li = /^[-*]\s+(.*)$/.exec(line);
    if (li) {
      closeQuote();
      if (!inList) {
        out.push('<ul>');
        inList = true;
      }
      out.push(`<li>${inline(li[1])}</li>`);
      continue;
    }
    const bq = /^>\s?(.*)$/.exec(line);
    if (bq) {
      closeList();
      if (!inQuote) {
        out.push('<blockquote>');
        inQuote = true;
      }
      out.push(inline(bq[1]));
      continue;
    }
    closeList();
    closeQuote();
    if (line.trim() === '') {
      out.push('');
      continue;
    }
    // 任务卡（Q7-1）：整段恰好一条任务详情链（可带 title 元信息）→ 块级卡片+「打开详情」钮
    const card = TASK_CARD_RE.exec(line.trim());
    if (card) {
      const meta = card[3] ? `<div class="tc-meta">${card[3]}</div>` : '';
      out.push(
        `<div class="task-card"><div class="tc-info"><div class="tc-head"><span class="tc-dot"></span>` +
          `<a class="tc-title" role="link" tabindex="0" data-dcs-link="${card[2]}">${card[1]}</a></div>${meta}</div>` +
          `<button class="tc-btn" type="button" data-dcs-link="${card[2]}">打开详情</button></div>`
      );
      continue;
    }
    out.push(`<p>${inline(line)}</p>`);
  }
  if (inCode) {
    out.push('</code></pre></div>');
  }
  closeList();
  closeQuote();
  return out.join('\n');
}
