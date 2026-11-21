<template>
  <el-card shadow="never" class="access-guide">
    <div class="access-guide__title">{{ variant === 'sharpen' ? '数据已经在进来了——想让画像更准，接更多业务操作' : '把你的业务数据接进画像（三步）' }}</div>
    <ol class="access-guide__steps">
      <li v-if="variant === 'sharpen'">
        <b>① 现在记录的只有登录与站内核心操作</b>
        <span class="muted">——把你的业务系统也接进来，同款几行代码，事件口径见文档「给你的系统接画像」</span>
      </li>
      <li v-else>
        <b>① 在你的业务代码里发事件</b>
        <span class="muted">——和内置任务模块同款：系统里每次发布 / 提交 / 审核任务，就是这么几行发出去的</span>
      </li>
      <li>
        <div class="access-guide__code">
          <pre>{{ CODE_SAMPLE }}</pre>
          <el-button link type="primary" class="access-guide__copy" @click="copyCode">复制</el-button>
        </div>
      </li>
      <li v-if="variant === 'sharpen'">
        <b>② 换一张打分模板，或自建维度与权重</b>
        <span class="muted">——画像工作室里可换模板、调维度与权重；离线历史数据走 CSV 补录</span>
      </li>
      <li v-else>
        <b>② 先试试</b>
        <span class="muted">——去「引擎与运维 → 引擎管理 → 测试发送」粘一段 JSON 发一条，马上能在画像页看到</span>
      </li>
      <li>
        <b>{{ variant === 'sharpen' ? '③ 每接一类新事件，画像自动长出新维度' : '③ 然后什么都不用配' }}</b>
        <span class="muted">{{ variant === 'sharpen' ? '——不用迁移数据，事件账本自动归口' : '——事件自动进画像，自动建维度配置、自动算分' }}</span>
      </li>
    </ol>
    <p class="access-guide__alt muted">
      不是 Java 项目？还有两条路：CSV 离线补录、以及让智能体帮你查（见文档「给你的系统接画像」）。画像只分析真实业务操作——数据不动，页面不动。
    </p>
    <div class="access-guide__actions">
      <el-button v-if="canTestSend" type="primary" @click="goTestSend">去测试发送</el-button>
      <slot name="extra" />
    </div>
  </el-card>
</template>

<script setup lang="ts" name="ProfileAccessGuideCard">
import { checkPermi } from '@/utils/permission';

/**
 * 接入示例卡: 不做接入码/新端点/向导页, 只把门面调用摆到明面上。
 * 单源重构 M1 D 片(题4 常驻+文案分叉):
 *  - onboard 无数据 → 三步接入(怎么把数据接进来);
 *  - sharpen 有数据 → 怎么更准(接更多业务操作/换模板/补历史)。
 * 代码示例与 docs/profile-integration.md(唯一真源)同口径。
 */
const props = withDefaults(defineProps<{ variant?: 'onboard' | 'sharpen' }>(), { variant: 'onboard' });
void props; // 模板直接用 variant; 留此行表明 prop 仅模板消费

const canTestSend = checkPermi(['profile:engine:query']);
const router = useRouter();
const goTestSend = () => router.push('/insight/engine-ops/profile-ops?tab=admin&sub=seed');

const CODE_SAMPLE = [
  'ProfileEventBo e = new ProfileEventBo();',
  'e.setEventName("order.paid");   // 事件名：实体.动作',
  'e.setEventUid(uid);             // 幂等键：同一条重发不会重复计数',
  'e.setSubjectType("user");       // 主体类型（内部员工用 user）',
  'e.setSubjectId("user-123");     // 这个人的 ID',
  'profileEventPublisher.publish(e);'
].join('\n');

const copyCode = async () => {
  try {
    await navigator.clipboard.writeText(CODE_SAMPLE);
    ElMessage.success('代码已复制');
  } catch {
    // 局域网 http(非安全上下文)没有 navigator.clipboard, 退 execCommand
    try {
      const ta = document.createElement('textarea');
      ta.value = CODE_SAMPLE;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand('copy');
      ta.remove();
      if (ok) {
        ElMessage.success('代码已复制');
        return;
      }
    } catch {
      // 继续走提示
    }
    ElMessage.warning('复制失败——请手动选择代码复制');
  }
};
</script>

<style scoped>
.access-guide {
  border: 1px solid var(--app-border);
}
.access-guide__title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 10px;
}
.access-guide__steps {
  margin: 0 0 10px;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.9;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.access-guide__code {
  position: relative;
  margin-top: 6px;
}
.access-guide__code pre {
  background: var(--app-raised);
  border: 1px solid var(--app-rule);
  border-radius: var(--app-control-radius);
  padding: 10px 14px;
  margin: 0;
  font-family: Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
  overflow-x: auto;
  white-space: pre;
}
.access-guide__copy {
  position: absolute;
  top: 6px;
  right: 8px;
}
.access-guide__alt {
  font-size: 12px;
  margin: 0 0 10px;
}
.access-guide__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.muted {
  color: var(--app-muted);
}
</style>
