<template>
  <el-card shadow="never" class="dcs-ops-table-card">
    <div class="ui-toolbar">
      <el-form :model="queryParams" :inline="true" class="search-form" @submit.prevent>
        <el-form-item label="单位">
          <el-input v-model="queryParams.tenantId" placeholder="租户ID（留空看全部）" clearable style="width: 220px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item>
          <el-button :icon="Search" @click="handleQuery">搜索</el-button>
          <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
      <!-- Q7/Q10：能力驱动 —— 有权限才显示，没权限不占位（不是灰按钮） -->
      <div class="ab-toolbar-actions">
        <el-button v-hasPermi="['smartdock:wallet:recharge']" type="primary" :icon="Plus" @click="openInit">初始化钱包</el-button>
        <el-button :icon="Refresh" @click="reloadAll">刷新</el-button>
      </div>
    </div>

    <!-- Q4：单位卡片网格 —— 每家一张卡，余额是最大的字，卡上直接带入口 -->
    <div v-loading="loading" class="wallet-grid">
      <div v-if="walletList.length === 0" class="ui-empty">
        <p class="ui-empty__title">还没有钱包</p>
        <p class="ui-empty__hint">初始化钱包后，这里会显示每个单位的余额、冻结与提醒线。</p>
      </div>
      <article v-for="row in walletList" :key="row.walletId" class="wallet-card" :class="{ 'wallet-card--low': isLowBalance(row) }">
        <header class="wallet-card__head">
          <div class="wallet-card__name">
            <span class="wallet-card__tenant">{{ row.tenantName || `租户 ${row.tenantId}` }}</span>
            <span class="wallet-card__id">{{ row.tenantId }}{{ row.remark ? ` · ${row.remark}` : '' }}</span>
          </div>
          <span class="wallet-card__badges">
            <span class="ui-pill" :class="walletStatusPill(row.status)">{{ walletStatusText(row.status).label }}</span>
            <span v-if="isLowBalance(row)" class="ui-pill ui-pill--warn">{{ LOW_BALANCE.label }}</span>
          </span>
        </header>

        <div class="wallet-card__amount">
          <strong class="wallet-card__balance">{{ money(row.balanceAmount) }}</strong>
          <span class="wallet-card__meta">
            可用 {{ money(row.availableAmount) }} · 冻结 {{ money(row.frozenAmount) }}
          </span>
        </div>

        <!-- Q12：状态一句话解释（状态名本身不足以让人知道该干嘛） -->
        <p class="wallet-card__hint">{{ isLowBalance(row) ? LOW_BALANCE.hint : walletStatusText(row.status).hint }}</p>

        <footer class="wallet-card__actions">
          <el-button v-hasPermi="['smartdock:wallet:recharge']" link type="primary" @click="openRecharge(row)">充值</el-button>
          <el-button v-hasPermi="['smartdock:wallet:status']" link class="ui-row-action" @click="openThreshold(row)">调提醒线</el-button>
          <el-button link class="ui-row-action" @click="goLedger(row)">看流水</el-button>
          <el-dropdown v-if="canManage" trigger="click" @command="(cmd: string) => onMoreCommand(cmd, row)">
            <el-button link class="ab-more-btn ui-row-action" aria-label="更多资金操作">
              更多<el-icon class="ab-more-caret"><CaretBottom /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-if="permDeduct" command="deduct">扣减</el-dropdown-item>
                <el-dropdown-item v-if="permStatus && row.status !== 'DISABLED'" command="toggle">{{
                  row.status === 'ACTIVE' ? '冻结' : '解冻'
                }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </footer>
      </article>
    </div>

    <!-- Q10：客户视角可能看不到某些按钮——统一说一句「找谁开通」，避免以为系统没有 -->
    <p v-if="!canManage" class="ab-perm-note">需要充值、冻结或调整提醒线？联系平台管理员开通相应权限。</p>

    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />

    <!-- 初始化钱包 -->
    <el-dialog v-model="initDialog.visible" title="初始化钱包" width="480px" append-to-body>
      <el-form :model="initDialog.form" label-width="90px">
        <el-form-item label="租户ID">
          <el-input v-model="initDialog.form.tenantId" placeholder="留空则初始化当前租户" clearable />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="initDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="initDialog.submitting" @click="submitInit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 充值 / 扣减 -->
    <el-dialog v-model="opDialog.visible" :title="opDialog.title" width="640px" append-to-body>
      <el-form :model="opDialog.form" label-width="90px">
        <el-form-item label="钱包">
          <span>{{ opDialog.row?.walletId }} ({{ opDialog.row?.tenantId }})</span>
        </el-form-item>
        <el-form-item label="当前余额">
          <span>{{ money(opDialog.row?.balanceAmount) }}</span>
        </el-form-item>
        <el-form-item label="金额" required>
          <el-input-number v-model="opDialog.form.amount" :min="0.01" :precision="2" :step="100" controls-position="right" style="width: 220px" />
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="opDialog.form.reason" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="请填写操作原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="opDialog.visible = false">取消</el-button>
        <el-button :type="opDialog.kind === 'recharge' ? 'primary' : 'warning'" :loading="opDialog.submitting" @click="submitOp">确定</el-button>
      </template>
    </el-dialog>

    <!-- 调阈值 -->
    <el-dialog v-model="thresholdDialog.visible" title="调整低余额阈值" width="640px" append-to-body>
      <el-form :model="thresholdDialog.form" label-width="110px">
        <el-form-item label="钱包">
          <span>{{ thresholdDialog.row?.walletId }} ({{ thresholdDialog.row?.tenantId }})</span>
        </el-form-item>
        <el-form-item label="当前阈值">
          <span>{{ money(thresholdDialog.row?.lowBalanceThreshold) }}</span>
        </el-form-item>
        <el-form-item label="新阈值" required>
          <el-input-number
            v-model="thresholdDialog.form.lowBalanceThreshold"
            :min="0"
            :precision="2"
            :step="100"
            controls-position="right"
            style="width: 220px"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="thresholdDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="thresholdDialog.submitting" @click="submitThreshold">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
/**
 * 账户总览页签（菜单IA重组批2，2026-10-16）：自 tenant-wallet/index.vue 迁移。
 * 升级项（拍板页 v5 第5节承诺）：KPI 全量口径（/summary）· 金额 ¥ 千分位 · 钱包ID 折叠为「租户·尾4」
 * · 低于阈值整行浅橙预警+左缘竖条 · 扣减/冻结收「···」下拉（均带二次确认）。
 */
import { Plus, Search, Refresh, RefreshLeft, CaretBottom } from '@element-plus/icons-vue';
import type { OperationsMetric } from '@/components/OperationsPageHeader/index.vue';
import { checkPermi } from '@/utils/permission';
import { LOW_BALANCE, walletStatusPill, walletStatusText } from '@/utils/walletStatus';
import {
  listTenantWallet,
  initMainWallet,
  manualRecharge,
  manualDeduct,
  updateWalletThreshold,
  updateWalletStatus,
  getWalletSummary,
  type TenantWalletVO,
  type WalletOperationForm
} from '@/api/smartdock/account-billing';

defineOptions({ name: 'AccountBillingOverviewTab' });

/** Q7/Q10：能力驱动显隐——有权限才出按钮，没权限不占位（不是灰按钮） */
const permRecharge = checkPermi(['smartdock:wallet:recharge']);
const permDeduct = checkPermi(['smartdock:wallet:deduct']);
const permStatus = checkPermi(['smartdock:wallet:status']);
/** 「更多」下拉里只要有一项就不空转 */
const canManage = computed(() => permDeduct || permStatus);

const emit = defineEmits<{ metrics: [m: OperationsMetric[]] }>();

// 深审回修(P2-1)+深扫回修(P1-4): 页签切换后旧组件 in-flight 响应不再 emit，防统计带被旧页签数字串档；
// keep-alive 路由缓存下切走只 deactivate 不 unmount，守卫须同时挂 onDeactivated
let metricsDisposed = false;
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

const loading = ref(false);
const walletList = ref<TenantWalletVO[]>([]);
const total = ref(0);
const summary = ref<{ walletCount: number; totalBalance: number; totalFrozen: number; belowThreshold: number; abnormal: number } | null>(null);
/** 汇总取不到时要留一句白话，不能让整条 KPI 无声消失 */
const summaryError = ref(false);
const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  tenantId: ''
});

