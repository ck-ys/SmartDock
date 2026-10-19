<template>
  <div class="risk-rules">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>风控规则配置</span>
          <div>
            <el-button v-hasPermi="['dcs:risk:rule:edit']" type="primary" :icon="Plus" @click="openAdd">新增规则</el-button>
            <el-button link :icon="Refresh" @click="loadRules">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" class="mb-3" show-icon title="计分规则说明">
        <div class="rule-guide">
          <p>单次分值=该事件每发生一次贡献的分；累计到窗口内上限后按 L1→L4 阈值逐级定风险（阈值须递增）。</p>
          <p>窗口(分钟)=该事件类型的计数窗口；GLOBAL 行是全部事件总分的聚合窗口（兜底定级）。</p>
        </div>
      </el-alert>

      <el-table v-loading="loading" :data="rules" stripe :row-class-name="rowClassName">
        <el-table-column label="事件类型" min-width="140">
          <template #default="{ row }">
            <span v-if="row._dirty" class="dirty-dot" title="有未保存的修改"></span>
            <el-tag :type="EVENT_TYPE_COLORS[row.eventType]" effect="plain" size="small">
              {{ EVENT_TYPE_LABELS[row.eventType] || row.eventType }}
            </el-tag>
            <div class="text-xs text-gray-400 mt-1">{{ row.ruleScope === 'GLOBAL' ? '聚合兜底策略' : row.eventType }}</div>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="180" prop="description" show-overflow-tooltip />

        <el-table-column label="单次分值" width="110" align="center">
          <template #default="{ row }">
            <span v-if="row.ruleScope === 'GLOBAL'" class="text-gray-400">不适用</span>
            <el-input-number
              v-else
              v-model="row.weight"
              :min="1"
              :max="20"
              size="small"
              :controls="false"
              style="width: 72px"
              @change="markDirty(row)"
            />
          </template>
        </el-table-column>

        <el-table-column label="窗口(分钟)" width="120" align="center">
          <template #default="{ row }">
            <el-input-number
              v-model="row.windowMinutes"
              :min="1"
              :max="10080"
              size="small"
              :controls="false"
              style="width: 82px"
              @change="markDirty(row)"
            />
          </template>
        </el-table-column>

        <el-table-column v-for="f in ['thresholdL1', 'thresholdL2', 'thresholdL3', 'thresholdL4']" :key="f" :width="82" align="center">
          <template #header>
            <span>L{{ f.slice(-1) }}<HelpHint :content="'累计分达到该值触发 L' + f.slice(-1) + ' 风险级，须大于前一级阈值'" /></span>
          </template>
          <template #default="{ row }">
            <!-- placeholder 显示前级阈值, 直观传达递增约束(K3 P2-1) -->
            <el-input-number
              v-model="row[f]"
              :min="1"
              :max="100"
              size="small"
              :controls="false"
              :placeholder="f === 'thresholdL1' ? '≥1' : '> ' + (row['thresholdL' + (Number(f.slice(-1)) - 1)] ?? '—')"
              style="width: 64px"
              :class="{ 'threshold-error': !!thresholdError(row) }"
              @change="markDirty(row)"
            />
          </template>
        </el-table-column>

        <el-table-column label="启用" width="100" align="center">
          <template #default="{ row }">
            <el-switch
              v-model="row.enabled"
              active-value="1"
              inactive-value="0"
              :loading="row._toggling"
              @change="(val: '0' | '1') => onToggleEnabled(row, val)"
            />
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip v-if="thresholdError(row)" :content="thresholdError(row)" placement="top">
              <el-icon class="threshold-warn"><WarningFilled /></el-icon>
            </el-tooltip>
            <el-button
              v-hasPermi="['dcs:risk:rule:edit']"
              :type="row._dirty ? 'primary' : 'default'"
              link
              size="small"
              :disabled="!row._dirty || !!thresholdError(row)"
              :loading="row._saving"
              @click="onSave(row)"
              >保存</el-button
            >
            <el-button v-if="row.ruleScope !== 'GLOBAL'" v-hasPermi="['dcs:risk:rule:edit']" type="danger" link size="small" @click="onDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- Req 7.2: 新增规则对话框; 事件类型改可建词下拉(2026-08-23, 替代手输英文枚举) -->
      <el-dialog v-model="addDialog.visible" title="新增风控规则" width="520px">
        <el-form :model="addDialog.form" label-width="110px">
          <el-form-item label="事件类型" required>
            <el-select
              v-model="addDialog.form.eventType"
              filterable
              allow-create
              default-first-option
              placeholder="选择已有类型, 或输入新英文枚举(大写)"
              style="width: 100%"
            >
              <el-option v-for="t in eventTypeOptions" :key="t" :label="`${EVENT_TYPE_LABELS[t] || t} (${t})`" :value="t" />
            </el-select>
          </el-form-item>
          <el-form-item label="说明">
            <el-input v-model="addDialog.form.description" placeholder="可选" />
          </el-form-item>
          <el-form-item label="单次分值">
            <el-input-number v-model="addDialog.form.weight" :min="1" :max="20" :controls="false" />
          </el-form-item>
          <el-form-item label="窗口(分钟)">
            <el-input-number v-model="addDialog.form.windowMinutes" :min="1" :max="10080" :controls="false" />
          </el-form-item>
          <el-form-item label="L1 阈值">
            <el-input-number v-model="addDialog.form.thresholdL1" :min="1" :max="100" :controls="false" placeholder=">=1" />
          </el-form-item>
          <el-form-item label="L2">
            <el-input-number
              v-model="addDialog.form.thresholdL2"
              :min="1"
              :max="100"
              :controls="false"
              :placeholder="`> ${addDialog.form.thresholdL1}`"
            />
          </el-form-item>
          <el-form-item label="L3">
            <el-input-number
              v-model="addDialog.form.thresholdL3"
              :min="1"
              :max="100"
              :controls="false"
              :placeholder="`> ${addDialog.form.thresholdL2}`"
            />
          </el-form-item>
          <el-form-item label="L4">
            <el-input-number
              v-model="addDialog.form.thresholdL4"
              :min="1"
              :max="100"
              :controls="false"
              :placeholder="`> ${addDialog.form.thresholdL3}`"
            />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="addDialog.form.enabled" active-value="1" inactive-value="0" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="addDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="addDialog.submitting" @click="submitAdd">保存</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { Refresh, Plus, WarningFilled } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listRules, updateRule, addRule, deleteRule, toggleRuleEnabled } from '@/api/dcs/risk';
