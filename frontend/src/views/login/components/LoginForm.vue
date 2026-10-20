<script setup lang="ts">
import { getCodeImg, getTenantList, getTenantsByLoginId } from '@/api/login';
import { authRouterUrl } from '@/api/system/social/auth';
import { useUserStore } from '@/store/modules/user';
import { LoginData, TenantVO } from '@/api/types';
import { to } from 'await-to-js';
import { HttpStatus } from '@/enums/RespEnum';
import { useI18n } from 'vue-i18n';
import { Hide, View } from '@element-plus/icons-vue';
import AgreementCheckbox from '@/components/AgreementCheckbox/index.vue';
import ForgotPwdDialog from './ForgotPwdDialog.vue';

const userStore = useUserStore();
const router = useRouter();
const { t } = useI18n();

const loginForm = ref<LoginData>({
  tenantId: '000000',
  username: '',
  password: '',
  rememberMe: false,
  code: '',
  uuid: ''
} as LoginData);

const loginRules = computed<ElFormRules>(() => ({
  tenantId: [{ required: true, trigger: 'change', message: t('login.specifyTenantPlaceholder') }],
  username: [{ required: true, trigger: 'blur', message: t('login.rule.username.required') }],
  password: [{ required: true, trigger: 'blur', message: t('login.rule.password.required') }],
  code: [{ required: true, trigger: 'change', message: t('login.rule.code.required') }]
}));

const codeUrl = ref('');
const passwordVisible = ref(false);
const loading = ref(false);
// 验证码开关由原有后端接口决定。
const captchaEnabled = ref(true);
const tenantEnabled = ref(true);
const register = ref(false);
const redirect = ref('/');
const loginRef = ref<ElFormInstance>();
/** 协议勾选门禁（2026-09-07 合规批）——勾选状态在组件内部，严禁并入 loginForm（记住密码回填会整体替换对象丢失勾选态） */
const agreementRef = ref<InstanceType<typeof AgreementCheckbox>>();
const tenantList = ref<TenantVO[]>([]);
const socialSources = ['wechat', 'maxkey', 'topiam', 'gitee', 'github'] as const;
// 临时隐藏入口，保留原登录源与处理逻辑，后续恢复只需切换此展示开关。
// ⚠️ 恢复前必读（2026-09-07 评审 P1）：社交登录回跳（SocialCallback）会完全绕过协议勾选门禁——
// 恢复时必须在 doSocialLogin 与 SocialCallback 的 loginByCode 前置勾选校验，否则协议门禁失效。
const showSocialLogin = false;

// ===== 登录改造（2026-09-25 拍板 3A+ 市场主流版）：先识别后登录 =====
/** 「指定单位登录」兜底入口：显式选单位直接登录，跳过识别（单位下拉仅此模式出现） */
const specMode = ref(false);
/** 识别出多家单位时的选择弹窗（0 家报错不弹 / 1 家直接进 / N 家弹窗选） */
const pickerVisible = ref(false);
/** 批4 忘记密码弹窗（2026-09-26） */
const forgotVisible = ref(false);
const pickerTenants = ref<TenantVO[]>([]);
const pickerChoice = ref('');
const pickerLoading = ref(false);
const pickerLoginId = ref('');
/** 记住上次：{ loginId: tenantId }，多单位弹窗默认选中上次登录的单位 */
const LAST_TENANT_KEY = 'lastTenantMap';

function readLastTenantMap(): Record<string, string> {
  try {
    const raw = localStorage.getItem(LAST_TENANT_KEY);
    return raw ? (JSON.parse(raw) as Record<string, string>) : {};
  } catch {
    return {};
  }
}

function saveLastTenant(loginId: string, tenantId: string) {
  if (!loginId) return;
  const map = readLastTenantMap();
  map[loginId] = tenantId;
  localStorage.setItem(LAST_TENANT_KEY, JSON.stringify(map));
}

/** 「指定单位登录」兜底入口开关（单位选项 onMounted 已一次加载，此处只切显隐）；
 *  展开时清空 tenantId（审查 P2-3）：默认值/记住密码回填的旧单位会让 required 校验恒过，
 *  用户没选就点登录会以错误单位发起——清空让"必须显式选择"的校验真正兜底 */
