<template>
  <!-- Q4 乙（2026-11-09 拍板）：抽屉打开时铺一层浅遮罩——不是挡内容，是告诉眼睛「你刚打开了
       一个东西」；点遮罩=收起。放 transition 外：抽屉的滑入靠 transform，塞在里面的 fixed
       会被它当包含块，动画那一帧遮罩会跟着错位。 -->
  <div v-if="sheetMode" class="sp-sheet-mask" @click="collapseMini()" />
  <transition name="dcs-sp">
    <!-- Q7（ZCode 对齐，2026-10-10）：胶囊与展开是同一元素的两个形态——capsule 常驻做
         透明度淡切（ZCode mini 行常驻子元素），full-body 常驻做 max-height+opacity 过渡；
         原 v-if 双分支卸载重建=宽度/高度/内容三重跳变，是「动画怪」的根因 -->
    <div
      v-if="turn"
      ref="panelRoot"
      class="stat-panel"
      :class="panelCls"
      role="status"
      @mouseenter="onPanelEnter"
      @mouseleave="onPanelLeave"
    >
      <button
        class="capsule"
        :class="{ ghost: !mini }"
        :tabindex="mini ? 0 : -1"
        :title="mini ? capsuleTitle : undefined"
        :aria-label="mini ? capsuleTitle : undefined"
        @click="expandMini()"
      >
        <span class="spdot" :class="dotCls" />
        <!-- Q1 乙 + Q2 甲（2026-11-09 拍板）：手机档收起态写状态词（「工作中/已收尾」）——
             手机没悬停，摘要进 title 和展开后的抽屉，眼前这几个字只回答「它是什么」 -->
        <span v-if="narrowMini" class="c-state">{{ stateWord }}</span>
        <!-- Q1 乙（2026-11-09 拍板）：收起态=摘要本身（ZCode 原样），步骤数进 title -->
        <span v-else class="c-last capx">{{ capsuleSummary }}</span>
      </button>
      <div class="full-body" :class="{ fold: mini }">
        <!-- Q5 乙：抽屉把手——按住下滑超过 56px 收起（「有把手能拖」，只对手机档生效） -->
        <span v-if="sheetMode" class="sheet-grip" aria-hidden="true" @pointerdown="onGripDown" />
        <!-- Q4 乙（2026-11-09 拍板）：标题行回答「这是什么」——遮罩负责「你打开了东西」 -->
        <div v-if="sheetMode" class="sheet-ttl">这一轮做了什么</div>
        <!-- DP-3 原型 sph：waiting 态点头=跳到确认卡；展开态只留收起钮（原型 L398/L403：btn-max 仅 mini 显示） -->
        <div
          class="sph"
          :class="{ clickable: turn.phase === 'waiting' }"
          :title="turn.phase === 'waiting' ? '点这里跳到确认卡' : undefined"
          @click="onSphClick"
        >
          <span class="spdot" :class="dotCls" />
          <span class="sp-name">{{ stateWord }}</span>
          <!-- k3 P2-4 回修：标注「步骤」——与下方「任务清单 N/N」（todo 口径）相邻，不标注读不出两数各是什么 -->
          <span class="cnt">步骤 {{ doneSteps }}/{{ turn.steps.length || 1 }}</span>
          <span v-if="sphLast" class="c-last">{{ sphLast }}</span>
          <span v-if="durText" class="sp-dur">{{ durText }}</span>
          <span class="sp-act">
            <button class="btn-min" title="收起为胶囊" aria-label="收起为胶囊" @click.stop="collapseMini()">
              <IconSpan name="chevron-down" :size="13" />
            </button>
          </span>
        </div>
        <!-- Q7 甲（2026-11-07 拍板，照 ZCode 的摘要链）：一句话说「现在最要紧的是什么」——
             优先级：在跑的步骤 → 计划进度 → 未完成待办 → 来源 → 文件 → 收尾。
             点这行不做事，只读；要细节去下面的分区。 -->
        <div class="sp-sum" :title="summaryText">{{ summaryText }}</div>

        <!-- Q7 甲：分区清单（计划 / 来源 / 文件 / 动作）——行可点，详情长在这行下面（Q3 乙）；
             同一项再点收起，换一项则替换（不叠 tab 条，ZCode 的多 tab 属于功能变多之后的丙案） -->
        <!-- Q2 乙（2026-11-09 拍板）：分区头可折叠；步骤并进「计划」——它们本来就在说同一件事
             （这轮干了什么），分成两块反而要来回对照。没有计划时这一区就叫步骤。 -->
        <div v-if="planRows.length || turn.steps.length" class="spsec">
          <button class="spsec-hd" type="button" :aria-expanded="!folded.plan" @click="toggleFold('plan')">
            <IconSpan name="flag" :size="12" /> 计划
            <span class="spsec-n">{{ planRows.length || turn.steps.length }}</span>
            <IconSpan class="spsec-chev" name="chevron-down" :size="12" />
          </button>
          <div v-show="!folded.plan" class="spsec-bd">
            <template v-for="r in planRows" :key="r.key">
              <button
                type="button"
                class="spsec-row"
                :class="{ on: detailKey === r.key }"
                :data-detail-key="r.key"
                @click="openDetail(r.key)"
              >
                <span class="kring" :class="'k-' + r.status"><IconSpan v-if="r.status === 'err'" name="x" :size="10" /><IconSpan v-else-if="r.status !== 'run'" name="check" :size="11" /></span>
                <span class="k-name">{{ r.title }}</span>
              </button>
              <div v-if="detailKey === r.key" class="spdetail" :data-detail-key="r.key">
                <div class="sd-hd">
                  <b>{{ dTitle(r.key) }}</b>
                  <button type="button" class="sd-x" title="收起详情" aria-label="收起详情" @click="closeDetail()">×</button>
                </div>
                <div class="sd-body">{{ dBody(r.key) }}</div>
              </div>
            </template>
            <!-- DP-3 原型 foldrow 双向：折叠 ⇄ 展开全部步骤 -->
            <button v-if="turn.steps.length > SPFOCUS" class="spr foldrow" @click="foldOpen = !foldOpen">
              {{ foldOpen ? `⋯ 收起早期步骤，只看最近 ${SPFOCUS} 步` : `⋯ 已折叠前 ${turn.steps.length - SPFOCUS} 步 · 点击展开` }}
            </button>
            <template v-for="s in shownSteps" :key="s.id">
              <div
                class="spr link"
                :class="{ on: detailKey === 'step:' + s.id }"
                :data-detail-key="'step:' + s.id"
                role="button"
                tabindex="0"
                title="展开这一步的参数与结果"
                @click="openStepDetail(s)"
                @keydown.enter="openStepDetail(s)"
              >
                <span class="kring" :class="'k-' + s.status">
                  <IconSpan v-if="s.status === 'err'" name="x" :size="10" />
                  <IconSpan v-else-if="s.status !== 'run'" name="check" :size="11" />
                </span>
                <span class="k-name">{{ s.verb }}{{ s.status === 'err' ? ' · 失败' : '' }}</span>
              </div>
              <div v-if="detailKey === 'step:' + s.id" class="spdetail" :data-detail-key="'step:' + s.id">
                <div class="sd-hd">
                  <b>{{ dTitle('step:' + s.id) }}</b>
                  <button type="button" class="sd-x" title="收起详情" aria-label="收起详情" @click="closeDetail()">×</button>
                </div>
                <div class="sd-body">{{ dBody('step:' + s.id) }}</div>
                <button type="button" class="sd-jump" @click="jumpFromDetail()">↗ 在对话中定位</button>
              </div>
            </template>
            <div v-if="shownSteps.length === 0 && !planRows.length" class="spr empty">暂无进行中的步骤</div>
          </div>
        </div>
        <!-- Q2 乙（2026-11-09 拍板）：每个分区头可折叠；Q3 乙：详情就长在被点的那行下面 -->
        <div v-if="sourceRows.length" class="spsec">
          <button class="spsec-hd" type="button" :aria-expanded="!folded.src" @click="toggleFold('src')">
            <IconSpan name="globe" :size="12" /> 来源 <span class="spsec-n">{{ sourceRows.length }}</span>
            <IconSpan class="spsec-chev" name="chevron-down" :size="12" />
          </button>
          <div v-show="!folded.src" class="spsec-bd">
            <template v-for="r in sourceRows" :key="r.key">
              <button
                type="button"
                class="spsec-row"
                :class="{ on: detailKey === r.key }"
                :data-detail-key="r.key"
                @click="openDetail(r.key)"
              >
                <span class="kring k-ok"><IconSpan name="link" :size="10" /></span>
                <span class="k-name">{{ r.title }}</span>
              </button>
              <div v-if="detailKey === r.key" class="spdetail" :data-detail-key="r.key">
                <div class="sd-hd">
                  <b>{{ dTitle(r.key) }}</b>
                  <button type="button" class="sd-x" title="收起详情" aria-label="收起详情" @click="closeDetail()">×</button>
                </div>
                <div class="sd-body">{{ dBody(r.key) }}</div>
              </div>
            </template>
          </div>
        </div>
        <div v-if="fileRows.length" class="spsec">
          <button class="spsec-hd" type="button" :aria-expanded="!folded.file" @click="toggleFold('file')">
            <IconSpan name="file" :size="12" /> 文件 <span class="spsec-n">{{ fileRows.length }}</span>
            <IconSpan class="spsec-chev" name="chevron-down" :size="12" />
          </button>
          <div v-show="!folded.file" class="spsec-bd">
            <template v-for="r in fileRows" :key="r.key">
              <button
                type="button"
                class="spsec-row"
                :class="{ on: detailKey === r.key }"
                :data-detail-key="r.key"
                @click="openDetail(r.key)"
              >
                <span class="kring" :class="'k-' + r.status"><IconSpan v-if="r.status === 'err'" name="x" :size="10" /><IconSpan v-else-if="r.status !== 'run'" name="check" :size="11" /></span>
                <span class="k-name">{{ r.title }}</span>
              </button>
              <div v-if="detailKey === r.key" class="spdetail" :data-detail-key="r.key">
                <div class="sd-hd">
                  <b>{{ dTitle(r.key) }}</b>
                  <button type="button" class="sd-x" title="收起详情" aria-label="收起详情" @click="closeDetail()">×</button>
                </div>
                <div class="sd-body">{{ dBody(r.key) }}</div>
              </div>
            </template>
          </div>
        </div>

        <!-- Q7 甲：原「底部固定单详情栏」已被 Q3 乙 取代（2026-11-09 拍板）——详情现在长在被点
             的那一行下面，见上面各分区；这里不再保留一条底部栏，避免同一内容出现两处。 -->

        <!-- 任务清单（ZCode 对齐 Q2）：todo_write 整表替换维护；划线+计数+>6 条收 3 条焦点窗 -->
        <div v-if="todoView.items.length" class="todo">
          <div class="todo-hd">
            <span class="todo-name">任务清单</span>
            <span class="todo-cnt" :class="{ all: todoView.done === todoView.items.length }">{{ todoView.done }}/{{ todoView.items.length }}</span>
          </div>
          <!-- Q2（20261011 拍板A）：面板与聊天的联动此前"有而不显"——常驻提示让可点性可发现；
               批1审查 P2：无可跳目标（回放旧会话无 todo_write 步骤）时行本就不可点，提示随之隐藏 -->
          <div v-if="todoStepId" class="todo-hint">↗ 点清单行在对话中定位；点步骤行看参数与结果</div>
          <button v-if="todoView.pre > 0 && !todoOpen" class="todo-fold" @click="todoOpen = true">⋯ 前面 {{ todoView.pre }} 步，点开看全部</button>
          <div
            v-for="(it, i) in todoView.focus"
            :key="i"
            class="todo-it"
            :class="['st-' + it.status, { jump: todoStepId }]"
            :role="todoStepId ? 'button' : undefined"
            :tabindex="todoStepId ? 0 : undefined"
            :title="todoStepId ? '在对话中定位写这份清单的步骤' : undefined"
            @click="onTodoJump"
            @keydown.enter="onTodoJump"
          >
            <span class="tico">
              <IconSpan v-if="it.status === 'completed'" name="check" :size="11" />
            </span>
            <span class="tx">{{ it.content }}</span>
          </div>
          <button v-if="todoView.post > 0 && !todoOpen" class="todo-fold" @click="todoOpen = true">⋯ 还有 {{ todoView.post }} 步待办</button>
        </div>
        <!-- Q2 乙：原来的独立「步骤」区已并进上面的「计划」分区（2026-11-09 拍板） -->
        <!-- eslint-disable-next-line vue/no-v-html —— stamp 源串保持 emoji（⚡/🧠/✓），渲染层按 EMO2IC 换 Lucide -->
        <div class="spf" :class="{ sumok }" v-html="emoToSvgHtml(spfText)"></div>
      </div>
      <!-- Q3 乙（2026-11-09 拍板）：窄屏第一次收尾时教一次「这里能点」——3.5s 或点开即消，
           之后不再出现（显示即记本机，点没点都算教过，否则每轮都弹=打扰） -->
      <span v-if="showTeach" class="sp-teach">点这里看这一轮干了什么</span>
    </div>
  </transition>
