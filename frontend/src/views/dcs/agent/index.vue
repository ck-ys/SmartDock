<template>
  <div class="agent-ws">
    <!-- 移动端：左栏抽屉遮罩 -->
    <div v-if="asideOpen" class="aws-mask" @click="asideOpen = false"></div>

    <aside class="aws-side" :class="{ 'mobile-open': asideOpen }">
      <div class="aws-brand">
        <span class="mk">S</span>
        <b>智能体工作台</b>
        <button class="aws-side-close" type="button" aria-label="关闭侧栏" @click="asideOpen = false"><IconSpan name="x" :size="14" /></button>
      </div>
      <button class="aws-new" type="button" @click="newTask"><IconSpan name="plus" :size="14" /> 新任务</button>

      <nav class="aws-nav" aria-label="工作台导航">
        <a class="on" aria-current="page"><IconSpan name="message" :size="15" /> 会话</a>
        <a v-if="canAudit" role="button" tabindex="0" @click="go('/ai/assistant-audit')" @keydown.enter="go('/ai/assistant-audit')">
          <IconSpan name="clipboard" :size="15" /> 审计
        </a>
        <a class="switch" role="button" tabindex="0" @click="go('/index')" @keydown.enter="go('/index')" @keydown.space.prevent="go('/index')">
          <IconSpan name="arrow-up-right" :size="15" /> 返回业务系统
        </a>
      </nav>

      <!-- 今日额度：真值来自 /dcs/assistant/status（未开启/未配模型时如实说明，不编数） -->
      <div class="aws-quota">
        <div class="qhead"><IconSpan name="timer" :size="12" /> 今日额度</div>
        <div v-if="status && status.enabled && status.modelConfigured" class="qbody">
          <div v-if="!roundUnlimited" class="bar"><i :style="{ width: quotaPct + '%' }"></i></div>
          <div class="qtext">
            已用 <b>{{ status.turnsUsedToday }}</b> / {{ roundUnlimited ? '不限' : status.dailyRoundLimitPerUser }} 回合
            <span>· 日预算 ¥{{ budgetText }}</span>
          </div>
        </div>
        <div v-else class="qtext muted">{{ quotaText }}</div>
      </div>

      <div class="aws-glabel">
        <span>最近会话</span>
        <button type="button" @click="openSession()">全部 →</button>
      </div>
      <div class="aws-sessions">
        <template v-for="g in grouped" :key="g.key">
          <div class="aws-day">{{ g.key }}</div>
          <div v-for="c in g.items" :key="c.id" class="aws-sess" role="button" tabindex="0" @click="openConv(c)" @keydown.enter="openConv(c)">
            <span class="t">{{ sessionTitle(c) }}</span>
            <span class="d">{{ rowTime(c.lastMessageTime, now) }}</span>
          </div>
        </template>
        <div v-if="!sessionsLoading && grouped.length === 0" class="aws-empty">
          <p>还没有会话。下面写一句，或点一张场景卡开始。</p>
        </div>
        <p v-else-if="sessionsLoading" class="aws-empty">正在读取会话…</p>
      </div>

      <div class="aws-user">
        <span class="av">{{ avatarChar }}</span>
        <div class="uinfo">
          <b>{{ userStore.nickname || '当前用户' }}</b>
          <span>{{ roleText }}</span>
        </div>
      </div>
    </aside>

    <main class="aws-main">
      <div class="aws-topbar">
        <button class="aws-menu-btn" type="button" aria-label="打开侧栏" :aria-expanded="asideOpen" @click="asideOpen = true">
          <IconSpan name="menu" :size="16" />
        </button>
        <span class="tb-brand"><span class="mk">S</span> 智能体工作台</span>
        <span class="tb-spacer"></span>
        <button class="tb-new" type="button" @click="newTask"><IconSpan name="plus" :size="13" /> 新任务</button>
      </div>

      <div class="aws-col">
        <header class="aws-hero">
          <h1>今天帮你做点什么</h1>
          <p>问数据、写周报、盯任务；@ 带上文件，/ 调技能与指令</p>
        </header>

        <div class="aws-tabs" role="tablist" aria-label="场景">
          <button
            v-for="t in PRESET_TABS"
            :key="t.key"
            class="aws-tab"
            :class="{ on: tab === t.key }"
            role="tab"
            :aria-selected="tab === t.key"
            type="button"
            @click="switchTab(t.key)"
          >
            {{ t.label }}
          </button>
        </div>

        <p class="aws-lead">先选场景，点一张直接填进下方输入框</p>
        <div class="aws-chips">
          <button v-for="chip in activeChips" :key="chip.label" class="aws-chip" type="button" @click="fillChip(chip)">
            {{ chip.label }}
          </button>
        </div>

        <div class="aws-composer">
          <textarea
            ref="inputRef"
            v-model="draft"
            class="aws-input"
            rows="3"
            placeholder="今天帮你做些什么？@ 添加上下文，/ 调用技能与指令"
            aria-label="输入你的任务"
            @keydown.meta.enter="send"
            @keydown.ctrl.enter="send"
          ></textarea>
          <div class="aws-crow">
            <span class="aws-hint">⌘/Ctrl + Enter 发送</span>
            <button class="aws-send" type="button" :aria-disabled="!canSend" :disabled="!canSend" aria-label="发送" @click="send">
              <IconSpan name="arrow-up" :size="16" />
            </button>
          </div>
        </div>

        <div class="aws-under">
          <span><IconSpan name="paperclip" :size="13" /> 附件在会话里上传</span>
          <span class="grow"></span>
          <span v-if="status && status.enabled && status.modelConfigured" class="muted">
            {{ roundUnlimited ? '剩余回合不限（按积分计费）' : `剩余 ${status.remainingTurnsToday} 回合` }}
          </span>
          <span v-else class="muted">{{ quotaText }}</span>
        </div>

        <section class="aws-strip" aria-label="能做什么">
          <div class="aws-strip-h">
            <b>能做什么</b>
            <em>能力由会话按需挂载；写操作一律先确认</em>
            <button type="button" class="aws-link" @click="openSession()">去会话里用 →</button>
          </div>
          <div v-if="skills.length" class="aws-cards">
            <article v-for="s in skills.slice(0, 3)" :key="s.id" class="aws-card">
              <div class="ch">
                <span class="tile"><IconSpan name="sparkles" :size="18" /></span>
                <div>
                  <div class="tn">{{ s.name }}</div>
                  <div class="ts">{{ minRoleText(s.minRole) }}</div>
                </div>
              </div>
              <p class="td">{{ s.desc }}</p>
              <button class="cu" type="button" @click="askSkill(s)">用它开个头 →</button>
            </article>
          </div>
          <div v-else class="aws-card empty-card">
            <p class="td">{{ skillsLoading ? '正在读取能力…' : '会话里会按需挂载技能与工具，不需要在这里挑。' }}</p>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import IconSpan from '@/components/DcsAssistant/IconSpan.vue';
