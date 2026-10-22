<template>
  <div ref="rootEl" class="dcs-confirm" :class="ask.cardState" tabindex="-1">
    <div class="ch">
      <span class="ch-ic"><IconSpan name="power" /></span>
      <b>{{ kindCfg.title }}</b>
      <button class="ch-why" type="button" :aria-expanded="whyOpen" title="为什么问我？" @click="whyOpen = !whyOpen">ⓘ</button>
    </div>

    <!-- 通用正文（非动作类富卡 / 富卡数据未就绪时的兜底文案） -->
    <!-- 业务批7 Q14：通用卡正文人话化——框架英文默认句（Tool 'x' requires user confirmation…）
         按工具名映射中文；无映射工具回落 ask.message 原文（宁可原文不瞎编） -->
    <p v-if="!rich" class="cb">{{ genericBodyText }}</p>

    <!-- CF-1 富卡正文（act_remind）：任务行 + 催办对象 chips + 附言 + ⓘ 逐人证据 -->
    <div v-else-if="toolKind === 'remind'" class="cb rich">
      <div class="kvl">
        <span class="k">任务</span>
        <span class="v">
          {{ targetInfo?.missionName ?? shortId(ask.toolInput?.mission_id) }}
          <template v-if="targetInfo?.topicName"> · {{ targetInfo.topicName }}</template>
          <template v-if="pendingTargets.length > 0"> · 待催 {{ pendingTargets.length }} 人</template>
        </span>
      </div>

      <div class="kvl">
        <span class="k">催办对象</span>
        <span class="v objs">
          <!-- 回执只读态（卡移到停靠区后，回执里的卡是新实例、名单从未加载）：
               不重新拉名单（时过境迁会失真），按模型原始入参说明当时确认的范围 -->
          <template v-if="ask.cardState !== 'pending'">{{ receiptTargetText }}</template>
          <template v-else-if="targetsLoading">正在加载催办对象…</template>
          <template v-else-if="targetsError">名单加载失败——确认后将催<b>全部</b>未交人员</template>
          <template v-else-if="targetsUnavailable">按确认时的范围执行；具体名单请到任务页面查看</template>
          <template v-else-if="pendingTargets.length === 0">当前没有待催人员（均已提交或不合格）</template>
          <template v-else>
            <span v-if="hiddenChipCount > 0" class="omore">共 {{ keptTargets.length }} 人，前 {{ visibleChips.length }} 人：</span>
            <span v-for="t in visibleChips" :key="t.subjectUserId" class="o">
              {{ t.subjectName }}
              <button
                v-if="ask.cardState === 'pending'"
                type="button"
                class="o-x"
                :aria-label="`移除${t.subjectName}`"
                title="移除该人员"
                @click="removeTarget(t)"
              >
                ✕
              </button>
            </span>
            <span v-if="hiddenChipCount > 0" class="omore">…等 {{ hiddenChipCount }} 人（ⓘ 可看证据行）</span>
            <span v-if="removedCount > 0" class="omore">已移除 {{ removedCount }} 人</span>
          </template>
        </span>
      </div>

      <div v-if="ask.cardState === 'pending'" class="kvl">
        <span class="k">附言</span>
        <input v-model="note" class="txt" type="text" maxlength="100" placeholder="请尽快完成并提交" :disabled="ask.cardState !== 'pending'" />
      </div>
      <div v-else-if="note" class="kvl">
        <span class="k">附言</span>
        <span class="v">{{ note }}</span>
      </div>
    </div>

    <!-- 批W2 富卡正文（act_forward）：任务 + 转发项 + 接收人 + 备注 -->
    <div v-else-if="toolKind === 'forward'" class="cb rich">
      <div class="kvl">
        <span class="k">任务</span>
        <span class="v">{{ shortId(ask.toolInput?.mission_id) }}</span>
      </div>
      <div class="kvl">
        <span class="k">转发项</span>
        <span class="v">{{ forwardItemsText }}</span>
      </div>
      <div class="kvl">
        <span class="k">接收人</span>
        <span class="v">{{ forwardTargetText }}</span>
      </div>
      <div v-if="ask.cardState === 'pending'" class="kvl">
        <span class="k">备注</span>
        <input v-model="remarkInput" class="txt" type="text" maxlength="100" placeholder="可选" />
      </div>
      <div v-else-if="remarkInput" class="kvl">
        <span class="k">备注</span>
        <span class="v">{{ remarkInput }}</span>
      </div>
    </div>

    <!-- 批W2 富卡正文（act_audit）：任务 + 批次/版本三定位内联 + 结论 + 退回原因（必填） -->
    <div v-else class="cb rich">
      <div class="kvl">
        <span class="k">任务</span>
        <span class="v">{{ shortId(ask.toolInput?.mission_id) }}</span>
      </div>
      <div class="kvl">
        <span class="k">提交批次</span>
        <span class="v" :title="auditBatchFull">…{{ auditBatchShort }} · 第 v{{ auditVersion }} 版</span>
      </div>
      <div class="kvl">
        <span class="k">结论</span>
        <span class="v" :class="toolKind === 'audit-reject' ? 'verdict-reject' : 'verdict-pass'">
          {{ toolKind === 'audit-reject' ? '退回（责任人需修改后重新提交）' : '通过' }}
        </span>
      </div>
      <div v-if="ask.cardState === 'pending' && toolKind === 'audit-reject'" class="kvl">
        <span class="k">退回原因</span>
        <input v-model="remarkInput" class="txt" type="text" maxlength="100" placeholder="必填：将转达责任人" />
      </div>
      <div v-else-if="ask.cardState === 'pending'" class="kvl">
        <span class="k">备注</span>
        <input v-model="remarkInput" class="txt" type="text" maxlength="100" placeholder="可选" />
      </div>
      <div v-else-if="remarkInput" class="kvl">
        <span class="k">{{ toolKind === 'audit-reject' ? '退回原因' : '备注' }}</span>
        <span class="v">{{ remarkInput }}</span>
      </div>
    </div>

    <div v-if="whyOpen" class="cwhy">
      <template v-if="rich && !targetsError && evidenceRows.length > 0">
        <!-- CF-1：ⓘ 折叠=逐人证据行（真值：状态/已催次数/限频；不编造响应时间） -->
        <div v-for="row in evidenceRows" :key="row.userId" class="evrow">
          <b>{{ row.name }}</b>
          <span class="evmeta">
            {{ row.dept }} · {{ row.statusText }}
            <template v-if="row.urged"> · 已催 {{ row.urged }} 次</template>
            <template v-if="row.limited"> · 限频中（下次可催 {{ row.nextAllowed }}）</template>
          </span>
        </div>
      </template>
      <template v-else>
        <p v-if="ask.message" class="evmeta">{{ ask.message }}</p>
        <p>写操作（如催办、撤回、修改）会影响真实数据。按你设置的「每次都问」，执行前需要你确认；确认与取消都会留审计记录，取消不算过错。</p>
      </template>
    </div>

    <!-- pending：确认/取消（取消不算过错，留审计记录）。
      SPEC §3.1'：act_ 前缀动作类永不渲染「本会话内不再确认」——写操作无免确认通道 -->
    <div v-if="ask.cardState === 'pending'" class="cf">
      <label v-if="!isActTool" class="cbx"><input v-model="remember" type="checkbox" /> 本会话内同类操作不再逐次确认</label>
      <div v-if="rich && allRemoved" class="cwarn">已移除全部人员——请至少保留一人，或改用取消</div>
      <div v-if="rejectReasonMissing" class="cwarn">退回需填写原因（将转达责任人）</div>
      <div class="cf-btns">
        <button class="cf-cancel" @click="emit('resolve', false, false)">取消</button>
        <button class="cf-go" :class="kindCfg.tone" :disabled="confirmDisabled" @click="onConfirm">
          {{ kindCfg.verb }}
        </button>
      </div>
    </div>
    <div v-else-if="ask.cardState === 'run'" class="c-run">
      <span class="spin"><IconSpan name="loader-circle" /></span>已确认，正在执行…
    </div>
    <div v-else class="c-done" :class="{ cancelled: ask.cancelled, stopped: ask.stopped }">
      <template v-if="ask.cancelled">已取消，未执行任何操作</template>
      <!-- 079：确认后点停止=执行结果未知的中性文案，不冒充「已执行完成」 -->
      <template v-else-if="ask.stopped">已停止 · 执行结果未知（可重新发起）</template>
      <template v-else><IconSpan name="check" /> 已执行完成（结果见下方回答）</template>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * HITL 确认卡（批E E3 + 批C 补件 + CF-1 富卡 2026-10-06；批W2 泛化 2026-10-12）。
 * 动作词法表（SPEC §2.1）：act_remind=催办卡（chips 删人+附言+ⓘ 逐人证据）/
 * act_forward=转发卡（转发项+接收人+备注）/ act_audit=审核卡（批次·版本三定位内联+
 * 退回原因必填）；标题/按钮动词/色阶跟动词走（通过蓝·转发催办橙·退回红）。
 * act_ 前缀整条关闭免确认（SPEC §3.1'）：不渲染「本会话不再确认」勾选。
 * 确认→父级 resolveHitl(true, remember, edits)（resume resolved+editedArgs 全量替换，
 * 键与 @ToolParam 同名——remind: message/target_user_ids；forward/audit: remark）；
 * 取消→cancelled 不报错。可达性：卡到自动 focus 容器（tabindex=-1）、chips ✕=真按钮
 * aria-label 逐人命名、Esc 链前置 pending 卡分支（AssistantDrawer）。
 */