</template>

<script setup lang="ts">
/**
 * 运行统计面板（v3.16 区域3 + 批I-4 DP-3 对齐原型 statRender）。
 * 全屏形态专属（原型 statdock 只挂 #full）；mini 胶囊手动收/完成后 1.6s 自动收（waiting 不收）。
 * 四交互：sph 双钮 / 步骤行点击跳流内工具行 / waiting 点头跳确认卡 / foldrow 双向展开。
 */
import type { AssistantTurn, AssistantStep } from './useAssistant';
import type { AssistantTodoItem } from '@/api/dcs/assistant';
import IconSpan from './IconSpan.vue';
import { emoToSvgHtml } from './icons';
import { turnStateWord } from './assistantCtx';
import { assistantName } from './nameState';

const props = defineProps<{
  turn: AssistantTurn | null;
  todo?: AssistantTodoItem[];
  /** Q2（20261011 拍板A）：todo 行跳转目标=全会话最近一次 todo_write 步骤 id（清单是会话级，
   *  步骤可能在更早回合——由宿主从 state.turns 跨回合解算后传入；无目标=行不可点） */
  todoJumpId?: string | null;
}>();
const emit = defineEmits<{
  /** 步骤行点击：宿主转 MsgFlow.jumpToStep 定位对应工具行 */
  'step-jump': [stepId: string];
  /** sph waiting 态点击：宿主转 MsgFlow.jumpToConfirm 定位确认卡 */
  'jump-confirm': [];
}>();

const SPFOCUS = 4;
/** Q6-B：容器够宽才自动展开（窄容器自动收胶囊，ZCode auto 同语义）。
 *  宽屏加宽（2026-10-11 用户拍板）后阈值不再写死 1150——列帽由宿主变量
 *  --dcs-col-max 动态给（默认 900，宿主 ≥1380 时 1080），展开阈随列帽联动：
 *  colMax + 面板 248 + 边距 28 上下（1080 档=1380 与档位阈同源，不再叠列） */
const YIELD_PANEL_W = 250;
/** Q19（2026-10-29）：正文列可让位的最低宽度——让位后低于它就宁可让面板浮在内容上，
 *  也不把正文挤成一条（实测 1100 宽下胶囊仍在按 196px 让位，正文列只剩 262px） */
const MIN_COL = 480;
/* Q20（2026-10-29）：胶囊/面板的横向分配常量——对齐 ZCode 的「面板属于栏、不属于正文」
   模型（见 conversationLayout.ts 的 24rem 栏与 ConversationStatusPanel 的 w-80）。
   Q30（2026-10-30 用户两图对质）把「贴正文 + 算缝」整套换成「右栏锚定 + 纯解析」：
   · GAP 24：canSide 判定用的走廊（列右缘到面板左缘的期望缝，ZCode 1280 档实测 24）
   · CAP_MAX 168 / CAP_MIN 136：胶囊优先整宽；右栏放不下就按栏宽缩，缩到 136（= 胶囊
     min-width，再窄「2/2 已完成」就要裁字）仍放不下才退化成 20px 状态点
   · DOT 20：状态点，与胶囊同一个右锚，不再有「贴字」和「贴边」两套落点 */
const GAP = 24;
const PANEL_W = 220;
/** Q1 乙（2026-11-09 拍板）：胶囊从「状态词」改成「摘要」，宽度上限 168 → 240——
 *  摘要一行话（计划：本周清理 · 2/3 步）在 168 里只剩省略号；可负担量不够时仍按栏宽缩 */
const CAP_MAX = 240;
const CAP_MIN = 112;
const DOT = 20;
/* Q26（2026-10-29 用户拍板「只轻微优化间距」）→ Q30 重写：呼吸只剩一个下限。
   Q26 当时是「左缝 16 / 右留白 12」两个各管一侧的固定值，结果两侧都不由栏宽决定：
   1920 实测胶囊到正文 16、到页面右缘 31，而正文离左缘 214——右边读起来是「黏在字上」。
   Q30 起右侧只有一条规则：**胶囊/面板的右缘永远钉在宿主内缘 − PANEL_EDGE**（ZCode
   `ConversationStatusPanel` 的 `absolute … right-4`，mini 与 panel 同一容器同一锚点），
   左缝 = 栏宽 − 16 − 胶囊宽，随窗口自然变大；MINI_AIR 只是「栏再窄也不许贴上字」的下限。 */
const MINI_AIR = 12;
/** Q35（2026-10-30 用户口径）：「答案区 + 输入框 到左侧、到右侧面板（展开/收起）的距离
 *  尽量一致，并且随宽度动态变化」——收起态也套展开态那条不变式。
 *  **strip = 此刻真正渲染的元素宽 + PANEL_EDGE**，代入「列在 [0, w−strip] 居中」即得
 *  左缝 ≡ 到胶囊的缝（与 w、列帽、胶囊宽都无关）。
 *  唯一的新代价是「正文让出的宽度」，所以给它一个上限：在列帽之下的富余之外，
 *  最多再啃 MINI_SHRINK。136 的来历：宽屏胶囊上限 168 − 右内距 16 = 152，
 *  取 136 意味着 1280 档（宿主 894、无富余）仍能保住 120 宽的胶囊 + 25/25 等距；
 *  再小就退 20px 点（strip = 36，仍然等距），不会为了等距把正文挤成一条。 */
const MINI_SHRINK = 136;
/** 窄档「微让位」上限：只为把 20px 点从贴边挪进栏里，正文列 798→775，肉眼几乎无感。
 *  Q30 起它只服务点态（胶囊态的让位由 need 整段给），不再用来「凑一个胶囊」。 */
const MINI_STRIP_MAX = 40;
/** Q22（2026-10-29 复核）：宽屏「侧栏位」的上限。WIDE_W 300：卡片最多吃到 300
 *  （ZCode w-80=320 同量级），220 时尾行「智能体 · 小助手 · 本轮未调用工具」会折两行。
 *  Q29 起「缝」不再是可调项（原 MAX_GAP 240 已删）：列居中后，宽屏多出来的空白
 *  由居中平分到列两侧，面板只钉宿主内缘 16。 */
