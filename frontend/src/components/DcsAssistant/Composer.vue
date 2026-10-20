<template>
  <div ref="rootEl" class="dcs-comp" :class="{ full, blocked: !!blocked }" :aria-hidden="blocked || undefined" :inert="blocked || undefined">
    <!-- 队列条（v3.18 四件套展示位；aria-live 播报）。R1 回修：被禁态隐藏——
         活的"立即发送/继续发送"按钮与禁用横幅自相矛盾（dspro P1-3 配套） -->
    <div v-if="(state.queue.length > 0 || state.queuePaused) && !disabled" class="queue-bar" aria-live="polite">
      <div class="qb-hd">
        <!-- BN-18 原型口径：头部带操作图例（改 · 提前 · 移除） -->
        <span>排队 {{ state.queue.length }} 条 · 答完自动逐条发送</span>
        <span class="qb-tip">✎ 改 · ↗ 提前 · ✕ 移除</span>
        <button v-if="state.queuePaused" class="qb-go" @click="emit('queue-resume')">继续发送</button>
      </div>
      <div v-if="state.queuePaused" class="qb-p">你中断了本轮回答，队列已暂停</div>
      <div v-for="(q, i) in state.queue" :key="q.pendingId" class="qb-it">
        <span class="q-n">{{ QN[i] ?? i + 1 }}</span>
        <!-- AU-33: 截 18 字必带 :title 全文（长问句悬停可读全文） -->
        <span class="q-t" :title="q.text">{{ q.text.length > 18 ? q.text.slice(0, 18) + '…' : q.text }}</span>
        <span class="q-ops">
          <button title="撤回修改" @click="emit('queue-edit', i)">✎</button>
          <button :title="sending ? '排到队首，答完就发' : '立即发送'" @click="emit('queue-go', i)"><IconSpan name="arrow-up-right" /></button>
          <button title="移除" @click="emit('queue-drop', i)"><IconSpan name="x" /></button>
        </span>
      </div>
    </div>

    <!-- 建议芯片（身份化；开聊隐藏、空会话恢复；点选回填聚焦） -->
    <div v-if="showChips" class="chips" :class="{ used: !chipsVisible }">
      <button v-for="c in roleChips" :key="c.label" class="chip" @click="pickChip(c.prompt)">{{ c.label }}</button>
    </div>

    <!-- 我的常用（原型 favBar：⭐ 回答上收藏的问法，点=直接发问，✕=移除；上限 6 显 4） -->
    <div v-if="favs.length" class="favbar">
      <span class="flab"><IconSpan name="star" /> 常用</span>
      <!-- BN-5 原型 quickFav=quick()：点收藏=回填输入框（不直接发） -->
      <span v-for="f in favs.slice(0, 4)" :key="f" class="chip fchip" :title="f" @click="fillDraft(f)">
        {{ f.length > 10 ? f.slice(0, 10) : f
        }}<u title="从常用移除" role="button" tabindex="0" @click.stop="removeFav(f)" @keydown.enter.stop="removeFav(f)"><IconSpan name="x" /></u>
      </span>
    </div>

    <!-- 会话附件条（ATTACH：挂会话多轮追问；上传后出现，状态人话） -->
    <div v-if="attachments.length || uploading" class="attbar">
      <span class="flab"><IconSpan name="paperclip" /> 附件</span>
      <!-- AU-32: 上传中的占位 chip（OSS+登记+解析串行，秒级 IO 无进度回调，态标注足够诚实） -->
      <span v-if="uploading" class="chip fchip uploading"><em class="att-st">上传中…</em></span>
      <span v-for="a in attachments" :key="a.id" class="chip fchip" :title="attTitle(a)"
        >{{ shortName(a.fileName) }}<em class="att-st">{{ attStatus(a) }}</em
        ><button class="att-x" title="移除附件" :aria-label="'移除附件 ' + a.fileName" @click="removeAttachment(a)">
          <IconSpan name="x" :size="10" /></button
      ></span>
    </div>

    <div
      class="box"
      :class="{ focus: focused, dragover }"
      @dragover.prevent="dragover = true"
      @dragleave.prevent="dragover = false"
      @drop.prevent="onDrop"
    >
      <!-- / 命令与 @ 引用任务菜单（cmdPop；批J-2：absolute 悬浮在输入盒上方，不再文档流下推）；
           批17 Q3：命令分「功能命令/快捷问句」双组（grp 变化处插组头），提示行加 Tab 补全 -->
      <div v-if="cmd.open" class="cmdpop" role="listbox" :aria-label="cmd.mode === 'slash' ? '命令菜单' : '引用任务'">
        <div class="cmd-tip">
          {{ cmd.mode === 'slash' ? '命令 · ↑↓ 选择 · Tab 补全 · Enter 确认 · 支持拼音首字母（如 /db→待办）' : '引用任务 · ↑↓ 选择，Enter 回填' }}
        </div>
        <template v-for="(it, i) in cmd.items" :key="it.label">
          <div v-if="cmd.mode === 'slash' && (i === 0 || cmd.items[i - 1].grp !== it.grp)" class="cmd-grp">
            {{ it.grp === 'fn' ? '功能命令' : '快捷问句' }}
          </div>
          <button
            class="cmd-it"
            :class="{ on: i === cmd.active }"
            role="option"
            :aria-selected="cmd.active === i"
            @mouseenter="cmd.active = i"
            @click="cmdPick(i)"
          >
            <span class="ci"><IconSpan :name="it.icon" /></span>
            <b>{{ it.label.length > 18 ? it.label.slice(0, 18) + '…' : it.label }}</b>
            <span class="cs">{{ it.sub }}</span>
          </button>
        </template>
        <div v-if="cmd.items.length === 0" class="cmd-empty">没有匹配项</div>
      </div>
      <textarea
        ref="taRef"
        v-model="draft"
        rows="1"
        :placeholder="disabled ? '管理员已暂停你的 AI 助手使用' : placeholder"
        maxlength="16000"
        :aria-label="`向 ${assistantName} 提问`"
        :readonly="disabled"
        :class="{ 'ta-disabled': disabled }"
        @focus="focused = true"
        @blur="focused = false"
        @input="onInput"
        @keydown="onKey"
      />
      <div class="brow">
        <!-- ➕ 添加面板（原型 addPop：每次都问/联网/查库/附件/引用/快捷能力）；bicon=28×28 无边框形态（左组独占） -->
        <button class="btool bicon" aria-label="添加" aria-haspopup="dialog" :aria-expanded="addOpen" title="添加" @click="addOpen = !addOpen">
          <IconSpan name="plus" :size="15" />
        </button>

        <!-- R1 联网一步钮（2026-10-13 用户提议「放 ➕ 旁边」）：常驻灰图标，点一下开/关；
             开态钮变蓝即状态显示（原「联网 ✕」状态签撤并——同一件事不标两遍），
             三分支语义与批④一致（未开放/用完见 title 与 toast） -->
        <button
          class="btool bicon wbtn"
          :class="{ on: webOn && !webDisabled }"
          role="switch"
          :aria-checked="webOn && !webDisabled"
          :aria-disabled="webDisabled || undefined"
          :aria-label="`联网搜索开关（${webOn && !webDisabled ? '已开' : '已关'}）`"
          :title="wbtnTitle"
          @click="toggleWeb(!webOn)"
        >
          <IconSpan name="globe" :size="15" />
        </button>

        <!-- 业务批7 Q3：技能胶囊挪到联网开关旁（会话级开关联动族：➕ 选完=本会话增强，
             与联网同侧语义一致；小屏 <560px 沿用 bpill 图标化规则不挤爆） -->
        <span
          v-if="skillCur"
          class="btool bpill spill"
          :title="`技能「${skillCur.name}」本会话每轮生效：${skillCur.desc}。点 × 停用`"
          role="button"
          tabindex="0"
          @click="addOpen = true"
          @keydown.enter.self="addOpen = true"
        >
          <IconSpan name="sparkles" :size="14" />
          <span class="tlabel">{{ skillCur.name }}</span>
          <button class="sx" aria-label="停用技能" title="停用技能（本会话不再按它执行）" @click.stop="clearSkill">
            <IconSpan name="x" :size="12" />
          </button>
        </span>

        <span class="spacer" />

        <!-- 会话状态合并环（批B B2 拍板 2026-10-11）：环主体=上下文已用%（正常主色 →
             超 75% 橙 → 超 90% 红）；额度不抢环色，剩<20%/用完只在环角亮哨兵点；
             悬停/点击出同款双段卡（触屏可点）；新会话无 token 记录=空环待测 -->
        <button
          class="btool ring-btn"
          :class="{ 'ctx-open': ringCardOpen }"
          :aria-label="ringAria"
          title="会话状态（悬停或点击看明细）"
          @click="toggleRingCard"
        >
          <span class="ringwrap">
            <svg class="cring" viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="12" cy="12" r="9" class="cr-bg" />
              <circle cx="12" cy="12" r="9" class="cr-fg" :style="ringFgStyle" />
            </svg>
            <span v-if="quotaBadge" class="qdot" :class="quotaBadge" />
          </span>
          <!-- 业务批7 Q15（2026-10-14 拍板方案A）：回原尺寸+纯进度条——每行=标签+条+值，
               长解释/恢复时间全部进 title hover；订阅租户积分区=三行微条 5小时窗→周池→加油包（有余额才显示） -->
          <span class="ctxpop">
            <b>会话状态</b>
            <span
              class="ctxrow"
              :title="
                (ctxKnown ? `本会话上下文占用 ${fmtK(ctxTokens)} / ${fmtK(ctxWindow)} tokens` : ctxEmptyHint) + ' · 超 75% 变橙 · 超 90% 建议新开会话'
              "
            >
              <span class="cl">上下文</span>
              <span class="cbar"><i :style="ctxBarStyle" /></span>
              <span>{{ ctxKnown ? Math.round(ctxPct * 100) + '%' : '待测' }}</span>
            </span>
            <!-- 批17 Q2：手动压缩入口（环变红时的自救手段；超阈值后端也会自动压） -->
            <span class="ctxrow" title="把前面的对话打包成摘要，腾出上下文空间（超过租户阈值时也会自动压缩）">
              <span class="cl">压缩</span>
              <button class="ctx-act" :disabled="compacting || !ctxKnown" @click="onCompactContext()">
                {{ compacting ? '压缩中…' : '打包前文' }}
              </button>
            </span>
            <span class="ctxdiv" />
            <template v-if="billingMode === 'credits'">
              <!-- 业务批(1015) Q8：常显消费方式徽标——演示/实测"池子不动"的困惑根因=不知这轮扣哪本账 -->
              <span class="ctxrow" title="助手对话当前扣哪本账：积分订阅=扣周池/加油包（钱包不动）；在「租户管理 → 积分套餐」可切换">
                <span class="cl">消费方式</span>
                <span class="ctx-tag tag-credit">积分订阅</span>
              </span>
              <!-- 业务反馈批1015b Q11（用户拍板"尽量简洁"）：撤「本周积分」文本行——周池微条已是
                   同一口径（悬停有精确值），纯冗余；明细入口挪进周池行 title 提示去积分套餐页 -->
              <!-- Q15 方案A：三行微条——名字+6px条+%；精确值/恢复时间进 title hover -->
              <span v-if="creditSummary" class="mbrows">
                <span
                  class="mbrow"
                  :class="{ warn: creditWindowLeft > 0 && creditWindowLeftPct <= 20, empty: creditWindowLeft <= 0 }"
                  :title="`近 5 小时滑动窗口：剩 ${fmtCredit(creditWindowLeft)} / ${fmtCredit(creditSummary.windowCap)} · 早期消耗随时间移出窗口自动回额（防刷护栏）`"
                >
                  <span class="mn">5小时窗</span>
                  <span class="mtrack"><i :style="{ width: creditWindowLeftPct + '%', background: 'var(--el-color-primary)' }" /></span>
                  <span class="mv">{{ creditWindowLeftPct }}%</span>
                </span>
                <span
                  class="mbrow"
                  :class="{ warn: creditWeeklyLeft > 0 && creditWeeklyLeftPct <= 20, empty: creditWeeklyLeft <= 0 }"
                  :title="`本周积分池：剩 ${fmtCredit(creditWeeklyLeft)} / ${fmtCredit(creditSummary.weeklyCap)} · 每周一 0 点整池恢复 · 明细见「租户管理 → 积分套餐」`"
                >
                  <span class="mn">周池</span>
                  <span class="mtrack"><i :style="{ width: creditWeeklyLeftPct + '%', background: 'var(--el-color-success)' }" /></span>
                  <span class="mv">{{ creditWeeklyLeftPct }}%</span>
                </span>
                <span
                  v-if="creditTankShow"
                  class="mbrow"
                  :title="`管理员追加的加油包余额：剩 ${fmtCredit(creditSummary?.tankBalance)} · 用完即止不恢复`"
                >
                  <span class="mn">加油包</span>
                  <span class="mtrack"><i :style="{ width: '100%', background: 'var(--el-color-warning)' }" /></span>
                  <span class="mv">{{ fmtCredit(creditSummary?.tankBalance ?? 0) }}</span>
                </span>
              </span>
              <!-- dsflash P2-3：summary 未就绪时微条整组不渲染，避免 0 值误报「已用完」 -->
              <span v-else class="ctxsub">三本账待测</span>
            </template>
            <template v-else>
              <!-- 业务批(1015) Q8：钱包分支同样常显消费方式（与积分套餐页口径一致） -->
              <span class="ctxrow" title="助手对话当前扣哪本账：钱包按量=按实际用量扣租户钱包（元）；订阅积分套餐后自动切换为扣积分">
                <span class="cl">消费方式</span>
                <span class="ctx-tag tag-wallet">钱包按量</span>
              </span>
              <span class="ctxrow" :title="`每人每天各有一份，互不影响 · 还能聊 ${remain} 个来回 · 明早 0 点恢复`">
                <span class="cl">今日额度</span>
                <span class="cbar"><i :style="{ width: usedPct * 100 + '%' }" /></span>
                <span>{{ used }} / {{ limit }}</span>
              </span>
            </template>
            <span class="ctxdiv" />
            <span
              class="ctxrow"
              :class="{ 'web-out': webOn && !webDisabled && webLeft <= 0 }"
              :title="webDisabled ? '管理员未开放联网（额度为 0）' : `每天 ${webLimit} 次 · 明早 0 点恢复`"
            >
              <span class="cl">联网次数</span>
              <span class="cbar"><i :style="{ width: webPct * 100 + '%' }" /></span>
              <span>{{ webUsed }} / {{ webLimit }}</span>
            </span>
            <!-- m3 二轮 P1：「未开放(额度0)」≠「当日用完」，是状态不是解释——仅此一种保留副行 -->
            <span v-if="webDisabled" class="ctxsub">未开放（额度为 0）</span>
            <span class="ctxdiv" />
            <!-- 业务反馈批1015b Q11：改回「缓存命中」+ 恢复平均值口径（用户原话"为什么没有
                 缓存平均值了"）——今日平均（LiteLLU 口径样本<3 显 —），单行不动用副行（拍板=尽量简洁）
                 快刀批 Q5：tooltip 人话化（拍板页 v4 口径——何时掉、为什么聊天场景天然低于编码） -->
            <span class="ctxrow" title="缓存命中：模型不用重读前文的重复部分，直接用缓存结果——更快更省（缓存价约为原价一折）。这里显示今日平均（今天聊得还少时显示 —）。什么时候会掉：新会话第一轮必不命中；会话中途开联网、换模型会让缓存重新预热。聊天场景普遍在 5~8 成，属健康区间，编码工具的 9 成以上学不来（场景不同）。">
              <span class="cl">缓存命中</span>
              <span>{{ cacheHitText }}</span>
            </span>
          </span>
        </button>

        <!-- 业务批7 Q2：技能胶囊已挪联网旁，此处原右组位移除 -->

        <!-- 模型选择（批7 Q5 消费端+Q12-④ 按会话记住）：管理员开「允许用户自选」才显示；
             选中存 localStorage 分 thread 各记一份，同会话后续消息都走它 -->
        <div
          v-if="choiceOn"
          class="btool bpill mpill"
          :class="{ 'mpill-warn': defaultMissing }"
          :title="
            defaultMissing
              ? '管理员设置的默认模型当前不在可用清单（可能没配人民币价格或已停用），请联系管理员到「AI 配置」处理'
              : `本会话模型：${modelBtnLabel}`
          "
          :aria-expanded="modelPopOpen"
          aria-label="选择模型"
          @click="toggleModelPop"
        >
          <IconSpan name="package" :size="14" />
          <span class="tlabel">{{ modelBtnLabel }}</span>
          <IconSpan name="chevron-down" :size="13" class="bchev" />
          <!-- 模型选择弹层（与思考弹层同款 autopop 右锚；首行=跟随默认，其余=本租户可用模型+单价） -->
          <div v-if="modelPopOpen" class="autopop tpop mpop" role="dialog" aria-label="选择模型">
            <button class="apopt" :class="{ on: !modelPin }" @click="pickModel(null)">
              <span class="apic"><IconSpan name="star" :size="15" /></span>
              <span class="aptx"
                ><b>跟随默认</b><span>{{ defaultModelLabel }}</span></span
              >
              <span v-if="!modelPin" class="apck"><IconSpan name="check" :size="14" /></span>
            </button>
            <!-- 批B 三审 P1：组头移出按钮独立成行——修两点：①数据源排序不含 provider 时相邻比较会重复组头
                 ②组头在 button 内点击=误选组首模型。分组改 find-or-create 累计算法（同 admin 页） -->
            <template v-for="g in groupedModelOptions" :key="g.provider">
              <div class="apgrp">{{ g.label }}</div>
              <button
                v-for="o in g.options"
                :key="String(o.configId)"
                class="apopt"
                :class="{ on: String(o.configId) === modelPin }"
                @click="pickModel(String(o.configId))"
              >
                <span class="apic"><IconSpan name="package" :size="15" /></span>
                <span class="aptx"
                  ><b>{{ o.modelName }}</b
                  ><span>{{ modelPriceLine(o) }}</span></span
                >
                <span v-if="String(o.configId) === modelPin" class="apck"><IconSpan name="check" :size="14" /></span>
              </button>
            </template>
          </div>
        </div>

        <!-- 思考档位（v3.18.1 → 批I-3 BN-8：主钮恒 🧠+tprog 充能条，点开三选弹层原型 thinkPop）；
             批7 按能力显隐：当前生效模型能力目录 tierKind=NONE（不支持思考）时隐藏 -->
        <button
          v-if="thinkSupported"
          class="btool bpill"
          :title="thinkTitle"
          :aria-expanded="thinkPopOpen"
          @click="
            modelPopOpen = false;
            thinkPopOpen = !thinkPopOpen;
          "
        >
          <IconSpan name="brain" :size="14" />
          <i class="tprog"><i class="tfill" :style="{ height: ((thinkLevel + 1) / 3) * 100 + '%' }" /></i>
          <span class="tlabel">{{ THINK[thinkLevel].label }}</span>
          <IconSpan name="chevron-down" :size="13" class="bchev" />
          <!-- 思考三档弹层（原型 thinkPop：zap/scale/microscope 三选+对勾，选中 700ms 收）；
               T-2(1008 拍板B)：尾部加「过程显示」开关行——档位管思考多少，眼睛管显示与否，凑一组 -->
          <div v-if="thinkPopOpen" class="autopop tpop" role="dialog" aria-label="思考程度">
            <button v-for="(t, i) in THINK" :key="t.tier" class="apopt" :class="{ on: i === thinkLevel }" @click="pickThink(i)">
              <span class="apic"><IconSpan :name="t.icon" :size="15" /></span>
              <span class="aptx"
                ><b>{{ t.label }}</b
                ><span>{{ t.title }}</span></span
              >
              <span v-if="i === thinkLevel" class="apck"><IconSpan name="check" :size="14" /></span>
            </button>
            <button
              class="apopt tshow"
              role="switch"
              :aria-checked="state.showThink"
              title="开=每条回复的思考过程默认展开；关=点哪条开哪条"
              @click="emit('think-show', !state.showThink)"
            >
              <span class="apic"><IconSpan name="eye" :size="15" /></span>
              <span class="aptx"><b>过程显示</b><span>展开每条回复的思考过程</span></span>
              <span class="apsw" :class="{ on: state.showThink }"><i /></span>
            </button>
          </div>
        </button>
        <button v-if="sending" class="round-btn stop show" aria-label="停止生成" title="停止（点错可点「继续说完」，Esc 同效）" @click="emit('stop')">
          <svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2" /></svg>
        </button>
        <!-- 发送键状态机（批J-2 对齐原型 L255：32×32 方圆角 8px 深灰 #1f2937；空=灰 dim；sending=深灰+琥珀角标）。永不 disabled（BN-9：空点=续发暂停的队列） -->
        <button
          class="round-btn send"
          :class="{ dim: !canSend && !sending, busy: sending }"
          :aria-label="sending ? '排队' : '发送'"
          :title="sendTitle"
          @click="onSend"
        >
          <svg
            viewBox="0 0 24 24"
            width="14"
            height="14"
            fill="none"
            stroke="currentColor"
            stroke-width="2.4"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <path d="M12 19V5" />
            <path d="m5 12 7-7 7 7" />
          </svg>
          <span v-if="sending" class="qb" aria-hidden="true">＋</span>
        </button>
      </div>

      <!-- ➕ 添加面板弹层（滚动只在 .alist 行区——弹层/底条留在外面防 overflow 裁切） -->
      <div v-if="addOpen" ref="addpopRef" class="addpop" role="dialog" aria-label="添加">
        <div class="ah">添加</div>
        <div class="alist">
          <!-- 自主权行（原型 apAuto：行首恒 🤖，三处文案随档同步，档2 小签 .hot；desc 溢出 ellipsis=原型同款，悬停看全文） -->
          <button class="arow" :aria-expanded="autoPopOpen" :title="autoCur.desc" @click="autoPopOpen = !autoPopOpen">
            <IconSpan name="bot" />
            <b>{{ autoCur.title }}</b>
            <span>{{ autoCur.desc }}</span>
            <span class="mch" :class="{ hot: autoIdx === 2 }">{{ autoCur.mch }} <IconSpan name="chevron-down" :size="12" /></span>
          </button>
          <!-- F-D2（2026-10-06）：联网/查库已真实上线，摘「即将上线」占位——
               联网=问外部知识自动启用（管理员未配 key 时助手会明说不可用）；
               查库=AI 默认查库回答，二者无前端开关，点击给说明而非死行 -->
          <!-- 业务批3-④ #17：联网会话级开关（默认关，grill Q6 定稿）；状态存
               dcs:assistant:webOn:{userId}:{threadId}（webOnState 单一真源，runTurn 出口注入） -->
          <div
            class="arow webrow"
            :title="
              webDisabled
                ? '联网搜索未开放（额度为 0），请联系管理员'
                : webOn
                  ? `联网已开 · 今天还能搜 ${webLeft} 次`
                  : '默认关闭；开启后问外部知识（新闻/天气/政策）会搜索网页并带来源，每天 ' + webLimit + ' 次免费额度'
            "
          >
            <IconSpan name="globe" /><b>联网搜索</b>
            <span class="tagline" :class="{ on: webOn && !webDisabled }">{{
              webDisabled ? '未开放' : webOn ? `剩 ${webLeft} 次` : `每天 ${webLimit} 次`
            }}</span>
            <button class="wsw" :class="{ on: webOn }" role="switch" :aria-checked="webOn" aria-label="联网搜索开关" @click="toggleWeb(!webOn)">
              <i />
            </button>
          </div>
          <button class="arow" type="button" @click="pushToast('查库已内置：待办、任务、提交状态等直接问，AI 自动查你有权限的数据')">
            <IconSpan name="database" /><b>查库</b><span>权限内数据直接问</span><span class="tagline on">已内置</span>
          </button>
          <button class="arow" type="button" @click="pickFile">
            <IconSpan name="paperclip" /><b>附件</b><span>上传表格、文档给小助分析</span><span class="tagline on">可提问</span>
          </button>
          <button class="arow" @click="startAt"><IconSpan name="target" /><b>引用任务</b><span>输入 @ 选择要聊的任务</span></button>
          <!-- 五需求批 R1：技能组（会话级选择，再点取消）。正文永不下发前端——后端选中当轮注入；
               发布人级技能按角色裁（与后端 SkillAssets.minRole 同口径） -->
          <div class="agrp">技能</div>
          <button
            v-for="s in skillCatalogForRole"
            :key="s.id"
            class="arow"
            :class="{ on: skillId === s.id }"
            :title="skillId === s.id ? `「${s.name}」已启用：本会话每轮生效，再点一次停用` : `启用「${s.name}」：本会话每轮按它的指引执行`"
            @click="toggleSkill(s.id)"
          >
            <i class="atile" :class="skillTileCls(s.id)">{{ s.tile }}</i>
            <b>{{ s.name }}</b>
            <span>{{ s.desc }}</span>
            <span class="tagline" :class="{ on: skillId === s.id }">{{ skillId === s.id ? '已启用' : '会话级' }}</span>
          </button>
          <div class="agrp">快捷能力</div>
          <!-- Q4：三行全员可见（成员触发写操作在确认/后端环节拦），问句对齐原型短句 -->
          <button class="arow" @click="quickSend('我的待办')"><i class="atile todo">办</i><b>待办速览</b><span>今天要处理什么</span></button>
          <button class="arow" @click="quickSend('最近哪个任务该催了？')">
            <i class="atile urge">催</i><b>该催谁了？</b><span>按截止和未交排序，挑一个催</span>
          </button>
          <button class="arow" @click="quickSend('广播任务是什么')">
            <i class="atile cast">播</i><b>广播任务解读</b><span>业务概念一句话讲清</span>
          </button>
        </div>
        <!-- 三档自主权弹层（原型 autoPop(apAutoLab,1)：三选+对勾+选中 700ms 收；alignend=右锚。
             必须是 addpop 直接子级——放 .alist 内会被滚动容器 overflow 裁掉（用户实锤「弹窗没出来」） -->
        <div v-if="autoPopOpen" class="autopop alignend" role="dialog" aria-label="自主权">
          <button v-for="(a, i) in AUTO_LV" :key="a.key" class="apopt" :class="{ on: i === autoIdx }" @click="pickAuto(i)">
            <span class="apic"><IconSpan :name="a.icon" :size="15" /></span>
            <span class="aptx"
              ><b>{{ a.popT }}</b
              ><span>{{ a.popD }}</span></span
            >
            <span v-if="i === autoIdx" class="apck"><IconSpan name="check" :size="14" /></span>
          </button>
        </div>
        <div class="afoot">
          <span><span class="akey">@</span>引用任务</span>
          <span><span class="akey">/</span>命令</span>
          <span class="ahint">ⓘ 输入内容以搜索</span>
        </div>
      </div>
    </div>
    <p class="hint">内容由 AI 生成，请注意核对</p>
    <!-- 隐藏文件选择（ATTACH：白名单+10MB 前置校验在 uploadOne） -->
    <input ref="fileInputRef" type="file" hidden multiple accept=".docx,.xlsx,.pptx,.pdf,.txt" @change="onFileChange" />
  </div>
