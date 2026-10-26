<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import { useAppStore } from '@/store/modules/app';
import LoginScene from './login/components/LoginScene.vue';
import LoginForm from './login/components/LoginForm.vue';

const appStore = useAppStore();
const { t, locale } = useI18n();
const router = useRouter();

const handleLanguageChange = (language: string) => {
  locale.value = language;
  appStore.changeLanguage(language);
  ElMessage.success(language === 'en_US' ? 'Switch Language Successful!' : '切换语言成功！');
};

// 协议页新标签打开(2026-09-07 合规批): 原生 a+target=_blank 键盘可达, 同窗跳转回退后登录表单会重新初始化
const agreementHref = (path: string) => router.resolve(path).href;
</script>

<template>
  <main class="login-page">
    <div class="login-page__scene">
      <LoginScene />
    </div>
    <section class="login-panel" aria-labelledby="login-title">
      <div class="login-panel__toolbar">
        <el-dropdown trigger="click" @command="handleLanguageChange">
          <button type="button" class="login-language" :aria-label="t('navbar.language')">
            <svg-icon icon-class="language" aria-hidden="true" />
            <span>{{ appStore.language === 'en_US' ? 'English' : '简体中文' }}</span>
            <i-ep-arrow-down aria-hidden="true" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item :disabled="appStore.language === 'zh_CN'" command="zh_CN">简体中文</el-dropdown-item>
              <el-dropdown-item :disabled="appStore.language === 'en_US'" command="en_US">English</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <div class="login-panel__body">
        <div class="login-panel__content">
          <header class="login-panel__heading">
            <div class="login-panel__logo" aria-hidden="true">SmartDock</div>
            <h1 id="login-title" class="login-panel__title">{{ t('login.welcome') }}</h1>
          </header>
          <LoginForm />
        </div>
      </div>
      <!-- 协议入口两行结构(2026-09-07 合规批): 版权行已约满宽, 单行加链接必溢出且换行点不可控(UI 深审裁决) -->
      <footer class="login-panel__footer">
        <div>Copyright © 2024-2026 SmartDock 演示部署方 All Rights Reserved.</div>
        <nav class="login-panel__footer-links" :aria-label="t('login.legalLinks')">
          <a :href="agreementHref('/agreement/user')" target="_blank" rel="noopener">{{ t('login.userAgreement') }}</a>
          <span aria-hidden="true">｜</span>
          <a :href="agreementHref('/agreement/privacy')" target="_blank" rel="noopener">{{ t('login.privacyPolicy') }}</a>
        </nav>
      </footer>
    </section>
  </main>
</template>

