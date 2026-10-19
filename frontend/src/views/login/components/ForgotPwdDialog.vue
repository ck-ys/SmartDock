<template>
  <el-dialog
    v-model="visible"
    :title="t('login.forgotTitle')"
    width="420px"
    append-to-body
    :close-on-click-modal="false"
    class="forgot-pwd"
    @closed="resetState"
  >
    <!-- 第一步：识别账号（多家先选单位）→ 发码 -->
    <div v-if="step === 1">
      <p class="forgot-pwd__hint">{{ t('login.forgotStep1') }}</p>
      <el-input v-model="loginId" size="large" :placeholder="t('login.phoneOrAccountPlaceholder')" @keyup.enter="handleSendCode">
        <template #prefix><svg-icon icon-class="user" class="input-icon" aria-hidden="true" /></template>
      </el-input>

      <div v-if="tenantCandidates.length > 1" class="forgot-pwd__tenants" role="listbox" :aria-label="t('login.pickTenantTitle')">
        <p class="forgot-pwd__hint">{{ t('login.forgotPickTenant') }}</p>
        <button
          v-for="tnt in tenantCandidates"
          :key="tnt.tenantId"
          type="button"
          role="option"
          :aria-selected="pickedTenantId === tnt.tenantId"
          class="forgot-pwd__tenant"
          :class="{ 'is-active': pickedTenantId === tnt.tenantId }"
          @click="pickedTenantId = tnt.tenantId"
        >
          <span class="forgot-pwd__dot" aria-hidden="true" />
          <span class="forgot-pwd__name">{{ tnt.companyName }}</span>
        </button>
      </div>

      <el-button
        type="primary"
        size="large"
        class="forgot-pwd__submit"
        :loading="sending"
        :disabled="tenantCandidates.length > 1 && !pickedTenantId"
        @click="handleSendCode"
      >
        {{ t('login.forgotSendCode') }}
      </el-button>
    </div>

    <!-- 第二步：验证码 + 新密码 -->
    <el-form v-else ref="formRef" :model="form" :rules="rules" label-position="top" hide-required-asterisk @submit.prevent="handleCommit">
      <el-form-item prop="code" :label="t('login.forgotEmailCode')">
        <el-input v-model="form.code" size="large" maxlength="6" autocomplete="one-time-code" :placeholder="t('login.forgotEmailCode')">
          <template #prefix><svg-icon icon-class="validCode" class="input-icon" aria-hidden="true" /></template>
        </el-input>
      </el-form-item>
      <el-form-item prop="password" :label="t('login.forgotNewPassword')">
        <el-input
          v-model="form.password"
          type="password"
          size="large"
          autocomplete="new-password"
          show-password
          :placeholder="t('login.rule.password.required')"
        >
          <template #prefix><svg-icon icon-class="password" class="input-icon" aria-hidden="true" /></template>
        </el-input>
      </el-form-item>
      <el-form-item prop="confirmPassword" :label="t('login.forgotConfirmPassword')">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          size="large"
          autocomplete="new-password"
          show-password
          :placeholder="t('login.rule.confirmPassword.required')"
          @keyup.enter="handleCommit"
        >
          <template #prefix><svg-icon icon-class="password" class="input-icon" aria-hidden="true" /></template>
        </el-input>
      </el-form-item>
      <el-button type="primary" size="large" class="forgot-pwd__submit" :loading="submitting" native-type="submit">
        {{ t('login.forgotSubmit') }}
      </el-button>
      <el-button link class="forgot-pwd__back" @click="backToStep1">{{ t('login.forgotSendCode') }}</el-button>
    </el-form>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 忘记密码·邮箱验证码三步重置（批4 2026-09-26 拍板 8A）
 * 流程：识别账号（复用批2 listByLoginId，多家先选单位）→ 发码到绑定邮箱 → 输码+新密码重置。
 * 未绑定邮箱/邮箱服务未配置由后端明确报错，前端原样 toast。
 */
import { useI18n } from 'vue-i18n';
import { commitPwdReset, getTenantsByLoginId, sendPwdResetCode } from '@/api/login';

const emit = defineEmits<{ (e: 'reset-ok', loginId: string): void }>();
const visible = defineModel<boolean>({ default: false });