import type { HitlAsk, HitlEdits } from './useAssistant';
/** 批5 催办名单端点退役后 targets 恒空——富字段展示机道按退役前口径保留（全可选） */
type TopicUrgeTargetVO = {
  userId: number;
  userName?: string;
  nickName?: string;
  subjectUserId?: string | number;
  subjectName?: string;
  detailStatus?: string;
  publishDeptPath?: string;
  recentUrgeCount?: number;
  nextAllowedAt?: string;
};
import IconSpan from './IconSpan.vue';

const props = defineProps<{ ask: HitlAsk }>();
const emit = defineEmits<{ resolve: [approved: boolean, remember?: boolean, edits?: HitlEdits] }>();

const whyOpen = ref(false);
const remember = ref(false);
const rootEl = ref<HTMLElement | null>(null);

// ===== 批W2 动作词法表（SPEC §2.1）：标题/按钮动词跟动词走，色阶=通过蓝/转发催办橙/退回红 =====
type ToolKind = 'remind' | 'forward' | 'audit-pass' | 'audit-reject' | 'generic';
const toolKind = computed<ToolKind>(() => {
  const tn = props.ask.toolName ?? '';
  if (tn === 'act_remind') return 'remind';
  if (tn === 'act_forward') return 'forward';
  if (tn === 'act_audit') {
    // 三审 P0（glm53/dspro/dsflash 交叉确认）：dcsToolInput 镜像把所有值字符串化
    // （19 位 Long 精度保护的副作用），pass 到这里必为 "false" 字符串——严格 === false
    // 判定会让退回卡永远渲染成通过卡（红钮/必填原因整条失效），必须归一双形态。
    // toolInput 缺失（镜像注入失败/不安全数字降级置空）时回落 generic 中性卡
    // （glm53 P1：默认落 audit-pass 会给破坏性操作误标「通过」标题）
    if (!props.ask.toolInput) return 'generic';
    const passRaw = props.ask.toolInput.pass;
    return passRaw === false || passRaw === 'false' ? 'audit-reject' : 'audit-pass';
  }
  return 'generic';
});
const KIND_CFG: Record<ToolKind, { title: string; verb: string; tone: '' | 'warn' | 'danger' }> = {
  remind: { title: '确认要发这条催办吗', verb: '确认催办', tone: 'warn' },
  forward: { title: '确认要执行这次转发吗', verb: '确认转发', tone: 'warn' },
  'audit-pass': { title: '确认要通过这条提交吗', verb: '确认通过', tone: '' },
  'audit-reject': { title: '确认要退回这条提交吗', verb: '确认退回', tone: 'danger' },
  generic: { title: '确认要执行这个操作吗', verb: '确认执行', tone: '' }
};
/* 业务批7 Q14：web_search 等工具的标题/动词人话化——常量前置声明（AGENTS.md 记录的 TDZ 坑） */
const GENERIC_TOOL_TXT: Record<string, { title: string; verb: string }> = {
  web_search: { title: '确认要联网搜索吗', verb: '确认搜索' }
};
/** 业务批7 Q14：通用卡正文人话化——框架英文默认句按工具名映射中文；无映射回落原文（不瞎编） */
const TOOL_BODY_ZH: Record<string, string> = {
  web_search: '模型想联网搜索公开网络信息来回答你的问题，确认后才会执行搜索。'
};
/* 未登记的新写工具也要能读懂——act_ 前缀一律中文兜底，不再透出框架英文默认句 */
const kindCfg = computed(() => {
  const override = GENERIC_TOOL_TXT[props.ask.toolName ?? ''];
  if (toolKind.value === 'generic' && override) {
    return { ...override, tone: '' as '' | 'warn' | 'danger' };
  }
  return KIND_CFG[toolKind.value];
});
const genericBodyText = computed(() => {
  const tn = props.ask.toolName ?? '';
  if (tn && TOOL_BODY_ZH[tn]) {
    return TOOL_BODY_ZH[tn];
  }
  if (tn.startsWith('act_')) {
    return '这个操作会修改真实数据，确认后才会执行；取消不会产生任何变更。';
  }
  // 三审回修（dspro P2-4）：未登记工具的框架默认英文句按通用写类口径兜底，不透出原文
  const raw = props.ask.message || '';
  if (raw.includes('requires user confirmation')) {
    return '这个操作会修改真实数据，确认后才会执行；取消不会产生任何变更。';
  }
  return raw || '这个操作会影响真实数据，请确认。';
});

