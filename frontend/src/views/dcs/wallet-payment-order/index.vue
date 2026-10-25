<template>
  <div class="dcs-ops-page">
    <DcsOperationsPageHeader
      eyebrow="钱包与计费"
      title="充值订单"
      subtitle="创建和跟踪钱包充值订单，清晰区分待支付、已支付与已关闭状态。"
      :metrics="headerMetrics"
    >
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openCreate">创建订单</el-button>
        <el-button :icon="Refresh" @click="getList">刷新</el-button>
      </template>
    </DcsOperationsPageHeader>

    <el-card shadow="never" class="dcs-ops-table-card">
      <!-- 搜索栏 -->
      <div class="ui-toolbar">
        <el-form :model="queryParams" :inline="true" class="search-form" @submit.prevent>
          <el-form-item label="租户ID">
            <el-input v-model="queryParams.tenantId" placeholder="请输入租户ID" clearable style="width: 200px" @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="订单状态">
            <el-select v-model="queryParams.status" clearable placeholder="全部" style="width: 140px" @change="handleQuery">
              <el-option label="待支付" value="PENDING" />
              <el-option label="已支付" value="PAID" />
              <el-option label="已关闭" value="CLOSED" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button :icon="Search" @click="handleQuery">搜索</el-button>
            <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <div class="ui-table-wrap">
        <el-table v-loading="loading" :data="orderList" class="ui-table">
          <el-table-column label="订单号" prop="orderNo" min-width="200" show-overflow-tooltip />
          <el-table-column label="租户ID" prop="tenantId" min-width="150" show-overflow-tooltip />
          <el-table-column label="钱包ID" prop="walletId" min-width="172" align="center">
            <template #default="{ row }"
              ><span class="dcs-ops-id">{{ row.walletId }}</span></template
            >
          </el-table-column>
          <el-table-column label="渠道" width="120" align="center">
            <template #default="{ row }">
              <span class="ui-pill">{{ channelLabel(row.channel) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="130" align="right">
            <template #default="{ row }">
              <span class="amount">{{ money(row.amount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <span class="ui-pill" :class="statusPillClass(row.status)">{{ statusLabel(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="支付时间" prop="payTime" width="170">
            <template #default="{ row }">{{ row.payTime || '--' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" prop="createTime" width="170" />
          <el-table-column label="备注" prop="remark" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.remark || '--' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PENDING'" v-hasPermi="['dcs:payment-order:close']" link type="danger" @click="onClose(row)"
                >关闭</el-button
              >
              <span v-else class="text-muted">--</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">暂无充值订单</p>
              <p class="ui-empty__hint">可先创建一笔手工充值订单，再在列表中跟踪支付状态。</p>
              <div class="ui-empty__actions">
                <el-button :icon="Plus" @click="openCreate">创建首笔订单</el-button>
              </div>
            </div>
          </template>
        </el-table>
      </div>

      <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </el-card>

    <!-- 创建订单 -->
    <el-dialog v-model="createDialog.visible" title="创建充值订单" width="500px" append-to-body>
      <el-form ref="formRef" :model="createDialog.form" :rules="rules" label-width="90px">
        <el-form-item label="钱包ID" prop="walletId">
          <el-input-number v-model="createDialog.form.walletId" :min="1" controls-position="right" style="width: 100%" placeholder="目标钱包ID" />
        </el-form-item>
        <el-form-item label="金额" prop="amount">
          <el-input-number v-model="createDialog.form.amount" :min="0.01" :precision="2" :step="100" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="渠道" prop="channel">
          <el-select v-model="createDialog.form.channel" style="width: 100%">
            <el-option label="手工(MANUAL)" value="MANUAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="createDialog.form.remark" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.submitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { Plus, Search, Refresh, RefreshLeft } from '@element-plus/icons-vue';
import type { FormInstance, FormRules } from 'element-plus';
import { listWalletPaymentOrder, createWalletPaymentOrder, closeWalletPaymentOrder, type WalletPaymentOrderVO } from '@/api/dcs/wallet-payment-order';

defineOptions({ name: 'DcsWalletPaymentOrder' });

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const orderList = ref<WalletPaymentOrderVO[]>([]);
const total = ref(0);
const headerMetrics = computed(() => [
  { label: '订单总数', value: total.value, hint: '符合当前筛选条件', tone: 'primary' as const },
  { label: '待支付', value: orderList.value.filter((item) => item.status === 'PENDING').length, hint: '当前页待处理订单', tone: 'warning' as const },
  { label: '已支付', value: orderList.value.filter((item) => item.status === 'PAID').length, hint: '当前页已完成订单', tone: 'success' as const },
  {
    label: '当前页金额',
    value: money(orderList.value.reduce((sum, item) => sum + Number(item.amount || 0), 0)),
    hint: '当前页订单金额合计',
    tone: 'neutral' as const
  }
]);
const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  tenantId: '',
  status: ''
});

const channelLabel = (c: string) => {
  const map: Record<string, string> = { MANUAL: '手工', WECHAT: '微信', ALIPAY: '支付宝', BALANCE: '余额' };
  return map[c] || c || '--';
};

const statusLabel = (s: string) => {
  const map: Record<string, string> = { PENDING: '待支付', PAID: '已支付', CLOSED: '已关闭' };
  return map[s] || s || '--';
};

/** 状态药丸：待支付=警告 / 已支付=成功 / 已关闭=中性 */
const statusPillClass = (s: string) => {
  const map: Record<string, string> = { PENDING: 'ui-pill--warn', PAID: 'ui-pill--pos', CLOSED: 'ui-pill--muted' };
  return map[s] || 'ui-pill--muted';
};

const money = (value?: number) => `${Number(value || 0).toFixed(2)}`;

const getList = async () => {
  loading.value = true;
  try {
    const res: any = await listWalletPaymentOrder(queryParams);
    orderList.value = res.rows || [];
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
  queryParams.status = '';
  queryParams.pageNum = 1;
  getList();
};

// 创建订单
const formRef = ref<FormInstance>();
const createDialog = reactive({
  visible: false,
  submitting: false,
  form: {
    walletId: undefined as number | undefined,
    amount: 100,
    channel: 'MANUAL',
    remark: ''
  }
});
const rules: FormRules = {
  walletId: [{ required: true, message: '请输入钱包ID', trigger: 'blur' }],
  amount: [{ required: true, message: '请输入金额', trigger: 'blur' }],
  channel: [{ required: true, message: '请选择渠道', trigger: 'change' }]
};

const openCreate = () => {
  createDialog.form = { walletId: undefined, amount: 100, channel: 'MANUAL', remark: '' };
  createDialog.visible = true;
  nextTick(() => formRef.value?.clearValidate());
};

const submitCreate = async () => {
  if (!formRef.value) return;
  await formRef.value.validate();
  createDialog.submitting = true;
  try {
    await createWalletPaymentOrder(createDialog.form);
    proxy?.$modal.msgSuccess('订单创建成功');
    createDialog.visible = false;
    getList();
  } finally {
    createDialog.submitting = false;
  }
};

// 关闭订单
const onClose = async (row: WalletPaymentOrderVO) => {
  await proxy?.$modal.confirm(`确认关闭订单「${row.orderNo}」?关闭后无法继续支付`);
  await closeWalletPaymentOrder(row.orderId);
  proxy?.$modal.msgSuccess('订单已关闭');
  getList();
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
.amount {
  color: var(--app-text);
  font-weight: 600;
}
.text-muted {
  color: var(--app-faint);
}
</style>