/** 批4 忘记密码重置成功：账号带回登录框（手机号或账号合一输入都兼容） */
const onPwdResetOk = (resetLoginId: string) => {
  loginForm.value.username = resetLoginId;
  loginForm.value.password = '';
};

const toggleSpecMode = () => {
  specMode.value = !specMode.value;
  if (specMode.value) {
    loginForm.value.tenantId = '';
  }
};

watch(
  () => router.currentRoute.value,
  (newRoute: any) => {
    try {
      redirect.value = newRoute.query && newRoute.query.redirect && decodeURIComponent(newRoute.query.redirect);
    } catch (e) {
      // redirect 含非法 % 序列(hash 裸%等极端输入)时 decode 会抛 URIError——降级首页, 不阻断登录页挂载
      redirect.value = '/';
    }
  },
  { immediate: true }
);

const handleLogin = async () => {
  if (loading.value) return;
  // 协议勾选门禁（2026-09-07 合规批）：必须在 loading 置位【前】同步拦截——loading 置位在 try 块外，
  // 插到置位之后未勾选 return 会跳过 finally，按钮永久转圈整页卡死（dspro 深读 P0 裁决）。
  // verify() 同步返回 false 时已自行弹 warning+抖动+聚焦，此处零副作用 return（不触碰验证码状态）。
  if (!agreementRef.value?.verify()) return;
  loading.value = true;
  try {
    // 账号去空格: 输入法/复制粘贴常带入首尾空格, 输入框里肉眼难发现(2026-08-24 业务反馈)
    loginForm.value.username = String(loginForm.value.username || '').trim();
    const valid = await loginRef.value?.validate().catch(() => false);
    if (!valid) return;

    // 先识别后登录（3A+）：指定单位模式/未开多租户直接登录；否则按手机号/账号识别归属单位
    // 0 家=提示账号不存在；1 家=直接登录；N 家=弹窗选（记住上次默认选中）
    if (tenantEnabled.value && !specMode.value) {
      const [idErr, idRes] = await to(getTenantsByLoginId(loginForm.value.username));
      if (idErr) {
        // 识别接口异常：拦截器已 toast，刷新验证码防失效后放行用户重试
        if (captchaEnabled.value) await getCode();
        return;
      }
      const list = idRes?.data || [];
      if (list.length === 0) {
        ElMessage.error(t('login.accountNotFound'));
        if (captchaEnabled.value) await getCode();
        return;
      }
      if (list.length > 1) {
        pickerLoginId.value = loginForm.value.username;
        pickerTenants.value = list;
        const last = readLastTenantMap()[loginForm.value.username];
        pickerChoice.value = list.some((x) => x.tenantId === last) ? last : list[0].tenantId;
        pickerVisible.value = true;
        return;
      }
      await doLogin(list[0].tenantId);
      return;
    }
    await doLogin(loginForm.value.tenantId || '000000');
  } finally {
    loading.value = false;
  }
};

/** 弹窗确认所选单位后登录（审查 P2-4：密码错时保留弹窗只刷新验证码，用户改完密码直接再点，不必重走识别） */
const confirmPickTenant = async () => {
  if (pickerLoading.value || !pickerChoice.value) return;
  pickerLoading.value = true;
  try {
    const ok = await doLogin(pickerChoice.value, pickerLoginId.value);
    if (ok) {
      pickerVisible.value = false;
    }
  } finally {
    pickerLoading.value = false;
  }
};

/** 实际发起登录（tenantId 已由识别/指定/弹窗选定）；成功后记住上次单位；返回登录成败 */
const doLogin = async (tenantId: string, loginId = loginForm.value.username) => {
  loginForm.value.tenantId = tenantId;
  // 沿用原有记住密码语义，勾选时保存用户名和密码。
  if (loginForm.value.rememberMe) {
    localStorage.setItem('tenantId', String(loginForm.value.tenantId));
    localStorage.setItem('username', String(loginForm.value.username));
    localStorage.setItem('password', String(loginForm.value.password));
    localStorage.setItem('rememberMe', String(loginForm.value.rememberMe));
  } else {
    localStorage.removeItem('tenantId');
    localStorage.removeItem('username');
    localStorage.removeItem('password');
    localStorage.removeItem('rememberMe');
  }
  const [err] = await to(userStore.login(loginForm.value));
  if (!err) {
    saveLastTenant(loginId, tenantId);
    await router.push(redirect.value || '/');
    return true;
  }
  // 登录失败后仍刷新验证码（密码错时拦截器已 toast 原因）。
  if (captchaEnabled.value) {
    await getCode();
  }
  return false;
};

