<script setup lang="ts">
/**
 * 协议页共享壳（《用户协议》《隐私政策》两页复用，2026-09-07 合规批）。
 *
 * 存在原因（UI 深审差异点）：--login-navy 变量定义在登录页 .login-page 作用域内，
 * 协议页走 constantRoutes 顶层平铺（不进 Layout、不在 .login-page 内）拿不到该变量，
 * 故本壳根元素复制 login.vue 的变量映射块，保证与登录页同一视觉语言。
 * 页头（标题/版本/生效日期/运营主体）与页尾（返回登录）只写一次，正文走 slot。
 */
defineProps<{
  /** 协议标题，如“多任务智能转发系统用户服务协议” */
  title: string;
  /** 版本号，如 V1.0 */
  version: string;
  /** 生效日期，如 2026-09-07 */
  effectiveDate: string;
  /** 运营主体全称 */
  owner: string;
}>();
</script>

<template>
  <main class="agreement-page">
    <div class="agreement-page__main">
      <header class="agreement-page__header">
        <router-link to="/login" class="agreement-page__back">← 返回登录</router-link>
        <h1 class="agreement-page__title">{{ title }}</h1>
        <p class="agreement-page__meta">版本 {{ version }} ｜ 生效日期 {{ effectiveDate }} ｜ {{ owner }}</p>
      </header>
      <article class="agreement-page__body">
        <slot />
      </article>
      <footer class="agreement-page__footer">
        <router-link to="/login" class="agreement-page__back">返回登录</router-link>
      </footer>
    </div>
  </main>
</template>

<style lang="scss" scoped>
// 变量块复制自 login.vue（.login-page 作用域）——协议页顶层平铺拿不到，必须自带
.agreement-page {
  --login-navy: #08184f;
  --el-color-primary: var(--login-navy);
  --el-color-primary-light-3: #344474;
  --el-color-primary-light-5: #737e9c;
  --el-color-primary-light-9: #f0f2f7;
  --el-color-primary-dark-2: #06123d;
  --el-text-color-primary: var(--login-navy);
  --el-text-color-regular: #344164;
  --el-text-color-placeholder: #828ba3;
  --el-border-color: #cdd3e1;
  min-height: 100vh;
  box-sizing: border-box;
  padding: 24px 16px 48px;
  overflow-y: auto;
  background: #fff;
  color: #344164;
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
}
.agreement-page__main {
  max-width: 800px; // 阅读宽度，长文阅读舒适区
  margin: 0 auto;
  padding: 24px 16px 0;
}
.agreement-page__header {
  margin-bottom: 24px;
  text-align: center;
}
.agreement-page__title {
  margin: 0 0 8px;
  color: var(--login-navy);
  font-size: 24px;
  font-weight: 600;
  line-height: 1.4;
}
.agreement-page__meta {
  margin: 0;
  color: #77819d;
  font-size: 12px;
  line-height: 1.7;
}
.agreement-page__body {
  font-size: 15px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
  :deep(h2) {
    margin: 32px 0 12px;
    color: #344164;
    font-size: 18px;
    font-weight: 600;
    line-height: 1.5;
  }
  :deep(h3) {
    margin: 20px 0 8px;
    color: #344164;
    font-size: 15px;
    font-weight: 600;
    line-height: 1.5;
  }
  :deep(p) {
    margin: 0 0 10px;
  }
  :deep(strong) {
    color: var(--login-navy); // 与重大利害关系条款的显著提示(民法典496提示义务)配套: 加粗+深色
    font-weight: 600;
  }
  :deep(ul) {
    margin: 0 0 12px;
    padding-left: 1.6em;
  }
  :deep(li) {
    margin-bottom: 6px;
  }
  :deep(a) {
    color: var(--login-navy);
    text-decoration: underline;
    text-underline-offset: 3px;
    &:hover {
      text-decoration-thickness: 2px;
    }
    &:focus-visible {
      outline: 2px solid var(--login-navy);
      outline-offset: 2px;
    }
  }
  // 窄屏防表格溢出
  :deep(.agreement-table-wrap) {
    overflow-x: auto;
  }
}
.agreement-page__back {
  display: inline-block;
  color: var(--login-navy);
  font-size: 13px;
  text-decoration: underline;
  text-underline-offset: 3px;
  &:hover {
    text-decoration-thickness: 2px;
  }
  &:focus-visible {
    outline: 2px solid var(--login-navy);
    outline-offset: 2px;
  }
}
.agreement-page__header .agreement-page__back {
  margin-bottom: 16px;
  font-size: 13px;
}
.agreement-page__footer {
  margin-top: 40px;
  text-align: center;
}
@media (max-width: 1000px) {
  .agreement-page {
    padding: 16px 12px 32px;
  }
  .agreement-page__main {
    padding: 16px 8px 0;
  }
  .agreement-page__title {
    font-size: 20px;
  }
  .agreement-page__body {
    font-size: 14px; // 正文降一档，行高保持
  }
}
</style>