</template>

<script setup lang="ts">
/**
 * 输入区（批E → PROTO PARITY 批B 全家桶，原型 .box.btools 形态）：
 * ➕添加面板（每次都问/联网/查库/附件=即将上线、@引用任务、快捷能力）+余量环（status 实值）
 * +思考三档（forwardedProps 透传）+发送键状态机（dim/橙+角标/title 三态）+占位轮换（身份词典 5s）
 * +/命令与@引用 cmdPop（↑↓EnterEsc）+ArrowUp 召回上一问+IME 守卫+拖文件虚线高亮。
 */
import { getAssistantStatus, getAssistantModelChoice, compactAssistantContext, type AssistantModelChoice } from '@/api/dcs/assistant';
import { getMyCreditSummary, type CreditSummaryVO } from '@/api/dcs/ai-credit';
import { checkPermi } from '@/utils/permission';
import { readWebOn, writeWebOn, webOnKey } from './webOnState';
import { SKILL_CATALOG, readSkillId, writeSkillId, isSkillKey } from './skillState';
import { matchAiCapability } from '@/api/dcs/ai';
import { mentionTasks } from '@/api/dcs/assistant';
import { useUserStore } from '@/store/modules/user';
import { roleOf } from './assistantCtx';
import { pushToast } from './toastStore';
import { assistantName } from './nameState';
import { ElMessageBox } from 'element-plus';
import { useFavs } from './favStore';
import IconSpan from './IconSpan.vue';
import type { AssistantState } from './useAssistant';
import { unwrap } from './util';
import { globalHeaders } from '@/utils/request';
import { listAssistantAttachments, registerAssistantAttachment, removeAssistantAttachment, type AssistantAttachmentItem } from '@/api/dcs/assistant';

const props = defineProps<{
  state: AssistantState;
  sending: boolean;
  active?: boolean;
  full?: boolean;
  /** 有待确认操作时收起输入框（保持挂载，草稿不丢）——对齐母仓 Q21-A / ZCode 口径 */
  blocked?: boolean;
}>();
const emit = defineEmits<{
  send: [text: string, stamp: string, fwd: Record<string, unknown>];
  stop: [];
  'queue-edit': [i: number];
  'queue-go': [i: number];
  'queue-drop': [i: number];
  'queue-resume': [];
  'auto-level': [level: 'always' | 'once' | 'auto'];
  /** T-2：思考过程总显示开关（弹层内「过程显示」行） */
  'think-show': [v: boolean];
  'open-help': [];
  'new-session': [];
  /** 批17 Q2：手动压缩完成（宿主收到后重拉会话消息，「已压缩」分隔条随历史重建出现） */
  compacted: [];
}>();

const QN = '①②③④⑤⑥⑦⑧⑨';

/* 会话附件（ATTACH 批：上传→登记→解析状态 chips；挂会话多轮追问） */
const ATTACH_SUFFIXES = ['docx', 'xlsx', 'pptx', 'pdf', 'txt'];
const ATTACH_MAX_BYTES = 10 * 1024 * 1024;
const ATTACH_MAX_COUNT = 3;
const attachments = ref<AssistantAttachmentItem[]>([]);
const fileInputRef = ref<HTMLInputElement | null>(null);
const uploading = ref(false);

function attStatus(a: AssistantAttachmentItem): string {
  switch (a.parseStatus) {
    case 'PARSED':
      return '已就绪';
    case 'TRUNCATED':
      return '过长已截';
    case 'FAILED':
      return '解析失败';
    default:
      return '解析中';
  }
}
function attTitle(a: AssistantAttachmentItem): string {
  const lines = a.lineCount ? `（${a.lineCount} 行）` : '';
  return a.parseStatus === 'FAILED' && a.parseError ? `${a.fileName}：${a.parseError}` : `${a.fileName}${lines}`;
}
function shortName(name: string): string {
  return name.length > 14 ? name.slice(0, 13) + '…' : name;
}

/** H9：active=false（抽屉隐身常驻形态）不发请求——双 Composer 并存时
 * 状态/附件接口不双打；抽屉打开（置活）时由 active 监听补拉。
 * ⚠️ Vue3 Boolean 断言坑（批B B2 E2E 实证）：布尔 prop 不传会被强制转成 false
 * 而非 undefined——调用方（会话页等非抽屉宿主）必须显式传 active，否则本守卫
 * 把常驻 composer 全部判死（状态/模型清单/上下文环全不刷新）。 */
function amActive(): boolean {
  return props.active !== false;
}

async function refreshAttachments() {
  const tid = props.state.threadId;
  if (!tid || !amActive()) return;
  try {
    const res: any = await listAssistantAttachments(tid);
    attachments.value = unwrap<AssistantAttachmentItem[]>(res);
  } catch {
    /* 挂载时失败不打扰（发消息不受影响） */
  }
}

/* 批E(AU-21 A)：解析异步化后有 PENDING 终态轮询——2s 一次，全到终态即停；
 * 回放挂载/上传完成都会经 refreshAttachments 触发，无 PENDING 不起表。
 * 批G G4：后端新增 PARSING（已开工）中间态，轮询条件同认（否则开工即停表） */
const ATT_ACTIVE_STATUSES = ['PENDING', 'PARSING'];
function isAttActive(a: AssistantAttachmentItem): boolean {
  return ATT_ACTIVE_STATUSES.includes(a.parseStatus);
}
let attachPollTimer: ReturnType<typeof setInterval> | null = null;
function stopAttachPolling() {
  if (attachPollTimer !== null) {
    clearInterval(attachPollTimer);
    attachPollTimer = null;
  }
}
function ensureAttachPolling() {
  if (attachPollTimer !== null) return;
  attachPollTimer = setInterval(async () => {
    await refreshAttachments();
    if (!attachments.value.some(isAttActive)) {
      stopAttachPolling();
    }
  }, 2000);
}
watch(attachments, (list) => {
  if (list.some(isAttActive)) {
    ensureAttachPolling();
  } else {
    stopAttachPolling();
  }
});

