/**
 * 图表取色（A 框架纸 / D 纯黑夜）
 *
 * ECharts 画在 canvas 上，读不到 CSS 变量，所以统一在这里把 --app-* / --el-color-*
 * 读成 JS 值。页面用 useChartTokens() 拿到 computed：主题切换时 computed 重算，
 * 折线、柱、饼、桑基自动换色，不需要刷新页面。
 *
 * 口径：数据系列只用一个强调色（accent），多系列靠同一色的深浅 + 中性灰区分；
 * 红/黄/绿只留给风险等级等语义，不用于普通系列。
 */
import { computed } from 'vue';
import { useSettingsStore } from '@/store/modules/settings';

export interface ChartTokens {
  /** 唯一数据色 */
  accent: string;
  /** 主文字 */
  ink: string;
  /** 次要文字 / 第二系列 */
  muted: string;
  /** 轴刻度文字 */
  faint: string;
  /** 网格线 */
  rule: string;
  /** 轴线 */
  border: string;
  /** 画布底（饼图扇区描边用） */
  surface: string;
  success: string;
  warning: string;
  danger: string;
  /** 多系列折线/柱状：accent → 灰阶 */
  series: string[];
  /** 饼图/环图：同一色由深到浅的六档 */
  ramp: string[];
}

function cssVar(style: CSSStyleDeclaration, name: string, fallback: string): string {
  const value = style.getPropertyValue(name).trim();
  return value || fallback;
}

/** 把 #rrggbb 转成 rgba()；非法值原样返回，宁可色彩不对也不让图表炸 */
export function alpha(hex: string, a: number): string {
  const m = /^#([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) {
    return hex;
  }
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
}

export function readChartTokens(): ChartTokens {
  const root = getComputedStyle(document.documentElement);
  /* 皮肤批（U-24）：主线三色走 --app-chart-1/2/3（= themes.mjs T1/T7 的 c1/c2/c3），
     --app-accent 即 c1；两套皮肤 × 明暗四个组合都在 theme-framed.scss 里声明 */
  const c1 = cssVar(root, '--app-chart-1', cssVar(root, '--app-accent', '#2f6feb'));
  const c2 = cssVar(root, '--app-chart-2', cssVar(root, '--app-muted', '#64748b'));
  const c3 = cssVar(root, '--app-chart-3', '#a9b6c4');
  const muted = cssVar(root, '--app-muted', '#5f7083');
  const faint = cssVar(root, '--app-faint', '#8494a6');
  return {
    accent: c1,
    ink: cssVar(root, '--app-text', '#14202d'),
    muted,
    faint,
    rule: cssVar(root, '--app-rule', '#dce3ec'),
    border: cssVar(root, '--app-border', '#dce3ec'),
    surface: cssVar(root, '--app-sheet-bg', '#ffffff'),
    success: cssVar(root, '--el-color-success', '#1e7b4f'),
    warning: cssVar(root, '--el-color-warning', '#92400e'),
    danger: cssVar(root, '--el-color-danger', '#c4242a'),
    series: [c1, c2, c3, alpha(c1, 0.38), muted],
    ramp: [1, 0.8, 0.62, 0.46, 0.3, 0.16].map((a) => alpha(c1, a))
  };
}

/** 主题/皮肤切换后自动失效的取色器 */
export function useChartTokens() {
  const settings = useSettingsStore();
  return computed<ChartTokens>(() => {
    // 显式依赖明暗与皮肤：html.dark / html[data-skin] 的类与属性切换完成后让 computed 重算；
    // 页面里的 option 引用本 computed，vue-echarts 监听 option 变化自动 setOption 换色
    void settings.dark;
    void settings.skin;
    return readChartTokens();
  });
}
