<template>
  <el-card shadow="never" class="dcs-ops-table-card">
    <div class="ui-toolbar ab-toolbar">
      <!-- 深审回修(P2-3): 积分账维度需 smartdock:aicredit:list，无权限不亮入口（亮了也是 403 空表） -->
      <div v-if="permCredit" class="ab-dim-switch" role="tablist" aria-label="账本维度">
        <button type="button" class="ab-dim" :class="{ on: dim === 'wallet' }" @click="switchDim('wallet')">钱包账（¥）</button>
        <button type="button" class="ab-dim" :class="{ on: dim === 'credit' }" @click="switchDim('credit')">积分账（分）</button>
      </div>
      <el-button :icon="Refresh" circle aria-label="刷新流水" @click="reload" />
    </div>

    <!-- 钱包账（¥）：原 tenant-wallet-ledger 迁移 -->
    <template v-if="dim === 'wallet'">
      <!-- Q5：余额头 —— 「还剩多少」提到页签顶部，不用切到总览页签 -->
      <div class="ledger-hero">
        <div class="ledger-hero__main">
          <span class="ledger-hero__label">可用余额</span>
          <strong class="ledger-hero__value">{{ money(walletAvailable) }}</strong>
          <span class="ledger-hero__sub">{{ walletHeroHint }}</span>
        </div>
        <div class="ledger-hero__side">
          <div class="ledger-hero__cell">
            <span class="ledger-hero__cell-label">当前筛选 · 收入</span>
            <strong class="ledger-hero__cell-value amount-in">+{{ money(summary.totalIn) }}</strong>
          </div>
          <div class="ledger-hero__cell">
            <span class="ledger-hero__cell-label">当前筛选 · 支出</span>
            <strong class="ledger-hero__cell-value amount-out">-{{ money(summary.totalOut) }}</strong>
          </div>
          <div class="ledger-hero__cell">
            <span class="ledger-hero__cell-label">净额</span>
            <strong class="ledger-hero__cell-value" :class="summary.net >= 0 ? 'amount-in' : 'amount-out'">{{ money(summary.net) }}</strong>
          </div>
        </div>
      </div>

      <!-- Q5：筛选条 —— 日期预设 + 自定义区间 + 类型快选；Q6：手机档收进「筛选」抽屉 -->
      <div class="ui-toolbar ledger-filter-bar">
        <div class="ledger-presets">
          <button
            v-for="p in DATE_PRESETS"
            :key="p.key"
            type="button"
            class="ledger-preset"
            :class="{ on: activePreset === p.key }"
            @click="applyPreset(p.key)"
          >
            {{ p.label }}
          </button>
          <el-date-picker
            v-model="walletQuery.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="~"
            start-placeholder="开始"
            end-placeholder="结束"
            class="ledger-range"
            @change="onDateChange"
          />
        </div>
        <div class="ledger-filter-actions">
          <el-select v-model="walletQuery.direction" clearable placeholder="全部方向" style="width: 118px" @change="handleQuery">
            <el-option label="收入" value="IN" />
            <el-option label="支出" value="OUT" />
          </el-select>
          <el-select v-model="walletQuery.bizType" clearable placeholder="全部类型" style="width: 128px" @change="handleQuery">
            <el-option v-for="(label, key) in BIZ_TYPE_TEXT" :key="key" :label="label" :value="key" />
          </el-select>
          <el-input
            v-model="walletQuery.serviceCode"
            placeholder="服务编码"
            clearable
            style="width: 150px"
            @keyup.enter="handleQuery"
          />
          <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
        </div>
      </div>

      <!-- 高级筛选（租户ID/钱包ID 是排障字段，默认收起；Q8：技术参数不挡主路径） -->
      <el-collapse v-model="advancedOpen" class="ledger-advanced">
        <el-collapse-item name="advanced">
          <template #title><span class="ledger-advanced__title">技术参数（排障用：按租户 / 钱包精确筛选）</span></template>
          <el-form :model="walletQuery" :inline="true" class="search-form" @submit.prevent>
            <el-form-item label="租户ID">
              <el-input v-model="walletQuery.tenantId" placeholder="租户ID" clearable style="width: 160px" @keyup.enter="handleQuery" />
            </el-form-item>
            <el-form-item label="钱包ID">
              <el-input v-model="walletQuery.walletId" placeholder="钱包ID" clearable style="width: 160px" @keyup.enter="handleQuery" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :icon="Search" @click="handleQuery">搜索</el-button>
            </el-form-item>
          </el-form>
        </el-collapse-item>
      </el-collapse>

      <!-- Q3：按日期分组的账本（桌面 + 手机同一份数据，手机档换卡片样式） -->
      <div v-loading="loading" class="ledger-days">
        <div v-if="walletDays.length === 0" class="ui-empty">
          <p class="ui-empty__title">这段时间没有流水</p>
          <p class="ui-empty__hint">换个日期范围或清掉筛选条件再试。</p>
        </div>
        <section v-for="day in walletDays" :key="day.day || 'unknown'" class="ledger-day">
          <header class="ledger-day__head">
            <span class="ledger-day__date">{{ day.label }}</span>
            <span class="ledger-day__weekday">{{ day.weekday }}</span>
            <span class="ledger-day__sums">
              <span v-if="day.totalIn > 0" class="amount-in">+{{ money(day.totalIn) }}</span>
              <span v-if="day.totalOut > 0" class="amount-out">-{{ money(day.totalOut) }}</span>
              <span class="ledger-day__count">{{ day.rows.length }} 笔</span>
            </span>
          </header>
          <div class="ledger-day__rows">
            <div v-for="row in day.rows" :key="row.ledgerId" class="ledger-row">
              <span class="ledger-row__time">{{ timeOf(row.createTime) }}</span>
              <span class="ledger-row__name">{{ rowSummary(row) }}</span>
              <span class="ui-pill ledger-row__biz">{{ bizTypeLabel(row.bizType) }}</span>
              <span class="ledger-row__amount" :class="row.direction === 'IN' ? 'amount-in' : 'amount-out'">
                {{ row.direction === 'IN' ? '+' : '-' }}{{ money(row.amount) }}
              </span>
              <span class="ledger-row__balance">余额 {{ money(row.balanceAfter) }}</span>
              <!-- Q3：流水ID / 钱包ID 收进「详情」，默认不占宽度 -->
              <el-popover placement="left" :width="260" trigger="click">
                <template #reference>
                  <el-button link class="ui-row-action ledger-row__more">详情</el-button>
                </template>
                <div class="ledger-detail">
                  <div><span>流水ID</span><b>{{ row.ledgerId }}</b></div>
                  <div><span>租户ID</span><b>{{ row.tenantId }}</b></div>
                  <div><span>钱包ID</span><b>{{ row.walletId }}</b></div>
                  <div v-if="row.idempotencyKey"><span>幂等键</span><b class="ledger-detail__wrap">{{ row.idempotencyKey }}</b></div>
                </div>
              </el-popover>
            </div>
          </div>
        </section>
      </div>

      <!-- Q6：手机档（≤992）沿用通用卡片，补「当天小计」 -->
      <div class="dcs-mobile-list">
        <div v-if="walletDays.length === 0" class="ui-empty">
          <p class="ui-empty__title">这段时间没有流水</p>
          <p class="ui-empty__hint">换个日期范围或清掉筛选条件再试。</p>
        </div>
        <template v-for="day in walletDays" :key="day.day || 'unknown'">
          <div class="ledger-mobile-day">
            <span class="ledger-mobile-day__date">{{ day.label }} {{ day.weekday }}</span>
            <span class="ledger-mobile-day__sums">
              <span v-if="day.totalOut > 0" class="amount-out">支出 {{ money(day.totalOut) }}</span>
              <span v-if="day.totalIn > 0" class="amount-in">收入 {{ money(day.totalIn) }}</span>
            </span>
          </div>
          <div v-for="row in day.rows" :key="row.ledgerId" class="dcs-mobile-card">
            <div class="dcs-card-head">
              <span class="dcs-card-title">{{ rowSummary(row) }}</span>
              <span class="dcs-card-badge"
                ><span class="ui-pill" :class="row.direction === 'IN' ? 'ui-pill--pos' : 'ui-pill--neg'">{{ directionLabel(row.direction) }}</span></span
              >
            </div>
            <div class="dcs-card-meta">
              <span class="ui-pill">{{ bizTypeLabel(row.bizType) }}</span>
              <span :class="row.direction === 'IN' ? 'amount-in' : 'amount-out'">
                {{ row.direction === 'IN' ? '+' : '-' }}{{ money(row.amount) }}
              </span>
              <span>余额 {{ money(row.balanceAfter) }}</span>
            </div>
            <div class="dcs-card-foot">
              <span class="dcs-card-time">{{ timeOf(row.createTime) }} · 钱包 {{ shortId(row.walletId) }}</span>
            </div>
          </div>
        </template>
      </div>
    </template>

    <!-- 积分账（分）：与积分套餐页流水同源同口径（bizTag/sceneLabel 等显示逻辑保持一致） -->
    <template v-else>
      <div class="ui-toolbar">
        <el-form :model="creditQuery" :inline="true" class="search-form" @submit.prevent>
          <el-form-item label="类型">
            <el-select v-model="creditQuery.bizType" clearable placeholder="全部" style="width: 130px" @change="handleQuery">
              <el-option v-for="(v, key) in CREDIT_BIZ" :key="key" :label="v.label" :value="key" />
            </el-select>
          </el-form-item>
          <el-form-item label="操作人">
            <el-select
              v-model="creditQuery.userId"
              clearable
              filterable
              placeholder="全部（近90天）"
              style="width: 170px"
              :no-data-text="ledgerUsersError ? '用户列表暂时取不到' : '没有匹配的用户'"
              @change="handleQuery"
            >
              <el-option v-for="u in ledgerUsers" :key="u.userId" :label="u.nickName" :value="u.userId" />
            </el-select>
            <el-button v-if="ledgerUsersError" link type="primary" style="margin-left: 8px" @click="loadLedgerUsers">重试</el-button>
          </el-form-item>
          <el-form-item>
            <el-checkbox v-model="creditQuery.onlyMine" label="只看我的" @change="handleQuery" />
          </el-form-item>
          <el-form-item>
            <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
          </el-form-item>
          <el-form-item>
            <span class="ab-dim-hint">完整套餐档位/倍率在「积分套餐」菜单</span>
          </el-form-item>
        </el-form>
      </div>

      <div class="ui-table-wrap">
        <el-table v-loading="creditLoading" :data="creditRows" class="ui-table dcs-table-only">
          <el-table-column label="时间" prop="createTime" width="170" />
          <el-table-column label="操作人" width="110" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.operatorName || '--' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="100" align="center">
            <template #default="{ row }">
              <span class="ui-pill" :class="bizTag(row.bizType).pill">{{ bizTag(row.bizType).label }}</span>
            </template>
          </el-table-column>
          <el-table-column label="积分" width="180" align="right">
            <template #default="{ row }">
              <div>
                <span v-if="row.bizType === 'DEDUCT'" class="amount-out">-{{ fmt(row.credits) }}</span>
                <span v-else-if="row.bizType === 'SUBSCRIBE' && subscribeAmountOf(row)" class="amount-in">
                  +{{ fmt(subscribeAmountOf(row)!.perWeek) }}<span class="add-sub"> ×{{ subscribeAmountOf(row)!.weeks }}周</span>
                </span>
                <span v-else-if="row.credits > 0" class="amount-in">+{{ fmt(row.credits) }}</span>
                <span v-else class="add-none">—</span>
              </div>
              <div v-if="row.bizType === 'DEDUCT' && row.credits > 0" class="credit-src">
                {{ row.tankCredits > 0 ? `周池 ${fmt(row.weeklyCredits)} · 加油包 ${fmt(row.tankCredits)}` : `周池 ${fmt(row.weeklyCredits)}` }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="场景" width="170" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="dcs-ops-id">{{ sceneLabel(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.remark || (row.bizType === 'DEDUCT' ? '助手对话消耗' : '--') }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">暂无积分流水</p>
              <p class="ui-empty__hint">产生订阅、加油包或对话消耗后，这里会出现对应流水。</p>
            </div>
          </template>
        </el-table>
      </div>

      <!-- U-09：≤992 积分流水表格改卡片 -->
      <div class="dcs-mobile-list">
        <div v-if="creditRows.length === 0" class="ui-empty">
          <p class="ui-empty__title">暂无积分流水</p>
          <p class="ui-empty__hint">产生订阅、加油包或对话消耗后，这里会出现对应流水。</p>
        </div>
        <div v-for="row in creditRows" :key="row.id" class="dcs-mobile-card">
          <div class="dcs-card-head">
            <span class="dcs-card-title">{{ row.operatorName || '--' }}</span>
            <span class="dcs-card-badge"
              ><span class="ui-pill" :class="bizTag(row.bizType).pill">{{ bizTag(row.bizType).label }}</span></span
            >
          </div>
          <div class="dcs-card-meta">
            <span v-if="row.bizType === 'DEDUCT'" class="amount-out">-{{ fmt(row.credits) }}</span>
            <span v-else-if="row.bizType === 'SUBSCRIBE' && subscribeAmountOf(row)" class="amount-in">
              +{{ fmt(subscribeAmountOf(row)!.perWeek) }}<span class="add-sub"> ×{{ subscribeAmountOf(row)!.weeks }}周</span>
            </span>
            <span v-else-if="row.credits > 0" class="amount-in">+{{ fmt(row.credits) }}</span>
            <span v-else class="add-none">—</span>
            <span class="dcs-ops-id">{{ sceneLabel(row) }}</span>
          </div>
          <div v-if="row.bizType === 'DEDUCT' && row.credits > 0" class="dcs-card-meta">
            <span>{{ row.tankCredits > 0 ? `周池 ${fmt(row.weeklyCredits)} · 加油包 ${fmt(row.tankCredits)}` : `周池 ${fmt(row.weeklyCredits)}` }}</span>
          </div>
          <div class="dcs-card-foot">
            <span class="dcs-card-time">{{ row.createTime }} · {{ row.remark || (row.bizType === 'DEDUCT' ? '助手对话消耗' : '--') }}</span>
          </div>
        </div>
      </div>
    </template>

    <pagination v-show="pagerTotal > 0" :total="pagerTotal" v-model:page="pager.pageNum" v-model:limit="pager.pageSize" @pagination="reload" />
  </el-card>
</template>

<script setup lang="ts">
/**
 * 钱包流水页签（菜单IA重组批2，2026-10-16）：钱包账|积分账双维切换。
 * 「两本账互不打通」是本次重组拍板要解的痛点——同一事件（如买加油包：钱包-¥99 / 积分+5000）
 * 切一下维度即可对照，不必再开两个菜单对照时间戳。
 * 积分账与积分套餐页流水同源（/ai-credit/ledger），显示逻辑（bizTag/sceneLabel/
 * subscribeAmountOf）与该页保持一致；套餐档位/倍率管理仍留在积分套餐菜单。
 */
import { Search, Refresh, RefreshLeft, ArrowRight } from '@element-plus/icons-vue';
import type { OperationsMetric } from '@/components/OperationsPageHeader/index.vue';
import { checkPermi } from '@/utils/permission';
import { listWalletLedger, getWalletLedgerSummary, type WalletLedgerVO, type WalletLedgerSummaryVO } from '@/api/smartdock/account-billing';
import { getWalletSummary } from '@/api/smartdock/account-billing';
import { listCreditLedger, listLedgerUsers, type CreditLedgerVO } from '@/api/smartdock/ai-credit';
import { groupByDay, pageTotals } from '@/utils/ledgerGroup';
import { useUserStore } from '@/store/modules/user';

defineOptions({ name: 'AccountBillingLedgerTab' });

const emit = defineEmits<{ metrics: [m: OperationsMetric[]] }>();

/** 积分账维度权限（深审回修 P2-3：无 smartdock:aicredit:list 不亮切换器） */
const permCredit = checkPermi(['smartdock:aicredit:list']);

// 深审回修(P2-1)+深扫回修(P1-4): 页签切换后旧组件 in-flight 响应不再 emit，防统计带被旧页签数字串档；
// 本页在 keep-alive 路由缓存下切走只 deactivate 不 unmount，守卫须同时挂 onDeactivated。
// 另：维度内连点/跨维快切时旧响应晚到会盖新数据并串统计带——getList/getCreditList 用序列号丢弃过期响应。
let metricsDisposed = false;
let walletSeq = 0;
let creditSeq = 0;
onUnmounted(() => {
  metricsDisposed = true;
});
onDeactivated(() => {
  metricsDisposed = true;
});
onActivated(() => {
  metricsDisposed = false;
});

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const dim = ref<'wallet' | 'credit'>('wallet');

// ===== 钱包账（¥）=====
const loading = ref(false);
const ledgerList = ref<WalletLedgerVO[]>([]);
const total = ref(0);
/** Q5：合计跟筛选走（后端 /summary），不再拿当前页累加——翻页就变会被读成数字在跳 */
const summary = ref<WalletLedgerSummaryVO>({ totalIn: 0, totalOut: 0, net: 0, count: 0 });
/** Q5：余额头 —— 可用余额（超管=平台总量，客户=本单位；文案必须跟着身份走，不能一律写「本单位」） */
const walletAvailable = ref<number | null>(null);
const isSuperAdmin = computed(() => useUserStore().roles.includes('superadmin'));
/** Q5：日期预设 */
const DATE_PRESETS = [
  { key: 'today', label: '今天' },
  { key: 'week', label: '本周' },
  { key: 'month', label: '本月' },
  { key: 'lastMonth', label: '上月' }
] as const;
type PresetKey = (typeof DATE_PRESETS)[number]['key'] | '';
const activePreset = ref<PresetKey>('');
/** Q8：技术参数（租户ID/钱包ID）默认收起，不挡主路径 */
const advancedOpen = ref<string[]>([]);
const walletQuery = reactive({
  pageNum: 1,
  pageSize: 50,
  tenantId: '',
  walletId: '',
  direction: '',
  bizType: '',
  serviceCode: '',
  dateRange: null as [string, string] | null
});

/** Q3：按日期分组的账本视图（纯函数，见 utils/ledgerGroup.ts） */
const walletDays = computed(() => groupByDay(ledgerList.value));

/** 余额头副文案：口径跟着身份走——超管看到的是平台总量，不能标成「本单位」 */
const walletHeroHint = computed(() => {
  if (walletAvailable.value === null) return '余额取数失败，点上方刷新重试';
  return isSuperAdmin.value ? '全平台钱包余额合计；合计按当前筛选条件算' : '本单位的钱包余额；合计按当前筛选条件算';
});

/** 服务码中文映射（与后端写入点一一对应；后端 ledger VO 下发 serviceCodeLabel，此处兜底） */
const SERVICE_CODE_TEXT: Record<string, string> = {
  AI_ASSISTANT_CHAT: 'AI 助手对话',
  AI_PROFILE_SCORE: '画像评分',
  AI_PROFILE_DIAGNOSE: '画像诊断',
  AI_FIELD_GENERATE: '字段生成',
  AI_SCORE_ENHANCE: '评分润色',
  AI_SCORE_RULE_RECOMMEND: '评分规则推荐',
  AI_PROFILE_EMBEDDING: '向量化',
  ADMIN_GIFT: '开户赠送',
  AI_CREDIT_SUBSCRIBE: '积分套餐订阅扣款',
  AI_CREDIT_BOOST: '加油包购买'
};
const serviceCodeLabel = (code?: string | null) => (code ? SERVICE_CODE_TEXT[code] || code : '--');

/**
 * 流水主列摘要：后端下发的人话摘要优先（手动充值/扣减的 reason 在 extra_json 里，
 * 这些行的 serviceCode 为空——直接取 serviceCodeLabel 会整列显示 "--"）。
 * 兜底顺序：summary → 服务码中文 → 业务类型中文 → "--"。
 */
const rowSummary = (row: { summary?: string | null; serviceCode?: string | null; serviceCodeLabel?: string | null; bizType?: string | null }) => {
  const s = String(row.summary || '').trim();
  if (s) return s;
  const byCode = String(row.serviceCodeLabel || '').trim() || (row.serviceCode ? serviceCodeLabel(row.serviceCode) : '');
  if (byCode && byCode !== '--') return byCode;
  return bizTypeLabel(String(row.bizType || ''));
};

const BIZ_TYPE_TEXT: Record<string, string> = {
  RECHARGE: '充值',
  DEDUCT: '扣减',
  FREEZE: '冻结',
  UNFREEZE: '解冻',
  CONSUME: '消费',
  REFUND: '退款',
  ADJUST: '调整'
};

const bizTypeLabel = (t: string) => BIZ_TYPE_TEXT[t] || t || '--';

const directionLabel = (d: string) => {
  const map: Record<string, string> = { IN: '收入', OUT: '支出' };
  return map[d] || d || '--';
};

const money = (value?: number) => `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

/** 只取时间部分（HH:mm）；取不到给原串 */
const timeOf = (t?: string | null) => {
  const s = String(t || '');
  const m = s.match(/\d{2}:\d{2}/);
  return m ? m[0] : s || '--';
};

const shortId = (id?: number | string) => String(id || '').slice(-4);

/** 按天算日期预设区间（GMT+8 口径由后端保证，这里只给 yyyy-MM-dd） */
const presetRange = (key: PresetKey): [string, string] | null => {
  const fmt = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const now = new Date();
  if (key === 'today') {
    const d = fmt(now);
    return [d, d];
  }
  if (key === 'week') {
    // 本周一 0 点起（周一为一周开始）
    const dow = now.getDay() === 0 ? 7 : now.getDay();
    const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - (dow - 1));
    return [fmt(monday), fmt(now)];
  }
  if (key === 'month') {
    return [fmt(new Date(now.getFullYear(), now.getMonth(), 1)), fmt(now)];
  }
  if (key === 'lastMonth') {
    const first = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    const last = new Date(now.getFullYear(), now.getMonth(), 0);
    return [fmt(first), fmt(last)];
  }
  return null;
};

const applyPreset = (key: PresetKey) => {
  activePreset.value = activePreset.value === key ? '' : key;
  walletQuery.dateRange = activePreset.value ? presetRange(activePreset.value) : null;
  handleQuery();
};

const onDateChange = () => {
  // 手动改日期就退出预设高亮（预设只是快捷方式，不是状态）
  activePreset.value = '';
  handleQuery();
};

/** 列表与合计共用同一组参数，保证同口径 */
const walletParams = () => {
  const [beginTime, endTime] = walletQuery.dateRange || [undefined, undefined];
  return {
    tenantId: walletQuery.tenantId || undefined,
    walletId: walletQuery.walletId || undefined,
    serviceCode: walletQuery.serviceCode || undefined,
    direction: walletQuery.direction || undefined,
    bizType: walletQuery.bizType || undefined,
    beginTime,
    endTime
  };
};

const getList = async () => {
  const seq = ++walletSeq;
  loading.value = true;
  try {
    const params = walletParams();
    const [res, sum] = await Promise.all([
      listWalletLedger({ ...params, pageNum: walletQuery.pageNum, pageSize: walletQuery.pageSize }),
      getWalletLedgerSummary(params)
    ]);
    if (seq !== walletSeq) return; // 过期响应丢弃(同维连点/跨维快切)
    ledgerList.value = res.rows || [];
    total.value = res.total || 0;
    summary.value = sum.data || { totalIn: 0, totalOut: 0, net: 0, count: 0 };
    if (dim.value === 'wallet') emitWalletMetrics();
  } catch {
    if (seq === walletSeq) proxy?.$modal.msgError('流水加载失败');
  } finally {
    if (seq === walletSeq) loading.value = false;
  }
};

/** 余额头：取可用余额（口径与总览页同源 /tenant-wallet/summary）
 *  ⚠后端 BigDecimal 序列化成字符串（"1500.0000"），不能用 typeof === 'number' 判空——那会恒落 null */
const loadWalletAvailable = async () => {
  try {
    const res: any = await getWalletSummary();
    const raw = res.data?.totalBalance;
    const n = raw === null || raw === undefined || raw === '' ? NaN : Number(raw);
    walletAvailable.value = Number.isFinite(n) ? n : null;
  } catch {
    walletAvailable.value = null;
  }
};

const emitWalletMetrics = () => {
  if (metricsDisposed) return;
  // Q5：KPI 带改用后端合计（与列表同口径），不再拿当前页累加
  const s = summary.value;
  emit('metrics', [
    { label: '符合筛选的流水', value: s.count, hint: '当前筛选条件全量口径', tone: 'primary' },
    { label: '收入合计', value: money(s.totalIn), hint: '当前筛选条件', tone: 'success' },
    { label: '支出合计', value: money(s.totalOut), hint: '当前筛选条件', tone: 'danger' },
    { label: '净额', value: money(s.net), hint: '收入 − 支出', tone: s.net >= 0 ? 'success' : 'danger' }
  ]);
};

// ===== 积分账（分）=====
const creditLoading = ref(false);
const creditRows = ref<CreditLedgerVO[]>([]);
const creditTotal = ref(0);
const ledgerUsers = ref<Array<{ userId: number; nickName: string }>>([]);
/** 操作人下拉取不到时不能呈现成「没有匹配的用户」——那会让人以为这段没人操作过 */
const ledgerUsersError = ref(false);
const creditQuery = reactive({
  pageNum: 1,
  pageSize: 20,
  bizType: '',
  userId: undefined as number | undefined,
  onlyMine: false
});

const CREDIT_BIZ: Record<string, { label: string }> = {
  SUBSCRIBE: { label: '订阅入账' },
  BOOST: { label: '加油包' },
  DEDUCT: { label: '消耗' },
  ADJUST: { label: '调整' }
};

const fmt = (n?: number | null) => `${Number(n || 0).toLocaleString('zh-CN')}`;

/** 积分类型药丸：消耗=警告 / 入账=成功 / 调整与测试=中性，与全站四组语义色一致 */
const bizTag = (t: string) => {
  const map: Record<string, { label: string; pill: string }> = {
    DEDUCT: { label: '消耗', pill: 'ui-pill--warn' },
    BOOST: { label: '加油包', pill: 'ui-pill--pos' },
    SUBSCRIBE: { label: '订阅入账', pill: 'ui-pill--pos' },
    ADJUST: { label: '调整', pill: 'ui-pill--muted' },
    TEST: { label: '测试', pill: 'ui-pill--muted' }
  };
  return map[t] || { label: t || '--', pill: 'ui-pill--muted' };
};

/** 与 ai-credit 页同款：订阅入账行真实量从 remark 提取（credits=0，量在备注里） */
const subscribeAmountOf = (row: CreditLedgerVO): { perWeek: number; weeks: number } | null => {
  const m = (row.remark || '').match(/([\d,]+)\s*积分\/周\s*×\s*(\d+)\s*周/);
  if (!m) return null;
  return { perWeek: Number(m[1].replace(/,/g, '')), weeks: Number(m[2]) };
};

/** 与 ai-credit 页同款：场景串中文化（assist:chat:seq42 → AI 对话 · 第42轮） */
const sceneLabel = (row: CreditLedgerVO): string => {
  const s = row.scene;
  if (!s) {
    return row.bizType === 'SUBSCRIBE' ? '订阅' : row.bizType === 'BOOST' ? '加油包' : '--';
  }
  const est = s.includes('|est');
  const base = s.replace(/\|est/g, '');
  const seqMatch = base.match(/^assist:chat:seq(\d+)/);
  const seq = seqMatch ? ` · 第${seqMatch[1]}轮` : '';
  let name = 'AI 对话';
  if (base.includes('sidecar:followup')) {
    name = '追问建议';
  } else if (base.includes('sidecar:title')) {
    name = '会话起名';
  } else if (base.includes('sidecar')) {
    name = '附带小任务';
  }
  return `${name}${seq}${est ? '（估算）' : ''}`;
};

const getCreditList = async () => {
  const seq = ++creditSeq;
  creditLoading.value = true;
  try {
    const res: any = await listCreditLedger({
      bizType: creditQuery.bizType || undefined,
      userId: creditQuery.userId || undefined,
      onlyMine: creditQuery.onlyMine || undefined,
      pageNum: creditQuery.pageNum,
      pageSize: creditQuery.pageSize
    });
    if (seq !== creditSeq) return; // 过期响应丢弃(同维连点/跨维快切)
    // 包络对齐 ai-credit 页权威口径：/ledger 返回 R<TableDataInfo>，rows/total 在 data 里
    // （批2 深扫复盘：曾按顶层 res.rows 读=恒空，视觉验收假过——DOM 行数断言才算数）
    const page: any = (res as any).data ?? res;
    creditRows.value = page.rows || [];
    creditTotal.value = Number(page.total || 0);
    if (dim.value === 'credit') emitCreditMetrics();
  } catch {
    if (seq === creditSeq) proxy?.$modal.msgError('积分流水加载失败');
  } finally {
    if (seq === creditSeq) creditLoading.value = false;
  }
};

const emitCreditMetrics = () => {
  if (metricsDisposed) return;
  const rows = creditRows.value;
  const spent = rows.filter((i) => i.bizType === 'DEDUCT').reduce((s, i) => s + Number(i.credits || 0), 0);
  const gained = rows.filter((i) => i.credits > 0).reduce((s, i) => s + Number(i.credits || 0), 0);
  emit('metrics', [
    { label: '积分流水总数', value: creditTotal.value, hint: '符合当前筛选条件', tone: 'primary' },
    { label: '当前页消耗', value: `-${fmt(spent)}`, hint: '当前页消耗合计（分）', tone: 'danger' },
    { label: '当前页入账', value: `+${fmt(gained)}`, hint: '订阅/加油包入账合计（分）', tone: 'success' },
    { label: '操作人数', value: new Set(rows.map((i) => i.createBy).filter(Boolean)).size, hint: '当前页出现的操作人', tone: 'neutral' }
  ]);
};

const loadLedgerUsers = async () => {
  try {
    const res: any = await listLedgerUsers();
    ledgerUsers.value = res.data || [];
    ledgerUsersError.value = false;
  } catch {
    ledgerUsers.value = [];
    ledgerUsersError.value = true;
  }
};

// ===== 公共 =====
/** 当前维度的分页对象（computed 返回响应式对象，v-model:page 可直接绑定其成员） */
const pager = computed(() => (dim.value === 'wallet' ? walletQuery : creditQuery));
const pagerTotal = computed(() => (dim.value === 'wallet' ? total.value : creditTotal.value));

const reload = () => {
  if (dim.value === 'wallet') getList();
  else getCreditList();
};

const switchDim = (d: 'wallet' | 'credit') => {
  if (dim.value === d) return;
  dim.value = d;
  if (d === 'credit') {
    getCreditList();
    if (!ledgerUsers.value.length) loadLedgerUsers();
  } else {
    getList();
    if (walletAvailable.value === null) loadWalletAvailable();
  }
};

const handleQuery = () => {
  if (dim.value === 'wallet') {
    walletQuery.pageNum = 1;
    getList();
  } else {
    creditQuery.pageNum = 1;
    getCreditList();
  }
};

const resetQuery = () => {
  if (dim.value === 'wallet') {
    walletQuery.tenantId = '';
    walletQuery.walletId = '';
    walletQuery.direction = '';
    walletQuery.bizType = '';
    walletQuery.serviceCode = '';
    walletQuery.dateRange = null;
    walletQuery.pageNum = 1;
    activePreset.value = '';
    getList();
  } else {
    creditQuery.bizType = '';
    creditQuery.userId = undefined;
    creditQuery.onlyMine = false;
    creditQuery.pageNum = 1;
    getCreditList();
  }
};

onMounted(() => {
  getList();
  loadWalletAvailable();
});
</script>

<style scoped>
/* 顶部维度切换条：.ui-toolbar 提供 1px 底线与 36 高控件 */
.ab-toolbar {
  justify-content: space-between;
}
/* 钱包账|积分账 双维切换：分段药丸，激活用中性底+主文字，禁止主色铺底 */
.ab-dim-switch {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--app-rule);
  border-radius: var(--dcs-pill-radius);
  background: var(--app-raised);
}
.ab-dim {
  height: calc(var(--dcs-control-h) - 6px);
  padding: 0 16px;
  border: 0;
  border-radius: var(--dcs-pill-radius);
  background: transparent;
  color: var(--app-muted);
  font-size: var(--dcs-font-md);
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color 0.15s,
    color 0.15s;
}
.ab-dim:hover {
  color: var(--app-text);
}
.ab-dim.on {
  background: var(--app-surface);
  color: var(--app-text);
  font-weight: 600;
}
.ab-dim-hint {
  color: var(--app-muted);
  font-size: var(--dcs-font-sm);
}
.search-form {
  flex: 1 1 520px;
  min-width: 0;
  margin: 0;
}
.search-form :deep(.el-form-item) {
  margin-bottom: 8px;
}
/* 375：表格外层容器可横滚，父链 min-width:0 */
.dcs-ops-table-card {
  min-width: 0;
}
.ui-table-wrap {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
}
/* 表格规格走 Element Plus 变量：表头无底带 / 行线令牌 / 行 hover 中性底 / 冻结列无阴影 */
.ui-table {
  --el-table-text-color: var(--app-muted);
  --el-table-header-text-color: var(--app-faint);
  --el-table-header-bg-color: transparent;
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--app-raised);
  --el-table-current-row-bg-color: var(--app-raised);
  --el-table-fixed-box-shadow: none;
  --el-table-fixed-left-column: none;
  --el-table-fixed-right-column: none;
}
.ui-table :deep(.el-table__empty-text) {
  width: 100%;
  line-height: 1.6;
}
.amount-in {
  color: var(--el-color-success);
  font-weight: 600;
}
.amount-out {
  color: var(--el-color-danger);
  font-weight: 600;
}
.add-sub {
  font-size: var(--dcs-font-sm);
  font-weight: 400;
  color: var(--app-muted);
}
.add-none {
  color: var(--app-faint);
}
.credit-src {
  margin-top: 2px;
  color: var(--app-muted);
  font-size: var(--dcs-font-sm);
}
.balance-change {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-variant-numeric: tabular-nums;
}
.balance-change .arrow {
  color: var(--app-faint);
}

/* ===== Q5：余额头 ===== */
.ledger-hero {
  display: flex;
  align-items: stretch;
  gap: 0;
  margin: 12px 0 14px;
  overflow: hidden;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-card-radius);
}
.ledger-hero__main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1 1 auto;
  min-width: 0;
  padding: 14px 18px;
}
.ledger-hero__label {
  color: var(--app-faint);
  font-size: var(--dcs-font-sm);
  line-height: 1.3;
}
.ledger-hero__value {
  color: var(--app-text);
  font-size: var(--dcs-font-2xl);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}
.ledger-hero__sub {
  color: var(--app-muted);
  font-size: var(--dcs-font-sm);
  line-height: 1.4;
}
.ledger-hero__side {
  display: flex;
  flex: 0 0 auto;
}
.ledger-hero__cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  justify-content: center;
  min-width: 118px;
  padding: 14px 16px;
  border-left: 1px solid var(--app-rule);
}
.ledger-hero__cell-label {
  color: var(--app-faint);
  font-size: var(--dcs-font-xs);
  line-height: 1.3;
}
.ledger-hero__cell-value {
  font-size: var(--dcs-font-md);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

/* ===== Q5：日期预设 ===== */
.ledger-filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 10px;
}
.ledger-presets {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.ledger-preset {
  height: calc(var(--dcs-control-h) - 6px);
  padding: 0 14px;
  border: 1px solid var(--app-rule);
  border-radius: var(--dcs-pill-radius);
  background: transparent;
  color: var(--app-muted);
  font-size: var(--dcs-font-md);
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color 0.15s,
    color 0.15s;
}
.ledger-preset:hover {
  color: var(--app-text);
}
.ledger-preset.on {
  background: var(--app-raised);
  color: var(--app-text);
  font-weight: 600;
}
.ledger-range {
  max-width: 240px;
}
.ledger-filter-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

/* ===== Q8：技术参数折叠 ===== */
.ledger-advanced {
  border-top: 0;
  margin-bottom: 4px;
}
.ledger-advanced__title {
  color: var(--app-muted);
  font-size: var(--dcs-font-sm);
}

/* ===== Q3：按日期分组的账本 ===== */
.ledger-days {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}
.ledger-day {
  border: 1px solid var(--app-rule);
  border-radius: var(--app-card-radius);
  overflow: hidden;
}
.ledger-day__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 9px 14px;
  background: var(--app-raised);
  border-bottom: 1px solid var(--app-rule);
}
.ledger-day__date {
  color: var(--app-text);
  font-size: var(--dcs-font-md);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.ledger-day__weekday {
  color: var(--app-faint);
  font-size: var(--dcs-font-sm);
}
.ledger-day__sums {
  display: inline-flex;
  align-items: baseline;
  gap: 10px;
  margin-left: auto;
  font-size: var(--dcs-font-sm);
  font-variant-numeric: tabular-nums;
}
.ledger-day__count {
  color: var(--app-faint);
}
.ledger-day__rows {
  display: flex;
  flex-direction: column;
}
.ledger-row {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) 64px 108px 132px 56px;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-top: 1px solid var(--app-rule);
  font-size: var(--dcs-font-md);
}
.ledger-row:first-child {
  border-top: 0;
}
.ledger-row__time {
  color: var(--app-faint);
  font-variant-numeric: tabular-nums;
}
.ledger-row__name {
  overflow: hidden;
  color: var(--app-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ledger-row__biz {
  justify-self: start;
}
.ledger-row__amount {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.ledger-row__balance {
  color: var(--app-muted);
  font-size: var(--dcs-font-sm);
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.ledger-row__more {
  justify-self: end;
}
.ledger-detail {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: var(--dcs-font-sm);
}
.ledger-detail > div {
  display: flex;
  gap: 10px;
  justify-content: space-between;
}
.ledger-detail span {
  color: var(--app-faint);
  flex: 0 0 auto;
}
.ledger-detail b {
  color: var(--app-text);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
  text-align: right;
}

/* ===== Q6：手机档当天小计（只在卡片视图里出现） ===== */
.ledger-mobile-day {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 2px 6px;
  color: var(--app-muted);
  font-size: var(--dcs-font-sm);
}
.ledger-mobile-day__date {
  color: var(--app-text);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.ledger-mobile-day__sums {
  display: inline-flex;
  gap: 10px;
  font-variant-numeric: tabular-nums;
}

/* 桌面档隐藏卡片版当天小计；手机档隐藏分组账本（两套视图互斥） */
@media (min-width: 993px) {
  .ledger-mobile-day {
    display: none;
  }
}
@media (max-width: 992px) {
  .ledger-days {
    display: none;
  }
  /* 手机档余额头竖排：三格合计换行到下一行，避免横向挤压 */
  .ledger-hero {
    flex-direction: column;
  }
  .ledger-hero__side {
    border-top: 1px solid var(--app-rule);
  }
  .ledger-hero__cell {
    flex: 1 1 0;
    min-width: 0;
    border-left: 1px solid var(--app-rule);
    padding: 10px 12px;
  }
  .ledger-hero__cell:first-child {
    border-left: 0;
  }
}
</style>
