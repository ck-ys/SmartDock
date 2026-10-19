<template>
  <div
    ref="rootEl"
    class="dcs-turn"
    :data-phase="turn.phase"
    :data-folded="folded ? '1' : '0'"
    @dcs-expand-fold="onExpandFold"
    @dcs-expand-steps="stepsAll = true"
  >
    <!-- Q2 批1审查 P1：MsgFlow.jumpToStep 目标落在中段折叠区时派发本事件先展开全部步骤 -->
    <!-- 回合状态条（tstat）：动词/终态/等你确认/耗时；可点折叠过程区（BN-2 原型 foldt，答完 1s 自动收）
         批J-2 补原型件：ts-ics 图标堆 / ts-f 失败计数 pill / ts-w8 等待 pill / tkl 换行滑动 / 终态去 ×N 双重计数 -->
    <button class="tstat" :class="statCls" :aria-expanded="!folded" :title="folded ? '展开过程明细' : '收起过程明细'" @click="toggleFold">
      <IconSpan v-if="live" name="loader-circle" cls="spin" />
      <IconSpan v-else-if="turn.phase === 'error'" name="circle-x" cls="fail-ic" />
      <IconSpan v-else-if="emptyFail" name="circle-x" cls="fail-ic" />
      <IconSpan v-else-if="turn.phase === 'stopped'" name="square" />
      <IconSpan v-else-if="turn.phase !== 'waiting'" name="check-circle" cls="ok-ic" />
      <!-- 原型 ts-ics（L317-322）：acting 期文案左侧堆叠已调用工具图标，live 脉冲、完成清空 -->
      <span v-if="live && turn.toolCalls > 0" class="ts-ics" :class="{ live: turn.phase === 'acting' }">
        <IconSpan v-for="icn in toolIcons" :key="icn" :name="icn" />
      </span>
      <Transition name="tkl" mode="out-in">
        <span v-if="turn.phase === 'waiting'" :key="statText" class="ts-w8" v-html="emoToSvgHtml(statText)"></span>
        <span
          v-else
          :key="statText"
          class="ts-t"
          :class="{ run: live, 'fin-warn': !live && turn.phase === 'stopped' && failCount > 0 }"
          v-html="emoToSvgHtml(statText)"
        ></span>
      </Transition>
      <span v-if="failCount > 0" class="ts-f"><IconSpan name="ban" :size="12" /> ×{{ failCount }}</span>
      <span v-if="turn.toolCalls > 0 && live" class="ts-x">×{{ turn.toolCalls }}</span>
      <span v-if="durText" class="ts-d">· {{ durText }}</span>
      <span class="ts-fold"><IconSpan name="chevron-down" :size="12" /></span>
    </button>

    <!-- 折叠态偷看行（原型 tpeek/fpeekrow L338-346）：思考摘要渐隐小窗 + 失败行点击展开定位；
         UI 专项批1：仅运行中保留（思考进行中瞄一眼），终态收起——英文 CoT 原文不再怼脸，想看完整思考点胶囊展开 -->
    <div v-if="folded && turn.thinkText && live" class="tpeek" title="展开过程明细" @click="toggleFold">{{ turn.thinkText }}</div>
    <div v-if="folded && failCount > 0" class="fpeekrow" @click="expandFail">
      <IconSpan name="ban" :size="12" /> {{ firstFailVerb }} · 失败 <IconSpan name="chevron-right" :size="12" />
    </div>

    <!-- 过程区（原型 .proc）：思考行+工具行左挂时间线圆点，末尾收尾分隔线。
         R3(2026-10-10 拍板A)：思考分段与工具行按事件到达序交错成统一时间线（ZCode 同构）——
         思考段就位在它发生的时刻（think①→工具①→think②），不再全文拼成一坨置顶；
         旧数据（thinkSegs 空/thinkText 有值、回放 steps 无 seq）兜底=合成单段置顶=旧行为 -->
    <div v-show="!folded" class="proc">
      <template v-for="it in timeline" :key="it.kind === 'think' ? 'th-' + it.seg.id : it.step.id">
        <button
          v-if="it.kind === 'tool' && !stepsAll && it.sIdx === 1 && hiddenMid > 0"
          class="midfold"
          title="展开全部步骤"
          @click="stepsAll = true"
        >
          ⋯ 另外 {{ hiddenMid }} 步
        </button>
        <!-- 思考段（Q9-A 胶囊折叠，逐段独立）：进行中=呼吸胶囊+流式摘要小窗；完成=自动收
             「已思考 · N 秒」（多段=思考① · N 秒）；点开=限高 300px 滚动区+markdown；
             开合=会话内记忆（用户点过的段优先于偏好）；停止时灰态「思考中断」 -->
        <div v-if="it.kind === 'think'" class="think">
          <div
            class="tk-h"
            role="button"
            tabindex="0"
            @click="onSegToggle(it.seg)"
            @keydown.enter="onSegToggle(it.seg)"
            @keydown.space.prevent="onSegToggle(it.seg)"
          >
            <span class="tk-i"><IconSpan name="brain" :size="14" /></span>
            <span class="tk-t" :class="{ run: !it.seg.done, cut: segCut(it.seg) }">{{ segLabel(it.seg, it.idx) }}</span>
            <!-- 流式摘要小窗（Q9：只看本段最新 400 字；用户点开后让位正文区） -->
            <span v-if="!it.seg.done && !segIsOpen(it.seg) && it.seg.text" :ref="(el) => setTkLive(el, it.seg)" class="tk-live">{{
              it.seg.text.slice(-400)
            }}</span>
            <!-- 停止思考（Q12-②）：思考进行中可中断，复用宿主停止通道（停整轮，可「继续说完」） -->
            <button
              v-if="!it.seg.done && live"
              type="button"
              class="tk-stop"
              title="停止思考（中断本轮，可点「继续说完」重问）"
              @click.stop="emit('stop')"
            >
              停止思考
            </button>
            <span class="tk-c"><IconSpan :name="segIsOpen(it.seg) ? 'chevron-up' : 'chevron-down'" :size="12" /></span>
          </div>
          <!-- eslint-disable-next-line vue/no-v-html —— md.ts 先转义后白名单替换（与正文气泡同链路；
               批3回修：思考块同样挂点击/回车委托，块内链/复制钮不再是死按钮） -->
          <div v-if="segIsOpen(it.seg)" class="tk-b" v-html="segHtml(it.seg)" @click="onMdClick" @keydown.enter="onMdClick"></div>
        </div>

        <!-- 工具行（Q10：单步耗时；Q12-③ 中段折叠>5 步首尾保留；终态可点开看真实返回 JSON） -->
        <div v-else class="tool" :class="'st-' + it.step.status" :data-step-id="it.step.id">
          <span
            class="th"
            :class="{ clickable: isTerminal && !!it.step.resultFull }"
            :tabindex="isTerminal && it.step.resultFull ? 0 : -1"
            role="button"
            @click="toggleStep(it.step.id)"
            @keydown.enter="toggleStep(it.step.id)"
          >
            <IconSpan v-if="it.step.status === 'run'" name="loader-circle" cls="spin mini" />
            <IconSpan v-else-if="it.step.status === 'ok'" name="check-circle" cls="ok-ic" />
            <IconSpan v-else name="circle-x" cls="fail-ic" />
            <span class="s-verb">{{ it.step.verb }}</span>
            <span v-if="stepDur(it.step)" class="s-dur">· {{ stepDur(it.step) }}</span>
            <!-- TW-1 原型口径：失败词下划虚线=点击复制错误信息 -->
            <span
              v-if="it.step.status === 'err'"
              class="failword"
              title="点击复制错误信息"
              role="button"
              tabindex="0"
              @click.stop="copyFail(it.step)"
              @keydown.enter.stop="copyFail(it.step)"
              >失败</span
            >
            <span v-if="isTerminal && it.step.resultFull" class="s-exp"
              ><IconSpan :name="openSteps.has(it.step.id) ? 'chevron-up' : 'chevron-down'" :size="12"
            /></span>
          </span>
          <span v-if="it.step.argsText" class="args">{{ shortArgs(it.step.argsText) }}</span>
          <div v-if="openSteps.has(it.step.id) && it.step.resultFull" class="s-json">{{ it.step.resultFull }}</div>
        </div>
      </template>
    </div>

    <!-- 已用工具章（原型 stamp）：UI 专项批1 删除——折叠态胶囊「用了 N 个工具」、
         展开态工具行 verb 双侧都与其重复（视觉评审 ui3 两轮实锤），动词语义留这两处承载 -->

    <!-- 计划卡（P1-3：CUSTOM plan 事件驱动，后端无源恒不渲染） -->
    <plan-card
      v-if="turn.plan"
      :plan="turn.plan"
      @confirm-plan="emit('ask', '按刚才的计划开始执行')"
      @edit-plan="(t) => emit('resend', t)"
      @cancel-plan="emit('plan-cancel')"
    />

    <!-- 正文气泡：流式=纯文本+光标（S-23 仅末段增量）；完成=全量 md；
         UI 专项批1：卡内「已完成」章删除——与顶部状态胶囊重复报状态（视觉评审 7.4 轮实锤），成功语义只留胶囊+浅绿描边 -->
    <div v-if="turn.bufText || turn.answerHtml" class="bub" :class="{ 'g-ok': turn.phase === 'done' && turn.toolCalls > 0 }">
      <!-- eslint-disable-next-line vue/no-v-html —— md.ts 先转义后白名单替换，无注入面；点击委托处理链导航/代码卡/表格复制 -->
      <div v-if="turn.answerHtml" class="md" v-html="turn.answerHtml" @click="onMdClick" @keydown.enter="onMdClick" />
      <div v-else class="stream">
        <template v-if="turn.bufText">{{ turn.bufText.slice(0, turn.shownLen) }}</template
        ><span v-else class="dots"><i /><i /><i /></span><span class="cursor" />
      </div>
      <!-- 批6-#2：中断回答徽标（历史回放 end_reason=partial/truncated）——刷新/断网保全的
           部分内容不再假装完整答案；partial 态短暂存在（轮询收敛后转 truncated 同标 -->
      <div v-if="turn.interrupted" class="cut-mark">回答被中断 · 以上是已生成的部分，内容可能不完整</div>
    </div>

    <!-- 业务批10 #2/#4：空回答轮诚实化——done 却无正文无工具=模型没吐过一个字（零事件卡死被
         收割/重放悬挂轮），不再假装"已完成"绿胶囊+静态推荐题；给人话结论+一键重问出路 -->
    <div v-if="emptyFail" class="ecard">
      这轮没等到回答（模型卡住或中断了）
      <button v-if="turn.question" class="ec-recover" @click="emit('resend-run', turn.question)">↻ 重新问一次</button>
    </div>

    <!-- 错误卡（AU-35 1008拍板A：挂起中断类=恢复优先双按钮——code+框架文案双保险判定，
         不再只匹配英文文案；「恢复挂起的确认」调 /hitl/pending 重建确认卡，会话不废） -->
    <div v-if="turn.errorMsg" class="ecard">
      {{ turn.errorMsg }}
      <!-- 业务批(1015) Q9：人话化映射后的原始报错收进折叠——用户不慌，排错有现场 -->
      <details v-if="turn.errorDetail" class="ec-detail">
        <summary>查看技术详情</summary>
        <code>{{ turn.errorDetail }}</code>
      </details>
      <template v-if="isInterruptErr">
        <button class="ec-recover" @click="emit('recover-interrupt')">↩ 恢复挂起的确认</button>
        <button class="ec-escape" @click="emit('new-session')">新建会话</button>
      </template>
      <!-- 业务批10 #4：非中断类失败（模型无应答/网络断等）给一键重试——原问题原样重发，不改会话历史 -->
      <button v-else-if="turn.question" class="ec-recover" @click="emit('resend-run', turn.question)">↻ 重试</button>
    </div>

    <!-- 停止便条（v3.18⑤：继续说完=按原问题重新请求；Q10 预期管理：说明会整段重生成，不是接着写） -->
    <div v-if="turn.phase === 'stopped'" class="stop-note">
      已停止——上面是已生成的部分。<button class="sn-go" @click="emit('resume')"><IconSpan name="play-circle" /> 继续说完</button
      ><span class="sn-hint">（按原问题重新生成完整回答，不是从断处接着写）</span>
    </div>

    <!-- HITL 确认卡（E3：waiting 态渲染；resolve 链路上浮到状态机 resolveHitl） -->
    <!-- 等待确认 or 已裁决（取消/执行完回执保留展示） -->
    <!-- 批F2 表单卡分派（SPEC §2.2 二站）：建任务=真实 EP 控件表单，改一改=当前值回注对话；
         R4a：建模板=同族 TemplateCard（字段清单动态行） -->
    <form-card
      v-if="turn.hitl && turn.hitl.toolName === 'act_create_mission' && (turn.phase === 'waiting' || turn.hitl.cardState !== 'pending')"
      :ask="turn.hitl"
      @resolve="(a, r, e) => emit('resolve-hitl', a, r, e)"
      @revise="(m) => emit('ask', m)"
    />
    <template-card
      v-else-if="turn.hitl && turn.hitl.toolName === 'act_create_template' && (turn.phase === 'waiting' || turn.hitl.cardState !== 'pending')"
      :ask="turn.hitl"
      @resolve="(a, r, e) => emit('resolve-hitl', a, r, e)"
      @revise="(m) => emit('ask', m)"
    />
    <confirm-card
      v-else-if="turn.hitl && (turn.phase === 'waiting' || turn.hitl.cardState !== 'pending')"
      :ask="turn.hitl"
      @resolve="(a, r, e) => emit('resolve-hitl', a, r, e)"
    />

    <!-- 来源折叠条（批C：🗂 N 个来源 ▾；rows 型工具=DataCard 数据原件，其余=文本摘要） -->
    <div v-if="hasFoot && turn.toolCalls > 0" class="srcwrap">
      <button class="srcbar" :aria-expanded="srcOpen" @click="srcOpen = !srcOpen">
        <IconSpan name="history" /> {{ turn.steps.length }} 个来源 <IconSpan name="chevron-down" />
      </button>
      <div v-if="srcOpen" class="srclist">
        <template v-for="(s, i) in turn.steps" :key="s.id">
          <data-card v-if="isRowsStep(s)" :step="s" @pgoto="(p) => emit('pgoto', p)" />
          <div v-else class="srcitem">
            <b>[{{ i + 1 }}]</b> {{ s.verb }}
            <span v-if="s.argsText" class="srcmeta">{{ shortArgs(s.argsText) }}</span>
            <div v-if="s.brief" class="srcexcerpt">{{ s.brief }}</div>
          </div>
        </template>
      </div>
    </div>

    <!-- 业务批(1015) Q6：联网搜索来源卡——受控可点外链的唯一出口（点击前再过协议白名单+
         noopener 新开）。业务反馈批1015c Q6(拍板A)：favicon 改后端代理（/dcs/ai/favicon，
         DuckDuckGo ip3 源+Redis 缓存）——前端直连外网图标域名在国内网络不可达（google s2
         曾回退成纯字母），后端代理在内网部署也稳；代理 miss（404/断网）onerror 回退首字母块。
         正文里模型手写的外链维持纯文本（md.ts 白名单），钓鱼面收窄到这份真实搜索结果 -->
    <div v-if="turn.citations?.length" class="cite">
      <button class="cite-bar" :aria-expanded="citeOpen" title="这轮联网搜索引用的网页，点击可打开（新标签）" @click="citeOpen = !citeOpen">
        <IconSpan name="globe" /> {{ turn.citations.length }} 个来源 <IconSpan name="chevron-down" />
      </button>
      <div v-if="citeOpen" class="cite-list">
        <button v-for="(c, i) in turn.citations" :key="c.url + i" class="cite-card" :title="`${c.title}\n${c.url}`" @click="openCitation(c.url)">
          <span class="cite-fav">
            <img
              v-if="!favHostDead.has(hostOf(c.url))"
              class="cite-favimg"
              :src="`${favBase}/dcs/ai/favicon?host=${encodeURIComponent(hostOf(c.url))}`"
              alt=""
              loading="lazy"
              @error="favHostDead.add(hostOf(c.url))"
            />
            <template v-else>{{ (hostOf(c.url) || '?').slice(0, 1).toUpperCase() }}</template>
          </span>
          <span class="cite-main">
            <b>{{ c.title }}</b>
            <span class="cite-dom">{{ c.siteName || hostOf(c.url) }} ↗</span>
          </span>
        </button>
        <div class="cite-note">链接来自第三方网站，请注意辨别真伪；标题与摘要由搜索引擎返回</div>
      </div>
    </div>

    <!-- 追问 chips（原型 fu）——业务反馈批1015c Q10(拍板A)：删静态兜底池，贴题追问宁缺毋滥
         （对齐 lobe-chat/open-webui：侧车生成失败就不显示，不再回退写死的通用问题冒充贴题）；
         空回答轮不挂推荐题（业务批10 #2 口径保留）；点击=填入输入框（业务批(1015) Q10） -->
    <div v-if="turn.phase === 'done' && !emptyFail && followups.length" class="fu">
      <button v-for="f in followups" :key="f" class="chip" :title="`${f}（点击填入输入框，可修改后发送）`" @click="emit('fill', f)">{{ f }}</button>
    </div>

    <!-- 尾行（原型 fua）：hover 显现操作行——复制/重试/分支/赞/踩(问卷)/收藏 + AI 角标 + 用量 -->
    <!-- E4 纪律：成本只用后端元值（title 悬停），前端禁自算价格 -->
    <div v-if="hasFoot" class="foot">
      <button class="fua-b" :title="copied ? '已复制' : '复制这轮回答的全文'" :aria-label="copied ? '已复制' : '复制'" @click="onCopy">
        <IconSpan :name="copied ? 'check' : 'clipboard'" />
      </button>
      <!-- 五需求批 R4b / Q7-A 一键汇报：Word（结论+表格）/ Excel（表格明细）下载。
           业务批(1015) Q5 回修：原手写 .reppop 浮层长在滚动容器内，贴近视口上缘被裁成
           "点了没反应"——改 el-popover（teleport 到 body + 自动翻转方向），根治裁剪 -->
      <el-popover v-model:visible="repOpen" trigger="click" placement="top" :width="232" popper-class="dcs-rep-pop">
        <template #reference>
          <button class="fua-b" title="把这轮的结论导出成 Word 汇报 / Excel 明细" aria-label="生成汇报" :aria-expanded="repOpen">
            <IconSpan name="file" />
          </button>
        </template>
        <div class="rep-inner">
          <b>生成汇报</b>
          <button class="rep-row" :disabled="repBusy" @click="exportReport('docx')"><IconSpan name="file" /> Word 汇报（结论+表格）</button>
          <button class="rep-row" :disabled="repBusy" @click="exportReport('xlsx')"><IconSpan name="grid" /> Excel 明细（表格数据）</button>
        </div>
      </el-popover>
      <button class="fua-b" title="重试：按原问题重新生成这轮回答" aria-label="重试" @click="emit('resend-run', turn.question)">
        <IconSpan name="refresh" />
      </button>
      <button class="fua-b" title="分支：从这轮分叉，带着前文开新会话（原会话保留）" aria-label="分支" @click="onBranch">
        <IconSpan name="arrow-up-right" />
      </button>
      <button class="fua-b emj" :class="{ on: voted === 'UP' }" title="这轮回答有帮助" aria-label="点赞" @click="onVote('UP')">
        <IconSpan name="thumbs-up" />
      </button>
      <div class="fb-host">
        <button
          class="fua-b emj"
          :class="{ on: voted === 'DOWN' }"
          title="没帮助，告诉我怎么改"
          aria-label="点踩"
          :aria-expanded="fbOpen"
          @click="fbOpen = !fbOpen"
        >
          <IconSpan name="thumbs-down" />
        </button>
        <div v-if="fbOpen" class="fbpop" role="dialog" aria-label="反馈问卷">
          <b>哪里不对？</b>
          <label v-for="r in FB_REASONS" :key="r" class="fr"><input v-model="fbReason" type="radio" :value="r" /> {{ r }}</label>
          <textarea v-model="fbNote" placeholder="补充一句（可选）" />
          <div class="fb-btns">
            <button type="button" class="fb-cancel" @click="fbOpen = false">取消</button>
            <button type="button" class="fb-go" @click="onVote('DOWN')">提交</button>
          </div>
        </div>
      </div>
      <button
        class="fua-b emj star"
        :class="{ on: faved }"
        :title="faved ? '已在「我的常用」' : '收藏这个问题，加入我的常用'"
        aria-label="收藏"
        @click="onFav"
      >
        <IconSpan name="star" />
      </button>
      <span v-if="turn.tokens != null" class="fua-t" :title="turn.costYuan != null ? `本回合费用 ¥${turn.costYuan.toFixed(4)}` : '本回合用量'"
        ><!-- 批I 零技术词：tok 英文单位去掉，纯数字+图标（换算成「字」会失真，不给伪精确单位） --><IconSpan name="zap" /> 用量
        {{ turn.tokens }}</span
      >
    </div>
  </div>
