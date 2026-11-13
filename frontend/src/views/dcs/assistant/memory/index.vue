<template>
  <div class="app-container dcs-as-memory">
    <div class="mem-head">
      <b>AI 助手 · 我的记忆</b>
      <el-tag v-if="memoryEnabled" size="small" type="success">已开启</el-tag>
      <el-tag v-else size="small" type="info">试点期</el-tag>
      <span v-if="memoryEnabled && !prefOn" class="mem-off">你已单独关闭，本轮起不再注入</span>
      <span class="mem-spacer" />
      <template v-if="memoryEnabled">
        <span class="mem-usage"><i class="mem-bar"><b :style="{ width: usagePct }" /></i>{{ count }} / {{ max }} 条</span>
        <el-switch v-model="prefOn" size="small" :loading="prefSaving" @change="onTogglePref" />
        <span class="mem-switch-label">记住我的偏好</span>
        <el-button size="small" @click="onExport">导出 JSON</el-button>
        <el-button size="small" type="danger" plain @click="onClearAll">清空全部</el-button>
      </template>
    </div>

    <!-- 未开启：设计空态（引导开通） -->
    <el-empty v-if="!memoryEnabled" :image-size="80">
      <template #description>
        <p class="mt-title">长期记忆还没开启</p>
        <p class="mt-sub">
          开启后，你告诉我的偏好（比如「催办默认明天 9 点」「称呼我为小林」）会沉淀到这里，<br />换会话也不会忘；现在每段对话都是独立的。
        </p>
      </template>
      <!-- 仅有配置权限者可直达管理页（批H P2-8：无权限跳转会被动态路由拦成 404） -->
      <el-button v-if="canAdmin" type="primary" plain @click="goAdmin">管理员在哪里开启？</el-button>
      <p v-else class="mt-sub">需要长期记忆的话，请联系本单位管理员开通。</p>
    </el-empty>

    <!-- 已开启：真数据清单（二站④ AU-53；ckys3 解锁板题7 案1 扩） -->
    <template v-else>
      <div class="mem-toolbar">
        <el-input v-model="keyword" class="mem-search" placeholder="搜索记忆内容" clearable />
        <!-- 全部类型用 'ALL' 哨兵：Element Plus 把空串当「未选」→ 会显示占位文案「请选择」 -->
        <el-select v-model="typeFilter" class="mem-type" size="default">
          <el-option v-for="t in TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </div>

      <div v-loading="loading" class="mem-body">
        <div v-if="shown.length === 0 && !loading" class="mem-empty">
          <p class="mt-title">{{ rows.length === 0 ? '还没有记忆' : '没有符合条件的记忆' }}</p>
          <p class="mt-sub">
            <template v-if="rows.length === 0">
              在对话里跟我说「记住……」（比如「称呼我为小林」「催办留言默认提醒今天截止」），<br />我会把稳定的偏好存到这里，换会话也不会忘。
            </template>
            <template v-else>换个关键词，或把类型切回「全部」。</template>
          </p>
        </div>

        <div v-for="m in shown" :key="m.id" class="mem-row">
          <span class="mem-type-tag" :class="m.memoryType || 'reference'">{{ typeLabel(m.memoryType) }}</span>
          <div class="mem-main">
            <template v-if="editingId === m.id">
              <el-input
                v-model="editText"
                type="textarea"
                :rows="2"
                maxlength="200"
                show-word-limit
                class="mem-edit"
                @keyup.esc="cancelEdit"
              />
              <div class="mem-edit-ops">
                <el-button size="small" type="primary" :loading="saving" @click="saveEdit(m)">保存</el-button>
                <el-button size="small" @click="cancelEdit">取消</el-button>
                <span class="mem-hint">保存后仍会做敏感信息脱敏；改不动的会提示原因。</span>
              </div>
            </template>
            <template v-else>
              <div class="mem-content">{{ m.content }}</div>
              <div class="mem-meta">
                <span>{{ fmtTime(m.createTime) }}</span>
                <span class="mem-src">{{ sourceText(m) }}</span>
                <span v-if="m.updateTime && m.updateTime !== m.createTime" class="mem-edited">已编辑</span>
              </div>
            </template>
          </div>
          <span v-if="editingId !== m.id" class="mem-ops">
            <el-button link type="primary" @click="startEdit(m)">编辑</el-button>
            <el-button link type="danger" @click="onRemove(m)">删除</el-button>
          </span>
        </div>
      </div>

      <details v-if="history.length > 0" class="mem-arch">
        <summary>历史记忆（被替代 {{ supersededCount }} 条 · 已归档 {{ archivedCount }} 条）</summary>
        <div class="mem-arch-list">
          <div v-for="m in history" :key="m.id" class="mem-row arch">
            <span class="mem-type-tag" :class="m.memoryType || 'reference'">{{ typeLabel(m.memoryType) }}</span>
            <div class="mem-main">
              <div class="mem-content">{{ m.content }}</div>
              <div class="mem-meta">
                <span>{{ fmtTime(m.createTime) }}</span>
                <span class="mem-src">{{ historyText(m) }}</span>
              </div>
            </div>
            <el-button link type="danger" @click="onRemove(m)">删除</el-button>
          </div>
        </div>
      </details>

      <p class="mem-foot">每条记忆都能看来源、随时能删；删除后新对话立即不再引用。</p>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * AI 助手记忆页（原型屏F，批F 空态 → 二站④ AU-53 真数据 → ckys3 解锁板题7/题10 案1）。
 *
 * <p>题7：搜索＋类型筛选＋出处＋行内编辑＋条数用量；题10：个人开关＋导出 JSON＋清空全部
 * ＋历史区（被同主题替代 / 90 天归档）。权限=dcs:assistant:use（菜单 11928）+
 * memory 系列端点 dcs:assistant:memory:manage（菜单 11922，全员分配——后端只操作本人数据）。</p>
 */
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ASSISTANT_ADMIN_PATH } from '@/router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { checkPermi } from '@/utils/permission';
import {
  getAssistantStatus,
  listAssistantMemory,
  listAssistantMemoryHistory,
  getAssistantMemoryUsage,
  getAssistantMemoryPref,
  setAssistantMemoryPref,
  editAssistantMemory,
  exportAssistantMemory,
  clearAssistantMemory,
  removeAssistantMemory,
  type AssistantMemoryRow
} from '@/api/dcs/assistant';
import { unwrap, fmtIsoTime } from '@/components/DcsAssistant/util';

