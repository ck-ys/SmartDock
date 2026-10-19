<template>
  <div v-if="!item.hidden">
    <template v-if="hasOneShowingChild(item, item.children) && (!onlyOneChild.children || onlyOneChild.noShowingChildren) && !item.alwaysShow">
      <app-link v-if="onlyOneChild.meta" :to="resolvePath(onlyOneChild.path, onlyOneChild.query)">
        <el-menu-item :index="resolvePath(onlyOneChild.path)" :class="{ 'submenu-title-noDropdown': !isNest }">
          <svg-icon :icon-class="onlyOneChild.meta.icon || (item.meta && item.meta.icon)" />
          <template #title>
            <span class="menu-title" :title="hasTitle(onlyOneChild.meta.title)">{{ onlyOneChild.meta.title }}</span>
            <!-- 12b 文件审核菜单待审徽标（Q7-A：仅有权限者显示，折叠态红点） -->
            <el-badge
              v-if="isFileReviewItem(onlyOneChild) && fileReviewBadge.count > 0"
              :value="fileReviewBadge.count > 99 ? '99+' : fileReviewBadge.count"
              :max="99"
              :is-dot="sidebarCollapsed"
              class="menu-review-badge"
            />
          </template>
        </el-menu-item>
      </app-link>
    </template>

    <el-sub-menu v-else ref="subMenu" :index="resolvePath(item.path)" teleported>
      <template v-if="item.meta" #title>
        <svg-icon :icon-class="item.meta ? item.meta.icon : ''" />
        <span class="menu-title" :title="hasTitle(item.meta?.title)">{{ item.meta?.title }}</span>
      </template>

      <sidebar-item
        v-for="(child, index) in item.children"
        :key="child.path + index"
        :is-nest="true"
        :item="child"
        :base-path="resolvePath(child.path)"
        class="nest-menu"
      />
    </el-sub-menu>
  </div>
</template>

<script setup lang="ts">
import { isExternal } from '@/utils/validate';
import AppLink from './Link.vue';
import { getNormalPath } from '@/utils/ruoyi';
import { RouteRecordRaw } from 'vue-router';
import type { PropType } from 'vue';
import { useFileReviewBadgeStore } from '@/store/modules/fileReviewBadge';
import { useAppStore } from '@/store/modules/app';

const props = defineProps({
  item: {
    type: Object as PropType<RouteRecordRaw>,
    required: true
  },
  isNest: {
    type: Boolean,
    default: false
  },
  basePath: {
    type: String,
    default: ''
  }
});

const onlyOneChild = ref<any>({});

// 12b 文件审核待审徽标：轮询幂等启动（store 内保证单定时器），无权限者空跑
const fileReviewBadge = useFileReviewBadgeStore();
const appStore = useAppStore();
const sidebarCollapsed = computed(() => !appStore.sidebar.opened);
// 叶子菜单经 hasOneShowingChild 时 path 被覆写为空串，须按 resolvePath 拼出完整路径判定
// （带前导斜杠防 'xfile-review' 类误报）
const isFileReviewItem = (route: any) => String(resolvePath(route?.path || '')).endsWith('/file-review');
onMounted(() => fileReviewBadge.ensureStarted());

const hasOneShowingChild = (parent: RouteRecordRaw, children?: RouteRecordRaw[]) => {
  if (!children) {
    children = [];
  }
  const showingChildren = children.filter((item) => {
    if (item.hidden) {
      return false;
    }
    onlyOneChild.value = item;
    return true;
  });

  // When there is only one child router, the child router is displayed by default
  if (showingChildren.length === 1) {
    return true;
  }

  // Show parent if there are no child router to display
  if (showingChildren.length === 0) {
    onlyOneChild.value = { ...parent, path: '', noShowingChildren: true };
    return true;
  }

  return false;
};

const resolvePath = (routePath: string, routeQuery?: string): any => {
  if (isExternal(routePath)) {
    return routePath;
  }
  if (isExternal(props.basePath as string)) {
    return props.basePath;
  }
  if (routeQuery) {
    const query = JSON.parse(routeQuery);
    return { path: getNormalPath(props.basePath + '/' + routePath), query: query };
  }
  return getNormalPath(props.basePath + '/' + routePath);
};

const hasTitle = (title: string | undefined): string => {
  if (!title || title.length <= 5) {
    return '';
  }
  return title;
};
</script>

<style scoped lang="scss">
/* 12b 文件审核待审徽标：随菜单标题内联，去掉 EP 默认右上角位移 */
.menu-review-badge {
  margin-left: 8px;

  :deep(.el-badge__content) {
    transform: none;
  }
}
</style>