<style lang="scss" scoped>
.login-page {
  /* U-24 皮肤批：登录页随肤——文字/主色吃「外观」皮肤的浅色令牌（theme-framed.scss 常亮页钉值），
     左场景的品牌字与强调点也不再固定藏青 */
  --login-navy: var(--app-text);
  --login-acc: var(--app-accent);
  --el-color-primary: var(--login-acc);
  --el-color-primary-light-3: color-mix(in srgb, var(--login-acc) 70%, #ffffff);
  --el-color-primary-light-5: color-mix(in srgb, var(--login-acc) 50%, #ffffff);
  --el-color-primary-light-9: var(--app-accent-soft);
  --el-color-primary-dark-2: color-mix(in srgb, var(--login-acc) 80%, #000000);
  --el-text-color-primary: var(--app-text);
  --el-text-color-regular: var(--app-muted);
  --el-text-color-placeholder: var(--app-faint);
  --el-border-color: var(--app-border);
  --el-fill-color-blank: #fff;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(420px, 38%);
  width: 100%;
  height: 100vh;
  height: 100svh;
  min-height: 0;
  overflow: hidden;
  background: var(--app-frame);
  color: var(--app-text);
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
}
.login-page__scene {
  display: flex;
  align-items: center;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  /* 场景底随肤：T1 深蓝（=T1 暗壳 #16202c）；T7 由 theme-framed.scss 覆写为 #101010 */
  background: var(--login-scene, #16202c);
}
.login-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
  padding: 28px 30px 20px;
  /* 皮肤批：去青/蓝装饰渐变，纸面走令牌、分界走 rule */
  background: var(--app-surface);
  border-left: 1px solid var(--app-rule);
  box-shadow: -14px 0 40px rgba(3, 10, 28, 0.12);
  &::before {
    content: '';
    position: absolute;
    inset: 0;
    background-image: radial-gradient(color-mix(in srgb, var(--app-text) 6%, transparent) 1px, transparent 1px);
    background-size: 26px 26px;
    mask-image: radial-gradient(75% 70% at 60% 45%, #000 0%, transparent 78%);
    -webkit-mask-image: radial-gradient(75% 70% at 60% 45%, #000 0%, transparent 78%);
    opacity: 0.55;
    pointer-events: none;
  }
  > * {
    position: relative;
    z-index: 1;
  }
}
.login-panel__toolbar {
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
  min-height: 32px;
  margin-bottom: 14px;
}
.login-language {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  padding: 4px 8px;
  border: 0;
  border-radius: var(--dcs-radius-sm);
  background: transparent;
  color: var(--login-navy);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
  &:hover {
    background: rgba(8, 24, 79, 0.05);
  }
  &:focus-visible {
    outline: 2px solid var(--login-navy);
    outline-offset: 3px;
  }
}
.login-panel__body {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  min-width: 0;
  min-height: 0;
}
.login-panel__content {
  width: 100%;
  max-width: 396px;
  padding: 26px 26px 22px;
  /* 皮肤批：渐变描边卡改安静实线边（U-17/口感径），底走纸面 */
  border: 1px solid var(--app-rule);
  border-radius: var(--app-control-radius);
  background: var(--app-surface);
  box-shadow:
    0 1px 2px rgba(8, 24, 79, 0.04),
    0 14px 36px rgba(8, 24, 79, 0.07);
}
.login-panel__heading {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 22px;
}
.login-panel__logo {
  display: none;
  margin-bottom: 10px;
  /* 皮肤批：去品牌渐变字，字色随皮肤（与登录场景字母标同源的纯文字品牌行） */
  color: var(--app-text);
  font-size: 28px;
  font-weight: 800;
  letter-spacing: 0.06em;
  line-height: 1.2;
  text-align: center;
  user-select: none;
}
.login-panel__title {
  margin: 0;
  color: var(--login-navy);
  font-size: 30px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: 2px;
  text-align: center;
}
.login-panel__footer {
  flex-shrink: 0;
  margin-top: 38px;
  color: #66718c;
  font-size: 11px;
  line-height: 1.7;
  text-align: center;
  overflow-wrap: anywhere;
}
.login-panel__footer-links {
  display: flex;
  justify-content: center;
  gap: 6px;
  margin-top: 2px;
  a {
    color: #66718c;
    cursor: pointer;
    text-decoration: underline;
    text-underline-offset: 2px;
    &:hover {
      color: var(--login-navy);
    }
    &:focus-visible {
      outline: 2px solid var(--login-navy);
      outline-offset: 2px;
    }
  }
}
@media (max-height: 1000px) {
  .login-panel {
    padding: 24px 28px 16px;
  }
  .login-panel__content {
    padding: 20px 22px 18px;
  }
  .login-panel__toolbar {
    margin-bottom: 18px;
  }
  .login-panel__heading {
    margin-bottom: 20px;
  }
  .login-panel__logo {
    font-size: 26px;
    margin-bottom: 14px;
  }
  .login-panel__title {
    font-size: 30px;
  }
  .login-panel__footer {
    margin-top: 24px;
  }
}
@media (max-height: 800px) {
  .login-panel {
    padding: 14px 24px 12px;
  }
  .login-panel__content {
    padding: 14px 18px 16px;
  }
  .login-panel__toolbar {
    min-height: 28px;
    margin-bottom: 8px;
  }
  .login-language {
    min-height: 28px;
  }
  .login-panel__heading {
    margin-bottom: 14px;
  }
  .login-panel__logo {
    font-size: 22px;
    margin-bottom: 8px;
  }
  .login-panel__title {
    font-size: 27px;
  }
  .login-panel__footer {
    margin-top: 16px; // M3 视觉审 P2-2: 与卡片底部间距略紧, 12→16
    font-size: 10px;
  }
}
@media (max-height: 620px) {
  // 620px 以下(横屏手机/极矮窗)卡片放不下，顶对齐+滚动兜底；
  // 768 档在验证码关闭时卡片约 377–460px，仍可居中，不再提前顶对齐
  .login-panel__body {
    align-items: flex-start;
    overflow-y: auto;
  }
  .login-panel__content {
    padding: 8px 0 12px;
  }
}
@media (max-width: 1000px) {
  // 手机端保留品牌带但压到 120–150px（原实现为 186–252px 的场景 hero）
  .login-page {
    display: flex;
    flex-direction: column;
  }
  .login-page__scene {
    display: flex;
    flex: 0 0 auto;
    height: clamp(120px, 18vh, 150px);
    height: clamp(120px, 18svh, 150px);
  }
  .login-panel {
    flex: 1 1 auto;
    height: auto;
    min-height: 0;
    padding: 14px 18px calc(12px + env(safe-area-inset-bottom));
    box-shadow: none;
  }
  .login-panel__content {
    padding: 18px 18px 16px;
  }
  // hero 占高后表单可能长于剩余空间(801-836px 高度档/全面屏首屏)——顶对齐+内部滚动兜底
  .login-panel__body {
    align-items: flex-start;
    overflow-y: auto;
  }
  .login-panel__toolbar {
    min-height: 28px;
    margin-bottom: 10px;
  }
  .login-panel__heading {
    margin-bottom: 20px;
  }
  .login-panel__logo {
    margin-bottom: 10px;
  }
}
@media (max-width: 1000px) and (max-height: 560px) {
  // 手机横屏：纵向空间不够，hero 让位给表单；整页滚动，避免页脚压住表单
  .login-page__scene {
    display: none;
  }
  .login-panel__logo {
    display: block;
    margin-bottom: 8px;
  }
  .login-panel__content {
    padding-bottom: 12px;
  }
  .login-page {
    height: auto;
    min-height: 100vh;
    min-height: 100svh;
    overflow-y: auto;
  }
  .login-panel {
    flex: 1 0 auto;
    height: auto;
    min-height: 100vh;
    min-height: 100svh;
    overflow: visible;
  }
  .login-panel__body {
    overflow: visible;
    align-items: flex-start;
  }
}
@media (max-width: 420px) {
  .login-panel {
    padding-right: 18px;
    padding-left: 18px;
  }
}
</style>