/** SPEC §3.1'：act_ 前缀=动作类——免确认通道（remember 勾选/autoApproved）整条关闭 */
const isActTool = computed(() => (props.ask.toolName ?? '').startsWith('act_'));

/** 卡到达自动 focus 卡容器（SPEC §2.1 可达性：tabindex=-1 焦点可 Esc 回收，不抢输入框） */
watch(
  () => props.ask.cardState,
  (s) => {
    if (s === 'pending') {
      nextTick(() => rootEl.value?.focus({ preventScroll: true }));
    }
  },
  { immediate: true }
);

/** 富卡判定：动作类工具且 toolInput 可用（镜像缺失且原始入参含不安全数字时
 *  useAssistant 已置空 toolInput——editedArgs 回声须逐字精确，降级通用卡） */
const rich = computed(() => toolKind.value !== 'generic' && !!props.ask.toolInput);

// ===== 批W2 forward/audit 卡正文数据（toolInput 出参均为字符串/小整数，无 BigNumber 险） =====
const remarkInput = ref('');

function tail4(v: unknown): string {
  const s = v == null ? '' : String(v).trim();
  return s ? s.slice(-4) : '';
}

const forwardItemsText = computed(() => {
  const raw = String(props.ask.toolInput?.trace_ids ?? '').trim();
  const ids = raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => /^\d+$/.test(s));
  if (ids.length === 0) {
    return '（模型未给出责任项编号——请取消后重新说明要转哪几项）';
  }
  const head = ids
    .slice(0, 3)
    .map((s) => `…${s.slice(-4)}`)
    .join('、');
  return ids.length > 3 ? `${ids.length} 个责任项（${head} 等）` : `${ids.length} 个责任项（${head}）`;
});