import { useUserStore } from '@/store/modules/user';
import { getAssistantSkillCatalog, getAssistantStatus, listConversations } from '@/api/dcs/assistant';
import type { AssistantConversation, AssistantStatus } from '@/api/dcs/assistant';
import { PRESET_TABS, groupSessions, rowTime, sessionTitle } from './agentView';

interface SkillRow {
  id: string;
  name: string;
  desc: string;
  minRole: 'member' | 'publisher' | 'admin';
}

const router = useRouter();
const userStore = useUserStore();

const tab = ref<(typeof PRESET_TABS)[number]['key']>('office');
const draft = ref('');
const inputRef = ref<HTMLTextAreaElement>();
const asideOpen = ref(false);

const sessions = ref<AssistantConversation[]>([]);
const sessionsLoading = ref(true);
const status = ref<AssistantStatus | null>(null);
const statusError = ref(false);
const skills = ref<SkillRow[]>([]);
const skillsLoading = ref(true);
/** 分组/行时间的“现在”：每分钟走一格，页面挂着跨零点也能把「今天」翻成「昨天」 */
const now = ref(Date.now());
let clockTimer: ReturnType<typeof setInterval> | undefined;

const activeChips = computed(() => PRESET_TABS.find((t) => t.key === tab.value)?.chips ?? []);
/** 助手未开启/未配模型/当前用户被停用时前端先拦住（后端与会话页仍有自己的闸）；状态还没回来时放行 */
const assistantReady = computed(() => !status.value || (status.value.enabled && status.value.modelConfigured && !status.value.quotaDisabled));
const canSend = computed(() => draft.value.trim().length > 0 && assistantReady.value);
const grouped = computed(() => groupSessions(sessions.value, now.value));
const avatarChar = computed(() => (userStore.nickname || 'U').slice(0, 1));
const roleText = computed(() => (userStore.roles.some((r) => r === 'superadmin' || r === 'tenant_admin' || r === 'admin') ? '管理员' : '成员'));
const canAudit = computed(() => userStore.permissions.includes('*:*:*') || userStore.permissions.includes('dcs:assistant:audit:query'));
const allowed = computed(() => userStore.permissions.includes('*:*:*') || userStore.permissions.includes('agentws:workspace:use'));
const quotaPct = computed(() => {
  const s = status.value;
  if (!s || !s.dailyRoundLimitPerUser) return 0;
  return Math.min(100, Math.round((s.turnsUsedToday / s.dailyRoundLimitPerUser) * 100));
});
const quotaText = computed(() => {
  const s = status.value;
  if (!s) return statusError.value ? '额度读取失败（进会话页可重试）' : '额度读取中…';
  if (!s.enabled) return '助手未开启（联系管理员）';
  if (!s.modelConfigured) return '模型未配置（联系管理员）';
  if (s.quotaDisabled) return '你的助手已被管理员暂停';
  return '额度读取中…';
});
/** 积分计费租户后端给 UNLIMITED_ROUNDS=1,000,000——如实写「不限」，不把哨兵值当真实额度展示 */
const roundUnlimited = computed(() => (status.value?.dailyRoundLimitPerUser ?? 0) >= 1_000_000);
const budgetText = computed(() => {
  const n = Number(status.value?.dailyBudgetAmount);
  return Number.isFinite(n) ? n.toFixed(2) : '—';
});

