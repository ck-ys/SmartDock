<template>
  <div class="app-container dcs-as-memory">
    <div class="mem-head">
      <b>AI 助手 · 我的记忆</b>
      <el-tag v-if="memoryEnabled" size="small" type="success">已开启</el-tag>
      <el-tag v-else size="small" type="info">试点期</el-tag>
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

    <!-- 已开启：真数据清单（二站④ AU-53） -->
    <template v-else>
      <div v-loading="loading" class="mem-body">
        <div v-if="rows.length === 0 && !loading" class="mem-empty">
          <p class="mt-title">还没有记忆</p>
          <p class="mt-sub">
            在对话里跟我说「记住……」（比如「称呼我为小林」「催办留言默认提醒今天截止」），<br />我会把稳定的偏好存到这里，换会话也不会忘。
          </p>
        </div>
        <div v-for="m in rows" :key="m.id" class="mem-row">
          <div class="mem-main">
            <div class="mem-content">{{ m.content }}</div>
            <div class="mem-meta">
              <span>{{ fmtTime(m.createTime) }}</span>
              <span class="mem-src">{{ m.source === 'AI' ? '对话中沉淀' : '手动添加' }}</span>
            </div>
          </div>
          <el-button link type="danger" @click="onRemove(m)">删除</el-button>
        </div>
      </div>
      <p class="mem-foot">每条记忆都能看来源、随时能删；删除后新对话立即不再引用。</p>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * AI 助手记忆页（原型屏F，批F 空态 → 二站④ AU-53 真数据）。
 * memoryEnabled=false（租户未开）保持原设计空态；true 时拉本人记忆清单，
 * 支持单条删除（ElMessageBox 确认）。权限=dcs:assistant:use（菜单 11928）+
 * memory/list 端点 dcs:assistant:memory:manage（菜单 11922，全员分配）。
 */
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ASSISTANT_ADMIN_PATH } from '@/router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { checkPermi } from '@/utils/permission';
import { getAssistantStatus } from '@/api/dcs/assistant';
import { listAssistantMemory, removeAssistantMemory, type AssistantMemoryRow } from '@/api/dcs/assistant';
import { unwrap, fmtIsoTime } from '@/components/DcsAssistant/util';

const router = useRouter();
/** 有 config 权限才给直达按钮；普通用户给联系管理员的文案（菜单 11927 perms=dcs:assistant:config） */
const canAdmin = checkPermi(['dcs:assistant:config']);

const memoryEnabled = ref(false);
const rows = ref<AssistantMemoryRow[]>([]);
const loading = ref(false);

const fmtTime = fmtIsoTime;

async function load() {
  loading.value = true;
  try {
    // request 拦截器成功路径返回 R 包装——unwrap 双兼容
    const st = unwrap<{ memoryEnabled?: boolean }>(await getAssistantStatus());
    memoryEnabled.value = st?.memoryEnabled === true;
    if (memoryEnabled.value) {
      rows.value = unwrap<AssistantMemoryRow[]>(await listAssistantMemory()) ?? [];
    }
  } catch {
    ElMessage.error('记忆加载失败，请稍后再试');
  } finally {
    loading.value = false;
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
    ElMessage.success('已删除，新对话不再引用');
  } catch {
    ElMessage.error('删除失败，请稍后再试');
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
  max-width: 720px;

  .mem-head {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 12px;

    b {
      font-size: 16px;
      color: var(--dcs-text-primary);
    }
  }
  .mt-title {
    margin: 0 0 6px;
    font-size: 14.5px;
    color: var(--dcs-text-primary);
    font-weight: 600;
  }
  .mt-sub {
    margin: 0;
    font-size: 13px;
    color: var(--dcs-text-secondary, #909399);
    line-height: 1.7;
  }
  .mem-body {
    min-height: 120px;
  }
  .mem-row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 12px;
    border: 1px solid var(--dcs-border-color);
    border-radius: 8px;
    margin-bottom: 8px;
    background: var(--el-bg-color);

    .mem-main {
      flex: 1;
      min-width: 0;
    }
    .mem-content {
      font-size: 13.5px;
      color: var(--dcs-text-primary);
      word-break: break-all;
    }
    .mem-meta {
      display: flex;
      gap: 10px;
      margin-top: 4px;
      font-size: 12px;
      color: var(--dcs-text-secondary, #909399);
    }
  }
  .mem-empty {
    text-align: center;
    padding: 32px 0;
  }
  .mem-foot {
    margin: 14px 0 0;
    font-size: 12.5px;
    color: var(--dcs-text-secondary, #909399);
  }
}
</style>
