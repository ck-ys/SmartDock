<template>
  <div ref="rootEl" class="dcs-elicit" tabindex="-1" role="dialog" :aria-label="`外部工具反问（${card.serverName || '未知服务'}）`">
    <div class="ch">
      <span class="ch-ic"><IconSpan name="help-circle" /></span>
      <b>外部工具反问</b>
      <span class="ch-srv">{{ card.serverName }}</span>
      <span class="ch-ttl" :class="{ urgent: remainMs < 60_000 }" title="超时未作答将自动关闭（该工具收不到答案）">{{
        remainText
      }}</span>
    </div>

    <p class="cb">{{ card.message || '这个外部工具需要你补充一点信息才能继续。' }}</p>

    <!-- requestedSchema 驱动动态表单：v1 支持 string / number / boolean / enum，
         其余类型或无 schema 降级 JSON 文本域（server 自校验，错误经工具结果回到模型转述） -->
    <div v-if="fields.length > 0" class="ef">
      <label v-for="f in fields" :key="f.key" class="erow">
        <span class="ek">
          {{ f.label }}<i v-if="f.required" class="req">*</i>
          <em v-if="f.desc" class="ed">{{ f.desc }}</em>
        </span>
        <select v-if="f.type === 'enum'" v-model="form[f.key]" class="ev">
          <option v-for="o in f.options" :key="String(o)" :value="o">{{ o }}</option>
        </select>
        <input v-else-if="f.type === 'boolean'" v-model="form[f.key]" type="checkbox" class="ev cbx" />
        <input
          v-else-if="f.type === 'number'"
          v-model="form[f.key]"
          type="number"
          class="ev"
          :placeholder="f.placeholder"
        />
        <input v-else v-model="form[f.key]" type="text" class="ev" :placeholder="f.placeholder" />
      </label>
    </div>
    <label v-else class="ef json">
      <span class="ek">按 JSON 补充回答</span>
      <textarea
        v-model="jsonText"
        class="ev"
        rows="3"
        placeholder='例如 {"city": "北京"}'
        spellcheck="false"
      />
      <span v-if="jsonError" class="ew">{{ jsonError }}</span>
    </label>

    <div class="cf-btns">
      <button class="cf-cancel" title="关闭反问（该工具收到「已关闭」继续收尾）" @click="submit('cancel')">
        关闭
      </button>
      <button
        class="cf-mid"
        title="不提供这些信息（该工具收到「拒绝」，由它决定如何收尾）"
        @click="submit('decline')"
      >
        不提供了
      </button>
      <button
        class="cf-go"
        :class="{ busy }"
        :disabled="busy || !!jsonError || missingRequired"
        :title="missingRequired ? `必填缺失：${missingRequired}` : '提交回答，工具继续执行'"
        @click="submit('accept')"
      >
        提交回答
      </button>
    </div>
    <p v-if="errText" class="ew foot">{{ errText }}</p>
  </div>
</template>

<script setup lang="ts">
/**
 * MCP elicitation 反问卡（G3-A1）：外部工具在执行中反问（server 在飞挂起，run 保持
 * RUNNING），用户作答后走独立端点 /elicitation/answer 回填三态——与权限确认卡
 * (ConfirmCard) 完全分轨：不经 interruptId、不进 RunAgentInput.resume。
 *
 * 已知取舍（调研口径）：刷新丢卡（H1 只恢复权限 HITL 卡）——卡面倒计时归零本地收卡，
 * 后端超时 CANCEL 兜底，审计留 ELICIT_TIMEOUT；schema v1 只渲染
 * string/number/boolean/enum，复杂 schema 降级 JSON 文本域由 server 自校验。
 */
import type { AssistantElicitationCard } from '@/api/dcs/assistant';
import { answerElicitation } from '@/api/dcs/assistant';
import IconSpan from './IconSpan.vue';