const forwardTargetText = computed(() => {
  const single = String(props.ask.toolInput?.to_user_id ?? '').trim();
  const multi = String(props.ask.toolInput?.to_user_ids ?? '').trim();
  if (/^\d+$/.test(single)) {
    return `1 人（用户ID …${single.slice(-4)}）`;
  }
  const ids = multi
    .split(',')
    .map((s) => s.trim())
    .filter((s) => /^\d+$/.test(s));
  if (ids.length > 0) {
    const head = ids
      .slice(0, 3)
      .map((s) => `…${s.slice(-4)}`)
      .join('、');
    return ids.length > 3 ? `${ids.length} 人（${head} 等）` : `${ids.length} 人（${head}）`;
  }
  return '（模型未给出接收人——请取消后重新说明转给谁）';
});

const auditBatchShort = computed(() => tail4(props.ask.toolInput?.submission_batch_id));
const auditBatchFull = computed(() => String(props.ask.toolInput?.submission_batch_id ?? ''));
const auditVersion = computed(() => String(props.ask.toolInput?.expected_submission_version ?? '?'));

/** 退回未填原因：确认钮置灰 + 行内提示（后端 act_audit 同口径二道闸） */
const rejectReasonMissing = computed(() => toolKind.value === 'audit-reject' && props.ask.cardState === 'pending' && remarkInput.value.trim() === '');

