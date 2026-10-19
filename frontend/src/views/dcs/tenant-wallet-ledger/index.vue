<template>
  <div class="dcs-ops-page">
    <DcsOperationsPageHeader
      title="钱包流水"
      subtitle="核对充值、扣减、冻结、解冻与服务消费等余额变动，快速定位每一笔资金变化。"
      :metrics="headerMetrics"
    >
      <template #actions><el-button :icon="Refresh" @click="getList">刷新</el-button></template>
    </DcsOperationsPageHeader>

    <el-card shadow="never" class="dcs-ops-table-card">
      <!-- 搜索栏 -->
      <el-form :model="queryParams" :inline="true" class="search-form">
        <el-form-item label="租户ID">
          <el-input v-model="queryParams.tenantId" placeholder="租户ID" clearable style="width: 180px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="钱包ID">
          <el-input v-model="queryParams.walletId" placeholder="钱包ID" clearable style="width: 150px" @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="方向">
          <el-select v-model="queryParams.direction" clearable placeholder="全部" style="width: 130px" @change="handleQuery">
            <el-option label="收入 IN" value="IN" />
            <el-option label="支出 OUT" value="OUT" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务类型">
          <el-select v-model="queryParams.bizType" clearable placeholder="全部" style="width: 150px" @change="handleQuery">
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
        <el-table-column label="业务类型" width="140" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ bizTypeLabel(row.bizType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="方向" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.direction === 'IN' ? 'success' : 'danger'">{{ directionLabel(row.direction) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="130" align="right">
          <template #default="{ row }">
            <span :class="row.direction === 'IN' ? 'amount-in' : 'amount-out'">
              {{ row.direction === 'IN' ? '+' : '-' }}{{ money(row.amount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="余额变化" min-width="180" align="center">
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
            <!-- 业务批7 Q5：服务码中文化（raw code 进 title 供排障）——与 AI 助手管理
                 计费口径（原「服务码价目」行，2026-10-14 起改动态「消费方式」）同款零技术词，充值/赠送等非 AI 行不显服务 -->
            <span :title="row.serviceCode || undefined">{{ row.serviceCodeLabel || serviceCodeLabel(row.serviceCode) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" prop="createTime" width="170" />
      </el-table>

      <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { Search, Refresh, RefreshLeft, ArrowRight } from '@element-plus/icons-vue';
import { listWalletLedger, type WalletLedgerVO } from '@/api/dcs/tenant-wallet-ledger';

defineOptions({ name: 'DcsTenantWalletLedger' });

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const ledgerList = ref<WalletLedgerVO[]>([]);
const total = ref(0);
const headerMetrics = computed(() => [
  { label: '流水总数', value: total.value, hint: '符合当前筛选条件', tone: 'primary' as const },
  {
    label: '当前页收入',
    value: money(ledgerList.value.filter((item) => item.direction === 'IN').reduce((sum, item) => sum + Number(item.amount || 0), 0)),
    hint: '当前页 IN 流水合计',
    tone: 'success' as const
  },
  {
    label: '当前页支出',
    value: money(ledgerList.value.filter((item) => item.direction === 'OUT').reduce((sum, item) => sum + Number(item.amount || 0), 0)),
    hint: '当前页 OUT 流水合计',
    tone: 'danger' as const
  },
  {
    label: '业务类型',
    value: new Set(ledgerList.value.map((item) => item.bizType).filter(Boolean)).size,
    hint: '当前页资金变动类型',
    tone: 'neutral' as const
  }
]);
const queryParams = reactive({
  pageNum: 1,
  pageSize: 20,
  tenantId: '',
  walletId: '',
  direction: '',
  bizType: ''
});

const bizTypeLabel = (t: string) => BIZ_TYPE_TEXT[t] || t || '--';

/** 业务批7 Q5：全量服务码中文映射（与后端 mapCallerToServiceCode/TenantWalletService 写入点一一对应）。
 *  业务批(1015) Q2：补批8 新增的钱包扣款服务码——订阅/加油包从钱包扣的钱（此前裸码直出）；
 *  后端已同步在 ledger VO 下发 serviceCodeLabel（本表退化为兜底） */
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

/** 业务类型下拉/标签共用映射（业务批7 Q5：筛选从手输英文码改下拉） */
const BIZ_TYPE_TEXT: Record<string, string> = {
  RECHARGE: '充值',
  DEDUCT: '扣减',
  FREEZE: '冻结',
  UNFREEZE: '解冻',
  CONSUME: '消费',
  REFUND: '退款',
  ADJUST: '调整'
};

const directionLabel = (d: string) => {
  const map: Record<string, string> = { IN: '收入', OUT: '支出' };
  return map[d] || d || '--';
};

const money = (value?: number) => `${Number(value || 0).toFixed(2)}`;

const getList = async () => {
  loading.value = true;
  try {
    const res: any = await listWalletLedger(queryParams);
    ledgerList.value = res.rows || [];
    total.value = res.total || 0;
  } catch {
    proxy?.$modal.msgError('流水加载失败');
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
  queryParams.walletId = '';
  queryParams.direction = '';
  queryParams.bizType = '';
  queryParams.pageNum = 1;
  getList();
};

onMounted(getList);
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.page-title {
  font-size: 18px;
  font-weight: 600;
}
.page-subtitle {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
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
.balance-change {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.balance-change .arrow {
  color: #909399;
}
</style>
