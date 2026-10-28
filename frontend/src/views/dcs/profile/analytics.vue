<template>
  <div class="app-container analytics-page">
    <dcs-operations-page-header
      eyebrow="画像与分析"
      title="数据分析中心"
      subtitle="事件账本 → 会话重建 → 日汇总 → 漏斗/留存/下钻。任何业务系统的操作事件都能接进来分析。"
    >
      <template #actions>
        <el-select v-if="!noRealSource && sourceOpts.length > 1" v-model="source" class="source-select" @change="onSourceChange">
          <el-option v-for="s in sourceOpts" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select v-model="daysMode" class="days-select" @change="onDaysModeChange">
          <el-option label="近 7 天" value="7" />
          <el-option label="近 30 天" value="30" />
          <el-option label="近 90 天" value="90" />
          <el-option label="自定义" value="custom" />
        </el-select>
        <el-input-number
          v-if="daysMode === 'custom'"
          v-model="days"
          :min="1"
          :max="365"
          :step="1"
          size="default"
          controls-position="right"
          style="width: 110px; margin-left: 8px"
          @change="onDaysChange"
        />
        <el-button v-hasPermi="['analytics:center:edit']" :loading="rebuilding" @click="rebuild"> 重建汇总 </el-button>
        <el-button :loading="loading" @click="reloadCurrent">刷新</el-button>
      </template>
    </dcs-operations-page-header>

    <!-- 第二批: 空态=完整接入示例卡(题7), 告诉用户数据从哪来、怎么接;
         三审三席 P1: 旧「来源被停用」分支指向已随来源管理页签退役的启用入口(死路)——整块删除 -->
    <AccessGuideCard v-if="noRealSource" class="analytics-empty-guide" />
    <!-- 无测试发送权限者给「找管理员」脚注(三审 dsflash P2: 卡内步骤②指引走不通时要兜底) -->
    <p v-if="noRealSource && !canTestSend" class="analytics-empty-guide__hint analytics-empty-guide__hint--block">
      数据接入由管理员在「引擎与运维 → 引擎运维」里配置
    </p>
    <div class="meta-line">
      <el-tag effect="plain" type="info">数据源 {{ source }}</el-tag>
      <span class="muted">统计区间 {{ overview.from || '—' }} ~ {{ overview.to || '—' }}</span>
      <span class="muted">口径：<span class="trend-seg">使用次数=全部操作事件 ·</span> <span class="trend-seg">活跃用户=去重人员 ·</span> <span class="trend-seg">会话=一次连续使用 ·</span> <span class="trend-seg">单页离开=只做一件事就走</span></span>
    </div>

    <el-tabs v-model="tab" class="analytics-tabs" @tab-change="onTabChange">
      <!-- ---------------------------------------------------------- 数据导入（2026-10-23 拍板：移除外部网站接入，仅保留 CSV 补录与全域总览） -->
      <el-tab-pane label="数据导入" name="collect" lazy>
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head"><span>CSV 补录</span><span class="muted">离线事件导入 · 最多 5000 行</span></div>
          </template>
          <p class="muted small">
            表头需包含
            <code>eventName</code
            >，可选列：eventUid、subjectType、subjectId、anonymousId、sessionId、occurredTime、pageUrl、keyword、referrer、channel、deviceType、os、browser、country、province、city、props。
          </p>
          <el-input
            v-model="csvText"
            type="textarea"
            :rows="5"
            placeholder="eventName,anonymousId,sessionId,occurredTime,pageUrl&#10;page_view,demo-visitor,demo-session,2026-10-19 10:00:00,/home"
          />
          <div class="import-actions">
            <el-button @click="fillCsvSample">填入示例</el-button>
            <el-button @click="csvText = ''">清空</el-button>
            <el-button v-hasPermi="['analytics:center:edit']" type="primary" :loading="importing" @click="submitCsv">导入</el-button>
          </div>
        </el-card>

        <el-card shadow="never" class="panel-card" v-loading="crossLoading">
          <template #header>
            <div class="panel-head">
              <span>全域总览</span>
              <span class="muted">近 {{ crossData?.days ?? 30 }} 天 · 跨业务源直读事件账本</span>
            </div>
          </template>
          <div class="ui-kpi-strip analytics-kpi" v-if="crossData?.sources?.length">
            <div class="ui-kpi" v-for="s in crossData.sources" :key="s.source">
              <span class="ui-kpi__label">{{ s.source }} 事件</span>
              <strong class="ui-kpi__value">{{ formatNumber(s.events) }}</strong>
              <span class="ui-kpi__hint">人数 {{ formatNumber(s.visitors) }} · 最近写入 {{ formatLastAt(s.lastAt) }}</span>
            </div>
          </div>
          <el-alert v-if="loadErrors.cross" type="error" show-icon :closable="false" title="加载失败——服务可能不可用" class="load-error">
            <el-button size="small" type="primary" @click="retryFailed">重试</el-button>
          </el-alert>
          <el-empty v-else-if="!crossData?.sources?.length" :image-size="50" description="还没有任何业务源写入事件" />
          <p class="muted small">画像引擎按统一事件协议接收任意业务源（网站 / 订单 / 供应链…）；每个源独立统计互不混算。</p>
        </el-card>
      </el-tab-pane>
      <!-- ---------------------------------------------------------- 使用概览（E 片单源重构：访客流→系统使用流） -->
      <el-tab-pane label="使用概览" name="overview">
        <el-alert v-if="loadErrors.overview || loadErrors.adoption" type="error" show-icon :closable="false" title="加载失败——服务可能不可用" class="load-error">
          <el-button size="small" type="primary" @click="retryFailed">重试</el-button>
        </el-alert>
        <!-- E 片单源重构: 口径升级说明——统计对象从"网站访客流"换成"系统统一事件流",
             PV/页面类指标没接页面埋点时恒 0, 必须先说清否则像坏了 -->
        <el-alert type="info" show-icon :closable="true" class="caliber-note" title="口径说明">
          <template #default>
            本页按「统一事件流」统计系统的全部使用行为（登录、提交、审核、任务、页面浏览等）。页面浏览类指标（PV、页面与搜索、路径分析）只对接入了页面埋点的数据源有数，未接入时显示 0 属正常。
          </template>
        </el-alert>
  <div v-loading="overviewLoading" class="ui-kpi-strip analytics-kpi analytics-kpi--six">
          <div class="ui-kpi">
            <span class="ui-kpi__label">周活（近 7 天）</span>
            <strong class="ui-kpi__value">{{ formatNumber(adoption?.weeklyActive) }}</strong>
            <span class="ui-kpi__hint">近 7 天用过系统的人数</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">新同事（近 7 天）</span>
            <strong class="ui-kpi__value">{{ formatNumber(adoption?.newUsers) }}</strong>
            <span class="ui-kpi__hint">首周激活 {{ formatNumber(adoption?.activated) }} 人 · {{ activationRateText }}</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">使用次数</span>
            <strong class="ui-kpi__value">{{ formatNumber(overview.current?.events) }}</strong>
            <span class="ui-kpi__hint delta" :class="deltaTone('events')">{{ deltaText('events') }}</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">活跃用户</span>
            <strong class="ui-kpi__value">{{ formatNumber(overview.current?.uv) }}</strong>
            <span class="ui-kpi__hint delta" :class="deltaTone('uv')">{{ deltaText('uv') }}</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">单页离开率</span>
            <strong class="ui-kpi__value">{{ formatPercent(overview.current?.bounceRate) }}</strong>
            <span class="ui-kpi__hint delta" :class="deltaTone('bounceRate')">{{ deltaText('bounceRate') }}</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">平均停留</span>
            <strong class="ui-kpi__value">{{ formatDuration(overview.current?.avgDuration) }}</strong>
            <span class="ui-kpi__hint delta" :class="deltaTone('avgDuration')">{{ deltaText('avgDuration') }}</span>
          </div>
        </div>

        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>使用趋势</span>
              <span class="muted trend-sub" v-if="overview.current">
                <!-- P8④(2026-10-27 拍板A)：按「·」分段 nowrap，窄屏整段换行、段内不断，不再拆出「531」孤行；
                     分隔符随上一段走（并进 span），避免折行后「·」孤悬行首（四席复审 P1-4） -->
                <span class="trend-seg">会话 {{ formatNumber(overview.current?.visits) }} ·</span>
                <span class="trend-seg">次均操作 {{ avgActionsText }} ·</span>
                <span class="trend-seg">新用户 {{ formatNumber(overview.current?.newVisitors) }} ·</span>
                <span class="trend-seg">老用户 {{ formatNumber(overview.current?.returningVisitors) }}</span>
              </span>
            </div>
          </template>
          <v-chart v-if="hasOverviewData" class="trend-chart" :option="trendOption" autoresize />
          <el-empty v-else-if="!loadErrors.overview" :image-size="60" description="该源在窗口期内还没有事件数据">
            <el-button type="primary" link @click="tab = 'collect'">去导入数据</el-button>
          </el-empty>
        </el-card>

        <!-- 五审回修: 拆解错误条移出 v-for——四张卡只报一条, 不再每卡重复 -->
        <el-alert v-if="loadErrors.breakdowns" type="error" show-icon :closable="false" title="加载失败——服务可能不可用" class="load-error">
          <el-button size="small" type="primary" @click="retryFailed">重试</el-button>
        </el-alert>
        <el-row :gutter="14" class="breakdown-row">
          <el-col v-for="block in breakdownBlocks" :key="block.type" :xs="24" :lg="12">
  <el-card shadow="never" class="panel-card" v-loading="breakdownLoading">
              <template #header>
                <div class="panel-head">
                  <span>{{ block.title }}</span>
                  <span class="muted">{{ block.hint }} · 点击行下钻使用明细</span>
                </div>
              </template>
              <el-table class="ui-table drill-table" :data="breakdowns[block.type] || []" :show-header="true" @row-click="(row: AnalyticsBreakdownRow) => drillToSessions(block.type, row)">
                <el-table-column prop="label" :label="block.label" min-width="140" show-overflow-tooltip />
                <el-table-column prop="events" label="次数" width="76" align="right" />
                <el-table-column prop="uv" label="人数" width="76" align="right" />
                <el-table-column prop="visits" label="会话" width="76" align="right" />
                <el-table-column label="占比" width="130">
                  <template #default="{ row }">
                    <div class="share-cell">
                      <el-progress :percentage="clamp(Number(row.share))" :show-text="false" :stroke-width="6" />
                      <span class="share-text">{{ formatPercent(row.share) }}</span>
                    </div>
                  </template>
                </el-table-column>
                <template #empty>
                  <el-empty :image-size="50" description="暂无数据">
                    <el-button type="primary" link @click="tab = 'collect'">去导入数据</el-button>
                  </el-empty>
                </template>
              </el-table>
            </el-card>
          </el-col>
        </el-row>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 页面与搜索 -->
  <el-tab-pane label="页面与搜索" name="page" lazy>
        <el-alert v-if="loadErrors.page" type="error" show-icon :closable="false" title="加载失败——服务可能不可用" class="load-error">
          <el-button size="small" type="primary" @click="retryFailed">重试</el-button>
        </el-alert>
        <el-row :gutter="14">
          <el-col :xs="24" :lg="14">
            <el-card shadow="never" class="panel-card" v-loading="pageLoading">
              <template #header>
                <div class="panel-head">
                  <span>页面分析</span>
                  <span class="muted">按 page_view 统计</span>
                </div>
              </template>
              <el-table class="ui-table" :data="pageRows">
                <el-table-column prop="label" label="页面" min-width="180" show-overflow-tooltip />
                <el-table-column prop="pv" label="PV" width="80" align="right" />
                <el-table-column prop="uv" label="UV" width="80" align="right" />
                <el-table-column prop="visits" label="访问" width="80" align="right" />
                <el-table-column label="PV 占比" width="140">
                  <template #default="{ row }">
                    <div class="share-cell">
                      <el-progress :percentage="clamp(Number(row.share))" :show-text="false" :stroke-width="6" />
                      <span class="share-text">{{ formatPercent(row.share) }}</span>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
          <el-col :xs="24" :lg="10">
            <el-card shadow="never" class="panel-card" v-loading="pageLoading">
              <template #header>
                <div class="panel-head">
                  <span>搜索词</span>
                  <span class="muted">search / keyword</span>
                </div>
              </template>
              <el-table class="ui-table" :data="searchRows">
                <el-table-column prop="label" label="关键词" min-width="120" show-overflow-tooltip />
                <el-table-column prop="pv" label="次数" width="70" align="right" />
                <el-table-column prop="uv" label="人数" width="70" align="right" />
                <el-table-column prop="share" label="占比" width="86" align="right">
                  <template #default="{ row }">{{ formatPercent(row.share) }}</template>
                </el-table-column>
                <template #empty>
                  <el-empty :image-size="50" description="暂无搜索事件">
                    <el-button type="primary" link @click="tab = 'collect'">去导入数据</el-button>
                  </el-empty>
                </template>
              </el-table>
            </el-card>
          </el-col>
        </el-row>

        <el-card shadow="never" class="panel-card" v-loading="pageLoading">
          <template #header>
            <div class="panel-head">
              <span>事件分布</span>
              <span class="muted">全部已接入事件</span>
            </div>
          </template>
          <el-table class="ui-table" :data="eventRows">
            <el-table-column prop="label" label="事件" min-width="160" />
            <el-table-column prop="events" label="事件数" width="100" align="right" />
            <el-table-column prop="uv" label="触达人数" width="100" align="right" />
            <el-table-column prop="visits" label="会话数" width="100" align="right" />
            <el-table-column label="事件占比" width="180">
              <template #default="{ row }">
                <div class="share-cell">
                  <el-progress :percentage="clamp(Number(row.share))" :show-text="false" :stroke-width="6" />
                  <span class="share-text">{{ formatPercent(row.share) }}</span>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 转化漏斗 -->
      <!-- ---------------------------------------------------------- 路径分析（批8） -->
      <el-tab-pane label="路径分析" name="paths" lazy>
        <el-alert v-if="loadErrors.paths" type="error" show-icon :closable="false" title="加载失败——服务可能不可用" class="load-error">
          <el-button size="small" type="primary" @click="retryFailed">重试</el-button>
        </el-alert>
  <el-card shadow="never" class="panel-card" v-loading="pathsLoading">
          <template #header>
            <div class="panel-head">
              <span>用户路径</span>
              <div class="head-actions">
                <el-select v-model="pathStart" placeholder="从哪页出发（默认入口视角）" clearable style="width: 240px" @change="loadPaths">
                  <el-option v-for="e in pathsData?.entries || []" :key="e.page" :label="`${e.page}（${e.visitors} 人）`" :value="e.page" />
                </el-select>
              </div>
            </div>
          </template>
          <v-chart v-if="pathsData?.links?.length" class="path-chart" :option="pathsOption" autoresize />
          <el-empty v-else :image-size="60" description="窗口期内没有可分析的页面路径">
            <el-button type="primary" link @click="tab = 'collect'">去导入数据</el-button>
          </el-empty>
          <p class="muted small">
            口径与转化漏斗一致：每位用户只取首次顺序走过的路径，连续重复页折叠为一步；线越粗走的人越多，鼠标悬停节点可看它通向哪里。带 ·2/·3
            的节点表示该页在第 2/3 步被再次到达（按步分层，再次到达不会成环）。
          </p>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="转化漏斗" name="funnel" lazy>
        <el-alert v-if="loadErrors.funnels || loadErrors.funnelResult || loadErrors.adoption" type="error" show-icon :closable="false" title="加载失败——服务可能不可用" class="load-error">
          <el-button size="small" type="primary" @click="retryFailed">重试</el-button>
        </el-alert>

        <!-- E 片两步上手漏斗: 固定口径(登录→首次核心动作), 不需要用户配置, 进页签即有答案 -->
        <el-card shadow="never" class="panel-card" v-loading="adoptionLoading">
          <template #header>
            <div class="panel-head">
              <span>两步上手</span>
              <span class="muted">近 {{ adoption?.days ?? days }} 天 · 登录 → 首次核心动作</span>
            </div>
          </template>
          <div class="funnel-list">
            <div class="funnel-step">
              <div class="funnel-step-head">
                <div class="funnel-name"><span class="funnel-index">1</span><span>登录过系统</span><code class="mono">user.login</code></div>
                <div class="funnel-numbers"><strong>{{ formatNumber(adoption?.loginUsers) }}</strong><span class="muted">人</span></div>
              </div>
              <div class="funnel-bar"><div class="funnel-bar-inner" :style="{ width: adoption?.loginUsers ? '100%' : '0%' }" /></div>
            </div>
            <div class="funnel-step">
              <div class="funnel-step-head">
                <div class="funnel-name"><span class="funnel-index">2</span><span>完成任一核心动作</span><code class="mono">发布 / 提交 / 审核 / 关闭</code></div>                <div class="funnel-numbers">
                  <strong>{{ formatNumber(adoption?.coreUsers) }}</strong>
                  <span class="muted">人 · 上手率 {{ twoStepRateText }}</span>
                </div>
              </div>
              <div class="funnel-bar"><div class="funnel-bar-inner" :style="{ width: twoStepWidth }" /></div>
            </div>
          </div>
          <p class="muted small" style="margin-top: 8px">
            核心动作名单：发布任务、提交数据、完成审核、关闭任务（审核结果不算提交人的动作）。上手率 = 完成核心动作的人数 ÷
            登录过的人数。首周激活按入职后自然 7 天计，刚入职几天的同事仍在观察期，读数偏低属正常。
          </p>
        </el-card>

        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>转化漏斗</span>
              <div class="head-actions">
                <el-select v-model="funnelId" placeholder="选择漏斗" style="width: 220px" @change="loadFunnelResult">
                  <el-option v-for="f in funnels" :key="f.funnelId" :label="`${f.funnelName} · ${f.windowDays}天`" :value="f.funnelId" />
                </el-select>
                <el-select v-model="funnelBreakdown" style="width: 132px" @change="loadFunnelResult">
                  <el-option label="整体口径" value="" />
                  <el-option label="按设备拆解" value="device" />
                  <el-option label="按系统拆解" value="os" />
                  <el-option label="按浏览器拆解" value="browser" />
                </el-select>
                <el-button v-hasPermi="['analytics:center:edit']" type="primary" @click="openFunnelDialog()">新建漏斗</el-button>
                <el-button v-if="currentFunnel" v-hasPermi="['analytics:center:edit']" @click="openFunnelDialog(currentFunnel)">编辑</el-button>
                <el-button
                  v-if="currentFunnel && currentFunnel.builtin !== '1'"
                  v-hasPermi="['analytics:center:edit']"
                  type="danger"
                  plain
                  @click="removeFunnel"
                >
                  删除
                </el-button>
              </div>
            </div>
          </template>

          <el-empty v-if="!funnels.length && !loadErrors.funnels" :image-size="60" description="当前业务源还没有漏斗">
            <el-button v-hasPermi="['analytics:center:edit']" type="primary" link @click="openFunnelDialog()">去新建漏斗</el-button>
          </el-empty>

          <template v-else-if="funnelResult">
            <div class="funnel-meta">
              <el-tag effect="plain">窗口 {{ funnelResult.windowDays }} 天</el-tag>
              <span class="muted">参与用户 {{ funnelResult.visitors }} 人 · 整体转化 {{ formatPercent(funnelResult.overallRate) }}</span>
            </div>
            <div class="funnel-list">
              <div v-for="(step, index) in funnelResult.steps" :key="`${step.eventName}-${index}`" class="funnel-step">
                <div class="funnel-step-head">
                  <div class="funnel-name">
                    <span class="funnel-index">{{ index + 1 }}</span>
                    <span>{{ step.name || step.eventName }}</span>
                    <code class="mono">{{ step.eventName }}</code>
                  </div>
                  <div class="funnel-numbers">
                    <strong>{{ step.count }}</strong>
                    <span class="muted">人 · 总转化 {{ formatPercent(step.rate) }}</span>
                    <el-tag v-if="index > 0" size="small" :type="Number(step.stepRate) >= 50 ? 'success' : 'warning'" effect="plain">
                      步骤转化 {{ formatPercent(step.stepRate) }}
                    </el-tag>
                    <span v-if="index > 0" class="muted">平均 {{ formatDuration(step.avgSeconds) }}</span>
                  </div>
                </div>
                <div class="funnel-bar">
                  <div class="funnel-bar-inner" :style="{ width: funnelWidth(step.count) }" />
                </div>
              </div>
            </div>
            <template v-if="funnelResult.breakdownGroups?.length">
              <el-table class="ui-table" :data="funnelResult.breakdownGroups" style="margin-top: 14px">
                <el-table-column prop="label" label="拆解组" min-width="120" fixed />
                <el-table-column prop="visitors" label="参与用户" width="96" align="right" />
                <el-table-column v-for="(gs, gi) in funnelResult.steps" :key="`bg-${gi}`" min-width="104" align="center">
                  <template #header>
                    <span>第{{ gi + 1 }}步 {{ gs.name || gs.eventName }}</span>
                  </template>
                  <template #default="{ row }">
                    <span>{{ row.steps?.[gi]?.count ?? 0 }}</span>
                    <span class="muted small">（{{ formatPercent(row.steps?.[gi]?.rate) }}）</span>
                  </template>
                </el-table-column>
                <el-table-column label="整体转化" width="100" align="right">
                  <template #default="{ row }">{{ formatPercent(row.overallRate) }}</template>
                </el-table-column>
              </el-table>
              <div class="muted small" style="margin-top: 6px">
                拆解口径: 按用户首步事件的「{{ funnelBreakdownLabel }}」取值分组(Top10 之外并入「其他」), 各组参与用户之和 = 整体参与用户(每用户只归一组)。
              </div>
            </template>
          </template>
        </el-card>

        <el-card shadow="never" class="panel-card" v-if="funnelLoss && funnelLoss.steps.length > 1">
          <template #header>
            <div class="panel-head">
              <span>流失去向</span>
              <span class="muted">每步流失的人接下来去了哪（Top5）</span>
            </div>
          </template>
          <div v-for="(step, index) in funnelLoss.steps.slice(0, -1)" :key="`loss-${index}`" class="loss-row">
            <div class="loss-head">
              <span class="funnel-index">{{ index + 1 }}</span>
              <span>{{ step.eventName }}</span>
              <el-tag size="small" type="danger" effect="plain">流失 {{ step.lost }} 人</el-tag>
            </div>
            <div v-if="step.next?.length" class="loss-next">
              <el-tag v-for="n in step.next" :key="n.label" size="small" effect="plain" class="loss-tag"> {{ n.label }} · {{ n.count }} </el-tag>
            </div>
            <div v-else class="muted small">本步无流失</div>
          </div>
        </el-card>

        <!-- E 片单源重构: 渠道归因卡整卡退役——"渠道"是外部网站投放概念, 内部系统统一事件流没有渠道字段, 恒空 -->

        <el-card shadow="never" class="panel-card tip-card">
          <div class="tip-copy">
            <strong>怎么读？</strong>
            <p>每一步按用户「首次满足顺序」计数；窗口天数限制相邻两步的最大间隔。跨会话回来超过窗口不累计，避免把隔天回来误算成一次转化。</p>
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 留存 -->
      <el-tab-pane label="持续使用" name="retention" lazy>
        <el-alert v-if="loadErrors.retention" type="error" show-icon :closable="false" title="加载失败——服务可能不可用" class="load-error">
          <el-button size="small" type="primary" @click="retryFailed">重试</el-button>
        </el-alert>
  <el-card shadow="never" class="panel-card" v-loading="retentionLoading">
          <template #header>
            <div class="panel-head">
              <span>持续使用</span>
              <div class="head-actions">
                <span v-if="retentionEffectiveDays > days" class="muted small">近 {{ retentionEffectiveDays }} 天</span>
                <el-select v-model="retentionGranularity" style="width: 92px" @change="onRetentionGranularityChange">
                  <el-option label="按日" value="day" />
                  <el-option label="按周" value="week" />
                  <el-option label="按月" value="month" />
                </el-select>
                <el-select v-model="retentionWindow" style="width: 118px" @change="loadRetention">
                  <el-option v-for="w in retentionWindowOptions" :key="w.value" :label="w.label" :value="w.value" />
                </el-select>
                <el-radio-group v-model="retentionCumulative" size="small" @change="loadRetention">
                  <el-radio-button :value="false">恰好第N{{ retentionUnit }}</el-radio-button>
                  <el-radio-button :value="true">该期或之后</el-radio-button>
                </el-radio-group>
              </div>
            </div>
          </template>
          <template v-if="retention.cohorts?.length">
            <el-table class="ui-table retention-table" :data="retention.cohorts">
              <el-table-column prop="cohort" label="首次使用" width="120" fixed />
              <el-table-column prop="total" label="新增用户" width="96" align="right" />
              <el-table-column v-for="i in retention.window" :key="i" :label="`第 ${i - 1} ${retentionUnit}`" min-width="78" align="center">
                <template #default="{ row }">
                  <div class="retention-cell" :style="retentionStyle(row.cells[i - 1])">
                    <strong>{{ row.cells[i - 1]?.visitors ?? 0 }}</strong>
                    <span>{{ formatPercent(row.cells[i - 1]?.rate) }}</span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
            <div class="retention-legend muted small">
              <span>回来继续用的比例</span>
              <span class="retention-legend__swatch" :style="{ background: alpha(ct.accent, 0.12) }" />
              <span class="retention-legend__swatch" :style="{ background: alpha(ct.accent, 0.35) }" />
              <span class="retention-legend__swatch" :style="{ background: alpha(ct.accent, 0.85) }" />
              <span>越高色越深（没人回来不着色）</span>
            </div>
          </template>
          <el-empty v-else-if="!loadErrors.retention" :image-size="60" description="暂无持续使用数据">
            <el-button type="primary" link @click="tab = 'collect'">去导入数据</el-button>
          </el-empty>
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 访问明细 -->
      <!-- ---------------------------------------------------------- 活跃分析（批8） -->
      <el-tab-pane label="活跃分析" name="activity" lazy>
        <el-alert v-if="loadErrors.activity" type="error" show-icon :closable="false" title="加载失败——服务可能不可用" class="load-error">
          <el-button size="small" type="primary" @click="retryFailed">重试</el-button>
        </el-alert>
  <el-card shadow="never" class="panel-card" v-loading="activityLoading">
          <template #header>
            <div class="panel-head">
              <span>活跃用户</span>
              <span class="muted">DAU/WAU/MAU 滚动窗口 · 粘性 = DAU÷MAU</span>
            </div>
          </template>
          <div class="ui-kpi-strip analytics-kpi">
            <div class="ui-kpi">
              <span class="ui-kpi__label">日活 DAU（最新一天）</span>
              <strong class="ui-kpi__value">{{ formatNumber(activityData?.latest?.dau) }}</strong>
            </div>
            <div class="ui-kpi">
              <span class="ui-kpi__label">周活 WAU（近 7 天）</span>
              <strong class="ui-kpi__value">{{ formatNumber(activityData?.latest?.wau) }}</strong>
            </div>
            <div class="ui-kpi">
              <span class="ui-kpi__label">月活 MAU（近 30 天）</span>
              <strong class="ui-kpi__value">{{ formatNumber(activityData?.latest?.mau) }}</strong>
            </div>
            <div class="ui-kpi">
              <span class="ui-kpi__label">粘性 DAU/MAU</span>
              <strong class="ui-kpi__value">{{ activityData?.latest?.sticky == null ? '—' : formatPercent(activityData.latest.sticky) }}</strong>
            </div>
          </div>
          <v-chart v-if="activityData?.dayList?.length" class="trend-chart" :option="activityOption" autoresize />
          <div class="muted small" style="margin-top: 8px">
            最新一天（{{ activityData?.newVsReturning?.statDate || '—' }}）构成：新用户
            {{ formatNumber(activityData?.newVsReturning?.newVisitors) }} 人 · 老用户
            {{ formatNumber(activityData?.newVsReturning?.returningVisitors) }} 人
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="使用明细" name="sessions" lazy>
        <el-card shadow="never" class="panel-card">
          <div class="filter-bar">
            <el-input v-model="sessionQuery.keyword" placeholder="人员/主体/页面" clearable style="width: 200px" @keyup.enter="searchSessions" />
            <el-select v-model="sessionQuery.deviceType" placeholder="终端" clearable style="width: 120px">
              <el-option label="电脑" value="desktop" />
              <el-option label="手机" value="mobile" />
              <el-option label="平板" value="tablet" />
            </el-select>
            <el-select v-model="sessionQuery.bounce" placeholder="单页离开" clearable style="width: 110px">
              <el-option label="是" value="1" />
              <el-option label="否" value="0" />
            </el-select>
            <el-date-picker
              v-model="sessionRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              style="width: 230px"
              :clearable="true"
              @change="onSessionRangeChange"
            />
            <el-button type="primary" :loading="sessionLoading" @click="searchSessions">查询</el-button>
            <el-button @click="resetSessions">重置</el-button>
            <el-button v-hasPermi="['analytics:center:export']" :loading="exporting" @click="exportSessionsCsv"> 导出 CSV </el-button>
          </div>

          <el-alert v-if="loadErrors.sessions" type="error" show-icon :closable="false" title="加载失败——服务可能不可用" class="load-error">
          <el-button size="small" type="primary" @click="retryFailed">重试</el-button>
        </el-alert>
  <el-table class="ui-table" :data="sessions" v-loading="sessionLoading">
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">当前筛选下没有使用记录</p>
                <p class="ui-empty__hint">调整筛选条件，或确认该业务源已有事件写入</p>
              </div>
            </template>
            <el-table-column prop="visitorId" label="用户" min-width="130" show-overflow-tooltip />
            <el-table-column prop="startedAt" label="开始时间" width="170" />
            <el-table-column prop="entryPage" label="起始页" min-width="140" show-overflow-tooltip />
            <el-table-column prop="exitPage" label="结束页" min-width="140" show-overflow-tooltip />
            <el-table-column prop="pageViews" label="浏览" width="66" align="right" />
            <el-table-column prop="durationSeconds" label="时长" width="88" align="right">
              <template #default="{ row }">{{ formatDuration(row.durationSeconds) }}</template>
            </el-table-column>
            <el-table-column label="新老" width="86">
              <template #default="{ row }">
                <el-tag size="small" :type="row.isNewVisitor === '1' ? 'success' : 'info'" effect="plain">
                  {{ row.isNewVisitor === '1' ? '新' : '老' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="单页离开" width="92">
              <template #default="{ row }">
                <el-tag size="small" :type="row.isBounce === '1' ? 'warning' : 'success'" effect="plain">
                  {{ row.isBounce === '1' ? '单页' : '多步' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openSession(row)">详情</el-button>
                <el-button link type="primary" :disabled="!row.subjectId" @click="goProfile(row.subjectType, row.subjectId)"> 看画像 </el-button>
              </template>
            </el-table-column>
          </el-table>
          <pagination v-model:page="sessionQuery.pageNum" v-model:limit="sessionQuery.pageSize" :total="sessionTotal" @pagination="loadSessions" />
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 自定义分析 -->
      <el-tab-pane label="自定义分析" name="custom" lazy>
        <el-card shadow="never" class="panel-card">
          <div class="filter-bar">
            <el-select v-model="customForm.dimension" style="width: 160px" @change="loadCustom">
              <el-option label="按天" value="day" />
              <el-option label="页面" value="page" />
              <el-option label="事件" value="event" />
              <el-option label="终端" value="device" />
              <el-option label="浏览器" value="browser" />
              <el-option label="操作系统" value="os" />
              <el-option label="起始页" value="entry" />
              <el-option label="结束页" value="exit" />
              <el-option label="搜索词" value="search" />
            </el-select>
            <el-select v-model="customForm.metric" style="width: 140px" @change="loadCustom">
              <el-option label="次数" value="events" />
              <el-option label="人数" value="uv" />
              <el-option label="会话数" value="visits" />
              <el-option label="页面浏览" value="pv" />
            </el-select>
            <el-radio-group v-model="customForm.limit" @change="loadCustom">
              <el-radio-button :value="10">Top 10</el-radio-button>
              <el-radio-button :value="20">Top 20</el-radio-button>
              <el-radio-button :value="50">Top 50</el-radio-button>
            </el-radio-group>
            <el-button type="primary" :loading="customLoading" @click="loadCustom">运行</el-button>
            <el-button v-hasPermi="['analytics:center:export']" :loading="exporting" @click="exportCustomCsv"> 导出 CSV </el-button>
            <el-divider direction="vertical" />
            <el-select v-model="myReportId" placeholder="我的视图" clearable style="width: 190px" @change="applyMyReport">
              <el-option v-for="rep in myReports" :key="rep.reportId" :label="rep.reportName" :value="rep.reportId" />
            </el-select>
            <el-button v-hasPermi="['analytics:report:save']" @click="openSaveReport">保存当前视图</el-button>
            <el-button v-if="myReportId" v-hasPermi="['analytics:report:remove']" type="danger" plain @click="removeMyReport">删除视图</el-button>
          </div>
          <el-alert v-if="loadErrors.custom" type="error" show-icon :closable="false" title="加载失败——服务可能不可用" class="load-error">
          <el-button size="small" type="primary" @click="retryFailed">重试</el-button>
        </el-alert>
  <v-chart class="custom-chart" :option="customOption" autoresize v-loading="customLoading" />
          <el-table :data="customRows" class="ui-table custom-table">
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">暂无自定义分析结果</p>
                <p class="ui-empty__hint">选择维度与指标后点「运行」</p>
              </div>
            </template>
            <el-table-column type="index" label="#" width="56" />
            <el-table-column prop="label" :label="dimensionLabel" min-width="180" show-overflow-tooltip />
            <el-table-column :label="metricLabel" width="120" align="right">
              <template #default="{ row }">{{ formatNumber(row.value) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 实时看板（批8） -->
      <el-tab-pane label="实时看板" name="realtime" lazy>
        <el-alert v-if="loadErrors.realtime" type="error" show-icon :closable="false" title="加载失败——服务可能不可用" class="load-error">
          <el-button size="small" type="primary" @click="retryFailed">重试</el-button>
        </el-alert>
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>近 {{ realtimeData?.windowMinutes ?? 5 }} 分钟</span>
              <div class="head-actions">
                <span class="muted small">每 5 秒自动刷新 · 数据延迟 ≤ 6 秒</span>
                <el-switch v-model="realtimeAuto" active-text="自动" @change="toggleRealtime" />
              </div>
            </div>
          </template>
          <div class="ui-kpi-strip analytics-kpi">
            <div class="ui-kpi">
              <span class="ui-kpi__label">当前活跃</span>
              <strong class="ui-kpi__value">{{ formatNumber(realtimeData?.summary?.visitors) }}</strong>
            </div>
            <div class="ui-kpi">
              <span class="ui-kpi__label">事件</span>
              <strong class="ui-kpi__value">{{ formatNumber(realtimeData?.summary?.events) }}</strong>
            </div>
            <div class="ui-kpi">
              <span class="ui-kpi__label">页面浏览</span>
              <strong class="ui-kpi__value">{{ formatNumber(realtimeData?.summary?.pv) }}</strong>
            </div>
            <div class="ui-kpi">
              <span class="ui-kpi__label">新用户</span>
              <strong class="ui-kpi__value">{{ formatNumber(realtimeData?.summary?.newVisitors) }}</strong>
            </div>
          </div>
          <div class="realtime-pulse">
            <i
              v-for="m in realtimeData?.minutes || []"
              :key="m.minute"
              :style="{ height: pulseHeight(m.events) }"
              :title="`${m.minute} · 事件 ${m.events} · 浏览 ${m.pv}`"
            />
          </div>
          <p class="muted small">更新于 {{ realtimeData?.now || '—' }}（按分钟的事件量脉冲条，条越高那一分钟越热闹）</p>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 会话详情抽屉 -->
    <el-drawer v-model="sessionDrawer" size="min(560px, 92vw)" :title="sessionDetail?.session?.visitorId || '使用详情'">
      <template v-if="sessionDetail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="开始时间">{{ sessionDetail.session.startedAt }}</el-descriptions-item>
          <el-descriptions-item label="终端">{{ sessionDetail.session.deviceType || '—' }}</el-descriptions-item>
          <el-descriptions-item label="浏览器">{{ sessionDetail.session.browser || '—' }}</el-descriptions-item>
          <el-descriptions-item label="时长">{{ formatDuration(sessionDetail.session.durationSeconds) }}</el-descriptions-item>
          <el-descriptions-item label="起始页">{{ sessionDetail.session.entryPage || '—' }}</el-descriptions-item>
          <el-descriptions-item label="结束页">{{ sessionDetail.session.exitPage || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div style="margin: 10px 0 4px">
          <el-button
            size="small"
            type="primary"
            :disabled="!sessionDetail.session.subjectId"
            @click="goProfile(sessionDetail.session.subjectType, sessionDetail.session.subjectId)"
          >
            看该主体画像
          </el-button>
          <span v-if="!sessionDetail.session.subjectId" class="muted small" style="margin-left: 8px">匿名会话未关联登录主体，无画像可看</span>
        </div>
        <div class="drawer-section-title">事件时间线</div>
        <el-alert
          v-if="sessionDetail.behaviorBlocked"
          type="info"
          :closable="false"
          show-icon
          title="无行为明细查看权限"
          description="需要「画像-看行为明细」权限才可见逐事件轨迹（含上下文与属性）。"
          style="margin-bottom: 10px"
        />
        <el-timeline v-if="!sessionDetail.behaviorBlocked">
          <el-timeline-item v-for="item in sessionDetail.timeline" :key="item.eventId" :timestamp="item.occurredTime" placement="top">
            <div class="timeline-item">
              <div class="timeline-head">
                <el-tag size="small" effect="plain">{{ item.eventName }}</el-tag>
                <span class="mono muted small">{{ item.pageUrl || '—' }}</span>
              </div>
              <div v-if="item.title" class="muted small">{{ item.title }}</div>
            </div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-if="!sessionDetail.behaviorBlocked && !sessionDetail.timeline.length" :image-size="50" description="没有匹配到事件" />
      </template>
    </el-drawer>

    <!-- 漏斗编辑 -->
    <el-dialog v-model="funnelDialog" :title="funnelForm.funnelId ? '编辑漏斗' : '新建漏斗'" width="min(620px, 94%)">
      <el-form :model="funnelForm" label-width="96px">
        <el-form-item label="漏斗名称">
          <el-input v-model="funnelForm.funnelName" placeholder="例如：上手转化（登录→发布→提交）" />
        </el-form-item>
        <el-form-item v-if="!funnelForm.funnelId" label="漏斗编码">
          <el-input v-model="funnelForm.funnelCode" placeholder="例如：web_conversion" />
        </el-form-item>
        <el-form-item label="窗口天数">
          <el-input-number v-model="funnelForm.windowDays" :min="1" :max="90" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="funnelForm.enabled" active-value="1" inactive-value="0" />
        </el-form-item>
        <el-form-item label="步骤">
          <div class="step-editor">
            <div v-for="(step, index) in funnelForm.steps" :key="index" class="step-row">
              <span class="step-no">{{ index + 1 }}</span>
              <el-input v-model="step.eventName" placeholder="事件名，如 page_view" />
              <el-input v-model="step.name" placeholder="展示名（可选）" />
              <el-button link type="danger" :disabled="funnelForm.steps.length <= 2" @click="funnelForm.steps.splice(index, 1)"> 删除 </el-button>
            </div>
            <el-button link type="primary" :disabled="funnelForm.steps.length >= 8" @click="funnelForm.steps.push({ eventName: '', name: '' })">
              + 添加步骤
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="funnelForm.remark" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="funnelDialog = false">取消</el-button>
        <el-button type="primary" :loading="funnelSaving" @click="saveFunnel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="DcsProfileAnalytics" lang="ts">
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { BarChart, LineChart, SankeyChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import VChart from 'vue-echarts';
import { useChartTokens, alpha } from '@/utils/chart-theme';
import AccessGuideCard from './AccessGuideCard.vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  deleteAnalyticsFunnel,
  getAnalyticsBreakdown,
  getAnalyticsOverview,
  getAnalyticsAdoption,
  getAnalyticsActivity,
  getAnalyticsCrossSource,
  getAnalyticsFunnelLoss,
  getAnalyticsPaths,
  getAnalyticsRealtime,
  getAnalyticsRetention,
  getAnalyticsSession,
  getAnalyticsTrend,
  importAnalyticsCsv,
  listAnalyticsFunnels,
  listAnalyticsReports,
  pageAnalyticsSessions,
  removeAnalyticsReport,
  saveAnalyticsReport,
  previewAnalyticsFunnel,
  rebuildAnalytics,
  runAnalyticsCustom,
  saveAnalyticsFunnel,
  type AnalyticsBreakdownRow,
  type AnalyticsFunnel,
  type AnalyticsFunnelResult,
  type AnalyticsFunnelStepDef,
  type AnalyticsOverview,
  type AnalyticsAdoption,
  type AnalyticsActivity,
  type AnalyticsCrossSource,
  type AnalyticsCustomReport,
  type AnalyticsFunnelLoss,
  type AnalyticsPaths,
  type AnalyticsRealtime,
  type AnalyticsRetention,
  type AnalyticsSession,
  type AnalyticsSessionDetail,
  type AnalyticsSessionQuery,
  type AnalyticsTrendPoint
} from '@/api/dcs/core/analytics';
import { download } from '@/utils/request';
import { useSourceOptions, useSourceEmpty, autoPickSource } from '@/composables/useProfileSources';
import { checkPermi } from '@/utils/permission';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';
const { list: sourceOpts, ensureSources } = useSourceOptions('event');
const eventEmpty = useSourceEmpty('event');
const noRealSource = computed(() => eventEmpty.value);
const canTestSend = checkPermi(['profile:engine:query']);
const router = useRouter();
/** 批2 看画像: 会话行/抽屉直达画像详情页; 匿名行(无 subjectId)按钮禁用 */
const goProfile = (subjectType?: string, subjectId?: string) => {
  if (!subjectId) return;
  router.push(`/insight/profile-detail/${source.value}/${subjectId}?subjectType=${encodeURIComponent(subjectType || 'user')}`);
};
use([CanvasRenderer, LineChart, BarChart, SankeyChart, GridComponent, LegendComponent, TooltipComponent]);

const route = useRoute();
/** 批3A: 筛选状态落 URL(source/days/tab), 刷新/分享链接恢复同视图 */
const source = ref(String(route.query.source || 'dcs'));
// 真实数据优先: 首挂单轮加载(三审 dspro P1)——onMounted 等清单→autoPick→一次加载, 不再双载
const initialSource = source.value;
const days = ref(Math.min(Math.max(Number(route.query.days) || 30, 1), 365));
const PRESET_DAYS = new Set([7, 30, 90]);
const daysMode = ref(PRESET_DAYS.has(days.value) ? String(days.value) : 'custom');
const syncQuery = () => {
  const q: Record<string, string> = {};
  if (source.value) q.source = source.value;
  if (days.value !== 30) q.days = String(days.value);
  if (tab.value && tab.value !== 'overview') q.tab = tab.value;
  // 五审回修: 板3A 承诺「配好视图复制链接恢复同视图」——自定义分析条件也是视图的一部分
  if (tab.value === 'custom') {
    q.dimension = customForm.dimension;
    q.metric = customForm.metric;
    q.limit = String(customForm.limit);
  }
  router.replace({ query: q }).catch(() => undefined);
};
const tab = ref(String(route.query.tab || 'overview'));
const loading = ref(false);
const rebuilding = ref(false);
/** 图表色随主题切换（A 墨青 / D 青柠），切深色时 SVG 画布自动重算 */
const ct = useChartTokens();

const overview = ref<Partial<AnalyticsOverview>>({});
const trend = ref<AnalyticsTrendPoint[]>([]);
/** E 片采用度: 周活/新同事/首周激活 + 两步上手漏斗的数据源 */
const adoption = ref<AnalyticsAdoption | null>(null);
const adoptionLoading = ref(false);
const activationRateText = computed(() =>
  adoption.value?.activationRate == null ? '激活率 —' : `激活率 ${formatPercent(adoption.value.activationRate)}`
);
/** 两步上手率 = 完成核心动作人数 ÷ 登录人数(后端已保证分子⊆分母, 前端再夹一层 ≤100% 兜底);
 * 没人登录时显示「—」, 不造 0% */
const twoStepRateText = computed(() => {
  const a = adoption.value;
  if (!a || !a.loginUsers) return '—';
  return formatPercent(Math.min((a.coreUsers / a.loginUsers) * 100, 100));
});
const twoStepWidth = computed(() => {
  const a = adoption.value;
  if (!a || !a.loginUsers) return '0%';
  return `${Math.min(Math.max((a.coreUsers / a.loginUsers) * 100, a.coreUsers > 0 ? 6 : 0), 100)}%`;
});
/** 次均操作 = 事件数 ÷ 会话数(旧「页次」只数页面浏览, 对操作流恒 0) */
const avgActionsText = computed(() => {
  const c = overview.value?.current;
  if (!c || !c.visits) return '—';
  return formatNumber(Number(c.events || 0) / Number(c.visits), 2);
});
/** 空源判定：窗口期事件/会话/PV 全 0 时趋势图换空态引导，不再渲染全 0 假曲线 */
const hasOverviewData = computed(() => {
  const c = overview.value?.current;
  if (!c) return false;
  return Number(c.events ?? 0) > 0 || Number(c.visits ?? 0) > 0 || Number(c.pv ?? 0) > 0;
});
const overviewLoading = ref(false);
const breakdownLoading = ref(false);

// E 片单源重构: 渠道/地域是外部网站概念, 统一事件流没有这两个字段(恒"(未知)"), 两卡退役
const breakdowns = reactive<Record<string, AnalyticsBreakdownRow[]>>({
  device: [],
  browser: []
});
const breakdownBlocks = [
  { type: 'device', title: '终端类型', label: '终端', hint: '电脑 / 手机 / 平板' },
  { type: 'browser', title: '浏览器', label: '浏览器', hint: '来自事件上下文' }
];

const pageLoading = ref(false);
const pageRows = ref<AnalyticsBreakdownRow[]>([]);
const searchRows = ref<AnalyticsBreakdownRow[]>([]);
const eventRows = ref<AnalyticsBreakdownRow[]>([]);

const funnels = ref<AnalyticsFunnel[]>([]);
const funnelId = ref('');
const funnelResult = ref<AnalyticsFunnelResult | null>(null);
const funnelDialog = ref(false);
const funnelSaving = ref(false);
const funnelForm = reactive<{
  funnelId?: string;
  funnelCode: string;
  funnelName: string;
  windowDays: number;
  enabled: string;
  remark: string;
  steps: AnalyticsFunnelStepDef[];
}>({
  funnelId: '',
  funnelCode: '',
  funnelName: '',
  windowDays: 7,
  enabled: '1',
  remark: '',
  steps: [
    { eventName: 'user.login', name: '登录系统' },
    { eventName: 'task.published', name: '发布任务' },
    { eventName: 'data.submitted', name: '提交数据' }
  ]
});
const currentFunnel = computed(() => funnels.value.find((f) => f.funnelId === funnelId.value));
// R2-3: 漏斗拆解维度(整体/设备/系统/浏览器); E 片渠道下架
const funnelBreakdown = ref('');
const funnelBreakdownLabel = computed(() => ({ device: '终端', os: '系统', browser: '浏览器' }[funnelBreakdown.value] || '整体'));

const retention = ref<Partial<AnalyticsRetention>>({ cohorts: [], window: 7, days: 30 });
const retentionWindow = ref(7);
// R2-3: 留存粒度(日/周/月自然对齐) + 恰好/累计两种口径
const retentionGranularity = ref('day');
const retentionCumulative = ref(false);
const retentionUnit = computed(() => ({ day: '天', week: '周', month: '月' }[retentionGranularity.value] || '天'));
const retentionWindowOptions = computed(() => ({
  day: [
    { label: '7 天窗口', value: 7 },
    { label: '14 天窗口', value: 14 },
    { label: '30 天窗口', value: 30 }
  ],
  week: [
    { label: '4 周窗口', value: 4 },
    { label: '8 周窗口', value: 8 },
    { label: '12 周窗口', value: 12 }
  ],
  month: [
    { label: '3 月窗口', value: 3 },
    { label: '6 月窗口', value: 6 },
    { label: '12 月窗口', value: 12 }
  ]
}[retentionGranularity.value] || []));
// 遗漏审查回修(dsflash#3): 周/月 cohort 是自然周期对齐, 天数档不够长时窗口列大量为空——
// 周期越粗需要的回看天数越多, 留存用"有效天数"取 max(全局天数, 粒度所需的最小回看), 日粒度跟全局
const RETENTION_MIN_DAYS: Record<string, number> = { day: 0, week: 90, month: 365 };
const retentionEffectiveDays = computed(() => Math.max(days.value, RETENTION_MIN_DAYS[retentionGranularity.value] || 0));
const onRetentionGranularityChange = () => {
  retentionWindow.value = retentionWindowOptions.value[0]?.value || 7;
  loadRetention();
};
const retentionLoading = ref(false);

// R2-4 下钻: 终端/浏览器 两表行点击 → 使用明细带筛选直达
const sessionRange = ref<[string, string] | null>(null);
const drillToSessions = (type: string, row: AnalyticsBreakdownRow) => {
  // 三审 P2: 「(未知)」是库里空值的展示兜底, 按它当筛选条件必然查空——不下钻
  if (!row.label || row.label === '(未知)') return;
  tab.value = 'sessions';
  Object.assign(sessionQuery, {
    keyword: '', deviceType: '', browser: '', country: '', bounce: '', pageNum: 1
  });  sessionRange.value = null;
  sessionQuery.fromDate = undefined;
  sessionQuery.toDate = undefined;
  if (type === 'device') sessionQuery.deviceType = row.label;
  if (type === 'browser') sessionQuery.browser = row.label;
  // 五审回修: 程序化切页签也要落 URL, 分享/刷新才能回到下钻后的视图
  syncQuery();
  loadSessions();
};
const onSessionRangeChange = (range: [string, string] | null) => {
  sessionQuery.fromDate = range?.[0] || undefined;
  sessionQuery.toDate = range?.[1] || undefined;
  searchSessions();
};
const sessionQuery = reactive<AnalyticsSessionQuery>({
  source: '', // 每次请求前都会被当前源覆盖, 死值 'web' 只会误导(三审 dsflash P3)
  days: 30,
  pageNum: 1,
  pageSize: 20,
  keyword: '',
  deviceType: '',
  bounce: ''
});
const sessions = ref<AnalyticsSession[]>([]);
const sessionTotal = ref(0);
const sessionLoading = ref(false);
const sessionDrawer = ref(false);
const sessionDetail = ref<AnalyticsSessionDetail | null>(null);

const customForm = reactive({ dimension: 'event', metric: 'events', limit: 20 });
// R2-4 我的视图: 保存/打开/删除
const myReports = ref<AnalyticsCustomReport[]>([]);
const myReportId = ref('');
const loadMyReports = async () => {
  try {
    myReports.value = await listAnalyticsReports();
  } catch {
    myReports.value = [];
  }
};
const applyMyReport = (id?: string) => {
  const rep = myReports.value.find((x) => x.reportId === id);
  if (!rep) return;
  // 三审 P2: 视图存了哪套源就切回哪套源, 否则保存的是 order、打开时还查着 web
  if (rep.source && rep.source !== source.value) {
    source.value = rep.source;
  }
  customForm.dimension = rep.dimension;
  customForm.metric = rep.metric;
  customForm.limit = rep.limitRows;
  if (rep.days && rep.days !== days.value) {
    days.value = rep.days;
    daysMode.value = PRESET_DAYS.has(rep.days) ? String(rep.days) : 'custom';
  }
  syncQuery();
  loadCustom();
};
const openSaveReport = async () => {
  try {
    const { value } = await ElMessageBox.prompt('给当前分析条件起个名字(同名保存=覆盖)', '保存当前视图', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputPattern: /^\s*\S.{0,99}\s*$/,
      inputErrorMessage: '视图名 1-100 字'
    });
    await saveAnalyticsReport({
      reportName: value.trim(),
      source: source.value,
      dimension: customForm.dimension,
      metric: customForm.metric,
      days: days.value,
      limitRows: customForm.limit
    });
    ElMessage.success('视图已保存');
    await loadMyReports();
  } catch (e) {
    // 五审回修: EP 统一 reject('cancel'), 真实失败交给 request 层提示——此处不再向外抛未处理拒绝
    if (e !== 'cancel') console.warn('保存视图失败', e);
  }
};
const removeMyReport = async () => {
  const rep = myReports.value.find((x) => x.reportId === myReportId.value);
  if (!rep) return;
  try {
    await ElMessageBox.confirm(`确定删除视图「${rep.reportName}」？`, '删除视图', { type: 'warning' });
  } catch {
    return;
  }
  await removeAnalyticsReport(rep.reportId);
  myReportId.value = '';
  ElMessage.success('已删除');
  await loadMyReports();
};
const customRows = ref<{ label: string; value: number }[]>([]);
const customLoading = ref(false);

const csvText = ref('');
const importing = ref(false);

const dimensionLabels: Record<string, string> = {
  day: '日期',
  page: '页面',
  device: '终端',
  browser: '浏览器',
  os: '操作系统',
  entry: '起始页',
  exit: '结束页',
  search: '搜索词',
  event: '事件'
};
const metricLabels: Record<string, string> = { pv: '页面浏览', uv: '人数', visits: '会话数', events: '次数' };
const dimensionLabel = computed(() => dimensionLabels[customForm.dimension] || customForm.dimension);
const metricLabel = computed(() => metricLabels[customForm.metric] || customForm.metric);

const trendOption = computed<any>(() => ({
  color: ct.value.series,
  tooltip: { trigger: 'axis' },
  legend: { data: ['使用次数', '活跃用户', '会话数'], left: 'center', top: 0, itemWidth: 14, itemHeight: 8 },
  /* top 52：右轴名「用户 / 会话」贴着轴顶（52-16≈36 带），与 0 带的居中图例在 375 档同横带叠字——
     下压网格顶界让轴名带（30-42）与图例带（0-14）垂直分开（四席复审 medium，二修） */
  grid: { left: 52, right: 74, top: 52, bottom: 32 },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map((p) => p.statDate),
    axisLabel: { color: ct.value.faint },
    axisLine: { lineStyle: { color: ct.value.border } }
  },
  // 使用次数(全部事件)与人数/会话差一个量级，分左右轴，避免人数贴在 0 轴上看不见
  yAxis: [
    {
      type: 'value',
      name: '使用次数',
      nameGap: 16,
      minInterval: 1,
      axisLabel: { color: ct.value.faint },
      nameTextStyle: { color: ct.value.faint },
      splitLine: { lineStyle: { color: ct.value.rule } }
    },
    {
      type: 'value',
      name: '用户 / 会话',
      nameGap: 16,
      minInterval: 1,
      nameTextStyle: { fontSize: 11, color: ct.value.faint },
      axisLabel: { color: ct.value.faint },
      splitLine: { show: false }
    }
  ],
  series: [
    {
      name: '使用次数',
      type: 'line',
      yAxisIndex: 0,
      smooth: true,
      symbolSize: 5,
      itemStyle: { color: ct.value.accent },
      lineStyle: { width: 2.2, color: ct.value.accent },
      areaStyle: { opacity: 0.08, color: ct.value.accent },
      data: trend.value.map((p) => Number(p.events || 0))
    },
    {
      name: '会话数',
      type: 'line',
      yAxisIndex: 1,
      smooth: true,
      symbol: 'none',
      lineStyle: { width: 1.5, color: ct.value.muted },
      itemStyle: { color: ct.value.muted },
      data: trend.value.map((p) => Number(p.visits || 0))
    },
    {
      // 活跃用户与会话数同值时也要能分辨：同色系浅一档 + 虚线，不另起色相
      name: '活跃用户',
      type: 'line',
      yAxisIndex: 1,
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2, type: 'dashed', color: ct.value.series[1] },
      itemStyle: { color: ct.value.series[1] },
      z: 6,
      data: trend.value.map((p) => Number(p.uv || 0))
    }
  ]
}));

let loadOverview = async () => {
  overviewLoading.value = true;
  try {
    const [ov, tr] = await Promise.all([getAnalyticsOverview(source.value, days.value), getAnalyticsTrend(source.value, days.value)]);
    overview.value = ov;
    trend.value = tr;
  } finally {
    overviewLoading.value = false;
  }
};

let loadBreakdowns = async () => {
  breakdownLoading.value = true;
  try {
    const results = await Promise.all(breakdownBlocks.map((block) => getAnalyticsBreakdown(source.value, days.value, block.type, 10)));
    breakdownBlocks.forEach((block, index) => {
      breakdowns[block.type] = results[index].rows;
    });
  } finally {
    breakdownLoading.value = false;
  }
};

/** E 片采用度/两步上手: 概览 KPI(周活/新同事)与漏斗页签两步卡共用一份数据 */
let loadAdoption = async () => {
  adoptionLoading.value = true;
  // 三席回修: 先清旧值——切源/切天数后请求失败时, 不让上一源的数字顶着错误条继续显示
  adoption.value = null;
  try {
    adoption.value = await getAnalyticsAdoption(source.value, days.value);
  } finally {
    adoptionLoading.value = false;
  }
};

let loadPageAnalysis = async () => {
  pageLoading.value = true;
  try {
    const [page, search, event] = await Promise.all([
      getAnalyticsBreakdown(source.value, days.value, 'page', 20),
      getAnalyticsBreakdown(source.value, days.value, 'search', 20),
      getAnalyticsBreakdown(source.value, days.value, 'event', 20)
    ]);
    pageRows.value = page.rows;
    searchRows.value = search.rows;
    eventRows.value = event.rows;
  } finally {
    pageLoading.value = false;
  }
};

// ---------------------------------------------------------------- 漏斗
let loadFunnels = async () => {
  funnels.value = await listAnalyticsFunnels(source.value);
  if (!funnels.value.some((f) => f.funnelId === funnelId.value)) {
    funnelId.value = funnels.value[0]?.funnelId || '';
  }
  await loadFunnelResult();
};

let loadFunnelResult = async () => {
  if (!funnelId.value) {
    funnelResult.value = null;
    funnelLoss.value = null;
    return;
  }
  const [result, loss] = await Promise.all([
    previewAnalyticsFunnel({ funnelId: funnelId.value, days: days.value, breakdown: funnelBreakdown.value || undefined }),
    getAnalyticsFunnelLoss({ funnelId: funnelId.value, days: days.value })
  ]);
  funnelResult.value = result;
  funnelLoss.value = loss;
};

const funnelWidth = (count: number) => {
  const base = Number(funnelResult.value?.steps?.[0]?.count || 0);
  if (!base) return '0%';
  return `${Math.max((count / base) * 100, count > 0 ? 6 : 0)}%`;
};

const openFunnelDialog = (funnel?: AnalyticsFunnel) => {
  if (funnel) {
    const steps = parseSteps(funnel.steps);
    Object.assign(funnelForm, {
      funnelId: funnel.funnelId,
      funnelCode: funnel.funnelCode,
      funnelName: funnel.funnelName,
      windowDays: funnel.windowDays || 7,
      enabled: funnel.enabled || '1',
      remark: funnel.remark || '',
      steps: steps.length ? steps : [{ eventName: '', name: '' }]
    });
  } else {
    Object.assign(funnelForm, {
      funnelId: '',
      funnelCode: '',
      funnelName: '',
      windowDays: 7,
      enabled: '1',
      remark: '',
      // E 片三席回修: 默认模板用系统内部事件——旧官网故事(page_view/search/signup)在单源世界里建出来全 0
      steps: [
        { eventName: 'user.login', name: '登录系统' },
        { eventName: 'task.published', name: '发布任务' },
        { eventName: 'data.submitted', name: '提交数据' }
      ]
    });
  }
  funnelDialog.value = true;
};

const saveFunnel = async () => {
  const steps = funnelForm.steps.filter((step) => step.eventName?.trim());
  if (!funnelForm.funnelName?.trim()) {
    ElMessage.warning('请填写漏斗名称');
    return;
  }
  if (steps.length < 2) {
    ElMessage.warning('漏斗至少需要两个步骤');
    return;
  }
  funnelSaving.value = true;
  try {
    const saved = await saveAnalyticsFunnel({
      funnelId: funnelForm.funnelId || undefined,
      source: source.value,
      funnelCode: funnelForm.funnelCode || undefined,
      funnelName: funnelForm.funnelName,
      windowDays: funnelForm.windowDays,
      enabled: funnelForm.enabled,
      remark: funnelForm.remark,
      steps: steps.map((step) => ({ eventName: step.eventName.trim(), name: step.name?.trim() || step.eventName.trim() }))
    });
    ElMessage.success('漏斗已保存');
    funnelDialog.value = false;
    funnelId.value = saved.funnelId;
    await loadFunnels();
  } finally {
    funnelSaving.value = false;
  }
};

const removeFunnel = async () => {
  if (!currentFunnel.value) return;
  try {
    await ElMessageBox.confirm(`确定删除漏斗「${currentFunnel.value.funnelName}」？`, '删除漏斗', { type: 'warning' });
  } catch {
    return;
  }
  await deleteAnalyticsFunnel(currentFunnel.value.funnelId);
  ElMessage.success('已删除');
  await loadFunnels();
};

const parseSteps = (raw: string | AnalyticsFunnelStepDef[] | undefined): AnalyticsFunnelStepDef[] => {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return [];
  }
};

// ---------------------------------------------------------------- 留存
let retentionReqSeq = 0;
let loadRetention = async () => {
  // 五审回修: 请求序号防竞态——后到的旧响应不回填(周/月列头单位取本地状态, 旧响应会错位)
  const seq = ++retentionReqSeq;
  retentionLoading.value = true;
  try {
    const data = await getAnalyticsRetention(source.value, retentionEffectiveDays.value, retentionWindow.value, retentionGranularity.value, retentionCumulative.value);
    if (seq === retentionReqSeq) {
      retention.value = data;
    }
  } finally {
    if (seq === retentionReqSeq) {
      retentionLoading.value = false;
    }
  }
};

const retentionStyle = (cell?: { visitors: number; rate: number | string }) => {
  const rate = Number(cell?.rate || 0);
  if (!rate) return {};
  const a = Math.min(0.85, 0.08 + rate / 130);
  return { background: alpha(ct.value.accent, a), color: a > 0.45 ? 'var(--app-primary-ink)' : 'var(--app-text)' };
};

// ---------------------------------------------------------------- 访问明细
let loadSessions = async () => {
  sessionLoading.value = true;
  try {
    const res = await pageAnalyticsSessions({ ...sessionQuery, source: source.value, days: days.value });
    sessions.value = res.rows;
    sessionTotal.value = res.total;
  } finally {
    sessionLoading.value = false;
  }
};

const searchSessions = () => {
  sessionQuery.pageNum = 1;
  loadSessions();
};

const resetSessions = () => {
  // 三审 P2(dsflash): browser/country/fromDate/toDate 是下钻写入的隐藏筛选, 重置必须一并清掉,
  // 否则用户点完重置仍被看不见的条件过滤
  sessionRange.value = null;
  sessionQuery.fromDate = undefined;
  sessionQuery.toDate = undefined;
  Object.assign(sessionQuery, { keyword: '', deviceType: '', browser: '', country: '', bounce: '', pageNum: 1 });
  loadSessions();
};

const openSession = async (row: AnalyticsSession) => {
  sessionDetail.value = await getAnalyticsSession(row.sessionId);
  sessionDrawer.value = true;
};

// ---------------------------------------------------------------- R2-5: 报表 CSV 导出(默认脱敏; 明文需编辑权限)
const exporting = ref(false);

const exportSessionsCsv = async () => {
  exporting.value = true;
  try {
    const date = new Date().toISOString().slice(0, 10);
    await download(
      '/analytics/export/sessions',
      { ...sessionQuery, source: source.value, days: days.value, masked: 'true' },
      `使用明细-${source.value}-${date}.csv`
    );
  } catch {
    // download 内部已提示
  } finally {
    exporting.value = false;
  }
};

const exportCustomCsv = async () => {
  exporting.value = true;
  try {
    const date = new Date().toISOString().slice(0, 10);
    await download('/analytics/export/custom', { source: source.value, days: days.value, ...customForm }, `自定义分析-${source.value}-${date}.csv`);
  } catch {
    // download 内部已提示
  } finally {
    exporting.value = false;
  }
};

// ---------------------------------------------------------------- 自定义分析
const customOption = computed<any>(() => {
  const isDay = customForm.dimension === 'day';
  return {
    color: ct.value.series,
    tooltip: { trigger: 'axis' },
    grid: { left: 52, right: 20, top: 24, bottom: isDay ? 32 : 80 },
    xAxis: {
      type: 'category',
      data: customRows.value.map((p) => p.label),
      axisLabel: isDay ? { rotate: 0, color: ct.value.faint } : { rotate: 35, interval: 0, overflow: 'truncate', width: 90, color: ct.value.faint },
      axisLine: { lineStyle: { color: ct.value.border } }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: ct.value.faint },
      splitLine: { lineStyle: { color: ct.value.rule } }
    },
    series: [
      {
        type: isDay ? 'line' : 'bar',
        smooth: true,
        symbolSize: 5,
        areaStyle: isDay ? { opacity: 0.08, color: ct.value.accent } : undefined,
        barMaxWidth: 28,
        itemStyle: { color: ct.value.accent },
        data: customRows.value.map((p) => Number(p.value || 0))
      }
    ]
  };
});

let loadCustom = async () => {
  customLoading.value = true;
  try {
    const res = await runAnalyticsCustom({
      source: source.value,
      days: days.value,
      dimension: customForm.dimension,
      metric: customForm.metric,
      limit: customForm.limit
    });
    customRows.value = res.points || [];
  } finally {
    customLoading.value = false;
  }
};

// ---------------------------------------------------------------- 批8 分析深度
const pathsData = ref<AnalyticsPaths | null>(null);
const pathsLoading = ref(false);
const pathStart = ref('');
let loadPaths = async () => {
  pathsLoading.value = true;
  try {
    pathsData.value = await getAnalyticsPaths(source.value, days.value, pathStart.value || undefined);
  } finally {
    pathsLoading.value = false;
  }
};
const pathsOption = computed<any>(() => ({
  color: ct.value.ramp,
  tooltip: { trigger: 'item', triggerOn: 'mousemove' },
  series: [
    {
      type: 'sankey',
      left: 12,
      right: 190,
      top: 14,
      bottom: 14,
      nodeAlign: 'left',
      emphasis: { focus: 'adjacency' },
      data: (pathsData.value?.nodes || []).map((n) => ({ name: n.name, value: n.value })),
      links: (pathsData.value?.links || []).map((l) => ({ source: l.source, target: l.target, value: l.value })),
      label: { color: ct.value.muted, fontSize: 11 },
      lineStyle: { color: 'gradient', curveness: 0.5, opacity: 0.28 },
      itemStyle: { borderWidth: 0 }
    }
  ]
}));

const funnelLoss = ref<AnalyticsFunnelLoss | null>(null);
/* E 片单源重构: 渠道归因整卡退役(内部统一事件流没有渠道字段), 归因相关状态一并撤下 */

const activityData = ref<AnalyticsActivity | null>(null);
const activityLoading = ref(false);
let loadActivity = async () => {
  activityLoading.value = true;
  try {
    activityData.value = await getAnalyticsActivity(source.value, days.value);
  } finally {
    activityLoading.value = false;
  }
};
const activityOption = computed<any>(() => ({
  color: ct.value.series,
  tooltip: { trigger: 'axis' },
  legend: { bottom: 0, icon: 'circle', itemWidth: 8, data: ['DAU 日活', 'WAU 周活', 'MAU 月活'] },
  grid: { left: 52, right: 24, top: 30, bottom: 44 },
  xAxis: {
    type: 'category',
    data: activityData.value?.dayList || [],
    axisLabel: { color: ct.value.faint },
    axisLine: { lineStyle: { color: ct.value.border } }
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    axisLabel: { color: ct.value.faint },
    splitLine: { lineStyle: { color: ct.value.rule } }
  },
  series: [
    {
      name: 'DAU 日活',
      type: 'line',
      smooth: true,
      symbolSize: 4,
      data: activityData.value?.dau || [],
      itemStyle: { color: ct.value.accent },
      lineStyle: { color: ct.value.accent }
    },
    {
      name: 'WAU 周活',
      type: 'line',
      smooth: true,
      symbolSize: 4,
      data: activityData.value?.wau || [],
      itemStyle: { color: ct.value.series[1] },
      lineStyle: { type: 'dashed', color: ct.value.series[1] }
    },
    {
      name: 'MAU 月活',
      type: 'line',
      smooth: true,
      symbolSize: 4,
      data: activityData.value?.mau || [],
      itemStyle: { color: ct.value.muted },
      lineStyle: { width: 1, color: ct.value.muted }
    }
  ]
}));

const realtimeData = ref<AnalyticsRealtime | null>(null);
const realtimeAuto = ref(true);
let realtimeTimer: number | null = null;
let loadRealtime = async () => {
  realtimeData.value = await getAnalyticsRealtime(source.value);
};
const startRealtime = () => {
  if (realtimeTimer) return;
  realtimeTimer = window.setInterval(async () => {
    if (tab.value === 'realtime' && document.visibilityState === 'visible') {
      await loadRealtime();
    }
  }, 5000);
};
const stopRealtime = () => {
  if (realtimeTimer) {
    window.clearInterval(realtimeTimer);
    realtimeTimer = null;
  }
};
const toggleRealtime = (on: any) => {
  if (on) {
    startRealtime();
  } else {
    stopRealtime();
  }
};
const pulseHeight = (events: number) => {
  // E 片三席回修: 分子分母同用事件量(原来分母还是 pv——纯操作流 pv=0, 两条事件就能把条顶满溢出盖住 KPI)
  const max = Math.max(1, ...(realtimeData.value?.minutes || []).map((m) => Number(m.events) || 0));
  return `${Math.max(4, ((Number(events) || 0) / max) * 100)}%`;
};

const crossData = ref<AnalyticsCrossSource | null>(null);
const crossLoading = ref(false);
let loadCross = async () => {
  crossLoading.value = true;
  try {
    crossData.value = await getAnalyticsCrossSource(30);
  } finally {
    crossLoading.value = false;
  }
};
const formatLastAt = (value: string | undefined) => {
  if (!value) return '—';
  // 后端时间是 GMT+8 墙上时间且不带时区标记，浏览器本地时区各不相同——
  // 必须显式按 +08:00 解析，否则跨时区机器会把时间算成过去/未来，相对文案全错
  const time = new Date(value.replace(' ', 'T') + '+08:00').getTime();
  if (Number.isNaN(time)) return String(value);
  const diffMin = Math.round((Date.now() - time) / 60000);
  if (diffMin < 1) return '刚刚';
  if (diffMin < 60) return `${diffMin} 分钟前`;
  const diffHour = Math.round(diffMin / 60);
  if (diffHour < 24) return `${diffHour} 小时前`;
  return `${Math.round(diffHour / 24)} 天前`;
};
onUnmounted(() => {
  stopRealtime();
});

const fillCsvSample = () => {
  // E 片: 示例换成内部操作故事(与演示种子同构)——旧官网 page_view 故事在单源世界里导进去全 0
  csvText.value = [
    'eventName,eventUid,subjectType,subjectId,occurredTime,deviceType,os,browser,props',
    'user.login,demo-csv-1,user,9100000001,2026-10-28 09:00:00,desktop,Windows,Chrome,',
    'data.submitted,demo-csv-2,user,9100000001,2026-10-28 09:05:00,desktop,Windows,Chrome,"{""taskId"":9001,""submissionId"":7001,""version"":1}"',
    'data.audited,demo-csv-3,user,1,2026-10-28 10:00:00,desktop,Windows,Chrome,"{""submissionId"":7001}"'
  ].join('\n');
};

const submitCsv = async () => {
  if (!csvText.value.trim()) {
    ElMessage.warning('请先粘贴 CSV 内容');
    return;
  }
  importing.value = true;
  try {
    const result = await importAnalyticsCsv(source.value, csvText.value);
    const droppedNote = (result as { dropped?: number; invalidTime?: number });
    const extra = (droppedNote.dropped ? `，超限丢弃 ${droppedNote.dropped} 条` : '')
      + (droppedNote.invalidTime ? `，时间非法 ${droppedNote.invalidTime} 条` : '');
    ElMessage.success(`导入完成：写入 ${result.written} 条，跳过 ${result.skipped} 条${extra}`);
    await rebuild();
  } finally {
    importing.value = false;
  }
};

// ---------------------------------------------------------------- 动作
const rebuild = async () => {
  rebuilding.value = true;
  try {
    const result = await rebuildAnalytics(source.value, days.value);
    ElMessage.success(`汇总完成：${result.sessions} 个会话 / ${result.dailyRows} 天`);
    await reloadCurrent();
  } finally {
    rebuilding.value = false;
  }
};

/** 批3B: 报错≠没数据——加载失败置位错误条+可重试; 空数据源仍显示正常空态 */
const loadErrors = reactive<Record<string, boolean>>({});
const wrapLoad =
  (key: string, fn: () => Promise<unknown>): (() => Promise<void>) =>
  async () => {
    loadErrors[key] = false;
    try {
      await fn();
    } catch {
      loadErrors[key] = true;
    }
  };
loadOverview = wrapLoad('overview', loadOverview);
loadAdoption = wrapLoad('adoption', loadAdoption);
loadBreakdowns = wrapLoad('breakdowns', loadBreakdowns);
loadPageAnalysis = wrapLoad('page', loadPageAnalysis);
loadFunnels = wrapLoad('funnels', loadFunnels);
loadFunnelResult = wrapLoad('funnelResult', loadFunnelResult);
loadRetention = wrapLoad('retention', loadRetention);
loadSessions = wrapLoad('sessions', loadSessions);
loadCustom = wrapLoad('custom', loadCustom);
loadPaths = wrapLoad('paths', loadPaths);
loadActivity = wrapLoad('activity', loadActivity);
loadRealtime = wrapLoad('realtime', loadRealtime);
loadCross = wrapLoad('cross', loadCross);
const retryFailed = () => reloadCurrent();

const clearSessionRange = () => {
  // 五审回修: 明细日期区间优先级高于天数, 切源/切天数不清区间会让天数控件静默失效
  sessionRange.value = null;
  sessionQuery.fromDate = undefined;
  sessionQuery.toDate = undefined;
};
const onSourceChange = async () => {
  sessionQuery.source = source.value;
  funnelId.value = '';
  funnelResult.value = null;
  clearSessionRange();
  syncQuery();
  await reloadCurrent();
};

/** 批3A: 预设档直切; 「自定义」档保持当前天数等用户输入 */
const onDaysModeChange = async () => {
  if (daysMode.value !== 'custom') {
    days.value = Number(daysMode.value) || 30;
    syncQuery();
    await reloadCurrent();
  }
};

const onDaysChange = async () => {
  days.value = Math.min(Math.max(Number(days.value) || 30, 1), 365);
  clearSessionRange();
  syncQuery();
  await reloadCurrent();
};

const reloadCurrent = async () => {
  loading.value = true;
  try {
    if (tab.value === 'overview') {
      await Promise.all([loadOverview(), loadBreakdowns(), loadAdoption()]);
    } else if (tab.value === 'page') {
      await loadPageAnalysis();
    } else if (tab.value === 'funnel') {
      await Promise.all([loadFunnels(), loadAdoption()]);
    } else if (tab.value === 'retention') {
      await loadRetention();
    } else if (tab.value === 'paths') {
      await loadPaths();
    } else if (tab.value === 'activity') {
      await loadActivity();
    } else if (tab.value === 'realtime') {
      await loadRealtime();
      if (realtimeAuto.value) {
        startRealtime();
      }
    } else if (tab.value === 'sessions') {
      await loadSessions();
    } else if (tab.value === 'custom') {
      await loadCustom();
    } else if (tab.value === 'collect') {
      await loadCross();
    }
  } finally {
    loading.value = false;
  }
};

const onTabChange = async () => {
  if (tab.value !== 'realtime') {
    stopRealtime();
  }
  syncQuery();
  await reloadCurrent();
};

// ---------------------------------------------------------------- 工具
const clamp = (value: number) => Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));
const formatNumber = (value: number | string | undefined, digits = 0) => {
  const num = Number(value);
  if (value === undefined || value === null || Number.isNaN(num)) return '—';
  return num.toLocaleString('zh-CN', { maximumFractionDigits: digits, minimumFractionDigits: digits });
};
const formatPercent = (value: number | string | undefined) => {
  const num = Number(value);
  return Number.isFinite(num) ? `${num.toFixed(1)}%` : '—';
};
const formatDuration = (value: number | string | undefined) => {
  // 三席回修: 未加载/无数据显示「—」而不是「0 秒」(平均停留升为主数字后, 0 秒会被读成真值)
  if (value === undefined || value === null) return '—';
  const seconds = Number(value);
  if (!Number.isFinite(seconds) || seconds <= 0) return '0 秒';
  if (seconds < 60) return `${Math.round(seconds)} 秒`;
  const minutes = Math.floor(seconds / 60);
  const rest = Math.round(seconds % 60);
  if (minutes < 60) return `${minutes} 分 ${rest} 秒`;
  const hours = Math.floor(minutes / 60);
  return `${hours} 时 ${minutes % 60} 分`;
};
const deltaRaw = (key: string) => overview.value.delta?.[key];
const deltaValue = (key: string) => {
  const raw = deltaRaw(key);
  return raw === null || raw === undefined ? Number.NaN : Number(raw);
};
const deltaText = (key: string) => {
  const value = deltaValue(key);
  if (!Number.isFinite(value)) return '无对比数据';
  if (value === 0) return '环比持平';
  return `环比 ${value > 0 ? '+' : ''}${value.toFixed(1)}%`;
};
const deltaTone = (key: string) => {
  const value = deltaValue(key);
  if (!Number.isFinite(value) || value === 0) return 'flat';
  const positiveIsGood = key !== 'bounceRate';
  return value > 0 === positiveIsGood ? 'good' : 'bad';
};