</template>

<script lang="ts">
import { reactive } from 'vue';
// Q9-A 会话内开合记忆（批8-3 回修 glm53 P2-9）：原实现把 sessionThinkOpen 写在 script setup 里
// =实例级变量，而每回合 :key=turn.id 换新实例——「同会话多回合共享记忆」从未生效；
// 真 module 级按 threadId 分键：同会话共享、换会话天然隔离、刷新页面即回偏好
const thinkOpenByThread = new Map<string, boolean>();
// 分支教学 toast 记名册（批1015c Q5）：必须 module 级——TurnBlock 每回合换实例，
// 写在 script setup 里=每回合首点都弹一遍教学（m3/dspro 三审 P2，同 sessionThinkOpen 错型）
const branchTaught = new Set<string>();
// favicon 失败黑名单（批1015c Q6）：module 级共享——同一 host 的坏图标跨回合/跨会话
// 只探测一次，防翻多回合搜索卡时同 host 反复打代理把 IP 限流(60/分)烧满（m3 P1-3）
const favHostDead = reactive(new Set<string>());
</script>

<script setup lang="ts">
/**
 * AI 回合块（批E Task E2，原型 .mrow.ai 翻译）：tstat 状态行 + 思考行 + 工具行
 * + 正文气泡（打字机流式/完成 md）+ 错误卡 + 停止便条 + 确认卡 + AI 角标。
 * 批C 补件：工具章/已完成章/来源折叠条/追问 chips/hover 六图标操作行（fua）
 * + 👎问卷（submitFeedback 落库）+ ⭐收藏（favBar）+ 代码卡复制（事件委托）。
 */
import type { AssistantStep, AssistantTurn, HitlEdits, ThinkSeg } from './useAssistant';
import { createVNode, render, watch } from 'vue';
import ConfirmCard from './ConfirmCard.vue';
import DataCard from './DataCard.vue';
import FormCard from './FormCard.vue';
import TemplateCard from './TemplateCard.vue';
import PlanCard from './PlanCard.vue';
// 批4回修 P2（两席共识）：异步组件——echarts（gzip ~300KB）只在回答真出现图占位时才下载，
// 不随抽屉 chunk 人人必载；createVNode 支持异步组件，加载完成自动填充 host
const ChartBlock = defineAsyncComponent(() => import('./ChartBlock.vue'));
import IconSpan from './IconSpan.vue';
import { emoToSvgHtml } from './icons';
import { turnStateWord } from './assistantCtx';
import { renderMd } from './md';
import { parseToolJson, summarizeArgs } from './toolJson';
import { submitFeedback, listConversations, listMessages, exportAssistantReport } from '@/api/dcs/assistant';
import { pushToast } from './toastStore';
import { useFavs } from './favStore';

