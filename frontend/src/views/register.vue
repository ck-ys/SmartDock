<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import { useAppStore } from '@/store/modules/app';
import { getCodeImg, register, getInviteInfo } from '@/api/login';
import { RegisterForm } from '@/api/types';
import { to } from 'await-to-js';
import { View, Hide } from '@element-plus/icons-vue';
import AgreementCheckbox from '@/components/AgreementCheckbox/index.vue';
import LoginScene from './login/components/LoginScene.vue';

/**
 * 注册页重写（批3 2026-09-26 拍板 6A/7A）：本系统仅支持邀请注册——
 * 注册页只认 ?invite={code} 的有效邀请链接（锁定单位+默认角色），
 * 无码/无效码显示引导文案并禁用表单；桌面布局与登录页同构（左场景+右面板），
 * 移动端(≤1000px)注册表单更长，有意不引入登录页的 hero 带（场景仍整块隐藏）。
 */

const { t, locale } = useI18n();
const appStore = useAppStore();
const router = useRouter();
const route = useRoute();

const handleLanguageChange = (language: string) => {
  locale.value = language;
  appStore.changeLanguage(language);
  ElMessage.success(language === 'en_US' ? 'Switch Language Successful!' : '切换语言成功！');
};

const registerRef = ref<ElFormInstance>();
/** 协议勾选门禁（2026-09-07 合规批，与登录页同构） */
const agreementRef = ref<InstanceType<typeof AgreementCheckbox>>();

const registerForm = ref<RegisterForm>({
  tenantId: '',
  username: '',
  password: '',
  confirmPassword: '',
  code: '',
  uuid: '',
  userType: 'sys_user',
  phonenumber: '',
  nickName: '',
  email: '',
  inviteCode: ''
});

const passwordVisible = ref(false);

/** 邀请链接信息：null=无码或无效（不放表单，显示引导文案） */
const inviteInfo = ref<{ companyName: string; roleName: string; tenantId: string } | null>(null);
const inviteState = ref<'loading' | 'ready'>('loading');

const codeUrl = ref('');
const captchaEnabled = ref(true);
const loading = ref(false);

/** 邮箱选填：空值放行（EP pattern 对空串会报错，须自定义 validator） */
const emailOptional = (_rule: any, value: string, callback: any) => {
  if (!value || /^[\w.-]+@[\w-]+(\.[\w-]+)+$/.test(value)) {
    callback();
  } else {
    callback(new Error(t('register.rule.email.pattern')));
  }
};

const equalToPassword = (rule: any, value: string, callback: any) => {
  if (registerForm.value.password !== value) {
    callback(new Error(t('register.rule.confirmPassword.equalToPassword')));
  } else {
    callback();
  }
};

const registerRules: ElFormRules = {
  phonenumber: [
    { required: true, trigger: 'blur', message: t('register.rule.phonenumber.required') },
    { pattern: /^1[3-9]\d{9}$/, message: t('register.rule.phonenumber.pattern'), trigger: 'blur' }
  ],
  nickName: [
    { required: true, trigger: 'blur', message: t('register.rule.nickName.required') },
    { min: 1, max: 30, message: t('register.rule.nickName.length'), trigger: 'blur' }
  ],
  password: [
    { required: true, trigger: 'blur', message: t('register.rule.password.required') },
    { min: 5, max: 20, message: t('register.rule.password.length', { min: 5, max: 20 }), trigger: 'blur' },
    { pattern: /^[^<>"'|\\]+$/, message: t('register.rule.password.pattern', { strings: '< > " \' \\ |' }), trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, trigger: 'blur', message: t('register.rule.confirmPassword.required') },
    { required: true, validator: equalToPassword, trigger: 'blur' }
  ],
  email: [{ validator: emailOptional, trigger: 'blur' }],
  code: [{ required: true, trigger: 'change', message: t('register.rule.code.required') }]
};

const getCode = async () => {
  const res = await getCodeImg();
  const { data } = res;
  captchaEnabled.value = data.captchaEnabled === undefined ? true : data.captchaEnabled;
  if (captchaEnabled.value) {
    codeUrl.value = 'data:image/gif;base64,' + data.img;
    registerForm.value.uuid = data.uuid;
  }
};