function minRoleText(r: SkillRow['minRole']) {
  return r === 'admin' ? '管理员可用' : r === 'publisher' ? '发布者可用' : '成员可用';
}

function go(path: string) {
  router.push(path);
}

function switchTab(k: (typeof PRESET_TABS)[number]['key']) {
  tab.value = k;
}

function fillChip(chip: { label: string; prompt: string }) {
  draft.value = chip.prompt;
  nextTick(() => inputRef.value?.focus());
}

function askSkill(s: SkillRow) {
  draft.value = `用「${s.name}」帮我：`;
  nextTick(() => inputRef.value?.focus());
}

function newTask() {
  draft.value = '';
  asideOpen.value = false;
  nextTick(() => inputRef.value?.focus());
}

/** 跨页交接：会话页读 sessionStorage 里的一次性指令（长提示词不走 query，避免截断/刷新重放）。
 *  at=写入时刻：会话页只认 2 分钟内的指令——跳转失败（403/登录过期/导航中止）留下的旧指令，
 *  不会在以后某次正常进入时突然自动发送（多席审查 P1-4）。 */
const PREFILL_KEY = 'dcs.agent.prefill';
const CONV_KEY = 'dcs.agent.conv';
const NEW_KEY = 'dcs.agent.new';
const AT_KEY = 'dcs.agent.at';

function handoffToChat() {
  sessionStorage.setItem(AT_KEY, String(Date.now()));
  // from=agent：路由守卫据此放行会话页（菜单直接点 /ai/assistant 会先落工作台 = V8 进门）
  router.push({ path: '/ai/assistant', query: { from: 'agent' } });
}

function openSession() {
  sessionStorage.removeItem(PREFILL_KEY);
  sessionStorage.removeItem(CONV_KEY);
  sessionStorage.removeItem(NEW_KEY);
  handoffToChat();
}

function openConv(c: AssistantConversation) {
  // conv 与 prefill 互斥：快速连点时不至于拿着上一句提示词又去开另一个会话
  sessionStorage.removeItem(PREFILL_KEY);
  sessionStorage.removeItem(NEW_KEY);
  sessionStorage.setItem(CONV_KEY, String(c.id));
  handoffToChat();
}

function send() {
  const text = draft.value.trim();
  if (!text) return;
  if (!assistantReady.value) {
    // 键盘 ⌘/Ctrl+Enter 也要过同一道闸（按钮 disabled 拦不住键盘）
    ElMessage.warning(quotaText.value);
    return;
  }
  sessionStorage.removeItem(CONV_KEY);
  // 工作台发起的任务一律落到新会话（不吃上次会话的上下文）
  sessionStorage.setItem(NEW_KEY, '1');
  sessionStorage.setItem(PREFILL_KEY, text);
  draft.value = '';
  handoffToChat();
}

async function loadSessions() {
  sessionsLoading.value = true;
  try {
    const res = (await listConversations({ pageNum: 1, pageSize: 20, status: 'ACTIVE' })) as unknown as {
      rows?: AssistantConversation[];
    };
    sessions.value = res?.rows ?? [];
  } catch {
    sessions.value = [];
    ElMessage.warning('会话列表读取失败，可点「全部 →」进会话页重试');
  } finally {
    sessionsLoading.value = false;
  }
}