function pickFile() {
  fileInputRef.value?.click();
}

/** 移除附件（AU-05A）：软删腾挂载位，成功后刷新 chip 条 */
async function removeAttachment(a: AssistantAttachmentItem) {
  try {
    await removeAssistantAttachment(a.id);
    pushToast(`已移除 ${shortName(a.fileName)}`, 'ok');
    await refreshAttachments();
  } catch (err: any) {
    pushToast(err?.msg || '移除失败，请稍后再试', 'err');
  }
}

/** AU-11: 多选文件串行队列——旧 forEach + uploading 哨兵早退会把第 2+ 个文件
 *  直接 toast 拒绝丢掉；排队逐个传，单文件失败不断链 */
let uploadChain: Promise<void> = Promise.resolve();

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = '';
  for (const f of files) {
    uploadChain = uploadChain.then(() => uploadOne(f));
  }
  uploadChain = uploadChain.catch(() => undefined);
}

async function uploadOne(file: File) {
  const suffix = (file.name.split('.').pop() ?? '').toLowerCase();
  if (!ATTACH_SUFFIXES.includes(suffix)) {
    pushToast(`暂不支持 .${suffix}，可上传 ${ATTACH_SUFFIXES.join('/')}`, 'err');
    return;
  }
  if (file.size > ATTACH_MAX_BYTES) {
    pushToast('文件超过 10MB 上限，请压缩或拆分后再传', 'err');
    return;
  }
  if (attachments.value.length >= ATTACH_MAX_COUNT) {
    pushToast(`每个会话最多挂 ${ATTACH_MAX_COUNT} 个附件`, 'err');
    return;
  }
  uploading.value = true;
  // H7：上传期间用户可能切换/新建会话——threadId 开始时定格，附件登记归上传那一刻所属的会话
  const tid = props.state.threadId;
  try {
    // 第一步：通用 OSS 上传（bizSource=ASSISTANT 走 12b EXEMPT 免审留痕）
    const fd = new FormData();
    fd.append('file', file);
    fd.append('bizSource', 'ASSISTANT');
    const up = await fetch(import.meta.env.VITE_APP_BASE_API + '/resource/oss/upload', {
      method: 'POST',
      headers: { ...globalHeaders() } as Record<string, string>,
      body: fd
    });
    const upJson = await up.json();
    if (!up.ok || upJson.code !== 200) {
      pushToast(upJson?.msg || '上传失败，请稍后重试', 'err');
      return;
    }
    const ossId = upJson.data?.ossId;
    if (!ossId) {
      pushToast('上传返回异常（缺少文件标识），请重试', 'err');
      return;
    }
    // 第二步：登记（批E 起秒回 PENDING，解析在后台跑，chip 轮询到终态）
    const reg: any = await registerAssistantAttachment({ ossId, threadId: tid });
    const row = unwrap<AssistantAttachmentItem>(reg);
    if (row.parseStatus === 'FAILED') {
      pushToast(`已收到 ${file.name}，但解析失败：${row.parseError || '格式异常'}`, 'err');
    } else if (row.parseStatus === 'PENDING' || row.parseStatus === 'PARSING') {
      pushToast(`已收到 ${file.name}，正在解析…（好了就能直接问它）`, 'ok');
    } else {
      pushToast(`已就绪：${file.name}，直接问它吧（如「这个表第二列求和」）`, 'ok');
    }
    await refreshAttachments();
  } catch (err: any) {
    pushToast(err?.msg || '附件上传失败，请稍后重试', 'err');
  } finally {
    uploading.value = false;
  }
}

/* 回放/重进：挂载即拉本会话附件（threadId 变化=换会话也要刷新） */
watch(
  () => props.state.threadId,
  () => refreshAttachments(),
  { immediate: true }
);

/* 我的常用（⭐ 收藏；点击直接发问，✕ 移除） */
const { favs, remove: removeFav } = useFavs();
/** 思考三档（批I-1 图标名化 → 批I-3 BN-8 弹层化：主钮恒 brain+tprog，icon 供弹层行用） */
const THINK = [
  // 副句对齐原型 thinkPop 三行原文（L1813：简单查询秒答 · 最省 token / 日常查数与操作（默认） / 多步推理 · 口径排查 · 略慢但准）
  { icon: 'zap', label: '快速', title: '简单查询秒答 · 最省 token', tier: 'fast' },
  { icon: 'scale', label: '平衡', title: '日常查数与操作（默认）', tier: 'balanced' },
  { icon: 'microscope', label: '深入', title: '多步推理 · 口径排查 · 略慢但准', tier: 'deep' }
];
/** 身份化快捷 chips（成员面无催办词——v12:76 断言守护口径） */
const CHIPS_BY_ROLE: Record<string, Array<{ label: string; prompt: string }>> = {
  publisher: [
    { label: '我的待办', prompt: '我今天有哪些待办？' },
    { label: '谁逾期了', prompt: '我名下的任务有谁逾期了吗？' },
    { label: '任务进度', prompt: '我发布的任务提交进度怎么样？' },
    { label: '怎么操作', prompt: '退回重做该怎么操作？' }
  ],
  admin: [
    { label: '我的待办', prompt: '我今天有哪些待办？' },
    { label: '谁逾期了', prompt: '我名下的任务有谁逾期了吗？' },
    { label: '任务进度', prompt: '我发布的任务提交进度怎么样？' },
    { label: '怎么操作', prompt: '退回重做该怎么操作？' }
  ],
  member: [
    { label: '我的待办', prompt: '我今天有哪些待办？' },
    { label: '截止提醒', prompt: '最近三天有哪些任务要截止了？' },
    { label: '怎么操作', prompt: '退回重做该怎么操作？' },
    { label: '查我的提交', prompt: '帮我查一下我的提交状态。' }
  ]
};
/** 占位轮换词典（PHINT 三身份；有字/生成中不轮换） */
const PHINT: Record<string, string[]> = {
  publisher: [
    '试试问：我有哪些待办？',
    '试试问：谁逾期了？',
    '试试问：怎么给未交的成员发催办？',
    '试试问：广播任务是什么？',
    '试试问：最近三天要截止什么？'
  ],
  admin: [
    '试试问：我有哪些待办？',
    '试试问：看板的「已提交」怎么算的？',
    '试试问：追加类任务怎么拆批？',
    '试试问：谁逾期了？',
    '试试问：怎么撤回转发？'
  ],
  member: ['试试问：我有哪些待办？', '试试问：我交了吗？', '试试问：退回重做怎么操作？']
};
/** / 命令词典（批17 Q3 重组：功能命令=动作，快捷问句=替你说话；成员隐藏 /催办）。
 *  act 系命令在 cmdPick 走本地动作分支（压缩/模型/用量/说明/新会话），不回填输入框；
 *  CMD_PINYIN=首字母缩写表——/db 也能筛出 /待办（cherry-studio 同款思路，中文输入刚需）。 */
const CMD_PINYIN: Record<string, string> = {
  '/待办': 'db',
  '/逾期': 'yq',
  '/进度': 'jd',
  '/看板': 'kb',
  '/催办': 'cb',
  '/退修': 'tx',
  '/说明': 'sm',
  '/新会话': 'xhh',
  '/压缩': 'ys',
  '/模型': 'mx',
  '/用量': 'yl'
};
const matchCmd = (label: string, kw: string) =>
  !kw || label.slice(1).startsWith(kw) || label.slice(1).includes(kw) || (CMD_PINYIN[label] ?? '').startsWith(kw);
const SLASH_CMDS = computed(() => {
  const fn = [
    { icon: 'inbox', label: '/压缩', sub: '前面对话打包成摘要，腾出空间', text: '__COMPACT__' },
    { icon: 'sparkles', label: '/新会话', sub: '清空重开一段对话', text: '__NEW__' },
    { icon: 'package', label: '/模型', sub: '切换本次对话用的模型', text: '__MODEL__' },
    { icon: 'receipt', label: '/用量', sub: '额度·次数·缓存明细', text: '__USAGE__' },
    { icon: 'file', label: '/说明', sub: '打开使用说明', text: '__HELP__' }
  ];
  const ask = [
    { icon: 'clipboard', label: '/待办', sub: '今天要处理什么', text: '我今天有哪些待办？' },
    { icon: 'alarm', label: '/逾期', sub: '名下逾期情况', text: '我名下的任务有谁逾期了吗？' },
    { icon: 'map-pin', label: '/进度', sub: '我发布的任务进度', text: '我发布的任务提交进度怎么样？' },
    // BN-17 原型 PHINT/EXT_SUG 高频问句补 /看板 /退修
    { icon: 'chart', label: '/看板', sub: '今日整体概况', text: '今日看板概况' },
    { icon: 'undo', label: '/退修', sub: '退回重做怎么操作', text: '退回重做的任务该怎么操作？' }
  ];
  if (roleKey.value !== 'member') {
    ask.splice(3, 0, { icon: 'megaphone', label: '/催办', sub: '给未交的人提个醒', text: '怎么给还没提交的成员发催办？' });
  }
  // fn 组在前 ask 组在后——syncCmd 过滤保序，模板按 grp 变化插组头
  return [...fn.map((c) => ({ ...c, grp: 'fn' as const })), ...ask.map((c) => ({ ...c, grp: 'ask' as const }))];
});

const userStore = useUserStore();
const roleKey = computed(() => roleOf(userStore.roles, userStore.permissions));
const roleChips = computed(() => CHIPS_BY_ROLE[roleKey.value] ?? CHIPS_BY_ROLE.teacher);

const draft = ref('');
const focused = ref(false);
const taRef = ref<HTMLTextAreaElement>();
/** 待确认时输入框收起——先收焦点与弹层，防移动端键盘顶住停靠区、也防隐藏后仍能 Enter 发送 */
watch(
  () => props.blocked,
  (b) => {
    if (b) {
      taRef.value?.blur();
      addOpen.value = false;
    }
  }
);
/** 思考档位持久化（Q9 拍板）：按用户分键，刷新不回「平衡」；坏值/越界回默认 1 */
const THINK_LEVEL_KEY = 'dcs:assistant:thinkLevel:' + userStore.userId;
const thinkLevel = ref(
  (() => {
    try {
      const saved = Number(localStorage.getItem(THINK_LEVEL_KEY));
      return Number.isInteger(saved) && saved >= 0 && saved < THINK.length ? saved : 1;
    } catch {
      return 1;
    }
  })()
);
const addOpen = ref(false);
const dragover = ref(false);
/**
 * 三档自主权（批I-2，原型 autoPop/syncAutoBtn 词典原样）：状态源=props.state.autoLevel
 * 单一真源（此前本地 ref 与全局双源，勾选确认卡后面板不同步——AP-2 修复）。
 */
const AUTO_LV = [
  {
    key: 'always',
    icon: 'bell',
    title: '每次都问',
    mch: '都问',
    desc: '写操作前先经我确认',
    popT: '每次执行前都问我',
    popD: '默认。写操作每次出确认卡'
  },
  {
    key: 'once',
    icon: 'repeat',
    title: '同类只问一次',
    mch: '同类一次',
    desc: '同类第二次起直接执行',
    popT: '同类操作只问一次',
    popD: '本会话内第二次起直接执行'
  },
  {
    key: 'auto',
    icon: 'rocket',
    title: '全自动',
    mch: '全自动',
    desc: '不确认直接执行，事后通知可撤回',
    popT: '全自动 + 事后通知',
    popD: '不再弹确认，执行完通知可撤回'
  }
] as const;
const autoIdx = computed(() => AUTO_LV.findIndex((a) => a.key === props.state.autoLevel));
const autoCur = computed(() => AUTO_LV[autoIdx.value] ?? AUTO_LV[0]);
const autoPopOpen = ref(false);
let autoPopTimer: ReturnType<typeof setTimeout> | null = null;
/** 思考三档弹层（批I-3 BN-8，与自主权弹层同款 700ms 自动收口径） */
const thinkPopOpen = ref(false);
let thinkPopTimer: ReturnType<typeof setTimeout> | null = null;
const addpopRef = ref<HTMLDivElement>();

/** 回填输入框（BN-5 收藏/B 快捷问句共用：原型 quick()=回填+聚焦，不直接发） */
function fillDraft(t: string) {
  draft.value = t;
  nextTick(() => taRef.value?.focus());
}

function pickAuto(i: number) {
  emit('auto-level', AUTO_LV[i].key);
  pushToast('自主权已切换：' + AUTO_LV[i].popT, 'ok');
  // 原型口径：选中后 700ms 自动收（对勾切换可见后再合上）
  if (autoPopTimer) clearTimeout(autoPopTimer);
  autoPopTimer = setTimeout(() => {
    autoPopOpen.value = false;
    autoPopTimer = null;
  }, 700);
}

/** 余量环数据（status 实值；挂载+每轮答完刷新） */
const limit = ref(50);
const used = ref(0);
const remain = computed(() => Math.max(0, limit.value - used.value));
const usedPct = computed(() => (limit.value > 0 ? Math.min(1, used.value / limit.value) : 0));

/* ---- 批B B2 会话状态合并环（拍板 2026-10-11 Q1 定稿细则×4） ---- */
/** 上下文占用分子：本会话末次模型调用 tokens（status 带 threadId 返回；null=空态） */
const ctxTokens = ref<number | null>(null);
/** 分母：当前生效模型（用户 pin > 助手默认）的 contextWindow——自选关时 options 空，
 *  回退 model-choice.defaultContextWindow（step5 P1-1：默认租户环分母不悬空）；<=0 视 null */
const ctxWindow = computed(() => {
  const rowWin = effectiveRow.value?.contextWindow;
  if (rowWin && rowWin > 0) {
    return rowWin;
  }
  const defWin = modelChoice.value?.defaultContextWindow;
  return defWin && defWin > 0 ? defWin : null;
});
/** 空态守卫（Review Focus ②）：分子分母任一缺失/非正 → 环不画弧、卡内解释待测 */
const ctxKnown = computed(() => (ctxTokens.value ?? 0) > 0 && (ctxWindow.value ?? 0) > 0);
const ctxPct = computed(() => (ctxKnown.value ? Math.min(1, (ctxTokens.value as number) / (ctxWindow.value as number)) : 0));
/** 阈值变色（LibreChat 严格同档 >75 黄/>90 红；step5 P2-2：等号边界对齐不含） */
const ringColor = computed(() =>
  ctxPct.value > 0.9 ? 'var(--el-color-danger)' : ctxPct.value > 0.75 ? 'var(--el-color-warning)' : 'var(--el-color-primary)'
);
/** SVG 铁律：样式一律 :style CSS 对象（stroke 数值不被错误缩放）；空态弧透明只留底环 */
const ringFgStyle = computed(() => ({
  strokeDasharray: '56.5 56.5',
  strokeDashoffset: String(56.5 * (1 - ctxPct.value)),
  stroke: ctxKnown.value ? ringColor.value : 'transparent'
}));
const ctxBarStyle = computed(() => ({ width: ctxPct.value * 100 + '%', background: ringColor.value }));
/** 额度哨兵点（不抢环色）：剩 <20% 橙 / 用完红（横幅照旧由抽屉弹）；
 *  业务批7 Q1：订阅租户回合不限次（used/limit 恒 100 万无意义），哨兵点改看积分余量 */
