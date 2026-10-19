<template>
  <el-card shadow="never" class="dcs-ops-table-card">
    <div class="ab-toolbar">
      <!-- 深审回修(P2-3): 积分账维度需 dcs:aicredit:list，无权限不亮入口（亮了也是 403 空表） -->
      <div v-if="permCredit" class="ab-dim-switch" role="tablist" aria-label="账本维度">
        <button type="button" class="ab-dim" :class="{ on: dim === 'wallet' }" @click="switchDim('wallet')">钱包账（¥）</button>
        <button type="button" class="ab-dim" :class="{ on: dim === 'credit' }" @click="switchDim('credit')">积分账（分）</button>
      </div>
      <el-button :icon="Refresh" circle @click="reload" />
    </div>

    <!-- 钱包账（¥）：原 tenant-wallet-ledger 迁移 -->
    <template v-if="dim === 'wallet'">
      <el-form :model="walletQuery" :inline="true" class="search-form" @submit.prevent>
        <el-form-item label="租户ID">
          <el-input v-model="walletQuery.tenantId" placeholder="租户ID" clearable style="width: 160px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="钱包ID">
          <el-input v-model="walletQuery.walletId" placeholder="钱包ID" clearable style="width: 150px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="方向">
          <el-select v-model="walletQuery.direction" clearable placeholder="全部" style="width: 120px" @change="handleQuery">
            <el-option label="收入 IN" value="IN" />
            <el-option label="支出 OUT" value="OUT" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务类型">
          <el-select v-model="walletQuery.bizType" clearable placeholder="全部" style="width: 130px" @change="handleQuery">
            <el-option v-for="(label, key) in BIZ_TYPE_TEXT" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleQuery">搜索</el-button>
          <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="ledgerList" border size="small">
        <el-table-column label="流水ID" prop="ledgerId" min-width="172" align="center">
          <template #default="{ row }"
            ><span class="dcs-ops-id">{{ row.ledgerId }}</span></template
          >
        </el-table-column>
        <el-table-column label="租户ID" prop="tenantId" min-width="150" show-overflow-tooltip />
        <el-table-column label="钱包ID" prop="walletId" min-width="172" align="center">
          <template #default="{ row }"
            ><span class="dcs-ops-id">{{ row.walletId }}</span></template
          >
        </el-table-column>
        <el-table-column label="业务类型" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ bizTypeLabel(row.bizType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="方向" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.direction === 'IN' ? 'success' : 'danger'">{{ directionLabel(row.direction) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="140" align="right">
          <template #default="{ row }">
            <span :class="row.direction === 'IN' ? 'amount-in' : 'amount-out'">
              {{ row.direction === 'IN' ? '+' : '-' }}{{ money(row.amount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="余额变化" min-width="190" align="center">
          <template #default="{ row }">
            <span class="balance-change">
              {{ money(row.balanceBefore) }}
              <el-icon class="arrow"><ArrowRight /></el-icon>
              <span :class="Number(row.balanceAfter || 0) >= Number(row.balanceBefore || 0) ? 'amount-in' : 'amount-out'">{{
                money(row.balanceAfter)
              }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="服务" width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- 服务码中文化（raw code 进 title 供排障）；充值/赠送等非 AI 行不显服务 -->
            <span :title="row.serviceCode || undefined">{{ row.serviceCodeLabel || serviceCodeLabel(row.serviceCode) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" prop="createTime" width="170" />
      </el-table>
    </template>

    <!-- 积分账（分）：与积分套餐页流水同源同口径（bizTag/sceneLabel 等显示逻辑保持一致） -->
    <template v-else>
      <el-form :model="creditQuery" :inline="true" class="search-form" @submit.prevent>
        <el-form-item label="类型">
          <el-select v-model="creditQuery.bizType" clearable placeholder="全部" style="width: 130px" @change="handleQuery">
            <el-option v-for="(v, key) in CREDIT_BIZ" :key="key" :label="v.label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作人">
          <el-select v-model="creditQuery.userId" clearable filterable placeholder="全部（近90天）" style="width: 170px" @change="handleQuery">
            <el-option v-for="u in ledgerUsers" :key="u.userId" :label="u.nickName" :value="u.userId" />
          </el-select>
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

      <el-table v-loading="creditLoading" :data="creditRows" border size="small">
        <el-table-column label="时间" prop="createTime" width="170" />
        <el-table-column label="操作人" width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.operatorName || '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="bizTag(row.bizType).tone">{{ bizTag(row.bizType).label }}</el-tag>
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
      </el-table>
    </template>

    <pagination v-show="pagerTotal > 0" :total="pagerTotal" v-model:page="pager.pageNum" v-model:limit="pager.pageSize" @pagination="reload" />
  </el-card>
</template>

<script setup lang="ts">
/**
 * 钱包流水页签（菜单IA重组批2，2026-10-16）：钱包账|积分账双维切换。
 * 「两本账互不打通」是本次重组拍板要解的痛点——同一事件（如买加油包：钱包-¥99 / 积分+5000）
 * 切一下维度即可对照，不必再开两个菜单对照时间戳。
 * 积分账与积分套餐页流水同源（/dcs/ai-credit/ledger），显示逻辑（bizTag/sceneLabel/
 * subscribeAmountOf）与该页保持一致；套餐档位/倍率管理仍留在积分套餐菜单。
 */
import { Search, Refresh, RefreshLeft, ArrowRight } from '@element-plus/icons-vue';
import type { DcsOperationsMetric } from '@/components/DcsOperationsPageHeader/index.vue';
import { checkPermi } from '@/utils/permission';
import { listWalletLedger, type WalletLedgerVO } from '@/api/dcs/account-billing';
import { listCreditLedger, listLedgerUsers, type CreditLedgerVO } from '@/api/dcs/ai-credit';

defineOptions({ name: 'AccountBillingLedgerTab' });

const emit = defineEmits<{ metrics: [m: DcsOperationsMetric[]] }>();

/** 积分账维度权限（深审回修 P2-3：无 dcs:aicredit:list 不亮切换器） */
const permCredit = checkPermi(['dcs:aicredit:list']);

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
const walletQuery = reactive({
  pageNum: 1,
  pageSize: 20,
  tenantId: '',
  walletId: '',
  direction: '',
  bizType: ''
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

const getList = async () => {
  const seq = ++walletSeq;
  loading.value = true;
  try {
    const res: any = await listWalletLedger(walletQuery);
    if (seq !== walletSeq) return; // 过期响应丢弃(同维连点/跨维快切)
    ledgerList.value = res.rows || [];
    total.value = res.total || 0;
    if (dim.value === 'wallet') emitWalletMetrics();
  } catch {
    if (seq === walletSeq) proxy?.$modal.msgError('流水加载失败');
  } finally {
    if (seq === walletSeq) loading.value = false;
  }
};

const emitWalletMetrics = () => {
  if (metricsDisposed) return;
  const rows = ledgerList.value;
  emit('metrics', [
    { label: '钱包流水总数', value: total.value, hint: '符合当前筛选条件', tone: 'primary' },
    {
      label: '当前页收入',
      value: money(rows.filter((i) => i.direction === 'IN').reduce((s, i) => s + Number(i.amount || 0), 0)),
      hint: '当前页 IN 流水合计',
      tone: 'success'
    },
    {
      label: '当前页支出',
      value: money(rows.filter((i) => i.direction === 'OUT').reduce((s, i) => s + Number(i.amount || 0), 0)),
      hint: '当前页 OUT 流水合计',
      tone: 'danger'
    },
    { label: '业务类型', value: new Set(rows.map((i) => i.bizType).filter(Boolean)).size, hint: '当前页资金变动类型', tone: 'neutral' }
  ]);
};

// ===== 积分账（分）=====
const creditLoading = ref(false);
const creditRows = ref<CreditLedgerVO[]>([]);
const creditTotal = ref(0);
const ledgerUsers = ref<Array<{ userId: number; nickName: string }>>([]);
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

/** 与 ai-credit 页同款：积分类型标签 */
const bizTag = (t: string) => {
  const map: Record<string, { label: string; tone: 'info' | 'warning' | 'success' | 'primary' }> = {
    DEDUCT: { label: '消耗', tone: 'primary' },
    BOOST: { label: '加油包', tone: 'warning' },
    SUBSCRIBE: { label: '订阅入账', tone: 'success' },
    ADJUST: { label: '调整', tone: 'info' },
    TEST: { label: '测试', tone: 'info' }
  };
  return map[t] || { label: t || '--', tone: 'info' as const };
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
  } catch {
    ledgerUsers.value = [];
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
    walletQuery.pageNum = 1;
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
});
</script>

<style scoped>
.ab-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
/* 钱包账|积分账 双维切换（拍板页定稿样式：蓝底/白字激活，非 EP 内置组件） */
.ab-dim-switch {
  display: inline-flex;
  border-radius: 6px;
  overflow: hidden;
}
.ab-dim {
  padding: 5px 18px;
  font-size: 13px;
  font-weight: 600;
  border: 1px solid var(--el-border-color);
  background: #fff;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  transition: all 0.15s;
}
.ab-dim + .ab-dim {
  border-left: none;
}
.ab-dim:first-child {
  border-radius: 6px 0 0 6px;
}
.ab-dim:last-child {
  border-radius: 0 6px 6px 0;
}
.ab-dim.on {
  background: var(--el-color-primary);
  border-color: var(--el-color-primary);
  color: #fff;
}
.ab-dim-hint {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.search-form {
  margin-bottom: 8px;
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
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.add-none {
  color: var(--el-text-color-placeholder);
}
.credit-src {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.balance-change {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.balance-change .arrow {
  color: #909399;
}
</style>
