<template>
  <div class="sidebar-user" :class="{ 'is-collapse': collapse }">
    <!-- 2026-10-25 拍板（四案板 · 方案二「单一工作区面板」）：左下只留一行，点开一个面板分
         「单位 / 账号」两段——两个触发点合并成一个，归属歧义从根上消失；色块从饱和彩色换成
         浅色底+深色首字（身份浅色相 60° 错开，色只上数据不上框架）；对勾删除，当前单位=灰底墨字，
         「默认」降为名字后的灰字；单位管理去图标去箭头；退出登录独立分区警示红。 -->
    <el-dropdown
      trigger="click"
      placement="top-start"
      :disabled="switching"
      popper-class="sidebar-user-panel-popper"
      @command="onCommand"
      @visible-change="onVisible"
    >
      <button
        type="button"
        class="sidebar-user__row"
        :aria-label="`工作区面板，当前账号${nickname}${canSwitch ? '，单位' + unitName : ''}`"
        aria-haspopup="menu"
        :aria-expanded="panelOpen"
      >
        <img v-if="avatar" :src="avatar" class="sidebar-user__avatar" alt="" />
        <span v-else class="sidebar-user__avatar sidebar-user__avatar--text" :style="avatarTint" aria-hidden="true">{{ initial }}</span>
        <span class="sidebar-user__text">
          <b class="sidebar-user__name">
            {{ nickname || '当前账号' }}
            <span v-if="canSwitch && unitName" class="sidebar-user__name-unit">· {{ switching ? '切换中…' : unitName }}</span>
          </b>
          <span class="sidebar-user__meta">{{ meta }}</span>
        </span>
        <el-icon class="sidebar-user__chev"><ArrowUp /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <!-- ══ 单位段：超管且多单位才有；普通成员整段不渲染 ══ -->
          <template v-if="canSwitch">
            <li role="presentation" class="sidebar-user__sec">单位</li>
            <li v-if="!ready" role="presentation" class="sidebar-user__hint">单位加载中…</li>
            <li v-else-if="loadFailed" role="presentation" class="sidebar-user__hint">
              <el-dropdown-item command="__retry" class="sidebar-user__retry">单位加载失败，点击重试</el-dropdown-item>
            </li>
            <template v-else>
              <!-- 单位 ≥5 才给搜索框：小部署不显冗余 -->
              <li v-if="tenants.length >= 5" role="presentation" class="sidebar-user__search">
                <el-icon class="sidebar-user__search-ic"><Search /></el-icon>
                <input ref="searchRef" v-model="keyword" type="text" class="sidebar-user__search-input" placeholder="搜索单位…" aria-label="搜索单位" />
              </li>
              <!-- 当前单位：灰底墨字锚定「你在哪」，不可点；默认单位跟灰字「默认」 -->
              <li role="presentation" class="sidebar-user__current">
                <span class="sidebar-user__tile" :style="tileTint(currentKey)" aria-hidden="true">{{ tileChar }}</span>
                <span class="sidebar-user__current-name">{{ unitName }}</span>
                <span v-if="isDefaultTenant" class="sidebar-user__suffix">默认</span>
              </li>
              <el-dropdown-item
                v-for="item in filteredOptions"
                :key="item.tenantId"
                :command="String(item.tenantId) === defaultId ? '__default' : String(item.tenantId)"
                class="sidebar-user__item"
                :title="item.companyName"
              >
                <span class="sidebar-user__tile" :style="tileTint(String(item.tenantId))" aria-hidden="true">{{ tileCharOf(item.companyName) }}</span>
                <span class="sidebar-user__opt">{{ item.companyName }}</span>
              </el-dropdown-item>
              <li v-if="!filteredOptions.length" role="presentation" class="sidebar-user__empty">没有其它匹配的单位</li>
              <el-dropdown-item command="__manage" class="sidebar-user__manage">单位管理</el-dropdown-item>
            </template>
            <li role="presentation" class="sidebar-user__hr"></li>
          </template>

          <!-- ══ 账号段：动作与顶栏头像菜单共用 useAccountMenu ══ -->
          <li v-if="canSwitch" role="presentation" class="sidebar-user__sec">账号</li>
          <el-dropdown-item v-if="showProfile" command="profile">个人中心</el-dropdown-item>
          <el-dropdown-item v-if="showLayout" command="layout">布局设置</el-dropdown-item>
          <el-dropdown-item command="feedback">反馈问题</el-dropdown-item>
          <li role="presentation" class="sidebar-user__hr"></li>
          <el-dropdown-item command="logout" class="sidebar-user__logout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useUserStore } from '@/store/modules/user';
