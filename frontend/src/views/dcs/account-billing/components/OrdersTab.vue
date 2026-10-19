<template>
  <el-card shadow="never" class="dcs-ops-table-card">
    <div class="ab-toolbar">
      <el-form :model="queryParams" :inline="true" class="search-form" @submit.prevent>
        <el-form-item label="租户ID">
          <el-input v-model="queryParams.tenantId" placeholder="租户ID" clearable style="width: 200px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="订单状态">
          <el-select v-model="queryParams.status" clearable placeholder="全部" style="width: 130px" @change="handleQuery">
            <el-option label="待支付" value="PENDING" />
            <el-option label="已支付" value="PAID" />
            <el-option label="已关闭" value="CLOSED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleQuery">搜索</el-button>
          <el-button :icon="RefreshLeft" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="ab-toolbar-actions">
        <el-button type="primary" :icon="Plus" @click="openCreate">创建订单</el-button>
        <el-button :icon="Refresh" @click="getList">刷新</el-button>
      </div>
    </div>

    <!-- 零数据降级摘要条：没有订单时不摆四格 0 的统计带，改一条人话提示 -->
    <el-alert
      v-if="!loading && total === 0"
      type="info"
      :closable="false"
      show-icon
      class="ab-empty-strip"
      title="尚未产生任何充值订单"
      description="钱包初始化时系统会赠送体验金；日常给租户加钱在「账户总览」页签直接点「充值」，只有需要走订单留痕的充值才在这里创建。"
    />

    <el-table v-loading="loading" :data="orderList" border size="small">
      <el-table-column label="订单号" prop="orderNo" min-width="200" show-overflow-tooltip />
      <el-table-column label="租户ID" prop="tenantId" min-width="150" show-overflow-tooltip />
      <el-table-column label="钱包ID" prop="walletId" min-width="172" align="center">
        <template #default="{ row }"
          ><span class="dcs-ops-id">{{ row.walletId }}</span></template
        >
      </el-table-column>
      <el-table-column label="渠道" width="110" align="center">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ channelLabel(row.channel) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="金额" width="140" align="right">
        <template #default="{ row }">
          <span class="amount">{{ money(row.amount) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="支付时间" width="170">
        <template #default="{ row }">{{ row.payTime || '--' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" prop="createTime" width="170" />
      <el-table-column label="备注" prop="remark" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.remark || '--' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" align="center" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'PENDING'" v-hasPermi="['dcs:payment-order:close']" link type="danger" size="small" @click="onClose(row)"
            >关闭</el-button
          >
          <span v-else class="text-muted">--</span>
        </template>
      </el-table-column>
      <template #empty>
        <div class="dcs-ops-empty">
          <el-empty description="暂无充值订单" :image-size="96">
            <p class="order-empty-hint">可先创建一笔手工充值订单，再在列表中跟踪支付状态。</p>
            <el-button type="primary" :icon="Plus" @click="openCreate">创建首笔订单</el-button>
          </el-empty>
        </div>
      </template>
    </el-table>

    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />

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
  </el-card>
</template>

<script setup lang="ts">
/**
 * 充值订单页签（菜单IA重组批2，2026-10-16）：自 wallet-payment-order/index.vue 迁移。
 * 升级项：金额 ¥ 千分位 · 零数据时统计带降级为一条人话摘要条（emit 空 metrics 隐藏四格 0）。
 */
import { Plus, Search, Refresh, RefreshLeft } from '@element-plus/icons-vue';
import type { FormInstance, FormRules } from 'element-plus';
import type { DcsOperationsMetric } from '@/components/DcsOperationsPageHeader/index.vue';
import { listWalletPaymentOrder, createWalletPaymentOrder, closeWalletPaymentOrder, type WalletPaymentOrderVO } from '@/api/dcs/account-billing';

defineOptions({ name: 'AccountBillingOrdersTab' });

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
const orderList = ref<WalletPaymentOrderVO[]>([]);
const total = ref(0);
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

const statusType = (s: string) => {
  const map: Record<string, string> = { PENDING: 'warning', PAID: 'success', CLOSED: 'info' };
  return map[s] || 'info';
};

const money = (value?: number) => `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

const emitMetrics = () => {
  if (metricsDisposed) return;
  // 零数据降级：不摆四格 0，摘要条在模板里给出解释
  if (total.value === 0) {
    emit('metrics', []);
    return;
  }
  const rows = orderList.value;
  emit('metrics', [
    { label: '订单总数', value: total.value, hint: '符合当前筛选条件', tone: 'primary' },
    { label: '待支付', value: rows.filter((i) => i.status === 'PENDING').length, hint: '当前页待处理订单', tone: 'warning' },
    { label: '已支付', value: rows.filter((i) => i.status === 'PAID').length, hint: '当前页已完成订单', tone: 'success' },
    {
      label: '当前页金额',
      value: money(rows.reduce((s, i) => s + Number(i.amount || 0), 0)),
      hint: '当前页订单金额合计',
      tone: 'neutral'
    }
  ]);
};

const getList = async () => {
  loading.value = true;
  try {
    const res: any = await listWalletPaymentOrder(queryParams);
    orderList.value = res.rows || [];
    total.value = res.total || 0;
    emitMetrics();
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
.ab-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 8px;
}
.ab-toolbar .search-form {
  margin-bottom: 8px;
}
.ab-toolbar-actions {
  display: flex;
  gap: 0;
  margin-bottom: 8px;
}
.ab-empty-strip {
  margin-bottom: 12px;
}
.amount {
  font-weight: 600;
  color: var(--el-color-primary);
}
.text-muted {
  color: #c0c4cc;
}
</style>