import { EVENT_TYPE_LABELS, EVENT_TYPE_COLORS, type RiskRuleVO } from '@/api/dcs/risk/types';
import HelpHint from '@/components/HelpHint/index.vue';

defineOptions({ name: 'DcsRiskRules' });

const { proxy } = getCurrentInstance() as any;

type RuleRow = RiskRuleVO & { _saving?: boolean; _toggling?: boolean; _dirty?: boolean; _orig?: Record<string, any> };
const rules = ref<RuleRow[]>([]);
const loading = ref(false);

/** 新增规则的事件类型选项: 字典枚举 ∪ 已有规则类型(allow-create 支持自定义新枚举) */
const eventTypeOptions = computed(() => {
  const set = new Set<string>([...Object.keys(EVENT_TYPE_LABELS), ...rules.value.map((r) => r.eventType)]);
  return [...set];
});

const loadRules = async () => {
  loading.value = true;
  try {
    const r: any = await listRules();
    if (r.code === 200) {
      // _orig 快照用于脏标记: 行内编辑改动未保存时与已保存行视觉区分(2026-08-23)
      rules.value = (r.data || []).map((x: any) => ({
        ...x,
        _saving: false,
        _toggling: false,
        _dirty: false,
        _orig: {
          weight: x.weight,
          windowMinutes: x.windowMinutes,
          thresholdL1: x.thresholdL1,
          thresholdL2: x.thresholdL2,
          thresholdL3: x.thresholdL3,
          thresholdL4: x.thresholdL4
        }
      }));
    }
  } finally {
    loading.value = false;
  }
};

/** 行内编辑变更后重算脏态(与加载快照逐字段比对) */
const markDirty = (row: RuleRow) => {
  const o = row._orig || {};
  row._dirty = ['weight', 'windowMinutes', 'thresholdL1', 'thresholdL2', 'thresholdL3', 'thresholdL4'].some((k) => (row as any)[k] !== o[k]);
};

/**
 * 校验 L1<L2<L3<L4 阈值递增关系。
 * 风险分随累计递增，阈值必须严格递增才能正确分级（L1 触发低风险、L4 触发极高危）。
 * 返回错误消息；返回空字符串表示校验通过(2026-08-23 起供行内红框+tooltip可视化复用)。
 */