const WIDE_W = 300;
/** Q29（2026-10-30 用户对质 ZCode 源码）：展开态让位量 = 面板宽 + 这一个右内距，
 *  让「内容区右缘」与「面板左缘」重合。列居中之后（MsgFlow/Composer 同步改），
 *  正文到左侧的缝与到面板的缝由同一次居中决定，恒等——这正是用户说的
 *  「跟左侧的距离 和 面板的距离貌似是一致的」。
 *  旧值 need = PANEL_W + GAP + 16 = 260 是「贴左」时代的算法：列右缘钉死，
 *  面板再退 24 的缝，于是多算的 24 只能从正文宽度里扣。
 *  · PANEL_EDGE 16：面板右缘到宿主内缘（= ZCode 的 right-4）。**Q30 起它同时是胶囊态
 *    和点态的锚点**——两态同锚，展开/收起只是「贴着右缘往左长」，右边缘一格不动。
 *  · COL_EDGE 25：列内距（.dcs-comp.full padding 24 + 1px 边框）——列被帽限制时，
 *    它同时也是列左缘到宿主内缘的距离，故「放得下侧栏」的判定用 2×COL_EDGE */
const PANEL_EDGE = 16;
const COL_EDGE = 25;

const panelRoot = ref<HTMLElement | null>(null);
const containerWide = ref(true);
/** 追随解算输入：父层（.main/.dw-body）borderBox 宽。Q30 起不再需要面板自身实宽——
 *  胶囊宽度由栏宽解析给出，读自己的 rect 会让「宽度 → 解算 → 宽度」成环 */
const containerW = ref(0);
/** 列宽节拍（宽屏加宽批）：列帽档位切换/宿主 resize 会改 .box 实宽——只听宿主宽时
 *  面板不会重算（预览注入实证列变面板不动）。观察 .box 触发本 ref，进 effect 依赖。 */
const boxTick = ref(0);
const mini = ref(false);
/** Q19：面板此刻是否叠在正文列上（走廊放不下时）——叠放态给胶囊加毛玻璃背板，
 *  免得正文从半透明底后面透出来显脏 */
const floating = ref(false);
/** Q20：gutter 放不下最窄胶囊（128）时的「状态点」形态——与 float 解耦：
 *  圆点可以落在列右 gutter 里（不压字），此时 float=false 但仍要 20px 点样式 */
const compact = ref(false);
/** 三席审查回修（m3 P1-1 + dspro P2-3）：用户手动开/收过胶囊——live 相位跳变、
 *  1.6s 自动收、容器宽跨阈三类自动行为均不得覆盖手动选择；新回合重置 */
const userMini = ref(false);
function expandMini() {
  /* Q3 乙：用户已经知道能点了——气泡立刻收掉并记「教过」 */
  if (showTeach.value) markTaught();
  mini.value = false;
  userMini.value = true;
}
function collapseMini() {
  mini.value = true;
  userMini.value = true;
}
const foldOpen = ref(false);
/* ---- Q2 乙（2026-11-09 拍板）：分区头可折叠 + 步骤并进计划 ----
 * 折叠状态按分区 key 记（plan/src/file）；换回合清空——ZCode 每个分区头都能折，
 * 我们原来三区常显，来源一多就把「文件」挤出屏幕。 */
const folded = ref<Record<string, boolean>>({});
function toggleFold(key: string) {
  folded.value[key] = !folded.value[key];
}
/** Q5 乙：手机档（<560）收起态=状态点，点开=底部抽屉（不再整块藏掉） */
const sheetMode = ref(false);
/** Q1 乙 + Q2 甲（2026-11-09 拍板）：手机档（<560）——收起态改成带字胶囊，右缘仍在右上角
 *  （Q2 选甲=不换位置）。桌面那套「按栏宽缩胶囊」在手机上恒退化成 20px 点（MIN_COL=480），
 *  所以窄屏单独一条几何：不挤正文、宽度交给 CSS 按内容定。 */
const narrow = ref(false);
const narrowMini = computed(() => narrow.value && mini.value);

/* ---- Q3 乙（2026-11-09 拍板）：窄屏第一次「干活中→收尾」时教一次 ----
 * 只教一次：显示即写本机标记（点没点都算教过，否则每轮都弹=打扰）；3.5s 自动消失。 */
const TEACH_KEY = 'dcs:assistant:statusTeach.v1';
const showTeach = ref(false);
let teachTimer: number | null = null;
function taught() {
  try {
    return localStorage.getItem(TEACH_KEY) === '1';
  } catch {
    return true; // 隐私模式读不到就当作教过：宁可不弹，也不要在每次渲染里抛异常
  }
}
function markTaught() {
  showTeach.value = false;
  if (teachTimer !== null) {
    window.clearTimeout(teachTimer);
    teachTimer = null;
  }
  try {
    localStorage.setItem(TEACH_KEY, '1');
  } catch {
    /* 写不进去只是下次再教一遍，不影响功能 */
  }
}
/* Q5 乙：把手下拉——跟手位移，松手超过 56px 就收起，否则弹回（不引第三方手势库） */
let gripDy = 0;
function onGripDown(e: PointerEvent) {
  const root = panelRoot.value;
  if (!root) return;
  /* 不 preventDefault 的话，拖动会把抽屉里的文字当作文本选中（实测截图里满屏蓝底） */
  e.preventDefault();
  const y0 = e.clientY;
  gripDy = 0;
  root.style.transition = 'none';
  const move = (ev: PointerEvent) => {
    gripDy = Math.max(0, ev.clientY - y0);
    root.style.transform = `translateY(${gripDy}px)`;
  };
  const up = () => {
    window.removeEventListener('pointermove', move);
    window.removeEventListener('pointerup', up);
    root.style.removeProperty('transition');
    root.style.removeProperty('transform');
    if (gripDy > 56) collapseMini();
    gripDy = 0;
  };
  window.addEventListener('pointermove', move);
  window.addEventListener('pointerup', up);
}
/** Q4 乙：鼠标停在面板上时不收，离开再收 */
const panelHover = ref(false);
let pendingCollapse = false;
function onPanelEnter() {
  panelHover.value = true;
}
function onPanelLeave() {
  panelHover.value = false;
  if (pendingCollapse && !userMini.value) {
    pendingCollapse = false;
    mini.value = true;
  }
}
let collapseTimer: ReturnType<typeof setTimeout> | null = null;
// Q6-B：观测主列宽度驱动 auto 展开/收胶囊（ResizeObserver 而非 window resize——全屏模态/独立页容器宽 ≠ 窗口宽）
let widthObserver: ResizeObserver | null = null;
/** 宽屏加宽批：观测输入卡 .box 实宽（列帽档位切换时触发追随重算） */
let boxObserver: ResizeObserver | null = null;
/** 卸载清理兜底（glm53 P2-5）：挂载期捕获宿主引用——v-if 先摘根（turn 置空）再卸载时
 *  panelRoot 已 null，live 读会 no-op 残留 --sp-strip 让正文列持续让位 */
let cleanupHost: HTMLElement | null = null;
/** 观测条 borderBox 宽（让位 padding 落在被观测父层上时 contentRect 会被自身 padding 缩小，
 *  有「展开→变窄→自动收→变宽」振荡风险——borderBox 不含 padding 语义天然免疫）。
 *  规范坑：ResizeObserverSize 的字段是 inlineSize/blockSize，没有 .width——写 .width 恒
 *  undefined 会静默回退 contentRect（自观测宽度恒初始值的实锤根因） */
function borderBoxW(entry: ResizeObserverEntry | undefined | null): number {
  const b = entry?.borderBoxSize;
  const single = Array.isArray(b) ? b[0] : b;
  return single?.inlineSize ?? entry?.contentRect.width ?? 0;
}
/** 列帽实值（宽屏加宽批）：从宿主内输入卡 computed max-width 解算（--dcs-col-max
 *  变量在此处已解成 px）。抽屉窄宿主 .box 无帽=none → parseFloat NaN → 900 兜底
 *  （与抽屉现行几何一致：strip 判定只看「放不下」方向，兜底值偏大不改变结论）。 */
function readColMax(host: HTMLElement | null): number {
  const box = host?.querySelector('.dcs-comp .box');
  const v = parseFloat(box ? getComputedStyle(box).maxWidth : '');
  return Number.isFinite(v) && v > 0 ? Math.round(v) : 900;
}

onMounted(() => {
  const target = panelRoot.value?.parentElement;
  cleanupHost = target ?? null;
  if (target && typeof ResizeObserver !== 'undefined') {
    widthObserver = new ResizeObserver((entries) => {
      const w = borderBoxW(entries[0]);
      containerW.value = Math.round(w);
      containerWide.value = w >= readColMax(target) + YIELD_PANEL_W;
    });
    // 三席审查 P2-1：本组件读的就是 borderBox 宽，按 border-box 观测语义一致，
    // 也免父层 --sp-strip 过渡期间 padding 变化触发无谓回调
    widthObserver.observe(target, { box: 'border-box' });
    // 宽屏加宽批：观测输入卡实宽——档位切换（宿主跨 1380）改列帽时 bump boxTick
    // 触发追随 effect 重算（宿主宽此时未必变，只听宿主会漏）
    const box = target.querySelector('.dcs-comp .box');
    if (box) {
      boxObserver = new ResizeObserver(() => {
        boxTick.value += 1;
      });
      boxObserver.observe(box, { box: 'border-box' });
    }
  }
});
onBeforeUnmount(() => {
  widthObserver?.disconnect();
  boxObserver?.disconnect();
  (cleanupHost ?? panelRoot.value?.parentElement)?.style.removeProperty('--sp-strip');
});

