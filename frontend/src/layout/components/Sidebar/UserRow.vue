<template>
  <div class="sidebar-user" :class="{ 'is-collapse': collapse }">
    <!-- 单位行（2026-10-24 二次拍板 题1案2+题2A：留左下换形态——色块工作区切换器，富浮层）：
         超管且多单位才有，左栏/混合布局下的唯一切换单位入口（顶部品牌行已退成纯品牌；
         顶栏布局本就卸载整个侧栏、无侧栏入口，属既有口径）；
         列表回来前渲染同高占位，加载失败渲染同高「重试」行，避免菜单区跳动 -->
    <el-dropdown
      v-if="canSwitch"
      trigger="click"
      placement="top-start"
      :disabled="switching"
      popper-class="sidebar-user-unit-popper"
      @command="onCommand"
      @visible-change="onVisible"
    >
      <button
        type="button"
        class="sidebar-user__unit"
        :title="`${unitName}（点击切换单位）`"
        :aria-label="`当前单位：${unitName}，点击切换单位`"
        aria-haspopup="menu"
        :aria-expanded="dropdownOpen"
      >
        <span
          class="sidebar-user__tile"
          :class="{ 'sidebar-user__tile--accent': isDefaultTenant }"
          :style="isDefaultTenant ? undefined : { background: tileColor(currentKey) }"
          aria-hidden="true"
          >{{ switching ? '…' : tileChar }}</span
        >
        <span class="sidebar-user__unit-name">{{ switching ? '切换中…' : unitName }}</span>
        <el-icon class="sidebar-user__unit-chev"><ArrowUp /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <!-- 单位 ≥5 才给搜索框：小部署不显冗余（拍板 题2A） -->
          <li v-if="tenants.length >= 5" role="presentation" class="sidebar-user__search">
            <el-icon class="sidebar-user__search-ic"><Search /></el-icon>
            <input ref="searchRef" v-model="keyword" type="text" class="sidebar-user__search-input" placeholder="搜索单位…" aria-label="搜索单位" />
          </li>
          <li role="presentation" class="sidebar-user__current">
            <span
              class="sidebar-user__tile"
              :class="{ 'sidebar-user__tile--accent': isDefaultTenant }"
              :style="isDefaultTenant ? undefined : { background: tileColor(currentKey) }"
              aria-hidden="true"
              >{{ tileChar }}</span
            >
            <span class="sidebar-user__current-name">{{ unitName }}</span>
            <span v-if="isDefaultTenant" class="sidebar-user__tag">默认</span>
            <el-icon class="sidebar-user__check"><Check /></el-icon>
          </li>
          <el-dropdown-item
            v-for="item in filteredOptions"
            :key="item.tenantId"
            :command="String(item.tenantId) === defaultId ? '__default' : String(item.tenantId)"
            class="sidebar-user__item"
            :title="item.companyName"
          >
            <span
              class="sidebar-user__tile"
              :class="{ 'sidebar-user__tile--accent': String(item.tenantId) === defaultId }"
              :style="String(item.tenantId) === defaultId ? undefined : { background: tileColor(String(item.tenantId)) }"
              aria-hidden="true"
              >{{ tileCharOf(item.companyName) }}</span
            >
            <span class="sidebar-user__opt">{{ item.companyName }}</span>
            <span v-if="String(item.tenantId) === defaultId" class="sidebar-user__tag">默认</span>
          </el-dropdown-item>
          <li v-if="!filteredOptions.length" role="presentation" class="sidebar-user__empty">没有其它匹配的单位</li>
          <el-dropdown-item divided command="__manage" class="sidebar-user__manage">
            <el-icon class="sidebar-user__manage-ic"><Setting /></el-icon>
            <span class="sidebar-user__manage-txt">单位管理</span>
            <el-icon class="sidebar-user__manage-arrow"><ArrowRight /></el-icon>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <div v-else-if="isSuperAdmin && !ready" class="sidebar-user__unit sidebar-user__unit--ghost" aria-hidden="true"></div>
    <!-- 列表加载失败：入口不能整会话消失（2026-10-24 审查 P1 回修），同高重试行兜住 -->
    <button v-else-if="isSuperAdmin && loadFailed" type="button" class="sidebar-user__unit sidebar-user__unit--fail" @click="retryLoad">
      <span class="sidebar-user__fail-txt">单位加载失败，点击重试</span>
    </button>
    <!-- 账号行：菜单里只有账号的事（单位切换在上一行） -->
    <div class="sidebar-user__menu">
      <dcs-user-menu placement="top-start" @layout="emit('set-layout')" @feedback="emit('feedback')">
        <button type="button" class="sidebar-user__row" :aria-label="`账号菜单，${nickname}`">
          <img v-if="avatar" :src="avatar" class="sidebar-user__avatar" alt="" />
          <span v-else class="sidebar-user__avatar sidebar-user__avatar--text">{{ initial }}</span>
          <span class="sidebar-user__text">
            <b class="sidebar-user__name">{{ nickname || '当前账号' }}</b>
            <span v-if="meta" class="sidebar-user__meta">{{ meta }}</span>
          </span>
          <el-icon class="sidebar-user__chev"><ArrowUp /></el-icon>
        </button>
      </dcs-user-menu>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import DcsUserMenu from '@/components/DcsUserMenu/index.vue';
