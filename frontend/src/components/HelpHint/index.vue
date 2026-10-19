<template>
  <!-- 点击触发的帮助 ⓘ 图标(2026-09-10 用户拍板: 问号→ⓘ 更易懂; 替代 hover tooltip 避免移动误触发) -->
  <el-popover trigger="click" placement="top" :width="width" popper-class="help-hint-pop">
    <template #reference>
      <span class="help-hint" role="button" aria-label="查看说明" @click.stop>
        <el-icon><InfoFilled /></el-icon>
      </span>
    </template>
    <div class="help-hint-content">{{ content }}</div>
  </el-popover>
</template>

<script setup lang="ts">
// InfoFilled 经 svgicon.ts 全局注册, 无需显式 import
withDefaults(defineProps<{ content: string; width?: number }>(), { width: 260 });
</script>

<style scoped>
.help-hint {
  display: inline-block;
  margin-left: 3px;
  color: var(--el-text-color-secondary);
  /* 原文字"?"为 12px, 换实心圆 ⓘ 图标后同级偏小, 提到 14px 补偿 */
  font-size: 14px;
  line-height: 1;
  vertical-align: -2px;
  cursor: pointer;
  user-select: none;
}
.help-hint:hover {
  color: var(--el-color-primary);
}
.help-hint-content {
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}
</style>