/**
 * 追随几何（Q5 ZCode 对齐，2026-10-10；宽屏加宽批三修；Q30 2026-10-30 四修=纯解析）。
 *
 * **Q30 为什么要重写这套解算**：Q5 起一直是「先写 --sp-strip，再读 .box 的 rect，
 * 用实测列右缘反推 right」。三个隐患在用户全屏实测里同时爆了：
 *  ① 宿主 `.main` 的 padding-right 有 0.3s 过渡，写完变量立刻 getBoundingClientRect()
 *     读到的是**过渡起点**的几何（同步布局只保证样式生效，不保证过渡走完）。1920 实测：
 *     收起时 right 应在 16，却按「展开态的列右缘」算成 188px，胶囊左缘压进正文 143px
 *     ——用户说的「展开、再收起，胶囊就挤进里面了」。
 *  ② 过渡结束后没有任何东西再触发重算（列帽未变 → .box 宽未变 → boxObserver 不响），
 *     错误值就永久留在 inline style 上，刷新前都不会回去。
 *  ③ 决策依赖实测值 → 值与布局互为因果，只能靠「别读自己写过的东西」绕，越绕越脆。
 * 现在改成**解析几何**：列右缘 = 列内距 + 居中偏移 + 列宽，全部由「宿主宽 w / 列帽
 * colMax / 让位 strip」三个数算出，与 1920/1680/1440 实测逐像素一致（probe-q30），
 * 且与过渡进度无关——任何一帧调用都得到终值，不存在中间态污染。
 *
 * 形态与落点：
 *  · 展开（!mini）：canSide 用「列帽 + 两侧内距 + 走廊 + 面板」判；放得下侧栏就 strip =
 *    面板宽 + 16（此时面板左缘正好压在内容区右缘，列在内容区居中 ⇒ 左缝 ≡ 到面板的缝，
 *    Q29 的不变式由居中本身保证），否则 strip=0 走浮层。
 *  · 收起（mini）：右缘仍钉 16，让位量与展开态同一条式子 `胶囊宽 + 16`（Q35）——
 *    列在「胶囊左侧的剩余区」里居中 ⇒ 左缝 ≡ 到胶囊的缝；胶囊跟着可负担的让位量缩
 *    （168 → 112），缩不下退 20px 点（strip = 36 仍等距），连 36 都负担不起才用 Q32
 *    的对称微让位兜底。
 *  · 两态同锚 ⇒ 展开/收起时右边缘不动，只有左缘在长/缩，不会「换地方」。
 */
watchEffect(() => {
  // 依赖必须无条件先读（Vue 教训级坑）：若在读取响应式依赖之前因宿主为空早退，
  // 本效应零依赖登记=死效应，宿主就位后也永不重跑（--sp-strip 恒空的实锤根因）。
  // 宿主取 live 读（panelRoot 反向解 parentElement）而非 onMounted 一次性捕获——
  // 三席审查回修：捕获式在挂载瞬间根不在场时会永久丢宿主且无从补救
  // boxTick=列宽节拍（宽屏加宽批）：档位切换改 .box 实宽时本效应须重算（预览实证漏听）
  const w = containerW.value;
  void boxTick.value;
  const hasTurn = !!props.turn;
  const root = panelRoot.value;
  const host = root?.parentElement ?? null;
  if (!host || !root) {
    return;
  }
  if (!hasTurn) {
    host.style.removeProperty('--sp-strip');
    host.style.removeProperty('--sp-strip-l');
    root.style.removeProperty('right');
    root.style.removeProperty('--sp-panel-w');
    root.style.removeProperty('--sp-cap-w');
    return;
  }
  /* Q5 乙（2026-11-09 拍板）：手机档（<560）展开态=底部抽屉——不参与右侧让位解算，
     宽高全交给 CSS（.sheet）。 */
  narrow.value = w > 0 && w < 560;
  sheetMode.value = !mini.value && narrow.value;
  if (sheetMode.value) {
    /* 抽屉态既不是「浮层」也不是「状态点」——这两个标记必须清掉：`.compact:hover`
       有一条「悬停向左展开成 168×26 胶囊」的老规则（状态点自查用），留着会把
       展开的抽屉在鼠标移上去时瞬间压成 26px 高（2026-11-09 实测抓到的真 bug）。 */
    floating.value = false;
    compact.value = false;
    host.style.setProperty('--sp-strip', '0px');
    host.style.removeProperty('--sp-strip-l');
    root.style.removeProperty('right');
    root.style.removeProperty('--sp-panel-w');
    root.style.removeProperty('--sp-cap-w');
    return;
  }
  /* Q1 乙 + Q2 甲（2026-11-09 拍板）：窄屏收起态=带字胶囊。桌面几何（MIN_COL=480）在手机上
     恒不成立，按老式子只会一路缩成 20px 点（390 实测就是这个根因）；窄屏不挤压正文
     （strip=0，胶囊浮在右上角），宽度由 CSS 按内容定。 */
  if (mini.value && narrow.value) {
    floating.value = false;
    compact.value = false;
    host.style.setProperty('--sp-strip', '0px');
    host.style.removeProperty('--sp-strip-l');
    root.style.removeProperty('--sp-panel-w');
    root.style.removeProperty('--sp-cap-w');
    root.style.right = `${PANEL_EDGE}px`;
    return;
  }
  const colMax = readColMax(host);
  /* Q29：判定与让位量全部改用「列帽 + 列内距」的常数几何，不再读 .box 的实测左缘——
     列居中之后 boxLeft 会随 strip 漂（1920 未展开 214、展开 56），拿它当阈值会让
     canSide 在展开瞬间自我翻转。列帽是 cap 属性，与 strip 无关，天然无反馈环。 */
  const canSide = w >= colMax + COL_EDGE * 2 + GAP + PANEL_W;
  const panelW = canSide ? WIDE_W : PANEL_W;
  const need = panelW + PANEL_EDGE;
  const roomy = w - need - COL_EDGE * 2 >= MIN_COL;
  /* 列右缘（相对宿主左缘）：stripR / stripL 都吃内距，列在剩余区居中——
     与 .box 实测的关系在 1920（900 帽）/1680（未溢出）/1440（满宽）三档逐像素对齐。 */
  const colRightAt = (stripR: number, stripL = 0) => {
    const avail = Math.max(0, w - stripR - stripL - COL_EDGE * 2);
    const colW = Math.min(colMax, avail);
    return stripL + COL_EDGE + (avail - colW) / 2 + colW;
  };
  const railAt = (stripR: number, stripL = 0) => Math.max(0, w - colRightAt(stripR, stripL));

  let strip = 0;
  /** Q32：左侧让位。只有 mini 微让位会用到——必须与右侧等量，否则「列左右等距」被打破 */
  let stripL = 0;
  let capW = CAP_MAX;
  let dot = false;
  if (mini.value) {
    /* Q35：strip = 胶囊宽 + PANEL_EDGE ⇒ 左缝 ≡ 到胶囊的缝（代数见 MINI_SHRINK 注释）。
       可负担量 afford 取两道闸的较小值：① 富余 + MINI_SHRINK；② colNat − MIN_COL。 */
    const colNat = Math.min(colMax, w - COL_EDGE * 2);
    const slack = Math.max(0, w - COL_EDGE * 2 - colMax);
    const afford = Math.max(0, Math.min(slack + MINI_SHRINK, colNat - MIN_COL));
    const capTry = Math.min(CAP_MAX, Math.floor(afford - PANEL_EDGE));
    if (capTry >= CAP_MIN) {
      // 胶囊按可负担量缩，让位量与它同步 ⇒ 两侧恒等
      capW = capTry;
      strip = capW + PANEL_EDGE;
    } else {
      dot = true;
      capW = DOT;
      if (afford >= DOT + PANEL_EDGE) {
        // 点态也能等距（36 = 20 + 16），不必再动左右内距
        strip = DOT + PANEL_EDGE;
      } else {
        /* 极窄档兜底（Q32）：连「点 + 16」都负担不起——正文本就在 MIN_COL 附近，
           只把点从贴边挪进栏里，每侧 ≤40 且不低于 MIN_COL。 */
        const rail = railAt(0);
        const want = DOT + MINI_AIR + PANEL_EDGE;
        if (rail < want) {
          const budget = Math.max(0, Math.min(MINI_STRIP_MAX, (colNat - MIN_COL) / 2));
          const pad = Math.round(Math.min(budget, want - rail));
          strip = pad;
          stripL = pad;
        }
      }
    }
  } else {
    strip = roomy ? need : 0;
  }
  floating.value = w > 0 && !mini.value && strip === 0;
  compact.value = dot;
  host.style.setProperty('--sp-strip', `${strip}px`);
  if (stripL > 0) host.style.setProperty('--sp-strip-l', `${stripL}px`);
  else host.style.removeProperty('--sp-strip-l');
  root.style.setProperty('--sp-cap-w', `${capW}px`);
  // 只在展开态写面板宽：mini 走 --sp-cap-w，留着旧的 168 会在展开那一帧闪窄
  if (mini.value) root.style.removeProperty('--sp-panel-w');
  else root.style.setProperty('--sp-panel-w', `${panelW}px`);
  /* Q30：两态同一个右锚（ZCode right-4）——不再按 gutter/gap 现算，右边缘恒定不动。
     唯一例外：栏连「元素 + 16」都装不下（1100 实测栏 25、正文 458 已低于 MIN_COL、
     微让位预算为 0）时退到「刚好出列」——宁可贴栏口，也不许压进正文（用户原话
     「挤进里面了」）。这条兜底只在极窄档触发，其余所有档恒 16。 */
  const railEnd = railAt(strip, stripL);
  const effW = mini.value ? capW : panelW;
  let right = PANEL_EDGE;
  if (railEnd - right - effW < 0) {
    right = Math.max(2, Math.round(railEnd - effW));
  }
  root.style.right = `${right}px`;
});
// 历史回放场景：回合一开始就是终态（无 live 过渡不触发 watch）——挂载即胶囊收起
if (props.turn && !['thinking', 'acting', 'writing', 'waiting'].includes(props.turn.phase)) {
  mini.value = true;
}

