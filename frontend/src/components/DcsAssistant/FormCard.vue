<template>
  <!-- dcs-confirm 标记类：MsgFlow.jumpToConfirm 的 .dcs-confirm.pending 选择器可跳到本卡（样式自持，见 scoped） -->
  <div ref="rootEl" class="dcs-form-card dcs-confirm" :class="ask.cardState" tabindex="-1">
    <div class="ch">
      <span class="ch-ic"><IconSpan name="file" /></span>
      <b>确认执行 · 创建任务（AI 已预填，请过目）</b>
      <button class="ch-why" type="button" :aria-expanded="whyOpen" title="为什么问我？" @click="whyOpen = !whyOpen">ⓘ</button>
    </div>

    <div class="cb">
      <div class="frow">
        <label class="k">任务名称<span class="req">＊</span></label>
        <div class="ctrl">
          <el-input v-model="fName" maxlength="200" show-word-limit placeholder="必填" :disabled="!editable" @change="touched.name = true" />
          <span class="badge" :class="{ user: touched.name }">{{ touched.name ? '你改过' : 'AI 填' }}</span>
        </div>
      </div>

      <div class="frow">
        <label class="k">任务类型<span class="req">＊</span></label>
        <div class="ctrl">
          <el-radio-group v-model="fType" :disabled="!editable" @change="touched.type = true">
            <el-radio :value="1">填表类</el-radio>
            <el-radio :value="2">追加类</el-radio>
          </el-radio-group>
          <span class="badge" :class="{ user: touched.type }">{{ touched.type ? '你改过' : 'AI 填' }}</span>
        </div>
      </div>

      <div class="frow">
        <label class="k">开始时间</label>
        <div class="ctrl">
          <el-date-picker
            v-model="fBegin"
            type="datetime"
            format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="可选"
            :disabled="!editable"
            class="dt"
            @change="touched.begin = true"
          />
          <span class="badge" :class="{ user: touched.begin }">{{ touched.begin ? '你改过' : 'AI 填' }}</span>
        </div>
      </div>

      <div class="frow">
        <label class="k">截止时间</label>
        <div class="ctrl">
          <el-date-picker
            v-model="fEnd"
            type="datetime"
            format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="可选"
            :disabled="!editable"
            class="dt"
            @change="touched.end = true"
          />
          <span class="badge" :class="{ user: touched.end }">{{ touched.end ? '你改过' : 'AI 填' }}</span>
        </div>
      </div>

      <div class="frow">
        <label class="k">任务说明</label>
        <div class="ctrl">
          <el-input
            v-model="fDesc"
            type="textarea"
            :rows="2"
            maxlength="500"
            placeholder="可选"
            :disabled="!editable"
            @change="touched.desc = true"
          />
          <span class="badge" :class="{ user: touched.desc }">{{ touched.desc ? '你改过' : 'AI 填' }}</span>
        </div>
      </div>

      <!-- 五需求批 R4b 智能填表（Q7补充 拍板F）：整段粘贴→名称/起止时间 -->
      <div v-if="editable" class="spaste">
        <button class="sp-toggle" type="button" :aria-expanded="pasteOpen" @click="pasteOpen = !pasteOpen">
          <IconSpan name="clipboard" /> 智能填表：粘贴一段话自动填名称和时间
        </button>
        <div v-if="pasteOpen" class="sp-body">
          <el-input
            v-model="pasteText"
            type="textarea"
            :rows="3"
            maxlength="600"
            placeholder="例如：名称：2026 春季体育节报名，开始 2026-10-20 9:00，截止 2026-10-25 18:00（日期须带年份）"
          />
          <div class="sp-row">
            <span class="sp-hint">{{ pasteHint }}</span>
            <button class="sp-go" type="button" :disabled="!pasteText.trim()" @click="applyPaste">识别并填充</button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="whyOpen" class="cwhy">
      创建任务会影响真实数据。按你设置的「每次都问」，执行前需要你确认；确认与取消都会留审计记录，取消不算过错。
      执行结果是一份草稿——明细与责任人配置、发布仍需在任务管理页完成。
    </div>

    <!-- SPEC §2.2 三出路：确认执行（置灰闸=必填缺失）/ 改一改（当前值回注对话）/ 取消（零落库） -->
    <div v-if="editable" class="cf">
      <div v-if="missingRequired" class="cwarn">必填缺失：{{ missingRequired }}——补齐后才能执行</div>
      <div v-if="timeUnparsedLabel" class="cwarn">{{ timeUnparsedLabel }}时间 AI 给的格式没认出来（已清空），请重选或留空</div>
      <div class="cf-btns">
        <button class="cf-cancel" @click="emit('resolve', false, false)">取消</button>
        <button class="cf-mid" title="当前表单值带回对话，说出要改的地方，AI 改完重新出卡" @click="onRevise">改一改（回对话说）</button>
        <button class="cf-go" :disabled="!!missingRequired" @click="onConfirm">确认执行</button>
      </div>
    </div>
    <div v-else-if="ask.cardState === 'run'" class="c-run">
      <span class="spin"><IconSpan name="loader-circle" /></span>已确认，正在执行…
    </div>
    <div v-else class="c-done" :class="{ cancelled: ask.cancelled }">
      <template v-if="ask.cancelled">已取消，未创建任何任务</template>
      <template v-else><IconSpan name="check" /> 已执行完成（结果见下方回答）</template>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 表单卡（二站 Q8-B，SPEC §2.2，2026-10-12）：AI 预填建任务基本信息 → 用户过目改字段 → 三出路。
 * 与 ConfirmCard 的分工：ConfirmCard=动作确认（展示模型入参+小改），FormCard=表单渲染
 * （schema 驱动的真实 EP 控件：输入框/单选/日期时间选择器/文本域，非纯文本展示）。
 * 字段面与真实新建表单「基本信息」段一致（名称/类型/开始/截止/说明）；责任人分派是向导
 * 后续步骤的深链契约（assign-details 只认已存在明细行），不进本卡——回执由后端指路。
 *
 * 三出路（缺一不可）：
 * - 确认执行 → resolve(true, false, edits.formCreate 五字段全量)——editedArgs 整体替换，
 *   mission_type 发 number（字符串绑 @ToolParam Integer 静默 null，批W3 镜像保型教训）
 * - 改一改 → emit('revise') 上浮为用户消息（当前值序列化回注对话；发新消息会作废悬挂卡
 *   =§4 律1 既有件零新机制），模型改完重新出卡
 * - 取消 → resolve(false) 零落库
 * 必填缺失（名称/类型）：确认置灰+行内原因；每字段「AI 填/你改过」本地状态（touched）。
 * 可达性同 ConfirmCard：卡到自动 focus（tabindex=-1）、Esc 链由 AssistantDrawer 统一前置。
 */
