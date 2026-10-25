<template>
  <div class="dcs-ops-page">
    <DcsOperationsPageHeader
      eyebrow="钱包与计费"
      title="租户钱包"
      subtitle="维护租户主钱包的余额、冻结资金、可用额度与低余额阈值，并保留人工资金操作入口。"
      :metrics="headerMetrics"
    >
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openInit">初始化钱包</el-button>
        <el-button :icon="Refresh" @click="getList">刷新</el-button>
      </template>
    </DcsOperationsPageHeader>

    <el-card shadow="never" class="dcs-ops-table-card">
      <!-- 搜索栏 -->
      <div class="ui-toolbar">
        <el-form :model="queryParams" :inline="true" class="search-form wallet-search-form">
          <el-form-item label="租户ID">
            <el-input v-model="queryParams.tenantId" placeholder="请输入租户ID" clearable style="width: 220px" @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item>
            <el-button :icon="Search" @click="handleQuery">搜索</el-button>
            <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <div class="ui-table-wrap">
        <el-table v-loading="loading" :data="walletList" class="ui-table">
          <el-table-column label="钱包ID" prop="walletId" min-width="172" align="center">
            <template #default="{ row }"
              ><span class="dcs-ops-id">{{ row.walletId }}</span></template
            >
          </el-table-column>
          <el-table-column label="租户ID" prop="tenantId" min-width="160" show-overflow-tooltip />
          <el-table-column label="类型" width="110" align="center">
            <template #default="{ row }">
              <span class="ui-pill" :class="row.walletType === 'MAIN' ? 'ui-pill--pos' : 'ui-pill--muted'">{{
                walletTypeLabel(row.walletType)
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="余额" width="120" align="right">
            <template #default="{ row }">{{ money(row.balanceAmount) }}</template>
          </el-table-column>
          <el-table-column label="冻结" width="120" align="right">
            <template #default="{ row }">
              <span :class="{ 'amount-warn': Number(row.frozenAmount || 0) > 0 }">{{ money(row.frozenAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="可用" width="120" align="right">
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
          <el-table-column label="操作" width="340" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link class="ui-row-action" @click="openRecharge(row)">充值</el-button>
              <el-button link class="ui-row-action" @click="openDeduct(row)">扣减</el-button>
              <el-button v-if="row.status !== 'DISABLED'" link :type="row.status === 'ACTIVE' ? 'danger' : 'success'" @click="onToggleStatus(row)">
                {{ row.status === 'ACTIVE' ? '冻结' : '解冻' }}
              </el-button>
              <el-button link class="ui-row-action" @click="openThreshold(row)">调阈值</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">暂无租户钱包</p>
              <p class="ui-empty__hint">点右上角「初始化钱包」为当前租户创建主钱包。</p>
            </div>
          </template>
        </el-table>
      </div>

      <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </el-card>

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
  </div>
</template>

<script setup lang="ts">
import { Plus, Search, Refresh, RefreshLeft } from '@element-plus/icons-vue';
import {
  listTenantWallet,
  initMainWallet,
  manualRecharge,
  manualDeduct,
  updateWalletThreshold,
  updateWalletStatus,
  type TenantWalletVO,
  type WalletOperationForm
} from '@/api/dcs/tenant-wallet';

defineOptions({ name: 'DcsTenantWallet' });

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const walletList = ref<TenantWalletVO[]>([]);
const total = ref(0);
const headerMetrics = computed(() => [
  { label: '钱包数量', value: total.value, hint: '符合当前租户筛选条件', tone: 'primary' as const },
  {
    label: '当前页余额',
    value: money(walletList.value.reduce((sum, item) => sum + Number(item.balanceAmount || 0), 0)),
    hint: '当前页主钱包余额合计',
    tone: 'success' as const
  },
  {
    label: '当前页可用',
    value: money(walletList.value.reduce((sum, item) => sum + Number(item.availableAmount || 0), 0)),
    hint: '扣除冻结金额后的可用资金',
    tone: 'neutral' as const
  },
  {
    label: '异常钱包',
    value: walletList.value.filter((item) => item.status !== 'ACTIVE').length,
    hint: '当前页冻结或异常状态',
    tone: 'warning' as const
  }
]);
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

/** 状态药丸：正常=成功 / 冻结=警告 / 停用=中性 */
const statusPillClass = (s: string) => {
  const map: Record<string, string> = { ACTIVE: 'ui-pill--pos', FROZEN: 'ui-pill--warn', DISABLED: 'ui-pill--muted' };
  return map[s] || 'ui-pill--muted';
};

const money = (value?: number) => `${Number(value || 0).toFixed(2)}`;

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
    getList();
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
    getList();
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
  getList();
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
    getList();
  } finally {
    thresholdDialog.submitting = false;
  }
};

onMounted(getList);
</script>

<style scoped>
.dcs-ops-page {
  min-width: 0;
}
/* 页头主操作与工具栏控件同高 36 */
.dcs-ops-header :deep(.dcs-ops-header__actions) {
  --el-component-size: var(--dcs-control-h);
}
.dcs-ops-header :deep(.dcs-ops-header__actions .el-button:not(.is-text):not(.is-link)) {
  height: var(--dcs-control-h);
}
/* 筛选条与表格同卡：.ui-toolbar 给 1px 底线与 36 高控件 */
.search-form {
  width: 100%;
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
</style>
