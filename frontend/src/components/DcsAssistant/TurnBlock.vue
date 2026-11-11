<template>
  <div
    ref="rootEl"
    class="dcs-turn"
    :data-phase="turn.phase"
    :data-folded="folded ? '1' : '0'"
    @dcs-expand-fold="onExpandFold"
    @dcs-expand-steps="expandAllSteps"
  >
    <!-- Q2 批1审查 P1：MsgFlow.jumpToStep 目标落在中段折叠区时派发本事件先展开全部步骤 -->
    <!-- 回合状态条（tstat）：动词/终态/等你确认/耗时；可点折叠过程区（BN-2 原型 foldt，答完 1s 自动收）
         批J-2 补原型件：ts-ics 图标堆 / ts-f 失败计数 pill / ts-w8 等待 pill / tkl 换行滑动 / 终态去 ×N 双重计数 -->
    <button class="tstat" :class="statCls" :aria-expanded="!folded" :title="folded ? '展开过程明细' : '收起过程明细'" @click="toggleFold">
      <!-- Q1 甲（照 ZCode）：3×3 像素轨道替代转圈——9 格 13px、环形依次亮，950ms 一圈 -->
      <span v-if="live" class="px" aria-hidden="true"><i v-for="n in 9" :key="n" /></span>
      <IconSpan v-else-if="turn.phase === 'error'" name="circle-x" cls="fail-ic" />
      <IconSpan v-else-if="emptyFail" name="circle-x" cls="fail-ic" />
      <IconSpan v-else-if="turn.phase === 'stopped'" name="square" />
      <IconSpan v-else-if="turn.phase !== 'waiting'" name="check-circle" cls="ok-ic" />
      <!-- 原型 ts-ics（L317-322）：acting 期文案左侧堆叠已调用工具图标，live 脉冲、完成清空 -->
      <span v-if="live && turn.toolCalls > 0" class="ts-ics" :class="{ live: turn.phase === 'acting' }">
        <IconSpan v-for="icn in toolIcons" :key="icn" :name="icn" />
      </span>
      <!-- Q16 状态词不再走 <Transition mode="out-in">：out-in 要先等旧词 180ms 退场再让新词进场，
           阶段切换（思考→执行→撰写）时胶囊里会出现约 180ms 的「只剩 · N 秒」空档。
           直接换文本，字永远在。 -->
      <span v-if="turn.phase === 'waiting'" :key="statText" class="ts-w8" v-html="emoToSvgHtml(statText)"></span>
      <span
        v-else
        :key="statText"
        class="ts-t"
        :class="{ run: live, 'fin-warn': !live && turn.phase === 'stopped' && failCount > 0 }"
        v-html="emoToSvgHtml(statText)"
      ></span>
      <span v-if="failCount > 0" class="ts-f"><IconSpan name="ban" :size="12" /> ×{{ failCount }}</span>
      <span v-if="turn.toolCalls > 0 && live" class="ts-x">×{{ turn.toolCalls }}</span>
      <span v-if="durText" class="ts-d">· {{ durText }}</span>
      <span class="ts-fold"><IconSpan name="chevron-down" :size="12" /></span>
    </button>

    <!-- 折叠态偷看行（原型 tpeek/fpeekrow L338-346）：思考摘要渐隐小窗 + 失败行点击展开定位；
         UI 专项批1：仅运行中保留（思考进行中瞄一眼），终态收起——英文 CoT 原文不再怼脸，想看完整思考点胶囊展开 -->
    <div v-if="folded && turn.thinkText && live" ref="peekEl" class="tpeek" :class="{ 'fade-t': peekOver }" title="展开过程明细" @click="toggleFold">{{ tpeekText }}</div>
    <div v-if="folded && failCount > 0" class="fpeekrow" @click="expandFail">
      <IconSpan name="ban" :size="12" /> {{ firstFailVerb }} · 失败 <IconSpan name="chevron-right" :size="12" />
    </div>

    <!-- 过程区（原型 .proc）：思考行+工具行左挂时间线圆点，末尾收尾分隔线。
         R3(2026-10-10 拍板A)：思考分段与工具行按事件到达序交错成统一时间线（ZCode 同构）——
         思考段就位在它发生的时刻（think①→工具①→think②），不再全文拼成一坨置顶；
         旧数据（thinkSegs 空/thinkText 有值、回放 steps 无 seq）兜底=合成单段置顶=旧行为 -->
    <!-- 题7 甲（2026-11-06）：折叠/展开 300ms 高度过渡（grid 0fr↔1fr，内容保持挂载——
         v-show 的 display:none 没法做高度动画，且卸载会丢内部滚动位置） -->
    <div class="proc-wrap" :class="{ open: !folded, animating: foldAnimating }">
    <div class="proc" :class="{ 'proc-empty': !timeline.length }">
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
             「思考 · N 秒」（多段=多行同款文案，不编号）；点开=限高 300px 滚动区+markdown；
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
            <span class="tk-t" :class="{ run: !it.seg.done, cut: segCut(it.seg) }">{{ segLabel(it.seg) }}</span>
            <!-- Q16：右侧一组（剩余量提示 + 开合箭头）整体靠右，箭头不再被提示挤走 -->
            <span class="tk-r">
              <!-- Q16：展开态且没滚到底时给个明确余量，光靠底部渐隐看不清还剩多少 -->
              <span v-if="segIsOpen(it.seg) && tkbRemain" class="tk-more">还有约 {{ tkbRemain }} 字</span>
              <!-- Q15：展开态常显上箭头（原来只绑 hover，点开后看不出「可收起」） -->
              <span class="tk-c" :class="{ on: segIsOpen(it.seg) }"><IconSpan :name="segIsOpen(it.seg) ? 'chevron-up' : 'chevron-down'" :size="12" /></span>
            </span>
          </div>
          <!-- 流式摘要小窗（Q9：只看本段最新 400 字；用户点开后让位正文区）。
               Q13：独占整行——原先挤在标题行内只剩 404px（图标14+标题60+停止钮66+箭头12 挤掉 184px），
               11.5px 下每行仅 35 字、可见 2 行≈70 字，故下移一级拿到整列宽度（566px≈49 字/行×5 行） -->
          <div
            v-if="!it.seg.done && !segIsOpen(it.seg) && it.seg.text"
            :ref="(el) => setTkLive(el, it.seg)"
            class="tk-live"
            :class="{ 'fade-t': !tkAtTop, 'fade-b': !tkAtBottom }"
            title="展开完整思考"
            @scroll.passive="onTkScroll"
            @click="onSegToggle(it.seg)"
          >
            {{ it.seg.text.slice(-400) }}
          </div>
          <!-- Q2 甲（2026-11-07 拍板，照 ZCode）：思考正文按纯文本渲染。
               思考的加粗/列表符号一律按原文显示——不跑 markdown，是「展开不卡」的另一半；
               正文气泡仍走 md.ts，不受影响。 -->
          <div
            v-if="segIsOpen(it.seg)"
            :ref="setTkB"
            class="tk-b"
            :class="{ 'fade-t': !tkbAtTop, 'fade-b': !tkbAtBottom }"
            @scroll.passive="onTkBScroll"
          >{{ it.seg.text }}</div>
        </div>

        <!-- 工具行（Q10：单步耗时；Q12-③ 中段折叠>5 步首尾保留；Q6 甲：终态点开=三段式详情）
             Q6 甲（2026-11-07 拍板，照 ZCode）：连续同类工具收成一组，组头给次数/合计耗时/失败数；
             命中即建组（gsize≥2），单次调用仍是原来那一行。组内跑着时强制展开，别把进度藏起来。 -->
        <template v-else>
          <button
            v-if="it.gid && it.gpos === 0"
            type="button"
            class="tgh"
            :class="{ open: groupIsOpen(it.gid) }"
            :aria-expanded="groupIsOpen(it.gid)"
            @click="toggleGroup(it.gid)"
          >
            <IconSpan :name="groupIcon(it.step.name)" :size="13" />
            <span class="tgh-n">{{ groupTitle(it.step) }} · {{ it.gsize }} 次</span>
            <span class="tgh-m">
              合计 {{ groupDurText(it.gid) }}<template v-if="groupFailCount(it.gid)"> · {{ groupFailCount(it.gid) }} 失败</template>
            </span>
            <span class="tgh-c"
              ><IconSpan :name="groupIsOpen(it.gid) ? 'chevron-up' : 'chevron-down'" :size="12"
            /></span>
          </button>
          <div
            v-if="!it.gid || it.gpos === 0 || groupIsOpen(it.gid)"
            class="tool"
            :class="['st-' + it.step.status, { 'in-grp': !!it.gid && it.gpos > 0 }]"
            :data-step-id="it.step.id"
          >
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
            <span class="s-verb">{{ it.step.name === 'ask_user' ? '已询问' : it.step.verb }}</span>
            <span v-if="it.step.name === 'ask_user' && askedCount(it.step)" class="s-dur">· {{ askedCount(it.step) }} 个问题</span>
            <span v-if="stepDur(it.step)" class="s-dur">· {{ stepDur(it.step) }}</span>
            <!-- TW-1 原型口径：失败词下划虚线=点击复制错误信息 -->
            <span
              v-if="it.step.status === 'err' && it.step.name === 'ask_user'"
              class="failword"
              :title="askFailReason(it.step) || '执行失败'"
              @click.stop="copyAskFail(it.step)"
              @keydown.enter.stop="copyAskFail(it.step)"
              >执行失败</span
            >
            <span
              v-if="it.step.status === 'err' && it.step.name !== 'ask_user'"
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
          <!-- 题8：反问的原始参数不摆给用户看（ZCode 那行 <pre> 也是注释掉的）；要排错点详情/看返回 -->
          <span v-if="it.step.argsText && it.step.name !== 'ask_user'" class="args">{{ shortArgs(it.step.argsText) }}</span>
          <!-- Q6 甲（2026-11-07 拍板，照 ZCode 的 tool 展开）：点开只有三段摘要——
               参数 / 结果 / 错误；原始 JSON 收进第二层折叠，不再一上来铺一屏（限高 240px 内滚）。 -->
          <div v-if="openSteps.has(it.step.id)" class="s-det" :class="{ 'has-dc': isRowsStep(it.step) }">
            <div v-if="it.step.name !== 'ask_user' && toolParams(it.step).length" class="sd">
              <div class="sd-h">参数</div>
              <div class="sd-kv">
                <span v-for="p in toolParams(it.step)" :key="p.raw" class="sd-p"><i>{{ p.k }}</i>{{ p.v }}</span>
              </div>
            </div>
            <div v-if="it.step.status === 'err'" class="sd sd-err">
              <div class="sd-h">错误</div>
              <div class="sd-t">{{ toolErrorText(it.step) }}</div>
              <button type="button" class="sd-copy" @click.stop="copyFail(it.step)">复制错误</button>
            </div>
            <div v-if="it.step.resultFull && it.step.status !== 'err'" class="sd">
              <div class="sd-h">{{ isRowsStep(it.step) ? '数据结果' : '结果' }}</div>
              <!-- 2026-11-09：rows 型工具的数据原件卡从「来源区」搬回工具行展开区——
                   数据是工具的结果，不是来源（ZCode 同口径：工具输出长在工具行里） -->
              <data-card v-if="isRowsStep(it.step)" :step="it.step" @pgoto="(p) => emit('pgoto', p)" />
              <div v-else class="sd-t">{{ toolResultBrief(it.step) }}</div>
            </div>
            <details v-if="it.step.argsText || it.step.resultFull" class="sd-raw">
              <summary>展开原始 JSON</summary>
              <pre>{{ toolRaw(it.step) }}</pre>
            </details>
          </div>
        </div>
        </template>
      </template>
    </div>
    </div>

    <!-- 已用工具章（原型 stamp）：UI 专项批1 删除——折叠态胶囊「用了 N 个工具」、
         展开态工具行 verb 双侧都与其重复（视觉评审 ui3 两轮实锤），动词语义留这两处承载 -->

    <!-- 计划卡（题四甲 2026-11-04）：最新一张待确认计划渲染成消息流表单卡
         （编号行+自由输入+忽略/提交）；其余状态与旧卡一律只读收据 -->
    <plan-form-card
      v-if="turn.plan && isActivePlan"
      :plan="turn.plan"
      :busy="planBusy"
      @confirm="(id) => emit('plan-confirm', id)"
      @adjust="(id, text) => emit('plan-adjust', id, text)"
      @cancel="(id) => emit('plan-cancel', id)"
      @collapse="emit('plan-collapse')"
    />
    <plan-card v-else-if="turn.plan" :plan="turn.plan" />

    <!-- G3-A1：MCP 外部工具反问卡（server 在飞挂起，run 保持 RUNNING）——作答走独立端点，
         不进 HITL resume 体系；done 后本地移除（后端超时 CANCEL 兜底） -->
    <elicit-card
      v-for="ec in turn.elicitations"
      :key="ec.id"
      :card="ec"
      :thread-id="sessionThreadId ?? ''"
      @done="(id) => emit('elicit-done', id)"
    />

    <!-- 题8 甲（2026-11-06）：反问回执——问过就留一行，四种结局都写清楚；点「详情」看逐题问答。
         活卡不在这里：题10 乙把它钉到输入框上方了（见宿主 dock），滚走也不会丢。 -->
    <div v-for="rc in turn.askReceipts" :key="'rc-' + rc.id" class="askrc">
      <button class="askrc-hd" type="button" :aria-expanded="askReceiptOpen.has(rc.id)" @click="toggleAskReceipt(rc.id)">
        <IconSpan name="help" :size="13" />
        <span class="askrc-lb">{{ receiptLead(rc) }}</span>
        <span v-if="rc.failed" class="askrc-fail" :title="rc.failReason || '执行失败'" @click.stop>执行失败</span>
        <span class="askrc-x">{{ askReceiptOpen.has(rc.id) ? '收起' : '详情' }}</span>
      </button>
      <div v-if="askReceiptOpen.has(rc.id)" class="askrc-body">
        <div v-for="(q, qi) in rc.questions" :key="qi" class="askrc-q">
          <b>{{ q.question }}</b>
          <span>{{ receiptAnswer(rc, qi) }}</span>
        </div>
      </div>
    </div>

    <!-- 正文气泡：流式=纯文本+光标（S-23 仅末段增量）；完成=全量 md；
         UI 专项批1：卡内「已完成」章删除——与顶部状态胶囊重复报状态（视觉评审 7.4 轮实锤），成功语义只留胶囊+浅绿描边 -->
    <div v-if="turn.bufText || turn.answerHtml" class="bub" :class="{ 'g-ok': turn.phase === 'done' && turn.toolCalls > 0 }">
      <!-- eslint-disable-next-line vue/no-v-html —— md.ts 先转义后白名单替换，无注入面；点击委托处理链导航/代码卡/表格复制 -->
      <div v-if="turn.answerHtml" ref="mdEl" class="md" v-html="answerHtmlRefs" @click="onMdClick" @keydown.enter="onMdClick" />
      <div v-else class="stream" @click="onMdClick" @keydown.enter="onMdClick">
        <!-- Q16 流式期也过行内渲染（粗体 / 行内码 / 站内链）——原先整段当纯文本，
             答案里 8 处 ** 原样露面，完成瞬间整块重排。块级结构仍留到完成态 -->
        <!-- eslint-disable-next-line vue/no-v-html —— renderStream 先转义后白名单替换，与 .md 同链路 -->
        <span v-if="turn.shownLen > 0" class="sh" v-html="streamHtml"></span
        ><span v-else class="dots"><i /><i /><i /></span><span class="cursor" />
      </div>
      <!-- 批6-#2：中断回答徽标（历史回放 end_reason=partial/truncated）——刷新/断网保全的
           部分内容不再假装完整答案；partial 态短暂存在（轮询收敛后转 truncated 同标 -->
      <div v-if="turn.interrupted" class="cut-mark">回答被中断 · 以上是已生成的部分，内容可能不完整</div>
    </div>

    <!-- 三批④（2026-11-01）：全自动档完成通知卡（auto_done CUSTOM 帧，RUN_FINISHED 非
         interrupt 收尾时后端按 run 查可撤行下发）——逐条列出本轮 AI 自动执行且可撤回的
         操作（只有存记忆/建任务可撤）；卡片随回合内存态（刷新消失属预期） -->
    <div v-if="autoDoneRows.length" class="autodone">
      <div class="ad-head"><IconSpan name="check-circle" cls="ok-ic" :size="13" /> 已自动执行 {{ autoDoneRows.length }} 项操作</div>
      <div v-for="a in autoDoneRows" :key="a.id" class="ad-row">
        <span class="ad-verb">{{ ACT[a.toolName] ?? a.toolName.replace(/_/g, ' ') }}</span>
        <span v-if="adSummary(a.summary)" class="ad-sum" :title="adSummary(a.summary)">{{ adSummary(a.summary) }}</span>
        <button
          class="ad-revoke"
          type="button"
          :disabled="revokeBusyId === a.id"
          :title="
            a.toolName === 'save_memory'
              ? '撤销这条记忆：记忆页同步删除，审计页留「已撤回」记录'
              : '撤销这个任务草稿：草稿删除（已发布的不能撤），审计页留「已撤回」记录'
          "
          @click="onRevokeAutoDone(a.id)"
        >
          撤回
        </button>
      </div>
    </div>

    <!-- 业务批10 #2/#4：空回答轮诚实化——done 却无正文无工具=模型没吐过一个字（零事件卡死被
         收割/重放悬挂轮），不再假装"已完成"绿胶囊+静态推荐题；给人话结论+一键重问出路。
         2026-11-11：补上第三种真实成因——模型回了 200 但正文为空（实测 17 token 无内容），
         原来只写「卡住或中断」会在这种情形下误导；顺带指出去哪查余额/可用性 -->
    <!-- 2026-11-11 拍板 Q3-2/Q4-2：有原因码（服务端判因、刷新不丢）按码出人话+下一步；
         无码（拉取未到/历史旧行）回退通用文案。原文仅管理员可展开（Q2-2 脱敏后落库） -->
    <div v-if="emptyFail" class="ecard">
      这轮没等到回答：{{ issueLine }}
      <details v-if="turn.issueDetail && issueAdmin" class="ec-detail">
        <summary>查看服务商原文</summary>
        <code>{{ turn.issueDetail }}</code>
      </details>
      <!-- 终审 P2-3：quota 的跳转按钮只有能进「AI 模型与钥匙」的人（管理员）才给，
           普通成员点了会落 404 兜底路由——quota 最常见受众恰是普通成员，给灰字出路 -->
      <button v-if="issueAction === 'quota' && issueAdmin" class="ec-recover" @click="emit('mdnav', AI_CONFIG_PATH)">去「AI 模型与钥匙」查看</button>
      <span v-else-if="issueAction === 'quota'" class="ec-note">请联系管理员充值或换模型</span>
      <button v-else-if="turn.question && (issueAction === 'retry' || issueAction === null)" class="ec-recover" @click="emit('resend-run', turn.question)">↻ 重新问一次</button>
      <span v-if="issueAction === 'wait'" class="ec-note">等几秒再试就好</span>
      <span v-else-if="issueAction === 'admin'" class="ec-note">请联系管理员更换钥匙</span>
    </div>

    <!-- 错误卡（AU-35 1008拍板A：挂起中断类=恢复优先双按钮——code+框架文案双保险判定，
         不再只匹配英文文案；「恢复挂起的确认」调 /hitl/pending 重建确认卡，会话不废）。
         2026-11-11：emptyFail（判因卡）接管时不叠旧错误文案——判因说得清就不重复报忧 -->
    <div v-if="turn.errorMsg && !emptyFail" class="ecard">
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

    <!-- HITL 确认卡（E3 → 停靠区）：待确认卡占据输入框位置（HitlDock），
         流内只留一行状态；确认/取消后收成一行回执，点「详情」展开原卡只读内容 -->
    <div v-if="turn.hitl" class="hitl-history">
      <button v-if="hitlPending" type="button" class="hitl-line pending" title="确认卡停在输入框位置，点此把焦点移过去" @click="focusHitlDock">
        <IconSpan name="loader-circle" :size="13" />
        <span class="hl-t">{{ hitlReceiptText }}</span>
        <span class="hl-ln">去确认</span>
      </button>
      <template v-else>
        <button
          type="button"
          class="hitl-line"
          :class="{ cancelled: turn.hitl.cancelled, stopped: turn.hitl.stopped }"
          :aria-expanded="receiptOpen"
          @click="receiptOpen = !receiptOpen"
        >
          <IconSpan :name="turn.hitl.cancelled ? 'x' : turn.hitl.stopped ? 'pause' : 'check'" :size="13" />
          <span class="hl-t">{{ hitlReceiptText }}</span>
          <span class="hl-ln">{{ receiptOpen ? '收起' : '详情' }}</span>
        </button>
        <div v-if="receiptOpen" class="hitl-detail">
          <form-card
            v-if="turn.hitl.toolName === 'act_create_mission'"
            :ask="turn.hitl"
            @resolve="(a, r, e) => emit('resolve-hitl', a, r, e)"
            @revise="(m) => emit('ask', m)"
          />
          <template-card
            v-else-if="turn.hitl.toolName === 'act_create_template'"
            :ask="turn.hitl"
            @resolve="(a, r, e) => emit('resolve-hitl', a, r, e)"
            @revise="(m) => emit('ask', m)"
          />
          <confirm-card v-else :ask="turn.hitl" @resolve="(a, r, e) => emit('resolve-hitl', a, r, e)" />
        </div>
      </template>
    </div>

    <!-- 来源（2026-11-09 改回 ZCode 口径）：只列联网搜索带回来的真实网页，编号 [n] 与正文互相跳转；
         工具调用不再冒充来源（它们长在各自工具行的展开区里）。没有真来源时整块不出现。 -->
    <div v-if="hasFoot && sourceItems.length" class="srcwrap">
      <button class="srcbar" :aria-expanded="srcOpen" @click="srcOpen = !srcOpen">
        <IconSpan name="history" /> {{ sourceItems.length }} 个来源 <IconSpan name="chevron-down" />
      </button>
      <div v-if="srcOpen" class="srclist">
        <button
          v-for="it in sourceItems"
          :key="it.key"
          type="button"
          class="src-row"
          :data-ref="it.n"
          :class="{ hot: hotRef === it.n }"
          :title="`${it.title}
