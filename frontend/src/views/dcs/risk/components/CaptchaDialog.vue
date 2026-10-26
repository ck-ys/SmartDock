<template>
  <el-dialog
    v-model="visible"
    title="发送前安全验证"
    width="min(440px, calc(100vw - 32px))"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    :before-close="beforeClose"
    append-to-body
    @opened="focusCurrentInput"
  >
    <div class="captcha-wrap">
      <div class="level-bar">
        <span class="level-label" :class="`lvl-${displayLevel}`">{{ LEVEL_LABELS[displayLevel] || 'L?' }}</span>
        <span class="risk-hint">当前风险分 {{ totalScore }}，请完成本次发送验证</span>
      </div>

      <el-steps v-if="challengeNeedsOtp" :active="activeStep" finish-status="success" align-center class="challenge-steps">
        <el-step title="图片验证" />
        <el-step title="邮箱验证码" />
      </el-steps>

      <section v-if="stage === 'image'" class="challenge-section" aria-label="图片验证码">
        <p class="section-tip">请输入图片中的字符，不区分大小写。</p>
        <div class="img-box">
          <img v-if="captchaImage" :src="captchaImage" alt="图片验证码，点击可刷新" class="captcha-img" title="点击刷新验证码" @click="refresh" />
          <el-icon v-else class="is-loading loading-icon"><Loading /></el-icon>
        </div>
        <el-input
          ref="imageInputRef"
          v-model="answer"
          aria-label="图片验证码答案"
          placeholder="请输入图片验证码"
          maxlength="8"
          clearable
          @keyup.enter="onVerify"
        >
          <template #append>
            <el-button :icon="Refresh" aria-label="刷新图片验证码" @click="refresh" />
          </template>
        </el-input>
      </section>

      <section v-else class="challenge-section otp-section" aria-label="邮箱验证码">
        <el-alert type="warning" :closable="false" show-icon class="delivery-alert">
          <template #title>
            验证码已发送至 <strong>{{ deliveryHint || '绑定邮箱' }}</strong>
          </template>
        </el-alert>
        <p class="section-tip">请输入邮件中的 6 位验证码。验证码仅用于当前这次发送。</p>
        <el-input
          ref="otpInputRef"
          v-model="otpAnswer"
          aria-label="邮箱验证码"
          placeholder="请输入 6 位邮箱验证码"
          maxlength="6"
          inputmode="numeric"
          clearable
          @keyup.enter="onVerifyOtp"
        />
        <el-button link type="primary" class="regenerate-button" :disabled="verifying" @click="refresh">重新获取验证</el-button>
      </section>
    </div>

    <template #footer>
      <el-button :disabled="verifying || generating" @click="onCancel">取消发送</el-button>
      <el-button v-if="stage === 'image'" type="primary" :loading="verifying || generating" @click="onVerify">验证并继续</el-button>
      <el-button v-else type="primary" :loading="verifying || generating" @click="onVerifyOtp">验证并发送</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { Loading, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { generateCaptcha, verifyCaptcha, verifyOtp } from '@/api/dcs/risk';
import { LEVEL_LABELS, type CaptchaResult, type CaptchaVerificationResult } from '@/api/dcs/risk/types';
import { resolveCaptchaPassToken } from '../captchaChallengeFlow';
import { createCaptchaRequestGuard } from '../captchaRequestGuard';

interface CaptchaPassed {
  captchaPassToken: string;
}

const props = defineProps<{
  modelValue: boolean;
  level: number;
  totalScore?: number;
  scene: string;
  action: string;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
  (e: 'success', payload: CaptchaPassed): void;
  (e: 'cancel'): void;
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
});

const answer = ref('');
const otpAnswer = ref('');
const verifying = ref(false);
const generating = ref(false);
const token = ref('');
const captchaImage = ref('');
const challengeNeedsOtp = ref(false);
const deliveryHint = ref('');
const displayLevel = ref(1);
const stage = ref<'image' | 'otp'>('image');
const imageInputRef = ref<any>();
const otpInputRef = ref<any>();
const requestGuard = createCaptchaRequestGuard();

const totalScore = computed(() => props.totalScore ?? 0);
const activeStep = computed(() => (stage.value === 'otp' ? 1 : 0));
const challengeContext = computed(() => ({ scene: props.scene, action: props.action }));
const busy = computed(() => generating.value || verifying.value);

watch(
  () => [props.modelValue, props.scene, props.action] as const,
  async ([opened]) => {
    requestGuard.invalidate();
    generating.value = false;
    verifying.value = false;
    if (!opened) {
      resetInputs();
      token.value = '';
      return;
    }
    displayLevel.value = props.level;
    await refresh();
  },
  { flush: 'post' }
);

const resetInputs = () => {
  answer.value = '';
  otpAnswer.value = '';
  captchaImage.value = '';
  deliveryHint.value = '';
  challengeNeedsOtp.value = false;
  stage.value = 'image';
};

const focusCurrentInput = () => {
  nextTick(() => {
    if (stage.value === 'image') {
      imageInputRef.value?.focus?.();
    } else {
      otpInputRef.value?.focus?.();
    }
  });
};