const props = defineProps<{ card: AssistantElicitationCard; threadId: string }>();
const emit = defineEmits<{ done: [id: string] }>();

type FieldType = 'string' | 'number' | 'boolean' | 'enum';
interface FieldDef {
  key: string;
  label: string;
  type: FieldType;
  required: boolean;
  desc?: string;
  options?: Array<string | number>;
  placeholder?: string;
}

const rootEl = ref<HTMLElement | null>(null);
const busy = ref(false);
const errText = ref('');
const jsonText = ref('');
const jsonError = ref('');
const form = reactive<Record<string, string | number | boolean>>({});
const nowMs = ref(Date.now());

/** schema.properties → 字段定义（纯展示层解析：认不出的类型降级整体 JSON 模式） */
const fields = computed<FieldDef[]>(() => {
  const props_ = (cardSchema.value.properties ?? {}) as Record<string, Record<string, unknown>>;
  return Object.entries(props_)
    .map(([key, def]) => {
      const enumVals = Array.isArray(def.enum) ? def.enum.filter((v) => ['string', 'number'].includes(typeof v)) : null;
      const t: FieldType | null = enumVals && enumVals.length > 0
        ? 'enum'
        : def.type === 'string' ? 'string'
        : def.type === 'number' || def.type === 'integer' ? 'number'
        : def.type === 'boolean' ? 'boolean'
        : null;
      if (t === null) return null;
      const f: FieldDef = {
        key,
        label: typeof def.title === 'string' && def.title ? def.title : key,
        type: t,
        required: Array.isArray(reqKeys.value) && reqKeys.value.includes(key)
      };
      if (typeof def.description === 'string' && def.description) f.desc = def.description;
      if (enumVals) f.options = enumVals as Array<string | number>;
      return f;
    })
    .filter((f): f is FieldDef => f !== null)
    .slice(0, 8);
});

const cardSchema = computed(() => {
  const s = props.card.requestedSchema as { properties?: unknown; required?: unknown };
  return { properties: (s?.properties ?? null) as Record<string, Record<string, unknown>> | null, required: s?.required };
});
const reqKeys = computed(() => cardSchema.value.required);

watch(
  fields,
  (fs) => {
    for (const f of fs) {
      if (!(f.key in form)) {
        form[f.key] = f.type === 'boolean' ? false : f.type === 'enum' && f.options?.length ? f.options[0] : '';
      }
    }
  },
  { immediate: true }
);

watch(jsonText, (v) => {
  if (!v.trim()) {
    jsonError.value = '';
    return;
  }
  try {
    const parsed = JSON.parse(v);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      jsonError.value = '要一个 JSON 对象（{…}），不是数组或纯文本';
    } else {
      jsonError.value = '';
    }
  } catch {
    jsonError.value = 'JSON 格式不对，检查引号/逗号';
  }
});

const missingRequired = computed(() => {
  const miss = fields.value.filter((f) => f.required && (form[f.key] === '' || form[f.key] == null));
  return miss.length ? miss.map((f) => f.label).join('、') : '';
});

const remainMs = computed(() => Math.max(0, props.card.expiresAt - nowMs.value));
const remainText = computed(() => {
  const s = Math.ceil(remainMs.value / 1000);
  return remainMs.value <= 0 ? '已超时' : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')} 后超时`;
});

let timer: ReturnType<typeof setInterval> | null = null;
onMounted(() => {
  rootEl.value?.focus({ preventScroll: true });
  timer = setInterval(() => {
    nowMs.value = Date.now();
    if (remainMs.value <= 0) {
      // 超时收卡（后端同刻 CANCEL 兜底；本端点不收已过期卡也给人话）
      emit('done', props.card.id);
    }
  }, 1000);
});
onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});