const live = computed(() => props.turn != null && ['thinking', 'acting', 'writing'].includes(props.turn.phase));
/** AU-31: live 态秒级时钟——durText 里的 Date.now() 无响应式依赖，computed 永不重算=耗时定格 */
const now = ref(Date.now());
let nowTimer: number | null = null;
watch(
  live,
  (on) => {
    if (on && nowTimer === null) {
      nowTimer = window.setInterval(() => {
        now.value = Date.now();
      }, 1000);
    } else if (!on && nowTimer !== null) {
      window.clearInterval(nowTimer);
      nowTimer = null;
    }
  },
  { immediate: true }
);
/* Q3 乙（2026-11-09 拍板）：窄屏「已收成胶囊 + 这一轮已结束」且从没教过 → 弹一次。
   不挂 live 下降沿：刷新/回放历史时直接落在终态，也要教得到（全局只教一次，不会反复打扰）。 */
watch(
  () => narrowMini.value && !live.value && ['done', 'stopped'].includes(props.turn?.phase ?? ''),
  (ready) => {
    if (!ready || showTeach.value || taught()) {
      return;
    }
    showTeach.value = true;
    teachTimer = window.setTimeout(() => markTaught(), 3500);
  },
  { immediate: true }
);
const doneSteps = computed(() => props.turn?.steps.filter((s) => s.status !== 'run').length ?? 0);
const shownSteps = computed(() => {
  if (!props.turn) {
    return [];
  }
  return foldOpen.value || props.turn.steps.length <= SPFOCUS ? props.turn.steps : props.turn.steps.slice(-SPFOCUS);
});

/* ===== 任务清单（ZCode 对齐 Q2）：>6 条收 3 条焦点窗 ===== */
/** ZCode ConversationStatusPanel 同款常量：COMPACT_TODO_THRESHOLD=6 / TODO_FOCUS_WINDOW_SIZE=3 */
const TODO_COMPACT = 6;
const TODO_WINDOW = 3;
const todoOpen = ref(false);

/** Q2（20261011 拍板A）：todo 行可点——目标由宿主跨回合解算（清单会话级、步骤可能在
 *  任意回合）；回放/无 steps 的会话无目标时优雅 no-op（行渲染为不可点态） */
const todoStepId = computed(() => props.todoJumpId ?? null);
function onTodoJump() {
  if (todoStepId.value) {
    emit('step-jump', todoStepId.value);
  }
}
/** 清单长度变化时重置展开态（k3 P2-7 回修口径：只 watch length——同长度整表替换
 * （如状态翻转帧）不重置展开，帧更新不打扰用户当前的展开选择，属有意行为） */
watch(
  () => props.todo?.length,
  () => {
    todoOpen.value = false;
  }
);
const todoView = computed(() => {
  const items = props.todo ?? [];
  const done = items.filter((it) => it.status === 'completed').length;
  if (items.length <= TODO_COMPACT || todoOpen.value) {
    return { items, done, focus: items, pre: 0, post: 0 };
  }
  // 焦点=进行中条目（无则首个未完成，再无则尾部窗口）——ZCode getStatusPanelTodoFocusWindow 同算法
  let fi = items.findIndex((it) => it.status === 'in_progress');
  if (fi < 0) {
    fi = items.findIndex((it) => it.status !== 'completed');
  }
  if (fi < 0) {
    fi = items.length - TODO_WINDOW;
  }
  const start = Math.max(0, Math.min(fi, items.length - TODO_WINDOW));
  return {
    items,
    done,
    focus: items.slice(start, start + TODO_WINDOW),
    pre: start,
    post: Math.max(0, items.length - start - TODO_WINDOW)
  };
});

/** Q1 甲（2026-11-07 拍板）：状态词与 TurnBlock 同源——进行中一律「工作中」，
 *  阶段动词只放尾注 c-last，不再让胶囊里出现「思考中/撰写中/执行中」三套词 */
const stateWord = computed(() => (props.turn ? turnStateWord(props.turn.phase) : ''));
const dotCls = computed(() => (live.value ? 'run' : props.turn?.phase === 'error' ? 'err' : props.turn?.phase === 'waiting' ? 'wait' : 'ok'));
/** 面板三态类（批J-2 原型 L405-409）：running/waiting/finish 驱动边框与文案色 */
const panelCls = computed(() => ({
  mini: mini.value,
  /** Q19：叠在正文列上（走廊放不下）——胶囊补毛玻璃背板，展开卡补强投影 */
  float: floating.value,
  /** Q20：gutter 只够一个点——20px 状态点（与 float 解耦，不压字也要点态） */
  compact: compact.value,
  /** Q5 乙（2026-11-09 拍板）：手机档展开=底部抽屉 */
  sheet: sheetMode.value,
  /** Q1 乙 + Q2 甲（2026-11-09 拍板）：手机档——收起态是带字胶囊（不是 20px 点） */
  narrow: narrow.value,
  running: live.value,
  waiting: props.turn?.phase === 'waiting',
  finish: !live.value && ['done', 'stopped'].includes(props.turn?.phase ?? '')
}));
/** 原型 sph 尾注 c-last：运行=最后一步动词；完成=回合 stamp 摘要 */
const sphLast = computed(() => {
  const t = props.turn;
  if (!t) {
    return '';
  }
  if (live.value) {
    return t.steps.at(-1)?.verb ?? '';
  }
  return t.stamp || '';
});
/** Q19-b：浮动胶囊缩成 22px 状态点后，文字信息只能进 title/aria——完整说法放这里 */
const capsuleTitle = computed(() => {
  const t = props.turn;
  if (!t) {
    return '';
  }
  const step = `${doneSteps.value}/${t.steps.length || 1}`;
  // Q1 乙：胶囊正文已是摘要，title 里补齐状态词与步数（点态 20px 时只剩 title）
  return `${stateWord.value} · 步骤 ${step} · ${capsuleSummary.value}${t.phase === 'waiting' ? ' · 点这里展开' : ''}`;
});
/* ---- Q7 甲（2026-11-07 拍板，照 ZCode 的摘要链 + 单详情栏）----
 * 摘要链优先级：在跑的步骤 → 计划进度 → 未完成待办 → 来源 → 文件 → 收尾一句话。
 * ZCode 的原链是 live workflow → currentPlanItem → active Goal → Git → done Goal →
 * completedPlanItem → Todo 数 → latestSessionPlan → runningCount → endedWorkflowRunCount；
 * 本仓没有 workflow/goal/git，能对上的是「在跑步骤 / 计划 / 待办 / 来源」，其余按本仓数据补。 */
const summaryText = computed(() => {
  const t = props.turn;
  if (!t) {
    return '';
  }
  const running = t.steps.find((s) => s.status === 'run');
  if (running) {
    return `正在 ${running.verb}${t.steps.length > 1 ? `（第 ${t.steps.indexOf(running) + 1}/${t.steps.length} 步）` : ''}`;
  }
  if (t.plan) {
    const done = t.plan.steps.filter((x) => x.state === 'done').length;
    return `计划：${t.plan.title || '本轮计划'} · ${done}/${t.plan.steps.length} 步`;
  }
  const tv = todoView.value;
  if (tv.items.length && tv.done < tv.items.length) {
    return `待办 ${tv.done}/${tv.items.length} · 还差 ${tv.items.length - tv.done} 项`;
  }
  const cites = t.citations?.length ?? 0;
  if (cites) {
    return `本轮引用了 ${cites} 条网页来源`;
  }
  const files = fileRows.value.length;
  if (files) {
    return `本轮读了 ${files} 个文件`;
  }
  if (t.phase === 'done') {
    return `这一轮已收尾${t.steps.length ? ` · 共 ${t.steps.length} 步` : ''}`;
  }
  return `${stateWord.value}${t.steps.length ? ` · ${doneSteps.value}/${t.steps.length} 步` : ''}`;
});

/** Q1 乙（2026-11-09 拍板，照 ZCode）：收起态胶囊本身就是摘要——不是只写「已收尾」，
 *  而是「计划：本周清理 · 2/3 步」这种一眼能判断要不要打断的信息；超长省略靠 CSS。 */
const capsuleSummary = computed(() => summaryText.value || stateWord.value);

interface SecRow {
  key: string;
  title: string;
  body: string;
  status: 'run' | 'ok' | 'err';
}
/** 计划分区：整份计划一行（点开看逐步状态），不逐条塞行——面板窄，逐条会挤掉待办 */
const planRows = computed<SecRow[]>(() => {
  const p = props.turn?.plan;
  if (!p) {
    return [];
  }
  const done = p.steps.filter((x) => x.state === 'done').length;
  const body = p.steps
    .map((x, i) => `${i + 1}. [${x.state === 'done' ? '已做' : x.state === 'run' ? '进行中' : '未开始'}] ${x.label}`)
    .join('\n');
  return [
    {
      key: `plan:${p.planId ?? 'draft'}`,
      title: `${p.title || '本轮计划'}（${done}/${p.steps.length}）`,
      body,
      status: done >= p.steps.length ? 'ok' : 'run'
    }
  ];
});
/** 来源分区：联网搜索结果（网页外链在消息流里可点；这里只列标题与域名，点开看完整地址） */
const sourceRows = computed<SecRow[]>(() =>
  (props.turn?.citations ?? []).map((c, i) => ({
    key: `source:${c.url || i}`,
    title: c.title || c.url,
    body: `${c.title || ''}
${c.siteName ? c.siteName + ' · ' : ''}${c.url}`,
    status: 'ok' as const
  }))
);
/** 文件分区：读写类工具步骤 */
const FILE_TOOLS = new Set(['read_file', 'write_file', 'list_dir', 'read_dir', 'open_file']);
const fileRows = computed<SecRow[]>(() =>
  (props.turn?.steps ?? [])
    .filter((s) => FILE_TOOLS.has(s.name) || s.name.includes('file'))
    .map((s) => ({
      key: `file:${s.id}`,
      title: `${s.verb}${s.status === 'err' ? ' · 失败' : ''}`,
      body: `工具：${s.name}
参数：${s.argsText || '—'}
结果：${(s.resultFull || s.brief || '—').slice(0, 800)}`,
      status: s.status
    }))
);

