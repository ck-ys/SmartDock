<template>
  <el-dialog
    v-model="visible"
    title="命令面板"
    class="dcs-command-palette"
    width="min(560px, 92vw)"
    top="12vh"
    append-to-body
    :show-close="false"
    :close-on-click-modal="true"
    @opened="focusInput"
    @closed="resetState"
  >
    <div class="dcs-command">
      <el-input
        ref="inputRef"
        v-model="keyword"
        class="dcs-command__input"
        placeholder="搜索页面、菜单，回车直达"
        aria-label="搜索页面或菜单"
        clearable
        :prefix-icon="Search"
        @keydown.down.prevent="moveActive(1)"
        @keydown.up.prevent="moveActive(-1)"
        @keydown.enter.prevent="selectActive"
        @keydown.esc.stop="close"
      />

      <ul v-if="filteredItems.length" ref="listRef" class="dcs-command__list" role="listbox" aria-label="可跳转页面">
        <li
          v-for="(item, index) in filteredItems"
          :key="item.path"
          class="dcs-command__item"
          :class="{ 'is-active': index === activeIndex }"
          role="option"
          :aria-selected="index === activeIndex"
          @mousemove="activeIndex = index"
          @click="selectItem(item)"
        >
          <span class="dcs-command__icon" aria-hidden="true">
            <svg-icon v-if="item.icon" :icon-class="item.icon" />
            <el-icon v-else><Menu /></el-icon>
          </span>
          <span class="dcs-command__body">
            <span class="dcs-command__title">{{ item.title }}</span>
            <span v-if="item.group" class="dcs-command__group">{{ item.group }}</span>
          </span>
          <span class="dcs-command__path" aria-hidden="true">{{ item.path }}</span>
        </li>
      </ul>
      <div v-else class="dcs-command__empty">
        <p class="dcs-command__empty-title">没有匹配的页面</p>
        <p class="dcs-command__empty-hint">换个关键词试试，或按 Esc 关闭</p>
      </div>

      <div class="dcs-command__footer">
        <span>共 {{ filteredItems.length }} 个可跳转页面</span>
        <span class="dcs-command__keys"><kbd>↑</kbd><kbd>↓</kbd> 选择 · <kbd>Enter</kbd> 打开 · <kbd>Esc</kbd> 关闭</span>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter, type RouteRecordRaw } from 'vue-router';
import { Menu, Search } from '@element-plus/icons-vue';
import { usePermissionStore } from '@/store/modules/permission';
import { isExternal } from '@/utils/validate';
import { getNormalPath } from '@/utils/ruoyi';

interface CommandItem {
  path: string;
  title: string;
  group: string;
  icon?: string;
  haystack: string;
}

const router = useRouter();
const permissionStore = usePermissionStore();

const visible = ref(false);
const keyword = ref('');
const activeIndex = ref(0);
const inputRef = ref<{ focus: () => void }>();
const listRef = ref<HTMLElement>();

/** 菜单树 → 扁平可跳转项：后端下发的路由已按权限过滤，这里只做隐藏项剔除与路径拼接 */
const collectItems = (routes: RouteRecordRaw[], basePath = '', parents: string[] = []): CommandItem[] => {
  const items: CommandItem[] = [];
  routes.forEach((route) => {
    if (route.hidden) {
      return;
    }
    const rawPath = String(route.path || '');
    // 根路由的 path 可能为空字符串（仅作 Layout 容器），不能直接丢弃，否则子路由（如 /index）会一起丢
    const fullPath = isExternal(rawPath) ? rawPath : getNormalPath(rawPath.startsWith('/') ? rawPath : `${basePath}/${rawPath}`);
    const title = typeof route.meta?.title === 'string' ? route.meta.title : '';
    const icon = typeof route.meta?.icon === 'string' ? route.meta.icon : undefined;

    if (route.children?.length) {
      items.push(...collectItems(route.children, fullPath, title ? [...parents, title] : parents));
      return;
    }
    if (!title || route.redirect === 'noRedirect') {
      return;
    }
    const group = parents.join(' / ');
    items.push({
      path: fullPath,
      title,
      group,
      icon,
      haystack: `${title} ${group} ${fullPath}`.toLowerCase()
    });
  });
  return items;
};

const allItems = computed<CommandItem[]>(() => {
  const seen = new Set<string>();
  return collectItems(permissionStore.getSidebarRoutes()).filter((item) => {
    if (seen.has(item.path)) {
      return false;
    }
    seen.add(item.path);
    return true;
  });
});

