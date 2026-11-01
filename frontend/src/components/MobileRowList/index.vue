<template>
  <div v-if="loading && !list.length" class="ui-empty dcs-mobile-empty"><p class="ui-empty__title">加载中…</p></div>
  <div v-else-if="!list.length" class="ui-empty dcs-mobile-empty">
    <slot name="empty">
      <p class="ui-empty__title">{{ emptyText }}</p>
      <p v-if="emptyHint" class="ui-empty__hint">{{ emptyHint }}</p>
    </slot>
  </div>
  <div v-else class="dcs-mobile-list">
    <div v-for="(row, i) in list" :key="keyOf(row, i)" class="dcs-mobile-card">
      <div class="dcs-card-head">
        <span class="dcs-card-title"><slot name="title" :row="row" /></span>
        <span class="dcs-card-badge"><slot name="badge" :row="row" /></span>
      </div>
      <div v-if="$slots.meta" class="dcs-card-meta"><slot name="meta" :row="row" /></div>
      <div v-if="$slots.foot" class="dcs-card-foot">
        <span class="dcs-card-time"><slot name="foot" :row="row" /></span>
        <div v-if="$slots.actions" class="dcs-card-actions"><slot name="actions" :row="row" /></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts" name="MobileRowList">
/**
 * 2026-11-01 Q3=乙：手机上把宽表格换成两行列表（共用件）。
 *
 * <p>用法：表格加 class="dcs-table-only"（≤992 自动隐藏），旁边放本组件。
 * 第一行=主体 + 状态徽章，第二行=关键字段，行尾=操作。样式走 dcs-list-cards.scss。</p>
 */
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    rows: any[];
    /** 行主键字段名，默认 rowId/id 兜底下标 */
    rowKey?: string;
    /** 空态标题（表格隐藏后，表格自带的 #empty 也一起看不见了，这里补一个） */
    emptyText?: string;
    emptyHint?: string;
    /** 加载中：没有行时不显示「暂无数据」，避免首帧误导 */
    loading?: boolean;
  }>(),
  { rowKey: 'id', emptyText: '暂无数据', emptyHint: '', loading: false },
);

/** 数据源可能是 undefined（页面首次渲染时还没取到列表），一律兜成空数组，避免整页白屏 */
const list = computed<any[]>(() => (Array.isArray(props.rows) ? props.rows : []));

function keyOf(row: any, index = 0): string | number {
  if (props.rowKey && row && row[props.rowKey] != null) return row[props.rowKey];
  return row?.id ?? row?.rowId ?? index;
}
</script>