import { useUserStore } from '@/store/modules/user';
import { useWorkspace } from '@/composables/useWorkspace';
import { useTenantSwitch } from '@/composables/useTenantSwitch';

defineProps<{ collapse: boolean }>();
const emit = defineEmits<{ 'set-layout': []; feedback: [] }>();

const router = useRouter();
const userStore = useUserStore();
const { tenantName } = useWorkspace();
const { tenants, canSwitch, switching, ready, loadFailed, currentTenant, currentId, isDefaultTenant, defaultId, unitOptions, isSuperAdmin, load, refresh, switchTo } =
  useTenantSwitch();

const nickname = computed(() => userStore.nickname);
const avatar = computed(() => userStore.avatar);
const initial = computed(() => (nickname.value || '?').slice(0, 1).toUpperCase());
// 角色用现有角色码翻译：超管 admin，其余统一叫「成员」，不臆造岗位名
const roleLabel = computed(() => (userStore.roles.includes('superadmin') ? '管理员' : '成员'));
// 昵称本身就叫「管理员」时不再重复角色，避免出现「管理员 / 管理员 · 演示单位」
const roleText = computed(() => (roleLabel.value === nickname.value ? '' : roleLabel.value));
// 超管的单位已在上一行常驻显示，账号行只留角色；普通成员没有单位行，副文字留角色位
//（成员的单位名缺数据源，2026-10-24 已知问题，见拍板板）
const meta = computed(() => (canSwitch.value ? roleText.value : [roleText.value, tenantName.value].filter(Boolean).join(' · ')));

// 单位行显示名：优先租户列表里的当前单位，加载前退回工作区名称，不编造
const unitName = computed(() => currentTenant.value?.companyName || tenantName.value || '当前单位');
// 色块字取单位名首字（按码点取，emoji 不劈半；去空白；拉丁转大写与账号行一致）；空名退「单」
const tileCharOf = (name?: string) => {
  const first = [...(name || '').trim()][0];
  return first ? first.toUpperCase() : '单';
};
const tileChar = computed(() => tileCharOf(currentTenant.value?.companyName || tenantName.value));
// 当前单位色块用的键：当前在默认单位时按默认走主色，否则按租户 id 哈希取色
const currentKey = computed(() => currentId.value || defaultId.value);

// 单位色块调色板（10 色，降低多单位撞色概率）：默认单位固定主色（题2A），其余按租户 id 稳定哈希，刷新不换色
const TILE_PALETTE = ['#5b6b8c', '#8c6d5b', '#5b8c6d', '#7c5b8c', '#8c5b5b', '#3d6d8c', '#8c7d5b', '#4d8c8c', '#8c5b7c', '#5b5f8c'];
const tileColor = (key: string) => {
  let hash = 0;
  for (const ch of key) {
    hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  }
  return TILE_PALETTE[hash % TILE_PALETTE.length];
};

// 搜索（单位 ≥5 才显示输入框）：按单位名过滤可切列表，当前单位始终在顶部不受过滤影响
const keyword = ref('');
const filteredOptions = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) {
    return unitOptions.value;
  }
  return unitOptions.value.filter((item) => (item.companyName || '').toLowerCase().includes(kw));
});

// 浮层开合：打开时静默刷新列表（60s 节流，单位管理页增删回来自动可见）并把焦点送进搜索框；
// 收起时清空搜索词，下次打开回到全量列表
const dropdownOpen = ref(false);
const searchRef = ref<HTMLInputElement | null>(null);
const onVisible = (visible: boolean) => {
  dropdownOpen.value = visible;
  if (visible) {
    refresh();
    if (tenants.value.length >= 5) {
      nextTick(() => searchRef.value?.focus());
    }
    return;
  }
  keyword.value = '';
};

// 失败重试：不走 60s 节流，点了立刻再拉
const retryLoad = () => {
  load(true);
};

