<template>
  <div ref="rootEl" class="dcs-form-card tpl dcs-confirm" :class="ask.cardState" tabindex="-1">
    <div class="ch">
      <span class="ch-ic"><IconSpan name="file" /></span>
      <b>确认执行 · 创建模板（AI 已预填，请过目）</b>
      <button class="ch-why" type="button" :aria-expanded="whyOpen" title="为什么问我？" @click="whyOpen = !whyOpen">ⓘ</button>
    </div>

    <div class="cb">
      <!-- 业务反馈批1015c Q15b(拍板A)：猜你要建——轻快模型按本租户近期模板品类+应季
           预设推荐方向；点一个=回填模板名称（字段手编/智能填表/AI 生成均可），
           预设兜底项标「应季」；加载失败静默不显示（锦上添花不挡主流程） -->
      <div v-if="editable && (recLoading || recs.length)" class="recrow">
        <span class="rec-k"><IconSpan name="sparkles" /> 猜你要建</span>
        <div v-if="recLoading" class="rec-loading">正在结合你们最近建的模板想推荐…</div>
        <div v-else class="rec-chips">
          <button
            v-for="r in recs"
            :key="r.name"
            class="rec-chip"
            :class="{ preset: r.preset }"
            type="button"
            :title="`${r.reason}（点击填入模板名称，字段可再改）`"
            @click="applyRec(r)"
          >
            {{ r.name }}<i v-if="r.preset" class="rec-p">应季</i>
          </button>
        </div>
      </div>

      <div class="frow">
        <label class="k">模板名称<span class="req">＊</span></label>
        <div class="ctrl">
          <el-input v-model="fName" maxlength="100" show-word-limit placeholder="必填" :disabled="!editable" @change="touched.name = true" />
          <span v-if="fName.trim() || touched.name" class="badge" :class="{ user: touched.name }">{{ touched.name ? '你改过' : 'AI 填' }}</span>
        </div>
      </div>

      <div class="frow">
        <label class="k">模板说明</label>
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
          <span v-if="fDesc.trim() || touched.desc" class="badge" :class="{ user: touched.desc }">{{ touched.desc ? '你改过' : 'AI 填' }}</span>
        </div>
      </div>

      <div class="frow">
        <label class="k">可见性</label>
        <div class="ctrl">
          <el-radio-group v-model="fVis" :disabled="!editable" @change="touched.vis = true">
            <el-radio value="PRIVATE">私有（仅自己可见）</el-radio>
            <el-radio value="PUBLIC" :disabled="!adminLike">公共{{ adminLike ? '' : '（仅管理员）' }}</el-radio>
          </el-radio-group>
          <span class="badge" :class="{ user: touched.vis }">{{ touched.vis ? '你改过' : 'AI 填' }}</span>
        </div>
      </div>

      <div v-if="editable && visDowngraded" class="vwarn">
        AI 建的是公共模板，但公共模板仅管理员可建——已按私有创建；确需公共请到「模板中心」修改可见性。
      </div>

      <!-- 字段清单（可增删改；SELECT 才显示选项行） -->
      <div class="frow flist-head">
        <label class="k">字段清单<span class="req">＊</span></label>
        <div class="ctrl col">
          <!-- 业务批(1015) Q14：字段行与它的选项行包进同一个 frowwrap——原实现是两个并列
               v-for（先画全部字段行、再把所有单选项甩到清单末尾），选项脱离所属字段变成
               "两行没头没尾的灰行"（用户红圈实锤）；v-if 同步挪到包裹层，不再产 0 高空 div -->
          <div v-for="(f, i) in rows" :key="i" class="frowwrap">
            <div class="frowline" :class="{ bad: rowBad(i) }">
              <el-input
                v-model="f.name"
                :placeholder="`字段${i + 1} 名称`"
                maxlength="50"
                :disabled="!editable"
                class="fn"
                @change="touched.fields = true"
              />
              <el-select v-model="f.type" :disabled="!editable" class="ft" @change="onTypeChange(f)">
                <el-option v-for="t in TYPES" :key="t.v" :label="t.label" :value="t.v" />
              </el-select>
              <el-checkbox v-model="f.required" :disabled="!editable" label="必填" @change="touched.fields = true" />
              <!-- 业务反馈批1015c Q15a(拍板A)：AI 预填下拉字段但选项为空时不再画占位空行
                   （用户实锤"两行空白"的残因）——收进显式的「＋ 选项」入口，点了才展开输入框 -->
              <button
                v-if="editable && f.type === 'SELECT' && !f.options && !optsOpen.has(f)"
                class="fopt-add"
                type="button"
                title="给这个下拉字段填选项：用 | 分隔，如：男|女"
                @click="optsOpen.add(f)"
              >
                ＋ 选项
              </button>
              <button v-if="editable && rows.length > 1" class="fdel" type="button" title="删掉这个字段" @click="delRow(i)">✕</button>
            </div>
            <el-input
              v-if="f.type === 'SELECT' && (f.options || optsOpen.has(f))"
              v-model="f.options"
              placeholder="选项（竖线分隔，如：男|女）"
              :disabled="!editable"
              class="fopt"
              @change="touched.fields = true"
            />
          </div>
          <button v-if="editable" class="fadd" type="button" :disabled="rows.length >= 30" @click="addRow">＋ 加一个字段</button>
        </div>
      </div>

      <!-- 五需求批 R4b 智能填表（Q7补充 拍板F）：整段粘贴→启发式拆字段行 -->
      <div v-if="editable" class="spaste">
        <button class="sp-toggle" type="button" :aria-expanded="pasteOpen" @click="pasteOpen = !pasteOpen">
          <IconSpan name="clipboard" /> 智能填表：整段粘贴自动拆字段
        </button>
        <div v-if="pasteOpen" class="sp-body">
          <el-input
            v-model="pasteText"
            type="textarea"
            :rows="3"
            maxlength="1000"
            placeholder="把要收集的信息整段粘进来，例如：姓名、工号、部门、办公区和房间号、联系电话、本人照片"
          />
          <div class="sp-row">
            <span class="sp-hint">按顿号/逗号/换行拆字段，词面带「日期/照片/是否/数量」等自动选类型；拆错可当场改</span>
            <button class="sp-go" type="button" :disabled="!pasteText.trim()" @click="applyPaste">拆解并填充</button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="whyOpen" class="cwhy">
      模板决定后续任务要收集什么信息。按你设置的「每次都问」，执行前需要你确认；确认与取消都会留审计记录，取消不算过错。
      模板建好后在「模板中心」可继续编辑，也能用它快速创建同款任务。
    </div>

    <div v-if="editable" class="cf">
      <div v-if="missingRequired" class="cwarn">必填缺失：{{ missingRequired }}——补齐后才能执行</div>
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
      <template v-if="ask.cancelled">已取消，未创建任何模板</template>
      <template v-else><IconSpan name="check" /> 已执行完成（结果见下方回答）</template>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 模板表单卡（五需求批 R4a，2026-10-13）：AI 预填模板名+字段清单 → 用户过目增删改 → 三出路。
 * 与 FormCard（建任务）同族同三出路（确认/改一改回注对话/取消零落库）；差异面=字段清单
 * 动态行（名称+类型+必填+SELECT 选项行），editedArgs 走 formTemplate 四键整体替换
 * （fieldsJson 由本卡序列化，后端 @ToolParam fields_json 同名接收）。
 * PUBLIC 仅管理员可选（后端同样强制归一，前端置灰只是提示——数据层闸在后端）。
 */