const quotaBadge = computed(() => {
  if (billingMode.value === 'credits') {
    /* 业务批7 Q2 终版：环点跟随最紧张的池——三池全空才红，任一池 <20% 橙 */
    const tank = creditSummary.value?.tankBalance ?? 0;
    const cap = creditSummary.value?.weeklyCap ?? 0;
    const winCap = creditSummary.value?.windowCap ?? 0;
    if (cap + tank + winCap > 0 && creditWeeklyLeft.value + tank + creditWindowLeft.value <= 0) {
      return 'red';
    }
    // k3 P2-1：单池耗尽(worst=0)也要亮橙——用户正被该池拦住，环点不能沉默；边界与
    // 卡片 warn 的 <=20 对齐；summary 未就绪不出哨兵（全 0 假值，dsflash P2-3 同源）
    const worst = Math.min(creditWeeklyLeftPct.value, creditWindowLeftPct.value);
    if (creditSummary.value && worst <= 20) {
      return 'orange';
    }
    return null;
  }
  if (limit.value > 0 && used.value >= limit.value) {
    return 'red';
  }
  if (limit.value > 0 && remain.value / limit.value < 0.2) {
    return 'orange';
  }
  return null;
});
/** 业务批7 Q2：环钮 aria 精简分叉（积分制/次数制各一句话） */
const ringAria = computed(() => {
  const ctx = ctxKnown.value ? `上下文已用${Math.round(ctxPct.value * 100)}%` : '上下文待测';
  if (billingMode.value === 'credits') {
    return `会话状态：${ctx}，本周积分剩 ${fmtCredit(creditWeeklyLeft.value)}${quotaBadge.value === 'red' ? '（积分已用完）' : quotaBadge.value === 'orange' ? '（积分将尽）' : ''}`;
  }
  return `会话状态：${ctx}，今天还能聊 ${remain.value} 个来回${quotaBadge.value === 'red' ? '（今日额度已用完）' : quotaBadge.value === 'orange' ? '（今日额度将尽）' : ''}`;
});
/** 点击=悬停同款卡（细则④：触屏无 hover 也能看） */
const ringCardOpen = ref(false);
function toggleRingCard() {
  ringCardOpen.value = !ringCardOpen.value;
}
function fmtK(n?: number | null): string {
  if (!n || n <= 0) {
    return '—';
  }
  return n >= 1000 ? Math.round(n / 1000) + 'K' : String(n);
}
/** 空态文案双分支（step5 P2-1）：缺分母（模型窗口未知）≠ 缺分子（会话无记录），
 *  老会话（改造前历史行无 tokens）与未配置窗口各自归因，不再一律「没有 token 记录」误导 */
const ctxEmptyHint = computed(() =>
  (ctxWindow.value ?? 0) <= 0 ? '模型上下文窗口未知（待管理员在模型配置补全后可测量）' : '本会话还没有可测量记录（回答完成后即显示）'
);

/* ---- 业务批3-④ #17：联网搜索会话级开关（默认关）+联网配额展示 ---- */
const webOn = ref(false);
const webUsed = ref(0);
const webLimit = ref(20);

/* ---- 业务批7 Q1/Q7：计费模式（订阅=credits）；Q9 缓存命中（status 返回） ---- */
const billingMode = ref<'credits' | 'wallet'>('wallet');
/** Q9（2026-10-15 拍板A）：本会话缓存命中（运行时逐回合累计，换会话即重置）——
 *  状态卡显示口径只取本会话；今日口径（cacheHitRateToday）不再参与显示（三席审查 P1：
 *  会话无样本时回退今日值与「只算这根对话」文案矛盾，且换会话看着像没重置）。
 *  业务批(1015) Q7 复核：更名「上下文加速」+空态「—」——后端 AssistantCacheHitHolder.ratePct
 *  在首轮真实回传后即返回数值（无缓存=0%），仅 requests=0 时为 null，故「—」只出现在
 *  新会话答完第一轮之前，首轮即出真实值（用户测试需求） */
const sessionCacheHitRate = ref<number | null>(null);
const sessionCacheHitRequests = ref(0);
/** 业务反馈批1015b Q11：今日平均缓存命中率（后端 LiteLLM 口径，样本<3 为 null） */
const cacheHitRateToday = ref<number | null>(null);
const cacheHitText = computed(() => {
  // 主口径=今日平均；今日样本不足时退回本会话值（聊过才有的看，总好过恒 —）
  const v = cacheHitRateToday.value ?? sessionCacheHitRate.value;
  return v == null ? '—' : `${v}%`;
});
const webLeft = computed(() => Math.max(0, webLimit.value - webUsed.value));
const webPct = computed(() => (webLimit.value > 0 ? Math.min(1, webUsed.value / webLimit.value) : 0));
/** 租户把联网额度关到 0（m3 二轮 P1）：「未开放」≠「当日用完」——文案/红色状态条都不得承诺明早恢复 */
const webDisabled = computed(() => webLimit.value <= 0);
/** R1：用户级禁用闸（抽屉 banner='disabled' 传导）——输入只读+置灰，发送在 onSend 拦截 */
const disabled = computed(() => props.state.banner === 'disabled');
/** R1 联网钮三态 title（语义与批④面板行/状态弹窗一致） */
const wbtnTitle = computed(() =>
  webDisabled.value
    ? '联网搜索未开放（额度为 0）'
    : webOn.value
      ? `联网已开 · 今天还能搜 ${webLeft.value} 次 · 点击关闭`
      : `联网搜索 · 点击开启（每天 ${webLimit.value} 次免费，问了外部知识才消耗）`
);
/** R1 跨 tab 同步：localStorage 'storage' 事件只在其它 tab 修改时触发本 tab（浏览器语义，
 *  本 tab 写不回环）；最新写入胜出=登记不修口径，同一会话两个 tab 开关态实时一致 */
function onWebOnStorage(e: StorageEvent) {
  if (e.key === webOnKey(userStore.userId, props.state.threadId)) {
    webOn.value = e.newValue === '1';
  } else if (isSkillKey(e.key ?? '')) {
    // R1 技能跨 tab 同步：整键重读真源（另一 tab 勾选/取消即对齐）
    readSkillSync();
  }
}
/** 会话切换按 localStorage 重建开关态（webOnState 单一真源；新会话无键=默认关） */
function readWebOnSync() {
  webOn.value = readWebOn(userStore.userId, props.state.threadId, localStorage);
}
function toggleWeb(on: boolean) {
  webOn.value = on;
  writeWebOn(userStore.userId, props.state.threadId, on, localStorage);
  pushToast(
    on
      ? webDisabled.value
        ? '联网已开，但本租户未开放联网搜索（额度为 0），暂时搜不了外部资料'
        : `联网已开：外部知识问答会搜网页带来源（今天剩 ${webLeft.value} 次）`
      : '联网已关：继续问系统内的数据不受影响',
    'ok'
  );
}

/* ---- 五需求批 R1：技能会话级选择（➕面板技能组勾选；skillState 单一真源，runTurn 出口注入） ---- */
const skillId = ref<string | null>(null);
/** 发布人级技能可见闸——与后端 ToolIdentity.from 同口径（菜单权限含 edit/audit 或超管
 *  通配才算发布人级），不按 role key 猜：dev 库存在 test1/多租户 student 等 role key
 *  与实际权限不一致的账号，按 key 裁会与后端目录分叉（前端勾选、后端不注入=勾了白勾） */
const skillPublisherLevel = computed(() => {
  const p = userStore.permissions ?? [];
  return roleKey.value === 'admin' || p.includes('*:*:*') || p.includes('dcs:task:edit') || p.includes('dcs:audit:audit');
});
/** 目录裁剪：无发布人级权限只留全员技能（与后端 SkillAssets 目录同口径） */
const skillCatalogForRole = computed(() => (skillPublisherLevel.value ? SKILL_CATALOG : SKILL_CATALOG.filter((s) => s.minRole === 'member')));
const skillCur = computed(() => SKILL_CATALOG.find((s) => s.id === skillId.value) ?? null);
/** 技能瓦片配色：复用语义色（建=主蓝/板·读=绿/催=琥珀），不新增调色板 */
function skillTileCls(id: string): string {
  return { 'create-mission': 'todo', 'create-template': 'cast', 'data-reading': 'cast', 'urge-wording': 'urge' }[id] ?? 'todo';
}
function readSkillSync() {
  skillId.value = readSkillId(userStore.userId, props.state.threadId, localStorage);
}
function toggleSkill(id: string) {
  const next = skillId.value === id ? null : id;
  skillId.value = next;
  writeSkillId(userStore.userId, props.state.threadId, next, localStorage);
  const label = SKILL_CATALOG.find((s) => s.id === id)?.name ?? id;
  pushToast(next ? `已启用技能「${label}」：本会话每轮按它的指引执行，点输入条上的胶囊 × 可停用` : `已停用技能「${label}」`, 'ok');
}
function clearSkill() {
  if (skillId.value) {
    toggleSkill(skillId.value);
  }
}

/* 业务批7 Q2：积分并入会话状态卡（独立胶囊下线）——订阅租户状态卡首行切「本周积分」 */
const creditSummary = ref<CreditSummaryVO | null>(null);
const creditSub = computed(() => creditSummary.value?.subscribed === true);
const creditWeeklyLeft = computed(() => Math.max(0, (creditSummary.value?.weeklyCap ?? 0) - (creditSummary.value?.weeklyUsed ?? 0)));
const creditWindowLeft = computed(() => Math.max(0, (creditSummary.value?.windowCap ?? 0) - (creditSummary.value?.windowUsed ?? 0)));
/** 业务批7 Q2 终版（2026-10-14 拍板）：迷你卡剩余%与加油包显隐——5小时窗→周池→加油包（有余额才显示） */
const creditWeeklyLeftPct = computed(() => {
  const cap = creditSummary.value?.weeklyCap ?? 0;
  return cap > 0 ? Math.min(100, Math.round((creditWeeklyLeft.value / cap) * 100)) : 0;
});
const creditWindowLeftPct = computed(() => {
  const cap = creditSummary.value?.windowCap ?? 0;
  return cap > 0 ? Math.min(100, Math.round((creditWindowLeft.value / cap) * 100)) : 0;
});
const creditTankShow = computed(() => (creditSummary.value?.tankBalance ?? 0) > 0);
/** 明细入口直达「租户管理→积分套餐」流水页（学 WorkBuddy 的明细入口） */
const router = useRouter();
/** k3 P1-2：明细页菜单(menu 11940)只授租户管理员/超管——订阅租户普通成员没有该路由，
 * 无权限不渲染死入口（点了落 catch-all 404）；P2-6：role=button+tabindex 键盘可达 */
const creditLedgerPerm = checkPermi(['dcs:aicredit:list']);
function goCreditLedger() {
  void router.push('/tenant/ai-credit');
}
const fmtCredit = (n?: number | null) => Number(n || 0).toLocaleString('zh-CN');
// 扣费口径（三审 dspro P2-3）：徽标/池条/哨兵点一律按 billingMode（status 权威值=真在扣哪本账）。
// 旧 billingCredits（billingMode==='credits' || creditSub）在「已切 WALLET 但订阅仍在有效期内」
// 时仍亮"积分订阅"徽标+冻结池条——与积分套餐页 Q8 引导条「当前按钱包扣费」当场互相打脸。
// creditSub（订阅仍有效）保留给"订阅资格"类判断，不再当扣费事实用。
async function refreshCredit() {
  try {
    const res = (await getMyCreditSummary()) as unknown as { data?: { summary?: CreditSummaryVO } };
    const d = (unwrap<Record<string, unknown>>(res) || {}) as unknown as { summary?: CreditSummaryVO };
    creditSummary.value = d.summary ?? null;
  } catch {
    /* 未订阅/接口不可达：状态卡保持非积分分支 */
  }
}
/**
 * R5：积分落账补偿刷——后端 DEDUCT 在 RUN_FINISHED 后 ~2-4s 才提交（record 走流
 * doFinally+enricher 收尾链），环的 1.8s 补刷实测仍读旧值，积分行再加一档 5.5s
 * 兜底；句柄清理与 ringRefetchTimer 同族（登出后回调 401 幽灵框）。
 */
let creditRefetchTimer: ReturnType<typeof setTimeout> | null = null;
function scheduleCreditRefetch() {
  if (creditRefetchTimer) {
    clearTimeout(creditRefetchTimer);
  }
  creditRefetchTimer = setTimeout(() => {
    creditRefetchTimer = null;
    if (amActive() && creditSub.value) {
      void refreshCredit();
    }
  }, 5500);
}

async function refreshStatus() {
  try {
    // R5：积分快照与状态同轮拉取（未订阅静默隐藏 pill；失败不影响环）
    if (amActive()) void refreshCredit();
    const res = (await getAssistantStatus(props.state.threadId || undefined)) as unknown as {
      data?: {
        dailyRoundLimitPerUser?: number;
        turnsUsedToday?: number;
        remainingTurnsToday?: number;
        websearchUsedToday?: number;
        websearchLimit?: number;
        contextTokens?: number | null;
        quotaDisabled?: boolean;
        billingMode?: 'credits' | 'wallet';
        sessionCacheHitRate?: number | null;
        sessionCacheHitRequests?: number;
        cacheHitRateToday?: number | null;
      };
    };
    const d = unwrap<Record<string, unknown>>(res);
    // 业务批7 Q1：计费模式；Q9：缓存命中只显本会话（今日口径字段后端仍下发，仅排错参考不进 UI）
    billingMode.value = d?.billingMode === 'credits' ? 'credits' : 'wallet';
    sessionCacheHitRate.value = typeof d?.sessionCacheHitRate === 'number' ? d.sessionCacheHitRate : null;
    sessionCacheHitRequests.value = typeof d?.sessionCacheHitRequests === 'number' ? d.sessionCacheHitRequests : 0;
    // Q11：今日平均（样本<3 后端 null → 显示层退本会话值）
    cacheHitRateToday.value = typeof d?.cacheHitRateToday === 'number' ? d.cacheHitRateToday : null;
    if (d?.dailyRoundLimitPerUser) {
      limit.value = d.dailyRoundLimitPerUser;
    }
    if (typeof d?.turnsUsedToday === 'number') {
      used.value = d.turnsUsedToday;
    } else if (typeof d?.remainingTurnsToday === 'number') {
      used.value = Math.max(0, limit.value - d.remainingTurnsToday);
    }
    ctxTokens.value = typeof d?.contextTokens === 'number' ? d.contextTokens : null;
    // 业务批3-④：联网配额展示（限值缺失保守 20 平台默认；0=租户整体关停，如实显示不能当缺失回退 20——dspro P2-2）
    if (typeof d?.websearchLimit === 'number') {
      webLimit.value = d.websearchLimit;
    }
    webUsed.value = typeof d?.websearchUsedToday === 'number' ? d.websearchUsedToday : 0;
    // R1（回修 P1-2/dsflash P2）：禁用态在此统一置/清——refreshStatus 在抽屉/全屏/会话页
    // 三宿主的挂载、active 激活、发送收尾三时机都会跑，管理员恢复后 banner 能被真正清掉。
    // state 是 getAssistant() 池化的共享响应式单例（有意架构，抽屉/会话页同源），定点豁免
    // no-mutating-props：横幅语义本就属于会话单例而非组件私有
    if (d?.quotaDisabled) {
      // eslint-disable-next-line vue/no-mutating-props
      props.state.banner = 'disabled';
    } else if (props.state.banner === 'disabled') {
      // eslint-disable-next-line vue/no-mutating-props
      props.state.banner = '';
    }
  } catch {
    /* 状态不可达：环保持上次值 */
  }
}

/* ===== 批17 Q2：手动压缩（/压缩 命令与会话状态卡「打包前文」按钮共用）。
 * 四态人话=open-webui 同款（生成中先等/太短不用压/压缩中/完成）；确认一步防误触=cline 同款。
 * 压缩在服务端做（agentscope CompactionMiddleware 同一套逻辑），完成后环回落、
 * 宿主经 compacted 事件重拉历史，「已压缩」分隔条出现。 ===== */
const compacting = ref(false);
async function onCompactContext() {
  if (compacting.value) {
    return;
  }
  if (props.sending) {
    pushToast('等当前回答结束再压缩');
    return;
  }
  try {
    await ElMessageBox.confirm(
      '把前面的对话打包成一条摘要，上下文占用会明显回落。关键结论、涉及的任务、你给过的反馈都会保留在摘要里。',
      '压缩上下文？',
      { type: 'info', confirmButtonText: '压缩', cancelButtonText: '先不' }
    );
  } catch {
    return; // 用户取消
  }
  compacting.value = true;
  pushToast('正在压缩前文…');
  try {
    const res = (await compactAssistantContext(props.state.threadId || undefined)) as unknown as {
      data?: { compacted?: boolean; reason?: string; beforeTokens?: number | null; afterTokens?: number | null };
    };
    const d = unwrap<Record<string, unknown>>(res) as
      | { compacted?: boolean; reason?: string; beforeTokens?: number | null; afterTokens?: number | null }
      | undefined;
    if (d?.compacted) {
      const saved =
        typeof d.beforeTokens === 'number' && typeof d.afterTokens === 'number' && d.beforeTokens > 0
          ? `，占用 ${fmtK(d.beforeTokens)} → ${fmtK(d.afterTokens)}`
          : '';
      pushToast(`已压缩：前文打包成摘要${saved}`);
      void refreshStatus();
      emit('compacted');
    } else {
      pushToast(d?.reason === 'too_short' ? '对话还太短，不需要压缩' : '当前没有可压缩的内容');
    }
  } catch {
    pushToast('压缩没成功，稍后再试（已有对话不受影响）');
  } finally {
    compacting.value = false;
  }
}
onMounted(() => {
  readWebOnSync();
  readSkillSync();
  if (amActive()) void refreshStatus();
  window.addEventListener('storage', onWebOnStorage);
});
onBeforeUnmount(() => {
  window.removeEventListener('storage', onWebOnStorage);
});
/** step5 P1-2：1.8s 补刷定时器须存句柄+卸载清理——不清理时登出后回调仍发请求，
 *  命中 request.ts 401 全局处理弹「登录状态已过期」幽灵确认框 */
