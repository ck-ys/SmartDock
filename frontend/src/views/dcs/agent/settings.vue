<template>
  <div v-if="allowed" class="set-ws">
    <!-- 题7① (2026-11-05)：没权限的人不渲染整页，只等 onMounted 跳 403——否则会先闪一帧设置页内容。
         注意：根节点不能是「注释 + 元素」两兄弟——那会编译成 Fragment，App.vue 的淡出（mode="out-in"）
         在 Fragment 上收不到 afterLeave，离开本页时整页白屏（2026-11-05 三席审查 P0）。
         所以注释挪进 div，无权限时由下面的空 div 兜住根节点。 -->
    <!-- 顶栏（48px，与工作台同高）：返回工作台 / 面包屑 / 当前身份。真路由整页跳转，地址会变 -->
    <header class="set-top">
      <button class="set-back" type="button" @click="backToWorkbench">
        <IconSpan name="arrow-left" :size="13" /> 返回工作台
      </button>
      <span class="set-sep" aria-hidden="true"></span>
      <nav class="set-crumb" aria-label="当前位置">
        <span>工作台</span><span class="sl" aria-hidden="true">/</span><span>设置</span><span class="sl" aria-hidden="true">/</span>
        <b>{{ currentLabel }}</b>
      </nav>
      <span class="sp"></span>
      <span class="set-scope">{{ scopeText }}</span>
    </header>

    <div class="set-body">
      <!-- 264px 子导航：只列真入口；没建的分节保留「待做」，不做假开关 -->
      <aside class="set-nav" aria-label="设置分节">
        <div class="set-brand">
          <span class="set-mark" aria-hidden="true"><BrandMark :size="16" /></span>
          <span class="set-btxt">
            <b>智能体工作台</b>
            <small>设置</small>
          </span>
        </div>

        <template v-for="g in navGroups" :key="g.label">
          <div class="set-grp">{{ g.label }}</div>
          <template v-for="it in g.items" :key="it.key">
            <button
              v-if="it.ready"
              type="button"
              class="set-row"
              :class="{ on: section === it.key }"
              :aria-current="section === it.key ? 'page' : undefined"
              @click="goSection(it.key)"
            >
              <IconSpan :name="it.icon" :size="15" /><span>{{ it.label }}</span>
            </button>
            <span
              v-else
              class="set-row soon"
              :title="
                it.key === 'schedule'
                  ? '定时任务还没开放：后台现在只有系统任务（每日摘要 08:00、数据保留清理 03:40），没有用户自定义任务'
                  : `${it.label} · 待建设（下一批或它自己的板）`
              "
            >
              <IconSpan :name="it.icon" :size="15" /><span>{{ it.label }}</span>
              <span class="soon-tag">{{ it.key === 'schedule' ? '未开放' : '待做' }}</span>
            </span>
          </template>
        </template>

        <div class="set-navfoot">
          <template v-if="isManager">本批只读：技能与工具的改动都在「AI 助手管理 → 能力」里（需要助手配置权限）。</template>
          <template v-else>技能与工具由管理员统一维护；其它设置项也由管理员管理。</template>
        </div>
      </aside>

      <main class="set-main">
        <!-- ============ 技能：列表 + 右侧详情（题 2 丙：只读三件套 + 试一句） ============ -->
        <section v-if="section === 'skills'" class="set-sheet" aria-label="技能">
          <div class="set-list" :class="{ 'is-hidden': isNarrow && mobileDetail }">
            <div class="set-lhead">
              <h1>技能</h1>
              <p>{{ listLead }}</p>
            </div>
            <el-input
              v-model="keyword"
              class="set-search"
              placeholder="搜索技能"
              aria-label="搜索技能"
              clearable
              :prefix-icon="SearchIcon"
            />
            <div class="set-lgrouph">
              <span>可用技能</span>
              <span>{{ filtered.length }}</span>
            </div>
            <div class="set-rows">
              <button
                v-for="s in filtered"
                :key="s.id"
                type="button"
                class="set-row2"
                :class="{ on: s.id === selectedId }"
                :aria-current="s.id === selectedId ? 'true' : undefined"
                @click="pickSkill(s.id)"
              >
                <span class="set-ic" aria-hidden="true"><IconSpan name="sparkles" :size="15" /></span>
                <span class="set-rmain">
                  <span class="set-name"
                    >{{ s.name }}<span class="set-tag">{{ roleLabel(s.minRole) }}起</span></span
                  >
                  <span class="set-ds" :title="skillDesc(s.desc)">{{ skillDesc(s.desc) }}</span>
                </span>
              </button>
            </div>
            <p v-if="!loading && !filtered.length" class="set-lempty">
              {{ failed ? '技能清单没读出来——刷新页面再试；还是空的话找管理员看技能库。' : keyword.trim() ? `没有匹配「${keyword.trim()}」的技能` : '还没有你能用的技能——管理员上架后这里会自动出现。' }}
            </p>
            <div class="set-lfoot">
              <template v-if="canEditSkills">
                只读 · 编辑在「AI 助手管理 → 能力」<button class="set-lfontlink" type="button" @click="goAdmin">去管理台 →</button>
              </template>
              <template v-else-if="isManager">只读 · 改技能内容要超级管理员</template>
              <template v-else>只读 · 技能由管理员统一维护</template>
            </div>
          </div>

          <div class="set-detail" :class="{ 'is-hidden': isNarrow && !mobileDetail }">
            <button v-if="isNarrow" class="set-mback" type="button" @click="mobileDetail = false">
              <IconSpan name="arrow-left" :size="13" /> 技能列表
            </button>

            <template v-if="sel">
              <div class="set-dh">
                <h2>{{ sel.name }}</h2>
                <span class="set-tag">{{ roleLabel(sel.minRole) }}起</span>
              </div>
              <div class="set-meta"><code>{{ sel.id }}</code> · 只读</div>
              <p class="set-desc">{{ skillDesc(sel.desc) }}</p>

              <div class="set-well">
                <div class="set-kv"><b>谁能用</b><span>{{ roleText(sel.minRole) }}</span></div>
                <div class="set-kv"><b>怎么用</b><span>在输入框打 <kbd>/</kbd> 选「{{ sel.name }}」；或在工作台技能条点一张卡</span></div>
              </div>

              <template v-if="detailLoading">
                <div class="set-label">它会怎么带你做</div>
                <p class="set-dnote">正在读技能说明…</p>
              </template>
              <template v-else-if="steps.length">
                <div class="set-label">它会怎么带你做</div>
                <ol class="set-steps">
                  <li v-for="(t, i) in steps" :key="i"><span class="n">{{ i + 1 }}</span><span>{{ t }}</span></li>
                </ol>
              </template>
              <template v-else-if="detailFailed">
                <div class="set-label">它会怎么带你做</div>
                <p class="set-dnote">技能说明没读出来——刷新页面再试；不影响点「试一句」。</p>
              </template>

              <template v-if="phrases.length">
                <div class="set-label">你可以这样说</div>
                <div class="set-say">
                  <span v-for="p in phrases" :key="p">「{{ p }}」</span>
                </div>
              </template>

              <div class="set-actions">
                <button class="set-btn primary" type="button" @click="tryOne">试一句</button>
                <button class="set-btn" type="button" @click="copySentence">复制问法</button>
              </div>
              <p class="set-hint">
                「试一句」回工作台并把这句填进输入框，不会直接发出去；你出发前框里留的草稿会被这句替换（回来后再新写的字才会先问你）。
                <template v-if="canEditSkills">本批只读，改内容去「AI 助手管理 → 能力」。</template>
              </p>

              <div class="set-label">
                最近使用<span>{{ recentCount }} 次</span>
              </div>
              <div class="set-empty">
                <template v-if="recentCount">你用过 {{ recentCount }} 次<template v-if="lastUsedText">，最后一次是 {{ lastUsedText }}</template>。</template>
                <template v-else>你还没用过这个技能。用过之后，这里会显示你自己的使用次数。</template>
              </div>
            </template>
            <p v-else class="set-dnote">{{ loading ? '正在读取技能…' : '左边选一个技能看它怎么带你做。' }}</p>
          </div>
        </section>

        <!-- ============ 工具：只读清单（题1-4 全甲：全员可见 / 表格 / 后端一句话 / 档位只读） ============ -->
        <section v-else-if="section === 'tools'" class="set-sheet tools" aria-label="工具">
          <div class="audit-hd">
            <div>
              <h1>工具</h1>
              <p>这个助手会做哪些事、每件风险多高、现在能不能用。{{ toolsSummary }}</p>
            </div>
          </div>

          <div class="audit-scroll">
            <el-table
              v-loading="toolsLoading"
              :data="toolsRows"
              class="ui-table tools-table"
              :style="isNarrow ? undefined : 'min-width: 760px'"
            >
              <el-table-column label="工具" min-width="300">
                <template #default="{ row }">
                  <div class="tool-nm">{{ row.displayName }}</div>
                  <div class="tool-ds">
                    {{ row.userDesc }}<template v-if="row.kind === 'mcp'"> · {{ callsText(row.calls) }}</template>
                  </div>
                  <!-- 窄屏只留工具一列，其余并到名字下面——避免横滑后只剩一排数字、对不上是哪个工具 -->
                  <div v-if="isNarrow" class="tool-meta">
                    {{ row.category }} · 风险
                    <span class="risk" :class="'r-' + row.riskLevel">{{ riskText(row.riskLevel) }}</span>
                    · {{ row.whoCanUse }}
                    <span class="tool-st tool-st-inline">
                      <i class="dot" :class="'d-' + row.status" aria-hidden="true"></i>{{ row.statusText }}
                    </span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column v-if="!isNarrow" label="分类" width="96">
                <template #default="{ row }"><span class="tool-mut">{{ row.category }}</span></template>
              </el-table-column>
              <el-table-column v-if="!isNarrow" label="风险" width="76">
                <template #default="{ row }">
                  <span class="risk" :class="'r-' + row.riskLevel">{{ riskText(row.riskLevel) }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="!isNarrow" label="状态" width="132">
                <template #default="{ row }">
                  <span class="tool-st"><i class="dot" :class="'d-' + row.status" aria-hidden="true"></i>{{ row.statusText }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="!isNarrow" label="谁能用" width="118">
                <template #default="{ row }"><span class="tool-mut">{{ row.whoCanUse }}</span></template>
              </el-table-column>
              <template #empty>
                <el-empty
                  :description="
                    toolsLoading
                      ? '正在读工具清单…'
                      : toolsFailed
                        ? '工具清单没读出来——刷新页面再试；还是不行找管理员'
                        : '这个助手还没有登记任何工具'
                  "
                />
              </template>
            </el-table>
          </div>

          <p class="tools-foot">
            <template v-if="canConfigAssistant">只读；谁能用和停用只能在「AI 助手管理 → 能力」里改<button class="set-lfontlink" type="button" @click="goAdmin">打开管理台 →</button></template>
            <template v-else>只读；谁能用由平台管理员在「AI 助手管理 → 能力」里配。</template>
          </p>
        </section>

        <!-- ============ 审计：只读，复用现有接口与列（业务审计页不动） ============ -->
        <section v-else-if="section === 'audit'" class="set-sheet audit" aria-label="审计">
          <div class="audit-hd">
            <div>
              <h1>审计</h1>
              <p>AI 替成员执行过的每一笔写操作：怎么放行的、谁确认的、结果如何。只读；业务系统里的「写操作审计」页保持原样。</p>
            </div>
            <button class="set-btn" type="button" :disabled="exporting" @click="handleExport">
              <IconSpan name="download" :size="13" /> {{ exporting ? '导出中…' : '导出 CSV' }}
            </button>
          </div>

          <div class="audit-filters">
            <el-input
              v-model="queryParams.keyword"
              clearable
              placeholder="成员昵称（模糊）"
              class="f-180"
              @keyup.enter="handleQuery"
              @clear="handleQuery"
            />
            <el-select v-model="queryParams.toolName" clearable filterable allow-create placeholder="工具" class="f-160" @change="handleQuery">
              <el-option v-for="t in TOOL_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
            <el-select v-model="queryParams.releaseMode" clearable placeholder="放行方式" class="f-140" @change="handleQuery">
              <el-option v-for="[value, label] in RELEASE_OPTIONS" :key="value" :label="label" :value="value" />
            </el-select>
            <el-date-picker
              v-model="timeRange"
              type="datetimerange"
              value-format="YYYY-MM-DD HH:mm:ss"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              class="f-330"
              @change="handleQuery"
            />
            <button class="set-btn" type="button" @click="handleQuery">查询</button>
            <button class="set-btn" type="button" @click="resetQuery">重置</button>
          </div>

          <div class="audit-scroll">
            <el-table v-loading="auditLoading" :data="auditRows" class="ui-table dcs-table-only" style="min-width: 820px">
              <el-table-column label="时间" width="158">
                <template #default="{ row }">{{ fmtIsoTime(row.createTime) }}</template>
              </el-table-column>
              <el-table-column label="成员" width="120">
                <template #default="{ row }">{{ row.userName || '—' }}</template>
              </el-table-column>
              <el-table-column label="工具" width="140">
                <template #default="{ row }">{{ toolLabel(row.toolName) }}</template>
              </el-table-column>
              <el-table-column label="放行方式" width="110">
                <template #default="{ row }">
                  <span :class="{ muted: !row.releaseMode }">{{ releaseLabel(row.releaseMode) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90" align="center">
                <template #default="{ row }">
                  <el-tag :type="logSt(row.status).type" size="small">{{ logSt(row.status).label }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="结果摘要" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">{{ row.resultSummary || '—' }}</template>
              </el-table-column>
              <template #empty>
                <el-empty
                  :description="
                    auditLoading
                      ? '加载中…'
                      : auditFailed
                        ? '审计记录没读出来——刷新页面再试；还是不行就把筛选清空重查一次'
                        : auditSearched
                          ? '没有符合条件的审计记录——换个筛选条件或放宽时间范围试试'
                          : 'AI 替成员执行的写操作（建任务、保存记忆等）会按放行方式逐笔记在这里；现在还没有记录'
                  "
                  :image-size="56"
                />
              </template>
            </el-table>
          </div>
          <pagination
            v-show="auditTotal > 0"
            v-model:page="queryParams.pageNum"
            v-model:limit="queryParams.pageSize"
            :total="auditTotal"
            @pagination="loadAudit"
          />
        </section>
      </main>
    </div>
  </div>
  <div v-else class="set-blocked" aria-hidden="true">
    <!-- 无权限时的占位根：v-if 为假只渲染注释节点，注释接不住过渡（见上方注释），这里留个真实元素 -->
  </div>
</template>

<script setup lang="ts">
/**
 * 工作台设置页（2026-11-05 拍板 A · 题1 A3 / 题2 丙 / 题5 乙 / 题6 A′）。
 *
 * - 真路由：/agent/settings（左栏点「设置」整页跳；地址变、浏览器返回可用）；
 * - A3 版式：264 子导航 + 360 列表 + 右侧详情；列表与详情同一张白卡，不丢上下文；
 * - 内容只读：技能来自 /dcs/assistant/skills（按角色过滤），详情补 body 只为取
 *   第一条编号清单做「它会怎么带你做」；没有任何开关/删除/新建；
 * - 成员（A′）：显示「技能」「工具」两个分节；工具全员只读，审计与其它分节不出现；
 * - 审计（题5 乙）：admin/tenant_admin 靠 F 型权限 12061 进来，只读、复用现有接口与列；
 *   业务系统里的 11936 审计页原样不动。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Search as SearchIcon } from '@element-plus/icons-vue';
import BrandMark from '@/components/BrandMark.vue';
import IconSpan from '@/components/DcsAssistant/IconSpan.vue';
import { useUserStore } from '@/store/modules/user';
import { ASSISTANT_ADMIN_PATH } from '@/router';
import {
  getAssistantSkillCatalog,
  getAssistantSkillDetailView,
  getAssistantRecentSkills,
  getAssistantToolCatalog,
  pageAssistantActionAudit,
  exportAssistantActionAudit,
  type AssistantActionAuditRow,
  type AssistantRecentSkill,
  type AssistantSkillDetailView,
  type AssistantToolRow
} from '@/api/dcs/assistant';
import { ACT, logSt } from '@/components/DcsAssistant/useAssistant';
import { fmtIsoTime } from '@/components/DcsAssistant/util';
import { buildSkillDraft, guideSteps, skillDesc, triggerPhrases } from './prefill';
import { setPendingSkill } from './bridge';

defineOptions({ name: 'AgentWorkspaceSettings' });

interface CatalogRow {
  id: string;
  name: string;
  desc: string;
  minRole: 'member' | 'publisher' | 'admin';
}

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

/* ---------- 权限与身份 ---------- */
const perms = computed(() => userStore.permissions);
const canEditSkills = computed(() => perms.value.includes('*:*:*') || perms.value.includes('dcs:assistant:skill:edit'));
/** 能进「AI 助手管理 → 能力」改档位的人：只认真正的配置权（租户管理员只有审计权时不挂死链） */
const canConfigAssistant = computed(() => perms.value.includes('*:*:*') || perms.value.includes('dcs:assistant:config'));
const canAudit = computed(() => perms.value.includes('*:*:*') || perms.value.includes('dcs:assistant:audit:query'));
/** 管理员视角=能审计 / 能改技能 / 有助手配置权；其余按成员/发布人视角（A′） */
const isManager = computed(
  () => canAudit.value || canEditSkills.value || perms.value.includes('*:*:*') || perms.value.includes('dcs:assistant:config')
);
const allowed = computed(() => perms.value.includes('*:*:*') || perms.value.includes('agentws:workspace:use'));
/** 发布人档与后端 ToolIdentity.from 同一套串（少一个就会被标成「成员」，与列表自相矛盾） */
const PUBLISHER_PERMS = ['dcs:mission:edit', 'dcs:mission:audit', 'dcs:task:add', 'dcs:task:edit', 'dcs:audit:audit'];
const isAdminLevel = computed(() => isManager.value || userStore.roles.includes('superadmin') || userStore.roles.includes('admin'));
const canPublish = computed(() => isAdminLevel.value || PUBLISHER_PERMS.some((p) => perms.value.includes(p)));

const roleTextOf = computed(() => (isAdminLevel.value ? '管理员' : canPublish.value ? '发布人' : '成员'));
/** 右上角「身份 · 名字」：昵称本身就叫「管理员」时不重复（同左栏用户行的口径） */
const scopeText = computed(() => {
  const role = roleTextOf.value;
  const nick = (userStore.nickname || userStore.name || '').trim();
  return nick && nick !== role ? `${role} · ${nick}` : role;
});

/* ---------- 分节导航 ---------- */
type SectionKey = 'skills' | 'tools' | 'audit';
interface NavItem {
  key: SectionKey | string;
  label: string;
  icon: string;
  ready?: boolean;
}
const READY: NavItem[] = [
  { key: 'skills', label: '技能', icon: 'sparkles', ready: true },
  // 题1 甲：全员可见全部工具行（含用不了的），状态列说清原因
  { key: 'tools', label: '工具', icon: 'wrench', ready: true },
  ...(canAudit.value ? [{ key: 'audit', label: '审计', icon: 'clipboard', ready: true } as NavItem] : [])
];
const navGroups = computed(() => {
  if (!isManager.value) {
    return [{ label: 'Agent 能力', items: READY.filter((i) => i.key === 'skills' || i.key === 'tools') }];
  }
  return [
    {
      label: '工作台',
      items: [
        { key: 'general', label: '常规', icon: 'settings' },
        { key: 'appearance', label: '外观', icon: 'lightbulb' },
        { key: 'model', label: '模型', icon: 'brain' }
      ] as NavItem[]
    },
    {
      label: 'Agent 能力',
      items: [
        { key: 'skills', label: '技能', icon: 'sparkles', ready: true },
        { key: 'tools', label: '工具', icon: 'wrench', ready: true },
        { key: 'mcp', label: 'MCP 服务', icon: 'globe' },
        { key: 'plugins', label: '插件', icon: 'package' },
        { key: 'schedule', label: '定时任务', icon: 'timer' },
        { key: 'subagents', label: '子智能体', icon: 'bot' }
      ] as NavItem[]
    },
    {
      label: '数据与审计',
      items: [
        ...(canAudit.value ? [{ key: 'audit', label: '审计', icon: 'clipboard', ready: true } as NavItem] : []),
        { key: 'usage', label: '用量', icon: 'chart' }
      ] as NavItem[]
    }
  ];
});
const SECTION_LABEL: Record<SectionKey, string> = { skills: '技能', tools: '工具', audit: '审计' };

/** 分节状态跟地址：?s=audit / ?s=tools 可直达（审计只给有权限的人） */
const section = ref<SectionKey>('skills');
const currentLabel = computed(() => SECTION_LABEL[section.value]);
watch(
  () => route.query.s,
  (v) => {
    const next: SectionKey = v === 'audit' && canAudit.value ? 'audit' : v === 'tools' ? 'tools' : 'skills';
    if (next !== section.value) {
      section.value = next;
    }
  },
  { immediate: true }
);
function goSection(key: string) {
  if (key !== 'skills' && key !== 'tools' && key !== 'audit') return;
  if (key === section.value) return;
  section.value = key;
  // replace：切分节不堆历史，浏览器返回=离开设置页回工作台
  router.replace({ path: '/agent/settings', query: key === 'skills' ? {} : { s: key } });
}

/** 返回工作台：从工作台来的走浏览器返回（工作台会接回刚才的会话与草稿）；
 *  直接打开/刷新进来的没有上一页，就 push 到 /agent。
 *  600ms 内只认第一次点击：连点两下会触发两次 back，第二下会把工作台一起退掉。 */
let lastBackAt = 0;
function backToWorkbench() {
  const now = Date.now();
  if (now - lastBackAt < 600) return;
  lastBackAt = now;
  const back = (window.history.state as { back?: unknown } | null)?.back;
  if (typeof back === 'string' && (back === '/agent' || back.startsWith('/agent?'))) {
    router.back();
    return;
  }
  router.push('/agent');
}
function goAdmin() {
  router.push(ASSISTANT_ADMIN_PATH);
}

/* ---------- 技能列表 ---------- */
const skills = ref<CatalogRow[]>([]);
const recent = ref<AssistantRecentSkill[]>([]);
const loading = ref(true);
const failed = ref(false);
const keyword = ref('');
const selectedId = ref('');
const mobileDetail = ref(false);

const isNarrow = ref(false);
const mq = typeof window !== 'undefined' ? window.matchMedia('(max-width: 992px)') : null;
function onMqChange(e: MediaQueryListEvent) {
  isNarrow.value = e.matches;
  if (!e.matches) mobileDetail.value = false;
}
if (mq) {
  isNarrow.value = mq.matches;
  mq.addEventListener('change', onMqChange);
}
onBeforeUnmount(() => mq?.removeEventListener('change', onMqChange));

const ROLE_RANK: Record<string, number> = { member: 0, publisher: 1, admin: 2 };
const filtered = computed(() => {
  // 成员能用的排前面（同一档内保持后端顺序）：成员打开先看到自己够得着的
  const list = [...skills.value].sort((a, b) => (ROLE_RANK[a.minRole] ?? 9) - (ROLE_RANK[b.minRole] ?? 9));
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return list;
  return list.filter((s) => s.name.toLowerCase().includes(kw) || skillDesc(s.desc).toLowerCase().includes(kw) || s.id.includes(kw));
});
const sel = computed(() => skills.value.find((s) => s.id === selectedId.value) ?? null);

/** 真实计数：管理员看到全量，能说「成员可用几个」；成员/发布人只知道自己能用的数量，不编总数 */
const listLead = computed(() => {
  const total = skills.value.length;
  if (isAdminLevel.value) {
    const memberCount = skills.value.filter((s) => s.minRole === 'member').length;
    return `本租户 ${total} 个技能；成员可直接用 ${memberCount} 个，其余要发布人及以上。`;
  }
  if (canPublish.value) {
    return `你能用 ${total} 个技能（含发布人技能）；管理员专属的技能不在这里。`;
  }
  return `你能用 ${total} 个技能；需要发布人角色的技能不在这里，找管理员开。`;
});

const ROLE_TEXT: Record<string, string> = { member: '成员', publisher: '发布人', admin: '管理员' };
const roleLabel = (r: string) => ROLE_TEXT[r] ?? '成员';
function roleText(r: string) {
  if (r === 'admin') return '管理员及以上（本租户全部技能）';
  if (r === 'publisher') return '发布人及以上（发布人、管理员）';
  return '成员及以上（所有登录成员）';
}

function pickSkill(id: string) {
  selectedId.value = id;
  if (isNarrow.value) mobileDetail.value = true;
}

/* ---------- 技能详情（正文只为取编号清单；取不到就整段不渲染） ---------- */
const detailCache = new Map<string, AssistantSkillDetailView>();
const detailLoading = ref(false);
const detailFailed = ref(false);
const steps = ref<string[]>([]);
/** 连点技能的自增序号：只让最后一次点选的结果落到界面上（三审 P2：A 的失败态不许写到 B） */
let detailSeq = 0;

async function loadDetail(id: string) {
  const my = ++detailSeq;
  if (!id) {
    steps.value = [];
    detailLoading.value = false;
    detailFailed.value = false;
    return;
  }
  detailFailed.value = false;
  const hit = detailCache.get(id);
  if (hit) {
    steps.value = guideSteps(hit.body);
    detailLoading.value = false;
    return;
  }
  steps.value = [];
  detailLoading.value = true;
  try {
    const res = (await getAssistantSkillDetailView(id)) as unknown as { data?: AssistantSkillDetailView };
    if (my !== detailSeq) {
      return;
    }
    if (res?.data) {
      detailCache.set(id, res.data);
      steps.value = guideSteps(res.data.body);
    }
  } catch {
    // 详情读不到不影响列表与试一句：步骤区整段不渲染，不编
    if (my === detailSeq) {
      detailFailed.value = true;
    }
  } finally {
    if (my === detailSeq) {
      detailLoading.value = false;
    }
  }
}

watch(selectedId, (id) => void loadDetail(id));

const phrases = computed(() => (sel.value ? triggerPhrases(sel.value.desc) : []));
const sentence = computed(() => (sel.value ? buildSkillDraft(sel.value.name, sel.value.desc) : ''));

const recentRow = computed(() => recent.value.find((r) => r.skillId === selectedId.value) ?? null);
const recentCount = computed(() => (recentRow.value?.useCount ?? 0) as number);
const lastUsedText = computed(() => (recentRow.value?.lastUsedAt ? fmtIsoTime(recentRow.value.lastUsedAt) : ''));

/* ---------- 试一句 / 复制 ---------- */
function tryOne() {
  if (!sel.value) return;
  // 一次性交接：回工作台后由 /agent 取走并清空（刷新不会又填一遍）
  setPendingSkill({ id: sel.value.id, name: sel.value.name, desc: sel.value.desc });
  router.push('/agent');
}
async function copySentence() {
  const text = sentence.value;
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success('已复制这句问法');
  } catch {
    ElMessage.warning('浏览器不让读写剪贴板——手动选中这句复制吧');
  }
}

/* ---------- 审计分节 ---------- */
const auditLoading = ref(false);
const auditSearched = ref(false);
const auditFailed = ref(false);
const auditRows = ref<AssistantActionAuditRow[]>([]);
const auditTotal = ref(0);
const exporting = ref(false);
/** 筛选/翻页连点的自增序号：旧响应不许覆盖新结果（三审 P2） */
let auditSeq = 0;
const queryParams = reactive({ pageNum: 1, pageSize: 20, keyword: '', toolName: '', releaseMode: '' });
const timeRange = ref<[string, string] | []>([]);
const TOOL_OPTIONS = [
  { value: 'save_memory', label: '保存长期记忆' },
  { value: 'dcs_create_task', label: '建任务' }
];
const RELEASE_OPTIONS: [string, string][] = [
  ['confirm', '确认后执行'],
  ['once', '同类已确认'],
  ['auto', '全自动'],
  ['allow', '平台放行']
];
const releaseLabel = (mode?: string) => {
  if (!mode) return '—';
  return RELEASE_OPTIONS.find(([v]) => v === mode)?.[1] ?? mode;
};
const toolLabel = (name?: string) => (name ? (ACT[name] ?? name) : '—');
const buildQuery = () => {
  const [begin, end] = timeRange.value || [];
  return {
    ...queryParams,
    keyword: queryParams.keyword || undefined,
    toolName: queryParams.toolName || undefined,
    releaseMode: queryParams.releaseMode || undefined,
    beginTime: begin || undefined,
    endTime: end || undefined
  };
};
async function loadAudit() {
  const my = ++auditSeq;
  auditLoading.value = true;
  auditFailed.value = false;
  try {
    const res = (await pageAssistantActionAudit(buildQuery())) as unknown as { rows?: AssistantActionAuditRow[]; total?: number };
    if (my !== auditSeq) {
      return;
    }
    auditRows.value = res?.rows ?? [];
    auditTotal.value = res?.total ?? 0;
  } catch {
    // 读失败要说「读不出来」，不能显示成「还没有记录」（拦截器已 toast，这里只记状态）
    if (my === auditSeq) {
      auditFailed.value = true;
      auditRows.value = [];
      auditTotal.value = 0;
    }
  } finally {
    if (my === auditSeq) {
      auditLoading.value = false;
    }
  }
}
function handleQuery() {
  queryParams.pageNum = 1;
  auditSearched.value = true;
  void loadAudit();
}
function resetQuery() {
  queryParams.keyword = '';
  queryParams.toolName = '';
  queryParams.releaseMode = '';
  timeRange.value = [];
  handleQuery();
}
async function handleExport() {
  exporting.value = true;
  try {
    const { pageNum: _pn, pageSize: _ps, ...rest } = buildQuery();
    await exportAssistantActionAudit(rest);
  } catch {
    // 下载失败已由 axios 拦截器 toast，这里只复位按钮态
  } finally {
    exporting.value = false;
  }
}

/* ---------- 工具清单（题1-4 全甲：全员只读；状态/一句话都由后端给，页面不自己翻译） ---------- */
const toolsRows = ref<AssistantToolRow[]>([]);
const toolsLoading = ref(false);
const toolsFailed = ref(false);
let toolsLoaded = false;

const RISK_TEXT: Record<string, string> = { low: '低', mid: '中', high: '高' };
const riskText = (r: string) => RISK_TEXT[r] ?? '—';

/** 外部服务的调用次数：0 不写成「已调用 0 次」；负数=后端没读出来，别冒充 0；账本只留 90 天 */
const callsText = (n: number | null | undefined) => {
  const v = n ?? 0;
  if (v < 0) return '调用次数没读出来';
  if (v === 0) return '近 90 天还没调用过';
  return v >= 10000 ? `近 90 天调用 ${(v / 10000).toFixed(1)} 万次` : `近 90 天调用 ${v} 次`;
};

/** 说明条：真实计数从行里数出来（不写死「9 个」——以后加工具/加外部服务会自动变） */
const toolsSummary = computed(() => {
  const list = toolsRows.value;
  if (!list.length) return '';
  const ready = list.filter((t) => t.status === 'ready').length;
  const waiting = list.filter((t) => t.status === 'waiting').length;
  const off = list.filter((t) => t.status === 'off').length;
  const admin = list.filter((t) => t.status === 'admin').length;
  // 管理员档对管理员本人是可用的：按当前视角算「你能用」，避免和表格里的「管理员限定」打架
  const usable = isAdminLevel.value ? ready + admin : ready;
  const parts = [`共 ${list.length} 个，你能用 ${usable} 个`];
  if (admin) parts.push(`${admin} 个仅管理员可用`);
  if (waiting) parts.push(`${waiting} 个等租户开启`);
  if (off) parts.push(`${off} 个已停用`);
  return parts.join('；') + '。';
});

async function loadTools() {
  if (toolsLoading.value) return;
  toolsLoading.value = true;
  toolsFailed.value = false;
  try {
    const res = (await getAssistantToolCatalog()) as unknown as { data?: AssistantToolRow[] };
    toolsRows.value = res?.data ?? [];
    toolsLoaded = true;
  } catch {
    // 读失败是「读不出来」，不是「没有工具」——空态文案按这个状态分支
    toolsFailed.value = true;
    toolsRows.value = [];
  } finally {
    toolsLoading.value = false;
  }
}

/* ---------- 挂载 ---------- */
function ensureFirstSelected() {
  // 默认选中第一条：A3 的右侧详情不该一开始就是空的（手机单列不自动跳详情）
  if (!isNarrow.value && !selectedId.value && filtered.value.length) {
    selectedId.value = filtered.value[0].id;
  }
}
onMounted(async () => {
  if (!allowed.value) {
    router.replace('/403');
    return;
  }
  // 没权限的人被钳回技能分节后，地址栏的 ?s=audit 也要清掉（三审 P2：别让地址和内容打架）
  if (route.query.s === 'audit' && !canAudit.value) {
    router.replace({ path: '/agent/settings' });
  }
  const [catRes, recRes] = await Promise.allSettled([getAssistantSkillCatalog(), getAssistantRecentSkills()]);
  skills.value = catRes.status === 'fulfilled' ? ((catRes.value as unknown as { data?: CatalogRow[] })?.data ?? []) : [];
  recent.value = recRes.status === 'fulfilled' ? ((recRes.value as unknown as { data?: AssistantRecentSkill[] })?.data ?? []) : [];
  failed.value = catRes.status === 'rejected';
  loading.value = false;
  // 审计分节不自动选技能：省掉一条没人看的详情请求（三审 P2）
  if (section.value === 'skills') {
    ensureFirstSelected();
  }
  if (section.value === 'tools') {
    void loadTools();
  }
  if (section.value === 'audit' && canAudit.value) {
    void loadAudit();
  }
});
watch(section, (v) => {
  if (v === 'skills') {
    ensureFirstSelected();
  }
  if (v === 'tools' && !toolsLoaded) {
    void loadTools();
  }
  if (v === 'audit' && canAudit.value && !auditRows.value.length && !auditLoading.value) {
    void loadAudit();
  }
});
</script>

<style lang="scss" scoped>
/* 与工作台同一套令牌：中性灰底、白面板、1px 边、圆角 8/10、无阴影。
   尺寸照板里已过审的 A3 1:1 模拟：顶栏 48 / 子导航 264 / 列表 360 / 行 50 / 字号 11-20。 */
.set-ws {
  --bd: #e6e8ec;
  --bd-soft: #eef0f3;
  --bg: #f6f7f9;
  --panel: #ffffff;
  --text: #1f2328;
  --text-2: #3f4248;
  --mut: #6b7078;
  --faint: #9aa0a8;
  --fill: #e9ebef;
  --hover: #f2f3f5;
  --well: #fafbfc;
  --acc: #1f2328;
  --acc-txt: #ffffff;
  --app-chrome: 0px;
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  color: var(--text);
  font-size: 13px;
  line-height: 1.45;
}
html.dark .set-ws {
  --bd: #2b2d31;
  --bd-soft: #26282c;
  --bg: #0f1113;
  --panel: #191b1e;
  --text: #e9ebee;
  --text-2: #c7cbd1;
  --mut: #9ba1a9;
  --faint: #7c828a;
  --fill: #2a2d31;
  --hover: #22252a;
  --well: #1e2125;
  --acc: #e9ebee;
  --acc-txt: #17191c;
}

/* 顶栏 */
.set-top {
  flex: none;
  height: 48px;
  display: flex;
  align-items: center;
  gap: 0;
  padding: 0 18px;
  background: var(--panel);
  border-bottom: 1px solid var(--bd);
}
.set-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 6px 0 0;
  border: 0;
  background: none;
  color: var(--text-2);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;

  &:hover {
    color: var(--text);
  }
  &:focus-visible {
    outline: 2px solid var(--text);
    outline-offset: 2px;
    border-radius: 4px;
  }
}
.set-sep {
  width: 1px;
  height: 14px;
  margin: 0 12px;
  background: var(--bd);
  flex: none;
}
.set-crumb {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--mut);

  b {
    font-weight: 500;
    color: var(--text);
  }
  .sl {
    color: var(--faint);
  }
}
.sp {
  flex: 1;
}
.set-scope {
  font-size: 11px;
  color: var(--mut);
  white-space: nowrap;
}

/* 主体：264 子导航 + 内容 */
.set-body {
  flex: 1;
  min-height: 0;
  display: flex;
}
.set-nav {
  width: 264px;
  flex: none;
  padding: 14px 12px 10px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
}
.set-brand {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 8px 12px;
}
.set-mark {
  width: 24px;
  height: 24px;
  border-radius: 7px;
  background: var(--fill);
  display: grid;
  place-items: center;
  flex: none;
}
.set-btxt {
  min-width: 0;

  b {
    display: block;
    font-size: 13px;
    font-weight: 600;
    line-height: 1.2;
  }
  small {
    display: block;
    margin-top: 2px;
    font-size: 11px;
    color: var(--mut);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
.set-grp {
  padding: 12px 10px 5px;
  font-size: 11px;
  color: var(--mut);
}
.set-row {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  height: 32px;
  padding: 0 9px;
  border: 0;
  border-radius: 8px;
  background: none;
  color: var(--text-2);
  font: inherit;
  font-size: 13px;
  text-align: left;
  white-space: nowrap;
  cursor: pointer;

  &:hover {
    background: var(--hover);
    color: var(--text);
  }
  &:focus-visible {
    outline: 2px solid var(--text);
    outline-offset: 2px;
  }
  &.on {
    background: var(--fill);
    color: var(--text);
    font-weight: 500;
  }
  &.soon {
    color: var(--mut);
    cursor: default;

    &:hover {
      background: none;
      color: var(--mut);
    }
  }
}
.soon-tag {
  margin-left: auto;
  font-size: 11px;
  color: var(--mut);
}
.set-navfoot {
  margin-top: auto;
  padding: 10px 10px 2px;
  font-size: 11px;
  line-height: 1.7;
  color: var(--mut);
}

/* 内容区：白卡内再分 360 列表 + 详情 */
.set-main {
  flex: 1;
  min-width: 0;
  padding: 14px 16px 16px;
  display: flex;
}
.set-sheet {
  flex: 1;
  min-width: 0;
  display: flex;
  background: var(--panel);
  border: 1px solid var(--bd);
  border-radius: 10px;
  overflow: hidden;
}
.set-list {
  width: 360px;
  flex: none;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--bd-soft);
}
.set-lhead {
  padding: 18px 18px 0;

  h1 {
    margin: 0;
    font-size: 18px;
    line-height: 24px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
  p {
    margin: 4px 0 0;
    font-size: 11px;
    line-height: 1.55;
    color: var(--mut);
  }
}
.set-search {
  margin: 12px 16px 2px;
  width: calc(100% - 32px);

  :deep(.el-input__wrapper) {
    height: 32px;
    border-radius: 8px;
    box-shadow: 0 0 0 1px var(--bd) inset;
    background: var(--panel);
  }
  :deep(.el-input__inner) {
    font-size: 12px;
  }
}
.set-lgrouph {
  display: flex;
  align-items: center;
  padding: 10px 16px 4px;
  font-size: 11px;
  color: var(--mut);

  span:last-child {
    margin-left: auto;
    font-variant-numeric: tabular-nums;
  }
}
.set-rows {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 8px 4px;
}
.set-row2 {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 50px;
  padding: 5px 8px;
  border: 0;
  border-radius: 8px;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;

  &:hover {
    background: var(--hover);
  }
  &:focus-visible {
    outline: 2px solid var(--text);
    outline-offset: -2px;
  }
  &.on {
    background: var(--bd-soft);
  }
}
.set-ic {
  width: 15px;
  height: 15px;
  flex: none;
  display: grid;
  place-items: center;
  color: var(--mut);

  .set-row2.on & {
    color: var(--text);
  }
}
.set-rmain {
  min-width: 0;
  flex: 1;
  display: block;
}
.set-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  color: var(--text);
  white-space: nowrap;
}
.set-tag {
  flex: none;
  padding: 0 5px;
  border-radius: 4px;
  background: var(--hover);
  color: #5f6368;
  font-size: 11px;
  font-weight: 400;
  line-height: 16px;
  white-space: nowrap;
}
html.dark .set-tag {
  color: var(--mut);
}
.set-ds {
  display: block;
  margin-top: 1px;
  font-size: 11px;
  line-height: 16px;
  color: var(--mut);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.set-lempty {
  margin: 8px 16px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--mut);
}
.set-lfoot {
  flex: none;
  padding: 9px 16px;
  border-top: 1px solid var(--bd-soft);
  font-size: 11px;
  line-height: 1.5;
  color: var(--mut);
}
.set-lfontlink {
  padding: 0;
  border: 0;
  background: none;
  color: var(--text-2);
  font: inherit;
  font-size: 11px;
  text-decoration: underline;
  cursor: pointer;

  &:hover {
    color: var(--text);
  }
}

/* 详情 */
.set-detail {
  flex: 1;
  min-width: 0;
  padding: 22px 26px 20px;
  overflow-y: auto;
}
.set-mback {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  margin: -4px 0 10px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--text-2);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.set-dh {
  display: flex;
  align-items: center;
  gap: 8px;

  h2 {
    margin: 0;
    font-size: 20px;
    line-height: 26px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
}
.set-meta {
  margin-top: 4px;
  font-size: 11px;
  color: var(--mut);

  code {
    padding: 1px 4px;
    border-radius: 4px;
    background: var(--hover);
    color: #5f6368;
    font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
    font-size: 10px;
  }
}
html.dark .set-meta code {
  color: var(--mut);
}
.set-desc {
  margin: 12px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-2);
}
.set-well {
  margin-top: 16px;
  padding: 2px 12px;
  border: 1px solid var(--bd-soft);
  border-radius: 8px;
  background: var(--well);
}
.set-kv {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 10px;
  padding: 8px 0;
  font-size: 12px;
  line-height: 1.5;

  & + .set-kv {
    border-top: 1px solid var(--bd-soft);
  }
  b {
    font-weight: 400;
    color: var(--mut);
  }
  span {
    color: var(--text-2);
  }
  kbd {
    padding: 0 3px;
    border: 1px solid var(--bd);
    border-radius: 3px;
    font-family: inherit;
    font-size: 11px;
  }
}
.set-label {
  display: flex;
  align-items: center;
  margin: 16px 0 6px;
  font-size: 11px;
  color: var(--mut);

  span {
    margin-left: auto;
    font-variant-numeric: tabular-nums;
  }
}
.set-steps {
  margin: 0;
  padding: 0;
  list-style: none;

  li {
    display: flex;
    gap: 9px;
    padding: 3px 0;
    font-size: 12px;
    line-height: 1.6;
    color: var(--text-2);
  }
  .n {
    width: 14px;
    flex: none;
    color: var(--mut);
    font-variant-numeric: tabular-nums;
  }
}
.set-say {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;

  span {
    font-size: 12px;
    color: var(--text-2);
    white-space: nowrap;
  }
}
.set-actions {
  display: flex;
  gap: 8px;
  margin-top: 18px;
}
.set-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 14px;
  border: 1px solid var(--bd);
  border-radius: 8px;
  background: var(--panel);
  color: var(--text);
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;

  &:hover {
    border-color: var(--text);
  }
  &:focus-visible {
    outline: 2px solid var(--text);
    outline-offset: 2px;
  }
  &:disabled {
    opacity: 0.6;
    cursor: default;
  }
  &.primary {
    background: var(--acc);
    border-color: var(--acc);
    color: var(--acc-txt);
  }
}
.set-hint {
  margin: 10px 0 0;
  font-size: 11px;
  line-height: 1.65;
  color: var(--mut);
}
.set-empty {
  margin-top: 2px;
  padding: 9px 12px;
  border: 1px dashed var(--bd);
  border-radius: 8px;
  background: var(--well);
  font-size: 11px;
  line-height: 1.6;
  color: var(--mut);
}
.set-dnote {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--mut);
}

/* 审计分节 */
.set-sheet.audit {
  flex-direction: column;
  padding: 18px 20px 10px;
  overflow: hidden;
}
.audit-hd {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  flex: none;

  h1 {
    margin: 0;
    font-size: 18px;
    line-height: 24px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
  p {
    margin: 4px 0 0;
    max-width: 720px;
    font-size: 11px;
    line-height: 1.6;
    color: var(--mut);
  }
  .set-btn {
    margin-left: auto;
    flex: none;
  }
}
.audit-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 14px 0 10px;
  flex: none;

  .f-180 {
    width: 180px;
  }
  .f-160 {
    width: 160px;
  }
  .f-140 {
    width: 140px;
  }
  .f-330 {
    width: 330px;
  }
}
.audit-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.set-sheet.audit :deep(.el-pagination) {
  margin-top: 8px;
  justify-content: flex-end;
}

/* 工具分节（题2 甲：表格；390 在容器内横滚，和审计表同款） */
.set-sheet.tools {
  flex-direction: column;
  padding: 18px 20px 10px;
  overflow: hidden;

  .audit-hd p {
    max-width: 760px;
  }
}
.tools-table :deep(.el-table__cell) {
  padding: 9px 0;
  vertical-align: top;
}
.tool-nm {
  font-size: 13px;
  line-height: 18px;
  color: var(--text);
}
.tool-ds {
  margin-top: 2px;
  font-size: 11px;
  line-height: 16px;
  color: var(--mut);
}
.tool-mut {
  font-size: 12px;
  color: var(--mut);
}
.risk {
  font-size: 12px;
  color: var(--mut);

  &.r-high {
    color: #831c1e;
    font-weight: 500;
  }
  &.r-mid {
    color: var(--text-2);
  }
}
/* 窄屏工具行：分类/风险/权限/状态并成一行，跟在描述下面 */
.tool-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-2);

  .tool-st-inline {
    display: inline-flex;

    &::before {
      content: '·';
      margin-right: 6px;
      color: var(--faint);
    }
  }
}
.tool-st {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-2);
  white-space: nowrap;

  .dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--faint);
    flex: none;

    &.d-ready {
      background: #247b50;
    }
    &.d-waiting {
      background: #b45309;
    }
    &.d-admin {
      background: #1f2328;
    }
    &.d-off {
      background: #9aa0a8;
    }
  }
}
.tools-foot {
  flex: none;
  margin: 10px 0 0;
  font-size: 11px;
  color: var(--mut);

  .set-lfontlink {
    margin-left: 4px;
  }
}
html.dark .risk.r-high {
  color: #e5566f;
}
html.dark .tool-st .dot.d-ready {
  background: #3fc07a;
}
html.dark .tool-st .dot.d-admin {
  background: #e9ebee;
}
/* 暗色下「等开启」和「已停用」也要有可读的亮度差，否则两颗点都糊成深灰 */
html.dark .tool-st .dot.d-waiting {
  background: #e0a45c;
}
html.dark .tool-st .dot.d-off {
  background: #6b727c;
}