import type { HitlAsk, HitlEdits } from './useAssistant';
import IconSpan from './IconSpan.vue';
import { pushToast } from './toastStore';
import { useUserStore } from '@/store/modules/user';
import { getTemplateRecommendations, type TemplateRecommendItem } from '@/api/dcs/assistant';

const props = defineProps<{ ask: HitlAsk }>();
const emit = defineEmits<{ resolve: [approved: boolean, remember?: boolean, edits?: HitlEdits]; revise: [message: string] }>();

const userStore = useUserStore();
const whyOpen = ref(false);
const rootEl = ref<HTMLElement | null>(null);
const editable = computed(() => props.ask.cardState === 'pending');
/** PUBLIC 可选性：仅超管通配串放开（租户管理员走模板中心改，卡面保守锁）；
 *  后端 visibility 归一是权威闸，卡面锁只是提前提示 */
const adminLike = computed(() => (userStore.permissions ?? []).includes('*:*:*'));

interface FieldRow {
  name: string;
  type: string;
  required: boolean;
  options: string;
}
const TYPES: Array<{ v: string; label: string }> = [
  { v: 'STRING', label: '文本' },
  { v: 'NUMBER', label: '数字' },
  { v: 'DATE', label: '日期' },
  { v: 'BOOLEAN', label: '是/否' },
  { v: 'SELECT', label: '单选' },
  { v: 'USER', label: '选人' },
  { v: 'ATTACHMENT', label: '附件' },
  { v: 'ORG_CASCADE', label: '组织级联' }
];

