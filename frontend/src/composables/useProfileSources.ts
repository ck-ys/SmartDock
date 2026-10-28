import { computed, ref } from 'vue';
import { listProfileSources } from '@/api/dcs/core/profile';

/**
 * 批2 列源单源化: 全部画像/分析页的「数据源」下拉共用一份后端清单
 * (画像系=有主体的源, 分析系=有事件的源), 拉取失败时退回内置兜底清单。
 * 标签只认内置两个真实源(三审 dsflash P2): web/order/supply 是旧演示假源,
 * 真实源恰好撞名时显示原始名, 不再冒出「xx 演示站点」。
 */
export const SOURCE_LABELS: Record<string, string> = {
  dcs: 'dcs 数据采集',
  risk: 'risk 风控'
};

export interface SourceOption {
  label: string;
  value: string;
  count?: number | string;
}

const SUBJECT_FALLBACK: SourceOption[] = [
  { label: SOURCE_LABELS.dcs, value: 'dcs' },
  { label: SOURCE_LABELS.risk, value: 'risk' }
];

// 三审(glm53 P2): 兜底清单只保留内置真实业务源——web/order/supply 是演示假源,
// 数据已清(2026-10-27 零模拟数据), 拉取失败时把它们摆进下拉=可选到死源
const EVENT_FALLBACK: SourceOption[] = [
  { label: SOURCE_LABELS.dcs, value: 'dcs' },
  { label: SOURCE_LABELS.risk, value: 'risk' }
];

const subjectOptions = ref<SourceOption[]>([]);
const eventOptions = ref<SourceOption[]>([]);
/**
 * 真实数据优先(2026-10-27 画像方向重构): 「加载成功但真实清单为空」标记——
 * true 时页面应显示诚实空态+接入引导, 而不是拿内置兜底清单当真源展示(虚列)。
 * 拉取失败不置 true(保持兜底行为)。
 */
const subjectEmpty = ref(false);
const eventEmpty = ref(false);
let inflight: Promise<void> | null = null;

const toOptions = (
  rows: { source: string; subjects?: number | string; events?: number | string; display_name?: string | null }[] | undefined,
  countKey: 'subjects' | 'events'
): SourceOption[] =>
  (rows || []).map((row) => ({
    // 第二批改名: 管理面起的显示名优先, 未配置回退内置标签
    label: row.display_name || SOURCE_LABELS[row.source] || row.source,
    value: row.source,
    count: row[countKey]
  }));

export const ensureSources = (): Promise<void> => {
  if (!inflight) {
    let failed = false;
    inflight = listProfileSources()
      .then((data) => {
        subjectOptions.value = toOptions(data.subjectSources, 'subjects');
        eventOptions.value = toOptions(data.eventSources, 'events');
        subjectEmpty.value = subjectOptions.value.length === 0;
        eventEmpty.value = eventOptions.value.length === 0;
      })
      .catch((e) => {
        // 三审 P2: 失败必须放掉 inflight——否则吞错后这份"兜底 promise"被永久缓存,
        // 后续所有页面都拿内置兜底清单, 真实源再也拉不回来(虚列回归)
        console.warn('[useProfileSources] 源清单拉取失败, 使用兜底清单', e);
        failed = true;
      })
      .finally(() => {
        if (failed) {
          inflight = null;
        }
      });
  }
  return inflight;
};

/**
 * 失效源清单缓存(2026-10-27 三审 dspro P1): 模块态 inflight 成功后永不释放,
 * 「空库→测试发送→回画像页」会一直拿旧缓存, 空态永远走不出去; 切租户同理。
 * 测试发送成功/重新检测空态时调用——下一次 ensureSources 会重新拉清单。
 */
export function invalidateSources() {
  inflight = null;
  subjectOptions.value = [];
  eventOptions.value = [];
  subjectEmpty.value = false;
  eventEmpty.value = false;
}

/**
 * kind: 'subject' 画像系下拉(有主体的源) / 'event' 分析系下拉(有事件的源)。
 * fallback 可覆盖(如运维页要保留 risk)。
 */
export function useSourceOptions(kind: 'subject' | 'event', fallback?: SourceOption[]) {
  const base = kind === 'subject' ? subjectOptions : eventOptions;
  const list = computed<SourceOption[]>(() =>
    base.value.length ? base.value : fallback || (kind === 'subject' ? SUBJECT_FALLBACK : EVENT_FALLBACK)
  );
  return { list, ensureSources };
}

/**
 * 真实数据优先(2026-10-27 画像方向重构): 源清单加载完成后,
 * 当前选中的源不在真实清单里(比如默认值写死了假源)就自动切到清单里的第一个——
 * 只有一个真实源时即「唯一真实源自动选中」。清单为空不动(页面自行显示空态引导)。
 */
export function autoPickSource(list: { value: SourceOption[] }, current: { value: string }, onChange?: () => void) {
  const real = list.value;
  if (!real.length) return;
  if (!real.some((o) => o.value === current.value)) {
    current.value = real[0].value;
    // 程序改值不触发 el-select 的 @change——切换了源必须让页面重拉数据,
    // 否则出现「下拉显示真实源、表格还是旧源数据」的三审 P1
    onChange?.();
  }
}

/** 真实清单为空(加载成功): 画像系/分析系各自查, 页面据此显示诚实空态+接入引导 */
export function useSourceEmpty(kind: 'subject' | 'event') {
  return kind === 'subject' ? subjectEmpty : eventEmpty;
}