import { useSettingsStore } from '@/store/modules/settings';
import { useWorkspace } from '@/composables/useWorkspace';
import { useTenantSwitch } from '@/composables/useTenantSwitch';
import { useAccountMenu } from '@/composables/useAccountMenu';

defineProps<{ collapse: boolean }>();
const emit = defineEmits<{ 'set-layout': []; feedback: [] }>();

const router = useRouter();
const userStore = useUserStore();
const settingsStore = useSettingsStore();
const { tenantName } = useWorkspace();
const {
  tenants,
  canSwitch,
  switching,
  ready,
  loadFailed,
  currentTenant,
  currentId,
  isDefaultTenant,
  defaultId,
  unitOptions,
  load,
  refresh,
  switchTo
} = useTenantSwitch();
const { showLayout, showProfile, goProfile, logout } = useAccountMenu();

const nickname = computed(() => userStore.nickname);
const avatar = computed(() => userStore.avatar);
const initial = computed(() => (nickname.value || '?').slice(0, 1).toUpperCase());
// 角色用现有角色码翻译：超管 admin，其余统一叫「成员」，不臆造岗位名
const roleLabel = computed(() => (userStore.roles.includes('superadmin') ? '管理员' : '成员'));
// 昵称本身就叫「管理员」时不再重复角色，避免出现「管理员 · 管理员 · 演示单位」
const roleText = computed(() => (roleLabel.value === nickname.value ? '' : roleLabel.value));
// 单行副文字：超管=角色（单位已在主行小字常驻）；成员=角色+单位名
const meta = computed(() => (canSwitch.value ? roleText.value : [roleText.value, tenantName.value].filter(Boolean).join(' · ')));

// 行内单位显示名：优先租户列表里的当前单位，加载前退回工作区名称，不编造
const unitName = computed(() => currentTenant.value?.companyName || tenantName.value || '当前单位');
// 色块字取单位名首字（按码点取，emoji 不劈半；去空白；拉丁转大写）；空名退「单」
const tileCharOf = (name?: string) => {
  const first = [...(name || '').trim()][0];
  return first ? first.toUpperCase() : '单';
};
const tileChar = computed(() => tileCharOf(currentTenant.value?.companyName || tenantName.value));
// 当前单位色块用的键：默认单位固定取第 0 号浅色相（青），其余按租户 id 稳定哈希
const currentKey = computed(() => currentId.value || defaultId.value);

// 身份浅色相（2026-10-25 拍板）：浅色相 60° 错开 6 对，色只上身份不上框架；
// 暗色下换深底浅字对（同一色相家族），跟随主题开关即时切换
const TILE_TINTS_LIGHT: Array<[string, string]> = [
  ['#dcebed', '#155a6b'],
  ['#f3ece1', '#7a4d1e'],
  ['#e9e5f0', '#4c2f78'],
  ['#e3f0e4', '#2a6138'],
  ['#f0e3e6', '#7c3350'],
  ['#e0e8f0', '#2d4a78']
];
const TILE_TINTS_DARK: Array<[string, string]> = [
  ['#17323c', '#7fd4e8'],
  ['#332814', '#f0d08a'],
  ['#2a2433', '#c9b8e8'],
  ['#152a1a', '#9fdcac'],
  ['#331d26', '#f0a8c0'],
  ['#1a2635', '#a8c4f0']
];
const isDark = computed(() => settingsStore.dark);
const tintPair = (key: string): [string, string] => {
  const tints = isDark.value ? TILE_TINTS_DARK : TILE_TINTS_LIGHT;
  if (key === defaultId.value) {
    return tints[0];
  }
  let hash = 0;
  for (const ch of key) {
    hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  }
  return tints[hash % tints.length];
};
const tileTint = (key: string) => {
  const [bg, ink] = tintPair(key);
  return { background: bg, color: ink };
};
// 账号头像同规则：按用户 id 稳定取浅色相（有自定义头像则用图片）
const avatarTint = computed(() => tileTint(`user:${userStore.userId}`));

// 搜索（单位 ≥5 才显示输入框）：按单位名过滤可切列表，当前单位始终在顶部不受过滤影响
const keyword = ref('');
const filteredOptions = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) {
    return unitOptions.value;
  }
  return unitOptions.value.filter((item) => (item.companyName || '').toLowerCase().includes(kw));
});