const props = defineProps<{ turn: AssistantTurn; turnIndex?: number; now: number; threadId?: string; showThink?: boolean }>();
const rootEl = ref<HTMLElement | null>(null);
const emit = defineEmits<{
  resume: [];
  /** Q12-② 停止思考：思考进行中胶囊旁中断入口——上浮到宿主 onStop（复用整轮停止通道） */
  stop: [];
  'resolve-hitl': [approved: boolean, remember?: boolean, edits?: HitlEdits];
  'new-session': [];
  /** AU-35：错误卡「恢复挂起的确认」——上浮到状态机 recoverInterrupt（peek 悬挂缓存重建卡） */
  'recover-interrupt': [];
  resend: [text: string];
  /** BN-7 原型口径：重试=直接重发（resend=回填编辑，两者分离） */
  'resend-run': [text: string];
  branch: [turn: AssistantTurn];
  ask: [text: string];
  /** 业务批(1015) Q10：追问 chip 点击=回填输入框不直接发（复用 HeroWelcome「帮我填一句」通道） */
  fill: [text: string];
  pgoto: [payload: { missionId: string; missionName: string }];
  /** 批3 Q6 站内链/任务卡导航（回修 P1-2：上浮宿主——抽屉态要先收抽屉再跳转，
   *  直接 router.push 会把目标页压在抽屉遮罩后面；session 页直接 push） */
  mdnav: [url: string];
  'plan-cancel': [];
}>();

/** 👎问卷三选一（原型 fbpop 固定文案） */
const FB_REASONS = ['答非所问', '数据不对', '看不懂'];

// Q9-A（2026-10-09 拍板）：思考块开合=会话内记忆——用户手点过的最后状态在同会话后续回合
// 沿用（T-2「过程显示」偏好退居没点过时的初始默认）；换会话天然重置回偏好（Map 按 threadId 分键）。
// R3(2026-10-10 拍板A)：分段版——thinkBaseOpen=未点过段的默认基准（会话记忆 > 偏好 > 完成自动收）；
// 用户点过的段进 segOpen 显式记忆；「完成自动收」由 segIsOpen 公式自然涌现（done 段未点过=收）
const sessionThreadId = props.threadId;
const thinkBaseOpen = ref(
  thinkOpenByThread.has(sessionThreadId)
    ? !!thinkOpenByThread.get(sessionThreadId)
    : !!props.showThink && !(props.turn.thinkDone && !['thinking', 'acting', 'writing'].includes(props.turn.phase))
);
/** R3 分段显式开合记忆：segId → 用户点过后的状态（点哪条开哪条） */
const segOpen = ref(new Map<string, boolean>());
function segIsOpen(seg: ThinkSeg): boolean {
  const touched = segOpen.value.get(seg.id);
  if (touched !== undefined) {
    return touched;
  }
  return seg.done ? false : thinkBaseOpen.value;
}
function onSegToggle(seg: ThinkSeg) {
  const next = !segIsOpen(seg);
  segOpen.value.set(seg.id, next);
  thinkBaseOpen.value = next;
  if (thinkOpenByThread.size > 100) {
    thinkOpenByThread.clear(); // 同页面生命周期内的会话数有限，兜底防无界
  }
  thinkOpenByThread.set(sessionThreadId, next);
}
watch(
  () => props.showThink,
  (v) => {
    if (!thinkOpenByThread.has(sessionThreadId)) {
      thinkBaseOpen.value = !!v;
    }
  }
);
// 流式摘要小窗跟随滚底（R3：多段只有一个在流，函数 ref 只挂进行中段；内容尾部增量，
// 不抢用户滚动——只在已贴底时跟随）
let tkLiveEl: HTMLElement | null = null;
function setTkLive(el: unknown, seg: ThinkSeg) {
  tkLiveEl = seg.done ? null : ((el as HTMLElement | null) ?? null);
}
watch(
  () => (props.turn.thinkSegs.length ? props.turn.thinkSegs.map((s) => s.text.length).join(',') : props.turn.thinkText.length),
  () => {
    const el = tkLiveEl;
    if (el && el.scrollHeight - el.scrollTop - el.clientHeight < 24) {
      el.scrollTop = el.scrollHeight;
    }
  }
);
const openSteps = ref(new Set<string>());
const srcOpen = ref(false);
const copied = ref(false);
const voted = ref<'' | 'UP' | 'DOWN'>('');
const fbOpen = ref(false);
/** BN-2 过程区折叠（原型 foldt）：答完 1s 自动收，用户手点过后不再自动。
 *  批2-1 Q4（拍板 2026-10-10）：历史回放初始值改为「回合进来时已是终态则折叠」——此前恒 false，
 *  刷新/重开会话时满屏过程明细铺开（三框架共识：cline/assistant-ui/open-webui 历史默认折叠）；
 *  live 回合 phase 从 thinking 起、初始非终态不受影响 */
const isTerminal = computed(() => ['done', 'stopped', 'error', 'waiting'].includes(props.turn.phase));
const folded = ref(isTerminal.value);
let userFolded = false;
let foldTimer: ReturnType<typeof setTimeout> | null = null;

function toggleFold() {
  folded.value = !folded.value;
  userFolded = true;
}

/** DP-3：统计面板跳步骤命中本回合但过程区已折叠（MsgFlow 派发）——展开并钉住（不再自动收） */
function onExpandFold() {
  folded.value = false;
  userFolded = true;
}

/** TW-1：失败词点击复制错误信息（原型 .failword 虚线下划 cursor:copy） */
function copyFail(s: { resultFull?: string; brief?: string; argsText?: string }) {
  const text = s.resultFull || s.brief || s.argsText || '';
  const done = () => pushToast(text ? '错误信息已复制' : '该步骤没有可复制的错误详情', text ? 'ok' : 'info');
  if (navigator.clipboard?.writeText && text) {
    navigator.clipboard.writeText(text).then(done, done);
  } else {
    done();
  }
}
const fbReason = ref(FB_REASONS[0]);
const fbNote = ref('');

const { has, add, remove } = useFavs();
const faved = computed(() => has(props.turn.question));

const live = computed(() => ['thinking', 'acting', 'writing'].includes(props.turn.phase));

/** BN-2 原型口径：答完 1s 自动收起过程明细（用户手动展开/收起过则尊重用户） */
watch(
  () => props.turn.phase,
  (ph) => {
    if (ph === 'done' && !userFolded) {
      if (foldTimer) clearTimeout(foldTimer);
      foldTimer = setTimeout(() => {
        folded.value = true;
        foldTimer = null;
      }, 1000);
    }
  }
);
onBeforeUnmount(() => {
  if (foldTimer) clearTimeout(foldTimer);
  tblRo?.disconnect();
  tblRo = null;
  document.removeEventListener('mousedown', onFbDocDown);
  document.removeEventListener('keydown', onFbEsc);
  unmountCharts();
});

/** 批4 Q5：chart 占位挂载——answerHtml 是 v-html，.dcs-chart 进不了虚拟树，
 *  终态渲染后手动 createVNode+render 挂 ChartBlock（ECharts）。answerHtml 重置
 *  （重发/分支）会整体重绘 innerHTML，先卸旧实例再挂新防 echarts 泄漏；
 *  流式期 answerHtml 恒空不受影响（S-23：流式不解析 md）。 */
const chartHosts: { el: Element; unmount: () => void }[] = [];
function unmountCharts() {
  chartHosts.forEach((h) => h.unmount());
  chartHosts.length = 0;
}
function mountCharts() {
  const root = rootEl.value;
  if (!root) {
    return;
  }
  // 批4回修 P2（两席共识）：只挂正文气泡内的占位——segHtml（思考块）走同一 renderMd，
  // 思考文本里的 chart 围栏也产 .dcs-chart 占位，挂进思考区既非预期、折叠销毁 DOM 时
  // dispose 也不触发；收窄后思考块围栏自然保持占位不挂图
  root.querySelectorAll<HTMLElement>('.bub .md .dcs-chart:not([data-mounted])').forEach((host) => {
    const spec = host.getAttribute('data-chart');
    if (!spec) {
      return;
    }
    host.setAttribute('data-mounted', '1');
    const vnode = createVNode(ChartBlock, { spec });
    render(vnode, host);
    chartHosts.push({ el: host, unmount: () => render(null, host) });
  });
  // 业务批4 Q12 基线修：横向滚动提示——.tbl-scroll 实际溢出才挂 .can-scroll
  // （右缘 hover 渐隐+卡条「横滑」提示只给真溢出的表，窄表保持完整不盖字）
  measureTables();
}
/** Q12 表格溢出复测：m3 P1-3 回修——窗口 resize/抽屉全屏切换/520px 媒体查询跨越都会
 *  改变气泡宽度使溢出状态翻转，靠 ResizeObserver 兜底重测（挂类幂等，无副作用） */
function measureTables() {
  const root = rootEl.value;
  if (!root) {
    return;
  }
  root.querySelectorAll<HTMLElement>('.bub .md .tbl-scroll').forEach((sc) => {
    sc.classList.toggle('can-scroll', sc.scrollWidth > sc.clientWidth + 2);
  });
}
watch(
  () => props.turn.answerHtml,
  () => {
    unmountCharts();
    nextTick(mountCharts);
  }
);
let tblRo: ResizeObserver | null = null;
onMounted(() => {
  mountCharts();
  if (typeof ResizeObserver !== 'undefined' && rootEl.value) {
    tblRo = new ResizeObserver(() => measureTables());
    tblRo.observe(rootEl.value);
  }
});

/** AU-27: 反馈问卷点外/Esc 关闭（sessMenu 范式）——开着时挂一次性监听 */
function onFbDocDown(ev: MouseEvent) {
  if (!(ev.target as HTMLElement)?.closest('.fb-host:not(.rep-host)')) {
    fbOpen.value = false;
  }
}
function onFbEsc(ev: KeyboardEvent) {
  if (ev.key === 'Escape') {
    fbOpen.value = false;
  }
}
watch(fbOpen, (open) => {
  document.removeEventListener('mousedown', onFbDocDown);
  document.removeEventListener('keydown', onFbEsc);
  if (open) {
    setTimeout(() => {
      document.addEventListener('mousedown', onFbDocDown);
      document.addEventListener('keydown', onFbEsc);
    }, 0);
  }
});
/** 业务批(1015) Q5：onRepDocDown/onRepEsc 已随 el-popover 改造下线（见 repOpen 注释） */
/** 尾部件显隐口径：waiting（等你确认）还没有可操作的回答，不出操作行/来源条 */
const hasFoot = computed(() => ['done', 'stopped', 'error'].includes(props.turn.phase));
/** 业务批10 #2：空回答轮——done 却无正文无工具（零事件卡死收割后重放/悬挂轮回放）。
 *  判定与 statText/推荐题/错误卡三处联动：不显示"已完成"、不荐题、给人话+重问出路 */
const emptyFail = computed(() => props.turn.phase === 'done' && !props.turn.bufText && !props.turn.answerHtml && props.turn.toolCalls === 0);

/* ---- 业务批(1015) Q6：联网搜索来源卡（citations 由 useAssistant.onToolResult 从 web_search
 *      结果结构化提取；此处只管展示与受控跳转） ---- */