const validateThresholds = (row: RiskRuleVO): string => {
  const { thresholdL1, thresholdL2, thresholdL3, thresholdL4 } = row;
  if (thresholdL1 >= thresholdL2) return `L1 阈值(${thresholdL1}) 必须小于 L2(${thresholdL2})`;
  if (thresholdL2 >= thresholdL3) return `L2 阈值(${thresholdL2}) 必须小于 L3(${thresholdL3})`;
  if (thresholdL3 >= thresholdL4) return `L3 阈值(${thresholdL3}) 必须小于 L4(${thresholdL4})`;
  return '';
};
const thresholdError = (row: RiskRuleVO) => validateThresholds(row);

/** GLOBAL 聚合行铺浅底+粗上边线, 与普通规则行视觉区分(K3 P1-4, 克制企业风) */
const rowClassName = ({ row }: { row: RiskRuleVO }) => (row.ruleScope === 'GLOBAL' ? 'global-aggregate-row' : '');

const onSave = async (row: RuleRow) => {
  // 保存前校验 L1<L2<L3<L4 递增关系
  const err = validateThresholds(row);
  if (err) {
    ElMessage.error(err);
    return;
  }
  row._saving = true;
  try {
    const r: any = await updateRule(row);
    if (r.code === 200) {
      ElMessage.success('规则已更新');
      // 保存成功后重载列表（避免后端规整后前端不同步）
      await loadRules();
    } else {
      ElMessage.error(r.msg || '保存失败');
    }
  } finally {
    row._saving = false;
  }
};

/**
 * 启用开关切换回调（B 档新增）。
 * 走 PATCH 单字段接口，避免回填全部阈值字段；切换失败时回滚前端状态。
 */
const onToggleEnabled = async (row: RuleRow, val: '0' | '1') => {
  row._toggling = true;
  try {
    const r: any = await toggleRuleEnabled(row.ruleId, val);
    if (r.code === 200) {
      ElMessage.success(val === '1' ? '已启用' : '已禁用');
    } else {
      row.enabled = val === '1' ? '0' : '1';
      ElMessage.error(r.msg || '切换失败');
    }
  } catch {
    row.enabled = val === '1' ? '0' : '1';
    ElMessage.error('切换失败');
  } finally {
    row._toggling = false;
  }
};

// Req 7.2: 新增规则
const addDialog = reactive({
  visible: false,
  submitting: false,
  form: {
    eventType: '',
    description: '',
    weight: 5,
    windowMinutes: 60,
    thresholdL1: 5,
    thresholdL2: 15,
    thresholdL3: 30,
    thresholdL4: 50,
    enabled: '1'
  } as Record<string, any>
});
const openAdd = () => {
  addDialog.form = {
    eventType: '',
    description: '',
    weight: 5,
    windowMinutes: 60,
    thresholdL1: 5,
    thresholdL2: 15,
    thresholdL3: 30,
    thresholdL4: 50,
    enabled: '1'
  };
  addDialog.visible = true;
};
const submitAdd = async () => {
  if (!addDialog.form.eventType || !addDialog.form.eventType.trim()) {
    ElMessage.warning('请填写事件类型');
    return;
  }
  addDialog.submitting = true;
  try {
    const r: any = await addRule(addDialog.form);
    if (r.code === 200) {
      ElMessage.success('规则已新增');
      addDialog.visible = false;
      await loadRules();
    } else {
      ElMessage.error(r.msg || '新增失败');
    }
  } finally {
    addDialog.submitting = false;
  }
};
const onDelete = async (row: RiskRuleVO) => {
  await ElMessageBox.confirm(`确认删除规则「${EVENT_TYPE_LABELS[row.eventType] || row.eventType}」？`, '删除确认', { type: 'warning' });
  const r: any = await deleteRule(row.ruleId);
  if (r.code === 200) {
    ElMessage.success('规则已删除');
    await loadRules();
  }
};

onMounted(loadRules);
</script>

<style scoped lang="scss">
.risk-rules {
  padding: 12px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.rule-guide p {
  margin: 2px 0;
  font-size: 12px;
  line-height: 1.7;
}
:deep(.global-aggregate-row) {
  background: var(--dcs-bg-fill-lighter);
  td {
    border-top: 2px solid var(--dcs-border-color-light);
  }
}
.dirty-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 50%;
  background: var(--dcs-color-warning);
  vertical-align: middle;
}
.threshold-error :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--dcs-color-danger) inset;
}
.threshold-warn {
  color: var(--dcs-color-danger);
  margin-right: 4px;
  vertical-align: middle;
}
</style>
