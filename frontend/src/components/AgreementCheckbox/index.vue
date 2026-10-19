<script setup lang="ts">
import { useI18n } from 'vue-i18n';

/**
 * 登录/注册页《用户协议》《隐私政策》勾选门禁组件（2026-09-07 合规批）。
 *
 * 用法：父级放 <AgreementCheckbox ref="agreementRef" />，提交函数最前面调
 * agreementRef.value?.verify() —— 返回 false 时组件已自行弹 warning+抖动+聚焦，父级直接 return。
 *
 * 实施硬约束（dspro 深读裁决，见 document/plans/LOGIN_AGREEMENT_GATING_PLAN_20260907.md §4.1）：
 * 1. 勾选状态只存在于本组件内部，严禁并入父级 loginForm 对象——记住密码回填（getLoginData 整体替换对象）会丢失勾选态；
 * 2. verify() 必须同步，不得 async——动画 await 期间二次点击存在重入窗口；
 * 3. 未勾选拦截时不触碰父级 loading/验证码状态（同步 return，零副作用）。
 */
const { t } = useI18n();
const router = useRouter();

/** 勾选状态——每次进入登录页默认未勾选（合规红线：默认勾选=违规） */
const checked = ref(false);
const rootRef = ref<HTMLDivElement>();

/** 协议页真实 href：a 标签原生 target=_blank 新标签打开（键盘可 Tab+Enter 触达），不打断登录表单已填内容 */
const agreementHref = (path: string) => router.resolve(path).href;

/** 勾选校验（同步）：已勾选返回 true；未勾选弹 warning+抖动+聚焦输入框后返回 false */
const verify = (): boolean => {
  if (checked.value) return true;
  ElMessage.warning(t('login.agreementUncheckTip'));
  const root = rootRef.value;
  if (root) {
    // 重复触发抖动：先移除类再强制 reflow 重新加类，保证连续点击每次都重新晃动
    root.classList.remove('is-shake');
    void root.offsetWidth;
    root.classList.add('is-shake');
    // 聚焦 checkbox 原生 input（EP 的 focus 光晕挂在 input 上，聚焦外层 label 无效果）
    root.querySelector<HTMLInputElement>('input')?.focus();
  }
  return false;
};

defineExpose({ verify });
</script>

<template>
  <div ref="rootRef" class="agreement-checkbox">
    <el-checkbox v-model="checked" class="agreement-checkbox__box">
      <span class="agreement-checkbox__text">
        {{ t('login.agreementPrefix') }}
        <a :href="agreementHref('/agreement/user')" target="_blank" rel="noopener" class="agreement-checkbox__link" @click.stop>{{
          t('login.userAgreement')
        }}</a>
        {{ t('login.agreementConj') }}
        <a :href="agreementHref('/agreement/privacy')" target="_blank" rel="noopener" class="agreement-checkbox__link" @click.stop>{{
          t('login.privacyPolicy')
        }}</a>
      </span>
    </el-checkbox>
  </div>
</template>

<style lang="scss" scoped>
.agreement-checkbox {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start; // 英文长句换行后，第二行与首行文字对齐而非与勾选框居中
  row-gap: 2px;
  padding-right: 4px; // 与"记住我"行右侧留白节律对齐(M3 视觉审 P2-1)
  line-height: 20px;

  // 2026-09-09 业务反馈#1: EP 基础规则 .el-checkbox__label{line-height:1} 会覆盖容器行高，
  // 导致勾选框与文字基线错位、长句换行后行距压扁文字截断——这里显式还原登录面板的行高节律。
  :deep(.el-checkbox__label) {
    line-height: 20px;
    white-space: normal;
    min-width: 0;
  }
}
.agreement-checkbox__text {
  color: var(--el-text-color-regular); // 比主文案弱半档，不与“记住我”抢视觉焦点
  font-size: 13px;
}
.agreement-checkbox__link {
  color: var(--el-color-primary); // 随环境 EP 主色：登录页已映射为 navy，注册页为 EP 默认蓝（同构校验不同皮，不写死）
  font-size: 13px;
  cursor: pointer;
  text-decoration: underline;
  text-decoration-thickness: 1px;
  // 2026-09-09 C6(视觉打分m3微瑕): 下划线贴字更近; m3验收发现 margin-inline 负值会把
  // 勾选行文案挤成两行(副作用), 已回退——仅保留 underline-offset 收敛
  text-underline-offset: 2px;
  &:hover {
    text-decoration-thickness: 2px;
  }
  &:focus-visible {
    outline: 2px solid var(--el-color-primary);
    outline-offset: 2px; // 与登录页既有 focus 惯例一致
  }
}
// 未勾选提示抖动：幅度 4px/时长 400ms/业界 shake 标准曲线（克制，不过度）
@keyframes agreement-shake {
  0%,
  100% {
    transform: translateX(0);
  }
  20% {
    transform: translateX(-4px);
  }
  40% {
    transform: translateX(4px);
  }
  60% {
    transform: translateX(-3px);
  }
  80% {
    transform: translateX(2px);
  }
}
.agreement-checkbox.is-shake {
  animation: agreement-shake 0.4s cubic-bezier(0.36, 0.07, 0.19, 0.97);
}
@media (prefers-reduced-motion: reduce) {
  .agreement-checkbox.is-shake {
    animation: none;
  }
}
</style>
