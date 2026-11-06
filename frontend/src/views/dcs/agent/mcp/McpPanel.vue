<template>
  <section class="mcp-ws" aria-label="MCP 服务">
    <div class="mcp-head">
      <h1>MCP 服务</h1>
      <p>
        外部服务（MCP）给助手带来额外能力。{{ canManage ? '这里是平台级配置：注册、启停、改地址都在这一页。' : '下面是本平台已开给你用的外部服务。' }}
      </p>
    </div>

    <div v-if="canManage" class="mcp-bar">
      <el-button type="primary" size="small" @click="openForm()">注册服务</el-button>
      <span class="mcp-hint">改动对下一轮新会话生效；停用的服务，那一组工具整体消失。</span>
    </div>

    <!-- 管理员：全量清单（含停用） -->
    <div v-if="canManage" v-loading="loading" class="mcp-list">
      <div v-for="row in rows" :key="row.id" class="mcp-row" :class="{ off: row.enabled !== '1' }">
        <div class="mcp-main">
          <b>{{ row.displayName || row.serverName }}</b>
          <code>{{ row.serverName }}</code>
          <span class="mcp-badge" :class="badgeOf(row.serverName).cls">{{ badgeOf(row.serverName).text }}</span>
          <span class="mcp-meta">{{ row.transportType === 'STDIO' ? '本地脚本' : '网络服务' }}</span>
          <span class="mcp-meta">{{ row.readOnly === '0' ? '可写' : '只读' }}</span>
          <span class="mcp-cmd" :title="cmdFull(row)">{{ cmdSummary(row) }}</span>
          <span v-if="row.headerKeys" class="mcp-meta">鉴权头：{{ row.headerKeys }}</span>
        </div>
        <div class="mcp-acts">
          <el-switch :model-value="row.enabled === '1'" size="small" @change="(v: any) => onToggle(row, v)" />
          <el-button link size="small" type="primary" @click="openForm(row)">编辑</el-button>
          <el-button link size="small" type="danger" :disabled="saving" @click="onDelete(row)">删除</el-button>
        </div>
      </div>
      <div v-if="!rows.length && !loading" class="mcp-empty">
        还没有注册外部服务。点右上角「注册服务」，填上地址或脚本路径即可。
      </div>
    </div>

    <!-- 成员：只读清单 + 带来的工具 + 能不能用 -->
    <div v-else v-loading="loading" class="mcp-list">
      <div v-for="row in members" :key="row.serverName" class="mcp-row">
        <div class="mcp-main">
          <b>{{ row.displayName }}</b>
          <code>{{ row.serverName }}</code>
          <span class="mcp-badge" :class="badgeOf(row.serverName).cls">{{ row.statusText }}</span>
          <span class="mcp-badge warn">高危</span>
          <span class="mcp-meta">{{ row.transportType === 'STDIO' ? '本地脚本' : '网络服务' }}</span>
          <span class="mcp-meta">{{ row.readOnly === '0' ? '可写' : '只读' }}</span>
          <span class="mcp-meta">能用的人：{{ row.whoCanUse }}</span>
        </div>
        <div class="mcp-tools">
          <span class="mcp-tools-l">带来的工具</span>
          <template v-if="row.tools.length">
            <span v-for="t in row.tools" :key="t" class="mcp-tool">{{ t }}</span>
          </template>
          <span v-else class="mcp-meta">还没有调用过；第一次用过之后这里会列出工具名</span>
        </div>
      </div>
      <div v-if="!members.length && !loading" class="mcp-empty">平台还没有开放外部服务给你。</div>
    </div>

    <p class="mcp-foot">
      外部服务由平台管理员统一配置。工具具体能不能用，还受「工具」分节里的租户开关与角色档位影响。
    </p>

    <el-drawer v-model="formVisible" :title="form.id ? '编辑外部服务' : '注册外部服务'" size="480px" append-to-body>
      <el-form label-width="92px">
        <el-form-item label="服务名" required>
          <el-input
            v-model="form.serverName"
            placeholder="小写字母/数字/下划线，3-30 位，如 weather_demo"
            :disabled="!!form.id"
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.displayName" placeholder="如：天气查询（试点）" maxlength="100" />
        </el-form-item>
        <el-form-item label="传输类型" required>
          <el-select v-model="form.transportType" style="width: 100%" :disabled="!!form.id">
            <el-option label="STDIO（node 脚本，试点推荐）" value="STDIO" />
            <el-option label="STREAMABLE_HTTP" value="STREAMABLE_HTTP" />
            <el-option label="SSE" value="SSE" />
          </el-select>
        </el-form-item>
        <template v-if="form.transportType === 'STDIO'">
          <el-form-item label="命令">
            <el-input model-value="node" disabled />
          </el-form-item>
          <el-form-item label="脚本路径" required>
            <el-input v-model="form.argsJson" placeholder='JSON 数组，如 ["D:/.../weather-server.mjs"]' />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="服务地址" required>
            <el-input v-model="form.url" placeholder="http://127.0.0.1:3000/mcp（host 须在白名单）" />
          </el-form-item>
          <el-form-item label="请求头">
            <el-input v-model="form.headers" placeholder='JSON，如 {"Authorization":"Bearer ..."}（值会加密存储）' />
          </el-form-item>
        </template>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
        <el-form-item label="立即启用">
          <el-switch v-model="form.enabledBool" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