const onCommand = (command: string | number | object) => {
  if (command === '__manage') {
    router.push('/system/tenant');
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
  flex-direction: column;
  gap: 4px;
  padding: 8px 0;
  border-top: 1px solid var(--app-rule);
  box-sizing: border-box;
}

.sidebar-user :deep(.el-dropdown) {
  width: 100%;
}

/* 单位行：与菜单行同一套几何（38 高 / 药丸 8 / 左右 inset 6），色块工作区切换器形态 */
.sidebar-user__unit {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  margin: 0 var(--app-nav-inset);
  height: var(--app-nav-row-h);
  padding: 0 12px;
  border: 0;
  border-radius: var(--app-nav-radius);
  background: transparent;
  color: var(--app-text);
  font-family: inherit;
  text-align: left;
  cursor: pointer;

  &:hover {
    background: var(--app-raised);
  }

  &:focus-visible {
    outline: 2px solid var(--app-accent);
    outline-offset: -2px;
  }
}

/* 单位色块：首字标识，默认单位主色块（暗色自动跟随主色变体） */
.sidebar-user__tile {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex: none;
  border-radius: 7px;
  color: #ffffff;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
}

.sidebar-user__tile--accent {
  background: var(--app-accent);
}

/* 加载期同高占位：占住 38px 防跳，不可见不可点 */
.sidebar-user__unit--ghost {
  visibility: hidden;
  pointer-events: none;
}

/* 加载失败重试行：同高兜住，弱化文案 + 悬停给出可点信号 */
.sidebar-user__unit--fail {
  color: var(--app-faint);
  font-size: 12.5px;

  &:hover {
    background: var(--app-raised);
    color: var(--app-accent);
  }
}

.sidebar-user__fail-txt {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-user__unit-name {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-user__unit-chev {
  flex: none;
  margin-left: auto;
  color: var(--app-faint);
  font-size: 12px;
}

.sidebar-user__menu {
  width: 100%;
}

.sidebar-user__row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 44px;
  margin: 0 var(--app-nav-inset);
  padding: 0 12px;
  border: 0;
  border-radius: var(--app-nav-radius);
  background: transparent;
  color: var(--app-text);
  font-family: inherit;
  text-align: left;
  cursor: pointer;

  &:hover {
    background: var(--app-raised);
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
  border-radius: 10px;
  object-fit: cover;
  background: var(--app-raised);
}

.sidebar-user__avatar--text {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--app-muted);
  font-size: 12px;
  font-weight: 600;
}

.sidebar-user__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  /* 文字块占满左侧剩余空间，箭头统一靠右 */
  margin-right: auto;
  line-height: 1.25;
}

.sidebar-user__name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
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

  .sidebar-user__unit {
    justify-content: center;
    padding: 0;
  }

  .sidebar-user__unit-name,
  .sidebar-user__unit-chev {
    display: none;
  }

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
/* 浮层 teleport 到 body，scoped 打不到：全局类都带 sidebar-user__ 前缀，只影响本组件 */
/* 搜索行（单位 ≥5 才渲染）：内嵌原生输入框，点它不触发菜单项 */
.el-dropdown-menu .sidebar-user__search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 6px 6px;
  padding: 0 10px;
  height: 34px;
  border-radius: 8px;
  background: var(--app-raised);
  cursor: default;
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

/* 当前单位行：不可点，只作「你在哪」的锚；色块 + 名字 + 默认标记 + 打勾 */
.el-dropdown-menu .sidebar-user__current {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  margin: 0 4px;
  padding: 0 8px;
  border-radius: 9px;
  color: var(--app-text);
  font-size: 13.5px;
  font-weight: 600;
  cursor: default;
}

.el-dropdown-menu .sidebar-user__check {
  flex: none;
  color: var(--app-accent);
  font-size: 15px;
}

.el-dropdown-menu .sidebar-user__current-name {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 「默认」小标记：单位名右侧对齐 */
.el-dropdown-menu .sidebar-user__tag {
  flex: none;
  margin-left: auto;
  padding: 0 5px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--app-accent) 8%, transparent);
  color: var(--app-accent);
  font-size: 11px;
  font-weight: 400;
  line-height: 18px;
}

/* 暗色下提亮一档，避免深底深字（2026-10-24 视觉席 P2 回修） */
html.dark .el-dropdown-menu .sidebar-user__tag {
  background: color-mix(in srgb, var(--app-accent) 22%, transparent);
  color: color-mix(in srgb, var(--app-accent) 55%, #ffffff);
}

/* 可切单位行：色块 + 名字占左、标记靠右 */
.el-dropdown-menu .el-dropdown-menu__item.sidebar-user__item {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  margin: 0 4px;
  padding: 0 8px;
  border-radius: 9px;
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
  padding: 8px 14px;
  color: var(--app-faint);
  font-size: 12.5px;
  cursor: default;
}

/* 底部「单位管理」直达：弱化的功能入口，与切换项区分 */
.el-dropdown-menu .el-dropdown-menu__item.sidebar-user__manage {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  margin: 2px 4px 0;
  padding: 0 8px;
  border-radius: 8px;
  color: var(--app-muted);
  font-size: 12.5px;
}

.el-dropdown-menu .sidebar-user__manage-ic {
  flex: none;
  font-size: 14px;
}

.el-dropdown-menu .sidebar-user__manage-txt {
  min-width: 0;
  flex: 1 1 auto;
  text-align: left;
}

.el-dropdown-menu .sidebar-user__manage-arrow {
  flex: none;
  color: var(--app-faint);
  font-size: 12px;
}

/* 单位浮层限宽限高：单位名长/数量多时省略号与滚动才真正生效（popper 挂 body，用 popper-class 命中） */
.sidebar-user-unit-popper .el-dropdown-menu {
  max-width: min(320px, calc(100vw - 24px));
  max-height: min(360px, 60vh);
  overflow-y: auto;
}
</style>