const initInvite = async () => {
  const code = String(route.query.invite || '').trim();
  getCode();
  if (!code) {
    inviteState.value = 'ready';
    return;
  }
  registerForm.value.inviteCode = code;
  const [err, res] = await to(getInviteInfo(code));
  // 无效/作废/过期码后端统一报"邀请链接无效或已失效"——这里降级为引导文案，不弹全局错误
  inviteInfo.value = err ? null : { companyName: res.data.companyName, roleName: res.data.roleName, tenantId: res.data.tenantId };
  inviteState.value = 'ready';
};

const handleRegister = () => {
  // 协议勾选门禁（2026-09-07 合规批）：同步拦截在第一行（enter 直调本函数，同登录页裁决）
  if (!agreementRef.value?.verify()) return;
  // 手机号去空格并同步 username（后端 RegisterBody.username @NotBlank，邀请路径 user_name=手机号）
  registerForm.value.phonenumber = String(registerForm.value.phonenumber || '').trim();
  registerForm.value.username = registerForm.value.phonenumber;
  registerRef.value?.validate(async (valid: boolean) => {
    if (valid) {
      loading.value = true;
      const [err] = await to(register(registerForm.value));
      if (!err) {
        await ElMessageBox.alert(t('register.registerSuccess', { username: registerForm.value.username }), '系统提示', {
          type: 'success'
        });
        await router.push('/login');
      } else {
        loading.value = false;
        if (captchaEnabled.value) {
          getCode();
        }
      }
    }
  });
};

onMounted(() => {
  initInvite();
});
</script>