onMounted(async () => {
  // 五审回修: URL 带回的自定义分析条件先恢复, 再按恢复后的页签首挂载
  const q = route.query;
  if (q.dimension) customForm.dimension = String(q.dimension);
  if (q.metric) customForm.metric = String(q.metric);
  if (q.limit) customForm.limit = Number(q.limit) || 20;
  // 真实数据优先(三审 dspro P1/P2): 先等源清单→autoPick→按最终源一次加载;
  // 空库时跳过注定为空的请求(诚实空态横幅已就位)
  await ensureSources();
  if (source.value === initialSource) autoPickSource(sourceOpts, source);
  if (noRealSource.value) return;
  // 三审 P2: 按 URL 恢复的 tab 首挂就要有数据——reloadCurrent 覆盖全部页签,
  // overview 默认态与原 Promise.all 完全等价, 不会多拉
  await reloadCurrent();
  // 五审回修: 页头「统计区间」取 overview——非 overview 页签直达时也要有区间可示
  if (tab.value !== 'overview' && !overview.value?.from) {
    loadOverview().catch(() => undefined);
  }
  // R2-4 我的视图: 无权限(403)时静默为空列表, 下拉只显示占位
  loadMyReports();
});

// 三审 glm53 P2: keep-alive 缓存页回页自愈——来源被改名/停用/新增后,
// 不进画像页直接切回分析中心也能拿到新清单(与画像页 onActivated 同款轻量路径)
onActivated(async () => {
  const before = source.value;
  await ensureSources();
  if (source.value === before) autoPickSource(sourceOpts, source);
  if (source.value !== before) onSourceChange();
});
</script>