async function loadStatus() {
  try {
    // 本项目 request 拦截器返回 R 包装（{code,msg,data}），业务体在 .data 下（对齐 HeroWelcome 等既有消费方）
    const res = (await getAssistantStatus()) as unknown as { data?: AssistantStatus };
    status.value = res?.data ?? null;
    statusError.value = !status.value;
  } catch {
    status.value = null;
    statusError.value = true;
  }
}

async function loadSkills() {
  skillsLoading.value = true;
  try {
    const res = (await getAssistantSkillCatalog()) as unknown as { data?: SkillRow[] };
    skills.value = Array.isArray(res?.data) ? res.data : [];
  } catch {
    skills.value = [];
  } finally {
    skillsLoading.value = false;
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') asideOpen.value = false;
}

onMounted(() => {
  // 独立分区不进动态路由，权限在这里自检；未授权统一进 /403 说明页（保留业务壳层）
  if (!allowed.value) {
    router.replace('/403');
    return;
  }
  void loadSessions();
  void loadStatus();
  void loadSkills();
  // 跨零点不刷新也能把「今天」翻成「昨天」；Esc 收手机侧栏
  clockTimer = setInterval(() => (now.value = Date.now()), 60_000);
  window.addEventListener('keydown', onKeydown);
});

onBeforeUnmount(() => {
  clearInterval(clockTimer);
  window.removeEventListener('keydown', onKeydown);
});
</script>

<style lang="scss" scoped>
/* V8 纸面：浅色=纸白底 + 中性灰选中 + 近黑唯一实心；暗色=深炭纸面（html.dark 下整组翻转） */
.agent-ws {
  --bd: rgba(13, 13, 13, 0.1);
  --bd-soft: rgba(13, 13, 13, 0.06);
  --bd-2: rgba(13, 13, 13, 0.18);
  --bg: #f8f8f8;
  --side: #f0f0f0;
  --panel: #ffffff;
  --hov: rgba(13, 13, 13, 0.05);
  --sel: #e6e6e6;
  --text: #0d0d0d;
  --mut: #52525b;
  --faint: #6b6b6b;
  --acc: #0d0d0d;
  --acc-soft: #ececec;
  --acc-txt: #ffffff;
  --danger: #c4242a;
  --ok: #1e7b4f;
  min-height: 100vh;
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr);
  background: var(--bg);
  color: var(--text);
  font-size: 14px;
}
html.dark .agent-ws {
  --bd: rgba(255, 255, 255, 0.12);
  --bd-soft: rgba(255, 255, 255, 0.07);
  --bd-2: rgba(255, 255, 255, 0.22);
  --bg: #101216;
  --side: #17191e;
  --panel: #1b1e24;
  --hov: rgba(255, 255, 255, 0.06);
  --sel: #2a2d33;
  --text: #e9ebee;
  --mut: #a8adb5;
  --faint: #8d929a;
  --acc: #e9ebee;
  --acc-soft: #2a2d33;
  --acc-txt: #17191c;
  --danger: #f08585;
  --ok: #4ec98a;
}

/* ---------- 左栏 ---------- */
.aws-side {
  background: var(--side);
  border-right: 1px solid var(--bd);
  padding: 0 12px 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  max-height: 100vh;
}
.aws-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 54px;
  padding: 0 6px;
}
.aws-brand .mk,
.aws-topbar .mk {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--acc);
  color: var(--acc-txt);
  font-size: 11px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.aws-brand b {
  font-size: 13.5px;
  font-weight: 650;
}
.aws-side-close {
  display: none;
  margin-left: auto;
  border: none;
  background: transparent;
  color: var(--mut);
  padding: 4px;
  border-radius: 6px;
  cursor: pointer;
}
.aws-new {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  width: 100%;
  background: var(--panel);
  border: 1px solid var(--bd-2);
  border-radius: 8px;
  padding: 7px 0;
  font-size: 13px;
  color: var(--text);
  font-weight: 600;
  cursor: pointer;
  margin-bottom: 10px;
}
.aws-new:hover {
  background: var(--acc);
  color: var(--acc-txt);
  border-color: var(--acc);
}
.aws-nav {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin-bottom: 8px;
}
.aws-nav a {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 32px;
  padding: 0 10px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--mut);
  cursor: pointer;
}
.aws-nav a:hover {
  background: var(--hov);
  color: var(--text);
}
.aws-nav a.on {
  background: var(--sel);
  color: var(--text);
  font-weight: 600;
}
.aws-nav a.switch {
  margin-top: 4px;
  color: var(--faint);
}
.aws-nav a.switch:hover {
  color: var(--text);
}