const router = useRouter();
/** 有 config 权限才给直达按钮；普通用户给联系管理员的文案（菜单 11927 perms=dcs:assistant:config） */
const canAdmin = checkPermi(['dcs:assistant:config']);

const TYPE_OPTIONS = [
  { value: 'ALL', label: '全部类型' },
  { value: 'user', label: '偏好' },
  { value: 'project', label: '项目' },
  { value: 'team', label: '协作' },
  { value: 'reference', label: '参考' }
];
const TYPE_LABEL: Record<string, string> = {
  user: '偏好',
  project: '项目',
  team: '协作',
  reference: '参考'
};

const memoryEnabled = ref(false);
const rows = ref<AssistantMemoryRow[]>([]);
const history = ref<AssistantMemoryRow[]>([]);
const loading = ref(false);
const keyword = ref('');
const typeFilter = ref('ALL');
const count = ref(0);
const max = ref(50);

const prefOn = ref(true);
const prefSaving = ref(false);

const editingId = ref<number | string | null>(null);
const editText = ref('');
const saving = ref(false);

const fmtTime = fmtIsoTime;

const shown = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  return rows.value.filter((m) => {
    if (typeFilter.value !== 'ALL' && (m.memoryType || 'reference') !== typeFilter.value) {
      return false;
    }
    if (k && !String(m.content || '').toLowerCase().includes(k)) {
      return false;
    }
    return true;
  });
});
const usagePct = computed(() => `${Math.min(100, Math.round((count.value / Math.max(1, max.value)) * 100))}%`);
const supersededCount = computed(() => history.value.filter((m) => m.status === 'SUPERSEDED').length);
const archivedCount = computed(() => history.value.filter((m) => m.status === 'ARCHIVED').length);

function typeLabel(t?: string) {
  return TYPE_LABEL[t || 'reference'] || '参考';
}

/** 出处（题7）：有会话 id 显示「来自对话 #id」，查不到保持真机原话「对话中沉淀」——不编造会话名 */
function sourceText(m: AssistantMemoryRow) {
  if (m.source && m.source !== 'AI') {
    return '手动添加';
  }
  return m.conversationId ? `来自对话 #${m.conversationId}` : '对话中沉淀';
}