const walletTypeLabel = (t: string) => {
  const map: Record<string, string> = { MAIN: '主钱包', SUB: '子钱包' };
  return map[t] || t || '--';
};

const money = (value?: number) => `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

/** Q4：「看流水」带租户过滤跳转，落地就能看到这一家的账 */
const router = useRouter();
const goLedger = (row: TenantWalletVO) => {
  router.push({ path: '/tenant/account-billing', query: { tab: 'ledger', tenantId: row.tenantId } });
};

/** 余额低于阈值 → 状态列补「低于阈值」warn 药丸（U-18：撤整行彩底+左缘彩条，状态色只上药丸） */
const isLowBalance = (row: TenantWalletVO) => {
  const th = Number(row.lowBalanceThreshold || 0);
  return th > 0 && Number(row.balanceAmount || 0) < th;
};

const emitMetrics = () => {
  if (metricsDisposed) return;
  if (!summary.value) {
    // 汇总取不到时整条 KPI 会消失，看着像页面坏了；保留三格占位写「—」，数字位不撒谎也不让版面塌成一张空卡
    if (summaryError.value) {
      emit('metrics', [
        { label: '钱包总数', value: '—', hint: '汇总取数失败，点表格上方「刷新」重试', tone: 'danger' },
        { label: '总余额', value: '—', hint: '', tone: 'neutral' },
        { label: '总冻结', value: '—', hint: '', tone: 'neutral' }
      ]);
    }
    return;
  }
  const s = summary.value;
  emit('metrics', [
    { label: '钱包总数', value: s.walletCount, hint: '全量口径·不受筛选分页影响', tone: 'primary' },
    { label: '总余额', value: money(s.totalBalance), hint: '全部钱包余额合计', tone: 'success' },
    { label: '总冻结', value: money(s.totalFrozen), hint: '冻结中资金合计', tone: 'warning' },
    { label: '低于阈值', value: s.belowThreshold, hint: '余额 < 各自低余额阈值', tone: s.belowThreshold > 0 ? 'danger' : 'neutral' },
    { label: '异常钱包', value: s.abnormal, hint: '冻结/停用状态', tone: s.abnormal > 0 ? 'danger' : 'neutral' }
  ]);
};

const getSummary = async () => {
  try {
    const res: any = await getWalletSummary();
    summary.value = res.data || null;
    summaryError.value = false;
    emitMetrics();
  } catch {
    summary.value = null;
    summaryError.value = true;
    emitMetrics();
  }
};

const getList = async () => {
  loading.value = true;
  try {
    const res: any = await listTenantWallet(queryParams);
    walletList.value = res.rows || [];
    total.value = res.total || 0;
  } finally {
    loading.value = false;
  }
};

const reloadAll = () => {
  getList();
  getSummary();
};

const handleQuery = () => {
  queryParams.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryParams.tenantId = '';
  queryParams.pageNum = 1;
  getList();
};

// 初始化钱包
const initDialog = reactive({
  visible: false,
  submitting: false,
  form: { tenantId: '' }
});
const openInit = () => {
  initDialog.form.tenantId = '';
  initDialog.visible = true;
};
const submitInit = async () => {
  initDialog.submitting = true;
  try {
    await initMainWallet(initDialog.form.tenantId || undefined);
    proxy?.$modal.msgSuccess('钱包初始化成功');
    initDialog.visible = false;
    reloadAll();
  } finally {
    initDialog.submitting = false;
  }
};

// 充值 / 扣减
const opDialog = reactive({
  visible: false,
  submitting: false,
  title: '',
  kind: 'recharge' as 'recharge' | 'deduct',
  row: null as TenantWalletVO | null,
  form: { amount: 100, reason: '' } as WalletOperationForm
});
const openRecharge = (row: TenantWalletVO) => {
  opDialog.kind = 'recharge';
  opDialog.title = '手工充值';
  opDialog.row = row;
  opDialog.form = { amount: 100, reason: '' };
  opDialog.visible = true;
};
const openDeduct = (row: TenantWalletVO) => {
  opDialog.kind = 'deduct';
  opDialog.title = '手工扣减';
  opDialog.row = row;
  opDialog.form = { amount: 100, reason: '' };
  opDialog.visible = true;
};
const submitOp = async () => {
  if (!opDialog.row) return;
  if (!opDialog.form.amount || opDialog.form.amount <= 0) {
    proxy?.$modal.msgWarning('请输入大于 0 的金额');
    return;
  }
  if (!opDialog.form.reason || !opDialog.form.reason.trim()) {
    proxy?.$modal.msgWarning('请填写操作原因');
    return;
  }
  await proxy?.$modal.confirm(
    `确认对钱包 ${opDialog.row.walletId} ${opDialog.kind === 'recharge' ? '充值' : '扣减'} ${money(opDialog.form.amount)} ？`
  );
  opDialog.submitting = true;
  try {
    if (opDialog.kind === 'recharge') {
      await manualRecharge(opDialog.row.walletId, opDialog.form);
    } else {
      await manualDeduct(opDialog.row.walletId, opDialog.form);
    }
    proxy?.$modal.msgSuccess(opDialog.kind === 'recharge' ? '充值成功' : '扣减成功');
    opDialog.visible = false;
    reloadAll();
  } finally {
    opDialog.submitting = false;
  }
};

// 冻结 / 解冻
const onToggleStatus = async (row: TenantWalletVO) => {
  if (row.status === 'DISABLED') return;
  const target = row.status === 'ACTIVE' ? 'FROZEN' : 'ACTIVE';
  const action = target === 'FROZEN' ? '冻结' : '解冻';
  await proxy?.$modal.confirm(`确认${action}租户 ${row.tenantId} 的钱包?`);
  await updateWalletStatus(row.walletId, target);
  proxy?.$modal.msgSuccess(`${action}成功`);
  reloadAll();
};

/** 「···」下拉分发：危险操作集中收纳，各自入口仍带二次确认 */
const onMoreCommand = (cmd: string, row: TenantWalletVO) => {
  if (cmd === 'deduct') openDeduct(row);
  else if (cmd === 'toggle') onToggleStatus(row);
};

// 调阈值
const thresholdDialog = reactive({
  visible: false,
  submitting: false,
  row: null as TenantWalletVO | null,
  form: { lowBalanceThreshold: 0 }
});
const openThreshold = (row: TenantWalletVO) => {
  thresholdDialog.row = row;
  thresholdDialog.form.lowBalanceThreshold = Number(row.lowBalanceThreshold || 0);
  thresholdDialog.visible = true;
};
const submitThreshold = async () => {
  if (!thresholdDialog.row) return;
  thresholdDialog.submitting = true;
  try {
    await updateWalletThreshold(thresholdDialog.row.walletId, thresholdDialog.form.lowBalanceThreshold);
    proxy?.$modal.msgSuccess('阈值更新成功');
    thresholdDialog.visible = false;
    reloadAll();
  } finally {
    thresholdDialog.submitting = false;
  }
};

onMounted(reloadAll);
</script>

<style scoped>
/* 筛选条与表格同卡：容器留给 .ui-toolbar（1px 底线 + 控件高 36），这里只排左右两栏 */
.ui-toolbar {
  justify-content: space-between;
  align-items: flex-start;
}
.search-form {
  flex: 1 1 520px;
  min-width: 0;
  margin: 0;
}
.search-form :deep(.el-form-item) {
  margin-bottom: 8px;
}
.ab-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
/* 375：表格外层容器可横滚，父链 min-width:0，避免整页横向溢出 */
.dcs-ops-table-card {
  min-width: 0;
}
.ui-table-wrap {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
}
/* 表格规格走 Element Plus 变量：表头无底带 / 行线令牌 / 行 hover 中性底 / 冻结列阴影按 EP 默认 */
.ui-table {
  --el-table-text-color: var(--app-muted);
  --el-table-header-text-color: var(--app-faint);
  --el-table-header-bg-color: transparent;
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--app-raised);
  --el-table-current-row-bg-color: var(--app-raised);
  --el-table-fixed-box-shadow: var(--el-box-shadow-light);
  /* 冻结列恢复 EP 默认边缘阴影（EP 仅在表格横向滚动时才画，兼作「右侧还有列」的提示；值取 EP 2.13.5 默认） */
  --el-table-fixed-left-column: inset 10px 0 10px -10px #00000026;
  --el-table-fixed-right-column: inset -10px 0 10px -10px #00000026;
}
.ui-table :deep(.el-table__empty-text) {
  width: 100%;
  line-height: 1.6;
}
.ui-row-action.el-button.is-link {
  color: var(--app-muted);
}
.ui-row-action.el-button.is-link:hover {
  color: var(--app-text);
}
.amount-warn {
  color: var(--el-color-warning);
  font-weight: 600;
}
.amount-danger {
  color: var(--el-color-danger);
  font-weight: 600;
}
.ab-more-btn {
  margin-left: 12px;
}
.ab-more-caret {
  font-size: 10px;
  margin-left: 2px;
}
/* U-18：状态列双药丸并排（正常 + 低于阈值预警），预警不再刷整行彩底 */
.ab-status-cell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  flex-wrap: wrap;
}
/* ===== Q4：单位卡片网格 ===== */
.wallet-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
  min-width: 0;
  padding: 4px 0 12px;
}
.wallet-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  padding: 14px 16px;
  border: 1px solid var(--app-rule);
  border-radius: var(--app-card-radius);
  background: var(--app-surface);
}
/* 低于提醒线：只加一条左缘提示，不刷整卡彩底 */
.wallet-card--low {
  border-left: 2px solid var(--el-color-warning);
}
.wallet-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}
.wallet-card__name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.wallet-card__tenant {
  overflow: hidden;
  color: var(--app-text);
  font-size: var(--dcs-font-md);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wallet-card__id {
  overflow: hidden;
  color: var(--app-faint);
  font-size: var(--dcs-font-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wallet-card__badges {
  display: inline-flex;
  flex: 0 0 auto;
  gap: 4px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.wallet-card__amount {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.wallet-card__balance {
  color: var(--app-text);
  font-size: 26px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1.15;
}
.wallet-card__meta {
  color: var(--app-muted);
  font-size: var(--dcs-font-sm);
  font-variant-numeric: tabular-nums;
}
.wallet-card__hint {
  margin: 0;
  color: var(--app-muted);
  font-size: var(--dcs-font-sm);
  line-height: 1.45;
}
.wallet-card__actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  margin-top: auto;
  padding-top: 4px;
  border-top: 1px solid var(--app-rule);
}
.ab-perm-note {
  margin: 4px 0 12px;
  color: var(--app-faint);
  font-size: var(--dcs-font-sm);
}
/* 手机档：卡片单列，余额略小 */
@media (max-width: 992px) {
  .wallet-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .wallet-card__balance {
    font-size: 26px;
  }
}
</style>