.aws-quota {
  margin: 6px 0 4px;
  padding: 10px;
  border: 1px solid var(--bd);
  border-radius: 10px;
  background: var(--panel);
}
.aws-quota .qhead {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--faint);
  font-weight: 600;
}
.aws-quota .bar {
  margin-top: 8px;
  height: 4px;
  border-radius: 999px;
  background: var(--acc-soft);
  overflow: hidden;
}
.aws-quota .bar i {
  display: block;
  height: 100%;
  background: var(--text);
  border-radius: 999px;
}
.aws-quota .qtext {
  margin-top: 7px;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--mut);
}
.aws-quota .qtext b {
  color: var(--text);
  font-weight: 650;
}
.aws-quota .qtext span {
  color: var(--faint);
}
.aws-quota .qtext.muted {
  color: var(--faint);
}

.aws-glabel {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 10px 10px 5px;
  font-size: 11px;
  color: var(--faint);
  font-weight: 600;
}
.aws-glabel button {
  margin-left: auto;
  border: none;
  background: transparent;
  color: var(--faint);
  font-size: 11px;
  cursor: pointer;
  padding: 0;
}
.aws-glabel button:hover {
  color: var(--text);
}
.aws-sessions {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}
.aws-day {
  padding: 6px 10px 3px;
  font-size: 11px;
  color: var(--faint);
}
.aws-sess {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 8px;
  cursor: pointer;
}
.aws-sess:hover {
  background: var(--hov);
}
.aws-sess .t {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.aws-sess .d {
  font-size: 11px;
  color: var(--faint);
  flex: none;
}
.aws-empty {
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--faint);
}
.aws-user {
  margin-top: auto;
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 6px 2px;
  border-top: 1px solid var(--bd);
}
.aws-user .av {
  width: 28px;
  height: 28px;
  border-radius: 999px;
  background: var(--acc);
  color: var(--acc-txt);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  flex: none;
}
.aws-user b {
  font-size: 13px;
  font-weight: 600;
  display: block;
}
.aws-user span {
  font-size: 11px;
  color: var(--faint);
}

