<template>
  <div class="err-403">
    <div class="err-403__code">403</div>
    <h2 class="err-403__title">这个页面打不开</h2>
    <p class="err-403__desc">它可能不存在，也可能需要相应权限才能访问；如需使用，请联系本单位管理员开通。</p>
    <el-button type="primary" class="err-403__back" @click="goHome">返回首页</el-button>
  </div>
</template>

<script setup lang="ts">
/**
 * 403 说明页（批一 P0-3）：登录用户访问本人路由表里没有的动态路径时，
 * 由 permission.ts 守卫接进本页。由 Layout 包裹渲染，菜单/顶栏仍在
 * （区别于 401/404 整屏页）；因后端按权限过滤菜单、前端无法区分「无权」
 * 与「真不存在」，未匹配路径统一落本页，文案按「不存在或无权」双口径给指引
 * （取舍记录见施工计划批一节 2026-10-26 施工修订）。
 */
import { useRouter } from 'vue-router';

const router = useRouter();

function goHome() {
  router.push('/index');
}
</script>

<style lang="scss" scoped>
.err-403 {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  padding: 24px;
  text-align: center;

  &__code {
    font-size: 64px;
    font-weight: 700;
    line-height: 1;
    color: var(--el-color-primary);
  }
  &__title {
    margin: 18px 0 8px;
    font-size: 20px;
    font-weight: 600;
    color: var(--app-text);
  }
  &__desc {
    margin: 0 0 24px;
    max-width: 460px;
    font-size: 13px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
  }
  &__back {
    min-width: 110px;
    border-radius: var(--dcs-pill-radius);
  }
}
</style>