const getCode = async () => {
  const { data } = await getCodeImg();
  captchaEnabled.value = data.captchaEnabled === undefined ? false : data.captchaEnabled;
  if (captchaEnabled.value) {
    loginForm.value.code = '';
    codeUrl.value = 'data:image/gif;base64,' + data.img;
    loginForm.value.uuid = data.uuid;
  }
};

const getLoginData = () => {
  const tenantId = localStorage.getItem('tenantId');
  const username = localStorage.getItem('username');
  const password = localStorage.getItem('password');
  const rememberMe = localStorage.getItem('rememberMe');
  loginForm.value = {
    tenantId: tenantId === null ? String(loginForm.value.tenantId) : tenantId,
    username: username === null ? String(loginForm.value.username) : username,
    password: password === null ? String(loginForm.value.password) : String(password),
    rememberMe: rememberMe === null ? false : Boolean(rememberMe)
  } as LoginData;
};

const initTenantList = async () => {
  const { data } = await getTenantList(false);
  tenantEnabled.value = data.tenantEnabled === undefined ? true : data.tenantEnabled;
  if (tenantEnabled.value) {
    // 选项留给「指定单位登录」兜底入口用；默认不预选单位——识别流程自动判定（3A+ 拍板）
    tenantList.value = data.voList;
  }
};

const doSocialLogin = (type: string) => {
  authRouterUrl(type, loginForm.value.tenantId).then((res: any) => {
    if (res.code === HttpStatus.SUCCESS) {
      window.location.href = res.data;
    } else {
      ElMessage.error(res.msg);
    }
  });
};

onMounted(() => {
  getCode();
  initTenantList();
  getLoginData();
});
</script>

