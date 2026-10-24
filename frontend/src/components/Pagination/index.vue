<template>
  <div :class="{ hidden: hidden }" class="pagination-container">
    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :background="background"
      :layout="layout"
      :page-sizes="pageSizes"
      :pager-count="pagerCount"
      :total="total"
      @size-change="handleSizeChange"
      @current-change="handleCurrentChange"
    />
  </div>
</template>

<script setup name="Pagination" lang="ts">
import { scrollTo } from '@/utils/scroll-to';
import { propTypes } from '@/utils/propTypes';

const props = defineProps({
  total: propTypes.number,
  page: propTypes.number.def(1),
  limit: propTypes.number.def(20),
  pageSizes: { type: Array<number>, default: () => [10, 20, 30, 50] },
  // 移动端页码按钮的数量端默认值5
  pagerCount: propTypes.number.def(document.body.clientWidth < 992 ? 5 : 7),
  layout: propTypes.string.def('total, sizes, prev, pager, next, jumper'),
  background: propTypes.bool.def(true),
  autoScroll: propTypes.bool.def(true),
  hidden: propTypes.bool.def(false),
  float: propTypes.string.def('right')
});

const emit = defineEmits(['update:page', 'update:limit', 'pagination']);
const { proxy } = getCurrentInstance() as ComponentInternalInstance;

/** 翻页回顶：纸面改成内部滚动后 document 不再滚动，要找到最近的滚动祖先；
 *  在抽屉/弹窗里分页时同样能命中（document 兜底留给无壳层场景） */
function scrollToTop() {
  let node = (proxy?.$el as HTMLElement | undefined)?.parentElement ?? null;
  while (node && node !== document.body) {
    if (/(auto|scroll)/.test(getComputedStyle(node).overflowY) && node.scrollHeight > node.clientHeight) {
      node.scrollTo({ top: 0, behavior: 'smooth' });
      return;
    }
    node = node.parentElement;
  }
  scrollTo(0, 800);
}

const currentPage = computed({
  get() {
    return props.page;
  },
  set(val) {
    emit('update:page', val);
  }
});
const pageSize = computed({
  get() {
    return props.limit;
  },
  set(val) {
    emit('update:limit', val);
  }
});
function handleSizeChange(val: number) {
  if (currentPage.value * val > props.total) {
    currentPage.value = 1;
  }
  emit('pagination', { page: currentPage.value, limit: val });
  if (props.autoScroll) {
    scrollToTop();
  }
}
function handleCurrentChange(val: number) {
  emit('pagination', { page: val, limit: pageSize.value });
  if (props.autoScroll) {
    scrollToTop();
  }
}
</script>

<style lang="scss" scoped>
.pagination-container {
  .el-pagination {
    float: v-bind(float);
  }
}
.pagination-container.hidden {
  display: none;
}
</style>