/* 详情（行内）：唯一入口 openDetail / closeDetail——同一时刻只开一条，换行即替换。
 * 代码席 P1（2026-11-09）：正文必须是**现算**的——早先存字符串快照，点开运行中的步骤
 * 会永远停在「结果：—」，计划推进后详情还写着旧进度，同一面板自相矛盾。 */
const detailKey = ref('');
function openDetail(key: string) {
  detailKey.value = detailKey.value === key ? '' : key;
}
function closeDetail() {
  detailKey.value = '';
}
function stepDetailBody(s: AssistantStep): string {
  return [
    `工具：${s.name}`,
    `参数：${s.argsText || '—'}`,
    `结果：${(s.resultFull || s.brief || '—').slice(0, 800)}`
  ].join('\n');
}
/* Q3 乙（2026-11-09 拍板）：详情就长在这行下面——原来固定挂面板最底部，点上面第 1 行
 * 眼睛要往下跑 300~500px。模板里每类行后各跟一块，统一走这三个取值函数（不做非空断言）。 */
function dTitle(key: string): string {
  return detailOf(key)?.title ?? '';
}
function dBody(key: string): string {
  return detailOf(key)?.body ?? '';
}
function detailOf(key: string): { title: string; body: string } | null {
  if (!key) {
    return null;
  }
  if (key.startsWith('step:')) {
    const s = (props.turn?.steps ?? []).find((x) => x.id === key.slice(5));
    return s ? { title: `${s.verb}${s.status === 'err' ? ' · 失败' : ''}`, body: stepDetailBody(s) } : null;
  }
  const r = [...planRows.value, ...sourceRows.value, ...fileRows.value].find((x) => x.key === key);
  return r ? { title: r.title, body: r.body } : null;
}
/** 步骤行也走同一个详情栏（Q7 甲：分区行与步骤行同一套交互，不搞两套）；
 *  跳转退成详情里的一枚「在对话中定位」——原先步骤行点击直跳，和分区行开详情互相打架 */
const detailStepId = computed(() => (detailKey.value.startsWith('step:') ? detailKey.value.slice(5) : ''));
function openStepDetail(s: AssistantStep) {
  openDetail(`step:${s.id}`);
}
function jumpFromDetail() {
  const id = detailStepId.value;
  if (!id) {
    return;
  }
  emit('step-jump', id);
  closeDetail();
}
/** 换回合/收面板时详情不跨回合残留（纯前端态，刷新即失，符合甲案） */
watch(
  () => props.turn?.id,
  () => closeDetail()
);

const durText = computed(() => {
  const t = props.turn;
  if (!t) {
    return '';
  }
  const ms = t.durMs > 0 ? t.durMs : live.value ? now.value - t.startedAt : 0;
  return ms >= 2100 ? (ms < 60_000 ? `${(ms / 1000).toFixed(0)}s` : `${Math.floor(ms / 60_000)}m`) : '';
});
/** 原型 spf 尾行五态：等待你确认/已停止/进行中/✓ 已完成+stamp/本轮完成（无工具=未调用工具）——Q10 词表对齐 */
const spfText = computed(() => {
  const t = props.turn;
  let tail = '';
  if (t?.phase === 'waiting') {
    tail = ' · 等待你确认';
  } else if (t?.phase === 'stopped') {
    tail = ' · 已停止';
  } else if (live.value) {
    tail = ' · 进行中';
  } else if (t?.stamp) {
    tail = ` · ✓ 已完成 · ${t.stamp}`;
  } else if (t?.steps.length) {
    tail = ' · 本轮完成';
  } else {
    tail = ' · 本轮未调用工具';
  }
  return `智能体 · ${assistantName.value}${tail}`;
});
const sumok = computed(() => !!props.turn?.stamp && !live.value && props.turn.phase !== 'waiting' && props.turn.phase !== 'stopped');

function onSphClick() {
  // 原型 sph onclick：非 mini 且 waiting → statJumpConfirm
  if (props.turn?.phase === 'waiting') {
    emit('jump-confirm');
  }
}

/** 新回合重置折叠行与手动胶囊标记；live 展开面板；离开 live 1.6s 自动收胶囊（waiting 期间不收，原型 !statWait 守卫） */
watch(
  () => props.turn?.id,
  () => {
    foldOpen.value = false;
    userMini.value = false;
    folded.value = {};
    pendingCollapse = false;
  }
);
watch(
  () => props.turn?.phase,
  (phase, old) => {
    if (collapseTimer) {
      clearTimeout(collapseTimer);
      collapseTimer = null;
    }
    const livePhases = ['thinking', 'acting', 'writing'];
    const liveNow = !!phase && livePhases.includes(phase);
    const wasLive = !!old && livePhases.includes(old);
    if (liveNow) {
      // Q6-B：进入 live 自动展开（容器够宽才有让位廊位）；live 相位之间的跳变不再重置
      // mini——用户可能已手动收成胶囊（三席审查 dspro P2-3）
      if (!wasLive && !userMini.value) {
        mini.value = !containerWide.value;
      }
    } else if (wasLive && phase !== 'waiting') {
      // Q4 乙（2026-11-09 拍板）：离开 live 3 秒收成胶囊（原 1.6 秒）——先给人看结果，
      // 再把地方还回去；鼠标停在面板上时暂停计时，移开再收。
      // 窗口内用户手动展开过则尊重选择（三席审查 m3 P1-1）；回合已换（极端竞态）也不收
      const turnAtSchedule = props.turn?.id;
      collapseTimer = setTimeout(() => {
        if (props.turn?.id !== turnAtSchedule || userMini.value) {
          return;
        }
        if (panelHover.value) {
          pendingCollapse = true; // 正在看，等鼠标离开再收
          return;
        }
        mini.value = true;
      }, 3000);
    }
  }
);
// Q6-B：live 期间容器宽度跨阈值时跟随 auto 语义（仅 live 态、且用户未手动选过；手动展开/收起不被覆盖）
watch(containerWide, (wide) => {
  if (live.value && !userMini.value) {
    mini.value = !wide;
  }
});
onBeforeUnmount(() => {
  if (collapseTimer) {
    clearTimeout(collapseTimer);
  }
  if (nowTimer !== null) {
    window.clearInterval(nowTimer);
  }
  /* Q3 乙：教学气泡的 3.5s 计时器（卸载后回调再跑会碰已卸载的 ref） */
  if (teachTimer !== null) {
    window.clearTimeout(teachTimer);
    teachTimer = null;
  }
});
</script>