const citeOpen = ref(false);
/** 业务反馈批1015c Q6：favicon 失败黑名单已上移 module 级 favHostDead（按 host 键，
 *  跨回合/跨会话共享——同一坏图标只探测一次，防翻多卡时烧满 IP 限流；m3 P1-3） */
const favBase = import.meta.env.VITE_APP_BASE_API as string;
const hostOf = (u: string): string => {
  try {
    return new URL(u).hostname;
  } catch {
    return '';
  }
};
function openCitation(url: string) {
  // 双保险：提取时已过白名单，点击前再核一次（防转手/篡改）
  if (!/^https?:\/\//i.test(url)) {
    pushToast('该链接不是网页地址，已拦截', 'err');
    return;
  }
  // 自查回修(m3 P1)：弹窗拦截器/未授权自打开时 window.open 返回 null——此前静默无反应，
  // 用户以为卡片坏了；此处如实提示放行方式
  const w = window.open(url, '_blank', 'noopener,noreferrer');
  if (!w) {
    pushToast('浏览器拦截了新标签页，请允许本站弹窗后重试', 'info');
  }
}
/** 业务反馈批1015c Q10(拍板A)：只显示后端侧车生成的贴题追问（≤3 条）；
 *  静态角色池已删——生成失败该区不渲染（宁缺毋滥，对齐行业做法） */
const followups = computed(() => props.turn.followups ?? []);
/** 业务反馈批1015c Q5(拍板A)：分支保持可用，首次点击教学一次（会话级，同 thinkOpenByThread 模式）；
 *  用户曾因"看不懂"要求置灰——教学替代置灰，功能不藏 */
function onBranch() {
  if (props.threadId && !branchTaught.has(props.threadId)) {
    branchTaught.add(props.threadId);
    pushToast('分支 = 从这轮分叉开新会话；原会话保留，前文自动带上', 'info');
  }
  emit('branch', props.turn);
}

const statCls = computed(() => ({
  live: live.value,
  err: props.turn.phase === 'error' || emptyFail.value,
  // 橙色 wait 样式只在卡真正待确认时挂（k3 P2-1：run/done 后去橙）
  wait: props.turn.phase === 'waiting' && props.turn.hitl?.cardState === 'pending',
  stopped: props.turn.phase === 'stopped'
}));

/* ---- 批J-2 原型补件：失败计数 / 图标堆 / 思考流式摘要 / 折叠失败偷看 ---- */
const failCount = computed(() => props.turn.steps.filter((s) => s.status === 'err').length);
const firstFailVerb = computed(() => props.turn.steps.find((s) => s.status === 'err')?.verb ?? '查询');
/** AU-35：挂起中断类错误——结构化 code 优先，框架英文文案兜底（同消息曾配不同 code，双保险） */
const isInterruptErr = computed(
  () =>
    !!props.turn.errorMsg &&
    (props.turn.errorCode === 'AGUI_INTERRUPT_CONTRACT_ERROR' || /unresolved interrupts|must (address|cover) all/i.test(props.turn.errorMsg))
);

/** 原型 ts-ics：acting 期堆叠去重工具图标（上限 3 防溢出；完成态由 v-if 摘除） */
const TOOL_ICON: Record<string, string> = {
  query_my_todos: 'clipboard',
  query_my_missions: 'clipboard',
  query_mission_detail: 'compass',
  query_submission_status: 'chart',
  query_deadline_alerts: 'alarm',
  query_unsubmitted: 'database',
  act_remind: 'megaphone'
};
const toolIcons = computed(() => {
  const names: string[] = [];
  for (const s of props.turn.steps) {
    if (names.length >= 3) {
      break;
    }
    const icn = TOOL_ICON[s.name] ?? 'wrench';
    if (!names.includes(icn)) {
      names.push(icn);
    }
  }
  return names;
});

/** 原型 tk-s（L523-528）已被 Q9 流式摘要小窗（tk-live 滚动窗）替代，删除 */

/** Q10 单步耗时：≥1s 显示一位小数（回放行 durMs=0 不显示） */
function stepDur(s: { durMs?: number }): string {
  const ms = s.durMs ?? 0;
  return ms >= 1000 ? (ms / 1000).toFixed(1) + 's' : '';
}

/** Q12-③ 中段折叠：步骤>5 首尾保留（首 1+尾 2），中段收「⋯ 另外 N 步」点击展开 */
const PROC_KEEP = 5;
const stepsAll = ref(false);
watch(
  () => props.turn.id,
  () => {
    stepsAll.value = false;
  }
);
const hiddenMid = computed(() => (props.turn.steps.length > PROC_KEEP ? props.turn.steps.length - 3 : 0));
const shownSteps = computed(() => {
  const all = props.turn.steps;
  if (stepsAll.value || all.length <= PROC_KEEP) {
    return all;
  }
  return [all[0], ...all.slice(-2)];
});

const statText = computed(() => {
  const t = props.turn;
  const lastRun = [...t.steps].reverse().find((s) => s.status === 'run');
  switch (t.phase) {
    case 'thinking':
      return turnStateWord('thinking');
    case 'acting':
      return turnStateWord('acting', { actingVerb: `正在${lastRun?.verb ?? '查询'}` });
    case 'writing':
      return turnStateWord('writing');
    case 'waiting': {
      // 确认卡生命周期联动（k3 视觉复审 P2-1）：卡 run/done 后状态行不再挂「等确认」
      const cs = t.hitl?.cardState;
      if (cs === 'run') return '🛡 正在执行确认的操作…';
      if (cs === 'done') return t.hitl?.cancelled ? '已取消（未执行）' : '已确认执行';
      return '🛡 ' + turnStateWord('waiting'); // 🛡 经 toast/文本通道按 EMO2IC→power 渲染（Q2=A 照搬原型）
    }
    case 'stopped': {
      // 六审回修（glm53 P2）：中途停止且已有失败步骤时点出失败数——error 态胶囊红色告警，
      // stopped 带失败却仍中性灰，折叠后用户无从知道过程里有步骤没跑成
      if (failCount.value > 0) {
        return `${turnStateWord('stopped')} · ${failCount.value} 项失败`;
      }
      return turnStateWord('stopped') + (t.toolCalls > 0 ? `（查了 ${t.toolCalls} 步）` : '');
    }
    case 'error':
      return turnStateWord('error');
    default:
      // 业务批10 #2：空回答轮不再显示"已完成"——模型一个字都没吐，绿色完成态是误导
      if (emptyFail.value) {
        return '没等到回答';
      }
      // Q10 完成折叠口径：一行读出「已完成 · 用了 N 个工具 · N 秒」（秒数由 durText 追加）
      return t.toolCalls > 0 ? `${turnStateWord('done')} · 用了 ${t.toolCalls} 个工具` : turnStateWord('done');
  }
});

/** 原型 fpeekrow：折叠态点失败行 → 展开并 flash 定位首个失败工具行 */
function expandFail() {
  folded.value = false;
  userFolded = true;
  nextTick(() => {
    const el = rootEl.value?.querySelector('.tool.st-err');
    el?.classList.add('flash');
    el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    setTimeout(() => el?.classList.remove('flash'), 1400);
  });
}

const durText = computed(() => {
  const t = props.turn;
  const ms = t.durMs > 0 ? t.durMs : live.value ? props.now - t.startedAt : 0;
  if (ms < 2100) {
    return ''; // 原型口径：≥2.1s 才显示
  }
  return ms < 60_000 ? `${(ms / 1000).toFixed(0)} 秒` : `${Math.floor(ms / 60_000)} 分 ${Math.floor((ms % 60_000) / 1000)} 秒`;
});

/* ---- R3(2026-10-10 拍板A)：思考分段视图 + 统一时间线 ---- */
/** 兜底视图：live 无分段数据（旧内存态/回放）时用 thinkText 合成单段置顶=旧行为；
 *  done 且无内容的空段收行不留空壳（Q9 口径保留） */
const thinkSegsView = computed<ThinkSeg[]>(() => {
  const t = props.turn;
  if (t.thinkSegs.length) {
    return t.thinkSegs.filter((s) => s.text || !s.done);
  }
  return t.thinkText ? [{ id: 'legacy-think', text: t.thinkText, done: t.thinkDone, startedAt: 0, durMs: t.thinkDurMs, seq: -1 }] : [];
});

/** 时间线项：think 段与 tool 步按事件序交错；sIdx=该步在 shownSteps（Q12-③ 折叠后）中的序号，
 *  供中段折叠按钮插位（i===1 前） */
type TlItem = { kind: 'think'; seg: ThinkSeg; idx: number; sIdx: -1 } | { kind: 'tool'; step: AssistantStep; idx: number; sIdx: number };
const timeline = computed<TlItem[]>(() => {
  const shown = shownSteps.value;
  const shownIdx = new Map(shown.map((s, i) => [s.id, i]));
  const items: TlItem[] = [];
  thinkSegsView.value.forEach((seg, idx) => items.push({ kind: 'think', seg, idx, sIdx: -1 }));
  props.turn.steps.forEach((step) => {
    const sIdx = shownIdx.get(step.id);
    if (sIdx !== undefined) {
      items.push({ kind: 'tool', step, idx: 0, sIdx });
    }
  });
  // 排序键：事件序（回放行无 seq=排最后按插入序稳定兜底；legacy 合成段 seq=-1 置顶）
  const key = (it: TlItem) => (it.kind === 'think' ? it.seg.seq : (it.step.seq ?? Number.MAX_SAFE_INTEGER));
  return items.sort((a, b) => key(a) - key(b));
});

/** 多段序号（思考①②…）：仅分段>1 时标号，单段保持「已思考 · N 秒」原口径 */
const SEG_NUM = ['①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧'];
function segLabel(seg: ThinkSeg, idx: number): string {
  const t = props.turn;
  // Q9 停止灰态：思考中被中断（该段未置 done 而回合已停）→「思考中断」
  if (t.phase === 'stopped' && !seg.done) {
    return '思考中断';
  }
  const multi = thinkSegsView.value.length > 1;
  const tag = multi && idx < SEG_NUM.length ? '思考' + SEG_NUM[idx] : '';
  if (seg.done) {
    // 批8-3 回修口径：优先纯思考段耗时；段无真值时退整轮口径
    const ms =
      seg.durMs > 0 ? seg.durMs : seg.startedAt > 0 ? Date.now() - seg.startedAt : t.durMs > 0 ? Math.min(t.durMs, Date.now() - t.startedAt) : 0;
    const sec = Math.max(1, Math.round(ms / 1000));
    return multi ? `${tag} · ${sec} 秒` : `已思考 · ${sec} 秒`;
  }
  return multi ? `${tag} 正在思考…` : '正在思考…';
}

/** Q9 思考中断灰态（逐段判定：cut 类=去呼吸动画、灰字） */
function segCut(seg: ThinkSeg): boolean {
  return props.turn.phase === 'stopped' && !seg.done;
}

/** Q9：思考正文 markdown 渲染（逐段；md.ts 先转义后白名单，与正文气泡同链路） */
function segHtml(seg: ThinkSeg): string {
  return renderMd(seg.text);
}

function shortArgs(a: string): string {
  // 批I 零技术词：JSON 英文键名→中文摘要（mission_id→任务 等）；非 JSON 原样截断
  const s = summarizeArgs(a);
  return s.length > 60 ? s.slice(0, 60) + '…' : s;
}

function toggleStep(id: string) {
  if (!isTerminal.value) {
    return;
  }
  const s = new Set(openSteps.value);
  if (s.has(id)) {
    s.delete(id);
  } else {
    s.add(id);
  }
  openSteps.value = s;
}