const { t } = useI18n();
const step = ref<1 | 2>(1);
const loginId = ref('');
const tenantCandidates = ref<{ tenantId: string; companyName: string }[]>([]);
const pickedTenantId = ref('');
const sending = ref(false);
const submitting = ref(false);
const formRef = ref<ElFormInstance>();
const form = reactive({ code: '', password: '', confirmPassword: '' });

const equalToPassword = (_rule: any, value: string, callback: any) => {
  callback(form.password === value ? undefined : new Error(t('login.rule.confirmPassword.equalToPassword')));
};
const rules: ElFormRules = {
  code: [{ required: true, trigger: 'blur', message: t('login.forgotEmailCode') }],
  password: [
    { required: true, trigger: 'blur', message: t('login.rule.password.required') },
    { min: 5, max: 20, message: t('login.rule.password.length', { min: 5, max: 20 }), trigger: 'blur' },
    { pattern: /^[^<>"'|\\]+$/, message: t('login.rule.password.pattern', { strings: `< > " ' \\ |` }), trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, trigger: 'blur', message: t('login.rule.confirmPassword.required') },
    { validator: equalToPassword, trigger: 'blur' }
  ]
};

const handleSendCode = async () => {
  const id = loginId.value.trim();
  if (!id) {
    ElMessage.warning(t('login.phoneOrAccountPlaceholder'));
    return;
  }
  sending.value = true;
  try {
    // 先识别（批2 同款），0 家=账号不存在；1 家直接发；多家先选单位
    const res: any = await getTenantsByLoginId(id);
    const tenants: { tenantId: string; companyName: string }[] = res.data || [];
    if (tenants.length === 0) {
      ElMessage.error(t('login.accountNotFound'));
      return;
    }
    tenantCandidates.value = tenants;
    let tenantId = pickedTenantId.value;
    if (tenants.length === 1) {
      tenantId = tenants[0].tenantId;
    } else if (!tenantId || !tenants.some((x) => x.tenantId === tenantId)) {
      pickedTenantId.value = '';
      return; // 首次多家：先渲染选择列表，用户选中后再点一次按钮
    }
    const codeRes: any = await sendPwdResetCode(tenantId, id);
    ElMessage.success(t('login.forgotCodeSent', { email: codeRes.data || '' }));
    step.value = 2;
  } finally {
    sending.value = false;
  }
};

const handleCommit = () => {
  formRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    submitting.value = true;
    try {
      await commitPwdReset({
        tenantId: tenantCandidates.value.length === 1 ? tenantCandidates.value[0].tenantId : pickedTenantId.value,
        loginId: loginId.value.trim(),
        code: form.code.trim(),
        password: form.password
      });
      ElMessage.success(t('login.forgotSuccess'));
      emit('reset-ok', loginId.value.trim());
      visible.value = false;
    } finally {
      submitting.value = false;
    }
  });
};

// 返回上一步：清掉旧验证码/密码（P2-7，glm53 终审）——旧码重发后必失效，留着必提交失败
const backToStep1 = () => {
  form.code = '';
  form.password = '';
  form.confirmPassword = '';
  step.value = 1;
};

const resetState = () => {
  step.value = 1;
  loginId.value = '';
  tenantCandidates.value = [];
  pickedTenantId.value = '';
  form.code = '';
  form.password = '';
  form.confirmPassword = '';
};
</script>

<style lang="scss" scoped>
.forgot-pwd__hint {
  margin: 0 0 14px;
  color: #344164;
  font-size: 13px;
  line-height: 1.8;
}

.forgot-pwd__tenants {
  margin-top: 14px;
}

.forgot-pwd__tenant {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 14px;
  margin-bottom: 8px;
  border: 1px solid #cdd3e1;
  border-radius: 6px;
  background: #fff;
  font: inherit;
  text-align: left;
  cursor: pointer;

  &.is-active {
    border-color: #08184f;
    background: #f0f2f7;
  }

  .forgot-pwd__dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #cdd3e1;
  }

  &.is-active .forgot-pwd__dot {
    background: #08184f;
  }

  .forgot-pwd__name {
    color: #08184f;
    font-size: 14px;
  }
}

.forgot-pwd__submit {
  width: 100%;
  margin-top: 16px;
  margin-left: 0;
}

.forgot-pwd__back {
  width: 100%;
  margin-top: 8px;
  margin-left: 0;
}
</style>
