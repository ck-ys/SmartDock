<template>
  <div class="dcs-ops-page credit-page">
    <DcsOperationsPageHeader
      title="积分套餐"
      eyebrow="AI 配置与用量"
      subtitle="AI 助手积分订阅：钱包是钱，套餐是优惠额度——订阅后对话扣积分不扣钱包，用完即停可随时切换回按量计费。"
      :metrics="headerMetrics"
    >
      <template #actions>
        <el-button :icon="Refresh" @click="reloadAll">刷新</el-button>
      </template>
    </DcsOperationsPageHeader>

    <!-- 区2：当前订阅 + 三本账（业务批8 Q7 丁：头部消费方式开关，二选一显式切换） -->
    <el-card shadow="never" class="dcs-ops-table-card status-card">
      <template #header>
        <div class="card-head">
          <span>当前状态</span>
          <div class="mode-switch">
            <span class="mode-label">消费方式</span>
            <el-radio-group :model-value="consumeMode" @change="onSwitchMode">
              <el-radio-button value="WALLET">钱包按量</el-radio-button>
              <el-radio-button value="CREDIT" :disabled="!summary.subscribed">积分订阅</el-radio-button>
            </el-radio-group>
            <el-tooltip v-if="!summary.subscribed" content="先订阅套餐才能切到积分消费；订阅成功会自动切换，到期自动切回钱包按量" placement="top">
              <el-icon class="mode-tip"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </div>
      </template>
      <div class="sub-line">
        <span v-if="summary.subscribed" class="ui-pill ui-pill--pos">{{ summary.planName }} · 至 {{ shortDate(summary.endTime) }}</span>
        <span v-else class="ui-pill ui-pill--muted">未订阅</span>
        <span class="sub-hint">{{
          consumeMode === 'CREDIT'
            ? '积分消费中：助手对话扣积分不扣钱包；免费档模型（0 元）不扣积分；积分用尽可买加油包或切回钱包按量'
            : '钱包按量中：助手对话按实际用量扣租户钱包（元）；订阅套餐后自动切换为积分消费'
        }}</span>
      </div>
      <!-- 业务反馈批1015c Q8/Q11(拍板A)：走钱包时积分池不动=预期（这轮扣的是钱包），但池卡
           照常显示 100%，演示/实测屡被当成 bug——显式引导条说真话，并指路钱包流水 -->
      <el-alert
        v-if="consumeMode !== 'CREDIT'"
        class="wallet-lead"
        type="info"
        :closable="false"
        show-icon
        title="当前按钱包计费：助手对话的扣费记录在「钱包流水」页，本页积分池不会变动；订阅套餐后自动切换为积分消费"
      />
      <el-row :gutter="16">
        <el-col :xs="24" :sm="24" :md="12" :lg="8">
          <div class="ledger-box">
            <div class="ledger-title">本周周池 · 剩余</div>
            <div class="ledger-num">
              {{ fmt(weeklyLeft) }} <span class="ledger-unit">/ {{ fmt(summary.weeklyCap) }} 积分</span>
            </div>
            <el-progress :percentage="weeklyPct" :stroke-width="8" :show-text="false" :color="barColor(weeklyPct)" />
            <div class="ledger-hint">周一 0 点整池恢复 · 本周已用 {{ fmt(summary.weeklyUsed) }} 积分</div>
          </div>
        </el-col>
        <el-col :xs="24" :sm="24" :md="12" :lg="8">
          <div class="ledger-box">
            <div class="ledger-title">{{ summary.windowHours }} 小时窗口 · 剩余</div>
            <div class="ledger-num">
              {{ fmt(windowLeft) }} <span class="ledger-unit">/ {{ fmt(summary.windowCap) }} 积分</span>
            </div>
            <el-progress :percentage="windowPct" :stroke-width="8" :show-text="false" :color="barColor(windowPct)" />
            <div class="ledger-hint">滚动窗口只限周池扣减，超限部分自动走加油包 · 窗口内已用 {{ fmt(summary.windowUsed) }} 积分</div>
          </div>
        </el-col>
        <el-col :xs="24" :sm="24" :md="12" :lg="8">
          <div class="ledger-box">
            <div class="ledger-title">加油包余额</div>
            <div class="ledger-num">{{ fmt(summary.tankBalance) }} <span class="ledger-unit">积分</span></div>
            <div class="ledger-hint">一次性余额，不受周池与窗口限制，用完为止；购买入口见下方「订阅档位」区</div>
          </div>
        </el-col>
        <el-col v-if="summary.subscribed && summary.totalQuota > 0" :xs="24" :sm="24" :md="12" :lg="8">
          <!-- Q12：整周期总额账盒——总剩余+已用%（先软后硬：只展示与预警，不拦扣费） -->
          <div class="ledger-box">
            <div class="ledger-title">整周期总额 · 剩余</div>
            <div class="ledger-num">
              {{ fmt(totalRemaining) }} <span class="ledger-unit">/ {{ fmt(summary.totalQuota) }} 积分</span>
            </div>
            <el-progress :percentage="totalPct" :stroke-width="8" :show-text="false" :color="barColor(totalPct)" />
            <div class="ledger-hint">本订阅期已用 {{ fmt(summary.termUsed) }} 积分（{{ totalPct }}%）· 用满会不会断：暂不限制，按周池节奏走</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 区1：档位（业务批8 Q4：description 上卡 + 中间档高亮 + 技术参数收灰字 + 加油包升格窄卡） -->
    <el-card shadow="never" class="dcs-ops-table-card" v-loading="loading">
      <template #header>
        <div class="card-head"><span>订阅档位</span><span class="head-sub">订阅后立即生效；切换档位旧订阅即停（剩余周池不结转）</span></div>
      </template>
      <el-row :gutter="16">
        <el-col v-for="(p, idx) in subscribePlans" :key="p.planCode" :xs="24" :sm="12" :md="8">
          <div
            class="plan-card"
            :class="{ current: summary.planCode === p.planCode, hot: p.planCode === recommendedPlanCode, off: p.enabled === '0' }"
          >
            <span v-if="p.planCode === recommendedPlanCode" class="ui-pill ui-pill--warn plan-hot-badge">最受欢迎</span>
            <span v-if="p.enabled === '0'" class="ui-pill ui-pill--muted plan-off-tag">已下架 · 仅超管可见</span>
            <div class="plan-name" :class="`plan-name-${idx}`">{{ p.planName }}</div>
            <div class="plan-price">
              ¥{{ Number(p.priceYuan).toFixed(0) }}<span class="plan-per"> / {{ p.durationWeeks }} 周</span>
            </div>
            <!-- 业务反馈批1015b Q12：整周期总额上卡（此前只有"每周"，看不出整期一共多少） -->
            <div class="plan-total">整周期总额 {{ fmt(planTotal(p)) }} 积分</div>
            <!-- 业务批8 Q4：库里的「适合谁」人话副标语上卡（此前 0 处渲染） -->
            <div v-if="p.description" class="plan-who">{{ p.description }}</div>
            <ul class="plan-features">
              <!-- step5 P2-D5 回修：轮数按经济档（×2）折算，重模型轮数腰斩以上——不标注会被当承诺 -->
              <li>
                每周 {{ fmt(p.weeklyCredits) }} 积分 ≈ {{ fmt(roundsPerWeek(p)) }} 轮对话<span class="rounds-basis"
                  >（按经济档模型折算，重模型更少）</span
                >
              </li>
              <li v-if="p.websearchLimitPerUserDaily > 0">每日联网搜索 {{ fmt(p.websearchLimitPerUserDaily) }} 次</li>
              <li>对话 / 工具 / 联网全场景可用</li>
            </ul>
            <!-- 业务批8 Q4：防刷窗口是风控参数，不当主卖点，收卡底一行灰字 -->
            <div class="plan-tech">突发用量保护：{{ p.windowHours }} 小时窗口上限 {{ fmt(p.windowCredits) }} 积分</div>
            <div class="plan-actions">
              <el-button
                v-hasPermi="['dcs:aicredit:subscribe']"
                class="plan-buy"
                :type="summary.planCode === p.planCode ? 'success' : 'primary'"
                :plain="summary.planCode === p.planCode"
                :disabled="p.enabled === '0'"
                :loading="busy === p.planCode"
                @click="onSubscribe(p)"
              >
                {{
                  p.enabled === '0'
                    ? '已下架'
                    : summary.planCode === p.planCode
                      ? '续订 / 重新起算'
                      : `订阅 · 扣钱包 ¥${Number(p.priceYuan).toFixed(0)}`
                }}
              </el-button>
              <!-- 业务批8 Q4：赠送开通（不扣钱包，仅超管）与改价入口 -->
              <el-button v-hasPermi="['dcs:aicredit:plan-edit']" size="small" text type="primary" @click="onSubscribe(p, true)">赠送</el-button>
              <el-button v-hasPermi="['dcs:aicredit:plan-edit']" size="small" text @click="openPlanEdit(p)">改价</el-button>
            </div>
            <span v-if="summary.planCode === p.planCode" class="ui-pill ui-pill--pos plan-cur">当前档</span>
            <!-- 业务批8 Q4：改价影响提示——该档当前在订租户数（跨租户，仅超管可见数据） -->
            <div v-if="planActiveCounts[p.planCode] > 0" class="plan-active-n">
              当前 {{ planActiveCounts[p.planCode] }} 家租户在订此档，改价立即影响它们
            </div>
          </div>
        </el-col>
      </el-row>
      <!-- 业务批8 Q4：加油包升格——从「当前状态」小按钮升为卡区横条窄卡（此前藏在余额盒里难发现） -->
      <div v-if="boostPlan" class="boost-bar">
        <div class="boost-main">
          <span class="boost-name">加油包</span>
          <span class="boost-price">¥{{ Number(boostPlan.priceYuan).toFixed(0) }}</span>
          <span class="boost-qty">= {{ fmt(boostPlan.weeklyCredits) }} 积分一次性 · 约 {{ fmt(roundsOf(boostPlan.weeklyCredits)) }} 轮</span>
        </div>
        <span class="boost-desc">{{ boostPlan.description || '池尽顶上，不受窗口限制' }}</span>
        <div class="boost-actions">
          <el-button v-hasPermi="['dcs:aicredit:subscribe']" type="warning" :loading="busy === 'boost'" @click="onBoost(false)">
            购买 · 扣钱包 ¥{{ Number(boostPlan.priceYuan).toFixed(0) }}
          </el-button>
          <el-button v-hasPermi="['dcs:aicredit:plan-edit']" size="small" text type="primary" @click="onBoost(true)">赠送</el-button>
          <el-button v-hasPermi="['dcs:aicredit:plan-edit']" size="small" text @click="openPlanEdit(boostPlan)">改价</el-button>
        </div>
      </div>
    </el-card>

    <!-- 业务批8 Q4：套餐编辑弹窗（任何参数可改，仅超管；kind/planCode 技术键只读防断链） -->
    <el-dialog v-model="planEdit.visible" :title="`编辑套餐 · ${planEdit.form.planCode || ''}`" width="520px" append-to-body>
      <el-alert
        v-if="planEdit.activeCount > 0"
        type="warning"
        :closable="false"
        show-icon
        :title="`此档当前有 ${planEdit.activeCount} 家租户正在订阅，保存后新配额立即对它们生效（已扣费用不退）。`"
        class="edit-alert"
      />
      <el-form :model="planEdit.form" label-width="130px" size="default">
        <el-form-item label="套餐类型">
          <span class="ui-pill ui-pill--muted">
            {{ planEdit.form.kind === 'BOOST' ? '加油包' : '订阅档' }}
          </span>
          <span class="edit-ro-hint">类型与编码是技术键，不可改（改了会让存量订阅断链）</span>
        </el-form-item>
        <el-form-item label="套餐名称"><el-input v-model="planEdit.form.planName" maxlength="60" /></el-form-item>
        <el-form-item label="售价（元）"><el-input-number v-model="planEdit.form.priceYuan" :min="0" :precision="2" :step="10" /></el-form-item>
        <el-form-item :label="planEdit.form.kind === 'BOOST' ? '积分量（一次性）' : '每周积分池'">
          <el-input-number v-model="planEdit.form.weeklyCredits" :min="0" :step="500" />
        </el-form-item>
        <template v-if="planEdit.form.kind !== 'BOOST'">
          <el-form-item label="窗口上限（积分）">
            <el-input-number v-model="planEdit.form.windowCredits" :min="0" :step="100" />
            <span class="edit-ro-hint">防刷：滚动窗口内最多可用量，建议=周池 20%</span>
          </el-form-item>
          <el-form-item label="窗口时长（小时）"><el-input-number v-model="planEdit.form.windowHours" :min="1" :max="48" /></el-form-item>
          <el-form-item label="订阅时长（周）"><el-input-number v-model="planEdit.form.durationWeeks" :min="1" :max="52" /></el-form-item>
          <el-form-item label="整周期总额度">
            <el-input-number v-model="planEdit.form.totalCredits" :min="0" :step="1000" />
            <span class="edit-ro-hint"
              >留空=按 每周×周数 推导（当前推导值
              {{ fmt(Number(planEdit.form.weeklyCredits || 0) * Number(planEdit.form.durationWeeks || 0)) }}）；填了以填写值为准</span
            >
          </el-form-item>
          <el-form-item label="每日联网次数/人">
            <el-input-number v-model="planEdit.form.websearchLimitPerUserDaily" :min="0" :step="10" />
            <span class="edit-ro-hint">0=跟随租户默认</span>
          </el-form-item>
        </template>
        <el-form-item label="在售状态">
          <el-switch v-model="planEdit.enabled" active-text="在售" inactive-text="已下架" />
          <span class="edit-ro-hint">下架后租户不可再订阅；已订阅不受影响（step5 P2-5：超管视角仍可见此卡可再上架）</span>
        </el-form-item>
        <el-form-item label="副标语（卡片）"
          ><el-input v-model="planEdit.form.description" type="textarea" :rows="2" maxlength="300" show-word-limit
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planEdit.visible = false">取消</el-button>
        <el-button type="primary" :loading="planEdit.saving" @click="onSavePlan">保存</el-button>
      </template>
    </el-dialog>

    <!-- 区2.5：模型积分倍率（业务批8 Q2：不同模型每回合吃的积分不一样，订阅期间按此表扣分） -->
    <el-card v-if="modelRates.length > 0" shadow="never" class="dcs-ops-table-card">
      <template #header>
        <div class="card-head">
          <span>模型积分倍率</span>
          <!-- step5 P1-D3 回修：原「按模型名后缀 ×N 生效」与计费规则矛盾——免费由计费配置判定
               （FIXED_PER_CALL 且单价 0），带 :free 后缀的模型照样按 token 计费扣分（表内即有反例） -->
          <span class="head-sub">重模型每回合消耗更多积分；本表只读——想改倍率/免费？去「AI 模型配置 → 编辑模型」调整（与定价设置同步）</span>
        </div>
      </template>
      <!-- 业务批(1015) Q12：倍率表改厂商分组卡——后端 modelRates 平铺无序（模型裸 id+厂商裸 code
           散落各处），前端按厂商分组（组头=logo+中文名+模型数，含默认组置顶），组内排序：默认→免费→倍率升序 -->
      <!-- 业务反馈批1015c Q12(拍板A)三刀：①组头可折叠（默认只展开含默认模型的组，厂商多时
           不再全平铺）；②组内三列严格对齐（列头一次，模型名长短不一时右缘不再参差）；
           ③徽章归一——免费=绿「免费」，其余=×N 单色阶（去掉"×1 免费档"字样防与免费徽章打架） -->
      <div class="rate-groups">
        <div v-for="g in rateGroups" :key="g.provider" class="rate-group">
          <button class="rate-ghead" type="button" :aria-expanded="!isRateCollapsed(g)" @click="toggleRate(g)">
            <ProviderLogo :provider="g.provider" :size="20" />
            <b>{{ providerNameOf(g.provider) }}</b>
            <span class="rate-gcount">{{ g.rows.length }} 个模型</span>
            <span v-if="g.hasDefault" class="ui-pill ui-pill--pos rate-gdef">含默认</span>
            <span class="rate-garrow" :class="{ open: !isRateCollapsed(g) }">▸</span>
          </button>
          <template v-if="!isRateCollapsed(g)">
            <div class="rate-cols">
              <span>模型</span>
              <span>档位</span>
              <span class="ta-r">约积分/轮</span>
            </div>
            <div class="rate-gbody">
              <div v-for="row in g.rows" :key="row.configId" class="rate-grow">
                <span class="rate-gname">
                  <span class="dcs-ops-id">{{ row.modelName }}</span>
                  <span v-if="row.isDefault" class="ui-pill ui-pill--pos rate-star">默认</span>
                </span>
                <!-- 免费由计费配置判定（FIXED_PER_CALL 且单价 0），无视倍率占位（step5 P1-3 口径保持） -->
                <span v-if="row.free" class="ui-pill ui-pill--pos">免费</span>
                <span v-else class="ui-pill" :class="ratePillClass(row.multiplier)">×{{ row.multiplier }}</span>
                <span class="rate-gcost">{{ row.free ? '不耗积分' : `约 ${row.perRound} 积分/轮` }}</span>
              </div>
            </div>
          </template>
        </div>
      </div>
    </el-card>

    <!-- 区3：流水对账（业务批7 Q4 真分页；业务批8 Q3 汇总条+扣分红+入账显量） -->
    <el-card shadow="never" class="dcs-ops-table-card">
      <template #header>
        <div class="card-head">
          <span
            >积分流水<span class="head-sub" style="margin-left: 8px">共 {{ fmt(ledgerTotal) }} 条</span></span
          >
          <!-- 菜单IA重组批3(2026-10-16)：钱包账|积分账两本账打通——完整流水对账入口指向账户与账单；
               深审回修(P2-6)：无钱包流水权限（租户管理员视角）不亮入口，其本页即完整积分账 -->
          <el-button
            v-if="checkPermi(['dcs:wallet-ledger:list'])"
            link
            type="primary"
            size="small"
            style="margin-left: 14px"
            @click="router.push('/tenant/account-billing?tab=ledger')"
            >完整积分账 →</el-button
          >
          <div class="ledger-filter">
            <!-- 业务批8 Q6：双视角——租户全景 / 只看我的（管理员看全租户账，个人核对自分） -->
            <el-radio-group v-model="ledgerView" @change="onSwitchView">
              <el-radio-button value="tenant">租户全景</el-radio-button>
              <el-radio-button value="mine">只看我的</el-radio-button>
            </el-radio-group>
            <el-select
              v-if="ledgerView === 'tenant'"
              v-model="ledgerUserId"
              style="width: 150px"
              filterable
              clearable
              placeholder="全部人员"
              @change="onSearch"
            >
              <el-option v-for="u in ledgerUsers" :key="u.userId" :label="u.nickName" :value="u.userId" />
            </el-select>
            <el-select v-model="ledgerBiz" style="width: 120px" @change="onSearch">
              <el-option label="全部类型" value="" />
              <el-option label="消耗" value="DEDUCT" />
              <el-option label="加油包" value="BOOST" />
              <el-option label="订阅入账" value="SUBSCRIBE" />
              <el-option label="调整" value="ADJUST" />
            </el-select>
            <el-date-picker
              v-model="ledgerRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="~"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              style="width: 240px"
              @change="onSearch"
            />
            <el-button :icon="Refresh" @click="onSearch">查询</el-button>
            <el-button @click="resetLedgerFilter">重置</el-button>
            <!-- AI 缺口批 Q7：按当前筛选导出流水 Excel（与列表同口径，上限 1 万行） -->
            <el-button :icon="Download" @click="exportLedger">导出 Excel</el-button>
          </div>
        </div>
      </template>
      <!-- 业务批8 Q3：汇总条——本周消耗 + 周池剩余（账单感；数据源 summary 同口现算）。
           step5 P1-D1 回修：汇总带是全租户口径，「只看我的」视角下表格只剩本人行，租户数字会与
           表格对不上造成误导——个人视角隐藏汇总带，换一行灰字指路 -->
      <!-- 题5-② 汇总带收到 2 格：「本月消耗」降级进本周格的 hint（明细在下方流水表） -->
      <div v-if="ledgerView === 'tenant'" class="ui-kpi-strip ledger-summary">
        <div class="ui-kpi">
          <span class="ui-kpi__label">全租户 · 本周消耗</span>
          <strong class="ui-kpi__value">{{ summary.weekSpent > 0 ? `-${fmt(summary.weekSpent)}` : '0' }}</strong>
          <span class="ui-kpi__hint">本月 {{ summary.monthSpent > 0 ? `-${fmt(summary.monthSpent)}` : '0' }}</span>
        </div>
        <div class="ui-kpi">
          <span class="ui-kpi__label">本周剩余（周池）</span>
          <strong class="ui-kpi__value">{{ fmt(Math.max(0, summary.weeklyCap - summary.weeklyUsed)) }}</strong>
        </div>
      </div>
      <div v-else class="ledger-summary-mine">汇总带为全租户口径，与「只看我的」列表对不上——切回「租户全景」查看汇总</div>
      <div class="ledger-table-wrap">
        <el-table v-loading="ledgerLoading" :data="ledgerRows" class="ui-table">
          <el-table-column label="时间" prop="createTime" width="170" />
          <el-table-column label="消耗人" width="110" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.operatorName || '--' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="90" align="center">
            <template #default="{ row }">
              <span class="ui-pill" :class="pillOf(bizTag(row.bizType).tone)">{{ bizTag(row.bizType).label }}</span>
            </template>
          </el-table-column>
          <el-table-column label="积分" width="170" align="right">
            <template #default="{ row }">
              <div>
                <!-- 业务批8 Q3：扣分从蓝改红（与钱包流水页「支出红/收入绿」统一）；
                     订阅入账行从 remark 提取真实量显示（此前显示「—」，本该最喜庆的行反而空着） -->
                <span v-if="row.bizType === 'DEDUCT'" class="ded-num">-{{ fmt(row.credits) }}</span>
                <span v-else-if="row.bizType === 'SUBSCRIBE' && subscribeAmountOf(row)" class="add-num">
                  +{{ fmt(subscribeAmountOf(row)!.perWeek) }}<span class="add-sub"> ×{{ subscribeAmountOf(row)!.weeks }}周</span>
                </span>
                <span v-else-if="row.credits > 0" class="add-num">+{{ fmt(row.credits) }}</span>
                <span v-else class="add-none">—</span>
              </div>
              <!-- 业务批7 Q4：扣减构成从独立列并入小字（「来源拆分」列整列下线——看不懂的是列名） -->
              <div v-if="row.bizType === 'DEDUCT' && row.credits > 0" class="credit-src">
                {{ row.tankCredits > 0 ? `周池 ${fmt(row.weeklyCredits)} · 加油包 ${fmt(row.tankCredits)}` : `周池 ${fmt(row.weeklyCredits)}` }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="场景" width="170" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="dcs-ops-id">{{ sceneLabel(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.remark || (row.bizType === 'DEDUCT' ? '助手对话消耗' : '--') }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">暂无积分流水</p>
              <p class="ui-empty__hint">订阅并产生一次助手对话后，可在此对账</p>
            </div>
          </template>
        </el-table>
      </div>
      <div class="ledger-pager">
        <el-pagination
          v-model:current-page="ledgerPage.pageNum"
          v-model:page-size="ledgerPage.pageSize"
          background
          layout="total, prev, pager, next, sizes"
          :page-sizes="[10, 20, 50]"
          :total="ledgerTotal"
          @current-change="loadLedger"
          @size-change="onSearch"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { QuestionFilled, Refresh, Download } from '@element-plus/icons-vue';
// 业务批(1015) Q12：厂商分组卡复用 ai-config 的 logo 组件与 presets 中文名目录
import ProviderLogo from '../ai-config/ProviderLogo.vue';
import { listDcsAiProviderPresets, type DcsAiProviderPresetVO } from '@/api/dcs/ai';
import {
  getCreditPlans,
  subscribeCreditPlan,
  boostCreditPlan,
  setBillingMode,
  saveCreditPlan,
  changeCreditPlanStatus,
  listCreditLedger,
  listLedgerUsers,
  getModelRates,
  type ModelRateVO,
  type CreditPlanVO,
  type CreditSummaryVO,
  type CreditLedgerVO,
  type CreditPlanSaveBO
} from '@/api/dcs/ai-credit';
import { checkPermi } from '@/utils/permission';

defineOptions({ name: 'DcsAiCredit' });

const router = useRouter();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(false);
const plans = ref<CreditPlanVO[]>([]);
const summary = ref<CreditSummaryVO>({
  subscribed: false,
  planCode: null,
  planName: null,
  endTime: null,
  weeklyUsed: 0,
  weeklyCap: 0,
  windowUsed: 0,
  windowCap: 0,
  windowHours: 5,
  tankBalance: 0,
  weekStart: '',
  weekSpent: 0,
  monthSpent: 0,
  totalQuota: 0,
  termUsed: 0
});
/** 五审回修（step5 P2-1）：下单按钮防连点（确认弹窗取消会抛 rejection，须 finally 复位） */
const busy = ref<string | null>(null);
/** 业务批8 Q7 丁：消费方式开关（WALLET=钱包按量默认 / CREDIT=积分订阅） */
const consumeMode = ref<'WALLET' | 'CREDIT'>('WALLET');
/** 业务批8 Q4：各档在订租户数（超管改价影响提示） */
const planActiveCounts = ref<Record<string, number>>({});
/** 业务批8 Q4：套餐编辑弹窗（任何参数可改；kind/planCode 只读） */
const planEdit = reactive({
  visible: false,
  saving: false,
  activeCount: 0,
  /** step5 P2-5：在售状态独立于 BO（后端 change-status 独立接口） */
  enabled: true,
  form: {} as CreditPlanSaveBO
});

const ledgerLoading = ref(false);
const ledgerRows = ref<CreditLedgerVO[]>([]);
const ledgerBiz = ref('');
/** 业务批7 Q4：真分页 + 时间范围筛选（后端半开区间 [当天 00:00, 次日 00:00)——k3 P3 勘误：非闭区间） */
const ledgerRange = ref<[string, string] | null>(null);
const ledgerTotal = ref(0);
const ledgerPage = reactive({ pageNum: 1, pageSize: 20 });
/** 业务批8 Q6：双视角（tenant=租户全景默认 / mine=只看我的）+ 按人筛选 */
const ledgerView = ref<'tenant' | 'mine'>('tenant');
const ledgerUserId = ref<number | null>(null);
const ledgerUsers = ref<Array<{ userId: number; nickName: string }>>([]);
/** 业务批8 Q2：模型积分倍率表 */
const modelRates = ref<ModelRateVO[]>([]);
/** 徽章归一（业务反馈批1015c Q12 + 全站 UI 契约 §2.6）：免费=成功药丸，×N 只按四组语义药丸
 *  分档（×1/×2 中性、×3 警告、≥×4 危险），不再自造蓝色阶（契约明令禁止四档蓝阶） */
const ratePillClass = (m: number): string => (m >= 4 ? 'ui-pill--neg' : m >= 3 ? 'ui-pill--warn' : 'ui-pill--muted');
/** Q12 折叠：用户手动开合过的组记名册（自动收起只发生在首次加载，不覆盖用户选择）。
 *  ⚠必须是 reactive Set：模板折叠态读 isRateCollapsed()→rateCollapsed.has()，
 *  普通 Set 变更不触发重渲染=点击组头无反应（1015c 批①第二次热修实录：
 *  TDZ 修复后首次暴露——首渲染折叠对是因为 immediate watch 在渲染前已填好，
 *  一次性读值不需要响应式，点击变更才需要） */
const rateTouched = reactive(new Set<string>());
const rateCollapsed = reactive(new Set<string>());
/** 默认只展开含默认模型的组——无默认的厂商组收起一行（logo+名+模型数）。
 *  ⚠watch 必须挂在 rateGroups 定义之后：immediate 首拍立即读源，挂在定义前=TDZ 白屏
 *  （1015c 批①热修实录：本块原在 rateTouched/rateCollapsed 声明处，致整页白屏） */
const isRateCollapsed = (g: { provider: string }) => rateCollapsed.has(g.provider);
const toggleRate = (g: { provider: string }) => {
  rateTouched.add(g.provider);
  if (rateCollapsed.has(g.provider)) {
    rateCollapsed.delete(g.provider);
  } else {
    rateCollapsed.add(g.provider);
  }
};

const subscribePlans = computed(() => plans.value.filter((p) => p.kind === 'SUBSCRIBE'));
const boostPlan = computed(() => plans.value.find((p) => p.kind === 'BOOST'));
/**
 * 业务批8 Q4：「最受欢迎」中间档（行为经济学锚定——三档同构+中间档高亮是 GLM/Stripe
 * 定价页通则）。通用判定：按价格升序取中间位；少于 3 档不挂角标（避免两档时武断）。
 */
const recommendedPlanCode = computed(() => {
  const sorted = [...subscribePlans.value].sort((a, b) => a.priceYuan - b.priceYuan);
  return sorted.length >= 3 ? (sorted[Math.floor(sorted.length / 2)]?.planCode ?? null) : null;
});
/** 业务批8 Q8：轮数宣传口径——实测均值约 1.3 积分/轮（取整百，下限口径诚实标注）。
 *  step5 P2-3 回修：订阅卡与加油包横条共用本函数（此前加油包裸除 1.3 出 3,846
 *  与订阅卡取整百口径同页打架） */
const roundsOf = (credits: number) => Math.max(100, Math.round(credits / 1.3 / 100) * 100);
const roundsPerWeek = (p: CreditPlanVO) => roundsOf(p.weeklyCredits);
/**
 * 业务批(1015) Q8：两池进度条/数字改「剩余」口径——旧「已用」口径与会话状态卡
 * （剩余%）同屏反向（一个剩 98% 一个用 2%），用户实测以为是数据打架；统一后
 * 条=剩余量（满条=满池），已用进 hint；打分席位 P2 的 minVisiblePct 随口径一并退役
 */
const weeklyLeft = computed(() => Math.max(0, summary.value.weeklyCap - summary.value.weeklyUsed));
/** Q12：套餐有效总额（显式 totalCredits 优先，否则 每周×周数 推导） */
const planTotal = (p: CreditPlanVO) =>
  Number(p.totalCredits) > 0 ? Number(p.totalCredits) : Number(p.weeklyCredits || 0) * Number(p.durationWeeks || 0);
/** Q12：整周期剩余与已用%（先软后硬：展示口径，不参与扣费闸） */
const totalRemaining = computed(() => Math.max(0, (summary.value.totalQuota || 0) - (summary.value.termUsed || 0)));
const totalPct = computed(() =>
  summary.value.totalQuota > 0 ? Math.min(100, Math.round((summary.value.termUsed / summary.value.totalQuota) * 100)) : 0
);
const windowLeft = computed(() => Math.max(0, summary.value.windowCap - summary.value.windowUsed));
const weeklyPct = computed(() => (summary.value.weeklyCap > 0 ? Math.min(100, Math.round((weeklyLeft.value / summary.value.weeklyCap) * 100)) : 0));
const windowPct = computed(() => (summary.value.windowCap > 0 ? Math.min(100, Math.round((windowLeft.value / summary.value.windowCap) * 100)) : 0));

const headerMetrics = computed(() => [
  {
    label: '订阅状态',
    value: summary.value.subscribed ? summary.value.planName || '已订阅' : '未订阅',
    hint: summary.value.subscribed ? `有效期至 ${summary.value.endTime || '--'}` : '助手对话按钱包余额计费',
    tone: (summary.value.subscribed ? 'success' : 'neutral') as 'success' | 'neutral'
  },
  {
    label: '消费方式',
    value: consumeMode.value === 'CREDIT' ? '积分订阅' : '钱包按量',
    hint: consumeMode.value === 'CREDIT' ? '对话扣积分不扣钱包' : '对话按实际用量扣钱包（元）',
    tone: (consumeMode.value === 'CREDIT' ? 'primary' : 'neutral') as 'primary' | 'neutral'
  }
  // 打分席位 P1 回修（2026-10-15）：头部原第三/四/五卡（周池剩余/窗口用量/加油包余额）与
  // 下方「当前状态」卡三本账完全同数重复，同屏两份数字违背工作面高密度原则——数字唯一权威归当前状态卡
]);

const fmt = (n?: number | null) => `${Number(n || 0).toLocaleString('zh-CN')}`;
const shortDate = (s?: string | null) => (s ? String(s).slice(0, 10) : '--');
const barColor = (pct: number) =>
  // 业务批(1015) Q8：改剩余口径后配色语义翻转——余量≤10% 红、≤30% 橙、充足绿（积分主题色）；
  // 其余进度条（如订阅卡）仍走已用口径的不受影响
  pct <= 10 ? 'var(--el-color-danger)' : pct <= 30 ? 'var(--el-color-warning)' : 'var(--el-color-success)';
const bizTag = (t: string) => {
  const map: Record<string, { label: string; tone: 'info' | 'warning' | 'success' | 'primary' }> = {
    DEDUCT: { label: '消耗', tone: 'primary' },
    BOOST: { label: '加油包', tone: 'warning' },
    SUBSCRIBE: { label: '订阅入账', tone: 'success' },
    ADJUST: { label: '调整', tone: 'info' },
    TEST: { label: '测试', tone: 'info' }
  };
  return map[t] || { label: t || '--', tone: 'info' };
};
/** 既有语义色 → 统一药丸四组（契约 §2.6：一屏只有一种药丸形态） */
const pillOf = (tone?: string): string =>
  tone === 'success' ? 'ui-pill--pos' : tone === 'warning' ? 'ui-pill--warn' : tone === 'danger' ? 'ui-pill--neg' : 'ui-pill--muted';

const loadPlans = async () => {
  loading.value = true;
  try {
    const res: any = await getCreditPlans();
    plans.value = res.data?.plans || [];
    summary.value = res.data?.summary || summary.value;
    consumeMode.value = res.data?.consumeMode || 'WALLET';
    planActiveCounts.value = res.data?.planActiveCounts || {};
  } finally {
    loading.value = false;
  }
};

const loadLedger = async () => {
  ledgerLoading.value = true;
  try {
    const res: any = await listCreditLedger({
      bizType: ledgerBiz.value || undefined,
      beginTime: ledgerRange.value?.[0] || undefined,
      endTime: ledgerRange.value?.[1] || undefined,
      // 业务批8 Q6 双视角：只看我的=后端按登录人过滤；全景可叠加按人筛选
      onlyMine: ledgerView.value === 'mine' || undefined,
      userId: ledgerView.value === 'tenant' ? ledgerUserId.value || undefined : undefined,
      pageNum: ledgerPage.pageNum,
      pageSize: ledgerPage.pageSize
    });
    ledgerRows.value = res.data?.rows || [];
    ledgerTotal.value = Number(res.data?.total || 0);
  } finally {
    ledgerLoading.value = false;
  }
};

/** 业务批8 Q6：视角切换——回第一页并同步人员下拉数据 */
const onSwitchView = () => {
  ledgerUserId.value = null;
  onSearch();
  void loadLedgerUsers();
};

/** AI 缺口批 Q7（2026-10-14）：按当前筛选导出积分流水 Excel（与 /ledger 同口径） */
const exportLedger = () => {
  proxy?.download(
    '/dcs/ai-credit/ledger/export',
    {
      bizType: ledgerBiz.value || undefined,
      beginTime: ledgerRange.value?.[0] || undefined,
      endTime: ledgerRange.value?.[1] || undefined,
      onlyMine: ledgerView.value === 'mine' || undefined,
      userId: ledgerView.value === 'tenant' ? ledgerUserId.value || undefined : undefined
    },
    `积分流水_${new Date().getTime()}.xlsx`
  );
};

const loadLedgerUsers = async () => {
  const res: any = await listLedgerUsers();
  ledgerUsers.value = res.data || [];
};

/** 筛选条件变化：翻回第一页再查 */
const onSearch = () => {
  ledgerPage.pageNum = 1;
  void loadLedger();
};

const resetLedgerFilter = () => {
  ledgerBiz.value = '';
  ledgerRange.value = null;
  onSearch();
};

/**
 * 业务批8 Q3：订阅入账行的真实量展示——SUBSCRIBE 行 credits=0（量在 remark
 * 「订阅 XX（25000 积分/周 × 12 周）」里，weekly/tank 拆分列语义是扣减来源不能占用），
 * 前端从 remark 正则提取；解析不出返回 null 显示「—」。
 */
const subscribeAmountOf = (row: CreditLedgerVO): { perWeek: number; weeks: number } | null => {
  const m = (row.remark || '').match(/([\d,]+)\s*积分\/周\s*×\s*(\d+)\s*周/);
  if (!m) return null;
  return { perWeek: Number(m[1].replace(/,/g, '')), weeks: Number(m[2]) };
};

/**
 * 业务批7 Q4：场景串中文化——内部标识 assist:chat:seq42|sidecar:followup|est 不再直出。
 * 形态：AI 对话 · 第42轮 / 追问建议 · 第42轮 / 会话起名 · 第1轮（估算行如实标注）
 */
const sceneLabel = (row: CreditLedgerVO): string => {
  const s = row.scene;
  if (!s) {
    return row.bizType === 'SUBSCRIBE' ? '订阅' : row.bizType === 'BOOST' ? '加油包' : '--';
  }
  const est = s.includes('|est');
  const base = s.replace(/\|est/g, '');
  const seqMatch = base.match(/^assist:chat:seq(\d+)/);
  const seq = seqMatch ? ` · 第${seqMatch[1]}轮` : '';
  let name = 'AI 对话';
  if (base.includes('sidecar:followup')) {
    name = '追问建议';
  } else if (base.includes('sidecar:title')) {
    name = '会话起名';
  } else if (base.includes('sidecar')) {
    name = '附带小任务';
  }
  return `${name}${seq}${est ? '（估算）' : ''}`;
};

const reloadAll = () => {
  loadPlans();
  loadLedger();
  void loadLedgerUsers();
  void loadModelRates();
};

const loadModelRates = async () => {
  const res: any = await getModelRates();
  modelRates.value = res.data || [];
};

/** 业务批(1015) Q12：presets 中文名目录（logo 落名与 ai-config 厂商中心同源） */
const providerPresets = ref<DcsAiProviderPresetVO[]>([]);
const providerNameOf = (p: string) => providerPresets.value.find((x) => x.provider === p)?.providerName || p;
const loadProviderPresets = async () => {
  try {
    const res: any = await listDcsAiProviderPresets();
    providerPresets.value = res.data || [];
  } catch {
    /* 目录拉不到时降级显示厂商代码 */
  }
};
/** 厂商分组 + 组内排序（默认 → 免费 → 倍率升序）；含默认的组置顶 */
interface RateGroup {
  provider: string;
  rows: typeof modelRates.value;
  hasDefault: boolean;
}
const rateGroups = computed<RateGroup[]>(() => {
  const map = new Map<string, typeof modelRates.value>();
  for (const r of modelRates.value) {
    const list = map.get(r.provider) ?? [];
    list.push(r);
    map.set(r.provider, list);
  }
  const groups = [...map.entries()].map(([provider, rows]) => {
    rows.sort(
      (a, b) => (b.isDefault ? 1 : 0) - (a.isDefault ? 1 : 0) || (a.free ? 0 : 1) - (b.free ? 0 : 1) || (a.multiplier ?? 0) - (b.multiplier ?? 0)
    );
    return { provider, rows, hasDefault: rows.some((r) => r.isDefault) };
  });
  groups.sort((a, b) => (b.hasDefault ? 1 : 0) - (a.hasDefault ? 1 : 0) || a.provider.localeCompare(b.provider));
  return groups;
});
/** Q12 折叠初始化：无默认模型的组自动收起（仅首次加载，用户开合过的组不覆盖）——
 *  挂在 rateGroups 定义之后（见上方 TDZ 警示注释） */
watch(
  rateGroups,
  (gs) => {
    for (const g of gs) {
      if (!g.hasDefault && !rateTouched.has(g.provider)) {
        rateCollapsed.add(g.provider);
      }
    }
  },
  { immediate: true }
);

/** 业务批8 Q7 丁：消费方式切换——radio-group 的 change 直接换目标态再确认，取消则回显旧值 */
const onSwitchMode = async (mode: 'WALLET' | 'CREDIT') => {
  const from = consumeMode.value;
  if (mode === from) return;
  const effect =
    mode === 'CREDIT'
      ? '切换后助手对话消耗积分（周池/加油包），不再扣租户钱包。'
      : '切换后助手对话按实际用量扣租户钱包（元），积分池暂停消耗、周池照常每周恢复。';
  try {
    await proxy?.$modal.confirm(
      `确认把消费方式从「${from === 'CREDIT' ? '积分订阅' : '钱包按量'}」切到「${mode === 'CREDIT' ? '积分订阅' : '钱包按量'}」？\n${effect}`
    );
  } catch {
    return; // 取消：model-value 未绑定 v-model，回显不变
  }
  try {
    await setBillingMode(mode);
    proxy?.$modal.msgSuccess(`消费方式已切换为${mode === 'CREDIT' ? '积分订阅' : '钱包按量'}`);
  } finally {
    // dspro P2-7：失败（409 无订阅/网络抖动）也要回读服务端真值——EP radio 勾选态
    // 停在被点击项会与服务端不一致，受控回显靠 loadPlans 刷新 consumeMode 纠偏
    loadPlans();
  }
};

const onSubscribe = async (p: CreditPlanVO, grant = false) => {
  // step5 P2-4 回修：confirm 取消的 reject 必须吞掉（否则 unhandledrejection 控制台噪音）
  try {
    if (grant) {
      await proxy?.$modal.confirm(`确认为当前租户赠送开通「${p.planName}」（不扣钱包，每周 ${fmt(p.weeklyCredits)} 积分）？`);
    } else {
      await proxy?.$modal.confirm(
        `确认为当前租户订阅「${p.planName}」（¥${Number(p.priceYuan).toFixed(0)} / ${p.durationWeeks} 周，每周 ${fmt(p.weeklyCredits)} 积分）？\n· 费用将从租户钱包扣除 ¥${Number(p.priceYuan).toFixed(0)}（余额不足会提示，不会半单）\n· 订阅后助手对话自动切换为积分消费，可随时切回钱包按量`
      );
    }
  } catch {
    return;
  }
  busy.value = p.planCode;
  try {
    await subscribeCreditPlan(p.planCode, grant);
    proxy?.$modal.msgSuccess(grant ? `已赠送开通 ${p.planName}` : `已订阅 ${p.planName}（钱包已扣款）`);
    reloadAll();
  } finally {
    busy.value = null;
  }
};

const onBoost = async (grant = false) => {
  const p = boostPlan.value;
  if (!p) return;
  try {
    if (grant) {
      await proxy?.$modal.confirm(`确认赠送「${p.planName}」（不扣钱包，一次性 +${fmt(p.weeklyCredits)} 积分）？`);
    } else if (consumeMode.value !== 'CREDIT') {
      // dspro P1-3：钱包按量下买加油包=积分不会被消耗——确认弹窗明示，防「¥99 买到不生效权益」
      await proxy?.$modal.confirm(
        `当前消费方式是「钱包按量」，购买的 ${fmt(p.weeklyCredits)} 积分要等订阅套餐并切到积分消费后才会被使用。\n确认仍要购买「${p.planName}」（扣钱包 ¥${Number(p.priceYuan).toFixed(0)}）？`
      );
    } else {
      await proxy?.$modal.confirm(
        `确认购买「${p.planName}」（¥${Number(p.priceYuan).toFixed(0)}，一次性 +${fmt(p.weeklyCredits)} 积分，不受窗口限制）？\n· 费用将从租户钱包扣除 ¥${Number(p.priceYuan).toFixed(0)}`
      );
    }
  } catch {
    return;
  }
  busy.value = 'boost';
  try {
    await boostCreditPlan(p.planCode, grant);
    proxy?.$modal.msgSuccess(grant ? '加油包已赠送到账' : '加油包已到账（钱包已扣款）');
    reloadAll();
  } finally {
    busy.value = null;
  }
};

/** 业务批8 Q4：打开编辑弹窗（拷贝一份，取消不污染卡片数据） */
const openPlanEdit = (p: CreditPlanVO) => {
  planEdit.form = {
    id: p.id,
    planCode: p.planCode,
    kind: p.kind,
    planName: p.planName,
    priceYuan: Number(p.priceYuan),
    weeklyCredits: p.weeklyCredits,
    windowCredits: p.windowCredits,
    windowHours: p.windowHours,
    durationWeeks: p.durationWeeks,
    websearchLimitPerUserDaily: p.websearchLimitPerUserDaily,
    description: p.description
  };
  planEdit.enabled = p.enabled !== '0';
  planEdit.activeCount = planActiveCounts.value[p.planCode] || 0;
  planEdit.visible = true;
};

const onSavePlan = async () => {
  planEdit.saving = true;
  try {
    await saveCreditPlan(planEdit.form);
    // step5 P2-5：在售状态走独立接口，保存字段后按需同步
    if (planEdit.form.id) {
      const target = planEdit.enabled ? '1' : '0';
      const current = plans.value.find((x) => x.id === planEdit.form.id)?.enabled;
      if (current !== undefined && current !== target) {
        await changeCreditPlanStatus(planEdit.form.id, target as '0' | '1');
      }
    }
    proxy?.$modal.msgSuccess(`套餐「${planEdit.form.planCode}」已保存`);
    planEdit.visible = false;
    reloadAll();
  } finally {
    planEdit.saving = false;
  }
};

onMounted(() => {
  reloadAll();
  // 厂商目录是与租户无关的稳定全集：挂载拉一次即可，刷新按钮不再重复拉（m3 P2 回修）
  void loadProviderPresets();
});
</script>

<style scoped>
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
/* 壳层留档批 题1A：手机档卡头竖排——横排时标题与「消费方式」开关抢宽，
   「当前状态」「消费方式」双双断字成「当前状/态」「消费方/式」；竖排后标题一行读完、开关整行挪下 */
@media (max-width: 760px) {
  .card-head {
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
  }
}
/* 业务批8 Q7 丁：消费方式开关（radio 非受控回弹——取消确认时 model-value 未变自动回显旧档） */
.mode-switch {
  display: flex;
  align-items: center;
  gap: 8px;
}
.mode-label {
  font-size: 12px;
  color: var(--app-muted);
}
.mode-tip {
  color: var(--app-faint);
  font-size: 14px;
}
.sub-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.sub-hint {
  font-size: 12px;
  color: var(--app-muted);
}
.head-sub {
  font-size: 12px;
  color: var(--app-faint);
  font-weight: 400;
}
.status-card :deep(.el-card__body) {
  padding-top: 12px;
}
.ledger-box {
  border: 1px solid var(--app-rule);
  border-radius: var(--app-card-radius);
  padding: 14px 16px;
  height: 100%;
}
.ledger-title {
  font-size: 13px;
  color: var(--app-muted);
  margin-bottom: 6px;
}
.ledger-num {
  font-size: 22px;
  font-weight: 600;
  color: var(--app-text);
  margin-bottom: 8px;
  /* 六审回修（视觉席）：数字列 tabular-nums——比例数字下 1/4/7 宽窄不一，刷新后数字抖动 */
  font-variant-numeric: tabular-nums;
}
.ledger-unit {
  font-size: 12px;
  color: var(--app-faint);
  font-weight: 400;
}
.ledger-hint {
  font-size: 12px;
  color: var(--app-faint);
  margin-top: 8px;
}
.plan-card {
  position: relative;
  border: 1px solid var(--app-border);
  border-radius: var(--app-card-radius);
  padding: 18px 18px 16px;
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  /* 业务反馈批1015b Q3：三卡等高（el-row 列默认等高拉伸，这里接住），按钮经 plan-features:flex:1 贴底 */
  height: calc(100% - 16px);
}
.plan-card.current {
  border-color: var(--app-control);
  background: var(--app-raised);
}
/* step5 P2-5：下架行置灰（仅超管可见——租管列表不含 enabled=0 行） */
.plan-card.off {
  opacity: 0.62;
  filter: grayscale(0.5);
}
.plan-off-tag {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 3;
}
.plan-name {
  font-size: 15px;
  font-weight: 600;
}
.plan-price {
  font-size: 24px;
  font-weight: 700;
  margin: 8px 0 10px;
  color: var(--app-text);
}
.plan-per {
  font-size: 12px;
  color: var(--app-faint);
  font-weight: 400;
}
.plan-total {
  /* Q12：整周期总额——中性信息条，介于价格与功能列表之间 */
  background: var(--app-raised);
  color: var(--app-muted);
  border-radius: var(--app-control-radius);
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 8px;
  align-self: flex-start;
}
.plan-features {
  list-style: none;
  margin: 0 0 12px;
  padding: 0;
  flex: 1;
}
.plan-features li {
  font-size: 12px;
  color: var(--app-muted);
  line-height: 1.9;
}
.plan-features li::before {
  content: '·';
  margin-right: 6px;
  color: var(--app-faint);
  font-weight: 700;
}
.plan-buy {
  align-self: flex-start;
}
/* 业务批8 Q4：订阅/赠送/改价按钮组 + 在订数提示 */
.plan-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.plan-active-n {
  margin-top: 8px;
  font-size: 11px;
  color: var(--dcs-color-warning);
}
.boost-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.edit-alert {
  margin-bottom: 14px;
}
.edit-ro-hint {
  margin-left: 8px;
  font-size: 11px;
  color: var(--app-faint);
}
/* 业务批8 Q2：倍率档位徽章统一走 .ui-pill 四组语义，不再自造蓝色阶（契约 §2.6） */
.rate-star {
  margin-left: 6px;
}
/* 业务批(1015) Q12：厂商分组卡——组头 logo+中文名+模型数；组内行=模型名+倍率徽章+预估 */
.rate-groups {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.rate-group {
  border: 1px solid var(--app-rule);
  border-radius: var(--app-card-radius);
  overflow: hidden;
}
.rate-ghead {
  /* Q12：组头改为可点按钮（折叠开关）——button 归零样式后沿用原视觉 */
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--app-raised);
  border: none;
  cursor: pointer;
  font: inherit;
  text-align: left;

  &:hover b {
    color: var(--app-text);
  }
  .rate-garrow {
    margin-left: 4px;
    color: var(--app-faint);
    font-size: 12px;
    transition: transform 0.15s;

    &.open {
      transform: rotate(90deg);
    }
  }
  b {
    font-size: 13px;
  }
  .rate-gcount {
    font-size: 12px;
    color: var(--app-faint);
  }
  /* 「含默认」走统一药丸，这里只负责推到行尾 */
  .rate-gdef {
    margin-left: auto;
  }
}
/* Q12：组内改三列 grid 严格对齐（列头与数据行同模板），右列右对齐 */
.rate-cols,
.rate-grow {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) 92px minmax(80px, 0.7fr);
  align-items: center;
  column-gap: 10px;
}
.rate-cols {
  padding: 6px 12px 2px;
  font-size: 12px;
  color: var(--app-faint);
  border-bottom: 1px solid var(--app-rule);

  .ta-r {
    text-align: right;
  }
}
.rate-gbody {
  padding: 2px 12px 6px;
}
.rate-grow {
  padding: 7px 0;
  border-bottom: 1px solid var(--app-rule);

  &:last-child {
    border-bottom: none;
  }
  .rate-gname {
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 6px;
    overflow: hidden;
  }
  .rate-gcost {
    font-size: 12px;
    color: var(--app-muted);
    text-align: right;
    font-variant-numeric: tabular-nums;
  }
}
/* Q8/Q11：钱包模式引导条 */
.wallet-lead {
  margin: 10px 0 2px;
}
.plan-cur {
  position: absolute;
  top: 12px;
  right: 12px;
}
.ledger-filter {
  display: flex;
  /* step5 P2-2 回修：≤800px 视口四件套挤一行会把重置按钮挤出卡片——允许换行 */
  flex-wrap: wrap;
  row-gap: 8px;
  gap: 8px;
  align-items: center;
}
.ded-num {
  /* 业务批8 Q3：扣分从主色蓝改红——与钱包流水页「支出红/收入绿」统一（此前两页打架） */
  color: var(--dcs-color-danger);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.add-num {
  color: var(--dcs-color-success);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.add-sub {
  font-size: 11px;
  color: var(--app-faint);
  font-weight: 400;
}
.add-none {
  color: var(--app-faint);
}
/* ---- 业务批8 Q4：套餐卡分档样式 ---- */
/* Q3：撤 scale(1.03)——放大让中间卡视觉高出 3%（322/324/346 实测的主因之一）；
   box-sizing:border-box 下加粗边框不增外高，等高不受影响。细线 1px，无投影。 */
.plan-card.hot {
  border: 1px solid var(--app-primary);
  z-index: 2;
}
/* 「最受欢迎」走统一药丸；本类只负责悬浮定位 */
.plan-hot-badge {
  position: absolute;
  top: -10px;
  left: 50%;
  transform: translateX(-50%);
  white-space: nowrap;
}
/* 档位名统一主文字色：不再用三阶蓝表达档次（契约 §2.6 禁止自造蓝阶） */
.plan-name-0,
.plan-name-1,
.plan-name-2 {
  color: var(--app-text);
}
.plan-who {
  font-size: 12px;
  color: var(--app-muted);
  background: var(--app-raised);
  border-radius: var(--app-control-radius);
  padding: 3px 8px;
  display: inline-block;
  margin-bottom: 8px;
}
.plan-tech {
  font-size: 11px;
  color: var(--app-faint);
  border-top: 1px solid var(--app-rule);
  padding-top: 6px;
  margin-bottom: 10px;
}
/* step5 P2-D5：轮数折算口径的灰字标注 */
.rounds-basis {
  font-size: 11px;
  color: var(--app-faint);
}
/* 加油包窄条：中性容器，颜色只留给药丸与数据 */
.boost-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid var(--app-rule);
  background: var(--app-raised);
  border-radius: var(--app-card-radius);
  padding: 10px 16px;
}
.boost-main {
  display: flex;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}
.boost-name {
  font-weight: 600;
  color: var(--app-text);
}
.boost-price {
  font-size: 18px;
  font-weight: 700;
  color: var(--app-text);
  font-variant-numeric: tabular-nums;
}
.boost-qty {
  font-size: 12px;
  color: var(--app-muted);
}
.boost-desc {
  flex: 1;
  font-size: 12px;
  color: var(--app-muted);
}
/* ---- 业务批8 Q3：流水汇总条（连通 KPI 带，数字不染色） ---- */
.ledger-summary {
  margin: 12px 16px;
}
/* step5 P1-D1：「只看我的」视角下汇总三格隐藏后的指路灰字 */
.ledger-summary-mine {
  margin: 12px 16px;
  font-size: 12px;
  color: var(--app-muted);
}
/* 375：流水表在容器内横滚，父链 min-width:0 防整页溢出 */
.ledger-table-wrap {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
}
.ledger-table-wrap :deep(.el-table) {
  /* 列宽之和：外层容器一处横滚，避免与 el-table 内部滚动条叠加 */
  min-width: 910px;
  /* 只覆盖 EP 表格 CSS 变量，不重写组件（契约 §2.5） */
  --el-table-header-bg-color: transparent;
  --el-table-tr-bg-color: var(--app-surface);
  --el-table-row-hover-bg-color: var(--app-raised);
  --el-table-border-color: var(--app-rule);
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
}
/* 业务批7 Q4：扣减构成小字 + 分页条 */
.credit-src {
  font-size: 11px;
  color: var(--app-faint);
  font-weight: 400;
  line-height: 1.5;
}
.ledger-pager {
  display: flex;
  justify-content: flex-end;
  padding: 0 16px 12px;
}
</style>