import type { HitlAsk, HitlEdits } from './useAssistant';
import IconSpan from './IconSpan.vue';

const props = defineProps<{ ask: HitlAsk }>();
const emit = defineEmits<{ resolve: [approved: boolean, remember?: boolean, edits?: HitlEdits]; revise: [message: string] }>();

const whyOpen = ref(false);
const rootEl = ref<HTMLElement | null>(null);
const editable = computed(() => props.ask.cardState === 'pending');

const fName = ref('');
const fType = ref<number | null>(null);
const fBegin = ref<string>('');
const fEnd = ref<string>('');
const fDesc = ref('');
// 镜像原始串（P2-3 用）：normalizeDateTime 认不出的形态（ISO T/timestamp 等）置空后，
// raw 非空 vs 归一空 = 无感丢失，须在卡面提示；用户重选（fBegin 有值）后提示自然消失
const rawBegin = ref('');
const rawEnd = ref('');
const touched = reactive({ name: false, type: false, begin: false, end: false, desc: false });

/** 镜像形态归一：toolInput 可能缺字段/类型值是字符串形态（全字符串镜像旧形态）——
 *  卡面只认 1/2 数字与合法日期串，其余置空让用户补（必填闸兜底） */
function normalizeDateTime(v: unknown): string {
  const s = v == null ? '' : String(v).trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) {
    return `${s} 00:00:00`;
  }
  // 分钟级补秒（后端 lenient 三格式同口径；value-format 严格要秒级）
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(s)) {
    return `${s}:00`;
  }
  return /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(s) ? s : '';
}