/* 手机档（992 断点，全仓统一）：子导航变横条；列表/详情单列切换，无横向溢出 */
@media (max-width: 992px) {
  .set-top {
    padding: 0 12px;
  }
  .set-crumb {
    display: none;
  }
  .set-sep {
    display: none;
  }
  .set-body {
    flex-direction: column;
  }
  .set-nav {
    width: auto;
    flex: none;
    flex-direction: row;
    align-items: center;
    gap: 6px;
    padding: 8px 12px;
    overflow-x: auto;
    border-bottom: 1px solid var(--bd);
  }
  .set-brand,
  .set-grp,
  .set-navfoot {
    display: none;
  }
  .set-row {
    width: auto;
    flex: none;
    padding: 0 12px;
  }
  .set-row.soon {
    display: none;
  }
  .set-main {
    padding: 8px;
  }
  .set-sheet {
    border-radius: 8px;
  }
  .set-list {
    width: 100%;
    border-right: 0;
  }
  .set-list.is-hidden,
  .set-detail.is-hidden {
    display: none;
  }
  .set-detail {
    padding: 16px 16px 20px;
  }
  .set-sheet.audit {
    padding: 14px 12px 8px;
  }
  .audit-hd {
    flex-wrap: wrap;
  }
  .audit-hd .set-btn {
    margin-left: 0;
  }
  .audit-filters {
    .f-180,
    .f-160,
    .f-140,
    .f-330 {
      width: 100%;
    }
  }
}
</style>