async function submit(action: 'accept' | 'decline' | 'cancel') {
  if (busy.value) return;
  busy.value = true;
  errText.value = '';
  let content: Record<string, unknown> | undefined;
  if (action === 'accept') {
    if (fields.value.length > 0) {
      content = {};
      for (const f of fields.value) {
        const v = form[f.key];
        if (v === '' || v == null) continue;
        content[f.key] = f.type === 'number' ? Number(v) : v;
      }
    } else if (jsonText.value.trim()) {
      content = JSON.parse(jsonText.value) as Record<string, unknown>;
    }
  }
  try {
    const res = await answerElicitation(props.threadId, props.card.id, action, content);
    if (res && typeof res === 'object' && 'code' in res && (res as { code: number }).code !== 200) {
      errText.value = String((res as { msg?: string }).msg ?? '提交失败，稍后再试');
      busy.value = false;
      return;
    }
    emit('done', props.card.id);
  } catch (e) {
    errText.value = '网络异常，稍后再试（超时前仍可提交）';
    busy.value = false;
  }
}
</script>

<style lang="scss" scoped>
/* 视觉语言对齐 ConfirmCard（dcs-confirm 家族）：同卡头/按钮/字段行基调 */
.dcs-elicit {
  border: 1px solid var(--dcs-border, #dcdfe6);
  border-radius: 10px;
  background: var(--dcs-card-bg, #fff);
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  &:focus-visible {
    outline: 2px solid var(--el-color-primary-light-5);
    outline-offset: -2px;
  }
}
.ch {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  b {
    font-size: 13px;
  }
}
.ch-ic {
  display: inline-flex;
  color: var(--el-color-warning);
}
.ch-srv {
  font-size: 11px;
  color: var(--dcs-text-secondary, #909399);
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 8px;
  padding: 1px 8px;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ch-ttl {
  margin-left: auto;
  font-size: 11px;
  color: var(--dcs-text-secondary, #909399);
  font-variant-numeric: tabular-nums;
  &.urgent {
    color: var(--el-color-danger);
  }
}
.cb {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--dcs-text-primary, #303133);
  word-break: break-word;
}
.ef {
  display: flex;
  flex-direction: column;
  gap: 8px;
  &.json .ev {
    font-family: Consolas, Menlo, monospace;
  }
}
.erow,
.json {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.ek {
  font-size: 12px;
  color: var(--dcs-text-secondary, #909399);
  .req {
    color: var(--el-color-danger);
    font-style: normal;
    margin-left: 2px;
  }
  .ed {
    font-style: normal;
    margin-left: 6px;
    opacity: 0.75;
  }
}
.ev {
  border: 1px solid var(--dcs-border, #dcdfe6);
  border-radius: 6px;
  padding: 5px 8px;
  font-size: 13px;
  background: var(--el-fill-color-blank, #fff);
  color: var(--dcs-text-primary, #303133);
  &:focus-visible {
    outline: none;
    border-color: var(--el-color-primary);
  }
}
textarea.ev {
  resize: vertical;
}
.cbx {
  width: 16px;
  height: 16px;
  justify-self: start;
}
.cf-btns {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  flex-wrap: wrap;
  button {
    border-radius: 7px;
    border: 1px solid var(--dcs-border, #dcdfe6);
    font-size: 12.5px;
    padding: 5px 12px;
    cursor: pointer;
    background: var(--el-fill-color-blank, #fff);
    &:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }
  }
  .cf-go {
    background: var(--el-color-primary);
    border-color: var(--el-color-primary);
    color: #fff;
    &:not(:disabled):hover {
      background: var(--el-color-primary-light-3);
    }
    &.busy {
      opacity: 0.6;
    }
  }
  .cf-mid {
    color: var(--el-color-primary);
    border-color: var(--el-color-primary-light-5);
    background: var(--el-color-primary-light-9, #ecf5ff);
  }
  .cf-cancel {
    color: var(--dcs-text-secondary, #909399);
  }
}
.ew {
  color: var(--el-color-danger);
  font-size: 12px;
  margin: 0;
  &.foot {
    text-align: right;
  }
}
</style>