<template>
  <el-form
    ref="loginRef"
    :model="loginForm"
    :rules="loginRules"
    class="login-form"
    :class="{ 'login-form--captcha': captchaEnabled }"
    label-position="top"
    hide-required-asterisk
    @submit.prevent="handleLogin"
  >
    <!-- 登录改造（2026-09-25 3A+）：主界面不放单位下拉——手机号/账号+密码，点登录自动识别单位；
         「指定单位登录 »」为兜底小入口，点开才出现下拉 -->
    <el-form-item prop="username" :label="t('login.phoneOrAccount')" for="login-username">
      <el-input
        id="login-username"
        v-model="loginForm.username"
        name="username"
        type="text"
        size="large"
        autocomplete="username"
        :placeholder="t('login.phoneOrAccountPlaceholder')"
      >
        <template #prefix><svg-icon icon-class="user" class="input-icon" aria-hidden="true" /></template>
      </el-input>
    </el-form-item>
    <el-form-item prop="password" :label="t('login.password')" for="login-password">
      <el-input
        id="login-password"
        v-model="loginForm.password"
        name="password"
        :type="passwordVisible ? 'text' : 'password'"
        size="large"
        autocomplete="current-password"
        :placeholder="t('login.rule.password.required')"
      >
        <template #prefix><svg-icon icon-class="password" class="input-icon" aria-hidden="true" /></template>
        <template #suffix>
          <button
            class="login-password-toggle"
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
    <el-form-item v-if="captchaEnabled" prop="code" :label="t('login.code')" for="login-code">
      <div class="login-captcha">
        <el-input id="login-code" v-model="loginForm.code" name="code" size="large" autocomplete="off" :placeholder="t('login.code')">
          <template #prefix><svg-icon icon-class="validCode" class="input-icon" aria-hidden="true" /></template>
        </el-input>
        <button type="button" class="login-captcha__refresh" :aria-label="t('login.refreshCode')" :title="t('login.refreshCode')" @click="getCode">
          <img :src="codeUrl" class="login-captcha__image" :alt="t('login.code')" />
        </button>
      </div>
    </el-form-item>
    <div v-if="tenantEnabled" class="login-form__spec">
      <button type="button" class="login-spec-toggle" :aria-expanded="specMode" @click="toggleSpecMode">
        {{ t('login.specifyTenant') }}<span class="login-spec-arrow" :class="{ 'is-open': specMode }" aria-hidden="true">»</span>
      </button>
      <el-form-item v-if="specMode" prop="tenantId" class="login-spec-item" for="login-tenant">
        <el-select
          id="login-tenant"
          v-model="loginForm.tenantId"
          filterable
          size="large"
          :placeholder="t('login.specifyTenantPlaceholder')"
          :aria-label="t('login.specifyTenant')"
        >
          <el-option v-for="item in tenantList" :key="item.tenantId" :label="item.companyName" :value="item.tenantId" />
        </el-select>
      </el-form-item>
    </div>
    <div class="login-form__remember">
      <el-checkbox v-model="loginForm.rememberMe">{{ t('login.rememberPassword') }}</el-checkbox>
      <!-- 批4 忘记密码（2026-09-26 拍板 8A）：邮箱验证码三步重置入口 -->
      <button type="button" class="login-forgot-link" @click="forgotVisible = true">{{ t('login.forgotPassword') }}</button>
    </div>
    <AgreementCheckbox ref="agreementRef" class="login-form__agreement" />
    <div v-if="showSocialLogin" class="login-social" role="group" :aria-label="t('login.otherWays')">
      <div class="login-social__label">
        <span>{{ t('login.otherWays') }}</span>
      </div>
      <div class="login-social__buttons">
        <el-button
          v-for="source in socialSources"
          :key="source"
          native-type="button"
          :title="t(`login.social.${source}`)"
          :aria-label="t(`login.social.${source}`)"
          @click="doSocialLogin(source)"
        >
          <svg-icon :icon-class="source" aria-hidden="true" />
        </el-button>
      </div>
    </div>
    <el-button class="login-submit" :loading="loading" :disabled="loading" size="large" type="primary" native-type="submit">
      {{ loading ? t('login.logging') : t('login.login') }}
    </el-button>
    <div v-if="register" class="login-form__register">
      <router-link class="link-type" to="/register">{{ t('login.switchRegisterPage') }}</router-link>
    </div>
  </el-form>

  <!-- 多单位选择弹窗（3A+）：同手机号/账号属于多家单位时出现，默认选中上次登录的单位 -->
  <el-dialog
    v-model="pickerVisible"
    :title="t('login.pickTenantTitle')"
    width="420px"
    append-to-body
    class="tenant-picker"
    :close-on-click-modal="false"
  >
    <p class="tenant-picker__hint">{{ t('login.pickTenantHint') }}</p>
    <div class="tenant-picker__list" role="listbox" :aria-label="t('login.pickTenantTitle')">
      <button
        v-for="tnt in pickerTenants"
        :key="tnt.tenantId"
        type="button"
        role="option"
        :aria-selected="pickerChoice === tnt.tenantId"
        class="tenant-picker__item"
        :class="{ 'is-active': pickerChoice === tnt.tenantId }"
        @click="pickerChoice = tnt.tenantId"
      >
        <span class="tenant-picker__dot" aria-hidden="true" />
        <span class="tenant-picker__name">{{ tnt.companyName }}</span>
        <span class="tenant-picker__id">{{ tnt.tenantId }}</span>
      </button>
    </div>
    <template #footer>
      <el-button
        type="primary"
        size="large"
        class="tenant-picker__submit"
        :loading="pickerLoading"
        :disabled="!pickerChoice"
        @click="confirmPickTenant"
      >
        {{ t('login.login') }}
      </el-button>
    </template>
  </el-dialog>

  <!-- 批4 忘记密码弹窗（2026-09-26）：重置成功把账号带回登录框 -->
  <ForgotPwdDialog v-model="forgotVisible" @reset-ok="onPwdResetOk" />
</template>

<style lang="scss" scoped>
.login-password-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin-right: -6px;
  padding: 7px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #77819d;
  cursor: pointer;

  &:hover {
    color: var(--login-navy);
  }

  &:focus-visible {
    outline: 2px solid var(--login-navy);
    outline-offset: 1px;
  }

  svg {
    width: 18px;
    height: 18px;
  }
}