/**
 * 设置页 · MCP 服务（旧板题 0/1/2，2026-11-06）：
 * - 题0甲：MCP 从「AI 助手管理」迁进设置页，成为独立分节；
 * - 题1甲：管理入口只留这里，旧页删掉这一节；
 * - 题2乙：成员看「清单 + 带来的工具 + 能不能用（高危标记）」——工具名取真实调用账本，
 *   没调用过就如实写「第一次用过之后会列出」，不猜。
 */
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { checkPermi } from '@/utils/permission';
import { useUserStore } from '@/store/modules/user';
import {
  deleteAssistantMcpServer,
  getAssistantToolCatalog,
  listAssistantMcpMembers,
  listAssistantMcpServers,
  saveAssistantMcpServer,
  toggleAssistantMcpServer,
  type AssistantMcpMemberRow,
  type AssistantMcpServerRow,
  type AssistantToolRow
} from '@/api/dcs/assistant';

const userStore = useUserStore();
/** 三审 P2：后端 save/toggle/delete 都过 requireSuperAdmin——只有 config 权限的自定义管理员
 *  能看到按钮但点了必报「仅超管可管理」。前端与后端同口径：必须真超管才给管理面。 */
const canManage = computed(
  () => checkPermi(['dcs:assistant:config']) && userStore.roles.includes('superadmin')
);

const rows = ref<AssistantMcpServerRow[]>([]);
const members = ref<AssistantMcpMemberRow[]>([]);
const toolRows = ref<AssistantToolRow[]>([]);
const loading = ref(false);
const saving = ref(false);

/** 状态徽章：管理员视角借「工具清单」里 mcp:<server> 行的状态，与工具分节同一口径 */
function badgeOf(serverName: string): { cls: string; text: string } {
  if (!canManage.value) {
    const m = members.value.find((x) => x.serverName === serverName);
    const code = m?.status ?? 'off';
    return { cls: code, text: m?.statusText ?? '—' };
  }
  const row = toolRows.value.find((t) => t.key === `mcp:${serverName}`);
  if (!row) {
    return { cls: 'off', text: '状态未知' };
  }
  return { cls: row.status, text: row.statusText };
}

function cmdSummary(row: AssistantMcpServerRow): string {
  if (row.transportType !== 'STDIO') {
    return row.url || '';
  }
  const raw = row.args || '';
  return raw.length > 64 ? raw.slice(0, 63) + '…' : raw;
}
function cmdFull(row: AssistantMcpServerRow): string {
  return row.transportType === 'STDIO' ? row.args || '' : row.url || '';
}

async function load() {
  loading.value = true;
  try {
    if (canManage.value) {
      // request 层返回整个 body（{code,msg,data}）——取 data 不取顶层（本仓老坑）
      const servers = (await listAssistantMcpServers()) as unknown as { data?: AssistantMcpServerRow[] };
      rows.value = servers?.data ?? [];
      const tools = (await getAssistantToolCatalog()) as unknown as { data?: AssistantToolRow[] };
      toolRows.value = tools?.data ?? [];
    } else {
      const list = (await listAssistantMcpMembers()) as unknown as { data?: AssistantMcpMemberRow[] };
      members.value = list?.data ?? [];
    }
  } catch {
    // request 层已 toast；保持空列表但由空态文案说明
  } finally {
    loading.value = false;
  }
}

const formVisible = ref(false);
const form = reactive({
  id: '',
  serverName: '',
  displayName: '',
  transportType: 'STDIO' as 'STDIO' | 'STREAMABLE_HTTP' | 'SSE',
  argsJson: '',
  url: '',
  headers: '',
  remark: '',
  enabledBool: true
});

function openForm(row?: AssistantMcpServerRow) {
  form.id = row?.id ?? '';
  form.serverName = row?.serverName ?? '';
  form.displayName = row?.displayName ?? '';
  form.transportType = (row?.transportType as 'STDIO' | 'STREAMABLE_HTTP' | 'SSE') ?? 'STDIO';
  form.argsJson = row?.args ?? '';
  form.url = row?.url ?? '';
  form.headers = '';
  form.remark = row?.remark ?? '';
  form.enabledBool = row ? row.enabled === '1' : true;
  formVisible.value = true;
}