function historyText(m: AssistantMemoryRow) {
  return m.status === 'SUPERSEDED' ? '已被同主题新记忆替代' : '90 天未使用已归档';
}

async function load() {
  loading.value = true;
  try {
    // request 拦截器成功路径返回 R 包装——unwrap 双兼容
    const st = unwrap<{ memoryEnabled?: boolean }>(await getAssistantStatus());
    memoryEnabled.value = st?.memoryEnabled === true;
    if (memoryEnabled.value) {
      await Promise.all([loadList(), loadPref()]);
    }
  } catch {
    ElMessage.error('记忆加载失败，请稍后再试');
  } finally {
    loading.value = false;
  }
}

async function loadList() {
  rows.value = unwrap<AssistantMemoryRow[]>(await listAssistantMemory()) ?? [];
  try {
    const usage = unwrap<{ count?: number; max?: number }>(await getAssistantMemoryUsage());
    count.value = usage?.count ?? rows.value.length;
    max.value = usage?.max ?? 50;
  } catch {
    count.value = rows.value.length;
  }
  try {
    history.value = unwrap<AssistantMemoryRow[]>(await listAssistantMemoryHistory()) ?? [];
  } catch {
    history.value = [];
  }
}

async function loadPref() {
  try {
    const p = unwrap<{ enabled?: boolean }>(await getAssistantMemoryPref());
    prefOn.value = p?.enabled !== false;
  } catch {
    prefOn.value = true;
  }
}

async function onTogglePref(val: boolean | string | number) {
  prefSaving.value = true;
  const want = val === true;
  try {
    const r = unwrap<{ enabled?: boolean }>(await setAssistantMemoryPref(want));
    prefOn.value = r?.enabled !== false;
    ElMessage.success(prefOn.value ? '已开启：之后会继续记住你的偏好' : '已关闭：之后不再注入、也不再保存新的');
  } catch {
    prefOn.value = !want;
    ElMessage.error('开关保存失败，请稍后再试');
  } finally {
    prefSaving.value = false;
  }
}

function startEdit(m: AssistantMemoryRow) {
  editingId.value = m.id;
  editText.value = m.content;
}

function cancelEdit() {
  editingId.value = null;
  editText.value = '';
}

async function saveEdit(m: AssistantMemoryRow) {
  const text = editText.value.trim();
  if (!text) {
    ElMessage.warning('内容不能为空');
    return;
  }
  saving.value = true;
  try {
    await editAssistantMemory(m.id, text);
    ElMessage.success('已保存');
    cancelEdit();
    await loadList();
  } catch {
    ElMessage.error('没有更新：内容为空/与其它记忆重复/不属于你');
  } finally {
    saving.value = false;
  }
}