// ===== 催办目标名单（仅 pending 态拉取一次；run/done 复用已拉到的数据只读展示） =====
/** 名单端点已随旧任务树退役（批5）：pending 态不再伪造「当前没有待催人员」 */
const targetsUnavailable = ref(false);
/** 镜像里的数组有两种形态（Map 形态 "[101, 102]" / JSON 形态 "[101,102]"）——统一按数字扫描 */
function idListFrom(v: unknown): string[] {
  if (Array.isArray(v)) {
    return v.map((x) => String(x).trim()).filter((x) => /^\d+$/.test(x));
  }
  const s = v == null ? '' : String(v);
  return s.match(/\d+/g) ?? [];
}
/** 回执只读态：不重新拉名单，按模型原始入参说明当时确认的范围 */
const receiptTargetText = computed(() => {
  const ids = idListFrom(props.ask.toolInput?.target_user_ids);
  return ids.length > 0 ? `指定 ${ids.length} 人` : '全部未交人员（确认时的名单）';
});
const targetInfo = ref<{ missionName?: string; topicName?: string } | null>(null);
const targets = ref<TopicUrgeTargetVO[]>([]);
const targetsLoading = ref(false);
const targetsError = ref(false);
/** 删人集合按字符串匹配（BigNumberSerializer 对超安全域 Long 出站为字符串——19 位
 *  ID 走 Number() 会取整碰撞误删，glm53 P3-2）；模型原始入参里的 target_user_ids 同为逗号串 */
const removedIds = ref<Set<string>>(new Set());
const note = ref('');

/** 模型原始入参圈定的子集（单审 P1-1：模型传 target_user_ids 时卡面必须只展示该子集，
 *  否则「卡面全量/实际只发模型圈的人」所见≠所发；缺省 null=模型没圈，展示全量名单） */
const modelSubsetIds = computed<Set<string> | null>(() => {
  const raw = props.ask.toolInput?.target_user_ids;
  if (typeof raw !== 'string' || !raw.trim()) {
    return null;
  }
  const ids = raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => /^\d+$/.test(s));
  return ids.length > 0 ? new Set(ids) : null;
});

/** 与后端 isPending 同口径（PENDING_STATUSES 五码 + 空值视为待办） */
// F-D1: FORWARDED=trace 级"已转发未提交"（mission 级名单 listPendingUsers 的主状态）
const PENDING_CODES = new Set(['DRAFT', 'PENDING_COMPLETE', 'IN_DRAFT', 'RETURNED', 'REVISING', 'FORWARDED']);
const pendingTargets = computed(() => {
  const base = targets.value.filter((t) => !t.detailStatus || PENDING_CODES.has(t.detailStatus));
  // P1-1：模型圈了子集 → 卡面只展示子集（所见即所发——不删人也不回传时 editedArgs
  // 底座就是模型原始子集，卡面展示与实际执行保持同一份名单）
  return modelSubsetIds.value ? base.filter((t) => modelSubsetIds.value!.has(String(t.subjectUserId))) : base;
});
const keptTargets = computed(() => pendingTargets.value.filter((t) => !removedIds.value.has(String(t.subjectUserId))));
/** chips 展示上限（E2E 实证 221 人全铺会把卡撑爆；上限外以计数行说明，名单仍完整用于提交） */
const CHIP_CAP = 30;
const visibleChips = computed(() => {
  // P3-1：run/done 回看也用 keptTargets（=最终发送名单），不再切回含被删者的 pendingTargets
  const base = keptTargets.value;
  return base.slice(0, CHIP_CAP);
});
const hiddenChipCount = computed(() => {
  const base = keptTargets.value;
  return Math.max(0, base.length - CHIP_CAP);
});
const removedCount = computed(() => pendingTargets.value.length - keptTargets.value.length);
const allRemoved = computed(
  () => rich && !targetsLoading.value && !targetsError.value && pendingTargets.value.length > 0 && keptTargets.value.length === 0
);
const confirmDisabled = computed(() => allRemoved.value || rejectReasonMissing.value);

