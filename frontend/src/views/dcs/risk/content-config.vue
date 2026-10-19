<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>内容机检</span>
          <el-tag size="small" type="info" effect="plain">运行时开关</el-tag>
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon class="mb-4">
        <template #title> 开关为运行时内存态：后端重启后恢复配置文件默认档（观察模式，只记不拦）。每次修改记入系统操作日志。 </template>
      </el-alert>

      <div v-loading="loading" class="switch-list">
        <div class="switch-row">
          <div class="switch-info">
            <div class="switch-name">
              内容机检总开关
              <div class="switch-sub">表单填写 / 在线批量 / Excel 导入 / 追加记录 四个入口的文本机检</div>
            </div>
          </div>
          <el-switch
            v-model="state.contentModerationEnabled"
            :loading="switching"
            @change="onSwitch('contentModerationEnabled', $event as boolean)"
          />
        </div>

        <el-divider />

        <div class="switch-row">
          <div class="switch-info">
            <div class="switch-name">
              拦截开关
              <div class="switch-sub">关（默认）= 观察模式：只记事件不拒绝任何提交； 开 = 命中阻断词的行直接拒绝，其余提交零感知</div>
            </div>
          </div>
          <el-switch
            v-model="state.contentBlockEnabled"
            :disabled="!state.contentModerationEnabled"
            :loading="switching"
            @change="onSwitch('contentBlockEnabled', $event as boolean)"
          />
        </div>
      </div>

      <div class="hint-line">
        <el-icon><InfoFilled /></el-icon>
        拦截开关依赖总开关；关闭总开关时拦截开关同步失效。开启拦截前建议先观察大盘「今日机检 · 命中阻断词」数量。
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { getContentConfigState, updateContentConfig, type ContentConfigState } from '@/api/dcs/risk';
import { InfoFilled } from '@element-plus/icons-vue';

defineOptions({ name: 'DcsRiskContentConfig' });

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(true);
const switching = ref(false);
const state = reactive<ContentConfigState>({
  contentModerationEnabled: false,
  contentBlockEnabled: false
});

/** 开关中文名与后果文案(二次确认弹窗用) */
const LABELS: Record<string, { name: string; on: string; off: string }> = {
  contentModerationEnabled: {
    name: '内容机检总开关',
    on: '开启后，表单/在线批量/Excel 导入/追加记录的文本字段将做敏感词机检',
    off: '关闭后，四个入口不再做任何机检（已产生的标记与事件保留）'
  },
  contentBlockEnabled: {
    name: '拦截开关',
    on: '开启后，命中阻断级词的提交行将被直接拒绝',
    off: '关闭后回到观察模式：只记事件，不拒绝任何提交'
  }
};

const getState = async () => {
  loading.value = true;
  try {
    const res = await getContentConfigState();
    Object.assign(state, res.data);
  } finally {
    loading.value = false;
  }
};

/** 切换前回滚 UI 态, 确认后才真正调接口(防确认取消后开关显示与后端不一致) */
const onSwitch = async (key: keyof ContentConfigState, next: boolean) => {
  const label = LABELS[key];
  const prev = !next;
  try {
    await ElMessageBox.confirm(`确定${next ? '开启' : '关闭'}「${label.name}」吗？${next ? label.on : label.off}。`, '修改风控开关', {
      confirmButtonText: next ? '开启' : '关闭',
      cancelButtonText: '再想想',
      type: 'warning'
    });
  } catch {
    state[key] = prev;
    return;
  }
  switching.value = true;
  try {
    const res = await updateContentConfig({ [key]: next } as Partial<ContentConfigState>);
    Object.assign(state, res.data);
    proxy?.$modal.msgSuccess(`「${label.name}」已${next ? '开启' : '关闭'}`);
  } catch {
    // 接口失败回滚 UI 态, 保持显示与后端一致(错误提示由请求拦截器统一 toast, 勿重抛)
    state[key] = prev;
  } finally {
    switching.value = false;
  }
};

onMounted(getState);
</script>

<style lang="scss" scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.switch-list {
  padding: 4px 0;
}

.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 10px 2px;
}

.switch-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--dcs-text-primary);
}

.switch-sub {
  margin-top: 4px;
  font-size: 12px;
  font-weight: 400;
  color: var(--dcs-text-secondary);
}

.hint-line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  color: var(--dcs-text-secondary);

  .el-icon {
    color: var(--el-color-info);
  }
}

.mb-4 {
  margin-bottom: 16px;
}
</style>
