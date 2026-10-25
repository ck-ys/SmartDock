<template>
  <el-popover
    v-model:visible="open"
    placement="bottom-end"
    trigger="click"
    :width="236"
    popper-class="dcs-columns-popper"
  >
    <template #reference>
      <el-button text :icon="Setting">列设置</el-button>
    </template>
    <div class="dcs-columns">
      <div class="dcs-columns__head">
        <span class="dcs-columns__title">显示列</span>
        <el-button link :disabled="!hidden.length" @click="reset">恢复默认</el-button>
      </div>
      <el-checkbox-group :model-value="visibleKeys" class="dcs-columns__list" @change="onVisibleChange">
        <el-checkbox
          v-for="column in columns"
          :key="column.key"
          :value="column.key"
          :label="column.label"
          :disabled="isLastVisible(column.key)"
        />
      </el-checkbox-group>
      <p class="dcs-columns__hint">勾选需要显示的列，设置按页面保存在本机。</p>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { Setting } from '@element-plus/icons-vue';
import { writeHiddenColumns, type TableColumnOption } from './table-columns';

const props = defineProps<{
  /** 列的 key / label 列表；key 用于 v-if，label 用于展示 */
  columns: TableColumnOption[];
  /** 本页在 localStorage 里的唯一标识（不含 dcs:table-columns: 前缀） */
  storageKey: string;
}>();

/** 被隐藏的列 key；页面侧用 readHiddenColumns(storageKey) 初始化 */
const hidden = defineModel<string[]>({ default: () => [] });

const open = ref(false);

const visibleKeys = computed(() => props.columns.filter((column) => !hidden.value.includes(column.key)).map((column) => column.key));

const isLastVisible = (key: string) => visibleKeys.value.length === 1 && visibleKeys.value[0] === key;

const persist = (nextHidden: string[]) => {
  hidden.value = nextHidden;
  writeHiddenColumns(props.storageKey, nextHidden);
};

const onVisibleChange = (value: CheckboxValueType[]) => {
  const known = new Set(props.columns.map((column) => column.key));
  // 不在当前 options 里的旧隐藏项要原样带回去：维度列是异步拿到的，
  // 首屏就动列设置时若整份重算，已存的 dim:* 会被静默抹掉，刷新后自己又冒出来
  const carried = hidden.value.filter((key) => !known.has(key));
  persist([
    ...carried,
    ...props.columns.filter((column) => !value.includes(column.key)).map((column) => column.key)
  ]);
};

const reset = () => {
  persist([]);
};
</script>

<style lang="scss">
/* popover 默认 teleport 到 body，样式必须顶层化（契约 §3.4） */
.dcs-columns-popper.el-popover.el-popper {
  --el-popover-bg-color: var(--app-surface);
  --el-popover-border-color: var(--app-border);
  --el-popover-border-radius: var(--app-card-radius);
  --el-popover-padding: 0;
  box-shadow: none;
}

.dcs-columns {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  &__title {
    color: var(--app-text);
    font-size: 12px;
    font-weight: 600;
  }

  &__list {
    display: flex;
    flex-direction: column;
    gap: 6px;
    max-height: 320px;
    overflow-y: auto;
  }

  &__hint {
    margin: 2px 0 0;
    padding-top: 8px;
    border-top: 1px solid var(--app-rule);
    color: var(--app-faint);
    font-size: 11px;
    line-height: 1.5;
  }

  .el-checkbox {
    height: 22px;
    margin-right: 0;
  }

  .el-checkbox__label {
    color: var(--app-muted);
    font-size: 13px;
  }

  .el-checkbox.is-checked .el-checkbox__label {
    color: var(--app-text);
  }
}
</style>
