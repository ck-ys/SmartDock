import { driver } from 'driver.js';
import 'driver.js/dist/driver.css';
import { assistantName } from './nameState';

/* ===== 批17 Q7：界面导览（driver.js 聚光灯带看）——欢迎卡「界面导览」按钮触发。
 * 抽屉与会话页共用：五幕元素在两个宿主同构（同一 Composer/MsgFlow）；
 * 元素缺席的幕自动跳过（如已有对话时欢迎卡不在场，演示模式幕略过）；
 * ESC/点遮罩/右上 × 都可退出。 */
export function startTour() {
  const steps = [
    {
      // 快刀批 Q6：选择器随称呼联动（Composer aria-label 同源 nameState，两处一起变）
      element: `[aria-label="向 ${assistantName.value} 提问"]`,
      popover: {
        title: '直接开口问',
        description: '这里就是提问框。输入 / 有命令菜单（待办、切模型、看用量），输入 @ 能引用具体任务，↓↑ 挑、Tab 补全。'
      }
    },
    {
      element: '.brow .bicon.plus-btn, .brow .bicon',
      popover: {
        title: '➕ 能力面板',
        description: '传附件（Word/Excel/PDF 让它帮你看）、选技能、引用任务，都从这进。'
      }
    },
    {
      element: '.brow .wbtn',
      popover: {
        title: '联网开关',
        description: '点一下变蓝=这轮对话可以联网查最新资料；不用时保持关闭，更快也更省。'
      }
    },
    {
      element: '.brow .ring-btn',
      popover: {
        title: '会话状态环',
        description: '环色显示这段对话占用离自动压缩线还有多远：橙=快了，红=马上自动打包前文。点开可看「已用 / 窗口 / 自动触发线」三个数。'
      }
    },
    {
      element: '.demo-row .link',
      popover: {
        title: '一键演示',
        description: '没想法时点「演示模式」，挑一幕小剧场看它自己连问连答跑完一整段工作。'
      }
    },
    {
      popover: {
        title: '导览完了',
        description: '就这么多——问就行。完整的说明随时点输入框旁的「说明」看。'
      }
    }
  ].filter((s) => !s.element || document.querySelector(s.element));
  driver({
    showProgress: true,
    nextBtnText: '下一步',
    prevBtnText: '上一步',
    doneBtnText: '完成',
    allowClose: true,
    overlayClickBehavior: 'close',
    steps
  }).drive();
}
