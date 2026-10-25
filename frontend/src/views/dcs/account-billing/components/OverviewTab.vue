<template>
  <el-card shadow="never" class="dcs-ops-table-card">
    <div class="ui-toolbar">
      <el-form :model="queryParams" :inline="true" class="search-form" @submit.prevent>
        <el-form-item label="租户ID">
          <el-input v-model="queryParams.tenantId" placeholder="请输入租户ID" clearable style="width: 220px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item>
          <el-button :icon="Search" @click="handleQuery">搜索</el-button>
          <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="ab-toolbar-actions">
        <el-button type="primary" :icon="Plus" @click="openInit">初始化钱包</el-button>
        <el-button :icon="Refresh" @click="reloadAll">刷新</el-button>
      </div>
    </div>

    <div class="ui-table-wrap">
      <el-table v-loading="loading" :data="walletList" class="ui-table" :row-class-name="walletRowClass">
        <el-table-column label="钱包" min-width="150">
          <template #default="{ row }">
            <span :title="`钱包ID ${row.walletId}`" class="dcs-ops-id">{{ row.tenantId }} · 尾{{ shortId(row.walletId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100" align="center">
          <template #default="{ row }">
            <span class="ui-pill" :class="row.walletType === 'MAIN' ? 'ui-pill--pos' : 'ui-pill--muted'">{{ walletTypeLabel(row.walletType) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="余额" width="130" align="right">
          <template #default="{ row }">{{ money(row.balanceAmount) }}</template>
        </el-table-column>
        <el-table-column label="冻结" width="120" align="right">
          <template #default="{ row }">
            <span :class="{ 'amount-warn': Number(row.frozenAmount || 0) > 0 }">{{ money(row.frozenAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="可用" width="130" align="right">
          <template #default="{ row }">
            <span :class="{ 'amount-danger': Number(row.availableAmount || 0) < 0 }">{{ money(row.availableAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <span class="ui-pill" :class="statusPillClass(row.status)">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="低余额阈值" width="120" align="right">
          <template #default="{ row }">{{ money(row.lowBalanceThreshold) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="170" />
        <el-table-column label="操作" width="210" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link class="ui-row-action" @click="openRecharge(row)">充值</el-button>
            <el-button link class="ui-row-action" @click="openThreshold(row)">调阈值</el-button>
            <el-dropdown trigger="click" @command="(cmd: string) => onMoreCommand(cmd, row)">
              <el-button link class="ab-more-btn ui-row-action" aria-label="更多资金操作">
                ···<el-icon class="ab-more-caret"><CaretBottom /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="deduct">扣减</el-dropdown-item>
                  <el-dropdown-item v-if="row.status !== 'DISABLED'" command="toggle">{{
                    row.status === 'ACTIVE' ? '冻结' : '解冻'
                  }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
        <template #empty>
          <div class="ui-empty">
            <p class="ui-empty__title">暂无钱包</p>
            <p class="ui-empty__hint">初始化钱包后，这里会显示租户钱包的余额、冻结与阈值状态。</p>
          </div>
        </template>
      </el-table>
    </div>

    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />

    <!-- 初始化钱包 -->
    <el-dialog v-model="initDialog.visible" title="初始化钱包" width="460px" append-to-body>
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
    <el-dialog v-model="opDialog.visible" :title="opDialog.title" width="480px" append-to-body>
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
    <el-dialog v-model="thresholdDialog.visible" title="调整低余额阈值" width="460px" append-to-body>
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
import type { DcsOperationsMetric } from '@/components/DcsOperationsPageHeader/index.vue';
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
} from '@/api/dcs/account-billing';

defineOptions({ name: 'AccountBillingOverviewTab' });

const emit = defineEmits<{ metrics: [m: DcsOperationsMetric[]] }>();

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
const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  tenantId: ''
});

const walletTypeLabel = (t: string) => {
  const map: Record<string, string> = { MAIN: '主钱包', SUB: '子钱包' };
  return map[t] || t || '--';
};

const statusLabel = (s: string) => {
  const map: Record<string, string> = { ACTIVE: '正常', FROZEN: '已冻结', DISABLED: '已停用' };
  return map[s] || s || '--';
};

/** 状态药丸：与全站四组语义色对齐（正常=成功 / 冻结=警告 / 停用=中性） */
const statusPillClass = (s: string) => {
  const map: Record<string, string> = { ACTIVE: 'ui-pill--pos', FROZEN: 'ui-pill--warn', DISABLED: 'ui-pill--muted' };
  return map[s] || 'ui-pill--muted';
};

const money = (value?: number) => `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

const shortId = (id?: number | string) => String(id || '').slice(-4);

/** 余额低于阈值 → 整行浅橙预警（样式见 .ab-row-low 的 :deep 规则） */
const walletRowClass = ({ row }: { row: TenantWalletVO }) => {
  const th = Number(row.lowBalanceThreshold || 0);
  return th > 0 && Number(row.balanceAmount || 0) < th ? 'ab-row-low' : '';
};

const emitMetrics = () => {
  if (metricsDisposed) return;
  if (!summary.value) return;
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
    emitMetrics();
  } catch {
    summary.value = null;
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
/* 表格规格走 Element Plus 变量：表头无底带 / 行线令牌 / 行 hover 中性底 / 冻结列无阴影 */
.ui-table {
  --el-table-border-color: var(--app-rule);
  --el-table-border: 1px solid var(--app-rule);
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
/* 低于阈值整行浅橙预警 + 首格左缘 3px 竖条（深色下用 --el-color-warning-light-9 的暗底） */
:deep(.ab-row-low td) {
  background: var(--el-color-warning-light-9);
}
:deep(.ab-row-low td:first-child) {
  box-shadow: inset 3px 0 0 var(--el-color-warning);
}
</style>