const fName = ref('');
const fDesc = ref('');
const fVis = ref<'PRIVATE' | 'PUBLIC'>('PRIVATE');
const rows = ref<FieldRow[]>([]);
/** 业务反馈批1015c Q15a：SELECT 空选项不画占位行——用户显式展开的选项输入框记这里
 *  （按行对象引用记键，delRow 换索引不错位；AI 预填的空选项不进此集合=收进「＋ 选项」入口） */
const optsOpen = reactive(new Set<FieldRow>());
const touched = reactive({ name: false, desc: false, vis: false, fields: false });
/** PUBLIC 在非管理员卡面上被锁掉时，AI 原始 PUBLIC 入参的归一提示（防无感降级） */
const visDowngraded = ref(false);

/** 业务反馈批1015c Q15b(拍板A)：猜你要建——pending 时拉一次轻模型推荐（贴本租户近期
 *  模板+应季预设）；失败/超时静默藏区（后端本就回退预设清单，这里再兜底空数组） */
const recs = ref<TemplateRecommendItem[]>([]);
/** 推荐加载态（三审 P2）：后端同步生成最长 30s，无加载态=用户干等后内容突兀出现 */
const recLoading = ref(false);
watch(
  editable,
  (v) => {
    if (!v || recs.value.length) return;
    recLoading.value = true;
    getTemplateRecommendations()
      .then((res) => {
        recs.value = (res.data ?? []).slice(0, 5);
      })
      .catch(() => {
        // 推荐是锦上添花，失败不打扰
      })
      .finally(() => {
        recLoading.value = false;
      });
  },
  { immediate: true }
);
function applyRec(r: TemplateRecommendItem) {
  fName.value = r.name;
  touched.name = true;
  pushToast(`已填入「${r.name}」——字段可在下方直接编辑，或用智能填表/AI 生成`, 'info');
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

function normType(v: unknown): string {
  const s = String(v ?? '').toUpperCase();
  return TYPES.some((t) => t.v === s) ? s : 'STRING';
}

// pending 到达时以模型原始入参为底初始化一次
watch(
  () => [props.ask.cardState, props.ask.toolInput] as const,
  ([state]) => {
    if (state !== 'pending' || rows.value.length > 0 || touched.name) {
      return;
    }
    const ti = props.ask.toolInput ?? {};
    fName.value = typeof ti.template_name === 'string' ? ti.template_name : String(ti.template_name ?? '');
    fDesc.value = typeof ti.description === 'string' ? ti.description : String(ti.description ?? '');
    const vis = String(ti.visibility ?? 'PRIVATE').toUpperCase() === 'PUBLIC' ? 'PUBLIC' : 'PRIVATE';
    visDowngraded.value = vis === 'PUBLIC' && !adminLike.value;
    fVis.value = vis === 'PUBLIC' && !adminLike.value ? 'PRIVATE' : vis;
    const list: FieldRow[] = [];
    try {
      const arr = typeof ti.fields_json === 'string' ? JSON.parse(ti.fields_json) : (ti.fields_json ?? []);
      if (Array.isArray(arr)) {
        for (const it of arr) {
          const o = it as Record<string, unknown>;
          const nm = String(o.name ?? '').slice(0, 50);
          // 业务批(1015) Q14：模型脏数据缺 name 的元素不渲染（原来照画"名称空白的废行"）
          if (!nm.trim()) {
            continue;
          }
          list.push({
            name: nm,
            type: normType(o.type),
            required: o.required === true || o.required === 'true' || o.required === 1,
            options: String(o.options ?? '')
          });
        }
      }
    } catch {
      /* 非法 JSON → 空清单，必填闸兜底让用户补 */
    }
    rows.value = list.length > 0 ? list : [newRow()];
  },
  { immediate: true }
);

function newRow(): FieldRow {
  return { name: '', type: 'STRING', required: false, options: '' };
}
function addRow() {
  rows.value.push(newRow());
  touched.fields = true;
}
function delRow(i: number) {
  rows.value.splice(i, 1);
  touched.fields = true;
}
function onTypeChange(f: FieldRow) {
  if (f.type !== 'SELECT') {
    f.options = '';
  } else if (!f.options) {
    // 业务反馈批1015c Q15a：用户手动切到下拉=明确要填选项，立即展开输入框（AI 预填的空选项不展开）
    optsOpen.add(f);
  }
  touched.fields = true;
}

/** 五需求批 R4b 智能填表（Q7补充 拍板F）：整段文字→字段行。
 *  分隔=顿号/逗号/分号/句读/换行/空白/斜杠竖线；类型按词面启发式推断
 *  （日期→DATE、照片附件→ATTACHMENT、是否→BOOLEAN、数量体温成绩→NUMBER、
 *  性别职级等级→SELECT 带预设选项），推不出的落文本——错了用户可当场改，不拦确认。 */
const pasteOpen = ref(false);
const pasteText = ref('');
const TYPE_HINTS: Array<[RegExp, string]> = [
  [/日期|时间|生日|报到|截止/, 'DATE'],
  [/照片|图片|截图|附件|文件|证明|扫描件/, 'ATTACHMENT'],
  [/是否|有无/, 'BOOLEAN'],
  [/数量|人数|金额|体温|成绩|分数|次数|年龄|重量|身高|工龄/, 'NUMBER']
];
const SELECT_PRESETS: Record<string, string> = { 性别: '男|女', 职级: '初级|中级|高级', 血型: 'A|B|AB|O' };
function guessField(name: string): FieldRow {
  for (const [re, type] of TYPE_HINTS) {
    if (re.test(name)) {
      return { name, type, required: true, options: '' };
    }
  }
  const preset = SELECT_PRESETS[name.trim()];
  if (preset) {
    return { name, type: 'SELECT', required: true, options: preset };
  }
  return { name, type: 'STRING', required: true, options: '' };
}
function applyPaste() {
  // 六审回修（m3 P2）：删掉「和」分隔——含「和」的字段名（如「总和信息」「和解协议号」）
  // 会被从中间劈开；要拆开写的人自然会加顿号，宁少拆不错拆
  const tokens = pasteText.value
    .replace(/^(要|需要)?收集?(的)?(信息)?(如下)?[：:]?/, '')
    .split(/[、，,；;。．\n\r\t／/｜|·]+|\s+/)
    .map((t) => t.replace(/[：:。,，、；;]+$/, '').trim())
    .filter((t) => t.length > 0 && t.length <= 20);
  const uniq: string[] = [];
  for (const t of tokens) {
    if (!uniq.includes(t)) {
      uniq.push(t);
    }
  }
  if (uniq.length === 0) {
    // 六审回修（glm53 P2）：空拆解静默无反馈——用户点了「拆解并填充」没有任何反应，
    // 分不清是没识别还是按钮坏了
    pushToast('没拆解出任何字段——请用顿号/逗号/换行分隔各项', 'info');
    return;
  }
  // 已有名子的行保留，完全空的行让位给拆解结果（总量守 30 上限）
  const keep = rows.value.filter((f) => f.name.trim() !== '');
  // 五审回修（step5 P2-11）：拆解项与已有行重名时不再重复入列（重名行加出去只留一份）
  const existing = new Set(keep.map((f) => f.name.trim()));
  const fresh = uniq.filter((t) => !existing.has(t));
  const room = Math.max(0, 30 - keep.length);
  if (fresh.length > room) {
    // 六审回修（glm53 P2）：超 30 行上限被静默丢弃——至少告知丢了几个、去哪补
    pushToast(`已达 30 字段上限，只保留前 ${room} 项，其余 ${fresh.length - room} 项未加入`, 'info');
  }
  rows.value = [...keep, ...fresh.slice(0, room).map(guessField)];
  touched.fields = true;
  pasteText.value = '';
  pasteOpen.value = false;
}
function rowBad(i: number): boolean {
  const f = rows.value[i];
  return f.name.trim() === '' || (f.type === 'SELECT' && f.options.trim() === '');
}

const missingRequired = computed(() => {
  if (!editable.value) {
    return '';
  }
  const miss: string[] = [];
  if (fName.value.trim() === '') {
    miss.push('模板名称');
  }
  // 自查回修(glm53 P2)：脏行全被过滤后 rows 可能为空——空清单=没东西可采集,
  // 不能放行出零字段模板；此前空名行靠 rowBad 挡，过滤后这道闸就漏了
  if (rows.value.length === 0) {
    miss.push('字段清单');
  }
  const bad = rows.value.filter((f, i) => rowBad(i)).length;
  if (bad > 0) {
    miss.push(
      `${bad} 个字段缺${rows.value.some((f) => f.type === 'SELECT' && f.name.trim() !== '' && f.options.trim() === '') ? '名称或选项' : '名称'}`
    );
  }
  return miss.join('、');
});

function onConfirm() {
  if (missingRequired.value) {
    return;
  }
  const fieldsJson = JSON.stringify(
    rows.value.map((f) => ({
      name: f.name.trim(),
      type: f.type,
      required: f.required,
      ...(f.type === 'SELECT' ? { options: f.options.trim() } : {})
    }))
  );
  emit('resolve', true, false, {
    formTemplate: {
      templateName: fName.value.trim(),
      fieldsJson,
      description: fDesc.value.trim(),
      visibility: fVis.value
    }
  });
}

function onRevise() {
  const fieldsText = rows.value
    .map(
      (f) =>
        `「${f.name.trim() || '（空）'}」${TYPES.find((t) => t.v === f.type)?.label ?? f.type}${f.required ? '/必填' : ''}${f.type === 'SELECT' ? `/选项:${f.options || '（空）'}` : ''}`
    )
    .join('、');
  emit(
    'revise',
    `我要改一改这版模板草稿，当前内容：模板名称「${fName.value.trim() || '（空）'}」；字段：${fieldsText}；说明「${fDesc.value.trim() || '（空）'}」。先别执行，等我说完要改的地方后按新内容重新出确认卡。`
  );
}
</script>

<style lang="scss" scoped>
/* FormCard 同族暖底卡；字段清单=动态行区（窄抽屉 300px 内换行排布） */
.dcs-form-card.tpl {
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
    :deep(.el-textarea) {
      flex: 1;
    }
    &.col {
      flex-direction: column;
      align-items: stretch;
      gap: 6px;
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
  .flist-head > .k {
    padding-top: 3px;
  }
  .frowwrap {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .frowline {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
    .fn {
      flex: 1 1 96px;
      min-width: 0;
    }
    .ft {
      flex: 0 0 86px;
      width: 86px;
    }
    .fdel {
      flex: none;
      border: 0;
      background: #fde2e2;
      color: #f56c6c;
      border-radius: 6px;
      width: 22px;
      height: 22px;
      cursor: pointer;
      line-height: 1;
    }
    &.bad :deep(.el-input__wrapper),
    &.bad :deep(.el-select__wrapper) {
      box-shadow: 0 0 0 1px #f56c6c inset;
    }
  }
  .fopt {
    /* 业务批(1015) Q14：选项行内联在所属字段下方——缩进+浅色暗示从属（原来是清单尾部的游离行） */
    margin-left: 18px;
    width: calc(100% - 18px);
  }
  .fopt-add {
    /* 业务反馈批1015c Q15a：空选项的显式展开入口（浅色小 chip，不再画"没头没尾的空白行"） */
    flex: none;
    border: 1px dashed #c0c6cf;
    border-radius: 6px;
    background: #fafbfc;
    color: #7a8494;
    font-size: 11.5px;
    padding: 2px 8px;
    cursor: pointer;
    &:hover {
      color: var(--el-color-primary);
      border-color: var(--el-color-primary);
    }
  }
  .vwarn {
    margin: 0 12px;
    color: #e6a23c;
    font-size: 12px;
  }
  .fadd {
    align-self: flex-start;
    border: 1px dashed #c6e2ff;
    background: #ecf5ff;
    color: #409eff;
    border-radius: 6px;
    padding: 3px 10px;
    cursor: pointer;
    font-size: 12px;
    &:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
  }
  /* 业务反馈批1015c Q15b：猜你要建推荐区——一行方向 chips，点选填名称 */
  .recrow {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 8px 10px;
    border-radius: 8px;
    background: var(--el-fill-color-lighter, #f8fbff);
    .rec-k {
      font-size: 12px;
      font-weight: 600;
      color: var(--dcs-text-secondary, #5a7ba6);
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }
    .rec-loading {
      font-size: 12px;
      color: var(--dcs-text-secondary, #5a7ba6);
    }
    .rec-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .rec-chip {
      border: 1px solid var(--el-color-primary-light-7, #d9ecff);
      background: var(--el-color-primary-light-9, #fafcff);
      color: var(--dcs-text-regular, #33456b);
      border-radius: 999px;
      padding: 3px 10px;
      font-size: 12px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      &:hover {
        border-color: var(--el-color-primary, #409eff);
        color: var(--el-color-primary, #409eff);
      }
      .rec-p {
        font-style: normal;
        font-size: 10px;
        line-height: 1;
        padding: 2px 4px;
        border-radius: 4px;
        background: var(--el-color-success-light-8, #e8f5e9);
        color: var(--el-color-success, #67c23a);
      }
      &.preset {
        border-style: dashed;
      }
    }
  }
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
      animation: tc-rot 1s linear infinite;
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
}
@keyframes tc-rot {
  to {
    transform: rotate(360deg);
  }
}
</style>