const filteredItems = computed<CommandItem[]>(() => {
  const query = keyword.value.trim().toLowerCase();
  const items = query ? allItems.value.filter((item) => item.haystack.includes(query)) : allItems.value;
  return items.slice(0, 60);
});

watch(filteredItems, () => {
  activeIndex.value = 0;
});

const open = () => {
  visible.value = true;
};

const close = () => {
  visible.value = false;
};

const focusInput = () => {
  inputRef.value?.focus();
};

const resetState = () => {
  keyword.value = '';
  activeIndex.value = 0;
};

const moveActive = (step: number) => {
  const total = filteredItems.value.length;
  if (!total) {
    return;
  }
  activeIndex.value = (activeIndex.value + step + total) % total;
  nextTick(() => {
    const active = listRef.value?.children[activeIndex.value] as HTMLElement | undefined;
    active?.scrollIntoView({ block: 'nearest' });
  });
};

const selectItem = (item: CommandItem) => {
  visible.value = false;
  if (isExternal(item.path)) {
    window.open(item.path, '_blank', 'noopener');
    return;
  }
  router.push(item.path).catch(() => {
    // 重复点击当前路由时 vue-router 会 reject，这里静默处理
  });
};

const selectActive = () => {
  const item = filteredItems.value[activeIndex.value];
  if (item) {
    selectItem(item);
  }
};

const onGlobalKeydown = (event: KeyboardEvent) => {
  if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    if (visible.value) {
      close();
    } else {
      open();
    }
  }
};

// 顶栏的「搜索页面…」假输入框通过窗口事件唤起面板（与 Ctrl/Cmd+K 同一个入口）
const onExternalOpen = () => open();

onMounted(() => {
  window.addEventListener('keydown', onGlobalKeydown);
  window.addEventListener('dcs:open-palette', onExternalOpen);
});
onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown);
  window.removeEventListener('dcs:open-palette', onExternalOpen);
});
</script>

<style lang="scss">
/* append-to-body 弹窗样式必须顶层化（契约 §3.4），统一用 .dcs-command-palette 命名空间 */
.dcs-command-palette.el-dialog {
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--app-border);
  border-radius: var(--app-card-radius);
  background: var(--app-surface);
  box-shadow: none;

  .el-dialog__header {
    display: none;
  }

  .el-dialog__body {
    padding: 0;
  }
}

.dcs-command {
  display: flex;
  flex-direction: column;
  min-width: 0;

  &__input {
    padding: 12px 14px;
    border-bottom: 1px solid var(--app-rule);

    .el-input__wrapper {
      box-shadow: none;
      background: transparent;
    }
  }

  &__list {
    max-height: min(52vh, 420px);
    margin: 0;
    padding: 6px;
    overflow-y: auto;
    list-style: none;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px 10px;
    border-radius: var(--app-control-radius);
    cursor: pointer;

    &.is-active {
      background: var(--app-raised);
    }
  }

  &__icon {
    display: inline-flex;
    flex: none;
    align-items: center;
    justify-content: center;
    width: 22px;
    color: var(--app-muted);
    font-size: 16px;
  }

  &__body {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
    gap: 2px;
  }

  &__title {
    overflow: hidden;
    color: var(--app-text);
    font-size: 13px;
    font-weight: 500;
    line-height: 1.4;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__group {
    overflow: hidden;
    color: var(--app-faint);
    font-size: 12px;
    line-height: 1.3;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__path {
    flex: none;
    max-width: 40%;
    overflow: hidden;
    color: var(--app-faint);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__empty {
    padding: 28px 16px;
    text-align: center;
  }

  &__empty-title {
    margin: 0;
    color: var(--app-muted);
    font-size: 13px;
    font-weight: 500;
  }

  &__empty-hint {
    margin: 6px 0 0;
    color: var(--app-faint);
    font-size: 12px;
  }

  &__footer {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 8px 14px;
    border-top: 1px solid var(--app-rule);
    color: var(--app-faint);
    font-size: 11px;
  }

  &__keys {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }

  kbd {
    padding: 0 4px;
    border: 1px solid var(--app-rule);
    border-radius: 4px;
    background: var(--app-raised);
    color: var(--app-muted);
    font-family: inherit;
    font-size: 10px;
    line-height: 16px;
  }
}

@media (max-width: 640px) {
  .dcs-command {
    &__path {
      display: none;
    }

    &__footer {
      justify-content: center;
    }
  }
}
</style>