.login-form {
  width: 100%;
  :deep(.el-form-item) {
    margin-bottom: 22px;
  }
  :deep(.el-form-item__label) {
    margin-bottom: 10px;
    padding: 0;
    color: var(--login-navy);
    font-size: 14px;
    font-weight: 600;
    line-height: 20px;
  }
  :deep(.el-input__wrapper),
  :deep(.el-select__wrapper) {
    min-height: 50px;
    padding: 1px 14px;
    border-radius: 10px;
    background: #fbfcff;
    box-shadow: 0 0 0 1px #d3daea inset;
    transition:
      box-shadow 0.18s ease,
      background 0.18s ease;
  }
  :deep(.el-input__wrapper:hover),
  :deep(.el-select__wrapper:hover) {
    box-shadow: 0 0 0 1px #b9c6e4 inset;
  }
  :deep(.el-input__wrapper.is-focus),
  :deep(.el-select__wrapper.is-focused) {
    background: #fff;
    box-shadow:
      0 0 0 1.5px #2f5fd0 inset,
      0 0 0 4px rgba(47, 95, 208, 0.12);
  }
  :deep(.el-form-item.is-error .el-input__wrapper),
  :deep(.el-form-item.is-error .el-select__wrapper) {
    box-shadow: 0 0 0 1px var(--el-color-danger) inset;
  }
  :deep(.el-input__inner) {
    color: var(--login-navy);
    font-size: 14px;
  }
  :deep(.el-input__prefix-inner) {
    color: var(--login-navy);
  }
  :deep(.el-checkbox__label) {
    color: var(--login-navy);
    font-size: 14px;
  }
  :deep(.el-checkbox__inner) {
    width: 16px;
    height: 16px;
    border-radius: 3px;
  }
  // 注意: 不得给 ::after 覆盖 left/top——EP2.13.5 对勾靠 left:50%/top:50% 百分比自居中，
  // 绝对值覆盖会让 translate(-45%,-60%) 把勾平移出 16px 盒外(2026-09-17 业务反馈#4: 勾选后只见深色方块)
}
.input-icon {
  width: 17px;
  height: 17px;
}
.login-captcha {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 116px;
  gap: 12px;
  width: 100%;
}
.login-captcha__refresh {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 48px;
  padding: 0;
  overflow: hidden;
  border: 1px solid #cbd2e1;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  &:focus-visible {
    outline: 2px solid var(--login-navy);
    outline-offset: 3px;
  }
}
.login-captcha__image {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.login-form__remember {
  margin-top: -4px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 批4 忘记密码入口（2026-09-26）：右对齐次级文字链，与「指定单位登录」同族 */
.login-forgot-link {
  border: 0;
  background: transparent;
  color: #344164;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;

  &:hover {
    color: var(--login-navy);
  }

  &:focus-visible {
    outline: 2px solid var(--login-navy);
    outline-offset: 2px;
  }
}
/* 「指定单位登录」兜底小入口（登录改造 3A+ 2026-09-25）：右对齐次级文字链，展开才占行 */
.login-form__spec {
  margin-top: -4px;
  text-align: right;
}
.login-spec-toggle {
  padding: 2px 0;
  border: 0;
  background: transparent;
  color: #77819d;
  font-size: 13px;
  line-height: 20px;
  cursor: pointer;

  &:hover,
  &:focus-visible {
    color: var(--login-navy);
    outline: none;
  }
  &:focus-visible {
    text-decoration: underline;
  }
}
.login-spec-arrow {
  display: inline-block;
  margin-left: 3px;
  transition: transform 0.2s ease;

  &.is-open {
    transform: rotate(90deg);
  }
}
.login-spec-item {
  margin-top: 10px;
  // 预留校验错误行的绝对定位空间，否则「选择要登录的单位」会压到「记住我」
  margin-bottom: 18px !important;
  text-align: left;
}
/* 多单位选择弹窗（3A+）：列表行选择态=主色边框+浅蓝底+左侧色点，EP 标准强调不引杂色 */
.tenant-picker__hint {
  margin: 0 0 12px;
  color: #909399;
  font-size: 13px;
  line-height: 20px;
}
.tenant-picker__list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.tenant-picker__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.15s ease,
    background-color 0.15s ease;

  &:hover {
    border-color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
  }
  &.is-active {
    border-color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
  }
  &:focus-visible {
    outline: 2px solid var(--el-color-primary);
    outline-offset: 1px;
  }
}
.tenant-picker__dot {
  flex: none;
  width: 8px;
  height: 8px;
  border: 2px solid #c0c4cc;
  border-radius: 50%;
  transition: border-color 0.15s ease;

  .tenant-picker__item.is-active & {
    border-color: var(--el-color-primary);
    background: var(--el-color-primary);
  }
}
.tenant-picker__name {
  flex: 1;
  overflow: hidden;
  color: #303133;
  font-size: 14px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tenant-picker__id {
  color: #909399;
  font-size: 12px;
}
.tenant-picker__submit {
  width: 100%;
}
/* 协议勾选行(2026-09-07 合规批): 单独一行(同排约370px>360px容器必溢出);
   现状 remember 与按钮是 0 间距, 协议行插入后由它承担与按钮的分隔 */
.login-form__agreement {
  margin-top: 4px;
  margin-bottom: 12px;
}
.login-form--captcha .login-form__agreement {
  margin-bottom: 8px;
}
.login-social {
  margin-top: 22px;
  margin-bottom: 32px;
}
.login-social__label {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 28px;
  color: #78829d;
  font-size: 13px;
  line-height: 20px;
  &::before,
  &::after {
    flex: 1;
    height: 1px;
    background: #d0d6e3;
    content: '';
  }
}
.login-social__buttons {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 16px;
  :deep(.el-button) {
    width: 100%;
    height: 56px;
    margin: 0;
    padding: 0;
    border: 1px solid #cbd2e1;
    border-radius: 6px;
    background: #fff;
    color: #202127;
    font-size: 25px;
    &:hover,
    &:focus-visible {
      border-color: var(--login-navy);
      background: #f3f5fa;
    }
  }
}
.login-submit {
  width: 100%;
  height: 52px;
  border: 0;
  border-radius: 10px;
  background: linear-gradient(120deg, #0b1f5c, #2f5fd0);
  box-shadow: 0 10px 24px rgba(11, 31, 92, 0.24);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 2px;
  transition:
    transform 0.16s ease,
    box-shadow 0.16s ease,
    filter 0.16s ease;

  &:hover:not(.is-disabled) {
    filter: brightness(1.06);
    transform: translateY(-1px);
    box-shadow: 0 14px 28px rgba(11, 31, 92, 0.28);
  }

  &:active:not(.is-disabled) {
    transform: translateY(0);
  }
}
.login-form__register {
  margin-top: 16px;
  text-align: right;
}
.login-form--captcha {
  :deep(.el-form-item) {
    margin-bottom: 16px;
  }
  :deep(.el-form-item__label) {
    margin-bottom: 8px;
  }
  .login-social {
    margin-top: 12px;
    margin-bottom: 20px;
  }
  .login-social__label {
    margin-bottom: 16px;
  }
  .login-submit {
    height: 54px;
  }
}
@media (max-height: 800px) {
  .login-form :deep(.el-form-item) {
    margin-bottom: 10px;
  }
  /* 679px 矮屏时压缩协议行间距, 减轻 overflow-y:auto 滚动幅度(UI 深审参数) */
  .login-form__agreement {
    margin: 2px 0 8px;
  }
  .login-form :deep(.el-form-item__label) {
    margin-bottom: 6px;
  }
  .login-form :deep(.el-input__wrapper),
  .login-form :deep(.el-select__wrapper) {
    min-height: 44px;
  }
  .login-captcha__refresh {
    height: 44px;
  }
  .login-social {
    margin-top: 10px;
    margin-bottom: 16px;
  }
  .login-social__label {
    margin-bottom: 12px;
  }
  .login-social__buttons :deep(.el-button) {
    height: 44px;
  }
  .login-submit {
    height: 48px;
    font-size: 18px;
  }
}
@media (max-width: 400px) {
  .login-social__buttons {
    gap: 12px;
  }
  .login-captcha {
    grid-template-columns: minmax(0, 1fr) 102px;
    gap: 10px;
  }
}
</style>