const STATUS_TEXT: Record<string, string> = {
  DRAFT: '未提交',
  PENDING_COMPLETE: '待完成',
  IN_DRAFT: '未提交',
  RETURNED: '已退修',
  REVISING: '待修改',
  COMPLETED: '已完成'
};
const evidenceRows = computed(() =>
  targets.value.slice(0, 20).map((t) => ({
    userId: t.subjectUserId,
    name: t.subjectName,
    dept: (t.publishDeptPath ?? '').split('/').filter(Boolean).pop() ?? '',
    statusText: STATUS_TEXT[t.detailStatus ?? ''] ?? (t.detailStatus ? '暂不可催' : '未提交'),
    urged: t.recentUrgeCount ?? 0,
    limited: !!t.nextAllowedAt,
    nextAllowed: t.nextAllowedAt ? String(t.nextAllowedAt).slice(5, 16).replace('T', ' ') : ''
  }))
);

function removeTarget(t: TopicUrgeTargetVO) {
  removedIds.value.add(String(t.subjectUserId));
  removedIds.value = new Set(removedIds.value);
}

function shortId(v: unknown): string {
  const s = v == null ? '' : String(v).trim();
  return s ? `#${s.slice(-4)}` : '';
}

async function loadTargets() {
  const mid = String(props.ask.toolInput?.mission_id ?? '').trim();
  const tid = String(props.ask.toolInput?.topic_id ?? '').trim();
  if (!/^\d+$/.test(mid)) {
    targetsError.value = true;
    return;
  }
  targetsLoading.value = true;
  try {
    // 批5: 催办名单端点随旧任务树退役——确认卡不再拉名单, 引导到任务页面操作
    targetInfo.value = { topicName: '催办请到任务页面操作' };
    targets.value = [];
    targetsError.value = false;
    targetsUnavailable.value = true;
  } catch {
    targetsError.value = true;
  } finally {
    targetsLoading.value = false;
  }
}

/** listPendingUsers 返回形状兼容（axios 封装 data/rows 双形态，同 detail.vue unwrap 口径） */

/** 名单异步落成（221 人 chips+证据行）会把确认钮顶出视口：MsgFlow 只在卡到达那一刻
 *  贴底一次，看不到名单后到（E2E 实测缺口 554px）。名单渲染后补贴底。
 *  三段执行（t0/600/1200ms）：cardArrived 的平滑滚动可能仍在途中（守卫会误判跳过），
 *  多段复查直到动画落定；守卫=卡仍在流可视区内——用户已上翻读历史则不打扰（k3 P2-2 二段修复）。 */
function scrollAfterTargets() {
  const settle = () => {
    const el = rootEl.value;
    const flow = el?.closest('.dcs-flow') as HTMLElement | null;
    if (!el || !flow || props.ask.cardState !== 'pending') {
      return;
    }
    const er = el.getBoundingClientRect();
    const fr = flow.getBoundingClientRect();
    if (er.bottom > fr.top && er.top < fr.bottom) {
      flow.scrollTop = flow.scrollHeight;
    }
  };
  nextTick(settle);
  setTimeout(settle, 600);
  setTimeout(settle, 1200);
}

watch(
  () => [props.ask.cardState, rich.value] as const,
  ([state, isRich]) => {
    // 附言/备注先按模型原始入参回填——不再只限 pending：回执里的卡是新实例，
    // 只读态也要能看出当时拟用/实际发送的附言（pending 时后续用户改写会覆盖）
    if (isRich && toolKind.value === 'remind' && note.value === '') {
      const m = props.ask.toolInput?.message;
      note.value = typeof m === 'string' && m.trim() !== '' ? m : '';
    }
    if (
      isRich &&
      (toolKind.value === 'forward' || toolKind.value === 'audit-pass' || toolKind.value === 'audit-reject') &&
      remarkInput.value === ''
    ) {
      const r = props.ask.toolInput?.remark;
      remarkInput.value = typeof r === 'string' && r.trim() !== '' ? r : '';
    }
    // 名单只属催办卡（三审 dsflash P2-2）：forward/audit 卡不展示名单，
    // 富卡即拉一次 listPendingUsers 是白打接口（大任务 221 行 payload）；只读回执不拉（会失真）
    if (
      state === 'pending' &&
      isRich &&
      toolKind.value === 'remind' &&
      !targetsLoading.value &&
      targets.value.length === 0 &&
      !targetsError.value &&
      !targetsUnavailable.value
    ) {
      loadTargets();
    }
  },
  { immediate: true }
);