let ringRefetchTimer: ReturnType<typeof setTimeout> | null = null;
watch(
  () => props.state.sending,
  (v, old) => {
    if (old && !v && amActive()) {
      refreshStatus();
      // 业务批7 Q10：答完随状态一起重拉模型选择——管理端改默认模型后，输入条显示不再
      // 要整页强刷才更新（modelChoice 原本只在挂载/抽屉重开时拉取）
      void refreshChoice();
      // R5：积分胶囊的独立补偿档（环 1.8s + 积分 5.5s，见 scheduleCreditRefetch 注释）
      scheduleCreditRefetch();
      // 批B B2：答完即刷+1.8s 补刷——消息落库（含 total_tokens）在 RUN_FINISHED 后异步，
      // 首刷可能读到旧值（批5 追问侧车同款时序坑）；重触发先清旧定时器防叠加
      if (ringRefetchTimer) {
        clearTimeout(ringRefetchTimer);
      }
      ringRefetchTimer = setTimeout(() => {
        ringRefetchTimer = null;
        if (amActive()) {
          void refreshStatus();
        }
      }, 1800);
    }
  }
);
// 批B B2：切会话即按新 threadId 重拉上下文占用（环分子换会话）；
// step5 P2-4：进入即清旧值——响应回来前的 1 个 RTT 窗口不再闪现旧会话读数，
// 失败时旧值也不会永久挂在新会话上
watch(
  () => props.state.threadId,
  () => {
    ctxTokens.value = null;
    readWebOnSync();
    readSkillSync();
    // 业务批7 Q10：切会话/新会话重拉模型选择——新开对话显示的默认模型不再停留旧快照
    void refreshChoice();
    if (amActive()) {
      void refreshStatus();
    }
  }
);
// H9：隐身抽屉转可见（打开/转全屏）补拉一次余量+附件，打开即见最新态；
// R1（回修 dspro P2-3）：storage 事件不跨同文档双宿主（抽屉/会话页各持一个本组件），
// 激活即重读 webOn 真源对齐，防「UI 显关、实际已开消耗额度」
watch(
  () => props.active,
  (v) => {
    if (v !== false) {
      readWebOnSync();
      readSkillSync();
      void refreshStatus();
      void refreshAttachments();
    }
  }
);

/** 占位轮换（5s；有字/生成中停） */
const phIdx = ref(Math.floor(Math.random() * 3));
let phTimer: ReturnType<typeof setInterval> | null = null;
onMounted(() => {
  phTimer = setInterval(() => {
    if (draft.value === '' && !props.sending) {
      phIdx.value += 1;
    }
  }, 5000);
});
onBeforeUnmount(() => {
  stopAttachPolling();
  if (phTimer) {
    clearInterval(phTimer);
  }
  if (autoPopTimer) {
    clearTimeout(autoPopTimer);
  }
  if (thinkPopTimer) {
    clearTimeout(thinkPopTimer);
  }
  // 批4回修（glm53 P2-3）：零命中回查 timer 同族清理——不清则卸载后 400ms 内仍发一次
  // mentionTasks，token 已失效时命中 request.ts 401 弹「登录状态已过期」幽灵框（step5 P1-2 同族）
  cancelKwFallback();
  if (ringRefetchTimer) {
    clearTimeout(ringRefetchTimer);
  }
  if (creditRefetchTimer) {
    clearTimeout(creditRefetchTimer);
  }
  document.removeEventListener('mousedown', onDocMdown);
});

/** ➕面板/自主权弹层/思考弹层/模型弹层点外关闭（原型 addPopToggle/autoPop/thinkPop 的 document mousedown 口径） */
function onDocMdown(e: MouseEvent) {
  const t = e.target as HTMLElement;
  const host = addpopRef.value;
  // 点在面板外且不在 ➕/🧠 钮上（钮自身的 click 会 toggle，先关再开会闪）→ 全收
  if ((addOpen.value || autoPopOpen.value) && !host?.contains(t) && !t.closest('.bicon')) {
    addOpen.value = false;
    autoPopOpen.value = false;
  }
  if (thinkPopOpen.value && !t.closest('.bpill')) {
    thinkPopOpen.value = false;
  }
  if (modelPopOpen.value && !t.closest('.bpill')) {
    modelPopOpen.value = false;
  }
  // step5 P2-5：环卡同口径点外收（钮自身 click 会 toggle，只拦外部点击）
  if (ringCardOpen.value && !t.closest('.ring-btn')) {
    ringCardOpen.value = false;
  }
}
onMounted(() => {
  document.addEventListener('mousedown', onDocMdown);
});

/**
 * 宿主列宽档位（2026-10-11 用户拍板：宽屏空白优化，答案区+聊天框同步加宽）。
 * 单一真源：本组件常驻所有宿主（会话页/抽屉全屏/抽屉），根的父层即 StatPanel
 * 认定的同一宿主——在此观测宿主宽并写 `--dcs-col-max`，五处 max-width 消费者
 * （本组件 .box+四条卡片行、MsgFlow 内容块）全部读变量，同步是结构保证。
 * · 单档：宿主 ≥1380 才把帽从 900 提到 1080（1380=24+1080+14+248+14，面板展开态
 *   最坏 248 也放得下不叠列，预览注入实测）；中屏/抽屉窄宿主完全不动。
 * · 抽屉（非 full）样式不引用本变量，写了也无消费者，天然豁免。
 * · 抽屉↔全屏是不同 v-if 子树=重挂载，onMounted 重新接新宿主，无观测残影。
 */
const rootEl = ref<HTMLElement | null>(null);
const COL_STEP_HOST_W = 1380;
let colHostObserver: ResizeObserver | null = null;
let colHostEl: HTMLElement | null = null;
onMounted(() => {
  const host = rootEl.value?.parentElement ?? null;
  if (!host || typeof ResizeObserver === 'undefined') {
    return;
  }
  colHostEl = host;
  colHostObserver = new ResizeObserver((entries) => {
    // borderBox 口径（同 StatPanel：宿主 padding 变化不算宽变，防让位反馈环）
    const b = entries[0]?.borderBoxWidth ?? entries[0]?.borderBoxSize;
    const single = Array.isArray(b) ? b[0] : b;
    const w = single?.inlineSize ?? entries[0]?.contentRect.width ?? 0;
    if (w >= COL_STEP_HOST_W) {
      host.style.setProperty('--dcs-col-max', '1080px');
    } else {
      host.style.removeProperty('--dcs-col-max');
    }
  });
  colHostObserver.observe(host, { box: 'border-box' });
});
onBeforeUnmount(() => {
  colHostObserver?.disconnect();
  colHostEl?.style.removeProperty('--dcs-col-max');
  colHostEl = null;
});

/** BN-1 原型口径反转：欢迎卡在场时隐藏，开聊后显示（原实现空会话显示=反了） */
const chipsVisible = computed(() => props.state.turns.length > 0 || props.state.queue.length > 0);
const showChips = computed(() => chipsVisible.value);
const canSend = computed(() => !props.blocked && draft.value.trim().length > 0);
const phPool = computed(() => PHINT[roleKey.value] ?? PHINT.teacher);
const placeholder = computed(() => {
  if (props.sending) {
    return '想接着问？先存这儿，不会丢…';
  }
  return phPool.value[phIdx.value % phPool.value.length];
});
// 原型 syncThinkBtn：title=思考程度：{档名}（换 THINK 副句后保持按钮 title 口径不变）
const thinkTitle = computed(() => `思考程度：${THINK[thinkLevel.value].label}`);
const sendTitle = computed(() => (props.sending ? '排队——答完自动发出' : canSend.value ? '发送（Enter）' : '先输入内容'));

function pickChip(p: string) {
  draft.value = p;
  taRef.value?.focus();
}
function quickSend(q: string) {
  addOpen.value = false;
  emit('send', q, '', fwdProps());
}
function startAt() {
  addOpen.value = false;
  draft.value = '@';
  nextTick(() => {
    taRef.value?.focus();
    syncCmd();
  });
}

/** 请求级标记（forwardedProps 通道：思考档位/模型自选等——后端读取链路批B-后端子批接线） */
function fwdProps(): Record<string, unknown> {
  const fwd: Record<string, unknown> = { thinkTier: THINK[thinkLevel.value].tier };
  // 批7 Q12-④：本会话 pin 了模型就逐请求上行（后端 resolver 链第一优先级；开关关/行失效自动回退）
  if (choiceOn.value && modelPin.value) {
    fwd.modelConfigId = modelPin.value;
  }
  return fwd;
}

function onInput() {
  autoGrow();
  syncCmd();
}
function autoGrow() {
  const ta = taRef.value;
  if (ta) {
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 160) + 'px';
  }
}
function pickThink(i: number) {
  thinkLevel.value = i;
  try {
    localStorage.setItem(THINK_LEVEL_KEY, String(i));
  } catch {
    /* 存储不可用忽略 */
  }
  pushToast('思考程度已切换：' + THINK[i].label, 'ok');
  // 弹层互斥（批7）：选思考档时收模型弹层
  modelPopOpen.value = false;
  if (thinkPopTimer) clearTimeout(thinkPopTimer);
  thinkPopTimer = setTimeout(() => {
    thinkPopOpen.value = false;
    thinkPopTimer = null;
  }, 700);
}

/* ---- 批7 Q5 消费端+Q12-④：用户侧模型自选（按会话记住）+思考档按能力显隐 ---- */
const modelChoice = ref<AssistantModelChoice | null>(null);
const modelPopOpen = ref(false);
const choiceOn = computed(() => modelChoice.value?.allowUserSwitch === '1' && (modelChoice.value?.options?.length ?? 0) > 0);
const modelOptions = computed(() => modelChoice.value?.options ?? []);
/**
 * 批B 三审 P1：模型选项按厂商 find-or-create 累积分组（同 admin 页算法）。
 * 原相邻比较法要求数据源按 provider 排序，options 实际按 is_default/更新时间排——
 * 厂商交错时会重复组头；组头模板里已移出按钮独立成行，点组名不再误选组首模型。
 */
const groupedModelOptions = computed(() => {
  const groups: Array<{ provider: string; label: string; options: Array<(typeof modelOptions.value)[number]> }> = [];
  for (const o of modelOptions.value) {
    let g = groups.find((x) => x.provider === String(o.provider));
    if (!g) {
      g = { provider: String(o.provider), label: o.providerName || String(o.provider), options: [] };
      groups.push(g);
    }
    g.options.push(o);
  }
  return groups;
});
/** 会话级 pin（Q12-④）：localStorage 按 userId+threadId 分键，各会话互不干扰 */
const modelPin = ref<string | null>(null);
function modelPinKey(tid: string): string {
  return 'dcs:assistant:modelPin:' + userStore.userId + ':' + tid;
}
function readPin() {
  const tid = props.state.threadId;
  if (!tid) {
    modelPin.value = null;
    return;
  }
  try {
    modelPin.value = localStorage.getItem(modelPinKey(tid));
  } catch {
    modelPin.value = null;
  }
  // pin 自愈：选中行已不在可用清单（停用/下架）→ 清除回跟随默认。
  // options 未加载（挂载早于 choice 请求返回）时跳过——refreshChoice 拉到清单后会再校验，
  // 防误删合法 pin（E2E 实证：threadId watch 的 readPin 先跑、options 仍空 → 合法 pin 被清）
  if (modelPin.value && modelOptions.value.length > 0 && !modelOptions.value.some((o) => String(o.configId) === modelPin.value)) {
    try {
      localStorage.removeItem(modelPinKey(tid));
    } catch {
      /* 忽略 */
    }
    modelPin.value = null;
  }
}
function pickModel(cid: string | null) {
  modelPin.value = cid;
  try {
    const tid = props.state.threadId;
    if (tid) {
      if (cid) {
        localStorage.setItem(modelPinKey(tid), cid);
      } else {
        localStorage.removeItem(modelPinKey(tid));
      }
    }
  } catch {
    /* 忽略 */
  }
  const hit = cid ? modelOptions.value.find((o) => String(o.configId) === cid) : null;
  pushToast(hit ? `本会话已切换：${hit.modelName}` : '本会话已恢复跟随默认模型', 'ok');
  modelPopOpen.value = false;
  thinkPopOpen.value = false;
  void refreshCapability();
}
function toggleModelPop() {
  modelPopOpen.value = !modelPopOpen.value;
  if (modelPopOpen.value) {
    thinkPopOpen.value = false;
  }
}
/** 当前生效模型行（思考能力判定用）：用户 pin > 助手默认 > ★接口默认（options[0]）。
 *  业务批5 Q3-A ③：末段 opts[0] 回退只是能力判定/环分母的近似兜底，显示层见 defaultMissing——
 *  默认行被 CNY 闸滤掉时 pill 不再拿首行顶包冒充默认（"显示 ds 实际调 free"两本账现场） */
const effectiveRow = computed(() => {
  const opts = modelOptions.value;
  if (!opts.length) {
    return null;
  }
  if (modelPin.value) {
    const hit = opts.find((o) => String(o.configId) === modelPin.value);
    if (hit) {
      return hit;
    }
  }
  const defId = modelChoice.value?.assistantDefaultConfigId;
  return opts.find((o) => String(o.configId) === String(defId ?? '')) ?? opts[0];
});
/** 业务批5 Q3-A ③：默认行诚实化——管理员默认指向的行真的不可用（没配人民币价格被 CNY 闸
 *  滤掉/被停用）时明示。回修（dspro P1-1）：判定改读后端下发的 defaultSelectable（按未过滤
 *  usable 判真伪）——管理员把默认行裁出自选白名单时 options 不含它但调用链直通正常，
 *  凭 options 判会常驻假告警；旧后端无该字段=不告警（向后兼容）。有 pin 不判 */