<template>
  <main class="register-page">
    <div class="register-page__scene">
      <LoginScene />
    </div>
    <section class="register-panel" aria-labelledby="register-title">
      <div class="register-panel__toolbar">
        <el-dropdown trigger="click" @command="handleLanguageChange">
          <button type="button" class="register-language" :aria-label="t('navbar.language')">
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
      <div class="register-panel__body">
        <div class="register-panel__content">
          <header class="register-panel__heading">
            <div class="register-panel__logo" aria-hidden="true">SmartDock</div>
            <h1 id="register-title" class="register-panel__title">{{ t('register.inviteOnly') }}</h1>
          </header>

          <!-- 加载中：校验邀请码 -->
          <div v-if="inviteState === 'loading'" class="register-invite-state" role="status">
            <el-icon class="is-loading" :size="26"><i-ep-loading /></el-icon>
          </div>

          <!-- 无码/无效码：引导文案，不放表单（后端只认有效邀请码，开关保持 false） -->
          <div v-else-if="!inviteInfo" class="register-invite-empty">
            <p class="register-invite-empty__title">{{ t('register.inviteOnlyTip') }}</p>
            <router-link class="link-type" to="/login">{{ t('register.switchLoginPage') }}</router-link>
          </div>

          <!-- 有效邀请：横幅锁定单位与角色 + 注册表单 -->
          <template v-else>
            <div class="register-invite-banner">
              <div class="register-invite-banner__main">{{ t('register.joining') }}「{{ inviteInfo.companyName }}」</div>
              <div class="register-invite-banner__sub">{{ t('register.inviteRole') }}：{{ inviteInfo.roleName }}</div>
            </div>
            <el-form
              ref="registerRef"
              :model="registerForm"
              :rules="registerRules"
              class="register-form"
              label-position="top"
              hide-required-asterisk
              @submit.prevent="handleRegister"
            >
              <el-form-item prop="phonenumber" :label="t('register.phonenumber')" for="register-phone">
                <el-input
                  id="register-phone"
                  v-model="registerForm.phonenumber"
                  name="phonenumber"
                  type="text"
                  size="large"
                  maxlength="11"
                  autocomplete="tel"
                  :placeholder="t('register.rule.phonenumber.required')"
                >
                  <template #prefix><svg-icon icon-class="user" class="input-icon" aria-hidden="true" /></template>
                </el-input>
              </el-form-item>
              <el-form-item prop="nickName" :label="t('register.nickName')" for="register-nickname">
                <el-input
                  id="register-nickname"
                  v-model="registerForm.nickName"
                  name="nickName"
                  type="text"
                  size="large"
                  maxlength="30"
                  autocomplete="name"
                  :placeholder="t('register.rule.nickName.required')"
                >
                  <template #prefix><svg-icon icon-class="user" class="input-icon" aria-hidden="true" /></template>
                </el-input>
              </el-form-item>
              <el-form-item prop="password" :label="t('register.password')" for="register-password">
                <el-input
                  id="register-password"
                  v-model="registerForm.password"
                  name="password"
                  :type="passwordVisible ? 'text' : 'password'"
                  size="large"
                  autocomplete="new-password"
                  :placeholder="t('register.rule.password.required')"
                >
                  <template #prefix><svg-icon icon-class="password" class="input-icon" aria-hidden="true" /></template>
                  <template #suffix>
                    <button
                      class="register-password-toggle"
                      type="button"
                      :aria-label="t(passwordVisible ? 'login.hidePassword' : 'login.showPassword')"
                      :aria-pressed="passwordVisible"
                      @click="passwordVisible = !passwordVisible"
                    >
                      <component :is="passwordVisible ? Hide : View" aria-hidden="true" />
                    </button>
                  </template>
                </el-input>
              </el-form-item>
              <el-form-item prop="confirmPassword" :label="t('register.confirmPassword')" for="register-confirm">
                <el-input
                  id="register-confirm"
                  v-model="registerForm.confirmPassword"
                  name="confirmPassword"
                  type="password"
                  size="large"
                  autocomplete="new-password"
                  :placeholder="t('register.rule.confirmPassword.required')"
                >
                  <template #prefix><svg-icon icon-class="password" class="input-icon" aria-hidden="true" /></template>
                </el-input>
              </el-form-item>
              <el-form-item prop="email" :label="t('register.email')" for="register-email">
                <el-input
                  id="register-email"
                  v-model="registerForm.email"
                  name="email"
                  type="text"
                  size="large"
                  maxlength="50"
                  autocomplete="email"
                  :placeholder="t('register.email')"
                >
                  <template #prefix><svg-icon icon-class="message" class="input-icon" aria-hidden="true" /></template>
                </el-input>
              </el-form-item>
              <el-form-item v-if="captchaEnabled" prop="code" :label="t('register.code')" for="register-code">
                <div class="register-captcha">
                  <el-input
                    id="register-code"
                    v-model="registerForm.code"
                    name="code"
                    size="large"
                    autocomplete="off"
                    :placeholder="t('register.code')"
                  >
                    <template #prefix><svg-icon icon-class="validCode" class="input-icon" aria-hidden="true" /></template>
                  </el-input>
                  <button
                    type="button"
                    class="register-captcha__refresh"
                    :aria-label="t('login.refreshCode')"
                    :title="t('login.refreshCode')"
                    @click="getCode"
                  >
                    <img :src="codeUrl" class="register-captcha__image" :alt="t('register.code')" />
                  </button>
                </div>
              </el-form-item>
              <AgreementCheckbox ref="agreementRef" class="register-form__agreement" />
              <el-button class="register-submit" :loading="loading" :disabled="loading" size="large" type="primary" native-type="submit">
                <span v-if="!loading">{{ t('register.register') }}</span>
                <span v-else>{{ t('register.registering') }}</span>
              </el-button>
              <div class="register-form__login">
                <router-link class="link-type" to="/login">{{ t('register.switchLoginPage') }}</router-link>
              </div>
            </el-form>
          </template>
        </div>
      </div>
      <footer class="register-panel__footer">
        <div>Copyright © 2024-2026 SmartDock 演示部署方 All Rights Reserved.</div>
      </footer>
    </section>
  </main>
</template>

<style lang="scss" scoped>
/* 布局与登录页同构（批3 2026-09-26）：左场景+右面板、navy 色板、矮窗滚动兜底断点；
   移动端差异见文件头注释（注册页不引入 hero） */