<style scoped>
.analytics-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.analytics-page :deep(.dcs-ops-header) {
  margin-bottom: 4px;
}
.analytics-page :deep(.dcs-ops-header__actions) {
  flex-wrap: wrap;
}
@media (min-width: 993px) {
  .source-select {
    width: 150px;
  }
  .load-error {
  margin-bottom: 10px;
}
.days-select {
    width: 120px;
  }
}
/* el-table 走 CSS 变量：表头无底带、行线走令牌、正文灰阶（不重写组件） */
.analytics-page :deep(.el-table) {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--app-raised);
}
/* 状态药丸：统一浅底深字，形状/字号与 .ui-pill 同口径 */
.analytics-page :deep(.el-tag) {
  --el-tag-bg-color: var(--dcs-pill-off-bg);
  --el-tag-text-color: var(--dcs-pill-off-ink);
  --el-tag-border-color: transparent;
  height: 22px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--dcs-pill-radius);
  font-size: var(--dcs-font-xs);
  font-weight: 500;
}
.analytics-page :deep(.el-tag--success) {
  --el-tag-bg-color: var(--dcs-pill-pos-bg);
  --el-tag-text-color: var(--dcs-pill-pos-ink);
}
.analytics-page :deep(.el-tag--warning) {
  --el-tag-bg-color: var(--dcs-pill-warn-bg);
  --el-tag-text-color: var(--dcs-pill-warn-ink);
}
.analytics-page :deep(.el-tag--danger) {
  --el-tag-bg-color: var(--dcs-pill-neg-bg);
  --el-tag-text-color: var(--dcs-pill-neg-ink);
}
.analytics-page :deep(.el-card__body),
.analytics-page :deep(.el-tabs__content) {
  min-width: 0;
}
.meta-line {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  min-width: 0;
  font-size: 12px;
}
.analytics-tabs {
  min-height: 400px;
  min-width: 0;
}
/* KPI 带：一个容器 + 1px 分隔；桌面 4 列，≤992 由 dcs-ui-kit 落成 2 列 */
.ui-kpi-strip {
  margin-top: 0;
}
.analytics-kpi {
  margin-bottom: 12px;
}
@media (min-width: 993px) {
  .analytics-kpi {
    grid-auto-flow: row;
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .analytics-kpi .ui-kpi {
    border-top: 1px solid var(--app-rule);
  }
  /* 三席回修: 旧四列 nth-child 规则必须排除六格条——同特异度下 4n+1/-n+4 会把六格条
     第 5 格左边线、第 4 格上边线一并抹掉, 两行三列的分隔线断两处 */
  .analytics-kpi:not(.analytics-kpi--six) .ui-kpi:nth-child(4n + 1) {
    border-left: 0;
  }
  .analytics-kpi:not(.analytics-kpi--six) .ui-kpi:nth-child(-n + 4) {
    border-top: 0;
  }
  /* E 片六格采用度条: 3 列两行, 比 4+2 的参差行整齐 */
  .analytics-kpi--six {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .analytics-kpi--six .ui-kpi:nth-child(3n + 1) {
    border-left: 0;
  }
  .analytics-kpi--six .ui-kpi:nth-child(-n + 3) {
    border-top: 0;
  }
}
.caliber-note {
  margin-bottom: 12px;
}
.delta {
  font-size: var(--dcs-font-xs);
  color: var(--app-faint);
  font-variant-numeric: tabular-nums;
}
.delta.good {
  color: var(--dcs-color-success);
}
.delta.bad {
  color: var(--dcs-color-danger);
}
.delta.flat {
  color: var(--app-faint);
}
.panel-card {
  margin-bottom: 12px;
}
.panel-card :deep(.el-card__body) {
  padding-top: 12px;
  min-width: 0;
}
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  gap: 10px;
  flex-wrap: wrap;
}
.head-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.muted {
  color: var(--el-text-color-secondary);
}
/* U-22：次要指标并入趋势卡头部的小字行（E 片换成 会话/次均操作/新老用户） */
.trend-sub {
  font-weight: 400;
  font-size: var(--dcs-font-sm);
  white-space: normal;
}
/* P8④：段内不断行——窄屏整段换行，标签与数字不拆散 */
.trend-seg {
  white-space: nowrap;
}
.small {
  font-size: 12px;
}
.mono {
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
}
.trend-chart {
  height: 280px;
  width: 100%;
}
.breakdown-row {
  display: flex;
  flex-wrap: wrap;
}
.breakdown-row :deep(.el-col) {
  display: flex;
  margin-bottom: 14px;
}
.breakdown-row .panel-card {
  width: 100%;
}
.share-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.share-cell :deep(.el-progress) {
  flex: 1;
}
.share-text {
  width: 44px;
  text-align: right;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.funnel-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.funnel-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.funnel-step {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--app-card-radius);
  padding: 12px 14px;
}
.funnel-step-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.funnel-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}
.funnel-index {
  display: inline-flex;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  align-items: center;
  justify-content: center;
  background: var(--app-raised);
  color: var(--app-text);
  font-size: 12px;
}
.funnel-numbers {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.funnel-numbers strong {
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}
.funnel-bar {
  height: 8px;
  border-radius: var(--app-control-radius);
  background: var(--el-fill-color-light);
  overflow: hidden;
}
.funnel-bar-inner {
  height: 100%;
  border-radius: var(--app-control-radius);
  background: var(--app-accent);
  transition: width 0.35s ease;
}
.tip-card {
  background: var(--el-fill-color-lighter);
}
.tip-copy strong {
  font-size: 13px;
}
.tip-copy p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.retention-cell {
  border-radius: var(--app-control-radius);
  padding: 5px 4px;
  display: flex;
  flex-direction: column;
  gap: 1px;
  font-size: 11px;
  line-height: 1.2;
}
.retention-cell strong {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.retention-legend {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}
.retention-legend__swatch {
  width: 14px;
  height: 10px;
  border-radius: var(--dcs-radius-xs);
  display: inline-block;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.08);
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
  min-width: 0;
}
.drawer-section-title {
  margin: 18px 0 10px;
  font-weight: 600;
  font-size: 13px;
}
.timeline-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.timeline-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.custom-chart {
  height: 300px;
  width: 100%;
  margin-bottom: 12px;
}
.custom-table {
  max-height: 320px;
  overflow: auto;
}
.step-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.step-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.step-no {
  width: 20px;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.import-actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}
.path-chart {
  /* 分层后节点数≈页数×3，420px 会裁掉最底一行——视觉复审实测要 560px */
  height: 560px;
}
.loss-row {
  padding: 8px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.loss-row:last-child {
  border-bottom: none;
}
.loss-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 6px;
}
.loss-next {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding-left: 26px;
}
.loss-tag {
  font-family: inherit;
}
.realtime-pulse {
  display: flex;
  gap: 3px;
  align-items: flex-end;
  height: 64px;
  margin: 10px 0 6px;
}
.realtime-pulse i {
  flex: 1;
  min-height: 3px;
  border-radius: var(--dcs-radius-xs) var(--dcs-radius-xs) 0 0;
  background: var(--app-accent);
  opacity: 0.7;
  transition: height 0.4s ease;
}
/* 空态接入卡下的管理员脚注 */
.analytics-empty-guide__hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.analytics-empty-guide__hint--block {
  display: block;
  margin: 8px 4px 0;
}
</style>
