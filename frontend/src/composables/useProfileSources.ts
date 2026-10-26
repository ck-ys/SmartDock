import { computed, ref } from 'vue';
import { listProfileSources } from '@/api/dcs/core/profile';

/**
 * 批2 列源单源化: 全部画像/分析页的「数据源」下拉共用一份后端清单
 * (画像系=有主体的源, 分析系=有事件的源), 拉取失败时退回内置兜底清单。
 */
export const SOURCE_LABELS: Record<string, string> = {
  dcs: 'dcs 数据采集',
  risk: 'risk 风控',
  web: 'web 演示站点',
  order: 'order 订单',
  supply: 'supply 供应链'
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

const EVENT_FALLBACK: SourceOption[] = [
  { label: SOURCE_LABELS.web, value: 'web' },
  { label: SOURCE_LABELS.dcs, value: 'dcs' },
  { label: SOURCE_LABELS.order, value: 'order' },
  { label: SOURCE_LABELS.supply, value: 'supply' }
];

const subjectOptions = ref<SourceOption[]>([]);
const eventOptions = ref<SourceOption[]>([]);
let inflight: Promise<void> | null = null;

const toOptions = (
  rows: { source: string; subjects?: number | string; events?: number | string }[] | undefined,
  countKey: 'subjects' | 'events'
): SourceOption[] =>
  (rows || []).map((row) => ({
    label: SOURCE_LABELS[row.source] || row.source,
    value: row.source,
    count: row[countKey]
  }));

const ensureSources = (): Promise<void> => {
  if (!inflight) {
    let failed = false;
    inflight = listProfileSources()
      .then((data) => {
        subjectOptions.value = toOptions(data.subjectSources, 'subjects');
        eventOptions.value = toOptions(data.eventSources, 'events');
      })
      .catch(() => {
        // 三审 P2: 失败必须放掉 inflight——否则吞错后这份"兜底 promise"被永久缓存,
        // 后续所有页面都拿内置兜底清单, 真实源再也拉不回来(虚列回归)
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
