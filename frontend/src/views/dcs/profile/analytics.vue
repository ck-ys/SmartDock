<template>
  <div class="app-container analytics-page">
    <dcs-operations-page-header
      eyebrow="画像与分析"
      title="数据分析中心"
      subtitle="事件账本 → 会话重建 → 日汇总 → 漏斗/留存/下钻。同一套采集协议可接官网、订单、供应链、工程等任意业务源。"
    >
      <template #actions>
        <el-select v-model="source" class="source-select" @change="onSourceChange">
          <el-option label="web 演示站点" value="web" />
          <el-option label="dcs 数据采集" value="dcs" />
          <el-option label="order 订单" value="order" />
          <el-option label="supply 供应链" value="supply" />
        </el-select>
        <el-select v-model="days" class="days-select" @change="onDaysChange">
          <el-option label="近 7 天" :value="7" />
          <el-option label="近 30 天" :value="30" />
          <el-option label="近 90 天" :value="90" />
        </el-select>
        <el-button v-hasPermi="['analytics:center:edit']" :loading="rebuilding" @click="rebuild"> 重建汇总 </el-button>
        <el-button :loading="loading" @click="reloadCurrent">刷新</el-button>
      </template>
    </dcs-operations-page-header>
    <div class="meta-line">
      <el-tag effect="plain" type="info">数据源 {{ source }}</el-tag>
      <span class="muted">统计区间 {{ overview.from || '—' }} ~ {{ overview.to || '—' }}</span>
      <span class="muted">口径：PV=页面浏览 · UV=去重访客 · 访问次数=去重会话 · 跳出=只看一页</span>
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
              <span class="ui-kpi__hint">访客 {{ formatNumber(s.visitors) }} · 最近写入 {{ formatLastAt(s.lastAt) }}</span>
            </div>
          </div>
          <el-empty v-else :image-size="50" description="还没有任何业务源写入事件" />
          <p class="muted small">画像引擎按统一事件协议接收任意业务源（网站 / 订单 / 供应链…）；每个源独立统计互不混算。</p>
        </el-card>
      </el-tab-pane>
      <!-- ---------------------------------------------------------- 流量概览 -->
      <el-tab-pane label="流量概览" name="overview">
        <div v-loading="overviewLoading" class="ui-kpi-strip analytics-kpi">
          <div class="ui-kpi">
            <span class="ui-kpi__label">浏览量 PV</span>
            <strong class="ui-kpi__value">{{ formatNumber(overview.current?.pv) }}</strong>
            <span class="ui-kpi__hint delta" :class="deltaTone('pv')">{{ deltaText('pv') }}</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">访客数 UV</span>
            <strong class="ui-kpi__value">{{ formatNumber(overview.current?.uv) }}</strong>
            <span class="ui-kpi__hint delta" :class="deltaTone('uv')">{{ deltaText('uv') }}</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">访问次数</span>
            <strong class="ui-kpi__value">{{ formatNumber(overview.current?.visits) }}</strong>
            <span class="ui-kpi__hint delta" :class="deltaTone('visits')">{{ deltaText('visits') }}</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">新访客占比</span>
            <strong class="ui-kpi__value">{{ formatPercent(overview.current?.newRate) }}</strong>
            <span class="ui-kpi__hint">新 {{ overview.current?.newVisitors ?? 0 }} / 回访 {{ overview.current?.returningVisitors ?? 0 }}</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">跳出率</span>
            <strong class="ui-kpi__value">{{ formatPercent(overview.current?.bounceRate) }}</strong>
            <span class="ui-kpi__hint delta" :class="deltaTone('bounceRate')">{{ deltaText('bounceRate') }}</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">平均停留</span>
            <strong class="ui-kpi__value">{{ formatDuration(overview.current?.avgDuration) }}</strong>
            <span class="ui-kpi__hint delta" :class="deltaTone('avgDuration')">{{ deltaText('avgDuration') }}</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">每次访问页数</span>
            <strong class="ui-kpi__value">{{ formatNumber(overview.current?.pagesPerVisit, 2) }}</strong>
            <span class="ui-kpi__hint delta" :class="deltaTone('pagesPerVisit')">{{ deltaText('pagesPerVisit') }}</span>
          </div>
          <div class="ui-kpi">
            <span class="ui-kpi__label">事件数</span>
            <strong class="ui-kpi__value">{{ formatNumber(overview.current?.events) }}</strong>
            <span class="ui-kpi__hint">会话 {{ formatNumber(overview.current?.visits) }} 次</span>
          </div>
        </div>

        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>流量趋势</span>
            </div>
          </template>
          <v-chart v-if="hasOverviewData" class="trend-chart" :option="trendOption" autoresize />
          <el-empty v-else :image-size="60" description="该源在窗口期内还没有事件数据">
            <el-button type="primary" link @click="tab = 'collect'">去导入数据</el-button>
          </el-empty>
        </el-card>

        <el-row :gutter="14" class="breakdown-row">
          <el-col v-for="block in breakdownBlocks" :key="block.type" :xs="24" :lg="12">
            <el-card shadow="never" class="panel-card" v-loading="breakdownLoading">
              <template #header>
                <div class="panel-head">
                  <span>{{ block.title }}</span>
                  <span class="muted">{{ block.hint }}</span>
                </div>
              </template>
              <el-table class="ui-table" :data="breakdowns[block.type] || []" :show-header="true">
                <el-table-column prop="label" :label="block.label" min-width="140" show-overflow-tooltip />
                <el-table-column prop="pv" label="PV" width="76" align="right" />
                <el-table-column prop="uv" label="UV" width="76" align="right" />
                <el-table-column prop="visits" label="访问" width="76" align="right" />
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
            口径与转化漏斗一致：每位访客只取首次顺序走过的路径，连续重复页折叠为一步；线越粗走的人越多，鼠标悬停节点可看它通向哪里。带 ·2/·3
            的节点表示该页在第 2/3 步被再次到达（按步分层，回访不会成环）。
          </p>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="转化漏斗" name="funnel" lazy>
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>转化漏斗</span>
              <div class="head-actions">
                <el-select v-model="funnelId" placeholder="选择漏斗" style="width: 220px" @change="loadFunnelResult">
                  <el-option v-for="f in funnels" :key="f.funnelId" :label="`${f.funnelName} · ${f.windowDays}天`" :value="f.funnelId" />
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

          <el-empty v-if="!funnels.length" :image-size="60" description="当前业务源还没有漏斗">
            <el-button v-hasPermi="['analytics:center:edit']" type="primary" link @click="openFunnelDialog()">去新建漏斗</el-button>
          </el-empty>

          <template v-else-if="funnelResult">
            <div class="funnel-meta">
              <el-tag effect="plain">窗口 {{ funnelResult.windowDays }} 天</el-tag>
              <span class="muted">参与访客 {{ funnelResult.visitors }} 人 · 整体转化 {{ formatPercent(funnelResult.overallRate) }}</span>
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

        <el-card shadow="never" class="panel-card" v-loading="attributionLoading">
          <template #header>
            <div class="panel-head">
              <span>渠道归因</span>
              <div class="head-actions attribution-head">
                <el-select v-model="conversionEvent" style="width: 160px" @change="loadAttribution">
                  <el-option v-for="ev in conversionEventOptions" :key="ev.value" :label="ev.label" :value="ev.value" />
                </el-select>
                <span class="muted">共 {{ attributionData?.conversions ?? 0 }} 次转化</span>
              </div>
            </div>
          </template>
          <el-table v-if="attributionData?.rows?.length" class="ui-table" :data="attributionData.rows">
            <el-table-column prop="channel" label="渠道" min-width="110" />
            <el-table-column prop="first" label="首次归因" width="90" align="right" />
            <el-table-column prop="last" label="末次归因" width="90" align="right" />
            <el-table-column prop="linear" label="线性" width="80" align="right" />
            <el-table-column prop="visitors" label="触达访客" width="90" align="right" />
          </el-table>
          <el-empty v-else :image-size="50" description="窗口期内没有转化事件，无法归因">
            <el-button type="primary" link @click="tab = 'collect'">去导入数据</el-button>
          </el-empty>
          <p class="muted small">首次=最早带来的渠道记功；末次=临门一脚记功；线性=沿途触点平分。只统计转化发生之前的访问。</p>
        </el-card>

        <el-card shadow="never" class="panel-card tip-card">
          <div class="tip-copy">
            <strong>怎么读？</strong>
            <p>每一步按访客「首次满足顺序」计数；窗口天数限制相邻两步的最大间隔。跨会话回访超过窗口不累计，避免把隔天回访误算成一次转化。</p>
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 留存 -->
      <el-tab-pane label="留存分析" name="retention" lazy>
        <el-card shadow="never" class="panel-card" v-loading="retentionLoading">
          <template #header>
            <div class="panel-head">
              <span>访客留存</span>
              <div class="head-actions">
                <el-select v-model="retentionWindow" style="width: 130px" @change="loadRetention">
                  <el-option label="7 天窗口" :value="7" />
                  <el-option label="14 天窗口" :value="14" />
                  <el-option label="30 天窗口" :value="30" />
                </el-select>
              </div>
            </div>
          </template>
          <template v-if="retention.cohorts?.length">
            <el-table class="ui-table retention-table" :data="retention.cohorts">
              <el-table-column prop="cohort" label="首次访问" width="120" fixed />
              <el-table-column prop="total" label="新增访客" width="96" align="right" />
              <el-table-column v-for="i in retention.window" :key="i" :label="`第 ${i - 1} 天`" min-width="78" align="center">
                <template #default="{ row }">
                  <div class="retention-cell" :style="retentionStyle(row.cells[i - 1])">
                    <strong>{{ row.cells[i - 1]?.visitors ?? 0 }}</strong>
                    <span>{{ formatPercent(row.cells[i - 1]?.rate) }}</span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
            <div class="retention-legend muted small">
              <span>回访率</span>
              <span class="retention-legend__swatch" :style="{ background: alpha(ct.accent, 0.12) }" />
              <span class="retention-legend__swatch" :style="{ background: alpha(ct.accent, 0.35) }" />
              <span class="retention-legend__swatch" :style="{ background: alpha(ct.accent, 0.85) }" />
              <span>越高色越深（无回访不着色）</span>
            </div>
          </template>
          <el-empty v-else :image-size="60" description="暂无留存数据">
            <el-button type="primary" link @click="tab = 'collect'">去导入数据</el-button>
          </el-empty>
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 访问明细 -->
      <!-- ---------------------------------------------------------- 活跃分析（批8） -->
      <el-tab-pane label="活跃分析" name="activity" lazy>
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
            最新一天（{{ activityData?.newVsReturning?.statDate || '—' }}）构成：新访客
            {{ formatNumber(activityData?.newVsReturning?.newVisitors) }} 人 · 回访客
            {{ formatNumber(activityData?.newVsReturning?.returningVisitors) }} 人
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="访问明细" name="sessions" lazy>
        <el-card shadow="never" class="panel-card">
          <div class="filter-bar">
            <el-input v-model="sessionQuery.keyword" placeholder="访客/主体/落地页" clearable style="width: 200px" @keyup.enter="searchSessions" />
            <el-select v-model="sessionQuery.channel" placeholder="渠道" clearable style="width: 130px">
              <el-option v-for="item in channelOptions" :key="item" :label="item" :value="item" />
            </el-select>
            <el-select v-model="sessionQuery.deviceType" placeholder="设备" clearable style="width: 120px">
              <el-option label="desktop" value="desktop" />
              <el-option label="mobile" value="mobile" />
              <el-option label="tablet" value="tablet" />
            </el-select>
            <el-select v-model="sessionQuery.bounce" placeholder="跳出" clearable style="width: 110px">
              <el-option label="是" value="1" />
              <el-option label="否" value="0" />
            </el-select>
            <el-button type="primary" @click="searchSessions">查询</el-button>
            <el-button @click="resetSessions">重置</el-button>
            <el-button v-hasPermi="['analytics:center:export']" :loading="exporting" @click="exportSessionsCsv"> 导出 CSV </el-button>
          </div>

          <el-table class="ui-table" :data="sessions" v-loading="sessionLoading">
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">当前筛选下没有访问记录</p>
                <p class="ui-empty__hint">调整筛选条件，或确认该业务源已有事件写入</p>
              </div>
            </template>
            <el-table-column prop="visitorId" label="访客" min-width="130" show-overflow-tooltip />
            <el-table-column prop="startedAt" label="开始时间" width="170" />
            <el-table-column prop="sourceChannel" label="渠道" width="100" />
            <el-table-column prop="entryPage" label="落地页" min-width="140" show-overflow-tooltip />
            <el-table-column prop="exitPage" label="离开页" min-width="140" show-overflow-tooltip />
            <el-table-column prop="pageViews" label="PV" width="66" align="right" />
            <el-table-column prop="durationSeconds" label="时长" width="88" align="right">
              <template #default="{ row }">{{ formatDuration(row.durationSeconds) }}</template>
            </el-table-column>
            <el-table-column label="新访客" width="86">
              <template #default="{ row }">
                <el-tag size="small" :type="row.isNewVisitor === '1' ? 'success' : 'info'" effect="plain">
                  {{ row.isNewVisitor === '1' ? '新' : '回访' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="跳出" width="76">
              <template #default="{ row }">
                <el-tag size="small" :type="row.isBounce === '1' ? 'warning' : 'success'" effect="plain">
                  {{ row.isBounce === '1' ? '跳出' : '继续' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openSession(row)">详情</el-button>
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
              <el-option label="渠道" value="channel" />
              <el-option label="投放活动" value="campaign" />
              <el-option label="设备" value="device" />
              <el-option label="浏览器" value="browser" />
              <el-option label="操作系统" value="os" />
              <el-option label="地域" value="geo" />
              <el-option label="落地页" value="entry" />
              <el-option label="离开页" value="exit" />
              <el-option label="搜索词" value="search" />
              <el-option label="事件" value="event" />
            </el-select>
            <el-select v-model="customForm.metric" style="width: 140px" @change="loadCustom">
              <el-option label="PV" value="pv" />
              <el-option label="UV" value="uv" />
              <el-option label="访问次数" value="visits" />
              <el-option label="事件数" value="events" />
            </el-select>
            <el-radio-group v-model="customForm.limit" @change="loadCustom">
              <el-radio-button :value="10">Top 10</el-radio-button>
              <el-radio-button :value="20">Top 20</el-radio-button>
              <el-radio-button :value="50">Top 50</el-radio-button>
            </el-radio-group>
            <el-button type="primary" :loading="customLoading" @click="loadCustom">运行</el-button>
            <el-button v-hasPermi="['analytics:center:export']" :loading="exporting" @click="exportCustomCsv"> 导出 CSV </el-button>
          </div>
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
              <span class="ui-kpi__label">在线访客</span>
              <strong class="ui-kpi__value">{{ formatNumber(realtimeData?.summary?.visitors) }}</strong>
            </div>
            <div class="ui-kpi">
              <span class="ui-kpi__label">页面浏览</span>
              <strong class="ui-kpi__value">{{ formatNumber(realtimeData?.summary?.pv) }}</strong>
            </div>
            <div class="ui-kpi">
              <span class="ui-kpi__label">事件</span>
              <strong class="ui-kpi__value">{{ formatNumber(realtimeData?.summary?.events) }}</strong>
            </div>
            <div class="ui-kpi">
              <span class="ui-kpi__label">新访客</span>
              <strong class="ui-kpi__value">{{ formatNumber(realtimeData?.summary?.newVisitors) }}</strong>
            </div>
          </div>
          <div class="realtime-pulse">
            <i
              v-for="m in realtimeData?.minutes || []"
              :key="m.minute"
              :style="{ height: pulseHeight(m.pv) }"
              :title="`${m.minute} · 浏览 ${m.pv} · 事件 ${m.events}`"
            />
          </div>
          <p class="muted small">更新于 {{ realtimeData?.now || '—' }}（按分钟的浏览量脉冲条，条越高那一分钟越热闹）</p>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 会话详情抽屉 -->
    <el-drawer v-model="sessionDrawer" size="min(560px, 92vw)" :title="sessionDetail?.session?.visitorId || '访问详情'">
      <template v-if="sessionDetail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="开始时间">{{ sessionDetail.session.startedAt }}</el-descriptions-item>
          <el-descriptions-item label="渠道">{{ sessionDetail.session.sourceChannel || '—' }}</el-descriptions-item>
          <el-descriptions-item label="设备">{{ sessionDetail.session.deviceType || '—' }}</el-descriptions-item>
          <el-descriptions-item label="浏览器">{{ sessionDetail.session.browser || '—' }}</el-descriptions-item>
          <el-descriptions-item label="地域">
            {{ [sessionDetail.session.country, sessionDetail.session.province, sessionDetail.session.city].filter(Boolean).join(' / ') || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="时长">{{ formatDuration(sessionDetail.session.durationSeconds) }}</el-descriptions-item>
          <el-descriptions-item label="落地页">{{ sessionDetail.session.entryPage || '—' }}</el-descriptions-item>
          <el-descriptions-item label="离开页">{{ sessionDetail.session.exitPage || '—' }}</el-descriptions-item>
        </el-descriptions>

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
          <el-input v-model="funnelForm.funnelName" placeholder="例如：官网访问转化" />
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
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  deleteAnalyticsFunnel,
  getAnalyticsBreakdown,
  getAnalyticsOverview,
  getAnalyticsAttribution,
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
  pageAnalyticsSessions,
  previewAnalyticsFunnel,
  rebuildAnalytics,
  runAnalyticsCustom,
  saveAnalyticsFunnel,
  type AnalyticsBreakdown,
  type AnalyticsBreakdownRow,
  type AnalyticsFunnel,
  type AnalyticsFunnelResult,
  type AnalyticsFunnelStepDef,
  type AnalyticsOverview,
  type AnalyticsAttribution,
  type AnalyticsActivity,
  type AnalyticsCrossSource,
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
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';

use([CanvasRenderer, LineChart, BarChart, SankeyChart, GridComponent, LegendComponent, TooltipComponent]);

const source = ref('web');
const days = ref(30);
const tab = ref('overview');
const loading = ref(false);
const rebuilding = ref(false);
/** 图表色随主题切换（A 墨青 / D 青柠），切深色时 SVG 画布自动重算 */
const ct = useChartTokens();

const overview = ref<Partial<AnalyticsOverview>>({});
const trend = ref<AnalyticsTrendPoint[]>([]);
/** 空源判定：窗口期事件/会话/PV 全 0 时趋势图换空态引导，不再渲染全 0 假曲线 */
const hasOverviewData = computed(() => {
  const c = overview.value?.current;
  if (!c) return false;
  return Number(c.events ?? 0) > 0 || Number(c.visits ?? 0) > 0 || Number(c.pv ?? 0) > 0;
});
const overviewLoading = ref(false);
const breakdownLoading = ref(false);

const breakdowns = reactive<Record<string, AnalyticsBreakdownRow[]>>({
  channel: [],
  device: [],
  browser: [],
  geo: []
});
const breakdownBlocks = [
  { type: 'channel', title: '来源渠道', label: '渠道', hint: '按会话落地来源' },
  { type: 'device', title: '设备分布', label: '设备', hint: 'desktop / mobile' },
  { type: 'browser', title: '浏览器', label: '浏览器', hint: 'User-Agent 解析' },
  { type: 'geo', title: '地域分布', label: '地域', hint: '国家 / 省 / 市' }
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
    { eventName: 'page_view', name: '访问页面' },
    { eventName: 'search', name: '站内搜索' },
    { eventName: 'signup', name: '完成注册' }
  ]
});
const currentFunnel = computed(() => funnels.value.find((f) => f.funnelId === funnelId.value));

const retention = ref<Partial<AnalyticsRetention>>({ cohorts: [], window: 7, days: 30 });
const retentionWindow = ref(7);
const retentionLoading = ref(false);

const sessionQuery = reactive<AnalyticsSessionQuery>({
  source: 'web',
  days: 30,
  pageNum: 1,
  pageSize: 20,
  keyword: '',
  channel: '',
  deviceType: '',
  bounce: ''
});
const sessions = ref<AnalyticsSession[]>([]);
const sessionTotal = ref(0);
const sessionLoading = ref(false);
const sessionDrawer = ref(false);
const sessionDetail = ref<AnalyticsSessionDetail | null>(null);

const customForm = reactive({ dimension: 'channel', metric: 'visits', limit: 20 });
const customRows = ref<{ label: string; value: number }[]>([]);
const customLoading = ref(false);

const csvText = ref('');
const importing = ref(false);

const dimensionLabels: Record<string, string> = {
  day: '日期',
  page: '页面',
  channel: '渠道',
  device: '设备',
  browser: '浏览器',
  os: '操作系统',
  geo: '地域',
  entry: '落地页',
  exit: '离开页',
  search: '搜索词',
  event: '事件'
};
const metricLabels: Record<string, string> = { pv: 'PV', uv: 'UV', visits: '访问次数', events: '事件数' };
const dimensionLabel = computed(() => dimensionLabels[customForm.dimension] || customForm.dimension);
const metricLabel = computed(() => metricLabels[customForm.metric] || customForm.metric);

const channelOptions = computed(() => {
  const set = new Set<string>(['direct', 'baidu', 'google', 'weibo', 'wechat']);
  (breakdowns.channel || []).forEach((row) => set.add(row.label));
  return Array.from(set);
});

const trendOption = computed<any>(() => ({
  color: ct.value.series,
  tooltip: { trigger: 'axis' },
  legend: { data: ['PV', 'UV', '访问次数'], left: 'center', top: 0, itemWidth: 14, itemHeight: 8 },
  grid: { left: 52, right: 74, top: 34, bottom: 32 },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map((p) => p.statDate),
    axisLabel: { color: ct.value.faint },
    axisLine: { lineStyle: { color: ct.value.border } }
  },
  // PV 与 UV/访问次数量级差一个数量级，分左右轴，避免 UV 贴在 0 轴上看不见
  yAxis: [
    {
      type: 'value',
      name: 'PV',
      nameGap: 16,
      minInterval: 1,
      axisLabel: { color: ct.value.faint },
      nameTextStyle: { color: ct.value.faint },
      splitLine: { lineStyle: { color: ct.value.rule } }
    },
    {
      type: 'value',
      name: 'UV / 访问',
      nameGap: 16,
      minInterval: 1,
      nameTextStyle: { fontSize: 11, color: ct.value.faint },
      axisLabel: { color: ct.value.faint },
      splitLine: { show: false }
    }
  ],
  series: [
    {
      name: 'PV',
      type: 'line',
      yAxisIndex: 0,
      smooth: true,
      symbolSize: 5,
      itemStyle: { color: ct.value.accent },
      lineStyle: { width: 2.2, color: ct.value.accent },
      areaStyle: { opacity: 0.08, color: ct.value.accent },
      data: trend.value.map((p) => Number(p.pv || 0))
    },
    {
      name: '访问次数',
      type: 'line',
      yAxisIndex: 1,
      smooth: true,
      symbol: 'none',
      lineStyle: { width: 1.5, color: ct.value.muted },
      itemStyle: { color: ct.value.muted },
      data: trend.value.map((p) => Number(p.visits || 0))
    },
    {
      // UV 与访问次数同值时也要能分辨：同色系浅一档 + 虚线，不另起色相
      name: 'UV',
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

const loadOverview = async () => {
  overviewLoading.value = true;
  try {
    const [ov, tr] = await Promise.all([getAnalyticsOverview(source.value, days.value), getAnalyticsTrend(source.value, days.value)]);
    overview.value = ov;
    trend.value = tr;
  } finally {
    overviewLoading.value = false;
  }
};

const loadBreakdowns = async () => {
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

const loadPageAnalysis = async () => {
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
const loadFunnels = async () => {
  funnels.value = await listAnalyticsFunnels(source.value);
  if (!funnels.value.some((f) => f.funnelId === funnelId.value)) {
    funnelId.value = funnels.value[0]?.funnelId || '';
  }
  await loadFunnelResult();
};

const loadFunnelResult = async () => {
  if (!funnelId.value) {
    funnelResult.value = null;
    funnelLoss.value = null;
    return;
  }
  const [result, loss] = await Promise.all([
    previewAnalyticsFunnel({ funnelId: funnelId.value, days: days.value }),
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
      steps: [
        { eventName: 'page_view', name: '访问页面' },
        { eventName: 'search', name: '站内搜索' },
        { eventName: 'signup', name: '完成注册' }
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
  await ElMessageBox.confirm(`确定删除漏斗「${currentFunnel.value.funnelName}」？`, '删除漏斗', { type: 'warning' });
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
const loadRetention = async () => {
  retentionLoading.value = true;
  try {
    retention.value = await getAnalyticsRetention(source.value, days.value, retentionWindow.value);
  } finally {
    retentionLoading.value = false;
  }
};

const retentionStyle = (cell?: { visitors: number; rate: number | string }) => {
  const rate = Number(cell?.rate || 0);
  if (!rate) return {};
  const a = Math.min(0.85, 0.08 + rate / 130);
  return { background: alpha(ct.value.accent, a), color: a > 0.45 ? 'var(--app-primary-ink)' : 'var(--app-text)' };
};

// ---------------------------------------------------------------- 访问明细
const loadSessions = async () => {
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
  Object.assign(sessionQuery, { keyword: '', channel: '', deviceType: '', bounce: '', pageNum: 1 });
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
      `访问明细-${source.value}-${date}.csv`
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

const loadCustom = async () => {
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
const loadPaths = async () => {
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
const attributionData = ref<AnalyticsAttribution | null>(null);
const attributionLoading = ref(false);
/* R2-1: 归因转化事件可选(后端本就支持 event 参数, 默认 signup), 换事件即重算 */
const conversionEvent = ref('signup');
const conversionEventOptions = [
  { label: '转化事件：signup', value: 'signup' },
  { label: '转化事件：login', value: 'login' },
  { label: '转化事件：order_submit', value: 'order_submit' },
  { label: '转化事件：pay_success', value: 'pay_success' }
];
const loadAttribution = async () => {
  attributionLoading.value = true;
  try {
    attributionData.value = await getAnalyticsAttribution(source.value, days.value, conversionEvent.value);
  } finally {
    attributionLoading.value = false;
  }
};

const activityData = ref<AnalyticsActivity | null>(null);
const activityLoading = ref(false);
const loadActivity = async () => {
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
const loadRealtime = async () => {
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
const pulseHeight = (pv: number) => {
  const max = Math.max(1, ...(realtimeData.value?.minutes || []).map((m) => Number(m.pv) || 0));
  return `${Math.max(4, ((Number(pv) || 0) / max) * 100)}%`;
};

const crossData = ref<AnalyticsCrossSource | null>(null);
const crossLoading = ref(false);
const loadCross = async () => {
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
  csvText.value = [
    'eventName,anonymousId,sessionId,occurredTime,pageUrl,keyword,channel,deviceType,browser',
    'page_view,csv-demo,csv-s1,2026-10-19 10:00:00,/home,,baidu,desktop,Chrome',
    'search,csv-demo,csv-s1,2026-10-19 10:01:00,/search,pricing,baidu,desktop,Chrome',
    'signup,csv-demo,csv-s1,2026-10-19 10:02:00,/signup,,baidu,desktop,Chrome'
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
    ElMessage.success(`导入完成：写入 ${result.written} 条，跳过 ${result.skipped} 条`);
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

const onSourceChange = async () => {
  sessionQuery.source = source.value;
  funnelId.value = '';
  funnelResult.value = null;
  await reloadCurrent();
};

const onDaysChange = async () => {
  await reloadCurrent();
};

const reloadCurrent = async () => {
  loading.value = true;
  try {
    if (tab.value === 'overview') {
      await Promise.all([loadOverview(), loadBreakdowns()]);
    } else if (tab.value === 'page') {
      await loadPageAnalysis();
    } else if (tab.value === 'funnel') {
      await Promise.all([loadFunnels(), loadAttribution()]);
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
  await Promise.all([loadOverview(), loadBreakdowns()]);
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
  .analytics-kpi .ui-kpi:nth-child(4n + 1) {
    border-left: 0;
  }
  .analytics-kpi .ui-kpi:nth-child(-n + 4) {
    border-top: 0;
  }
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
  border-radius: 10px;
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
  border-radius: 6px;
  background: var(--el-fill-color-light);
  overflow: hidden;
}
.funnel-bar-inner {
  height: 100%;
  border-radius: 6px;
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
  border-radius: 6px;
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
  border-radius: 2px;
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
  border-radius: 2px 2px 0 0;
  background: var(--app-accent);
  opacity: 0.7;
  transition: height 0.4s ease;
}
</style>