/** rows 型工具判定（来源区升级为 DataCard 数据原件；解析失败回退文本摘要） */
const ROWS_TOOLS = new Set([
  'query_my_todos',
  'query_my_missions',
  'query_mission_detail',
  'query_submission_status',
  'query_deadline_alerts',
  'query_unsubmitted',
  'query_unsubmitted_by_dept'
]);
function isRowsStep(s: { name: string; resultFull: string }): boolean {
  if (!ROWS_TOOLS.has(s.name) || !s.resultFull) {
    return false;
  }
  const j = parseToolJson(s.resultFull);
  return Array.isArray(j?.rows);
}

/** 复制整条回答（原型 msgCopy：取纯文本；bufText=完整明文累积）。
 *  业务批(1015) Q5：图标静变 1.2s 太含蓄（用户实锤"点了没反应"）——补「已复制」toast；
 *  剪贴板 API 不可用/写入失败时如实报失败，不再假装复制成功 */
function onCopy() {
  const text = props.turn.bufText || props.turn.question;
  if (navigator.clipboard?.writeText) {
    navigator.clipboard
      .writeText(text)
      .then(() => {
        copied.value = true;
        setTimeout(() => (copied.value = false), 1200);
        pushToast('已复制', 'ok');
      })
      .catch(() => pushToast('复制失败，请手动选择内容复制', 'err'));
  } else {
    pushToast('当前环境不支持一键复制，请手动选择内容复制', 'info');
  }
}

/** 五需求批 R4b / Q7-A 一键汇报：回答原文（bufText 直播/回放两形态都全量）交后端渲染 poi 流下载。
 *  业务批(1015) Q5：弹层改 el-popover（teleport+自动翻转），外点/Esc 由 EP 自管——
 *  原手写 onRepDocDown/onRepEsc 整组下线（teleport 后弹层不在 .rep-host 内，旧外点
 *  判定会把"点菜单项"误判为外点先关层，mousedown 先于 click 导致导出永远不触发） */
const repOpen = ref(false);
const repBusy = ref(false);
async function exportReport(format: 'docx' | 'xlsx') {
  if (!props.turn.bufText) {
    pushToast('该回合还没有可导出的回答', 'info');
    return;
  }
  repBusy.value = true;
  try {
    const blob = (await exportAssistantReport({
      format,
      question: props.turn.question,
      content: props.turn.bufText
    })) as unknown as Blob;
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    // 六审回修（k3 P2）：toISOString 是 UTC——东八区 0~8 点下载文件名日期是昨天，改取本地日
    const d = new Date();
    const localDate = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    a.download = `AI助手汇报_${localDate}.${format}`;
    a.click();
    URL.revokeObjectURL(url);
    pushToast(format === 'docx' ? 'Word 汇报已生成，开始下载' : 'Excel 明细已生成，开始下载', 'ok');
    repOpen.value = false;
  } catch {
    // 失败提示由 request.ts 统一弹（blob 分支解析业务 msg），此处再 toast 会双弹（六审回修 k3 P2）
  } finally {
    repBusy.value = false;
  }
}

/** 👍/👎 落库（dcs_assistant_feedback；👎带问卷 reason+补充） */
async function onVote(rating: 'UP' | 'DOWN') {
  // AU-26 防重：同向重复点（连点/提交中再点）直接忽略；voted 同步置先于 await，天然挡并发双击
  if (voted.value === rating) {
    fbOpen.value = false;
    return;
  }
  const note = rating === 'DOWN' ? [fbReason.value, fbNote.value.trim()].filter(Boolean).join('：') : undefined;
  voted.value = rating;
  fbOpen.value = false;
  // messageId 契约：后端只收真实消息表 Long id（turn.id 是前端回合号会被 400「消息 ID 不合法」）；
  // 惰性两跳解析 threadId→会话→最后一条 assistant 消息；解析不到降级整会话评价（后端允许空）
  const mid = await resolveMessageId();
  submitFeedback({ messageId: mid, rating, note }).then(
    () => pushToast(rating === 'UP' ? '已记录你的反馈' : '已收到，我们会改进', 'ok'),
    () => pushToast('反馈提交失败，稍后再试', 'err')
  );
}

/** AU-56(1008 拍板B)：反馈按「回合序号配对走位」精确归属——消息列表 user→assistant
 *  交替配对与 rebuildFromHistory 同款（中断留下的"无回答回合"不位移后续归属）；
 *  对不上（超 100 条 clamp 窗口/回合尚无消息）→ null 降级整会话评价（后端允许空 messageId） */
async function resolveMessageId(): Promise<number | string | null> {
  const tid = props.threadId;
  if (!tid) {
    return null;
  }
  try {
    // AU-19 关联缓解：反馈归属按 threadId 找会话行——50 条上限外的旧会话找不到行=反馈丢失，
    // 放宽到后端 clamp 上限 100
    const convs = (await listConversations({ pageNum: 1, pageSize: 100 })) as unknown as {
      rows?: Array<{ id: number | string; threadId: string }>;
    };
    const conv = (convs?.rows ?? []).find((c) => c.threadId === tid);
    if (!conv) {
      return null;
    }
    const msgs = (await listMessages(conv.id, { limit: 100 })) as unknown as {
      data?: Array<{ id: number | string; role: string }>;
    };
    const list = msgs?.data ?? [];
    if (props.turnIndex == null) {
      // 宿主未传序号（非 MsgFlow 挂载）：维持旧口径=最后一条 assistant 消息
      const last = [...list].reverse().find((m) => m.role === 'assistant');
      return last?.id ?? null;
    }
    let ti = -1;
    for (const m of list) {
      if (m.role === 'user') {
        ti++;
      } else if (m.role === 'assistant' && ti === props.turnIndex) {
        return m.id;
      }
    }
    return null;
  } catch {
    return null;
  }
}

/** ⭐ 收藏问答句进「我的常用」（上限 6，favBar 展示前 4） */
function onFav() {
  const q = props.turn.question;
  if (has(q)) {
    remove(q);
    pushToast('已从「我的常用」移除', 'ok');
  } else if (add(q)) {
    pushToast('已加入「我的常用」：' + q.slice(0, 14), 'ok');
  } else {
    pushToast('「我的常用」已满（6 条），先移除一条再收藏', 'err');
  }
}

/** md 区点击/回车委托（v-html，元素不进虚拟树）：
 *  ①站内直达链 a[data-dcs-link]/任务卡按钮 → 上浮宿主 mdnav（批3回修 P1-2：抽屉态收抽屉再跳，白名单在 md.ts 侧把关）；
 *  ②代码卡/表格「复制」→ 剪贴板（表格转 TSV，Q7-2；table 优先于 code——.tbl 单元格里的行内代码不算复制目标，回修 P1-1） */
function tableToTsv(table: Element): string {
  return [...table.querySelectorAll('tr')]
    .map((tr) => [...tr.children].map((td) => (td.textContent ?? '').replace(/[\t\n]+/g, ' ').trim()).join('\t'))
    .join('\n');
}
/** 剪贴板兜底（回修 P1-3）：http 局域网下 navigator.clipboard 不存在——退化 execCommand
 *  textarea 方案（同 src/directive/common/copyText.ts 口径）；失败 reject 交调用方提示 */
function writeClipboard(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    return navigator.clipboard.writeText(text);
  }
  return new Promise((resolve, reject) => {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;left:-9999px;top:0';
    document.body.appendChild(ta);
    ta.select();
    try {
      if (document.execCommand('copy')) {
        resolve();
      } else {
        reject(new Error('execCommand copy failed'));
      }
    } finally {
      ta.remove();
    }
  });
}
function onMdClick(e: MouseEvent | KeyboardEvent) {
  const link = (e.target as HTMLElement).closest('[data-dcs-link]');
  if (link) {
    e.preventDefault();
    const url = link.getAttribute('data-dcs-link');
    if (url) {
      emit('mdnav', url);
    }
    return;
  }
  const btn = (e.target as HTMLElement).closest('.cc-copy, .tbl-copy');
  if (!btn) {
    return;
  }
  const host = btn.closest('.cc, .tbl');
  const code = host?.querySelector('code');
  const table = host?.querySelector('table');
  const text = table ? tableToTsv(table) : (code?.textContent ?? '');
  writeClipboard(text)
    .then(() => {
      btn.textContent = '已复制';
      setTimeout(() => (btn.textContent = '复制'), 1200);
    })
    .catch(() => pushToast('复制失败，请手动选择内容复制', 'err'));
}
</script>

