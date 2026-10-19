<template>
  <el-dialog
    v-model="visible"
    :title="props.current ? '更换邮箱' : '绑定邮箱'"
    width="480px"
    append-to-body
    :close-on-click-modal="false"
    class="email-bind"
    @closed="resetState"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item v-if="props.current" label="当前邮箱">
        <span class="email-bind__current">{{ props.current }}</span>
      </el-form-item>
      <el-form-item label="新邮箱" prop="email">
        <el-input v-model="form.email" maxlength="50" placeholder="请输入要绑定的邮箱" />
      </el-form-item>
      <el-form-item label="验证码" prop="code">
        <div class="email-bind__code">
          <el-input v-model="form.code" maxlength="6" placeholder="邮箱收到的 6 位验证码" @keyup.enter="handleBind" />
          <el-button :disabled="countdown > 0" :loading="sending" @click="handleSendCode">
            {{ countdown > 0 ? `${countdown}s 后可重发` : '获取验证码' }}
          </el-button>
        </div>
      </el-form-item>
    </el-form>
    <p class="email-bind__hint">绑定后可在登录页「忘记密码」用该邮箱自助重置密码；验证码 5 分钟内有效。</p>
    <template #footer>
      <el-button @click="visible = false">取 消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleBind">确认绑定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 个人信息·邮箱自助绑定弹窗（批B 2026-09-26 拍板 Q2-B）
 * 流程：输新邮箱 → 发码到该邮箱（所有权验证，60 秒限发）→ 输码 → 确认绑定。
 * 邮箱已被占用/格式错误/码错误由后端校验并报错，前端原样 toast。
 */
import { bindEmail, sendEmailBindCode } from '@/api/system/user';

const props = defineProps<{ current?: string }>();
const emit = defineEmits<{ (e: 'bound'): void }>();
const visible = defineModel<boolean>({ default: false });

const formRef = ref<ElFormInstance>();
const form = reactive({ email: '', code: '' });
const sending = ref(false);
const submitting = ref(false);
const countdown = ref(0);
let timer: ReturnType<typeof setInterval> | null = null;

const rules: ElFormRules = {
  email: [
    { required: true, message: '邮箱地址不能为空', trigger: 'blur' },
    { type: 'email', message: '请输入正确的邮箱地址', trigger: ['blur', 'change'] }
  ],
  code: [
    { required: true, message: '验证码不能为空', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '请输入 6 位数字验证码', trigger: 'blur' }
  ]
};

const startCountdown = () => {
  countdown.value = 60;
  timer && clearInterval(timer);
  timer = setInterval(() => {
    countdown.value--;
    if (countdown.value <= 0 && timer) {
      clearInterval(timer);
      timer = null;
    }
  }, 1000);
};

const handleSendCode = () => {
  formRef.value?.validateField('email', async (valid: boolean) => {
    if (!valid) return;
    sending.value = true;
    try {
      const res: any = await sendEmailBindCode(form.email.trim());
      ElMessage.success(`验证码已发送至 ${res.data || form.email.trim()}`);
      form.code = '';
      startCountdown();
    } finally {
      sending.value = false;
    }
  });
};

const handleBind = () => {
  formRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    submitting.value = true;
    try {
      await bindEmail({ email: form.email.trim(), code: form.code.trim() });
      ElMessage.success('绑定成功');
      emit('bound');
      visible.value = false;
    } finally {
      submitting.value = false;
    }
  });
};

const resetState = () => {
  form.email = '';
  form.code = '';
  // 清校验态（深挖审核 Q8④）：resetState 原来只清值不清红字——输错邮箱关窗重开后，
  // 空弹窗上还挂着上一次"请输入正确的邮箱地址"，100% 复现
  formRef.value?.clearValidate();
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  countdown.value = 0;
};

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});
</script>

<style lang="scss" scoped>
.email-bind__current {
  color: #909399;
  word-break: break-all;
}
.email-bind__code {
  display: flex;
  gap: 12px;
  width: 100%;
  .el-input {
    flex: 1;
  }
  .el-button {
    flex-shrink: 0;
    min-width: 116px;
  }
}
.email-bind__hint {
  margin: 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.7;
}
</style>