${it.url}`"
          @mouseenter="hotRef = it.n"
          @mouseleave="hotRef = 0"
          @click="onSrcClick(it)"
        >
          <b>[{{ it.n }}]</b>
          <span v-if="it.url" class="src-fav">
            <img
              v-if="!favHostDead.has(hostOf(it.url))"
              class="src-favimg"
              :src="`${favBase}/dcs/ai/favicon?host=${encodeURIComponent(hostOf(it.url))}`"
              alt=""
              loading="lazy"
              @error="favHostDead.add(hostOf(it.url))"
            />
            <template v-else>{{ (hostOf(it.url) || '?').slice(0, 1).toUpperCase() }}</template>
          </span>
          <span class="src-main">
            <span class="src-t">{{ it.title }}</span>
            <span v-if="it.meta" class="src-m">{{ it.meta }}{{ it.url ? ' ↗' : '' }}</span>
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
import type { AssistantAskReceipt, AssistantStep, AssistantTurn, HitlEdits, ThinkSeg } from './useAssistant';
import { ACT, ASSISTANT_ISSUE_COPY, getAssistant } from './useAssistant';
import { createVNode, defineAsyncComponent, render, watch } from 'vue';
import ConfirmCard from './ConfirmCard.vue';
import DataCard from './DataCard.vue';
import FormCard from './FormCard.vue';
import TemplateCard from './TemplateCard.vue';
import PlanCard from './PlanCard.vue';
import PlanFormCard from './PlanFormCard.vue';
import ElicitCard from './ElicitationCard.vue';
import IconSpan from './IconSpan.vue';
import { emoToSvgHtml } from './icons';
import { turnStateWord } from './assistantCtx';
import { linkRefs, renderStream } from './md';
import { argPairs, parseToolJson, summarizeArgs } from './toolJson';
import { submitFeedback, listConversations, listMessages, exportAssistantReport } from '@/api/dcs/assistant';
import { useUserStore } from '@/store/modules/user';
import { getAgentPrefs } from './prefsStore';
import { pushToast } from './toastStore';
import { useFavs } from './favStore';
import { findVisibleHitlDock } from './util';
// 批4回修 P2（两席共识）：异步组件——echarts（gzip ~300KB）只在回答真出现图占位时才下载，
// 不随抽屉 chunk 人人必载；createVNode 支持异步组件，加载完成自动填充 host
const ChartBlock = defineAsyncComponent(() => import('./ChartBlock.vue'));

const props = defineProps<{
  turn: AssistantTurn;
  turnIndex?: number;
  now: number;
  threadId?: string;
  showThink?: boolean;
  /** 题四甲：当前可操作的计划卡 id（宿主算好传进来；空串=没有可操作的卡） */
  activePlanId?: string;
  /** 计划决策请求在飞（锁卡防重复提交） */
  planBusy?: boolean;
}>();
const userStore = useUserStore();
const prefs = getAgentPrefs(userStore.userId);
const rootEl = ref<HTMLElement | null>(null);
/** 只有「最新一张 pending 卡」可操作；历史卡/已决策卡走只读收据 */
const isActivePlan = computed(
  () => !!props.turn.plan?.planId && props.turn.plan.planId === props.activePlanId && (!props.turn.plan.status || props.turn.plan.status === 'pending')
);

/** 流内回执——待确认只留一行指路（真卡在输入框位置的停靠区），
 *  完成/取消后折叠为一行（详情可展开原卡只读内容） */
const receiptOpen = ref(false);
const HITL_LABELS: Record<string, string> = {
  act_create_mission: '创建任务草稿',
  act_create_template: '创建模板',
  act_remind: '催办',
  act_forward: '转发',
  act_audit: '审核'
};
const hitlPending = computed(() => props.turn.hitl?.cardState === 'pending');
const hitlLabel = computed(() => HITL_LABELS[props.turn.hitl?.toolName ?? ''] ?? '操作');
const hitlReceiptText = computed(() => {
  const h = props.turn.hitl;
  if (!h) return '';
  if (h.cardState === 'pending') return `等待确认 · ${hitlLabel.value}`;
  if (h.cancelled) return `已取消 · ${hitlLabel.value}（未执行）`;
  if (h.stopped) return `已停止 · ${hitlLabel.value}（执行结果未知）`;
  return `已确认执行 · ${hitlLabel.value}`;
});
function focusHitlDock() {
  const el = findVisibleHitlDock();
  if (!el) return;
  el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  el.focus({ preventScroll: true });
}
const emit = defineEmits<{
  resume: [];
  /** Q12-② 停止思考：思考进行中胶囊旁中断入口——上浮到宿主 onStop（复用整轮停止通道） */
  stop: [];
  /** G3 反问卡收卡（作答/超时）上浮宿主 store 移除——不在子组件里改 prop（eslint
   *  vue/no-mutating-props，三审回修） */
  'elicit-done': [id: string];
  /** 题12 案1：结构化反问卡收卡（提交/关闭/超时） */
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
  /** 题四甲：消息流计划卡上的决策（上浮宿主，与旧停靠区同一套 handler） */
  'plan-confirm': [planId: string];
  'plan-adjust': [planId: string, feedback: string];
  'plan-cancel': [planId: string];
  'plan-collapse': [];
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
  /* Q16：手动开合=用户已经在这块内容上交互过——答完 1s 的自动收起必须让位，
     否则刚点开想看，1 秒后整块又被收走（ZCode/assistant-ui 同口径：用户优先）。
     同时撤掉已经排上的收起定时器，避免点开后 200ms 又被收。 */
  userFolded = true;
  if (foldTimer) {
    clearTimeout(foldTimer);
    foldTimer = null;
  }
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
/* Q13 跟随状态机：初值 true（首帧就贴底），只有用户真实上滚才置 false，回到距底 4px 内自动恢复。
   原判据 `sh-st-h < 24` 在首帧 gap=142 时永不成立 → scrollTop 恒 17，加高后只是显示更多旧内容 */
const tkLiveEl = ref<HTMLElement | null>(null);
const tkFollowing = ref(true);
/* Q16 动态遮罩：贴底时不该再把最新一行压暗（原来 55% 起的静态渐隐正好吃掉正在看的行）。
   上下两端各一把开关，只在「那一侧还有内容」时才渐隐。 */
const tkAtTop = ref(true);
const tkAtBottom = ref(true);
function syncTkEdges(el: HTMLElement) {
  tkAtTop.value = el.scrollTop <= 2;
  tkAtBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight <= 4;
}
function setTkLive(el: unknown, seg: ThinkSeg) {
  const node = seg.done ? null : ((el as HTMLElement | null) ?? null);
  if (node === tkLiveEl.value) return;
  tkLiveEl.value = node;
  if (!node) return;
  tkFollowing.value = true;
  nextTick(() => {
    node.scrollTop = node.scrollHeight;
    syncTkEdges(node);
  });
}
function onTkScroll(e: Event) {
  const el = e.target as HTMLElement | null;
  if (!el) return;
  tkFollowing.value = el.scrollHeight - el.scrollTop - el.clientHeight <= 4;
  syncTkEdges(el);
}
watch(
  () => (props.turn.thinkSegs.length ? props.turn.thinkSegs.map((s) => s.text.length).join(',') : props.turn.thinkText.length),
  () => {
    if (!tkFollowing.value) return;
    const el = tkLiveEl.value;
    if (!el) return;
    // flush:'pre' 下回调早于 DOM 更新，必须等一帧再量 scrollHeight
    nextTick(() => {
      el.scrollTop = el.scrollHeight;
      syncTkEdges(el);
    });
  }
);

/* Q16 点开态（.tk-b）同样用动态遮罩，并在标题行右侧给出剩余字数——306px 的窗口只装得下
   15 行，光有遮罩看不出还剩多少；滚到底提示自动消失。 */
const tkbEl = ref<HTMLElement | null>(null);
const tkbAtTop = ref(true);
const tkbAtBottom = ref(true);
const tkbSeen = ref(1);
function syncTkb() {
  const el = tkbEl.value;
  if (!el) return;
  const max = el.scrollHeight - el.clientHeight;
  tkbAtTop.value = el.scrollTop <= 2;
  tkbAtBottom.value = max - el.scrollTop <= 4;
  tkbSeen.value = max <= 0 ? 1 : Math.min(1, Math.max(0, el.scrollTop / max));
}
function setTkB(el: unknown) {
  const node = (el as HTMLElement | null) ?? null;
  /* 2026-10-31 卡死修复：函数 ref 在 Vue 里每次 patch 都会被调用（runtime-core setRef 对
     函数 ref 没有同值短路），原实现无条件 `tkbSeen = 0`；而 syncTkb 会把滚动比例写回
     tkbSeen，两者互相触发 → 无限更新循环，主线程被微任务吃满（实测展开思考块后一滚动
     即卡死）。同一个节点必须直接返回，只有重新挂载才重置。 */
  if (node === tkbEl.value) return;
  tkbEl.value = node;
  if (!node) return;
  tkbSeen.value = 0;
  nextTick(syncTkb);
}
function onTkBScroll() {
  syncTkb();
}
const tkbRemain = computed(() => {
  if (tkbAtBottom.value) return '';
  const seg = thinkSegsView.value.find((s) => segIsOpen(s));
  const total = seg?.text.length ?? 0;
  if (!total) return '';
  const left = Math.max(0, Math.round(total * (1 - tkbSeen.value)));
  if (left < 60) return '';
  return left >= 1000 ? (left / 1000).toFixed(1) + 'k' : String(left);
});
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
const folded = ref(isTerminal.value && !prefs.toolsOpen);
let userFolded = false;
let foldTimer: ReturnType<typeof setTimeout> | null = null;
/** 折叠动画进行中：这 300ms 内强制裁掉溢出，否则 0fr→1fr 的中间态里明细会压到下面的答案上 */
const foldAnimating = ref(false);
let foldAnimTimer: ReturnType<typeof setTimeout> | null = null;

function animateFold() {
  foldAnimating.value = true;
  if (foldAnimTimer) {
    clearTimeout(foldAnimTimer);
  }
  foldAnimTimer = setTimeout(() => {
    foldAnimating.value = false;
    foldAnimTimer = null;
  }, 320);
}

function toggleFold() {
  folded.value = !folded.value;
  userFolded = true;
  animateFold();
}

/** DP-3：统计面板跳步骤命中本回合但过程区已折叠（MsgFlow 派发）——展开并钉住（不再自动收） */
function onExpandFold() {
  folded.value = false;
  userFolded = true;
  animateFold();
}

/** TW-1：失败词点击复制错误信息（原型 .failword 虚线下划 cursor:copy） */
/* ---- 题8 甲（2026-11-06）：反问回执行 ---- */
/** 展开状态按卡 id 记忆（同 ZCode 的 toolId 记忆口径） */
const askReceiptOpen = reactive(new Set<string>());
function toggleAskReceipt(id: string) {
  if (askReceiptOpen.has(id)) {
    askReceiptOpen.delete(id);
  } else {
    askReceiptOpen.add(id);
  }
}
function receiptLead(rc: AssistantAskReceipt): string {
  const n = rc.questions.length;
  if (rc.failed || rc.outcome === 'answered') {
    return `已询问 · ${n} 个问题`;
  }
  if (rc.outcome === 'cancelled') return '已询问 · 未提供回答';
  if (rc.outcome === 'timeout') return '已询问 · 未回答，已超时';
  return '已询问 · 未回答，已自动继续';
}
function receiptAnswer(rc: AssistantAskReceipt, qi: number): string {
  const byQuestion = rc.answers.find((a) => a.question === rc.questions[qi]?.question);
  const a = byQuestion ?? rc.answers[qi];
  const picked = a?.picked ?? [];
  const other = (a?.other ?? '').trim();
  if (!picked.length && !other) {
    return '未提供回答';
  }
  return picked.map((p) => `「${p}」`).join('、') + (other ? `；补充：${other}` : '');
}
/** ask_user 参数里的题数（失败行也要说「已询问 · N 个问题」） */
function askedCount(step: AssistantStep): number {
  try {
    const v = JSON.parse(step.argsText || '{}')?.questions;
    return Array.isArray(v) ? v.length : 0;
  } catch {
    return 0;
  }
}
function askFailReason(step: AssistantStep): string {
  const raw = (step.resultFull || step.brief || '').replace(/\s+/g, ' ').trim();
  return raw ? `反问没有发出去：${raw.slice(0, 160)}` : '反问没有发出去（模型给的参数不合法）';
}
function copyAskFail(step: AssistantStep) {
  const text = askFailReason(step);
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).then(
      () => pushToast('失败原因已复制', 'ok'),
      () => pushToast('复制失败，可点详情查看', 'info')
    );
  } else {
    pushToast('复制失败，可点详情查看', 'info');
  }
}

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

/** Q16 偷看行改取尾部：原来是「整段开头 + 单行省略」，运行中永远只能看见第一句，
 *  正好是最没用的那部分（用户想知道「现在想到哪了」）。
 *  Q18 起改成两行贴底（见 .tpeek）：取尾部 300 字交给容器裁，能露多少由宽度决定，
 *  不再用「120 字」猜一行——写字数只能适配一种视口，宽屏浪费、窄屏还是被切。 */
const tpeekText = computed(() => (props.turn.thinkText || '').replace(/\s+/g, ' ').slice(-300));

/** Q18 偷看行是否被两行窗口裁掉：只有真裁了才加顶部渐隐，短文本保持原样不压暗。
 *  不能用 scrollHeight——容器是「贴底」布局，溢出的旧文本在块首方向，规范里不算进
 *  scrollable overflow（scrollHeight 恒等于 clientHeight）；这里直接量文本 Range 的真实高度。 */
const peekEl = ref<HTMLElement | null>(null);
const peekOver = ref(false);
function syncPeekOver() {
  const el = peekEl.value;
  if (!el) {
    peekOver.value = false;
    return;
  }
  const range = document.createRange();
  range.selectNodeContents(el);
  peekOver.value = range.getBoundingClientRect().height > el.clientHeight + 1;
}
watch([tpeekText, folded, () => props.turn.phase], () => nextTick(syncPeekOver));

/** Q16 流式正文：打字机长度不变（shownLen 逐帧增长），但每帧渲染的是「行内已闭合」的 HTML，
 *  不再是裸文本——否则 **按月复盘** 会原样显示到完成那一刻才突然变粗。 */
const streamHtml = computed(() => renderStream(props.turn.bufText.slice(0, props.turn.shownLen)));

/** BN-2 原型口径：答完 1s 自动收起过程明细（用户手动展开/收起过则尊重用户） */
watch(
  () => props.turn.phase,
  (ph) => {
    if (ph === 'done' && !userFolded && !prefs.toolsOpen) {
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
  if (foldAnimTimer) clearTimeout(foldAnimTimer);
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
 *  判定与 statText/推荐题/错误卡三处联动：不显示"已完成"、不荐题、给人话+重问出路。
 *  2026-11-11 补丁：error 轮（服务商故障关流，活体实证框架只关流不出 RUN_ERROR）拿到
 *  判因码后也归入——判因卡比「连接已断开」说得清；无码 error 轮维持旧错误卡兜底 */
const emptyFail = computed(() => {
  const t = props.turn;
  const zeroContent = !t.bufText && !t.answerHtml && t.toolCalls === 0;
  if (!zeroContent) {
    return false;
  }
  if (t.phase === 'done') {
    return true;
  }
  return t.phase === 'error' && !!t.issueCode;
});

/* ---- 2026-11-11 拍板 Q3-2/Q4-2：「这轮为什么没回答」按码出人话 ----
 * 文案/下一步词典与 StatPanel 共用（useAssistant.ASSISTANT_ISSUE_COPY），后端只发六码；
 * 无码（拉取未到/历史旧行）回退旧通用文案——卡片文案永不空。原文仅 superadmin 展开。 */
const AI_CONFIG_PATH = '/tenant/ai-config';
const issueInfo = computed(() => (props.turn.issueCode ? ASSISTANT_ISSUE_COPY[props.turn.issueCode] : undefined));
const issueLine = computed(() =>
  issueInfo.value
    ? `${issueInfo.value.text}。`
    : '模型结束时一个字都没返回（可能中途停住，或服务商返回了空回答）。反复出现就去「AI 模型与钥匙」看该模型的余额和可用状态。'
);
const issueAction = computed<'retry' | 'wait' | 'quota' | 'admin' | null>(() => issueInfo.value?.action ?? null);
const issueAdmin = computed(() => userStore.roles.includes('superadmin'));

/* ---- 来源（2026-11-09 按 ZCode 真源码改口径）：只列"真来源" ----
 * ZCode 全仓没有"每轮来源清单"这种东西（ai-elements 的 Sources / SourcesTrigger 零调用）；
 * 它的来源只有两种：正文里的 [n] 行内角标、以及模型自己声明的文件引用卡。**工具调用从不进来源**
 * （工具结果长在工具行自己的展开区里）。我们上一版把每个工具步骤都塞进清单并分了五组，
 * 用户原话「你怎么全部来源都上了」——这里改回真来源口径：
 *   来源 = turn.citations（web_search 结构化出来的真实网页，带地址可点）；
 *   一条都没有 → 整块不出现（宁缺毋滥，不拿工具充数）。 */
interface SrcItem {
  key: string;
  n: number;
  title: string;
  meta?: string;
  url?: string;
}
/** 扁平来源清单：citations 顺序即编号，与正文 [n] 对齐 */
const sourceItems = computed<SrcItem[]>(() =>
  (props.turn.citations ?? []).map((c, i) => ({
    key: `web-${i}-${c.url}`,
    n: i + 1,
    title: c.title || hostOf(c.url),
    meta: c.siteName || hostOf(c.url),
    url: c.url
  }))
);
const hotRef = ref(0);
/** 正文 [n] 变成可点的 .refn（只处理正文，代码块里原样保留） */
const answerHtmlRefs = computed(() => linkRefs(props.turn.answerHtml, sourceItems.value.length));
const mdEl = ref<HTMLElement | null>(null);
/** 悬停来源 → 反过来高亮正文里所有同号 [n]（v-html 里的节点只能直接操作类名） */
watch(hotRef, (n) => {
  const root = mdEl.value;
  if (!root) {
    return;
  }
  root.querySelectorAll('.refn.hot').forEach((el) => el.classList.remove('hot'));
  if (n) {
    root.querySelectorAll(`.refn[data-ref="${n}"]`).forEach((el) => el.classList.add('hot'));
  }
});
/** 点来源：有网页就开网页（受控外链），否则把正文里第一个同号 [n] 滚到眼前闪一下 */
function onSrcClick(it: SrcItem) {
  if (it.url) {
    openCitation(it.url);
    return;
  }
  const el = mdEl.value?.querySelector(`.refn[data-ref="${it.n}"]`) as HTMLElement | null;
  if (el) {
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    el.classList.add('hot');
    setTimeout(() => el.classList.remove('hot'), 1600);
  }
}

/* ---- 业务批(1015) Q6：联网搜索来源卡（citations 由 useAssistant.onToolResult 从 web_search
 *      结果结构化提取；此处只管展示与受控跳转） ---- */
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

/* ---- 三批④（2026-11-01）：全自动完成通知卡撤回。自治模式（同 ElicitCard/AskUserCard
 *      卡内直接触后端的口径）：经 getAssistant 单例直达 store.revokeAutoDone（api 调用+
 *      条目移除+toast 都在 store 单点），不上浮事件到 MsgFlow/宿主——撤回只按账本行 id
 *      （全局唯一），不依赖 threadId。useUserStore 在点击时才取（组件测试不点撤回不触 pinia）。 ---- */
const autoDoneRows = computed(() => props.turn.autoDone ?? []);
/** 完成卡摘要去掉账本回执尾段「（记录 #id）」——雪花 id 是后端撤回锚点，不该上屏 */
const adSummary = (s?: string) => (s ?? '').replace(/（记录 #\d+）$/, '');
const revokeBusyId = ref('');
function onRevokeAutoDone(id: string) {
  if (revokeBusyId.value) {
    return;
  }
  revokeBusyId.value = id;
  // 失败 toast 由 request 层弹后端人话（store 侧同口径），此处只复位按钮防连点
  getAssistant(useUserStore().userId)
    .revokeAutoDone(id)
    .finally(() => {
      revokeBusyId.value = '';
    });
}
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
const stepsAll = ref(prefs.toolsOpen);
watch(
  () => props.turn.id,
  () => {
    stepsAll.value = prefs.toolsOpen;
  }
);
watch(
  () => prefs.toolsOpen,
  (v) => {
    stepsAll.value = v;
    if (isTerminal.value) {
      folded.value = !v;
      userFolded = false;
    }
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
  switch (t.phase) {
    case 'thinking':
    case 'acting':
    case 'writing':
      // Q1 甲（照 ZCode）：进行中只有一个词；在思考还是在调工具，由下面各行自己说
      return turnStateWord(t.phase);
    case 'waiting': {
      // 确认卡生命周期联动（k3 视觉复审 P2-1）：卡 run/done 后状态行不再挂「等你确认」
      const cs = t.hitl?.cardState;
      if (cs === 'run') return '🛡 正在执行确认的操作…';
      if (cs === 'done') return t.hitl?.cancelled ? '已取消（未执行）' : '已确认执行';
      return '🛡 ' + turnStateWord('waiting'); // 🛡 经 toast/文本通道按 EMO2IC→power 渲染（Q2=A 照搬原型）
    }
    case 'stopped':
      // 失败数已由 ts-f 独立 pill 展示，主词回一个词，不重复计数
      return turnStateWord('stopped');
    case 'error':
      // 2026-11-11：判因卡接管（拿到服务端码）的断流轮——主词同空回答口径
      return emptyFail.value ? '没等到回答' : turnStateWord('error');
    default:
      // 业务批10 #2：空回答轮不再显示"已完成"——模型一个字都没吐，绿色完成态是误导
      if (emptyFail.value) {
        return '没等到回答';
      }
      // Q1 甲：有耗时才说「已工作 · N 秒」；太快的轮次照 ZCode 说「已处理」
      return (t.durMs || 0) >= 2100 ? turnStateWord('done') : '已处理';
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

/** Q1 甲（照 ZCode）：计时 0.1 秒跳一次。心跳只挂在本回合自己身上（ZCode 也是独立小组件），
 *  不让整条消息流跟着 10Hz 重渲染；live 才起，终态即停。 */
const liveTick = ref(Date.now());
let liveTimer: ReturnType<typeof setInterval> | null = null;
function stopLiveTimer() {
  if (liveTimer) {
    clearInterval(liveTimer);
    liveTimer = null;
  }
}
watch(
  live,
  (v) => {
    stopLiveTimer();
    if (v) {
      liveTick.value = Date.now();
      liveTimer = setInterval(() => (liveTick.value = Date.now()), 100);
    }
  },
  { immediate: true }
);
onBeforeUnmount(stopLiveTimer);

const durText = computed(() => {
  const t = props.turn;
  const ms = t.durMs > 0 ? t.durMs : live.value ? Math.max(0, liveTick.value - t.startedAt) : 0;
  if (!live.value && ms < 2100) {
    return ''; // 终态口径：≥2.1s 才显示；live 从 0.0 秒起跳
  }
  if (ms < 60_000) {
    return live.value ? `${(ms / 1000).toFixed(1)} 秒` : `${(ms / 1000).toFixed(0)} 秒`;
  }
  return `${Math.floor(ms / 60_000)} 分 ${Math.floor((ms % 60_000) / 1000)} 秒`;
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
 *  供中段折叠按钮插位（i===1 前）。
 *  Q6 甲（2026-11-07）：连续同类工具合并——gid=组 id（组内至少 2 步）、gpos=组内序号、gsize=组大小；
 *  不同工具名之间即便紧邻也不进同一组（ZCode 也按类目分组，不跨类型）。 */
type TlItem =
  | { kind: 'think'; seg: ThinkSeg; idx: number; sIdx: -1; gid: null; gpos: 0; gsize: 0 }
  | { kind: 'tool'; step: AssistantStep; idx: 0; sIdx: number; gid: string | null; gpos: number; gsize: number };
const timeline = computed<TlItem[]>(() => {
  const shown = shownSteps.value;
  const shownIdx = new Map(shown.map((s, i) => [s.id, i]));
  const items: TlItem[] = [];
  thinkSegsView.value.forEach((seg, idx) => items.push({ kind: 'think', seg, idx, sIdx: -1, gid: null, gpos: 0, gsize: 0 }));
  // 代码席 P1（2026-11-09）：分组必须在**全量步骤**上算——按 shownSteps 子集算会把
  // 「首步 + 末两步」当成相邻，组头写「N 次」却少算中间被折叠的同类调用
  props.turn.steps.forEach((step) => {
    items.push({ kind: 'tool', step, idx: 0, sIdx: shownIdx.get(step.id) ?? -1, gid: null, gpos: 0, gsize: 0 });
  });
  // 排序键：事件序（回放行无 seq=排最后按插入序稳定兜底；legacy 合成段 seq=-1 置顶）
  const key = (it: TlItem) => (it.kind === 'think' ? it.seg.seq : (it.step.seq ?? Number.MAX_SAFE_INTEGER));
  const sorted = items.sort((a, b) => key(a) - key(b));
  // 合并：排序后相邻、都是工具、工具名相同 → 建组（单次调用不建组，仍是一行）
  // 中段折叠（>PROC_KEEP 且未展开）时不建组：折叠把中间同类步骤藏起来后，组的首行
  // 可能不在可见集里，会出现「有组身没组头、点了也打不开」的死行；此时按单步渲染，数字不会错
  const grouping = stepsAll.value || props.turn.steps.length <= PROC_KEEP;
  const out: TlItem[] = [];
  for (let i = 0; i < sorted.length; ) {
    const cur = sorted[i];
    if (cur.kind !== 'tool') {
      out.push(cur);
      i++;
      continue;
    }
    let j = i + 1;
    while (j < sorted.length) {
      const nx = sorted[j];
      if (nx.kind !== 'tool' || nx.step.name !== cur.step.name) {
        break;
      }
      j++;
    }
    const size = j - i;
    const gid = grouping && size >= 2 ? `g:${cur.step.name}:${cur.step.id}` : null;
    for (let k = i; k < j; k++) {
      const t = sorted[k];
      if (t.kind !== 'tool') {
        continue;
      }
      out.push({ ...t, gid, gpos: k - i, gsize: size });
    }
    i = j;
  }
  // 渲染仍只给可见步骤（sIdx=-1 的属于中段折叠区），思考段始终在
  return out.filter((it) => it.kind === 'think' || it.sIdx >= 0);
});


/* 题6 甲（2026-11-06）：思考段不再编号——序号只表示「第几段」，对用户无信息量，
   三个参照仓（ZCode/cline/assistant-ui）也都不编号。多段就是多行，按时间顺序排。 */
function segLabel(seg: ThinkSeg): string {
  const t = props.turn;
  // Q9 停止灰态：思考中被中断（该段未置 done 而回合已停）→「思考中断」
  if (t.phase === 'stopped' && !seg.done) {
    return '思考中断';
  }
  if (seg.done) {
    // 批8-3 回修口径：优先纯思考段耗时；段无真值时退整轮口径
    const ms =
      seg.durMs > 0 ? seg.durMs : seg.startedAt > 0 ? Date.now() - seg.startedAt : t.durMs > 0 ? Math.min(t.durMs, Date.now() - t.startedAt) : 0;
    const sec = Math.max(1, Math.round(ms / 1000));
    return `思考 · ${sec} 秒`;
  }
  return '正在思考…';
}

/** Q9 思考中断灰态（逐段判定：cut 类=去呼吸动画、灰字） */
function segCut(seg: ThinkSeg): boolean {
  return props.turn.phase === 'stopped' && !seg.done;
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

/* ---- Q6 甲（2026-11-07 拍板，照 ZCode）：工具分组 + 三段式详情 ---- */

/** 组默认收起；组里还有步骤在跑时强制展开——进度不能藏起来 */
const openGroups = ref(new Set<string>());
const groupSteps = computed(() => {
  const m = new Map<string, AssistantStep[]>();
  for (const it of timeline.value) {
    if (it.kind === 'tool' && it.gid) {
      const arr = m.get(it.gid);
      if (arr) {
        arr.push(it.step);
      } else {
        m.set(it.gid, [it.step]);
      }
    }
  }
  return m;
});
function groupIsOpen(gid: string): boolean {
  if (openGroups.value.has(gid)) {
    return true;
  }
  return (groupSteps.value.get(gid) ?? []).some((s) => s.status === 'run');
}
function toggleGroup(gid: string) {
  // 代码席 P1（2026-11-09）：组内有在跑的步骤时恒展开，点击不写状态——
  // 否则用户点「收起」会被记成 add(展开)，视觉没变但意图记反，跑完后组仍开着
  if ((groupSteps.value.get(gid) ?? []).some((s) => s.status === 'run')) {
    return;
  }
  const s = new Set(openGroups.value);
  if (s.has(gid)) {
    s.delete(gid);
  } else {
    s.add(gid);
  }
  openGroups.value = s;
}
function groupFailCount(gid: string): number {
  return (groupSteps.value.get(gid) ?? []).filter((s) => s.status === 'err').length;
}
/** 代码席 P1（2026-11-09）：状态面板「在对话中定位」落到组内非首步时，该行不在 DOM 里，
 *  MsgFlow 只能展开中段折叠、查不到元素就静默返回。这里在展开步骤的同时把组也全打开，
 *  组身回到 DOM 后 MsgFlow 的 nextTick 重查才能命中。 */
function expandAllSteps() {
  stepsAll.value = true;
  nextTick(() => {
    openGroups.value = new Set(groupSteps.value.keys());
  });
}
function groupDurText(gid: string): string {
  const ms = (groupSteps.value.get(gid) ?? []).reduce((n, s) => n + (s.durMs || 0), 0);
  if (!ms) {
    return '—';
  }
  return ms >= 1000 ? (ms / 1000).toFixed(1) + ' 秒' : Math.round(ms) + ' 毫秒';
}
/** 组头的人话标题与图标：同类工具的叫法按类目收（ZCode 的 Explore / Execute / Changes 同款），
 *  词典里没有的工具退回它自己的动词，不硬编造类目名 */
const GROUP_META: Record<string, { title: string; icon: string }> = {
  web_search: { title: '联网搜索', icon: 'globe' },
  read_file: { title: '读文件', icon: 'file' },
  write_file: { title: '写文件', icon: 'file' },
  list_dir: { title: '列目录', icon: 'file' },
  search_knowledge: { title: '知识库', icon: 'book' },
  todo_write: { title: '任务清单', icon: 'check-circle' },
  submit_plan: { title: '计划', icon: 'flag' }
};
function groupTitle(s: AssistantStep): string {
  if (GROUP_META[s.name]) {
    return GROUP_META[s.name].title;
  }
  if (s.name.startsWith('analytics_')) {
    return '分析工具';
  }
  if (s.name.startsWith('query_')) {
    return '业务数据';
  }
  return s.verb;
}
function groupIcon(name: string): string {
  if (GROUP_META[name]) {
    return GROUP_META[name].icon;
  }
  if (name.startsWith('analytics_')) {
    return 'chart';
  }
  if (name.startsWith('query_')) {
    return 'database';
  }
  return 'wrench';
}

/** 三段式详情：参数段（键值对，最多 8 对） */
function toolParams(step: AssistantStep) {
  return argPairs(step.argsText);
}
/** 失败行的错误正文：ask_user 有专门的人话口径，其它工具原样截 300 字 */
function toolErrorText(step: AssistantStep): string {
  if (step.name === 'ask_user') {
    return askFailReason(step);
  }
  const raw = (step.resultFull || step.brief || '').replace(/\s+/g, ' ').trim();
  return raw ? raw.slice(0, 300) : '这一步没有返回错误详情';
}
/** 结果段摘要：优先用后端给的 brief（rows 型走数据卡，不经过这里） */
function toolResultBrief(step: AssistantStep): string {
  const b = (step.brief || '').replace(/\s+/g, ' ').trim();
  return b || '已执行完成（展开原始 JSON 看返回）';
}
/** 原始 JSON：参数与结果拼在一起，收进第二层折叠 */
function toolRaw(step: AssistantStep): string {
  const parts: string[] = [];
  if (step.argsText) {
    parts.push('参数：\n' + step.argsText);
  }
  if (step.resultFull) {
    parts.push('结果：\n' + step.resultFull);
  }
  return parts.join('\n\n');
}

/** rows 型工具判定（来源区升级为 DataCard 数据原件；解析失败回退文本摘要）。
 *  「问数据」批：analytics 三个带 rows 的工具同样升卡（DataCard 的 TOOL_CARDS
 *  已配列定义与 sourceNote 脚注；总览/留存/路径无 rows 仍走文本摘要，设计如此） */
const ROWS_TOOLS = new Set([
  'query_my_todos',
  'query_my_missions',
  'query_mission_detail',
  'query_submission_status',
  'query_deadline_alerts',
  'query_unsubmitted',
  'query_unsubmitted_by_dept',
  'analytics_trend',
  'analytics_funnel',
  'analytics_custom'
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
  // Q6 丙：正文里的 [n] → 展开来源区并高亮对应那条（来源→正文的反向路径见 onSrcClick）
  const ref = (e.target as HTMLElement).closest('.refn');
  if (ref) {
    e.preventDefault();
    const n = Number(ref.getAttribute('data-ref') || 0);
    if (n > 0) {
      srcOpen.value = true;
      hotRef.value = n;
      nextTick(() => {
        const row = rootEl.value?.querySelector(`.src-row[data-ref="${n}"]`) as HTMLElement | null;
        row?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      });
      setTimeout(() => (hotRef.value = 0), 2000);
    }
    return;
  }
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

  /* HITL 流内回执行——待确认只留一行指路（真卡在输入框位置的停靠区），
     完成后收成一行回执，点「详情」展开原卡只读内容 */
  .hitl-history {
    margin-top: 6px;
    max-width: 100%;
  }
  .hitl-line {
    display: flex;
    align-items: center;
    gap: 6px;
    max-width: 100%;
    padding: 5px 10px;
    font: inherit;
    font-size: 12.5px;
    line-height: 1.5;
    color: var(--dcs-text-secondary);
    background: var(--el-fill-color-lighter, #f5f7fa);
    border: 1px solid var(--el-border-color-lighter, #ebeef5);
    border-radius: 8px;
    cursor: pointer;
    text-align: left;

    .hl-t {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .hl-ln {
      flex: none;
      font-size: 12px;
      color: var(--el-color-primary);
    }
    &:hover .hl-ln {
      text-decoration: underline;
    }

    /* 待确认：虚线+主色（动作还没做完，提示去停靠区） */
    &.pending {
      color: var(--el-color-primary);
      background: var(--el-color-primary-light-9, #ecf5ff);
      border-style: dashed;
      border-color: var(--el-color-primary-light-7, #a0cfff);
    }
    &.cancelled {
      color: var(--dcs-text-placeholder);
      background: transparent;
    }
    &.stopped {
      color: var(--el-color-warning, #e6a23c);
      border-color: var(--el-color-warning-light-7, #f3d19e);
      background: var(--el-color-warning-light-9, #fdf6ec);
    }
  }
  .hitl-detail {
    margin-top: 8px;
  }

  /* 三批④（2026-11-01）：全自动完成通知卡——hitl 回执同款安静边框口径（design-taste
     app-screens：细边框+浅底常态，无渐变/辉光/阴影）；行内动词+摘要+撤回钮 */
  .autodone {
    margin-top: 6px;
    max-width: 100%;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 6px 10px;
    background: var(--el-fill-color-lighter, #f5f7fa);
    border: 1px solid var(--el-border-color-lighter, #ebeef5);
    border-radius: 8px;

    .ad-head {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12.5px;
      line-height: 1.5;
      color: var(--dcs-text-secondary);
    }
    .ad-row {
      display: flex;
      align-items: center;
      gap: 8px;
      min-width: 0;
      font-size: 12px;
      line-height: 1.5;
      color: var(--dcs-text-regular);
    }
    .ad-verb {
      flex: none;
      font-weight: 500;
    }
    .ad-sum {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      color: var(--dcs-text-secondary);
    }
    .ad-revoke {
      flex: none;
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
      &:disabled {
        opacity: 0.6;
        cursor: default;
      }
    }
  }

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
      color: var(--app-primary-ink);
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

  /* Q1 甲（参数照 ZCode chat-loading.tsx / styles.css）：3×3 共 9 格，总 13px，
     格间距 1.3px，圆角 1px；中心格固定 0.07，其余按环形顺序依次亮起（950ms，相邻 110ms）。 */
  .px {
    display: grid;
    grid-template-columns: repeat(3, 3.4px);
    grid-template-rows: repeat(3, 3.4px);
    gap: 1.3px;
    width: 13px;
    height: 13px;
    flex: none;
    color: currentColor;

    i {
      display: block;
      background: currentColor;
      border-radius: 1px;
      opacity: 0.15;
      animation: dcs-pxon 950ms ease-in-out infinite;
      &:nth-child(5) {
        opacity: 0.07;
        animation: none;
      }
      &:nth-child(1) { animation-delay: 0ms; }
      &:nth-child(2) { animation-delay: 110ms; }
      &:nth-child(3) { animation-delay: 220ms; }
      &:nth-child(6) { animation-delay: 330ms; }
      &:nth-child(9) { animation-delay: 440ms; }
      &:nth-child(8) { animation-delay: 550ms; }
      &:nth-child(7) { animation-delay: 660ms; }
      &:nth-child(4) { animation-delay: 770ms; }
    }
  }

  /* 运行态扫字呼吸（ZCode zcode-loading-label：1.4s，中性灰→主字色→中性灰） */
  &.live .ts-t.run {
    background: linear-gradient(90deg, var(--dcs-text-secondary) 35%, var(--dcs-text-primary) 50%, var(--dcs-text-secondary) 65%);
    background-size: 200% 100%;
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
    animation: dcs-sweep 1.4s linear infinite;
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
    background: var(--app-warm-bg);
    color: var(--dcs-color-warning, #e6a23c);
    border: 1px solid var(--app-warm-border);
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
    background: var(--dcs-color-danger-light-9);
    color: var(--dcs-color-danger);
    border: 1px solid color-mix(in srgb, var(--dcs-color-danger) 34%, transparent);
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
/* 题8 甲：反问回执行——安静一行，点「详情」展开逐题问答（题目加粗、回答偏灰） */
.askrc {
  margin: 6px 0 2px;
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
  overflow: hidden;
}
.askrc-hd {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: 0;
  background: transparent;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  color: var(--dcs-text-secondary);
  text-align: left;

  &:hover {
    background: var(--el-fill-color-lighter);
  }
}
.askrc-lb {
  color: var(--dcs-text-primary);
}
.askrc-fail {
  color: var(--dcs-color-danger);
  border-bottom: 1px dashed currentColor;
  cursor: copy;
}
.askrc-x {
  margin-left: auto;
  color: var(--dcs-text-placeholder);
  font-size: 11px;
}
.askrc-body {
  border-top: 1px solid var(--dcs-border-color-lighter);
  padding: 8px 10px 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.askrc-q {
  display: grid;
  gap: 2px;
  border-left: 1px solid var(--dcs-border-color-light);
  padding-left: 10px;

  b {
    font-size: 12px;
    font-weight: 600;
    color: var(--dcs-text-primary);
  }
  span {
    font-size: 12px;
    color: var(--dcs-text-secondary);
  }
}
@keyframes dcs-tool-in {
  from {
    opacity: 0;
    transform: translateY(2px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@media (prefers-reduced-motion: reduce) {
  .dcs-turn .tool {
    animation: none;
  }
}
@keyframes dcs-istk {
  50% {
    opacity: 0.35;
  }
}
/* 图标态修复（批J-2 P1）：cls 经 ic() 落在 v-html 注入的 svg 上，scoped 编译后
   `.spin[data-v]` 永不命中（svg 无 data-v 属性）——必须 :deep 才能生效 */
.dcs-turn :deep(.spin) {
  /* 题7 甲（对齐 ZCode reasoning.tsx / ToolLayout.tsx：时间线里不许有旋转 loader）：
     运行态只留静态图标 + 文字扫光，别让一排小圈同时转 */
  animation: none;
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
/* 折叠态偷看行（原型 tpeek/fpeekrow L338-346）
   Q18：单行 → 两行。max-height 正好 2×20px；贴底对齐让「最新想到的那句」始终在窗口里，
   超出的旧文本从上方裁掉（有没有裁由 .fade-t 决定：真裁了才做顶部渐隐，
   否则第一行会被拦腰切断，看起来像渲染坏了）。短文本仍只占一行，不会白留 20px。 */
.tpeek {
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  max-height: 40px;
  overflow: hidden;
  font-size: 11.5px;
  line-height: 20px;
  color: var(--dcs-text-placeholder);
  cursor: pointer;
  border-radius: 6px;
  padding: 0 4px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;

  &.fade-t {
    -webkit-mask-image: linear-gradient(180deg, transparent 0, #000 14px);
    mask-image: linear-gradient(180deg, transparent 0, #000 14px);
  }

  &:hover {
    box-shadow: inset 0 0 0 1px var(--dcs-border-color-light);
  }
}
/* Q17 空窗占位：过程区还没有任何一行时显示。放在 .proc 里时左起点=14px（容器 padding），
   图标 14 + gap 8 → 文字与思考正文同起点（36px），不会看起来像另一套缩进 */
.twait {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--dcs-text-secondary);
}
/* 折叠态复用 .tpeek：Q18 起父级是「两行贴底」的纵向 flex，这一行要改回横排一行 */
.tpeek.twait {
  flex-direction: row;
  justify-content: flex-start;
  gap: 6px;
  -webkit-mask-image: none;
  mask-image: none;
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
.proc-wrap {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.3s ease-in-out;

  &.open {
    grid-template-rows: 1fr;
  }
  /* 收起时裁掉溢出才能让 0fr 真正变 0；展开态还原可见，别把内部滚动/吸附一起废掉 */
  > .proc {
    min-height: 0;
    overflow: hidden;
  }
  &.open > .proc {
    overflow: visible;
  }
  /* 动画这 300ms 内一律裁掉：展开/收起的中间态不能让明细压到答案上（动画结束还原 visible） */
  &.animating > .proc {
    overflow: hidden;
  }
}
@media (prefers-reduced-motion: reduce) {
  .proc-wrap {
    transition: none;
  }
}
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
  /* Q17：一条过程行都没有时不画收尾线——空窗期凭空一条横线更像「内容丢了」 */
  &.proc-empty::after {
    content: none;
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
      /* 题9 甲：四个状态词统一 1.4s（ZCode .zcode-loading-label 同参数） */
      color: var(--el-color-primary);
      animation: dcs-sweep 1.4s linear infinite;
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
    /* Q14 块内「停止思考」已删：停止入口收敛到聊天框那一个按钮（ZCode 同款） */
    /* 展开钮默认隐、hover/展开时显（原型 tk-c L529-530） */
    /* Q16：右侧「剩余量 + 箭头」成组靠右，箭头不再被提示文字挤到中间 */
    .tk-r {
      margin-left: auto;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      flex: none;
    }
    .tk-more {
      font-size: 11px;
      color: var(--dcs-text-placeholder);
      white-space: nowrap;
      font-variant-numeric: tabular-nums;
    }
    .tk-c {
      font-size: 12px;
      opacity: 0;
      transition: opacity 0.15s;
    }
    &:hover .tk-c,
    .tk-c.on {
      opacity: 1;
    }
    /* Q17 触屏没有 hover：收起态箭头常显 35%，否则「可展开」这条唯一线索永远看不到；
       桌面仍保持 hover 才显（ZCode 同款，避免每行右侧多一个符号显吵） */
    @media (hover: none) {
      .tk-c {
        opacity: 0.35;
      }
    }
    &:hover .tk-t {
      color: var(--dcs-text-primary);
    }
  }
  /* Q13 流式摘要小窗：独占整行（max-height 5 行，12px×18px 行高 → 90px），
     宽度=内容列-22 缩进；Q16 起遮罩改为动态，见 .fade-t / .fade-b */
  .tk-live {
    margin: 2px 0 3px 22px;
    max-height: 90px;
    overflow-y: auto;
    /* Q16：11.5px + placeholder 灰在真机上只有 3.03:1，几乎看不清；12px/18px 整数行高
       与点开态（.tk-b 12px）统一，灰阶抬到 secondary（实测 4.93:1） */
    font-size: 12px;
    line-height: 18px;
    color: var(--dcs-text-secondary);
    white-space: pre-wrap;
    word-break: break-word;
    scrollbar-width: none;
    cursor: pointer;
    &::-webkit-scrollbar {
      display: none;
    }
  }
  /* Q16 动态遮罩：只在那一侧还有内容时渐隐。贴底=底部不再压暗（原来 55% 起的静态渐隐
     正好把「最新想到的一行」压成半透明，边跟边看不清）。 */
  .tk-live.fade-b,
  .tk-b.fade-b {
    -webkit-mask-image: linear-gradient(180deg, #000 calc(100% - 16px), transparent 100%);
    mask-image: linear-gradient(180deg, #000 calc(100% - 16px), transparent 100%);
  }
  .tk-live.fade-t,
  .tk-b.fade-t {
    -webkit-mask-image: linear-gradient(180deg, transparent 0, #000 16px);
    mask-image: linear-gradient(180deg, transparent 0, #000 16px);
  }
  .tk-live.fade-t.fade-b,
  .tk-b.fade-t.fade-b {
    -webkit-mask-image: linear-gradient(180deg, transparent 0, #000 16px, #000 calc(100% - 16px), transparent 100%);
    mask-image: linear-gradient(180deg, transparent 0, #000 16px, #000 calc(100% - 16px), transparent 100%);
  }
  .tk-b {
    margin: 2px 0 2px 22px;
    padding-left: 12px;
    border-left: 1px solid var(--dcs-border-color-light);
    /* Q15：300 → 306——12px×1.7=20.4，306 正好 15 行整，收尾不再切出半行 */
    max-height: 306px;
    overflow-y: auto;
    /* Q15：滚动条常显。原先是系统 overlay 规则，静止时柄会淡出，看不出还能往下滚 */
    scrollbar-width: thin;
    scrollbar-color: var(--dcs-border-color) transparent;
    &::-webkit-scrollbar {
      width: 6px;
    }
    &::-webkit-scrollbar-track {
      background: transparent;
    }
    &::-webkit-scrollbar-thumb {
      background: var(--dcs-border-color);
      border-radius: 3px;
      &:hover {
        background: var(--dcs-text-placeholder);
      }
    }
    font-size: 12px;
    line-height: 1.7;
    color: var(--dcs-text-placeholder);
    /* Q2 甲：纯文本保留换行/缩进（原来是 markdown 段落渲染） */
    white-space: pre-wrap;

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
@keyframes dcs-pxon {
  0%,
  100% {
    opacity: 0.15;
    transform: scale(1);
  }
  35% {
    opacity: 0.75;
    transform: scale(1.08);
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
/* Q6 甲（2026-11-07 拍板，照 ZCode）：连续同类工具组头——次数 / 合计耗时 / 失败数；
   点组头展开组内明细，单次调用不建组。组内行缩进一级，一眼看出从属关系。 */
.tgh {
  display: flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  max-width: 100%;
  margin: 2px 0;
  padding: 2px 6px 2px 2px;
  border: 0;
  background: none;
  border-radius: 6px;
  font-size: 12.5px;
  color: var(--dcs-text-secondary);
  cursor: pointer;

  &:hover {
    background: rgba(127, 127, 127, 0.09);
  }
  &:focus-visible {
    outline: 2px solid var(--el-color-primary-light-5);
  }
  .tgh-n {
    font-weight: 500;
  }
  .tgh-m {
    color: var(--dcs-text-placeholder);
    font-size: 11px;
    font-variant-numeric: tabular-nums;
  }
  .tgh-c {
    margin-left: 2px;
    color: var(--dcs-text-placeholder);
  }
}
.tool.in-grp {
  margin-left: 18px;
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
  /* Q6 甲（2026-11-07 拍板，照 ZCode）：三段式详情——参数 / 结果 / 错误；
     限高 240px 内滚，原始 JSON 收进第二层折叠（不再一上来铺一屏深色 JSON） */
  .s-det {
    margin: 4px 0 2px 25px;
    max-height: 240px;
    overflow: auto;
    border: 1px solid var(--dcs-border-color-light);
    border-radius: var(--dcs-radius-sm, 8px);
    background: rgba(127, 127, 127, 0.05);
    padding: 8px 10px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    font-size: 11.5px;
    line-height: 1.6;

    /* 2026-11-09：数据卡（rows 型结果）搬进这里后，240px 限高会把它裁成一条缝、
       还要在大表里再滚一层——带数据卡时放开限高，滚动交给卡自己的长表折叠 */
    &.has-dc {
      max-height: none;
      overflow: visible;
    }
  }
  .sd-h {
    font-size: 10.5px;
    color: var(--dcs-text-placeholder);
    letter-spacing: 0.04em;
    margin-bottom: 2px;
  }
  .sd-kv {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 12px;
  }
  .sd-p {
    display: inline-flex;
    align-items: baseline;
    gap: 4px;
    word-break: break-all;

    i {
      font-style: normal;
      color: var(--dcs-text-placeholder);
    }
  }
  .sd-t {
    white-space: pre-wrap;
    word-break: break-word;
    color: var(--dcs-text-secondary);
  }
  .sd-err .sd-t {
    color: var(--dcs-color-danger);
  }
  .sd-copy {
    margin-top: 4px;
    padding: 1px 8px;
    border: 1px solid var(--dcs-border-color-light);
    border-radius: 6px;
    background: none;
    color: var(--dcs-text-secondary);
    font-size: 11px;
    cursor: copy;
  }
  .sd-raw {
    summary {
      cursor: pointer;
      color: var(--dcs-text-placeholder);
      font-size: 11px;
    }
    pre {
      margin: 6px 0 0;
      max-height: 180px;
      overflow: auto;
      white-space: pre-wrap;
      word-break: break-all;
      font-family: ui-monospace, Menlo, Consolas, monospace;
      font-size: 11px;
      color: var(--dcs-text-secondary);
    }
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
  /* 题7 甲：工具行是突然插进来的——补 200ms 淡入 + 2px 上移，不抢注意力 */
  animation: dcs-tool-in 0.2s ease-out;
  /* 运行/失败整行着色（原型 L369-371：st-run 主色+动词扫光 / st-err 红） */
  &.st-run .th {
    color: var(--el-color-primary);

    .s-verb {
      background: linear-gradient(90deg, #6b7280 25%, var(--el-color-primary) 50%, #6b7280 75%);
      background-size: 200% 100%;
      -webkit-background-clip: text;
      background-clip: text;
      color: transparent;
      /* 题9 甲：状态词统一 1.4s */
      animation: dcs-sweep 1.4s linear infinite;
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
  font-size: var(--dcs-answer-size, 14px);
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

    /* Q16 流式期行内渲染（v-html 内容无 data-v，必须 :deep）——完整 .md 那套只服务完成态 */
    :deep(strong) {
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    :deep(code) {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 11.5px;
      padding: 1px 4px;
      border-radius: 4px;
      background: var(--el-fill-color-light, #f5f5f5);
    }
    :deep(.dcs-link) {
      color: var(--el-color-primary);
      cursor: pointer;
      text-decoration: underline;
      text-underline-offset: 2px;
    }

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
  color: var(--app-primary-ink);
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
      color: var(--app-primary-ink);
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
/* 业务批(1015) Q6：联网搜索来源卡（cherry-studio/LobeChat 同款"底部折叠来源条"形态）
   2026-11-09 改回 ZCode 口径后只剩"真网页来源"一种，分组/只看被引用的样式一并删除 */
.src-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  text-align: left;
  padding: 6px 6px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: none;
  cursor: pointer;
  font-size: 12px;
  color: var(--dcs-text-regular);

  &:hover,
  &.hot {
    background: var(--el-color-primary-light-9);
    border-color: var(--el-color-primary-light-7);
  }
  b {
    flex: none;
    color: var(--el-color-primary);
    font-weight: 600;
  }
  .src-fav {
    flex: none;
    width: 16px;
    height: 16px;
    border-radius: 4px;
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
    font-size: 10px;
    font-weight: 700;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;

    .src-favimg {
      width: 100%;
      height: 100%;
      object-fit: contain;
      display: block;
    }
  }
  .src-main {
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
  .src-t {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .src-m {
    font-size: 10.5px;
    color: var(--dcs-text-placeholder);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
.cite-note {
  font-size: 11px;
  color: var(--dcs-text-secondary);
  padding-top: 6px;
  line-height: 1.4;
}
/* Q6 丙：正文里的 [n] —— 与来源列表互相跳转的锚点 */
:deep(.refn) {
  color: var(--el-color-primary);
  cursor: pointer;
  padding: 0 1px;
  border-radius: 3px;
  font-variant-numeric: tabular-nums;

  &:hover,
  &.hot {
    background: var(--el-color-primary-light-8);
  }
}
@media (prefers-reduced-motion: reduce) {
  .spin,
  .cursor,
  .tk-t.run,
  /* 2026-11-09 代码席 P2：Q1 新加的像素轨道也是无限动画，系统开了「减少动态效果」
     就该停——被它替换掉的 .spin 原本在这张名单里，不补等于无障碍退让能力回退 */
  .px i {
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