<style lang="scss" scoped>
.dcs-turn {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 8px;

  /* 批3 Q6/Q7（回修 P2-1）：站内链/任务卡/表格容器挂根级 :deep——正文气泡 .md 与
     思考块 .tk-b 两处 v-html 共用同一份样式，思考块内不再是死按钮+裸样式 */
  :deep(.dcs-chart) {
    margin: 8px 0;
  }
  :deep(.dcs-link) {
    color: var(--el-color-primary);
    text-decoration: none;
    cursor: pointer;

    &:hover {
      text-decoration: underline;
    }
  }
  :deep(.tbl) {
    /* 表格优化回修（用户截图实锤）：外框卡也随内容收缩——原块级 width:auto 在宽宿主
     * （全屏会话页 1075px 内容列）里占满整行，内部 table 收缩后卡框右侧大片空白+
     * 复制钮悬在空白区+表格右缘无闭合边框。fit-content 让卡贴表格走（窄表=窄卡，
     * 卡外是气泡正常行文空白，与短段落同排版）；max-width 兜底宽表不撑破 */
    width: fit-content;
    max-width: 100%;
    margin: 6px 0;
    border: 1px solid var(--dcs-border-color-light);
    border-radius: 8px;
    overflow: hidden;
    /* 回修（dspro P2-5③）：撤 .bub 白卡后表格补自身白壳（灰底消息区上卡片感落在表格上，
       cherry-studio 同款取向）——注释与代码对齐 */
    background: var(--el-bg-color);

    .tbl-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 3px 10px;
      background: var(--el-fill-color-light);
      color: var(--dcs-text-secondary);
      font-size: 11px;

      /* 业务批4 Q12 基线修：溢出表卡条给「横滑」提示（.can-scroll 由 mountCharts 实测溢出挂类；
         :has 串联=窄表不提示，与右缘渐隐同源同宽 */
      span:first-child::after {
        content: '';
      }
    }
    &:has(> .tbl-scroll.can-scroll) .tbl-bar span:first-child::after {
      content: ' · ←→ 可横滑';
    }
    .tbl-copy {
      border: 1px solid var(--dcs-border-color-light);
      background: var(--el-bg-color);
      color: var(--dcs-text-secondary);
      border-radius: 4px;
      padding: 1px 8px;
      font-size: 11px;
      cursor: pointer;

      &:hover {
        color: var(--el-color-primary);
        border-color: var(--el-color-primary-light-5);
      }
    }
    /* 表格优化：滚动承载移到独立容器（table 不再 display:block 块化），宽度自适应见 .md table 段。
     * 业务批5 Q5-乙（2026-10-13 拍板）：撤 340px 限高——业界 7 个同类产品无一限高，整表
     * 展开随页滚（业务红圈痛点：36 行只见 9 行+横向滚动条悬在半截腰压行；全展开后滚动条
     * 自然落到表格真正底部，两痛点一起解）。配套：padding-bottom 让位防滚动条贴末行文字
     * （LibreChat 同款）；移动端触摸惯性横滑+滚动遏制（横向滚动不外溢到页面） */
    .tbl-scroll {
      overflow-x: auto;
      overflow-y: auto;
      max-height: none;
      -webkit-overflow-scrolling: touch;
      overscroll-behavior-x: contain;
      scrollbar-width: thin;
      position: relative;
      /* 回修（dspro P2-5①）：让位只给真溢出表——窄表末行与卡底不留 6px 灰缝（can-scroll 由
         measureTables 实测溢出挂类，与"可横滑"提示同源） */
      &.can-scroll {
        padding-bottom: 6px;
      }

      &::-webkit-scrollbar {
        height: 6px;
        width: 6px;
      }
      &::-webkit-scrollbar-thumb {
        background: var(--dcs-border-color);
        border-radius: 3px;
      }
      &.can-scroll::after {
        content: '';
        position: absolute;
        top: 0;
        right: 0;
        bottom: 0;
        max-height: 420px;
        width: 26px;
        background: linear-gradient(to right, transparent, var(--el-fill-color-lighter, #fafafa));
        opacity: 0;
        pointer-events: none;
        transition: opacity 0.15s;
      }
      &.can-scroll:hover::after {
        opacity: 1;
      }
    }
    table {
      margin: 0;
    }
    /* 业务批4 Q12：状态列小标签（md.ts CELL_TAG 升格）——四色口径与 statusColor 人话对齐 */
    .cell-tag {
      display: inline-block;
      padding: 0 7px;
      border-radius: 999px;
      font-size: 11px;
      line-height: 18px;
      white-space: nowrap;
    }
    .ct-ok {
      color: var(--el-color-success);
      background: var(--el-color-success-light-9);
    }
    .ct-bad {
      color: var(--el-color-danger);
      background: var(--el-color-danger-light-9);
    }
    .ct-mid {
      color: var(--el-color-warning);
      background: var(--el-color-warning-light-9);
    }
    .ct-dim {
      color: var(--dcs-text-secondary);
      background: var(--el-fill-color-light);
    }
  }
  :deep(.task-card) {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin: 10px 0;
    padding: 10px 14px;
    background: var(--el-color-primary-light-9);
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: 8px;

    .tc-info {
      min-width: 0;
    }
    .tc-head {
      display: flex;
      align-items: center;
      gap: 8px;
      min-width: 0;
    }
    .tc-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--el-color-warning);
      flex: none;
    }
    .tc-title {
      color: var(--el-color-primary);
      font-weight: 600;
      cursor: pointer;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;

      &:hover {
        text-decoration: underline;
      }
    }
    .tc-meta {
      margin-top: 2px;
      font-size: 12px;
      color: var(--dcs-text-secondary);
    }
    .tc-btn {
      flex: none;
      border: none;
      background: var(--el-color-primary);
      color: #fff;
      border-radius: 4px;
      padding: 6px 14px;
      font-size: 13px;
      cursor: pointer;

      &:hover {
        background: var(--el-color-primary-light-3);
      }
    }
    @media (max-width: 520px) {
      flex-wrap: wrap;

      .tc-btn {
        margin-left: auto;
      }
      .tc-title {
        white-space: normal;
      }
    }
  }
}
.tstat {
  /* UI 专项批1（5.3fh 审查「全宽横条语义模糊像禁用 select」）：瘦身行内胶囊——
     auto 宽随内容收窄，灰底浮于消息区，既是回合身份行（动词+耗时）也是折叠开关 */
  display: inline-flex;
  align-items: center;
  gap: 7px;
  width: fit-content;
  max-width: 100%;
  padding: 2px 8px 2px 7px;
  margin-bottom: 8px;
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 999px;
  background: var(--el-bg-color);
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.04);
  font: inherit;
  text-align: left;
  cursor: pointer;
  font-size: 12.5px;
  color: var(--dcs-text-secondary);
  user-select: none;
  font-variant-numeric: tabular-nums;
  align-self: flex-start;

  &:hover {
    color: var(--dcs-text-regular);
    border-color: var(--dcs-border-color-light);
  }

  .ts-fold {
    margin-left: auto;
    flex: none;
    color: var(--dcs-text-placeholder);

    .iswap {
      transition: transform 0.15s;
    }
  }

  /* 运行态扫字呼吸（原型 L312：灰→蓝→灰 200% 渐变 1.6s） */
  &.live .ts-t.run {
    background: linear-gradient(90deg, var(--dcs-text-secondary) 25%, var(--el-color-primary) 50%, var(--dcs-text-secondary) 75%);
    background-size: 200% 100%;
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
    animation: dcs-sweep 1.6s linear infinite;
  }
  /* 完成态强化（原型 L324：t1 色+500） */
  &:not(.live) .ts-t {
    color: var(--dcs-text-primary);
    font-weight: 500;
  }
  &.err .ts-t {
    color: var(--dcs-color-danger);
  }
  /* 六审回修（glm53 P2）：stopped 且带失败步骤——胶囊文案警示色（非 error 级红，介于中性与红之间） */
  &:not(.live) .ts-t.fin-warn {
    color: var(--el-color-warning, #e6a23c);
  }
  /* 等待确认 pill（原型 ts-w8 L333-336：wn 底 wn 边胶囊，替代文案着色） */
  .ts-w8 {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    background: #fdf6ec;
    color: var(--dcs-color-warning, #e6a23c);
    border: 1px solid #faecd8;
    border-radius: 999px;
    font-size: 10.5px;
    font-weight: 600;
    padding: 0 8px;
    line-height: 17px;
  }
  /* 失败计数 pill（原型 ts-f L333-336：红边胶囊 ⛔×N） */
  .ts-f {
    display: inline-flex;
    align-items: center;
    gap: 2px;
    background: #fef0f0;
    color: var(--dcs-color-danger);
    border: 1px solid #fbc4c4;
    border-radius: 999px;
    font-size: 10.5px;
    padding: 0 7px;
    line-height: 17px;
  }
  /* 图标堆（原型 ts-ics L317-322：10px 小图标+live 脉冲） */
  .ts-ics {
    display: inline-flex;
    align-items: center;
    gap: 2px;
    color: var(--dcs-text-placeholder);

    &.live {
      color: var(--el-color-primary);

      .iswap {
        animation: dcs-istk 1.4s ease infinite;
      }
    }
  }
  .ts-x,
  .ts-d {
    color: var(--dcs-text-placeholder);
  }
}
/* 原型 L353：展开态长过程滚动时状态条钉顶——胶囊化后保留白底即可浮于灰消息区 */
.dcs-turn:not([data-folded='1']) .tstat {
  position: sticky;
  top: -6px;
  z-index: 6;
  background: var(--el-bg-color);
  border-radius: 999px;
  box-shadow: 0 1px 0 var(--dcs-border-color-lighter);
}
/* 原型 L374：折叠时 chevron 旋 -90° 指右；UI 专项批1：展开态旋 180° 指上（暗示「点击收起」，
   视觉评审 ui3 实锤：展开仍朝下读作「还有下文」） */
.dcs-turn:not([data-folded='1']) .tstat .ts-fold .iswap {
  transform: rotate(180deg);
}
.dcs-turn[data-folded='1'] .tstat .ts-fold .iswap {
  transform: rotate(-90deg);
}
/* 折叠密度（原型 foldt：L354-355；胶囊化后只收 margin，padding 保持胶囊比例） */
.dcs-turn[data-folded='1'] .tstat {
  margin-bottom: 4px;
}
/* tkl 换行滑动（原型 tkin/tkout L327-331） */
.tkl-enter-active,
.tkl-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
.tkl-enter-from {
  opacity: 0;
  transform: translateY(6px);
}
.tkl-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
@keyframes dcs-istk {
  50% {
    opacity: 0.35;
  }
}
/* 图标态修复（批J-2 P1）：cls 经 ic() 落在 v-html 注入的 svg 上，scoped 编译后
   `.spin[data-v]` 永不命中（svg 无 data-v 属性）——必须 :deep 才能生效 */
.dcs-turn :deep(.spin) {
  animation: dcs-rot 0.8s linear infinite;
  color: var(--el-color-primary);
  font-size: 15px;
}
.dcs-turn :deep(.spin.mini) {
  font-size: 13px;
}
.dcs-turn :deep(.ok-ic) {
  color: var(--dcs-color-success);
  font-size: 15px;
}
.dcs-turn :deep(.fail-ic) {
  color: var(--dcs-color-danger);
  font-size: 15px;
}
@keyframes dcs-rot {
  to {
    transform: rotate(360deg);
  }
}
/* 折叠态偷看行（原型 tpeek/fpeekrow L338-346） */
.tpeek {
  max-height: 40px;
  overflow: hidden;
  font-size: 11.5px;
  line-height: 20px;
  color: var(--dcs-text-placeholder);
  cursor: pointer;
  border-radius: 6px;
  padding: 0 4px;
  -webkit-mask-image: linear-gradient(180deg, #000 30%, transparent 100%);
  mask-image: linear-gradient(180deg, #000 30%, transparent 100%);
  white-space: nowrap;
  text-overflow: ellipsis;

  &:hover {
    box-shadow: inset 0 0 0 1px var(--dcs-border-color-light);
  }
}
.fpeekrow {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--dcs-color-danger);
  background: var(--el-bg-color);
  border: 1px solid #fbc4c4;
  border-radius: 8px;
  padding: 3px 10px;
  cursor: pointer;
  width: fit-content;

  &:hover {
    box-shadow: inset 0 0 0 1px #f19898;
  }
}
/* 过程区（原型 .proc L356-359）：左挂时间线圆点 + 末尾收尾线 */
.proc {
  position: relative;
  padding-left: 14px;
  display: flex;
  flex-direction: column;
  gap: 2px;

  &::after {
    content: '';
    border-top: 1px solid var(--dcs-border-color-lighter);
    margin: 12px 0 0 -14px;
  }
  /* Q12-③ 中段折叠行（首尾保留之间的「⋯ 另外 N 步」） */
  .midfold {
    align-self: flex-start;
    border: none;
    background: none;
    padding: 3px 6px;
    margin-left: 14px;
    font-size: 11.5px;
    color: var(--dcs-text-placeholder);
    cursor: pointer;
    border-radius: 6px;

    &:hover {
      color: var(--el-color-primary);
      background: var(--el-fill-color-light);
    }
  }
  .think::before,
  .tool::before {
    content: '';
    position: absolute;
    left: 1px;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--el-bg-color);
    border: 1px solid var(--dcs-border-color);
    margin-top: 6px;
  }
  .tool.st-run::before {
    background: var(--el-color-primary);
    border-color: var(--el-color-primary);
    animation: dcs-pp 1s ease infinite;
  }
  .tool.st-ok::before {
    background: #10b981;
    border-color: #10b981;
  }
  .tool.st-err::before {
    background: var(--dcs-color-danger);
    border-color: var(--dcs-color-danger);
  }
}
@keyframes dcs-pp {
  50% {
    box-shadow: 0 0 0 4px rgba(64, 158, 255, 0.12);
  }
}
.think {
  margin: 1px 0 7px;

  .tk-h {
    display: flex;
    align-items: center;
    gap: 8px;
    border: none;
    background: none;
    padding: 2px 0;
    cursor: pointer;
    font-size: 12px;
    color: var(--dcs-text-secondary);
    width: 100%;

    .tk-t.run {
      color: var(--el-color-primary);
      animation: dcs-sweep 1.5s linear infinite;
      background: linear-gradient(90deg, #6b7280 25%, var(--el-color-primary) 50%, #6b7280 75%);
      background-size: 200% 100%;
      -webkit-background-clip: text;
      background-clip: text;
      color: transparent;
    }
    /* Q9 思考中断灰态：去动画、灰字（不动 .run 优先级——cut 只在非 run 态出现） */
    .tk-t.cut {
      color: var(--dcs-text-placeholder);
    }
    /* Q9 流式摘要小窗（替代原 tk-s 60 字单行）：3 行高滚动跟随，底部渐隐 */
    .tk-live {
      flex: 1;
      min-width: 0;
      max-height: 54px;
      overflow-y: auto;
      font-size: 11.5px;
      line-height: 1.55;
      color: var(--dcs-text-placeholder);
      white-space: pre-wrap;
      word-break: break-word;
      -webkit-mask-image: linear-gradient(180deg, #000 55%, transparent 100%);
      mask-image: linear-gradient(180deg, #000 55%, transparent 100%);
      scrollbar-width: none;
      &::-webkit-scrollbar {
        display: none;
      }
    }
    /* Q12-② 停止思考：小描边按钮，hover 红（与停止键同语义族） */
    .tk-stop {
      flex: none;
      font-size: 11px;
      line-height: 1;
      font-family: inherit;
      color: var(--dcs-text-secondary);
      border: 1px solid var(--dcs-border-color-light);
      border-radius: 999px;
      padding: 6px 10px; /* 批8-3（k3 P2-3）：热区抬到 ≥24px（原 3px 9px≈19px 低于 WCAG 2.5.8） */
      cursor: pointer;
      background: var(--el-bg-color);
      transition:
        color 0.15s,
        border-color 0.15s;

      &:hover {
        color: var(--dcs-color-danger);
        border-color: var(--dcs-color-danger-light-5, #f89898);
      }
    }
    /* 展开钮默认隐、hover/展开时显（原型 tk-c L529-530） */
    .tk-c {
      font-size: 12px;
      margin-left: auto;
      opacity: 0;
      transition: opacity 0.15s;
    }
    &:hover .tk-c,
    .tk-c.on {
      opacity: 1;
    }
    &:hover .tk-t {
      color: var(--dcs-text-primary);
    }
  }
  .tk-b {
    margin: 2px 0 2px 22px;
    padding-left: 12px;
    border-left: 1px solid var(--dcs-border-color-light);
    /* Q9 拍板：限高 300px 滚动区 + markdown 渲染 */
    max-height: 300px;
    overflow-y: auto;
    font-size: 12px;
    line-height: 1.7;
    color: var(--dcs-text-placeholder);

    :deep(p) {
      margin: 0 0 6px;
      white-space: pre-wrap;
      &:last-child {
        margin-bottom: 0;
      }
    }
    :deep(ul) {
      margin: 4px 0;
      padding-left: 18px;
    }
    :deep(code) {
      background: var(--el-fill-color-light);
      border-radius: 4px;
      padding: 1px 5px;
      font-size: 11.5px;
      font-family: ui-monospace, Menlo, Consolas, monospace;
    }
  }
}
@keyframes dcs-sweep {
  to {
    background-position: -200% 0;
  }
}
@keyframes dcs-dt {
  30% {
    transform: translateY(-4px);
    opacity: 0.6;
  }
  60% {
    transform: translateY(0);
    opacity: 1;
  }
}
.tool {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12.5px;

  .th {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--dcs-text-secondary);
    border-radius: 4px;
    width: fit-content;

    &.clickable {
      cursor: pointer;
    }
    &:focus-visible {
      outline: 2px solid var(--el-color-primary-light-5);
    }
  }
  .s-verb {
    font-weight: 500;
  }
  /* Q10 单步耗时（≥1s 才显示；灰细字不抢动词） */
  .s-dur {
    color: var(--dcs-text-placeholder);
    font-size: 11px;
    font-variant-numeric: tabular-nums;
  }
  .s-exp {
    margin-left: auto;
    font-size: 11px;
    color: var(--dcs-text-placeholder);
  }
  .s-json {
    margin: 2px 0 0 25px;
    max-height: 220px;
    overflow: auto;
    background: #2b3648;
    color: #e8ecf3;
    border-radius: 8px;
    padding: 8px 10px;
    font-size: 11.5px;
    line-height: 1.6;
    font-family: ui-monospace, Menlo, Consolas, monospace;
    white-space: pre-wrap;
    word-break: break-all;
  }
  .failword {
    /* 原型 L537：红字+点状下划线（非胶囊） */
    color: var(--dcs-color-danger);
    font-size: 11.5px;
    border-bottom: 1px dotted var(--dcs-color-danger);
    cursor: copy;
  }
  .args {
    margin-left: 25px;
    font-size: 11.5px;
    color: var(--dcs-text-placeholder);
    font-family: ui-monospace, Menlo, Consolas, monospace;
    word-break: break-all;
  }
  /* 运行/失败整行着色（原型 L369-371：st-run 主色+动词扫光 / st-err 红） */
  &.st-run .th {
    color: var(--el-color-primary);

    .s-verb {
      background: linear-gradient(90deg, #6b7280 25%, var(--el-color-primary) 50%, #6b7280 75%);
      background-size: 200% 100%;
      -webkit-background-clip: text;
      background-clip: text;
      color: transparent;
      animation: dcs-sweep 1.6s linear infinite;
    }
  }
  &.st-err .th {
    color: var(--dcs-color-danger);
  }
  /* fpeekrow 点击定位：红描边 flash（原型 L347） */
  &.flash {
    outline: 2px solid var(--dcs-color-danger);
    outline-offset: 2px;
    border-radius: 6px;
  }
  &.st-ok .s-verb {
    color: var(--dcs-text-secondary);
  }
}

.bub {
  /* 业务批5 Q5-乙（2026-10-13 拍板，业务点名参考 ZCode 答案区）：AI 回复撤白卡回归裸排——
     内容直接躺在消息区灰底上（业界 7 个同类产品 6 个里 5 个裸排，全展开随页滚）。
     历史：UI 专项批1 的白卡源于当时 5.3fh 批「裸文本无归属 3.5/10」，但灰底（第 1 层）
     +用户蓝气泡（第 3 层）的对比仍在，裸排不会回到「什么都白」；真实使用反馈优先于
     视觉席意见。表格/代码卡自带白壳（局部卡片感保留，cherry-studio 同款取向） */
  font-size: 14px;
  line-height: 1.75;
  color: var(--dcs-text-primary);
  min-height: 1.75em;

  /* 批6-#2：中断回答徽标——工作面克制风（灰底细字+橙点），不抢正文注意力 */
  .cut-mark {
    margin-top: 8px;
    padding-top: 6px;
    border-top: 1px dashed var(--el-border-color, #dcdfe6);
    font-size: 12px;
    line-height: 1.5;
    color: var(--dcs-text-secondary, #909399);

    &::before {
      content: '';
      display: inline-block;
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--el-color-warning, #e6a23c);
      margin-right: 6px;
      vertical-align: 1px;
    }
  }

  /* 带工具成功（g-ok）原为白卡绿描边——撤卡后描边载体没了，语义由状态行「已完成」
     胶囊+工具时间线完整表达，不再叠第三重视觉（class 保留作行为标记） */
  .stream {
    white-space: pre-wrap;
    word-break: break-word;

    /* 原型 .dots（L183-186）：首 token 前三点波浪等待 */
    .dots {
      display: inline-flex;
      gap: 4px;
      margin-right: 6px;
      vertical-align: baseline;

      i {
        width: 7px;
        height: 7px;
        border-radius: 50%;
        background: var(--el-color-primary);
        animation: dcs-dt 1.2s ease infinite;

        &:nth-child(2) {
          animation-delay: 0.15s;
        }
        &:nth-child(3) {
          animation-delay: 0.3s;
        }
      }
    }
  }
  .cursor {
    /* 原型 L246：7×15 灰方块光标 */
    display: inline-block;
    width: 7px;
    height: 15px;
    vertical-align: -2px;
    border-radius: 1.5px;
    background: var(--dcs-text-primary);
    margin-left: 1px;
    animation: dcs-blink 0.9s steps(1) infinite;
  }
  :deep(.md) {
    p {
      margin: 0 0 8px;
      &:last-child {
        margin-bottom: 0;
      }
    }
    /* 批2-1 Q2-3（拍板 2026-10-10）：答案首行首个 strong 主色提级——扫读一眼抓住结论
       （如「**27** 项待办」）；只命中首段首个 strong，答案以列表/表格/标题开头时不亮（宁缺勿错） */
    p:first-child strong:first-of-type {
      color: var(--el-color-primary);
      font-size: 15px;
    }
    h4 {
      margin: 10px 0 6px;
      color: var(--dcs-text-primary);
      font-weight: 600;
      font-size: 17px;
      &:first-child {
        margin-top: 0;
      }
    }
    h5 {
      margin: 8px 0 5px;
      color: var(--dcs-text-primary);
      font-weight: 600;
      font-size: 15.5px;
      &:first-child {
        margin-top: 0;
      }
    }
    ul {
      margin: 0 0 8px;
      padding-left: 20px;
      li {
        margin: 3px 0;
      }
    }
    table {
      /* 表格优化（参考 ZCode markdown-table + EP 企业风）：宽度=内容最大宽（max-content，
       * 列永不被容器压缩——fit-content 会压列导致日期断行/长 ID 折三四行，视觉复核实锤）；
       * 窄表按内容收缩不再 width:100% 强拉空白；min 兜底两用——窄宿主铺满、宽宿主给
       * 2 列小表 480px 存在感；超宽表整体撑开由 .tbl-scroll 裁出横向滚动（逐列完整不折行） */
      width: max-content;
      min-width: min(100%, 480px);
      margin: 0;
      border-collapse: separate;
      border-spacing: 0;
      font-size: 12.5px;

      th,
      td {
        /* 业务批4 Q12 B 方案（cherry-studio 细线卡）：行底线 0.5px 更淡——去竖线只留
         * 发丝横线，白底干净；斑马纹同步撤（B+D：卡片化后靠细线分列已够，灰底条在
         * 窄气泡里显脏，open-webui/cherry 同款取向） */
        border-bottom: 0.5px solid var(--dcs-border-color-lighter);
        padding: 5px 10px;
        text-align: left;
        vertical-align: top;
        word-break: break-word;
        min-width: 3.5em;
        max-width: 26em;
      }
      th {
        background: var(--el-fill-color-light);
        color: var(--dcs-text-primary);
        font-weight: 600;
        white-space: nowrap;
        border-bottom: 1px solid var(--dcs-border-color);
        /* 表头吸顶（限高竖滚时代配套）：业务批5 Q5-乙 撤限高后 .tbl-scroll 不再竖滚，
           本规则无滚动锚=自然失效（表头随页滚，业界同款表现），保留无害——将来若恢复
           内滚形态（如全屏预览）可直接复用 */
        position: sticky;
        top: 0;
        z-index: 1;
      }
      /* 数字列（md.ts 探测挂 num）：右对齐+等宽数字，统计表个位可比（ZCode/GitHub 同款取向） */
      th.num,
      td.num {
        text-align: right;
        font-variant-numeric: tabular-nums;
      }
      /* 业务批4 Q12 撤斑马纹：hover 行高亮保留（工作面扫行定位刚需），底色条撤走 */
      tbody tr:hover {
        background: var(--el-fill-color);
      }
      tbody tr:last-child td {
        border-bottom: none;
      }
      /* 业务批4 Q12 ↓避让（窄屏）：<520px 长单元格单行省略+悬停原生 title 看全文
         （title 由 md.ts cell() 挂；宽屏保持自动换行不损失信息） */
      @media (max-width: 520px) {
        td > span {
          display: block;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
      }
    }
    /* 批3 三组样式（.tbl/.dcs-link/.task-card）已上提组件根级 :deep——与思考块 .tk-b 共用 */
    blockquote {
      /* 原型 L430：主色浅蓝身份（左线 p8+底 p9+圆角） */
      margin: 6px 0;
      padding: 6px 12px;
      border-left: 3px solid var(--el-color-primary-light-5);
      border-radius: 4px;
      color: var(--dcs-text-secondary);
      background: var(--el-color-primary-light-9);
    }
    code {
      background: var(--el-fill-color-light);
      border-radius: 4px;
      padding: 1px 5px;
      font-size: 13px;
      font-family: ui-monospace, Menlo, Consolas, monospace;
    }
    /* 代码卡（批C：围栏=cc 容器+卡条语言名+复制钮，事件委托在宿主） */
    .cc {
      margin: 6px 0;
      border-radius: 8px;
      overflow: hidden;

      .cc-bar {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 4px 10px;
        background: rgba(255, 255, 255, 0.06);
        color: #aab3c5;
        font-size: 11px;
        font-family: ui-monospace, Menlo, Consolas, monospace;
      }
      .cc-copy {
        border: 1px solid rgba(255, 255, 255, 0.25);
        background: transparent;
        color: #e8ecf3;
        border-radius: 4px;
        padding: 1px 8px;
        font-size: 11px;
        cursor: pointer;

        &:hover {
          background: rgba(255, 255, 255, 0.12);
        }
      }
      pre {
        margin: 0;
        border-radius: 0;
      }
    }
    pre {
      background: #2b3648;
      color: #e8ecf3;
      border-radius: 8px;
      padding: 10px 12px;
      overflow-x: auto;
      margin: 6px 0;

      code {
        background: none;
        padding: 0;
        color: inherit;
      }
    }
  }
}
@keyframes dcs-blink {
  50% {
    opacity: 0;
  }
}
/* AU-35：恢复钮=主操作（EP 主蓝），逃生钮维持危险描边——视觉层级=恢复优先 */
.ec-recover {
  margin-left: 8px;
  border: 1px solid var(--el-color-primary, #409eff);
  background: var(--el-color-primary, #409eff);
  color: #fff;
  border-radius: 6px;
  padding: 2px 10px;
  font-size: 12px;
  cursor: pointer;

  &:hover {
    background: var(--el-color-primary-light-3, #79bbff);
    border-color: var(--el-color-primary-light-3, #79bbff);
  }
}

.ec-escape {
  margin-left: 8px;
  border: 1px solid var(--dcs-color-danger-light-5, #f89898);
  background: none;
  color: var(--dcs-color-danger);
  border-radius: 6px;
  padding: 2px 10px;
  font-size: 12px;
  cursor: pointer;

  &:hover {
    background: var(--dcs-color-danger-light-9, #fef0f0);
  }
}
.ecard {
  border: 1px solid var(--dcs-color-danger-light-7, #fbc4c4);
  background: var(--dcs-color-danger-light-9, #fef0f0);
  color: var(--dcs-color-danger);
  border-radius: 10px;
  padding: 9px 12px;
  font-size: 13px;
}
/* 业务批(1015) Q9：人话化错误的原始报错折叠——默认收起不吓人，展开可拷给排错 */
.ec-detail {
  margin-top: 6px;
  font-size: 12px;
  color: var(--dcs-text-secondary);

  summary {
    cursor: pointer;
    user-select: none;
    color: var(--el-color-info);
  }

  code {
    display: block;
    margin-top: 4px;
    padding: 6px 8px;
    border-radius: 6px;
    background: rgba(0, 0, 0, 0.04);
    word-break: break-all;
    white-space: pre-wrap;
    font-size: 11px;
    color: var(--dcs-text-secondary);
  }
}
.stop-note {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--dcs-text-secondary);
  border: 1px dashed var(--dcs-border-color-light);
  border-radius: 10px;
  padding: 7px 10px;

  .sn-go {
    border: none;
    background: none;
    color: var(--el-color-primary);
    cursor: pointer;
    font-size: 12.5px;
    padding: 0;

    &:hover {
      text-decoration: underline;
    }
  }
  /* Q10 停止预期文案的补语（弱化不抢按钮） */
  .sn-hint {
    color: var(--dcs-text-placeholder);
    font-size: 11.5px;
  }
}
/* 尾部件（原型 fua：hover 显现操作行） */
.foot {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 1px 2px 0;
  opacity: 0;
  transition: opacity 0.15s;
  position: relative;
  font-size: 11px;
  color: var(--dcs-text-placeholder);
}
.dcs-turn:hover .foot,
.dcs-turn:focus-within .foot,
.foot:has(.fbpop) {
  opacity: 1;
}
/* AU-24 触屏适配：无 hover 能力的设备（手机/平板）操作行常显——否则复制/重试/赞踩永不可达 */
@media (hover: none) {
  .foot {
    opacity: 1;
  }
}
.fua-b {
  border: none;
  background: none;
  cursor: pointer;
  padding: 1px 2px;
  font-size: 13px;
  color: var(--dcs-text-secondary);
  display: flex;
  border-radius: 4px;

  &:hover {
    color: var(--el-color-primary);
  }
  &.emj {
    font-size: 12px;
    line-height: 1;
  }
  &.on {
    color: var(--el-color-primary);
  }
}
/* star 收藏态=填充（描边→实心，Q2 ☆/⭐ 统一 star 本体） */
.fua-b.star.on :deep(svg) {
  fill: currentColor;
}
.fua-t {
  margin-left: auto;
  font-size: 11px;
  color: var(--dcs-text-placeholder);
  font-variant-numeric: tabular-nums;
  user-select: none;
  white-space: nowrap;
}

/* 👎 问卷（原型 fbpop：236px，锚在踩钮上方） */
.fb-host {
  position: relative;
  display: flex;
}
.fbpop {
  position: absolute;
  right: 0;
  top: -6px;
  transform: translateY(-100%);
  background: var(--el-bg-color);
  border: 1px solid var(--dcs-border-color);
  border-radius: 10px;
  box-shadow: var(--el-box-shadow-light);
  padding: 10px 12px;
  width: 236px;
  z-index: 35;
  font-size: 12.5px;
  color: var(--dcs-text-regular);

  b {
    font-size: 13px;
    color: var(--dcs-text-primary);
  }
  .fr {
    display: flex;
    gap: 6px;
    align-items: center;
    padding: 3px 0;
    cursor: pointer;
    margin-top: 2px;

    input {
      accent-color: var(--el-color-primary);
    }
  }
  textarea {
    width: 100%;
    border: 1px solid var(--dcs-border-color-light);
    border-radius: 6px;
    font: inherit;
    font-size: 12px;
    padding: 4px 6px;
    margin-top: 6px;
    resize: none;
    height: 38px;
    outline: none;
    background: transparent;
    color: var(--dcs-text-regular);
  }
  .fb-btns {
    display: flex;
    justify-content: flex-end;
    gap: 6px;
    margin-top: 8px;

    button {
      border-radius: 6px;
      padding: 3px 10px;
      font-size: 12px;
      cursor: pointer;
      border: 1px solid var(--dcs-border-color-light);
      background: var(--el-bg-color);
      color: var(--dcs-text-regular);

      &:hover {
        border-color: var(--el-color-primary-light-5);
      }
    }
    .fb-go {
      background: var(--el-color-primary);
      border-color: var(--el-color-primary);
      color: #fff;
    }
  }
}

/* 来源折叠条（原型 srcbar/srclist） */
.srcwrap {
  display: flex;
  flex-direction: column;
}
.srcbar {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--dcs-text-secondary);
  border: 1px solid var(--dcs-border-color);
  background: var(--el-bg-color);
  border-radius: 999px;
  padding: 2px 12px 2px 10px;
  cursor: pointer;
  width: fit-content;

  &:hover {
    border-color: var(--el-color-primary);
    color: var(--el-color-primary);
  }
}
.srclist {
  /* 原型 L781-785：单个边框盒内平铺条目（非逐条成卡） */
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--dcs-border-color);
  border-radius: 10px;
  padding: 6px 12px;

  .srcitem {
    padding: 7px 0;
    font-size: 12px;
    color: var(--dcs-text-regular);
    line-height: 1.6;

    & + .srcitem {
      border-top: 1px solid var(--dcs-border-color-lighter);
    }
  }

  .srcitem b {
    color: var(--el-color-primary);
    font-weight: 600;
  }
  .srcmeta {
    color: var(--dcs-text-placeholder);
    margin-left: 6px;
    font-size: 11px;
  }
  .srcexcerpt {
    color: var(--dcs-text-secondary);
    margin: 1px 0 5px;
    word-break: break-all;
  }
}

/* 追问 chips（原型 fu；UI 专项批1：间距上提 14px 与答案脱开（评审 10px 偏紧）+对比度提到正文灰） */
.fu {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding: 0 2px;

  .chip {
    height: 28px;
    font-size: 12.5px;
    background: var(--el-bg-color);
    border: 1px solid var(--dcs-border-color-light);
    color: var(--dcs-text-regular);
    border-radius: 999px;
    padding: 0 13px;
    cursor: pointer;
    transition: 0.15s;

    &:hover {
      color: var(--el-color-primary);
      border-color: var(--el-color-primary-light-5);
      background: var(--el-color-primary-light-9);
    }
  }
}
/* 业务批(1015) Q6：联网搜索来源卡（cherry-studio/LobeChat 同款"底部折叠来源条"形态） */
.cite {
  margin-top: 10px;

  .cite-bar {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 26px;
    padding: 0 12px;
    font-size: 12.5px;
    border-radius: 999px;
    border: 1px solid var(--dcs-border-color-light);
    background: var(--el-bg-color);
    color: var(--dcs-text-regular);
    cursor: pointer;

    &:hover {
      color: var(--el-color-primary);
      border-color: var(--el-color-primary-light-5);
    }
  }
  .cite-list {
    margin-top: 8px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .cite-card {
    display: flex;
    align-items: center;
    gap: 10px;
    text-align: left;
    padding: 7px 10px;
    border: 1px solid var(--dcs-border-color-light);
    border-radius: 8px;
    background: var(--el-bg-color);
    cursor: pointer;

    &:hover {
      border-color: var(--el-color-primary-light-5);
    }
    img {
      flex: none;
      width: 18px;
      height: 18px;
      border-radius: 4px;
    }
    .cite-fav {
      flex: none;
      width: 18px;
      height: 18px;
      border-radius: 4px;
      background: var(--el-color-primary-light-9);
      color: var(--el-color-primary);
      font-size: 11px;
      font-weight: 700;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      .cite-favimg {
        width: 100%;
        height: 100%;
        object-fit: contain;
        display: block;
      }
    }
    /* 业务反馈批1015c Q6：来源列表第三方免责行 */
    .cite-note {
      font-size: 11px;
      color: var(--dcs-text-secondary);
      padding-top: 6px;
      line-height: 1.4;
    }
    .cite-main {
      min-width: 0;

      b {
        display: block;
        font-size: 12.5px;
        font-weight: 500;
        color: var(--dcs-text-regular);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .cite-dom {
        font-size: 11.5px;
        color: var(--dcs-text-secondary);
      }
    }
  }
}
@media (prefers-reduced-motion: reduce) {
  .spin,
  .cursor,
  .tk-t.run {
    animation: none;
  }
}
</style>

<style lang="scss">
/* 业务批(1015) Q5：生成汇报弹层 el-popover 内容——teleport 到 body，scoped 样式够不到，
 * 独立非 scoped 块（popper-class 锚定）；卡片语言与 .fbpop 同款 */
.dcs-rep-pop {
  .rep-inner {
    display: flex;
    flex-direction: column;
    gap: 6px;
    font-size: 12.5px;

    b {
      font-size: 13px;
      color: var(--dcs-text-primary, #303133);
    }
    .rep-row {
      display: flex;
      align-items: center;
      gap: 6px;
      width: 100%;
      border: 1px solid var(--el-border-color-lighter, #ebeef5);
      background: var(--el-fill-color-light, #f5f7fa);
      color: var(--el-text-color-regular, #606266);
      border-radius: 8px;
      padding: 6px 10px;
      cursor: pointer;
      font-size: 12.5px;
      text-align: left;

      &:hover:not(:disabled) {
        border-color: var(--el-color-primary-light-5, #a0cfff);
        color: var(--el-color-primary, #409eff);
      }
      &:disabled {
        opacity: 0.55;
        cursor: not-allowed;
      }
    }
  }
}
</style>