const refresh = async () => {
  if (generating.value || verifying.value) return;
  const requestId = requestGuard.begin();
  generating.value = true;
  resetInputs();
  try {
    const response = await generateCaptcha(challengeContext.value);
    if (!requestGuard.isCurrent(requestId) || !props.modelValue) return;
    const data = response.data;
    token.value = data.token;
    displayLevel.value = data.level;
    challengeNeedsOtp.value = data.needOtp;
    deliveryHint.value = data.deliveryHint || '';
    captchaImage.value = data.imageBase64 || '';
    focusCurrentInput();
  } catch (error: any) {
    if (requestGuard.isCurrent(requestId)) {
      ElMessage.error('验证码获取失败：' + (error?.message ?? '请稍后重试'));
    }
  } finally {
    if (requestGuard.isCurrent(requestId)) {
      generating.value = false;
    }
  }
};

const finishChallenge = (result: CaptchaVerificationResult): boolean => {
  const passToken = resolveCaptchaPassToken(result);
  if (!passToken) return false;
  ElMessage.success('安全验证通过，正在继续发送');
  visible.value = false;
  emit('success', { captchaPassToken: passToken });
  return true;
};

const onVerify = async () => {
  if (!answer.value.trim()) {
    ElMessage.warning('请输入图片验证码');
    return;
  }
  const requestId = requestGuard.capture();
  verifying.value = true;
  try {
    const response = await verifyCaptcha({
      token: token.value,
      answer: answer.value,
      ...challengeContext.value
    });
    if (!requestGuard.isCurrent(requestId) || !props.modelValue) return;
    const result = response.data;
    if (!result.verified) {
      ElMessage.error('图片验证码未通过，请重试或刷新');
      answer.value = '';
      focusCurrentInput();
      return;
    }
    if (result.needOtp) {
      stage.value = 'otp';
      ElMessage.success('图片验证通过，请输入邮箱验证码');
      focusCurrentInput();
      return;
    }
    if (!finishChallenge(result)) {
      ElMessage.error('验证结果已失效，请重新获取');
      await refresh();
    }
  } finally {
    if (requestGuard.isCurrent(requestId)) {
      verifying.value = false;
    }
  }
};

const onVerifyOtp = async () => {
  if (!/^\d{6}$/.test(otpAnswer.value.trim())) {
    ElMessage.warning('请输入 6 位邮箱验证码');
    return;
  }
  const requestId = requestGuard.capture();
  verifying.value = true;
  try {
    const response = await verifyOtp({
      token: token.value,
      otpCode: otpAnswer.value.trim(),
      ...challengeContext.value
    });
    if (!requestGuard.isCurrent(requestId) || !props.modelValue) return;
    const result = response.data;
    if (!result.verified || !finishChallenge(result)) {
      ElMessage.error('邮箱验证码未通过，请重新输入');
      otpAnswer.value = '';
      focusCurrentInput();
    }
  } finally {
    if (requestGuard.isCurrent(requestId)) {
      verifying.value = false;
    }
  }
};

const cancelChallenge = () => {
  requestGuard.invalidate();
  generating.value = false;
  verifying.value = false;
  emit('cancel');
};

const onCancel = () => {
  if (busy.value) return;
  cancelChallenge();
  visible.value = false;
};

const beforeClose = (done: () => void) => {
  if (busy.value) return;
  cancelChallenge();
  done();
};
</script>

<style scoped lang="scss">
.captcha-wrap {
  padding: 0 4px;
}

.level-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border-radius: var(--app-control-radius);
}

.level-label {
  flex: none;
  padding: 3px 9px;
  border-radius: var(--dcs-radius-sm);
  color: var(--el-color-white);
  font-size: 13px;
  font-weight: 700;

  &.lvl-0 {
    background: var(--dcs-color-success);
  }

  &.lvl-1 {
    background: var(--dcs-color-info);
  }

  &.lvl-2 {
    background: var(--dcs-color-warning);
  }

  &.lvl-3 {
    background: var(--dcs-color-danger);
  }

  &.lvl-4 {
    background: var(--el-color-danger-dark-2);
  }
}

.risk-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
  text-align: right;
}

.challenge-steps {
  margin: 2px 4px 22px;
}

.challenge-section {
  margin-top: 8px;
}

.section-tip {
  margin: 0 0 10px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.img-box {
  display: flex;
  min-height: 70px;
  align-items: center;
  justify-content: center;
  margin-bottom: 12px;
  padding: 8px;
  overflow: hidden;
  border: 1px solid var(--el-border-color);
  border-radius: var(--app-control-radius);
  background: var(--dcs-bg-card);
}

.captcha-img {
  max-width: 100%;
  height: 54px;
  cursor: pointer;
  user-select: none;
}

.loading-icon {
  color: var(--el-color-primary);
  font-size: 24px;
}

.delivery-alert {
  margin-bottom: 12px;
}

.regenerate-button {
  margin-top: 8px;
  padding-left: 0;
}

@media (max-width: 480px) {
  .level-bar {
    align-items: flex-start;
    flex-direction: column;
  }

  .risk-hint {
    text-align: left;
  }
}
</style>