.register-page {
  --register-navy: #08184f;
  --el-color-primary: var(--register-navy);
  --el-color-primary-light-3: #344474;
  --el-color-primary-light-5: #737e9c;
  --el-color-primary-light-9: #f0f2f7;
  --el-color-primary-dark-2: #06123d;
  --el-text-color-primary: var(--register-navy);
  --el-text-color-regular: #344164;
  --el-text-color-placeholder: #828ba3;
  --el-border-color: #cdd3e1;
  --el-fill-color-blank: #fff;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(390px, 31.3%);
  width: 100%;
  height: 100vh;
  height: 100svh;
  min-height: 0;
  overflow: hidden;
  background: #fff;
  color: var(--register-navy);
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
}
.register-page__scene {
  display: flex;
  align-items: center;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #061126;
}
.register-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
  padding: 30px 32px 20px;
  background: #fff;
}
.register-panel__toolbar {
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
  min-height: 32px;
  margin-bottom: 20px;
}
.register-language {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  padding: 4px 8px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--register-navy);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
  &:focus-visible {
    outline: 2px solid var(--register-navy);
    outline-offset: 3px;
  }
}
.register-panel__body {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  min-width: 0;
  min-height: 0;
}
.register-panel__content {
  width: 360px;
  max-width: 100%;
}
.register-panel__heading {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 20px;
}
.register-panel__logo {
  margin-bottom: 12px;
  color: var(--register-navy);
  font-size: 26px;
  font-weight: 800;
  letter-spacing: 0.06em;
  line-height: 1.2;
  text-align: center;
  user-select: none;
}
.register-panel__title {
  margin: 0;
  color: var(--register-navy);
  font-size: 26px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: 2px;
  text-align: center;
}
.register-panel__footer {
  flex-shrink: 0;
  margin-top: 20px;
  color: #77819d;
  font-size: 11px;
  line-height: 1.7;
  text-align: center;
  overflow-wrap: anywhere;
}

/* 邀请码三态 */
.register-invite-state {
  display: flex;
  justify-content: center;
  padding: 40px 0;
  color: #737e9c;
}
.register-invite-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  padding: 36px 12px;
  text-align: center;

  .register-invite-empty__title {
    margin: 0;
    color: #344164;
    font-size: 14px;
    line-height: 1.9;
  }

  .link-type {
    color: var(--register-navy);
    text-decoration: underline;
    text-underline-offset: 3px;
  }
}
.register-invite-banner {
  padding: 12px 16px;
  margin-bottom: 16px;
  border-left: 3px solid var(--register-navy);
  border-radius: 6px;
  background: var(--el-color-primary-light-9);

  .register-invite-banner__main {
    color: var(--register-navy);
    font-size: 15px;
    font-weight: 600;
  }

  .register-invite-banner__sub {
    margin-top: 4px;
    color: #344164;
    font-size: 13px;
  }
}

/* 表单区（对齐 LoginForm 视觉） */
.register-form {
  :deep(.el-form-item) {
    margin-bottom: 16px;
  }
  :deep(.el-form-item__label) {
    color: var(--register-navy);
    font-weight: 500;
  }
}
.register-password-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #828ba3;
  cursor: pointer;

  &:hover {
    color: var(--register-navy);
  }
  &:focus-visible {
    outline: 2px solid var(--register-navy);
    outline-offset: 1px;
  }
}
.register-captcha {
  display: flex;
  gap: 10px;
  width: 100%;

  .register-captcha__refresh {
    flex-shrink: 0;
    padding: 0;
    border: 0;
    background: transparent;
    cursor: pointer;

    .register-captcha__image {
      display: block;
      width: 108px;
      height: 40px;
      border-radius: 4px;
    }
  }
}
.register-submit {
  width: 100%;
  margin-top: 4px;
  letter-spacing: 6px;
}
.register-form__login {
  margin-top: 14px;
  text-align: right;

  .link-type {
    color: #344164;
    &:hover {
      color: var(--register-navy);
    }
  }
}
.register-form__agreement {
  margin-bottom: 4px;
}

@media (max-height: 900px) {
  .register-panel {
    padding: 20px 28px 14px;
  }
  .register-panel__heading {
    margin-bottom: 14px;
  }
  .register-panel__logo {
    font-size: 22px;
    margin-bottom: 10px;
  }
  .register-panel__title {
    font-size: 22px;
  }
  .register-form {
    :deep(.el-form-item) {
      margin-bottom: 12px;
    }
  }
}
@media (max-height: 799px) {
  /* 矮窗（如 1366×768）表单项多放不下——滚动兜底（登录页同款断点裁决 2026-09-17） */
  .register-panel__body {
    align-items: flex-start;
    overflow-y: auto;
  }
  .register-panel__content {
    padding: 8px 0 12px;
  }
}
@media (max-width: 1000px) {
  .register-page {
    display: block;
  }
  .register-page__scene {
    display: none;
  }
  .register-panel {
    height: 100vh;
    height: 100svh;
    min-height: 0;
    padding-right: 24px;
    padding-left: 24px;
  }
}
</style>
