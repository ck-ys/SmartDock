/**
 * 使用说明弹层共享开关（PROTO PARITY 批A）。
 * 模块级单例状态：抽屉头 ❓ 与 hero「完整说明」两处入口共用一份弹层（原型 #helpModal 双入口同源）。
 */
import { ref } from 'vue';

const open = ref(false);

export function useHelpModal() {
  return {
    open,
    show: () => {
      open.value = true;
    },
    hide: () => {
      open.value = false;
    }
  };
}