<style lang="scss" scoped>
.stat-panel {
  position: absolute;
  top: 60px;
  /* Q30：默认值与解算同锚（两态一致，脚本注入/离屏首帧都不跳） */
  right: 16px;
  /* Q22：展开卡宽度由 canSide 分支写入（220 → 300）；mini 走下面的 --sp-cap-w */
  width: var(--sp-panel-w, 220px);
  /* Q9（拍板 2026-10-10）：胶囊与展开卡同锚点（ZCode 原装）——mini 只是高度塌缩，
     不换地方；批G 时代「胶囊退流内防遮挡」由宿主父层的常备廊位接棒（sp-capsule/sp-panel） */
  /* Q7（ZCode 对齐，2026-10-10）：同一元素两形态全可插值——mini 显式宽 168（原 auto
     不可插值=宽度跳变根因）；壳过渡补 border-radius/padding（ZCode 外观四件套+几何）；
     overflow:hidden 让 full-body 的 max-height 塌缩不撑壳 */
  overflow: hidden;
  &.mini {
    width: var(--sp-cap-w, 168px);
    border: none;
    box-shadow: none;
    background: transparent;
  }
  /* 原型 L376：320px/radius 16/深投影/四属性过渡（mini 变形用）；批J-2 对齐 */
  border-radius: 16px;
  /* Q28（2026-10-30）：与 ZCode v4 `ConversationStatusPanel` 同口径——面板本体是
     popover 面（`bg-[var(--color-popover)]` + 1px popover 边框 + shadow-md + 16 圆角），
     `absolute top-0 right-4` 浮在会话画布上；改的不是卡，是它身后那条带子：
     会话页 / 抽屉的让位带已按答案区画布色（--el-fill-color-light）铺底（见
     views/dcs/assistant/session/index.vue 的 .main::after），白卡落上去才看得见轮廓。 */
  background: var(--el-bg-color);
  border: 1px solid var(--dcs-border-color-lighter);
  box-shadow: 0 8px 28px rgb(16 24 40 / 10%);
  font-size: 12.5px;
  z-index: 5;
  max-height: min(64vh, 32rem);
  display: flex;
  flex-direction: column;
  transition:
    width 0.3s,
    right 0.3s,
    border-radius 0.3s,
    padding 0.3s,
    border-color 0.3s,
    box-shadow 0.3s,
    background 0.3s;
  /* 原型三态（L405-409）：running 点环琥珀脉冲 / waiting 边框+文案琥珀 / finish 绿 */
  &.running {
    .spdot.run {
      background: var(--dcs-color-warning, #e6a23c);
      animation: dcs-ppring 1s ease infinite;
    }
  }
  &.waiting {
    border-color: #f3d19e;

    .sp-name {
      color: #a0640a;
    }
  }
  &.finish .spdot.ok {
    background: var(--dcs-color-success);
  }

  &.mini {
    border-radius: 999px;
    padding: 0;
  }
  /* Q19：叠放态——展开卡加更重的投影（读起来是「浮层」而不是「压在文字上的色块」）。
     Q27（2026-10-30）：中屏让位区也回到这张卡片（白底 / 16 圆角 / 投影 / 高度随内容），
     与宽屏 canSide 同一套外观；让位带只负责让出宽度，不再被灰底填成通高栏。 */
  &.float {
    box-shadow: 0 10px 34px rgb(16 24 40 / 18%);
  }
  /* Q20（2026-10-29）：gutter 放不下 128 胶囊时收成 20px 状态点——整颗落进列右
     25px gutter（20+3+2），不再像 26px 版压住列右缘 8px、在 1280/1100 实测压到字。
     文案进 title/aria；悬停向左借位展开成 168 胶囊（用户主动看，允许临时压字）。
     状态色点保留脉冲/成功/告警语义，命中区用伪元素扩到 34px。 */
  &.compact {
    box-sizing: border-box;
    width: 20px;
    height: 20px;
    background: color-mix(in srgb, var(--el-bg-color) 88%, transparent);
    backdrop-filter: blur(8px);
    box-shadow: 0 3px 12px rgb(16 24 40 / 16%);
    transition:
      width 0.22s ease,
      height 0.22s ease,
      right 0.22s ease,
      background 0.22s;

    .capsule {
      position: relative;
      width: 20px;
      height: 20px;
      min-width: 0;
      padding: 0;
      gap: 0;
      background: transparent;
      box-shadow: none;
      transition:
        width 0.22s ease,
        gap 0.22s;

      &::after {
        content: '';
        position: absolute;
        inset: -7px;
        border-radius: 999px;
      }
      .cnt,
      .c-last {
        display: none;
      }
      .spdot {
        width: 7px;
        height: 7px;
      }
    }
    /* 悬停自查：向左借位展开成完整胶囊（右缘锚定不动，不追鼠标） */
    &:hover {
      width: 168px;
      height: 26px;

      .capsule {
        width: 168px;
        height: 26px;
        gap: 6px;
        padding: 0 10px;

        .cnt,
        .c-last {
          display: inline;
        }
        .spdot {
          width: 7px;
          height: 7px;
        }
      }
    }
  }
}
/* Q5 乙（2026-11-09 拍板）：手机档（<560）展开态=底部抽屉——拇指够得着，不再用
   「浮动面板盖半屏」（甲）也不是整块藏掉（丙）。放在 .stat-panel 块之后：与 .compact
   同特异性，必须后写才能盖掉它的 20px 尺寸（抽屉展开时 compact 仍为真）。 */
.stat-panel.sheet {
  left: 8px;
  right: 8px;
  bottom: 8px;
  top: auto;
  width: auto;
  height: auto;
  max-height: 52%;
  border-radius: 16px 16px 12px 12px;
  z-index: 30;
  background: var(--el-bg-color);
  backdrop-filter: none;
  box-shadow: 0 -10px 28px rgb(16 24 40 / 18%);
  transition: none;
  /* Q5 乙：抽屉「升起来」而不是凭空出现（0.22s，动作可见即够） */
  animation: dcs-sheet-up 0.22s ease-out;

  .capsule {
    display: none;
  }
  .full-body {
    max-height: 100%;
  }
}
@keyframes dcs-sheet-up {
  from {
    transform: translateY(18px);
    opacity: 0.35;
  }
  to {
    transform: none;
    opacity: 1;
  }
}
/* Q5 乙：抽屉把手（只有手机档渲染）——一道 40×4 的横杠，tab 焦点不占位 */
.sheet-grip {
  display: block;
  width: 40px;
  height: 4px;
  margin: 6px auto 0;
  border-radius: 2px;
  background: var(--el-border-color-darker, #c9ced6);
  cursor: grab;
  touch-action: none;
  user-select: none;
}
/* Q4 乙（2026-11-09 拍板）：抽屉标题行——回答「这是什么」（遮罩负责「你打开了东西」） */
.sheet-ttl {
  padding: 8px 12px 4px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--dcs-text-regular);
}
/* Q4 乙：浅遮罩——点一下收起，和拖把手下滑等价。z-index 低于抽屉（30）高于正文。 */
.sp-sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 28;
  background: rgb(16 24 40 / 22%);
  animation: dcs-mask-in 0.22s ease-out;
}
@keyframes dcs-mask-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
/* Q1 乙 + Q2 甲（2026-11-09 拍板）：手机档收起态=带字胶囊，右缘仍钉 16（位置不变）。
   宽度交给内容：手机上正文是全宽，胶囊是浮层，不参与桌面「按栏宽缩胶囊」的让位几何
   （那条式子的 MIN_COL=480 在 390 上恒不成立，一路算下去只剩 20px 点——实测根因）。 */