const defaultMissing = computed(() => {
  if (!modelOptions.value.length || modelPin.value) {
    return false;
  }
  const defId = modelChoice.value?.assistantDefaultConfigId;
  return !!defId && modelChoice.value?.defaultSelectable === false;
});
/** 思考档按能力显隐：生效模型 tierKind=NONE → 隐藏思考档钮；拿不到能力（未加载/失败）保显示不丢功能 */
const thinkSupported = ref(true);
async function refreshCapability() {
  const row = effectiveRow.value;
  if (!row) {
    thinkSupported.value = true;
    return;
  }
  try {
    const res: any = await matchAiCapability({ provider: row.provider, model: row.modelName });
    const d = res?.data ?? res;
    thinkSupported.value = d?.tierKind !== 'NONE';
  } catch {
    thinkSupported.value = true;
  }
}
async function refreshChoice() {
  if (!amActive()) {
    return;
  }
  try {
    const d = unwrap<AssistantModelChoice>((await getAssistantModelChoice()) as any);
    modelChoice.value = {
      allowUserSwitch: d?.allowUserSwitch ?? '0',
      assistantDefaultConfigId: d?.assistantDefaultConfigId ?? null,
      // step5 P1-1：默认模型窗口一并存（后端 -1 哨兵折回 null）
      defaultContextWindow: typeof d?.defaultContextWindow === 'number' && d.defaultContextWindow > 0 ? d.defaultContextWindow : undefined,
      options: Array.isArray(d?.options) ? d.options : []
    };
    // P2 修复（2026-10-09 Q7）：管理员关掉「允许自选」→ 本会话 pin 即刻清除——
    // 否则 runTurn 出口仍按残留 pin 上行 modelConfigId（后端双闸会拒，但 pill/上行不诚实）
    if (modelChoice.value.allowUserSwitch !== '1') {
      const tid = props.state.threadId;
      if (tid) {
        try {
          localStorage.removeItem(modelPinKey(tid));
        } catch {
          /* 忽略 */
        }
      }
      modelPin.value = null;
    }
    readPin();
    void refreshCapability();
  } catch {
    /* 拉不到=不显示选择器，维持现状（思考档保显示） */
  }
}
watch(
  () => props.state.threadId,
  () => {
    readPin();
    // 批8-3 回修（k3 P1-4）：换会话 pin 变了 → effectiveRow 变 → 思考档按新模型能力显隐须重判
    // （旧漏：从 pin 思考盲区模型的会话切回默认会话，思考档按钮留在旧状态）
    void refreshCapability();
  },
  { immediate: true }
);
onMounted(() => {
  void refreshChoice();
});
// H9：隐身抽屉转可见补拉（对齐余量环模式）
watch(
  () => props.active,
  (v) => {
    if (v !== false) {
      void refreshChoice();
    }
  }
);
const modelBtnLabel = computed(() => {
  const hit = modelPin.value ? modelOptions.value.find((o) => String(o.configId) === modelPin.value) : null;
  // 批8-3 回修（k3 P2-5）：跟随默认时实指默认模型名（前缀「默认·」区分自选态），
  // 拿不到行名（options 未回）退「默认模型」
  // 业务批5 Q3-A ③：默认行被滤（无价/停用）→ 明示暂不可用，不再顶包首行（诚实显示）
  if (hit) {
    return hit.modelName;
  }
  if (defaultMissing.value) {
    return '默认模型不可用';
  }
  return effectiveRow.value ? `默认·${effectiveRow.value.modelName}` : '默认模型';
});
const defaultModelLabel = computed(() => effectiveRow.value?.modelName ?? '管理员设置的默认模型');
/** 弹层行单价副句（FIXED=元/次；TOKEN=输入单价/百万；未配置=空串） */
function modelPriceLine(o: {
  billingMode?: string;
  unitPricePerCall?: number | null;
  inputPricePerMillion?: number | null;
  currency?: string;
}): string {
  // 单价去尾零（¥2.000000→¥2；¥0.5→¥0.5）——E2E 实证原样透 6 位小数太机器人；
  // 符号跟币种（选项已过 CNY 闸恒 ¥，此处按数据渲染防将来口径再漂移）
  const sym = o.currency === 'USD' ? '$' : '¥';
  const fmt = (v: number) => Number(v).toString();
  // 业务批4 Q4-A：官方价＋服务费=双段（官方按量价＋每次服务费）；单位统一「/百万」
  // （m3 P1-2 回修：原「/百万字」与 ai-config/admin 两处「/百万」漂移，用户疑数据不一致）
  if (o.billingMode === 'OFFICIAL_PLUS_FEE') {
    const fee = o.unitPricePerCall != null ? `${sym}${fmt(o.unitPricePerCall)}/次服务费` : '';
    const base = o.inputPricePerMillion != null ? `${sym}${fmt(o.inputPricePerMillion)}/百万` : '';
    if (base && fee) return `${base}＋${fee}`;
    return fee || base;
  }
  if (o.billingMode === 'FIXED_PER_CALL' && o.unitPricePerCall != null) {
    return `${sym}${fmt(o.unitPricePerCall)}/次`;
  }
  if (o.inputPricePerMillion != null) {
    return `${sym}${fmt(o.inputPricePerMillion)}/百万字起`;
  }
  return '';
}
function onSend() {
  const q = draft.value.trim();
  if (!q) {
    // BN-9 原型口径：空点发送键=续发暂停的队列（键永不 disabled）
    if (props.state.queuePaused && props.state.queue.length > 0) {
      emit('queue-resume');
    }
    return;
  }
  // 档位 stamp（原型 aiSay 同款：⚡快速/🧠深入；平衡不带章）
  const stampMap: Record<string, string> = { fast: '⚡ 快速', deep: '🧠 深入' };
  emit('send', q, stampMap[THINK[thinkLevel.value].tier] ?? '', fwdProps());
  // H6：归档会话宿主会拦下发并提示——草稿必须保留（旧代码无条件清空=白打的整段字直接没了）
  if (!props.state.curConvArchived) {
    draft.value = '';
  }
  closeCmd();
  nextTick(autoGrow);
}

/** / 与 @ 命令菜单（cmdPop） */
const cmd = reactive({
  open: false,
  mode: 'slash' as 'slash' | 'at',
  items: [] as Array<{ icon: string; label: string; sub: string; text: string }>,
  active: 0
});
/** @ 引用任务数据（业务批4 Q1：切 mention-tasks 专用端点——七路可见+relation 标注，
 *  治「成员 @ 一辈子空」；60s TTL 缓存防回环（#24 教训），服务端已限 100 行（业务批4 Q2-A） */
let taskPool: Array<{ icon: string; label: string; sub: string; text: string }> = [];
let taskLoading = false;
let taskFetchedAt = 0;
const TASK_POOL_TTL_MS = 60_000;
/** 业务批4 Q2-A：已带词回查过的关键词（防同词重复回查风暴；主池 TTL 刷新时清空，新任务可再查到） */
const kwFetched = new Set<string>();
let kwFetchTimer: ReturnType<typeof setTimeout> | null = null;
async function loadTasks(): Promise<boolean> {
  // 拉取在途或 60s 缓存未过期直接返回。返回值=本次是否真发起过请求——dsflash P0 回修：
  // 池空时 syncCmd 的 .then 重同步只允许在"真拉过一轮"后发生，否则 TTL 命中的立即返回
  // 会让 syncCmd↔loadTasks 互递归成纯微任务自旋（无渲染无输入让路，标签页卡死到 TTL 过期）。
  // step5 FE-1 回修：去掉 taskPool.length>0 前置短路——它让 TTL 在池非空后永不生效，
  // @ 任务池整个组件生命周期不刷新（新收任务要刷新页面才 @ 得到）
  if (taskLoading || Date.now() - taskFetchedAt < TASK_POOL_TTL_MS) {
    return false;
  }
  taskLoading = true;
  taskFetchedAt = Date.now();
  try {
    // 业务批4 Q11-A：端点契约恒 R.data 数组形态（Controller 直返 List），去掉分页 rows 死分支
    const res = (await mentionTasks()) as unknown as { data?: unknown };
    const raw = Array.isArray(res?.data) ? res.data : [];
    const rows = raw as Array<{ missionId: string | number; missionName: string; relation?: string }>;
    taskPool = rows.slice(0, 100).map((m) => {
      const full = String(m.missionName ?? '');
      return {
        icon: 'clipboard',
        // 过滤按全名，展示截断（截断 label 会漏匹配后缀关键词——E2E 实证 @v14 查空）
        label: full,
        sub: m.relation || '',
        text: '@' + full + ' '
      };
    });
    kwFetched.clear();
  } catch {
    taskPool = [];
  } finally {
    taskLoading = false;
  }
  return true;
}
/** 业务批4 Q2-A（2026-10-13 拍板）：本地零命中回查——池有数据但过滤为空且已输入≥2字时，
 *  400ms 防抖带词查服务端补候选（@ 服务端排序 100 名之外的冷门任务的兜底）。
 *  防死循环三闸：同词只查一次（失败也记账）/回查前校验关键词未变/主池在拉时放弃本轮 */
/** 批4回修（glm53 P2-2）：撤销挂起的零命中回查——有命中/菜单关闭/组件卸载时调用。
 *  原清 timer 逻辑藏在 scheduleKwFallback 头部，有命中路径根本不进来=旧词挂起的回查照发 */
function cancelKwFallback() {
  if (kwFetchTimer) {
    clearTimeout(kwFetchTimer);
    kwFetchTimer = null;
  }
}
function scheduleKwFallback(kw: string) {
  cancelKwFallback();
  if (kw.length < 2 || kwFetched.has(kw)) {
    return;
  }
  kwFetchTimer = setTimeout(async () => {
    kwFetchTimer = null;
    // 批4回修（glm53 P2-1）：进回调即记账——原放 finally，回查结果全重复时 syncCmd 重入
    // 会再排一个同词 timer（尚未记账拦不住）=同词白查两次；提前后失败路径同样覆盖
    kwFetched.add(kw);
    if (taskLoading) {
      return; // 主池刷新在途，本轮放弃（TTL 刷新成功会清 kwFetched，下个键击自然重试）
    }
    try {
      const res = (await mentionTasks(kw)) as unknown as { data?: unknown };
      const rows = (Array.isArray(res?.data) ? res.data : []) as Array<{
        missionId: string | number;
        missionName: string;
        relation?: string;
      }>;
      if (rows.length > 0) {
        const have = new Set(taskPool.map((t) => t.label));
        for (const m of rows) {
          const full = String(m.missionName ?? '');
          if (!full || have.has(full)) {
            continue;
          }
          have.add(full);
          taskPool.push({ icon: 'clipboard', label: full, sub: m.relation || '', text: '@' + full + ' ' });
        }
        if (draft.value.startsWith('@') && draft.value.slice(1) === kw) {
          syncCmd();
        }
      }
    } catch {
      /* 回查失败静默——用户按词搜不到时行为与旧版一致 */
    }
  }, 400);
}
function syncCmd() {
  const v = draft.value;
  if (v.startsWith('/')) {
    const kw = v.slice(1);
    cmd.mode = 'slash';
    // 批17 Q3：前缀/包含/拼音首字母三路匹配（保序过滤，fn→ask 分组不乱）
    cmd.items = SLASH_CMDS.value.filter((c) => matchCmd(c.label, kw));
    cmd.active = 0;
    cmd.open = cmd.items.length > 0;
  } else if (v.startsWith('@')) {
    const kw = v.slice(1);
    cmd.mode = 'at';
    cmd.items = taskPool.filter((t) => t.label.includes(kw));
    cmd.active = 0;
    // 业务批4 Q5 根治「空菜单死等」：池空（拉过仍无数据）不弹空弹层——@ 退化为普通
    // 文本；池有数据按过滤结果开合（无匹配也收起）。
    // dsflash P0 回修：仅"本轮真发起过请求"才重同步一次（拉完仍空 → 下轮 loadTasks
    // 返 false 终止；TTL 命中直接返 false 不再进 .then），杜绝微任务自旋。
    // step5 FE-1：TTL 过期也触发刷新（池非空但超 60s 时更新任务池，新任务可 @ 到）
    cmd.open = taskPool.length > 0 && cmd.items.length > 0;
    // 业务批4 Q2-A：本地池零命中且≥2字 → 防抖带词回查服务端；有命中/未到2字时撤销挂起的
    // 回查（批4回修 glm53 P2-2：撤销须无条件调 cancel——原靠 scheduleKwFallback 头部清 timer，
    // 有命中路径不进来，旧词挂起的回查照发一次白请求）
    cancelKwFallback();
    if (cmd.items.length === 0 && taskPool.length > 0) {
      scheduleKwFallback(kw);
    }
    const poolStale = taskPool.length === 0 || Date.now() - taskFetchedAt >= TASK_POOL_TTL_MS;
    if (poolStale) {
      void loadTasks().then((fetched) => {
        if (fetched && draft.value.startsWith('@')) {
          syncCmd();
        }
      });
    }
  } else {
    closeCmd();
  }
}
function closeCmd() {
  cmd.open = false;
  cmd.items = [];
  cmd.active = 0;
  // 批4回修（glm53 P2-3/dspro P2-1）：@ 菜单关闭即撤销挂起的零命中回查（400ms 内关菜单/
  // 挑中候选后不再发白请求；卸载清理在 onBeforeUnmount 同款）
  cancelKwFallback();
}
function cmdPick(i?: number) {
  const idx = i ?? cmd.active;
  const it = cmd.items[idx];
  if (!it) {
    return;
  }
  if (it.text === '__HELP__') {
    draft.value = '';
    closeCmd();
    emit('open-help');
    return;
  }
  if (it.text === '__NEW__') {
    draft.value = '';
    closeCmd();
    emit('new-session');
    return;
  }
  // 批17 Q3 功能命令（动作型，不回填输入框）
  if (it.text === '__COMPACT__') {
    draft.value = '';
    closeCmd();
    void onCompactContext();
    return;
  }
  if (it.text === '__MODEL__') {
    draft.value = '';
    closeCmd();
    if (!choiceOn.value) {
      pushToast('管理员未开放模型自选，当前用助手默认模型');
      return;
    }
    thinkPopOpen.value = false;
    modelPopOpen.value = true;
    return;
  }
  if (it.text === '__USAGE__') {
    draft.value = '';
    closeCmd();
    ringCardOpen.value = true;
    return;
  }
  draft.value = it.text;
  closeCmd();
  nextTick(() => {
    taRef.value?.focus();
    autoGrow();
  });
}

function onKey(e: KeyboardEvent) {
  if (cmd.open && ['ArrowDown', 'ArrowUp', 'Escape'].includes(e.key)) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      cmd.active = (cmd.active + 1) % Math.max(1, cmd.items.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      cmd.active = (cmd.active - 1 + Math.max(1, cmd.items.length)) % Math.max(1, cmd.items.length);
    } else {
      closeCmd();
    }
    return;
  }
  if (e.key === 'Enter' && cmd.open) {
    e.preventDefault();
    cmdPick();
    return;
  }
  // 批17 Q1：Tab 补全——slash 模式把选中命令名补进输入框（不执行，Enter 才确认）
  if (e.key === 'Tab' && cmd.open && cmd.mode === 'slash') {
    e.preventDefault();
    const it = cmd.items[cmd.active];
    if (it) {
      draft.value = it.label;
      nextTick(() => {
        syncCmd();
        autoGrow();
      });
    }
    return;
  }
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    if (canSend.value || props.sending) {
      onSend();
    }
  } else if (e.key === 'ArrowUp' && draft.value === '' && !e.isComposing) {
    // 召回最近一问（原型口径）
    const turns = props.state.turns;
    if (turns.length > 0) {
      draft.value = turns[turns.length - 1].question;
      nextTick(autoGrow);
    }
  }
}
function onDrop(e: DragEvent) {
  dragover.value = false;
  // ATTACH：拖入文件=上传（白名单外文件仍走提示）
  const files = Array.from(e.dataTransfer?.files ?? []);
  if (files.length) {
    files.forEach(uploadOne);
  }
}

function setDraft(text: string) {
  draft.value = text;
  nextTick(autoGrow);
}
function clearDraft() {
  draft.value = '';
  nextTick(autoGrow);
}
/** AU-29 Esc 链：宿主（抽屉/会话页）全局 Esc 先问这里——五个弹层任一开着就收它并吞掉事件
 *  （优先级高于「停止生成/收面板」），返回 false=没有弹层，宿主继续自己的 Esc 分层 */
function escClosePop(): boolean {
  if (addOpen.value || autoPopOpen.value) {
    addOpen.value = false;
    autoPopOpen.value = false;
    return true;
  }
  if (modelPopOpen.value) {
    modelPopOpen.value = false;
    return true;
  }
  if (thinkPopOpen.value) {
    thinkPopOpen.value = false;
    return true;
  }
  // 五审回修（step5 P1-4）：上下文环卡进 Esc 链
  if (ringCardOpen.value) {
    ringCardOpen.value = false;
    return true;
  }
  return false;
}

defineExpose({
  draftText: computed(() => draft.value),
  setDraft,
  clearDraft,
  focus: () => taRef.value?.focus(),
  escClosePop
});
</script>