// 面板开合：打开时静默刷新列表（60s 节流，单位管理页增删回来自动可见）并把焦点送进搜索框；
// 收起时清空搜索词，下次打开回到全量列表
const panelOpen = ref(false);
const searchRef = ref<HTMLInputElement | null>(null);
const onVisible = (visible: boolean) => {
  panelOpen.value = visible;
  if (visible) {
    if (canSwitch.value) {
      refresh();
    }
    if (tenants.value.length >= 5) {
      nextTick(() => searchRef.value?.focus());
    }
    return;
  }
  keyword.value = '';
};

const onCommand = (command: string | number | object) => {
  if (command === '__manage') {
    router.push('/system/tenant');
    return;
  }
  if (command === '__retry') {
    load(true);
    return;
  }
  if (command === 'profile') {
    goProfile();
    return;
  }
  if (command === 'layout') {
    emit('set-layout');
    return;
  }
  if (command === 'feedback') {
    emit('feedback');
    return;
  }
  if (command === 'logout') {
    void logout();
    return;
  }
  switchTo(String(command));
};

// canSwitch 要等租户列表回来才知道，load 内部自判超管，这里无条件触发
onMounted(load);
</script>

<style lang="scss" scoped>
.sidebar-user {
  flex: none;
  display: flex;
  padding: 8px 0;
  /* 挂在侧栏底色上，按侧栏文字令牌派生柔线（2026-10-27 P1 拍板A）：
     亮档不再刺眼、暗档重新可见，四皮肤自动翻转 */
  border-top: 1px solid color-mix(in srgb, var(--app-side-text) 18%, transparent);
  box-sizing: border-box;
}

.sidebar-user :deep(.el-dropdown) {
  width: 100%;
}

/* 单行触发：与菜单行同一套几何（药丸 8 / 左右 inset 6），头像 + 名字（单位进小字）+ 副行角色 */
.sidebar-user__row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 52px;
  margin: 0 var(--app-nav-inset);
  padding: 0 12px;
  border: 0;
  border-radius: var(--app-nav-radius);
  background: transparent;
  /* 行坐在侧栏底色上，文字/悬停一律走侧栏令牌族（同 BrandRow.vue）：
     T1 浅档侧栏是深蓝 #1e2a38，误用内容区 --app-text(#14202d) 会近乎隐形（实测 1.13:1） */
  color: var(--app-side-text);
  font-family: inherit;
  text-align: left;
  cursor: pointer;

  &:hover {
    background: var(--app-side-hover);
  }

  &:focus-visible {
    outline: 2px solid var(--app-accent);
    outline-offset: -2px;
  }
}

.sidebar-user__avatar {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: var(--app-control-radius);
  object-fit: cover;
  background: var(--app-raised);
}

.sidebar-user__avatar--text {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12.5px;
  font-weight: 600;
}

.sidebar-user__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  margin-right: auto;
  line-height: 1.25;
}