/* ---------- 主区 ---------- */
.aws-main {
  min-width: 0;
  overflow-y: auto;
  max-height: 100vh;
}
.aws-topbar {
  display: none;
  align-items: center;
  gap: 8px;
  height: 48px;
  padding: 0 12px;
  border-bottom: 1px solid var(--bd);
  background: var(--panel);
  position: sticky;
  top: 0;
  z-index: 5;
}
.aws-menu-btn,
.tb-new {
  border: 1px solid var(--bd);
  background: var(--panel);
  color: var(--mut);
  border-radius: 8px;
  height: 30px;
  padding: 0 9px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  font-size: 12px;
}
.tb-brand {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 650;
}
.tb-spacer {
  flex: 1;
}
.aws-col {
  width: 100%;
  max-width: 848px;
  margin: 0 auto;
  padding: 0 24px 40px;
}
.aws-hero {
  margin-top: 48px;
  text-align: center;
}
.aws-hero h1 {
  font-size: 30px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.2;
}
.aws-hero p {
  margin-top: 8px;
  font-size: 15px;
  line-height: 1.6;
  color: var(--mut);
  text-wrap: balance;
}
.aws-tabs {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-top: 16px;
  flex-wrap: wrap;
}
.aws-tab {
  height: 36px;
  padding: 0 16px;
  border-radius: 999px;
  border: 1px solid var(--bd);
  background: var(--panel);
  font-size: 13px;
  color: var(--mut);
  cursor: pointer;
}
.aws-tab:hover {
  color: var(--text);
  border-color: var(--bd-2);
}
.aws-tab.on {
  border-color: transparent;
  background: var(--sel);
  color: var(--text);
  font-weight: 600;
}
.aws-lead {
  margin-top: 16px;
  text-align: center;
  font-size: 12px;
  color: var(--faint);
}
.aws-chips {
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 10px;
}
.aws-chip {
  height: 32px;
  padding: 0 14px;
  border-radius: 16px;
  border: 1px solid var(--bd);
  background: var(--panel);
  font-size: 13px;
  color: var(--mut);
  cursor: pointer;
}
.aws-chip:hover {
  color: var(--text);
  border-color: var(--bd-2);
  background: var(--acc-soft);
}
.aws-composer {
  margin-top: 24px;
  border: 1px solid var(--bd-2);
  border-radius: 16px;
  background: var(--panel);
  padding: 12px 14px 10px;
  display: flex;
  flex-direction: column;
  min-height: 146px;
}
.aws-composer:focus-within {
  border-color: var(--text);
}
.aws-input {
  width: 100%;
  min-height: 76px;
  resize: vertical;
  background: transparent;
  border: none;
  outline: none;
  font-family: inherit;
  font-size: 15px;
  line-height: 1.6;
  color: var(--text);
  padding: 4px 6px;
}
.aws-input::placeholder {
  color: var(--faint);
}
.aws-crow {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.aws-hint {
  font-size: 11.5px;
  color: var(--faint);
}
.aws-send {
  margin-left: auto;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  border: 1.5px solid var(--acc);
  background: var(--acc);
  color: var(--acc-txt);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
.aws-send[aria-disabled='true'] {
  background: transparent;
  border-color: var(--bd-2);
  color: var(--faint);
  cursor: default;
}
.aws-under {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 10px;
  padding-left: 6px;
  font-size: 12.5px;
  color: var(--mut);
}
.aws-under span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.aws-under .grow {
  flex: 1;
}
.aws-under .muted {
  color: var(--faint);
}

/* ---------- 能力卡 ---------- */
.aws-strip {
  margin-top: 30px;
}
.aws-strip-h {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 13px;
  color: var(--text);
}
.aws-strip-h b {
  font-weight: 650;
}
.aws-strip-h em {
  font-style: normal;
  font-size: 12.5px;
  color: var(--mut);
}
.aws-link {
  margin-left: auto;
  border: none;
  background: transparent;
  font-size: 12.5px;
  color: var(--text);
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
  padding: 0;
}
.aws-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-top: 10px;
  align-items: start;
}
.aws-card {
  border: 1px solid var(--bd);
  border-radius: 12px;
  background: var(--panel);
  padding: 16px 16px 12px;
}
.aws-card:hover {
  border-color: var(--bd-2);
}
.aws-card .ch {
  display: flex;
  align-items: center;
  gap: 10px;
}
.aws-card .tile {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  background: var(--acc-soft);
  color: var(--text);
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
.aws-card .tn {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.3;
  color: var(--text);
}
.aws-card .ts {
  font-size: 12px;
  color: var(--faint);
}
.aws-card .td {
  margin-top: 8px;
  min-height: 40px;
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--mut);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.aws-card .cu {
  margin-top: 6px;
  border: none;
  background: transparent;
  color: var(--text);
  font-size: 12.5px;
  cursor: pointer;
  padding: 2px 0;
}
.aws-card .cu:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}
.aws-card.empty-card {
  grid-column: 1 / -1;
}
.aws-card.empty-card .td {
  min-height: 0;
}

/* 键盘可达：统一焦点环（纸面案用近黑/浅色描边，不引系统蓝） */
.agent-ws :is(button, a, [role='button']):focus-visible {
  outline: 2px solid var(--text);
  outline-offset: 2px;
}

/* ---------- 响应式 ---------- */
.aws-mask {
  display: none;
}
@media (max-width: 992px) {
  .agent-ws {
    grid-template-columns: minmax(0, 1fr);
  }
  .aws-side {
    position: fixed;
    inset: 0 auto 0 0;
    width: 280px;
    z-index: 30;
    transform: translateX(-100%);
    transition: transform 0.18s ease;
    max-height: none;
  }
  .aws-side.mobile-open {
    transform: translateX(0);
  }
  .aws-side-close {
    display: inline-flex;
  }
  .aws-mask {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.32);
    z-index: 20;
  }
  .aws-main {
    max-height: none;
  }
  .aws-topbar {
    display: flex;
  }
  .aws-hero {
    margin-top: 28px;
  }
  .aws-hero h1 {
    font-size: 24px;
  }
  .aws-cards {
    grid-template-columns: minmax(0, 1fr);
  }
  .aws-col {
    padding: 0 16px 32px;
  }
}
@media (max-width: 600px) {
  /* 手机档：标题+入口同一行，说明句独占一行——避免「能做什么」被挤成竖排 */
  .aws-strip-h {
    flex-wrap: wrap;
    row-gap: 2px;
  }
  .aws-strip-h em {
    order: 3;
    flex: 1 0 100%;
  }
}
@media (prefers-reduced-motion: reduce) {
  .aws-side {
    transition: none;
  }
}
</style>