async function onSave() {
  if (!form.serverName.trim()) {
    ElMessage.warning('先填服务名');
    return;
  }
  saving.value = true;
  try {
    await saveAssistantMcpServer({
      id: form.id || undefined,
      serverName: form.serverName.trim(),
      displayName: form.displayName.trim(),
      transportType: form.transportType,
      command: form.transportType === 'STDIO' ? 'node' : undefined,
      args: form.transportType === 'STDIO' ? form.argsJson.trim() : undefined,
      url: form.transportType === 'STDIO' ? undefined : form.url.trim(),
      headers: form.headers.trim() || undefined,
      remark: form.remark.trim(),
      enabled: form.enabledBool ? '1' : '0'
    } as Partial<AssistantMcpServerRow> & { headers?: string });
    ElMessage.success('已保存，下一轮新会话生效');
    formVisible.value = false;
    await load();
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false;
  }
}

async function onToggle(row: AssistantMcpServerRow, on: boolean) {
  try {
    await toggleAssistantMcpServer(row.id, on ? '1' : '0');
    row.enabled = on ? '1' : '0';
    ElMessage.success(on ? '已启用' : '已停用');
  } catch {
    // 拦截器已提示；UI 状态不变
  }
}

async function onDelete(row: AssistantMcpServerRow) {
  try {
    await ElMessageBox.confirm(
      `删掉「${row.displayName || row.serverName}」后，它带来的工具会从下一条消息起消失；调用记录保留。`,
      '删除外部服务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  saving.value = true;
  try {
    await deleteAssistantMcpServer(row.id);
    ElMessage.success('已删除');
    await load();
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<style lang="scss" scoped>
.mcp-ws {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}
.mcp-head {
  h1 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
    color: var(--app-text, #181818);
  }
  p {
    margin: 6px 0 0;
    font-size: 12.5px;
    line-height: 1.6;
    color: var(--app-muted, #666);
  }
}
.mcp-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.mcp-hint {
  font-size: 11.5px;
  color: var(--app-faint, #8a8a8a);
}
.mcp-link {
  margin-left: auto;
  border: 0;
  background: none;
  color: var(--el-color-primary);
  font-size: 12px;
  cursor: pointer;
}
.mcp-list {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--app-border, rgba(13, 13, 13, 0.1));
  border-radius: 12px;
  background: var(--app-surface, #fff);
  overflow: hidden;
  min-height: 120px;
}
.mcp-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border-top: 1px solid var(--app-border, rgba(13, 13, 13, 0.08));
  &:first-child {
    border-top: 0;
  }
  &.off {
    .mcp-main,
    .mcp-tools {
      opacity: 0.55;
    }
  }
}
.mcp-main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
  flex: 1;
  b {
    font-size: 13px;
  }
  code {
    font-size: 11px;
    color: var(--app-muted, #666);
    background: color-mix(in srgb, var(--app-text, #181818) 5%, transparent);
    border-radius: 5px;
    padding: 1px 6px;
  }
}
.mcp-meta {
  font-size: 11.5px;
  color: var(--app-faint, #8a8a8a);
}
.mcp-cmd {
  font-size: 11px;
  color: var(--app-faint, #8a8a8a);
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mcp-badge {
  font-size: 10.5px;
  line-height: 16px;
  padding: 0 6px;
  border-radius: 999px;
  border: 1px solid var(--app-border, rgba(13, 13, 13, 0.12));
  color: var(--app-muted, #666);
  &.ready {
    color: #1f7a3f;
    border-color: color-mix(in srgb, #1f7a3f 35%, transparent);
  }
  &.admin,
  &.waiting {
    color: #8a6a00;
    border-color: color-mix(in srgb, #8a6a00 30%, transparent);
  }
  &.off {
    color: var(--app-faint, #8a8a8a);
  }
  &.warn {
    color: #a33;
    border-color: color-mix(in srgb, #a33 30%, transparent);
  }
}
.mcp-acts {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}
.mcp-tools {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  flex-basis: 100%;
  padding-left: 2px;
}
.mcp-tools-l {
  font-size: 11px;
  color: var(--app-faint, #8a8a8a);
}
.mcp-tool {
  font-size: 11px;
  line-height: 18px;
  padding: 0 7px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-text, #181818) 5%, transparent);
  color: var(--app-muted, #666);
}
.mcp-empty {
  padding: 28px 16px;
  text-align: center;
  font-size: 12px;
  color: var(--app-faint, #8a8a8a);
}
.mcp-foot {
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--app-faint, #8a8a8a);
}
@media (max-width: 720px) {
  .mcp-row {
    flex-direction: column;
    align-items: stretch;
  }
  .mcp-acts {
    justify-content: flex-end;
  }
  .mcp-cmd {
    max-width: 100%;
  }
}
</style>