function onConfirm() {
  // 只在用户真删过人时才回传保留名单（否则省略 target_user_ids=服务端全量单次催办，
  // 避免「没删人却把 1 次全量劣化成 N 次逐人调用」——E2E 实证 221 人场景）。
  // P1-1：模型圈了子集时卡面已过滤为该子集，此处的 keptTargets 即子集扣删——不删人
  // 不回传，editedArgs 底座=模型原始子集，所见即所发闭环。
  // P2-2 数据层闸：删光名单时协议层空串=全量催办，UI 禁用之外再挡一道 emit。
  if (rich.value && toolKind.value === 'remind' && removedCount.value > 0 && keptTargets.value.length === 0) {
    return;
  }
  // 批W2：audit-reject 空原因数据层闸（UI 置灰之外再挡一道 emit，与后端二道闸同口径）
  if (rejectReasonMissing.value) {
    return;
  }
  const edits: HitlEdits | undefined = rich.value
    ? toolKind.value === 'remind'
      ? {
          message: note.value.trim(),
          ...(removedCount.value > 0 && !targetsError.value ? { targetUserIds: keptTargets.value.map((t) => t.subjectUserId) } : {})
        }
      : { remark: remarkInput.value.trim() }
    : undefined;
  emit('resolve', true, remember.value, edits);
}
</script>