<style lang="scss" scoped>
.dcs-comp {
  flex: none;
  /* 待确认期间收起（保持挂载，draft/附件/弹层状态不丢） */
  &.blocked {
    display: none;
  }
  /* 原型 .dw-input：transparent 无边框，padding 8px 14px 12px（批J-2 对齐） */
  padding: 8px 14px 12px;
  background: transparent;
  /* Q8（拍板 2026-10-10）：全屏/会话页与消息列共用同一条宽度公式（原型 #fullComposer 与
     #msgFlow 同为 max-width:900 + 24 横向 padding）——输入卡与答案卡完全同宽同位、
     一起被父层廊位推让；抽屉窄形态（384px）不受 900 帽影响，保持原口径。
     三反馈①（2026-10-10）：消息画布是 #f5f7fa，输入条自身透明坐在白色父层上会透出一条白带——
     full 态铺同款灰底与画布连成一体（原型 fp-foot bg2 同构） */
  &.full {
    padding: 8px 24px 12px;
    background: var(--el-fill-color-light, #f5f7fa);

    /* Q5（ZCode 对齐，2026-10-10）：主列左贴会话列表（原 900 居中导致主体漂在离左栏
       半米远的空档；ZCode @1280 段=w-[calc(100%-24rem)]+translate-x-42 同语义=列贴左、
       右侧让面板）。max-width 900 帽不动，窄容器自然撑满 */
    /* k3 P1-3 回修（2026-10-11）：列共线——原 margin-left 24px 叠在本层 24px padding
       之上，输入列左缘=48 而消息列（MsgFlow.full 子块）=24，两条列差 24px 不共线
       （图-码对质实锤）。去 margin：两列同在 padding 24 轨上；贴左目标更彻底 */
    /* 宽屏加宽（2026-10-11 用户拍板）：帽改读宿主变量（单一真源由本组件 onMounted
       观测宿主写入，默认 900 不变；宿主 ≥1380 时 1080）。答案区/卡片行同步=同一变量 */
    .box {
      max-width: var(--dcs-col-max, 900px);
      margin: 0 auto 0 0;
    }

    /* Q6（用户反馈「卡片挤在左侧不与聊天框齐头」，2026-10-10）：chips/常用/附件条
       与输入卡同宽同起点（ZCode ConversationSuggestedPrompts 同构——建议行与
       输入框共用一条列几何），不再各自满宽左漂。k3 P2-3 回修：queue-bar（排队条）
       一并纳入同列口径——漏网时队列条满宽左漂。
       间距回修（2026-10-11 用户反馈「四卡与常用上下拥挤」）：原简写 margin:0 auto 0 0
       把基础样式的 margin-bottom 8px 清零——常用/快捷/附件/排队四行全部贴死输入卡
       上沿（实测 DOM 间隙 0）；改三值简写保留 8px 底距，四行与输入卡恢复呼吸 */
    .chips,
    .favbar,
    .attbar,
    .queue-bar {
      max-width: var(--dcs-col-max, 900px);
      margin: 0 auto 8px 0;
    }
  }
}
.chips {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
  flex-wrap: wrap;

  .chip {
    /* 原型 .chip：26px 白底胶囊，hover 三项全变主色 */
    height: 26px;
    padding: 0 12px;
    border: 1px solid var(--dcs-border-color-light);
    background: var(--el-bg-color);
    border-radius: 13px;
    font-size: 12.5px;
    color: var(--dcs-text-regular);
    cursor: pointer;
    transition: all 0.15s;

    &:hover {
      color: var(--el-color-primary);
      border-color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
    }
  }
}

/* 我的常用条（原型 favbar：⭐ 常用 + chips，✕ 移除） */
.favbar {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
  align-items: center;

  .flab {
    font-size: 11px;
    color: var(--dcs-text-placeholder);
    flex: none;
  }
  .fchip {
    /* 原型 .fchip：11px/t3/白底/radius 10 */
    border: 1px solid var(--dcs-border-color-light);
    background: var(--el-bg-color);
    border-radius: 10px;
    padding: 1px 8px;
    font-size: 11px;
    color: var(--dcs-text-secondary);
    cursor: pointer;
    user-select: none;

    &:hover {
      color: var(--el-color-primary);
      border-color: var(--el-color-primary-light-5);
    }
    u {
      margin-left: 5px;
      color: var(--dcs-text-placeholder);
      text-decoration: none;
      cursor: pointer;

      &:hover {
        color: var(--dcs-color-danger);
      }
    }
  }
}
/* 会话附件条（ATTACH：favbar 同构；em=状态小签，不可点） */
.attbar {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
  align-items: center;

  .flab {
    font-size: 11px;
    color: var(--dcs-text-placeholder);
    flex: none;
  }
  .fchip {
    border: 1px solid var(--dcs-border-color-light);
    background: var(--el-bg-color);
    border-radius: 10px;
    padding: 1px 8px;
    font-size: 11px;
    color: var(--dcs-text-secondary);
    user-select: none;
    display: inline-flex;
    align-items: center;

    .att-st {
      font-style: normal;
      margin-left: 5px;
      color: var(--dcs-text-placeholder);
    }
    /* AU-32: 上传中占位 chip（呼吸闪烁，完成后被真实 chip 顶替） */
    &.uploading {
      border-style: dashed;
      animation: dcs-att-pulse 1.2s ease-in-out infinite;

      .att-st {
        margin-left: 0;
        color: var(--el-color-primary);
      }
    }
    /* 移除钮（AU-05A）：chip 内小 ✕，hover 才显——不抢阅读注意力 */
    .att-x {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      margin-left: 4px;
      width: 14px;
      height: 14px;
      border: none;
      border-radius: 50%;
      background: none;
      color: var(--dcs-text-placeholder);
      cursor: pointer;
      opacity: 0;
      transition: opacity 0.15s;

      &:hover {
        background: var(--el-fill-color);
        color: var(--dcs-color-danger);
      }
    }
    &:hover .att-x {
      opacity: 1;
    }
  }
}
.queue-bar {
  border: 1px solid var(--dcs-border-color-light);
  border-radius: 12px;
  padding: 8px 10px;
  margin-bottom: 8px;
  background: var(--el-fill-color-extra-light);
  font-size: 12px;

  .qb-hd {
    display: flex;
    align-items: center;
    justify-content: space-between;
    color: var(--dcs-text-secondary);
    margin-bottom: 4px;
  }
  .qb-go {
    border: 1px solid var(--el-color-primary-light-5);
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
    border-radius: 999px;
    font-size: 11.5px;
    padding: 2px 9px;
    cursor: pointer;
  }
  .qb-p {
    color: var(--dcs-color-warning);
    font-size: 11.5px;
    margin-bottom: 4px;
  }
  .qb-it {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 3px 0;
    color: var(--dcs-text-regular);

    .q-n {
      color: var(--dcs-text-placeholder);
    }
    .q-t {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .q-ops {
      display: flex;
      gap: 2px;

      button {
        border: none;
        background: none;
        cursor: pointer;
        color: var(--dcs-text-secondary);
        font-size: 12px;
        padding: 2px 5px;
        border-radius: 5px;

        &:hover {
          color: var(--el-color-primary);
          background: var(--el-color-primary-light-9);
        }
      }
    }
  }
}
/* / 与 @ 命令菜单（批J-2：absolute 悬浮输入盒上方——原型 .slash，不再文档流下推） */
.cmdpop {
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: calc(100% + 6px);
  z-index: 30;
  border: 1px solid var(--dcs-border-color-light);
  border-radius: 10px;
  background: var(--el-bg-color);
  box-shadow: 0 6px 24px rgba(16, 24, 40, 0.08);
  padding: 6px;
  max-height: 240px;
  overflow-y: auto;

  .cmd-tip {
    font-size: 11px;
    color: var(--dcs-text-placeholder);
    padding: 2px 6px 4px;
  }
  /* 批17 Q3：双分组组头（功能命令/快捷问句） */
  .cmd-grp {
    font-size: 11px;
    color: var(--dcs-text-placeholder);
    padding: 4px 6px 2px;
    border-top: 1px dashed var(--dcs-border, #e4e7ed);
    margin-top: 2px;
    &:first-of-type {
      border-top: none;
    }
  }
  .cmd-it {
    display: flex;
    align-items: baseline;
    gap: 10px;
    width: 100%;
    border: none;
    background: none;
    padding: 8px 12px;
    border-radius: 8px;
    cursor: pointer;
    text-align: left;

    .ci {
      flex: none;
      font-size: 13px;
    }
    b {
      /* 原型 .slash b：Consolas 等宽 600 定宽（命令名列对齐） */
      font-family: Consolas, Monaco, monospace;
      font-size: 12.5px;
      font-weight: 600;
      min-width: 56px;
      color: var(--dcs-text-primary);
      white-space: nowrap;
    }
    .cs {
      flex: 1;
      min-width: 0;
      font-size: 12px;
      color: var(--dcs-text-secondary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    &.on,
    &:hover {
      color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);

      b {
        color: var(--el-color-primary);
      }
      /* 视觉席 U3 存疑项：选中高亮底上灰字 relation 对比度偏低（未复现丢失，模板恒渲染）——
         选中态提一档主色透明度，保证"我发布的/与我相关"在蓝底上可读 */
      .cs {
        color: var(--el-color-primary);
        opacity: 0.75;
      }
    }
  }
  .cmd-empty {
    padding: 8px;
    font-size: 12px;
    color: var(--dcs-text-placeholder);
    text-align: center;
  }
}
.box {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 12px;
  container-type: inline-size;
  border: 1px solid var(--dcs-border-color-light);
  background: var(--el-bg-color);
  border-radius: 16px;
  padding: 12px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.04);
  transition:
    border-color 0.15s,
    box-shadow 0.15s,
    background 0.15s;

  &:hover {
    border-color: var(--dcs-border-color);
  }
  /* 原型 :focus-within：浅蓝边 #79bbff + 底 #fafbfc（无外圈 ring） */
  &.focus {
    border-color: #79bbff;
    background: #fafbfc;
  }
  &.dragover {
    border: 1px dashed var(--el-color-primary);
    background: var(--el-color-primary-light-9);
  }
  textarea {
    width: 100%;
    min-height: 44px;
    max-height: 160px;
    border: none;
    outline: none;
    resize: none;
    font: inherit;
    font-size: 15px;
    line-height: 21px;
    padding: 11px 2px;
    color: var(--dcs-text-primary);
    background: transparent;

    &::placeholder {
      color: var(--dcs-text-placeholder);
      /* 原型：占位符单行省略（长轮换文案不折行） */
      text-overflow: ellipsis;
      overflow: hidden;
      white-space: nowrap;
    }
  }
}
.brow {
  display: flex;
  align-items: center;
  /* AU-13(1007拍板Q8)：4px 视觉实测过挤（余量环/思考档/发送互相贴住），放宽到 8px；
     窄容器档(@container)仍收 3px 保全小屏单行 */
  gap: 8px;

  .spacer {
    flex: 1;
  }
  /* 原型 v2.0 .btool 幽灵钮：无边框透明底/t3 字/13px/28px 高（批J-2 对齐） */
  .btool {
    display: flex;
    align-items: center;
    gap: 5px;
    border: 0;
    background: transparent;
    border-radius: 8px;
    height: 28px;
    padding: 0 8px;
    font-size: 13px;
    white-space: nowrap;
    flex: none;
    color: var(--dcs-text-secondary);
    cursor: pointer;
    position: relative;
    transition: all 0.15s;

    &:hover {
      background: var(--el-fill-color-light);
      color: var(--dcs-text-regular);
      transform: translateY(-1px);
    }
    &:active {
      transform: scale(0.97);
    }
  }
  /* ➕ 钮（原型 .btools .bicon：28×28 无边框，hover 灰底） */
  .btool.bicon {
    width: 28px;
    height: 28px;
    padding: 0;
    justify-content: center;

    &:hover {
      background: var(--el-fill-color-light);
      color: var(--dcs-text-regular);
    }
  }
  .bpill .tlabel {
    font-size: 13px;
  }
  /* R1 技能胶囊：蓝底亮态=正在生效；× 内钮独立 hover（点主体开 ➕ 面板，点 × 停用） */
  .bpill.spill {
    background: #e8f3ff;
    color: var(--el-color-primary, #409eff);
    gap: 5px;

    .tlabel {
      max-width: 84px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .sx {
      flex: none;
      width: 16px;
      height: 16px;
      padding: 0;
      border: none;
      border-radius: 50%;
      background: transparent;
      color: inherit;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;

      &:hover {
        background: rgba(64, 158, 255, 0.18);
      }
    }
  }
  /* 批7 模型选择 pill：模型名截断防撑爆输入条（弹层里有全名+单价） */
  .bpill.mpill .tlabel {
    max-width: 96px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  /* 业务批5 Q3-A ③：默认行被滤（无价/停用）的警示态——暖橙底一眼可辨"默认出问题了"，
     与 title 的处理指引配套（点开弹层仍可手动选可用模型继续用） */
  .bpill.mpill.mpill-warn {
    background: #fdf6ec;
    border-color: #f3d19e;
    color: #b88230;
  }
  /* ---- R1（2026-10-13）：联网一步钮（用户提议放 ➕ 旁）——开态蓝底即状态显示，
         原「联网 ✕」状态签(.wpill)同批撤并；未开放时维持灰态不给蓝（禁用语义不点亮） ---- */
  .btool.bicon.wbtn.on {
    background: #e8f3ff;
    color: var(--el-color-primary, #409eff);

    &:hover {
      background: #d9ecff;
      color: var(--el-color-primary, #409eff);
    }
  }
  /* 状态弹窗联网行：额度用完整行红（与回合行红色数字同语义，加行级提示） */
  .ctxrow.web-out,
  .ctxrow.web-out + .ctxsub {
    color: var(--el-color-danger);
  }
  .ctxrow.web-out .cbar i {
    background: var(--el-color-danger);
  }
  /* 会话状态合并环（批B B2：环=上下文已用%；14×14 / stroke 3.2 / 底环 .35） */
  .ring-btn {
    padding: 3px;
    border-radius: 8px;

    &:hover {
      opacity: 0.8;
    }
    /* 弹层展开时必须不透明——ctxpop 是本按钮子节点，按钮透明会连带弹层透底（视觉审查 2026-10-15 实锤） */
    &.ctx-open,
    &.ctx-open:hover {
      opacity: 1;
    }
    .ringwrap {
      position: relative;
      display: block;
      width: 14px;
      height: 14px;
    }
    .cring {
      width: 14px;
      height: 14px;
      display: block;

      .cr-bg {
        fill: none;
        stroke: var(--dcs-border-color-light);
        stroke-width: 3.2;
        opacity: 0.35;
      }
      .cr-fg {
        fill: none;
        stroke: var(--el-color-primary); /* 兜底；实际色由 :style CSS 对象按阈值覆盖（SVG 铁律） */
        stroke-width: 3.2;
        stroke-linecap: round;
        transform: rotate(-90deg);
        transform-origin: center;
        transition: stroke-dashoffset 0.3s;
      }
    }
    /* 额度哨兵点（不抢环色）：剩<20% 橙 / 用完红 */
    .qdot {
      position: absolute;
      top: -2px;
      right: -2px;
      width: 6px;
      height: 6px;
      border-radius: 50%;
      border: 1.5px solid var(--dcs-bg, #fff);
      background: var(--dcs-bg, #fff);

      &.orange {
        background: var(--el-color-warning);
      }
      &.red {
        background: var(--el-color-danger);
      }
    }
    /* 悬停明细（ctxpop；原型 L687-694：right:0 右锚防溢出；批B B2 双段卡+点击常开态）。
       ⚠️ 必须重置 white-space：.btool 的 nowrap 被卡片继承，长副文案（超 75% 变橙…）
       单行溢出 250px 卡片压到卡片外背景（5.3fh 视觉 P1 实证，2026-10-12 修） */
    .ctxpop {
      display: none;
      position: absolute;
      bottom: calc(100% + 10px);
      right: 0;
      width: 250px;
      padding: 11px 13px;
      border-radius: 10px;
      background: var(--el-bg-color-overlay);
      border: 1px solid var(--dcs-border-color-lighter);
      box-shadow: 0 6px 24px rgba(16, 24, 40, 0.08);
      flex-direction: column;
      gap: 5px;
      white-space: normal;
      z-index: 20;
      cursor: default;

      b {
        font-size: 12.5px;
        font-weight: 600;
        color: var(--dcs-text-primary);
        margin-bottom: 7px;
      }
      .ctxrow {
        display: flex;
        align-items: center;
        gap: 7px;
        font-size: 11.5px;
        color: var(--dcs-text-secondary);

        .cl {
          flex: none;
          width: 52px;
        }
        /* 批17 Q2：手动压缩小按钮 */
        .ctx-act {
          border: 1px solid var(--el-color-primary-light-5, #a0cfff);
          background: var(--el-color-primary-light-9, #ecf5ff);
          color: var(--el-color-primary, #409eff);
          border-radius: 6px;
          font-size: 10.5px;
          line-height: 1.5;
          padding: 1px 8px;
          cursor: pointer;
          &:disabled {
            opacity: 0.5;
            cursor: not-allowed;
          }
        }
        /* 业务批(1015) Q8：消费方式徽标（积分订阅/钱包按量）——与积分套餐页同色系 */
        .ctx-tag {
          flex: none;
          border-radius: 999px;
          padding: 1px 9px;
          font-size: 10.5px;
          line-height: 1.6;

          &.tag-credit {
            background: var(--el-color-success-light-9, #f0f9eb);
            border: 1px solid var(--el-color-success-light-5, #b3e19d);
            color: var(--el-color-success, #67c23a);
          }
          &.tag-wallet {
            background: var(--el-color-primary-light-9, #ecf5ff);
            border: 1px solid var(--el-color-primary-light-5, #a0cfff);
            color: var(--el-color-primary, #409eff);
          }
        }
        .cbar {
          flex: 1;
          height: 6px;
          border-radius: 3px;
          background: var(--el-fill-color-light);
          overflow: hidden;

          i {
            display: block;
            height: 100%;
            background: var(--el-color-primary);
          }
        }
      }
      .ctxsub {
        font-size: 10.5px;
        color: var(--dcs-text-placeholder);
        line-height: 1.5;
        margin: 0 0 5px 59px;
      }
      /* 业务批7 Q15 方案A（2026-10-14 拍板）：积分三池=三行微条（名字+6px条+%，对齐额度行列宽），
         恢复时间/精确值进 title hover；低于 20% 整行橙、耗尽红——替代 Q2 三迷你卡 */
      .mbrows {
        display: flex;
        flex-direction: column;
        gap: 5px;
        margin: 3px 0 1px;
      }
      .mbrow {
        display: flex;
        align-items: center;
        gap: 7px;
        font-size: 11px;
        color: var(--dcs-text-secondary);

        .mn {
          flex: none;
          width: 52px;
          color: var(--dcs-text-placeholder);
        }
        .mtrack {
          flex: 1;
          height: 6px;
          border-radius: 3px;
          background: var(--el-fill-color-light);
          overflow: hidden;

          i {
            display: block;
            height: 100%;
            background: var(--el-color-primary);
          }
        }
        .mv {
          flex: none;
          min-width: 34px;
          text-align: right;
          font-weight: 600;
          color: var(--dcs-text-primary);
        }
        &.warn .mv,
        &.warn .mn {
          color: var(--el-color-warning);
        }
        &.empty .mv,
        &.empty .mn {
          color: var(--el-color-danger);
        }
      }
      /* 明细入口（学 WorkBuddy）：直达「租户管理→积分套餐」流水页 */
      .qlink {
        color: var(--el-color-primary);
        cursor: pointer;

        &:hover {
          text-decoration: underline;
        }
      }
      /* 批B B2：上下文段与额度段的分隔线 */
      .ctxdiv {
        height: 1px;
        background: var(--dcs-border-color-lighter);
        margin: 4px 0;
      }
    }
    &:hover .ctxpop,
    &:focus-visible .ctxpop,
    &.ctx-open .ctxpop {
      display: flex;
    }
  }
  /* 发送/停止键族（原型 L255-256/672-673：8px 方圆角） */
  .round-btn {
    width: 32px;
    height: 32px;
    border-radius: 8px;
    border: none;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    color: #fff;
    background: #1f2937;
    flex: none;
    position: relative;
    transition: all 0.15s;

    &.send:hover {
      background: #111827;
    }
    &.stop {
      /* 原型 stopBtn：白底灰图标+bd2 描边，hover 变红 */
      background: var(--el-bg-color);
      color: var(--dcs-text-secondary);
      border: 1px solid var(--dcs-border-color-light);
      display: none;

      &.show {
        display: flex;
      }
      &:hover {
        color: var(--dcs-color-danger);
        border-color: var(--dcs-color-danger);
      }
    }
    &.send.dim {
      background: #eceef1;
      color: #a8abb2;
      cursor: default;
    }
    /* 排队角标（原型 .qb：琥珀底+白描边+穿透禁用；挂 send 恒可显，不依赖 busy 换色） */
    .qb {
      position: absolute;
      top: -5px;
      right: -5px;
      width: 15px;
      height: 15px;
      border-radius: 50%;
      background: var(--dcs-color-warning, #e6a23c);
      border: 1.5px solid #fff;
      color: #fff;
      font-size: 10px;
      font-weight: 700;
      line-height: 12px;
      text-align: center;
      pointer-events: none;
    }
  }
}
/* 弹层族（原型 .autopop v2.0 L807-810+L867：268px/12px 圆角/默认左锚；tpop 右锚） */
.autopop {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  width: min(268px, 92cqw);
  background: var(--el-bg-color);
  border: 1px solid var(--dcs-border-color-light);
  border-radius: 12px;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.14);
  padding: 4px 4px 6px;
  z-index: 40;
  animation: apopIn 0.12s cubic-bezier(0.2, 0.8, 0.3, 1);
  transform-origin: bottom left;

  &.tpop {
    left: auto;
    right: 0;
    transform-origin: bottom right;
  }
  /* 原型 autoPop(btn,1)→.autopop.alignend：自主权弹层右锚（用户反馈：左侧弹出与原型不符） */
  &.alignend {
    left: auto;
    right: 0;
    transform-origin: bottom right;
  }
  /* 批7 模型弹层：右锚已由 tpop 提供，这里只放宽行高容纳双行（模型名+单价副句） */
  &.mpop {
    .apopt .aptx {
      b {
        display: block;
        max-width: 168px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      span {
        font-size: 11px;
        color: var(--dcs-text-placeholder);
      }
    }
  }
  /* 批B 三审 P1：组头独立成行（按钮外），不再需要 .apopt 的 flex-wrap 断行 hack */
  .apgrp {
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.4px;
    opacity: 0.72;
    padding: 5px 9px 1px;
  }
  .apopt {
    display: flex;
    align-items: center;
    gap: 9px;
    width: 100%;
    padding: 7px 9px;
    border: none;
    background: none;
    border-radius: 8px;
    cursor: pointer;
    text-align: left;
    font: inherit;
    color: var(--dcs-text-secondary);

    &:hover {
      background: var(--el-fill-color-light);
    }
    /* 原型 v2.0 选中态：仅图标+标题变蓝，无整行底色 */
    &.on {
      color: var(--dcs-text-regular);
      background: transparent;
    }
    .apic {
      display: flex;
      flex: none;
    }
    &.on .apic {
      color: var(--el-color-primary);
    }
    .aptx {
      display: flex;
      flex-direction: column;
      gap: 1px;
      flex: 1;
      min-width: 0;

      b {
        font-size: 13px;
        font-weight: 500;
        color: var(--dcs-text-primary);
      }
      span {
        font-size: 11px;
        color: var(--dcs-text-placeholder);
        font-weight: 400;
      }
    }
    &.on .aptx b {
      color: var(--el-color-primary);
    }
    .apck {
      flex: none;
      color: var(--el-color-primary);
    }
  }
  /* T-2(1008)：「过程显示」开关行——EP switch 视觉口径（28×16 滑轨+12 圆点，主蓝点亮） */
  .apopt.tshow {
    border-top: 1px solid var(--dcs-border-color-light-7, #e4e7ed);
    margin-top: 2px;
    padding-top: 6px;
  }
  .apsw {
    flex: none;
    width: 28px;
    height: 16px;
    border-radius: 8px;
    background: rgb(0 0 0 / 15%);
    position: relative;
    transition: background 0.2s;

    i {
      position: absolute;
      top: 2px;
      left: 2px;
      width: 12px;
      height: 12px;
      border-radius: 50%;
      background: #fff;
      transition: left 0.2s;
      box-shadow: 0 0 2px rgb(0 0 0 / 20%);
    }

    &.on {
      background: var(--el-color-primary, #409eff);

      i {
        left: 14px;
      }
    }
  }
}
/* 思考充能条（原型 .tprog：3×13 竖条，tfill 高度=(档位+1)/3） */
.tprog {
  width: 3px;
  height: 13px;
  border-radius: 2px;
  background: rgb(0 0 0 / 10%);
  position: relative;
  overflow: hidden;
  flex: none;

  .tfill {
    position: absolute;
    left: 0;
    bottom: 0;
    width: 100%;
    background: var(--dcs-color-success);
    border-radius: 2px;
    /* Q3：ZCode 同款 spring 曲线（cubic-bezier 0.34,1.56,0.64,1) 300ms——档位切换有回弹感 */
    transition: height 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
  }
}
/* ➕ 添加面板（原型 addpop） */
.addpop {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  width: 100%;
  border: 1px solid var(--dcs-border-color-light);
  border-radius: 16px;
  background: var(--el-bg-color);
  box-shadow: 0 6px 24px rgba(16, 24, 40, 0.08);
  padding: 6px;
  z-index: 40;
  /* 动态尺寸（用户反馈面板太大，原型实测占视口约 45%、VSCode/ZCode 命令面板同款定式）：
     高度上限=420px 与 48vh 取小——大屏不超 420，小屏（720p≈345px）内滚不遮聊天区。
     滚动只在 .alist 行区：addpop 自身绝不设 overflow——自主权弹层（autopop）是其子元素
     且向上弹出在容器外，overflow 祖先会把它整个裁掉（用户实锤「弹窗没出来」） */
  max-height: min(420px, 48vh);
  display: flex;
  flex-direction: column;
  line-height: 1.45;

  .alist {
    flex: 1 1 auto;
    min-height: 0;
    overflow-y: auto;
    scrollbar-width: thin;
  }

  /* 标题/行/分组/底条全按原型 L642-651 字号与间距（批J-1 塌样修复）；行高显式钉死防继承 */
  .ah {
    font-size: 12px;
    line-height: 18px;
    color: var(--dcs-text-placeholder);
    padding: 6px 10px 4px;
    flex: none;
  }
  .arow {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    border: none;
    background: none;
    padding: 7px 10px;
    border-radius: 8px;
    cursor: pointer;
    text-align: left;
    font: inherit;
    font-size: 13px;
    line-height: 20px;
    color: var(--dcs-text-primary);

    /* 图标容器（IconSpan 根）：绝不能吃 flex:1——旧版 span 通配误伤 iswap 撑到 287px 整行网格断裂（视觉塌样根因） */
    > .iswap {
      flex: none;
      color: var(--dcs-text-secondary);
    }
    > b {
      font-weight: 500;
      white-space: nowrap;
    }
    /* 说明文字：自然宽度+省略（原型 L647 无 flex:1，胶囊靠自身 margin-left:auto 推右） */
    > span:not(.mch):not(.tagline):not(.iswap) {
      font-size: 12px;
      color: var(--dcs-text-placeholder);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .mch {
      margin-left: auto;
      flex: none;
      display: inline-flex;
      align-items: center;
      gap: 2px;
      font-size: 11px;
      color: var(--dcs-text-secondary);
      border: 1px solid var(--dcs-border-color-light);
      border-radius: 6px;
      padding: 1px 8px;
      line-height: 18px;
      background: var(--el-bg-color);
      white-space: nowrap;
      /* 档2「全自动」小签黄边提醒（原型 .mch.hot：wn 琥珀字） */
      &.hot {
        color: var(--dcs-color-warning, #e6a23c);
        border-color: #f3d19e;
      }
    }
    .tagline {
      margin-left: auto;
      flex: none;
      font-size: 10.5px;
      color: var(--dcs-text-placeholder);
      border: 1px solid var(--dcs-border-color-light);
      border-radius: 8px;
      padding: 0 6px;
      line-height: 16px;
      /* F-D2：能力已上线的正状态徽标（区别于灰置灰的"即将上线"） */
      &.on {
        color: var(--el-color-success, #67c23a);
        border-color: var(--el-color-success-light-8, #b3e19d);
      }
    }
    &.soon {
      cursor: default;
      opacity: 0.75;
    }
    &:not(.soon):hover {
      background: var(--el-fill-color-light);
    }
  }
  .agrp {
    font-size: 11px;
    line-height: 16px;
    color: var(--dcs-text-placeholder);
    padding: 10px 10px 4px;
  }
  /* R1 技能行选中态：浅蓝底+主色名（与 .apopt.on 弹层选项同语义，克制企业风） */
  .arow.on {
    background: var(--el-color-primary-light-9);

    > b {
      color: var(--el-color-primary);
    }
  }
  .atile {
    flex: none;
    width: 20px;
    height: 20px;
    border-radius: 6px;
    color: #fff;
    font-size: 11px;
    font-style: normal;
    display: flex;
    align-items: center;
    justify-content: center;

    &.todo {
      background: var(--el-color-primary);
    }
    &.urge {
      background: var(--dcs-color-warning);
    }
    &.cast {
      background: var(--dcs-color-success);
    }
  }
  .afoot {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 10px 6px;
    margin-top: 6px;
    border-top: 1px solid var(--dcs-border-color-light);
    font-size: 11.5px;
    line-height: 18px;
    color: var(--dcs-text-placeholder);
    flex: none;

    /* 原型 .ahint：右对齐搜索提示 */
    .ahint {
      margin-left: auto;
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }

    /* 原型 .akey 键帽（L651）：16px 见方灰底描边居中 */
    .akey {
      min-width: 16px;
      height: 16px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 1px solid var(--dcs-border-color-light);
      border-radius: 4px;
      background: var(--el-fill-color-light);
      font-size: 10.5px;
      color: var(--dcs-text-secondary);
      padding: 0 3px;
      margin-right: 4px;
    }
  }
}
.hint {
  margin: 6px 2px 0;
  font-size: 11px;
  color: var(--dcs-text-placeholder);
  text-align: center;
}
/* AU-32: 上传中 chip 呼吸闪烁 */
@keyframes dcs-att-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}

/* 三档弹层入场（原型 @keyframes popIn 同款） */
@keyframes apopIn {
  from {
    opacity: 0;
    transform: translateY(6px) scale(0.95);
  }
}
/* 原型 .box.btools 容器查询（L861-863）：窄容器下思考档名隐藏、只留图标+充能条。
   批8-4 回修（5.3fh 视觉席实证）：模型 pill(.mpill) 豁免——模型名是主信息（用户须知道
   当前用谁，抽屉 384px 命中本档时曾被连坐隐藏成图标-only），96px+ellipsis 已兜底长名 */
@container (max-width: 559px) {
  .brow {
    gap: 3px;

    .bpill:not(.mpill) .tlabel,
    .bpill:not(.mpill) .bchev {
      display: none;
    }
  }
}
/* 批2-1 Q1（拍板 2026-10-10）：宽容器档模型名放宽 96→200px——窄档 96px 是防撑爆兜底（面板内容宽仅
   308px，注入实测塞档位词会挤出发送键），但全屏 944px 还截断是反模式；业界硬指标 ZCode 288 /
   cherry 208 / open-webui 208 / LobeChat 200px，取 LobeChat 同值档，ellipsis+title 悬停兜底不变 */
@container (min-width: 560px) {
  /* 链上 .brow 对齐基础规则特异性（.brow .bpill.mpill .tlabel 同权 0,4,0），本块在文件尾部后写者胜 */
  .brow .bpill.mpill .tlabel {
    max-width: 200px;
  }
  /* Q3（ZCode 对齐，2026-10-10）：充能条只在文字隐藏的窄档出现（ZCode @sm~@xl 只显条、
     @xl 起文字常显无条）——宽档「🧠 快速 ▮▮ + 文字」三份冗余被用户点名，条退场 */
  .brow .bpill:not(.mpill) .tprog {
    display: none;
  }
}
/* 业务批4 Q7：超窄档（手机竖屏抽屉）mpill 图标化——96px 文字位在 360px 级容器仍挤走
   发送键/➕钮；收成 package 图标 only，全名走 pill 自带 title 悬停/长按可见（拍板口径） */
@container (max-width: 399px) {
  .brow .bpill.mpill .tlabel,
  .brow .bpill.mpill .bchev {
    display: none;
  }
}
/* 业务批3-④ #17：➕面板联网行 + 迷你开关（原型 v1.6 简单版开关=纯 CSS 滑块）。
   R1（2026-10-13 用户指出「剩 N 次」悬中间）：删行内说明字后行有余量；把推右职责
   从 .wsw 换到 .tagline——空档全落在标签与计数之间，计数与开关成组贴右（组三b mockup 口径） */
.arow.webrow {
  cursor: default;
}
/* R1：被禁用户输入置灰（发送在 useAssistant.assertUsable+onSend 双层拦截+toast；
   须放顶层——textarea 是 .box 直接子节点、.brow 的兄弟，嵌进 .brow 会编译成
   .brow textarea 永不命中（dspro P1-1） */
textarea.ta-disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
.arow.webrow b {
  cursor: default;
}
.arow.webrow .wsw {
  margin-left: 0;
}
.wsw {
  flex: none;
  width: 34px;
  height: 18px;
  margin-left: auto;
  border: none;
  border-radius: 9px;
  background: var(--dcs-fill, #dcdfe6);
  position: relative;
  transition: background 0.18s ease;
  cursor: pointer;
}
.wsw i {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
  transition: transform 0.18s ease;
}
.wsw.on {
  background: var(--el-color-primary);
}
.wsw.on i {
  transform: translateX(16px);
}

@media (prefers-reduced-motion: reduce) {
  .autopop {
    animation: none !important;
  }
}
</style>