.sidebar-user__name {
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 主行内的单位名：小一档灰字，超管随时知道自己在哪 */
.sidebar-user__name-unit {
  font-weight: 400;
  font-size: 12px;
  color: var(--app-faint);
}

.sidebar-user__meta {
  overflow: hidden;
  color: var(--app-muted);
  font-size: 12px;
  font-weight: 400;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-user__chev {
  flex: none;
  color: var(--app-faint);
  font-size: 12px;
}

.sidebar-user.is-collapse {
  padding: 8px 0;

  .sidebar-user__row {
    justify-content: center;
    padding: 0;
  }

  .sidebar-user__text,
  .sidebar-user__chev {
    display: none;
  }
}
</style>

<style lang="scss">
/* 面板 teleport 到 body，scoped 打不到：全局类都带 sidebar-user__ 前缀，只影响本组件 */

/* 面板本体（popper 根节点）：宽度=侧栏-16 永不出界，扁平白面 + 1px 细边，
   弹层是真正浮起的东西、允许浅影（app-screens 法则） */
.sidebar-user-panel-popper.el-popper {
  width: calc(var(--app-sidebar-w, 256px) - 16px);
  max-width: calc(100vw - 24px);
  max-height: min(440px, 70vh);
  overflow-y: auto;
  padding: 8px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-card-radius);
  background: var(--app-surface);
  box-shadow: 0 12px 32px rgba(15, 30, 40, 0.14);
}

/* U-11：面板底/边/分块底色全走令牌（--app-border/--app-surface/--app-raised 随主题翻转），
   暗色不再整条覆盖——底座调档一处生效；仅阴影按暗色习惯加深保留 */
html.dark .sidebar-user-panel-popper.el-popper {
  box-shadow: 0 14px 36px rgba(0, 0, 0, 0.7);
}

/* 段标题（单位 / 账号）：小字灰，不吃点击 */
.el-dropdown-menu .sidebar-user__sec {
  padding: 8px 10px 4px;
  color: var(--app-faint);
  font-size: 11.5px;
  line-height: 1;
  cursor: default;
  list-style: none;
}

/* 段间细线：1px rule，一段一件事（--app-rule 已随主题翻转，无暗色覆盖——U-10） */
.el-dropdown-menu .sidebar-user__hr {
  height: 1px;
  margin: 6px 8px;
  background: var(--app-rule);
  list-style: none;
}

/* 加载/提示行 */
.el-dropdown-menu .sidebar-user__hint {
  padding: 0;
  list-style: none;

  .el-dropdown-menu__item {
    color: var(--app-faint);
    font-size: 12.5px;
  }
}

.el-dropdown-menu .sidebar-user__retry {
  color: var(--app-muted);

  &:hover,
  &:not(.is-disabled):focus-visible {
    background: var(--app-raised);
    color: var(--app-accent);
  }
}

/* 身份色块（浅色相对）：pale 底 + 深字首字，行内 24px */
.el-dropdown-menu .sidebar-user__tile {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex: none;
  border-radius: var(--app-control-radius);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
}

/* 搜索行（单位 ≥5 才渲染）：内嵌原生输入框，点它不触发菜单项 */
.el-dropdown-menu .sidebar-user__search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 2px 4px 8px;
  padding: 0 10px;
  height: 36px;
  border-radius: var(--app-control-radius);
  background: var(--app-raised);
  cursor: default;
  list-style: none;
}

.el-dropdown-menu .sidebar-user__search-ic {
  flex: none;
  color: var(--app-faint);
  font-size: 14px;
}

.el-dropdown-menu .sidebar-user__search-input {
  min-width: 0;
  flex: 1 1 auto;
  border: 0;
  background: transparent;
  color: var(--app-text);
  font-family: inherit;
  font-size: 13px;
  outline: none;

  &::placeholder {
    color: var(--app-faint);
  }
}

/* 当前单位行：raised 底锚定「你在哪」，不可点；无对勾，「默认」只是名字后的灰字 */
.el-dropdown-menu .sidebar-user__current {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 42px;
  margin: 2px 4px 4px;
  padding: 0 10px;
  border-radius: var(--app-control-radius);
  background: var(--app-raised);
  color: var(--app-text);
  font-size: 13.5px;
  font-weight: 600;
  cursor: default;
  list-style: none;
}

.el-dropdown-menu .sidebar-user__current-name {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 「默认」灰字后缀：不再做彩底小标签（对勾/彩签双标记已删） */
.el-dropdown-menu .sidebar-user__suffix {
  flex: none;
  color: var(--app-faint);
  font-size: 11.5px;
  font-weight: 400;
}

/* 可切单位行：浅色相块 + 名字 */
.el-dropdown-menu .el-dropdown-menu__item.sidebar-user__item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 42px;
  margin: 0 4px;
  padding: 0 10px;
  border-radius: var(--app-control-radius);
  font-size: 13.5px;

  &:hover,
  &:not(.is-disabled):focus-visible {
    background: var(--app-raised);
    color: var(--app-text);
  }
}

.el-dropdown-menu .sidebar-user__opt {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  font-size: 13.5px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 搜索无结果的空态行 */
.el-dropdown-menu .sidebar-user__empty {
  padding: 10px 14px;
  color: var(--app-faint);
  font-size: 12.5px;
  cursor: default;
  list-style: none;
}

/* 「单位管理」：纯文字弱入口（去齿轮去箭头），段底收尾 */
.el-dropdown-menu .el-dropdown-menu__item.sidebar-user__manage {
  height: 34px;
  margin: 2px 4px 0;
  padding: 0 10px;
  border-radius: var(--app-control-radius);
  color: var(--app-muted);
  font-size: 12.5px;

  &:hover,
  &:not(.is-disabled):focus-visible {
    background: var(--app-raised);
    color: var(--app-text);
  }
}

/* 退出登录：独立分区的警示红，与顶栏头像菜单同一口径 */
.el-dropdown-menu .sidebar-user__logout {
  color: var(--el-color-danger);

  &:hover,
  &:not(.is-disabled):focus-visible {
    background: var(--app-raised);
    color: var(--el-color-danger);
  }
}
</style>