async function onRemove(m: AssistantMemoryRow) {
  try {
    await ElMessageBox.confirm(`删除这条记忆？\n「${m.content}」`, '删除记忆', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await removeAssistantMemory(m.id);
    rows.value = rows.value.filter((r) => r.id !== m.id);
    history.value = history.value.filter((r) => r.id !== m.id);
    count.value = Math.max(0, count.value - 1);
    ElMessage.success('已删除，新对话不再引用');
  } catch {
    ElMessage.error('删除失败，请稍后再试');
  }
}

async function onExport() {
  try {
    const data = unwrap<AssistantMemoryRow[]>(await exportAssistantMemory()) ?? [];
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `assistant-memory-${new Date().toISOString().slice(0, 10)}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    ElMessage.success(`已导出 ${data.length} 条（含历史）`);
  } catch {
    ElMessage.error('导出失败，请稍后再试');
  }
}

async function onClearAll() {
  try {
    await ElMessageBox.confirm(
      `清空全部记忆？包括历史与已归档（共 ${count.value + history.value.length} 条）。\n清空后不可恢复，助手会从现在重新开始记。`,
      '清空全部记忆',
      { confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    const r = unwrap<{ deleted?: number }>(await clearAssistantMemory());
    ElMessage.success(`已清空 ${r?.deleted ?? 0} 条`);
    rows.value = [];
    history.value = [];
    count.value = 0;
    cancelEdit();
  } catch {
    ElMessage.error('清空失败，请稍后再试');
  }
}

function goAdmin() {
  // 批一 P0-1：路由常量在 src/router/index.ts（对应菜单 11927 挂 11930「AI 助手」下），
  // 不再手写死路径——旧值 '/assistant-admin' 缺父级目录段，动态路由匹配不上必落 404
  router.push(ASSISTANT_ADMIN_PATH);
}

onMounted(load);
</script>

<style lang="scss" scoped>
.dcs-as-memory {
  /* 2026-11-01 Q2=乙：原 720 左对齐，1920 下右边空 944px；改为跟随全局宽度口径 */

  .mem-head {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 12px;
    flex-wrap: wrap;

    b {
      font-size: 16px;
      color: var(--dcs-text-primary);
    }
  }
  .mem-spacer {
    flex: 1;
  }
  .mem-off {
    font-size: 12px;
    color: var(--el-color-warning);
  }
  .mem-usage {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--dcs-text-secondary);
    white-space: nowrap;

    .mem-bar {
      display: inline-block;
      width: 44px;
      height: 4px;
      border-radius: 2px;
      background: var(--dcs-border-color);
      overflow: hidden;

      b {
        display: block;
        height: 100%;
        background: var(--el-color-primary);
      }
    }
  }
  .mem-switch-label {
    font-size: 12px;
    color: var(--dcs-text-secondary);
    margin-right: 4px;
  }
  .mem-toolbar {
    display: flex;
    gap: 8px;
    margin-bottom: 10px;

    .mem-search {
      max-width: 280px;
    }
    .mem-type {
      width: 132px;
      flex: none;
    }
  }
  .mt-title {
    margin: 0 0 6px;
    font-size: 15px;
    color: var(--dcs-text-primary);
    font-weight: 600;
  }
  .mt-sub {
    margin: 0;
    font-size: 13px;
    color: var(--dcs-text-secondary);
    line-height: 1.7;
  }
  .mem-body {
    min-height: 120px;
  }
  .mem-row {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    padding: 10px 12px;
    border: 1px solid var(--dcs-border-color);
    border-radius: var(--app-card-radius);
    margin-bottom: 8px;
    background: var(--el-bg-color);

    .mem-main {
      flex: 1;
      min-width: 0;
    }
    .mem-content {
      font-size: 14px;
      color: var(--dcs-text-primary);
      word-break: break-all;
    }
    .mem-meta {
      display: flex;
      gap: 10px;
      margin-top: 4px;
      font-size: 12px;
      color: var(--dcs-text-secondary);
    }
    .mem-edited {
      color: var(--el-color-primary);
    }
    .mem-ops {
      flex: none;
      display: inline-flex;
      align-items: center;
      gap: 2px;
      white-space: nowrap;
    }
  }
  /* 类型徽标（题7）：淡底细边，四类各一色——安静不抢内容 */
  .mem-type-tag {
    flex: none;
    margin-top: 1px;
    padding: 1px 7px;
    border-radius: 4px;
    font-size: 11px;
    line-height: 18px;
    white-space: nowrap;
    border: 1px solid transparent;

    &.user {
      color: #2f6fb3;
      background: #eef5fc;
      border-color: #d7e6f7;
    }
    &.project {
      color: #8a6d1f;
      background: #fdf7e6;
      border-color: #f0e3bf;
    }
    &.team {
      color: #2e7d5b;
      background: #ecf8f1;
      border-color: #cfe9dc;
    }
    &.reference {
      color: #6b7280;
      background: #f4f5f7;
      border-color: #e5e7eb;
    }
  }
  .mem-edit {
    margin-bottom: 6px;
  }
  .mem-edit-ops {
    display: flex;
    align-items: center;
    gap: 8px;

    .mem-hint {
      font-size: 12px;
      color: var(--dcs-text-secondary);
    }
  }
  .mem-arch {
    margin-top: 12px;
    border: 1px solid var(--dcs-border-color);
    border-radius: var(--app-card-radius);
    padding: 8px 12px;
    background: var(--el-bg-color);

    summary {
      cursor: pointer;
      font-size: 13px;
      color: var(--dcs-text-secondary);
    }
    .mem-arch-list {
      margin-top: 8px;
    }
    .mem-row.arch {
      background: var(--el-fill-color-lighter);
      border-style: dashed;
    }
  }
  .mem-empty {
    text-align: center;
    padding: 32px 0;
  }
  .mem-foot {
    margin: 14px 0 0;
    font-size: 12px;
    color: var(--dcs-text-secondary);
  }
}
</style>