watch(
  () => props.ask.cardState,
  (s) => {
    if (s === 'pending') {
      nextTick(() => rootEl.value?.focus({ preventScroll: true }));
    }
  },
  { immediate: true }
);

// pending 到达时以模型原始入参为底初始化一次（只 pending 态；run/done 复用已改值只读展示）
watch(
  () => [props.ask.cardState, props.ask.toolInput] as const,
  ([state]) => {
    if (state !== 'pending' || fName.value !== '' || fType.value !== null || touched.name) {
      return;
    }
    const ti = props.ask.toolInput ?? {};
    fName.value = typeof ti.mission_name === 'string' ? ti.mission_name : String(ti.mission_name ?? '');
    const t = Number(ti.mission_type);
    fType.value = t === 1 || t === 2 ? t : null;
    rawBegin.value = String(ti.begin_time ?? '').trim();
    rawEnd.value = String(ti.end_time ?? '').trim();
    fBegin.value = normalizeDateTime(ti.begin_time);
    fEnd.value = normalizeDateTime(ti.end_time);
    fDesc.value = typeof ti.description === 'string' ? ti.description : String(ti.description ?? '');
  },
  { immediate: true }
);

const missingRequired = computed(() => {
  if (!editable.value) {
    return '';
  }
  if (fName.value.trim() === '' && fType.value !== 1 && fType.value !== 2) {
    return '任务名称、任务类型';
  }
  if (fName.value.trim() === '') {
    return '任务名称';
  }
  return fType.value !== 1 && fType.value !== 2 ? '任务类型' : '';
});

/** 五需求批 R4b 智能填表（Q7补充 拍板F）：整段话→任务名称+起止时间。
 *  名称取「名称：X」/引号包裹段，兜底首个片段；日期认 2026-10-20 / 2026年10月20日（可带时分），
 *  按「截止/之前」就近归 end、「开始/起」归 begin；只填空位不覆盖已改值，认不出如实提示。 */