.stat-panel.narrow.mini {
  width: auto;
  max-width: calc(100vw - 32px);
  /* 教学气泡挂在胶囊下方，不能被壳的 overflow:hidden 裁掉（fold 态的 full-body 自己裁） */
  overflow: visible;
  transition: none;

  .capsule {
    width: auto;
    min-width: 0;
    height: 28px;
    padding: 0 12px 0 10px;
    gap: 6px;
  }
  /* 收起态把展开体彻底移出布局：fold 只是 max-height:0，宽度仍会被里面的摘要撑到 235px
     （透明面板照样拦截点击，压在正文上的是一块看不见的挡板）——2026-11-09 实测抓到的 */
  .full-body {
    display: none;
  }
  .c-state {
    white-space: nowrap;
    font-size: 12px;
    color: var(--dcs-text-regular);
  }
}
/* Q3 乙：一次性教学气泡——出现 3.5s 或点开即消失，之后不再出现 */
.sp-teach {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 6;
  padding: 6px 10px;
  border-radius: 8px;
  background: #111827;
  color: #fff;
  font-size: 11.5px;
  line-height: 1.5;
  white-space: nowrap;
  box-shadow: 0 6px 18px rgb(16 24 40 / 18%);
  animation: dcs-teach-in 0.18s ease-out;
  pointer-events: none;

  &::after {
    content: '';
    position: absolute;
    top: -4px;
    right: 22px;
    width: 8px;
    height: 8px;
    border-radius: 1px;
    background: #111827;
    transform: rotate(45deg);
  }
}
@keyframes dcs-teach-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
/* Q7：胶囊常驻淡切（ZCode mini 行 150ms opacity）——非 mini 态绝对定位退出流+隐交互 */
.capsule {
  /* 三反馈③（拍板 2026-10-10）：定最小宽+内容居中——live 期文本随阶段切换（思考中→查任务→…）
     宽度反复伸缩，右锚定下左缘每秒跳；定宽后宽度稳定，跟随几何（hug）的解算输入也随之稳定 */
  /* Q20：胶囊会随 gutter 缩到 137px——min-width 必须跟 100% 取小，否则 136 内容宽 + 24 padding
     会撑破壳（overflow:hidden 直接裁字）；border-box 让 padding 计入这 168/137 */
  box-sizing: border-box;
  min-width: min(136px, 100%);
  /* Q1 乙（2026-11-09 拍板）：胶囊正文改成摘要，左对齐 + 单行省略（原来居中是因为只有状态词） */
  justify-content: flex-start;
  display: flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: var(--el-bg-color);
  border-radius: 999px;
  padding: 5px 12px;
  cursor: pointer;
  font-size: 12px;
  color: var(--dcs-text-regular);
  box-shadow: 0 2px 8px rgb(0 0 0 / 12%);
  transition: opacity 0.15s;

  &.ghost {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    opacity: 0;
    visibility: hidden;
    pointer-events: none;
  }

  /* Q1 乙：摘要一行话，超长省略——宽度上限由解算给出（240 → 可负担量） */
  .c-last,
  .capx {
    flex: 1 1 auto;
    min-width: 0;
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    text-align: left;
  }
}
/* Q7：展开体常驻，max-height+opacity 过渡（同元素可插值的另一半） */
.full-body {
  display: flex;
  flex-direction: column;
  min-height: 0;
  max-height: min(64vh, 32rem);
  /* 代码席 P1（2026-11-09）：分区多了以后总高会超 max-height，而 .stat-panel 是
     overflow:hidden——没有这行，超出的分区/尾行被直接裁掉且没有滚动条，永远够不到 */
  overflow-y: auto;
  overscroll-behavior: contain;
  opacity: 1;
  visibility: visible;
  transition:
    max-height 0.3s ease,
    opacity 0.2s ease,
    /* k3 P2-6 回修：visibility 延迟到收起过渡末帧——立即 hidden 会把 0.2s 淡出整个吞掉 */ visibility 0s linear 0.3s;

  &.fold {
    max-height: 0;
    overflow: hidden;
    opacity: 0;
    visibility: hidden;
    pointer-events: none;
  }
}
.spdot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;

  &.run {
    background: var(--el-color-primary);
    animation: dcs-dot 1.2s ease-in-out infinite;
  }
  &.ok {
    background: var(--dcs-color-success);
  }
  &.wait {
    background: var(--dcs-color-warning);
  }
  &.err {
    background: var(--dcs-color-danger);
  }
}
@keyframes dcs-dot {
  50% {
    opacity: 0.35;
  }
}
/* Q7 甲（2026-11-07 拍板）：摘要链一行 + 分区清单；详情按 Q3 乙 改为行内展开（2026-11-09） */
.sp-sum {
  padding: 8px 12px;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--dcs-text-secondary);
  border-bottom: 1px solid var(--dcs-border-color-lighter);
  /* 长句最多两行，超了省略——面板是速览，不是正文 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.spsec {
  padding: 6px 8px;
  border-bottom: 1px solid var(--dcs-border-color-lighter);

  &:last-of-type {
    border-bottom: 0;
  }
}
/* Q2 乙（2026-11-09 拍板）：分区头变成可点的折叠开关（原来是死的 div） */
.spsec-hd {
  display: flex;
  align-items: center;
  gap: 5px;
  width: 100%;
  border: 0;
  background: none;
  cursor: pointer;
  font-size: 11px;
  color: var(--dcs-text-placeholder);
  letter-spacing: 0.02em;
  padding: 2px 4px 3px;
  border-radius: 5px;

  &:hover {
    background: rgba(127, 127, 127, 0.08);
    color: var(--dcs-text-regular);
  }
  .spsec-chev {
    margin-left: 2px;
    transition: transform 0.15s ease;
  }
  &[aria-expanded='false'] .spsec-chev {
    transform: rotate(-90deg);
  }
}
.spsec-n {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
.spsec-row {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  text-align: left;
  padding: 4px 6px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: none;
  cursor: pointer;
  font-size: 12px;
  color: var(--dcs-text-regular);

  &:hover {
    background: rgba(127, 127, 127, 0.08);
  }
  &.on {
    background: var(--el-color-primary-light-9);
    border-color: var(--el-color-primary-light-7);
  }
  .k-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
/* Q3 乙（2026-11-09 拍板）：详情就长在被点的那一行下面（原来是面板最底部的固定栏，
   点上面第 1 行要往下跑 300~500px）；内部滚动上限保留 */
.spdetail {
  margin: 2px 0 6px;
  border: 1px solid var(--dcs-border-color);
  border-radius: 6px;
  overflow: hidden;
  background: rgba(127, 127, 127, 0.04);
  max-height: 240px;
  display: flex;
  flex-direction: column;

  .sd-hd {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 7px 12px 4px;
    font-size: 12px;

    b {
      font-weight: 500;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }
  .sd-x {
    margin-left: auto;
    border: 0;
    background: none;
    cursor: pointer;
    color: var(--dcs-text-placeholder);
    font-size: 15px;
    line-height: 1;
    padding: 0 2px;

    &:hover {
      color: var(--dcs-text-primary);
    }
  }
  .sd-body {
    padding: 0 12px 9px;
    font-size: 11px;
    line-height: 1.6;
    color: var(--dcs-text-secondary);
    white-space: pre-wrap;
    word-break: break-all;
    overflow: auto;
  }
  .sd-jump {
    align-self: flex-start;
    margin: 0 12px 8px;
    border: 0;
    background: none;
    padding: 0;
    font-size: 11px;
    color: var(--dcs-brand, #1f6feb);
    cursor: pointer;

    &:hover {
      text-decoration: underline;
    }
  }
}
.sph {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--dcs-border-color-lighter);
  font-size: 13px;

  &.clickable {
    cursor: pointer;
  }

  .sp-name {
    font-weight: 600;
    color: var(--dcs-text-primary);
    flex: none;
  }
  /* 原型 L382：展开态计数绿 600（mini 胶囊恒灰） */
  .cnt {
    color: var(--dcs-color-success);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    flex: none;
  }
  .sp-dur {
    color: var(--dcs-text-secondary);
    font-variant-numeric: tabular-nums;
    flex: none;
  }
  .c-last {
    min-width: 0;
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--dcs-text-secondary);
  }
  .sp-act {
    margin-left: auto;
    display: flex;
    gap: 2px;
    flex: none;

    .btn-min,
    .btn-max {
      border: none;
      background: none;
      cursor: pointer;
      color: var(--dcs-text-secondary);
      padding: 3px;
      border-radius: 6px;
      display: flex;

      &:hover {
        background: var(--el-fill-color-light);
        color: var(--dcs-text-primary);
      }
    }
  }
}
/* mini 胶囊内计数恒灰（原型 L404） */
.mini .cnt {
  color: var(--dcs-text-secondary);
  font-weight: 400;
}
/* 任务清单（ZCode 对齐 Q2，EP 企业风：克制状态色+tabular 计数+划线完成态） */
.todo {
  padding: 6px 6px 4px;
  border-bottom: 1px solid var(--dcs-border-color-lighter);
  display: flex;
  flex-direction: column;
  gap: 2px;

  .todo-hd {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 2px 6px 4px;

    .todo-name {
      font-size: 12px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    .todo-cnt {
      font-size: 11.5px;
      color: var(--dcs-text-secondary);
      font-variant-numeric: tabular-nums;

      &.all {
        color: var(--dcs-color-success);
      }
    }
  }
  .todo-hint {
    padding: 0 6px 2px;
    font-size: 11px;
    color: var(--dcs-text-secondary);
    opacity: 0.85;
  }
  .todo-it {
    display: flex;
    align-items: center;
    gap: 7px;
    padding: 4px 6px;
    border-radius: 6px;
    font-size: 12px;

    /* Q2（20261011 拍板A）：有跳转目标时可点——hover 手势+浅底让"点得动"可发现 */
    &.jump {
      cursor: pointer;
      transition: background-color 0.15s;

      &:hover,
      &:focus-visible {
        background: var(--el-fill-color-light);

        .tx {
          color: var(--el-color-primary);
        }
      }
      &:focus-visible {
        outline: 1px solid var(--el-color-primary);
      }
    }

    .tico {
      width: 14px;
      height: 14px;
      border-radius: 50%;
      border: 1.5px solid var(--dcs-border-color);
      flex: none;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .tx {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      color: var(--dcs-text-regular);
    }
    &.st-completed {
      .tico {
        border-color: var(--dcs-color-success);
        color: var(--dcs-color-success);
      }
      .tx {
        color: var(--dcs-text-placeholder);
        text-decoration: line-through;
      }
    }
    &.st-in_progress {
      .tico {
        border-color: var(--dcs-color-warning, #e6a23c);
        background: var(--dcs-color-warning, #e6a23c);
        animation: dcs-dot 1.2s ease-in-out infinite;
      }
      .tx {
        color: var(--dcs-text-primary);
        font-weight: 500;
      }
    }
  }
  .todo-fold {
    border: none;
    background: none;
    padding: 3px 6px;
    font: inherit;
    font-size: 11.5px;
    color: var(--dcs-text-secondary);
    cursor: pointer;
    text-align: left;

    &:hover {
      color: var(--el-color-primary);
    }
  }
}
/* Q2 乙（2026-11-09）：分区体纵向排布——原容器 .spb（底部详情栏）已删，
   间距改由这里给，步骤行与计划行的节奏保持一致 */
.spsec-bd {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
/* 步骤/计划行（原挂在 .spb 下；步骤并进「计划」分区后 .spb 容器没了，
   规则必须提层——否则圆环、间距、悬停全丢） */
.spr {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 6px;
  border-radius: 8px;
  color: var(--dcs-text-regular);
  min-width: 0;

  &.link {
    cursor: pointer;

    &:hover {
      background: var(--el-fill-color-light);
    }
    &:hover .k-name {
      color: var(--el-color-primary);
    }
    &:focus-visible {
      outline: 2px solid var(--el-color-primary-light-5);
      outline-offset: 1px;
      border-radius: 4px;
    }
  }
  &.on {
    background: var(--el-fill-color-light);
  }
  &.foldrow {
    border: none;
    background: none;
    padding: 5px 6px;
    font: inherit;
    font-size: 12px;
    color: var(--dcs-text-secondary);
    cursor: pointer;
    text-align: left;

    &:hover {
      color: var(--el-color-primary);
    }
  }
  &.empty {
    color: var(--dcs-text-placeholder);
  }

  .k-name {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .kring {
    width: 16px;
    height: 16px;
    border-radius: 50%;
    border: 1.5px solid var(--dcs-border-color);
    font-size: 9px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--dcs-text-placeholder);
    flex: none;

    &.k-ok {
      border-color: var(--dcs-color-success);
      color: var(--dcs-color-success);
    }
    &.k-err {
      border-color: var(--dcs-color-danger);
      color: var(--dcs-color-danger);
    }
    /* 原型 L392：运行步骤环琥珀脉冲 */
    &.k-run {
      border-color: var(--dcs-color-warning, #e6a23c);
      color: var(--dcs-color-warning, #e6a23c);
      animation: dcs-ppring 1s ease infinite;
    }
  }
}

@keyframes dcs-ppring {
  50% {
    box-shadow: 0 0 0 6px rgba(230, 162, 60, 0.1);
  }
}
.spf {
  padding: 8px 12px;
  border-top: 1px solid var(--dcs-border-color-lighter);
  color: var(--dcs-text-placeholder);
  font-size: 11.5px;
  /* Q27：220 宽卡片里尾注会在「本轮 / 完成」处折出一个孤字——两行均分，
     不要在第二行只剩两个字（300 宽单行不受影响；不支持则忽略） */
  text-wrap: balance;

  &.sumok {
    color: var(--dcs-color-success);
  }
}
.dcs-sp-enter-active,
.dcs-sp-leave-active {
  transition:
    opacity 0.2s,
    transform 0.2s;
}
.dcs-sp-enter-from,
.dcs-sp-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
@media (prefers-reduced-motion: reduce) {
  .spdot.run {
    animation: none;
  }
}
</style>
