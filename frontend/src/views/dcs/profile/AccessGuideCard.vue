<template>
  <el-card shadow="never" class="access-guide">
    <div class="access-guide__title">把你的业务数据接进画像（照 dcs 抄三步）</div>
    <ol class="access-guide__steps">
      <li>
        <b>① 在你的业务代码里发事件</b>
        <span class="muted">——和 dcs 模块同款：系统里每次发布 / 提交 / 审核任务，就是这么 5 行发出去的</span>
        <div class="access-guide__code">
          <pre>{{ CODE_SAMPLE }}</pre>
          <el-button link type="primary" class="access-guide__copy" @click="copyCode">复制</el-button>
        </div>
      </li>
      <li>
        <b>② 先试试</b>
        <span class="muted">——去「引擎与运维 → 引擎运维 → 引擎管理 → 测试发送」粘一段 JSON 发一条，马上能在画像页看到</span>
      </li>
      <li>
        <b>③ 然后什么都不用配</b>
        <span class="muted">——你的来源自动出现在所有下拉，自动建配置、自动算分</span>
      </li>
    </ol>
    <p class="access-guide__alt muted">
      不是 Java 项目？还有两条路：CSV 离线补录、以及让智能体帮你查（见文档「给你的系统接画像」）。画像分析的是真实业务操作——数据不动，页面就不动。
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
 * 接入示例卡(画像方向第二批·题7 推荐): 不做接入码/新端点/向导页,
 * 只把 dcs 的 5 行门面调用摆到明面上。代码示例=dcs 模块真实调法(零虚构)。
 */
const canTestSend = checkPermi(['profile:engine:query']);
const router = useRouter();
const goTestSend = () => router.push('/insight/engine-ops/profile-ops?tab=admin&sub=seed');

const CODE_SAMPLE = [
  'ProfileEventBo e = new ProfileEventBo();',
  'e.setSource("myapp");           // 来源名自己定',
  'e.setEventType("task_done");    // 事件名自己定',
  'e.setEventUid(uid);             // 幂等键：同一条重发不会重复计数',
  'e.setSubjectType("user");       // 主体类型：user / visitor / order…',
  'e.setSubjectId("user-123");     // 这个人的 ID',
  'profileEventPublisher.publish(e);'
].join('\n');

const copyCode = async () => {
  try {
    await navigator.clipboard.writeText(CODE_SAMPLE);
    ElMessage.success('代码已复制');
  } catch {
    // 三审 dsflash P3: 局域网 http(非安全上下文)没有 navigator.clipboard, 退 execCommand
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
