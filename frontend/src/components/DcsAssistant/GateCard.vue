<template>
  <div class="dcs-gate">
    <div class="gcard">
      <span class="gwave"><IconSpan name="hand" :size="26" /></span>
      <b>首次使用 DCS 小助手</b>
      <p>开始前请知悉三条承诺：</p>
      <!-- GT-1：三条承诺卡=原型 .gli2/.pr 原文案（标题+副句），图标 🔒🧠🧾 按 EMO2IC 换 lock/brain/receipt；J-2 图标并入标题行防三行断裂。
           思考链路批(1008)：承诺 2/3 副句对齐真实能力——「想开随时开」实际需管理员开通；
           「每次查库都留痕」实际=会话/运行记录（运行记录页签随二站① 上线） -->
      <div class="gli2">
        <div class="pr">
          <b
            ><span class="pr-ic"><IconSpan name="lock" /></span>只查你有权限的数据</b
          >
          <span>我能看到的和你一样——你在哪个页面，我就只查那个页面的数据</span>
        </div>
        <div class="pr">
          <b
            ><span class="pr-ic"><IconSpan name="brain" /></span>长期记忆默认关闭</b
          >
          <span>跨会话记忆需本单位管理员开启；开启后每条记忆都能看来源、随时能删</span>
        </div>
        <div class="pr">
          <b
            ><span class="pr-ic"><IconSpan name="receipt" /></span>对话与操作全程有记录</b
          >
          <span>会话记录仅自己可见；管理员可在运行记录里看到何时做了什么</span>
        </div>
      </div>
      <button class="gok" @click="emit('ok')">同意并继续</button>
      <div class="gmore">
        <button class="glink" type="button" @click="help.show()">查看完整说明</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 须知门（v3.17 后移到首次发问前；批I-4 GT-1 对齐原型 showGate：
 * 三条承诺卡=标题+副句+底部「查看完整说明」链接，同意后原问题自动继续）。
 */
import IconSpan from './IconSpan.vue';
import { useHelpModal } from './helpModal';

const help = useHelpModal();
const emit = defineEmits<{ ok: [] }>();
</script>

<style lang="scss" scoped>
.dcs-gate {
  position: absolute;
  inset: 0;
  z-index: 20;
  background: rgb(30 41 54 / 30%);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.gcard {
  width: 290px;
  background: var(--el-bg-color);
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 12px;
  box-shadow: 0 10px 34px rgb(0 0 0 / 24%);
  padding: 18px;
  font-size: 13px;
  color: var(--dcs-text-regular);
  animation: dcs-gate-pop 0.2s ease both;

  .gwave {
    display: flex;
    justify-content: center;
    color: var(--el-color-primary);
  }
  b {
    display: block;
    text-align: center;
    font-size: 15px;
    color: var(--dcs-text-primary);
    margin: 6px 0 4px;
  }
  > p {
    font-size: 12px;
    color: var(--dcs-text-secondary);
    margin: 0 0 8px;
    text-align: center;
  }
  /* 三条承诺卡（原型 .gli2 浅蓝容器 + .pr 白底行卡） */
  .gli2 {
    background: var(--el-color-primary-light-9);
    border: 1px solid var(--el-color-primary-light-8);
    border-radius: 8px;
    padding: 8px 9px;
    display: flex;
    flex-direction: column;
    gap: 6px;

    .pr {
      background: var(--el-bg-color);
      border: 1px solid var(--el-color-primary-light-8);
      border-radius: 8px;
      padding: 6px 8px;

      b {
        display: flex;
        align-items: center;
        gap: 5px;
        text-align: left;
        font-size: 12.5px;
        margin: 0 0 2px;

        .pr-ic {
          color: var(--el-color-primary);
          display: inline-flex;
        }
      }
      > span:last-child {
        display: block;
        font-size: 11px;
        line-height: 1.55;
        color: var(--dcs-text-secondary);
      }
    }
  }
  .gok {
    margin-top: 10px;
    width: 100%;
    height: 32px;
    border: none;
    border-radius: 6px;
    background: var(--el-color-primary);
    color: #fff;
    font-size: 14px;
    cursor: pointer;

    &:hover {
      background: #66b1ff;
    }
  }
  .gmore {
    text-align: center;
    margin-top: 8px;

    .glink {
      border: none;
      background: none;
      padding: 0;
      font-size: 12px;
      color: var(--dcs-text-secondary);
      text-decoration: underline;
      cursor: pointer;

      &:hover {
        color: var(--el-color-primary);
      }
    }
  }
}
@keyframes dcs-gate-pop {
  from {
    opacity: 0;
    transform: scale(0.96) translateY(6px);
  }
}
@media (prefers-reduced-motion: reduce) {
  .gcard {
    animation: none;
  }
}
</style>