<style lang="scss" scoped>
.dcs-confirm {
  border: 1px solid #f3d19e;
  background: #fffcf5;
  border-radius: 10px;
  padding: 0;
  font-size: 13px;
  /* SPEC §2.1 可达性：卡容器可聚焦（到卡自动 focus）；焦点可见性克制为细线 */
  &:focus-visible {
    outline: 2px solid var(--el-color-primary-light-5);
    outline-offset: 1px;
  }

  /* 原型 .confirm 分区各自 padding（L219）：头带/正文/按钮带三段 */
  > * {
    padding: 10px 12px;
  }

  &.run {
    border-color: var(--el-color-primary-light-5);
    background: var(--el-color-primary-light-9);
  }
  &.done {
    border-color: #c2e7b0;
    background: #f6fceb;

    /* 079：停止态去绿——结果未知不是成功，回中性琥珀底 */
    &.stopped {
      border-color: #f3d19e;
      background: #fffcf5;
    }
  }
}
.ch {
  /* 原型 .confirm .ch（L219）：头带=渐变底+暖色下边线 */
  display: flex;
  align-items: center;
  gap: 6px;
  border-bottom: 1px solid #f7ecd4;
  background: linear-gradient(#fffdf8, #fffcf5);

  b {
    font-size: 13.5px;
    color: var(--dcs-text-primary);
  }
  .ch-ic {
    font-size: 14px;
  }
  .ch-why {
    margin-left: auto;
    border: none;
    background: none;
    padding: 0 2px;
    font-size: 13px;
    color: var(--dcs-text-placeholder);
    cursor: pointer;

    &:hover {
      color: var(--el-color-primary);
    }
  }
}
.cb {
  margin: 0 0 8px;
  line-height: 1.65;
  color: var(--dcs-text-regular);
  white-space: pre-wrap;
  word-break: break-word;

  &.rich {
    white-space: normal;
  }
}
/* CF-1 富卡 kv 行（原型 .kv/.objs/.txt） */
.kvl {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin: 2px 0;

  .k {
    flex: none;
    font-weight: 600;
    color: var(--dcs-text-primary);
  }
  .v {
    flex: 1;
    min-width: 0;
    word-break: break-all;
  }

  /* 批W2 审核卡结论行：通过=绿/退回=红（与状态色板语义一致） */
  .verdict-pass {
    color: var(--dcs-color-success);
  }
  .verdict-reject {
    color: var(--el-color-danger);
  }
}
.objs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;

  /* 原型 .objs .o（L225）：白底胶囊 14px 圆角 + ✕ 次要色
     批W2 可达性：✕ 从 <u> 换真 <button>（aria-label 逐人命名） */
  .o {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    background: var(--el-bg-color);
    border: 1px solid var(--dcs-border-color-light);
    border-radius: 14px;
    padding: 2px 6px 2px 10px;
    font-size: 12px;

    .o-x {
      border: none;
      background: none;
      padding: 0;
      color: var(--dcs-text-placeholder);
      cursor: pointer;
      text-decoration: none;
      font-size: 11px;
      line-height: 1;

      &:hover {
        color: var(--el-color-danger);
      }
    }
  }
  .omore {
    font-size: 12px;
    color: var(--dcs-text-secondary);
  }
  b {
    color: var(--dcs-text-primary);
  }
}
.txt {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--dcs-border-color-light);
  border-radius: 6px;
  padding: 5px 10px;
  font-size: 13px;
  color: var(--dcs-text-regular);
  background: var(--el-bg-color);

  &::placeholder {
    color: var(--dcs-text-placeholder);
  }
  &:focus {
    outline: none;
    border-color: var(--el-color-primary);
  }
}
.cwhy {
  font-size: 12px;
  color: var(--dcs-text-secondary);
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
  padding: 6px 8px;
  margin: 0 0 8px;
  line-height: 1.6;
}
/* CF-1 ⓘ 逐人证据行 */
.evrow {
  display: flex;
  gap: 6px;
  align-items: baseline;
  padding: 2px 0;

  b {
    flex: none;
    color: var(--dcs-text-regular);
    font-weight: 600;
  }
  .evmeta {
    color: var(--dcs-text-secondary);
    word-break: break-all;
  }
}
.cf {
  display: flex;
  flex-direction: column;
  gap: 8px;

  .cbx {
    align-self: flex-start;
    font-size: 12px;
    color: var(--dcs-text-secondary);
    cursor: pointer;
    display: flex;
    gap: 5px;
    align-items: center;

    input {
      accent-color: var(--el-color-primary);
    }
  }
  .cwarn {
    font-size: 12px;
    color: var(--el-color-danger);
  }
  .cf-btns {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    border-top: 1px solid #f7ecd4;
  }

  button {
    /* 原型 .btn/.btn-p（L70-72）：radius 6 / 高 32 / 字 14 */
    border-radius: 6px;
    padding: 6px 16px;
    font-size: 13px;
    cursor: pointer;
    border: 1px solid var(--dcs-border-color-light);
    background: var(--el-bg-color);
    color: var(--dcs-text-regular);

    &.cf-go {
      /* 原型确认发送主钮=主色蓝（L1362 .btn-p），非琥珀 */
      background: var(--el-color-primary);
      border-color: var(--el-color-primary);
      color: #fff;

      &:hover:not(:disabled) {
        background: var(--el-color-primary-light-3);
      }
      &:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }

      /* SPEC §2.1 危险级别色阶：转发/催办=warning 橙、退回=danger 红——破坏性动词禁用蓝色主钮 */
      &.warn {
        background: var(--el-color-warning);
        border-color: var(--el-color-warning);

        &:hover:not(:disabled) {
          background: var(--el-color-warning-light-3);
        }
      }
      &.danger {
        background: var(--el-color-danger);
        border-color: var(--el-color-danger);

        &:hover:not(:disabled) {
          background: var(--el-color-danger-light-3);
        }
      }
    }
    &.cf-cancel:hover {
      color: var(--dcs-text-primary);
      border-color: var(--dcs-border-color);
    }
  }
}
.c-run {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--el-color-primary);
  font-size: 12.5px;

  .spin {
    animation: dcs-cf-rot 1s linear infinite;
  }
}
.c-done {
  color: var(--dcs-color-success);
  font-size: 12.5px;

  &.cancelled {
    color: var(--dcs-text-secondary);
  }

  /* 079：停止=警示系（结果未知），非成功绿非取消灰 */
  &.stopped {
    color: var(--el-color-warning);
  }
}
@keyframes dcs-cf-rot {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .spin {
    animation: none;
  }
}
</style>