const pasteOpen = ref(false);
const pasteText = ref('');
// 六审回修（m3 P2）：旧提示许诺「10 月 20 号」可识别，但日期正则要求带年份——照写必然识别失败
const pasteHint = ref('识别「名称：…」或引号里的任务名，以及 2026-10-20 / 2026年10月20日 这类日期（须带年份，可带时间）');
function applyPaste() {
  const blob = pasteText.value.trim();
  if (!blob) {
    return;
  }
  const filled: string[] = [];
  // 六审回修（k3 P2）：名称右边界此前只断标点——无标点连写时「开始/截止」与年份跟着被吞进任务名；
  // 惰性量词+前瞻在关键词/年份前收口（名称内部含「开幕式」等词不受影响：lookahead 不满足时惰性继续吃字）
  const mName =
    blob.match(/(?:任务)?名称[：:]\s*([^，,。；;\n]{2,40}?)(?=\s*(?:开始|截止|之前|\d{4}[-/年])|$)/) || blob.match(/[「“]([^」”]{2,40})[」”]/);
  if (mName && fName.value.trim() === '') {
    fName.value = mName[1].trim().slice(0, 200);
    touched.name = true;
    filled.push('名称');
  }
  const dates = [...blob.matchAll(/(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?(?:\s*(\d{1,2})[:：时](\d{1,2})分?)?/g)];
  const norm = (m: RegExpMatchArray, defTime: string): string => {
    const p = (n: number, w = 2) => String(m[n]).padStart(w, '0');
    const hm = m[4] != null ? `${p(4)}:${p(5)}` : defTime;
    return `${p(1, 4)}-${p(2)}-${p(3)} ${hm}:00`;
  };
  // 六审回修（m3 P2）：单字「前/起/开」裸匹配会把「目前/提前/召开/一起」当时间锚——
  // 单字锚要求前邻是空白/标点（多字关键词「截止/之前/开始」不受影响；锚在窗口首字符
  // 时前邻不可见，保守不认，宁可不填不错填）
  const endAnchor = new RegExp('截止|之前|(?<=[\\s，,。；;：:])前');
  const beginAnchor = new RegExp('开始|(?<=[\\s，,。；;：:])起|(?<=[\\s，,。；;：:])开');
  for (const m of dates) {
    const at = m.index ?? 0;
    const ctx = blob.slice(Math.max(0, at - 8), at);
    if (endAnchor.test(ctx) && fEnd.value === '') {
      fEnd.value = norm(m, '23:59');
      touched.end = true;
      filled.push('截止时间');
    } else if (beginAnchor.test(ctx) && fBegin.value === '') {
      fBegin.value = norm(m, '09:00');
      touched.begin = true;
      filled.push('开始时间');
    }
  }
  // 六审回修（glm53 P2）：认出了内容但对应字段都已填（只填空位不覆盖策略）≠"没认出"——
  // 两种情况提示分开口径，避免用户以为粘贴白粘了
  if (filled.length > 0) {
    pasteHint.value = `已填：${filled.join('、')}——请过目确认`;
  } else if (mName || dates.length > 0) {
    pasteHint.value = '认出了名称/日期，但对应字段都已填好——不覆盖你已改的值，可先清空再粘贴';
  } else {
    pasteHint.value = '没认出可填的内容，试试写上「名称：」或具体日期（日期须带年份）';
  }
  if (filled.length > 0) {
    pasteText.value = '';
    pasteOpen.value = false;
  }
}

/** P2-3（glm53 批F 审查）：镜像时间没认出来被置空——时间非必填，不提示就会无感丢失
 *  （任务静默变无限期）；只在待确认态提示，用户重选/改过后 fXxx 有值即消失 */
const timeUnparsedLabel = computed(() => {
  if (!editable.value) {
    return '';
  }
  const parts: string[] = [];
  if (rawBegin.value !== '' && fBegin.value === '') {
    parts.push('开始');
  }
  if (rawEnd.value !== '' && fEnd.value === '') {
    parts.push('截止');
  }
  return parts.join('、');
});

const TYPE_LABEL: Record<number, string> = { 1: '填表类', 2: '追加类' };

function onConfirm() {
  if (missingRequired.value) {
    return; // UI 置灰之外的数据层闸（与 ConfirmCard 退回原因二道闸同款）
  }
  emit('resolve', true, false, {
    formCreate: {
      missionName: fName.value.trim(),
      missionType: fType.value as number,
      beginTime: fBegin.value,
      endTime: fEnd.value,
      description: fDesc.value.trim()
    }
  });
}

/** 改一改（SPEC §2.2）：当前值序列化回注对话——用户自然语言说改哪，模型改完重新出卡 */
function onRevise() {
  const parts = [
    `任务名称「${fName.value.trim() || '（空）'}」`,
    `任务类型 ${fType.value ? TYPE_LABEL[fType.value] : '（空）'}`,
    `开始时间 ${fBegin.value || '（空）'}`,
    `截止时间 ${fEnd.value || '（空）'}`,
    `任务说明「${fDesc.value.trim() || '（空）'}」`
  ];
  emit('revise', `我要改一改这版建任务草稿，当前内容：${parts.join('；')}。先别执行，等我说完要改的地方后按新内容重新出确认卡。`);
}
</script>

<style lang="scss" scoped>
/* 与 ConfirmCard 同族的暖底确认卡；字段行=左标签右控件（EP 真控件，工作面企业风） */
.dcs-form-card {
  border: 1px solid #f3d19e;
  background: #fffcf5;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.6;
  outline: none;
  width: 100%;

  .ch {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 9px 12px;
    border-bottom: 1px solid #f3e3c3;
    b {
      flex: 1;
    }
  }
  .ch-ic {
    color: #b8860b;
    display: inline-flex;
  }
  .ch-why {
    border: 0;
    background: none;
    color: #909399;
    cursor: pointer;
    font-style: normal;
    padding: 0 2px;
  }

  .cb {
    padding: 10px 12px 4px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .frow {
    display: flex;
    gap: 8px;
    align-items: flex-start;
  }
  .k {
    flex: 0 0 64px;
    padding-top: 5px;
    color: #6b5900;
    font-weight: 500;
  }
  .req {
    color: #f56c6c;
  }
  .ctrl {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 8px;
    :deep(.el-input),
    :deep(.el-textarea),
    :deep(.el-date-editor) {
      flex: 1;
    }
    .dt {
      width: 100%;
    }
  }
  .badge {
    flex: 0 0 auto;
    font-size: 11px;
    color: #909399;
    background: #f4f4f5;
    border-radius: 8px;
    padding: 1px 8px;
    &.user {
      color: #409eff;
      background: #ecf5ff;
    }
  }
  /* 波 0：窄屏停靠卡去掉「AI 填/你改过」徽标并把标签列收窄——
     16px 输入字号下，日期值（2026-10-20 09:00）会被徽标挤到只剩半截 */
  @media (max-width: 768px) {
    .frow .k {
      flex-basis: 56px;
    }
    .frow .ctrl .badge {
      display: none;
    }
  }

  .cwhy {
    margin: 0 12px 8px;
    padding: 8px 10px;
    background: #fdf6ec;
    border-radius: 6px;
    color: #7a6a45;
    font-size: 12px;
  }

  .cf {
    padding: 8px 12px 10px;
    border-top: 1px solid #f3e3c3;
  }
  .cwarn {
    color: #e6a23c;
    font-size: 12px;
    margin-bottom: 6px;
  }
  .cf-btns {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    justify-content: flex-end;
  }
  button {
    border-radius: 6px;
    cursor: pointer;
    font-size: 13px;
    /* 窄抽屉（卡宽约 300px）三按钮排不下时整钮换行，禁止按钮文字被拆两行（视觉复核实锤） */
    white-space: nowrap;
  }
  .cf-cancel {
    border: 1px solid #dcdfe6;
    background: #fff;
    color: #606266;
    padding: 5px 14px;
  }
  .cf-mid {
    border: 1px solid #c6e2ff;
    background: #ecf5ff;
    color: #409eff;
    padding: 5px 12px;
  }
  .cf-go {
    border: 0;
    background: #409eff;
    color: #fff;
    padding: 5px 16px;
    &:disabled {
      background: #a0cfff;
      cursor: not-allowed;
    }
  }

  .c-run {
    padding: 9px 12px;
    color: #b8860b;
    .spin {
      display: inline-flex;
      animation: fc-rot 1s linear infinite;
    }
  }
  .c-done {
    padding: 9px 12px;
    color: #67c23a;
    &.cancelled {
      color: #909399;
    }
  }
  &.run,
  &.done {
    .ctrl {
      opacity: 0.75;
    }
  }
  /* 五需求批 R4b 智能填表（拍板F）：与 TemplateCard 同款粘贴拆解区 */
  .spaste {
    display: flex;
    flex-direction: column;
    gap: 6px;
    .sp-toggle {
      align-self: flex-start;
      border: 1px dashed #d9ecff;
      background: #fafcff;
      color: #5a7ba6;
      border-radius: 6px;
      padding: 3px 10px;
      cursor: pointer;
      font-size: 12px;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      &:hover {
        border-color: #409eff;
        color: #409eff;
      }
    }
    .sp-body {
      display: flex;
      flex-direction: column;
      gap: 6px;
      background: #f8fbff;
      border: 1px solid #e4eefb;
      border-radius: 8px;
      padding: 8px;
    }
    .sp-row {
      display: flex;
      align-items: center;
      gap: 8px;
      justify-content: space-between;
    }
    .sp-hint {
      flex: 1;
      font-size: 11px;
      color: #909399;
      line-height: 1.5;
    }
    .sp-go {
      flex: none;
      border: 1px solid #c6e2ff;
      background: #ecf5ff;
      color: #409eff;
      border-radius: 6px;
      padding: 3px 12px;
      cursor: pointer;
      font-size: 12px;
      &:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }
    }
  }
}
@keyframes fc-rot {
  to {
    transform: rotate(360deg);
  }
}
</style>
