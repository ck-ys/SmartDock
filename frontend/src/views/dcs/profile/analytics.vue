<template>
  <div class="app-container analytics-page">
    <el-card shadow="never" class="hero-card">
      <div class="hero-head">
        <div class="hero-copy">
          <h3>数据分析中心</h3>
          <p>事件账本 → 会话重建 → 日汇总 → 漏斗/留存/下钻。同一套采集协议可接官网、订单、供应链、工程等任意业务源。</p>
        </div>
        <div class="hero-actions">
          <el-select v-model="source" style="width: 150px" @change="onSourceChange">
            <el-option label="web 演示站点" value="web" />
            <el-option label="dcs 数据采集" value="dcs" />
            <el-option label="order 订单" value="order" />
            <el-option label="supply 供应链" value="supply" />
          </el-select>
          <el-select v-model="days" style="width: 120px" @change="onDaysChange">
            <el-option label="近 7 天" :value="7" />
            <el-option label="近 30 天" :value="30" />
            <el-option label="近 90 天" :value="90" />
          </el-select>
          <el-button v-hasPermi="['analytics:center:edit']" :loading="rebuilding" @click="rebuild"> 重建汇总 </el-button>
          <el-button :loading="loading" @click="reloadCurrent">刷新</el-button>
        </div>
      </div>
      <div class="meta-line">
        <el-tag effect="plain" type="info">数据源 {{ source }}</el-tag>
        <span class="muted">统计区间 {{ overview.from || '—' }} ~ {{ overview.to || '—' }}</span>
        <span class="muted">口径：PV=页面浏览 · UV=去重访客 · 访问次数=去重会话 · 跳出=只看一页</span>
      </div>
    </el-card>

    <el-tabs v-model="tab" class="analytics-tabs" @tab-change="onTabChange">
      <!-- ---------------------------------------------------------- 流量概览 -->
      <el-tab-pane label="流量概览" name="overview">
        <div v-loading="overviewLoading" class="stat-grid">
          <div class="stat">
            <span class="stat-label">浏览量 PV</span>
            <strong>{{ formatNumber(overview.current?.pv) }}</strong>
            <span class="delta" :class="deltaTone('pv')">{{ deltaText('pv') }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">访客数 UV</span>
            <strong>{{ formatNumber(overview.current?.uv) }}</strong>
            <span class="delta" :class="deltaTone('uv')">{{ deltaText('uv') }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">访问次数</span>
            <strong>{{ formatNumber(overview.current?.visits) }}</strong>
            <span class="delta" :class="deltaTone('visits')">{{ deltaText('visits') }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">新访客占比</span>
            <strong>{{ formatPercent(overview.current?.newRate) }}</strong>
            <span class="delta muted">新 {{ overview.current?.newVisitors ?? 0 }} / 回访 {{ overview.current?.returningVisitors ?? 0 }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">跳出率</span>
            <strong>{{ formatPercent(overview.current?.bounceRate) }}</strong>
            <span class="delta" :class="deltaTone('bounceRate')">{{ deltaText('bounceRate') }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">平均停留</span>
            <strong>{{ formatDuration(overview.current?.avgDuration) }}</strong>
            <span class="delta" :class="deltaTone('avgDuration')">{{ deltaText('avgDuration') }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">每次访问页数</span>
            <strong>{{ formatNumber(overview.current?.pagesPerVisit, 2) }}</strong>
            <span class="delta" :class="deltaTone('pagesPerVisit')">{{ deltaText('pagesPerVisit') }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">事件数</span>
            <strong>{{ formatNumber(overview.current?.events) }}</strong>
            <span class="delta muted">会话 {{ formatNumber(overview.current?.visits) }} 次</span>
          </div>
        </div>

        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>流量趋势</span>
              <span class="muted">PV / UV / 访问次数</span>
            </div>
          </template>
          <v-chart class="trend-chart" :option="trendOption" autoresize />
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
              <el-table :data="breakdowns[block.type] || []" size="small" :show-header="true">
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
                <template #empty><el-empty :image-size="50" description="暂无数据" /></template>
              </el-table>
            </el-card>
          </el-col>
        </el-row>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 页面与搜索 -->
      <el-tab-pane label="页面与搜索" name="page">
        <el-row :gutter="14">
          <el-col :xs="24" :lg="14">
            <el-card shadow="never" class="panel-card" v-loading="pageLoading">
              <template #header>
                <div class="panel-head">
                  <span>页面分析</span>
                  <span class="muted">按 page_view 统计</span>
                </div>
              </template>
              <el-table :data="pageRows" size="small">
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
              <el-table :data="searchRows" size="small">
                <el-table-column prop="label" label="关键词" min-width="120" show-overflow-tooltip />
                <el-table-column prop="pv" label="次数" width="70" align="right" />
                <el-table-column prop="uv" label="人数" width="70" align="right" />
                <el-table-column prop="share" label="占比" width="86" align="right">
                  <template #default="{ row }">{{ formatPercent(row.share) }}</template>
                </el-table-column>
                <template #empty><el-empty :image-size="50" description="暂无搜索事件" /></template>
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
          <el-table :data="eventRows" size="small">
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
      <el-tab-pane label="转化漏斗" name="funnel">
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>转化漏斗</span>
              <div class="head-actions">
                <el-select v-model="funnelId" placeholder="选择漏斗" style="width: 220px" @change="loadFunnelResult">
                  <el-option v-for="f in funnels" :key="f.funnelId" :label="`${f.funnelName} · ${f.windowDays}天`" :value="f.funnelId" />
                </el-select>
                <el-button v-hasPermi="['analytics:center:edit']" @click="openFunnelDialog()">新建漏斗</el-button>
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

          <el-empty v-if="!funnels.length" :image-size="60" description="当前业务源还没有漏斗" />

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
                      环比 {{ formatPercent(step.stepRate) }}
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

        <el-card shadow="never" class="panel-card tip-card">
          <div class="tip-copy">
            <strong>怎么读？</strong>
            <p>每一步按访客「首次满足顺序」计数；窗口天数限制相邻两步的最大间隔。跨会话回访超过窗口不累计，避免把隔天回访误算成一次转化。</p>
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 留存 -->
      <el-tab-pane label="留存分析" name="retention">
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
          <el-table v-if="retention.cohorts?.length" :data="retention.cohorts" size="small" border class="retention-table">
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
          <el-empty v-else :image-size="60" description="暂无留存数据" />
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 访问明细 -->
      <el-tab-pane label="访问明细" name="sessions">
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
          </div>

          <el-table :data="sessions" size="small" v-loading="sessionLoading">
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
      <el-tab-pane label="自定义分析" name="custom">
        <el-card shadow="never" class="panel-card">
          <div class="filter-bar">
            <el-select v-model="customForm.dimension" style="width: 160px" @change="loadCustom">
              <el-option label="按天" value="day" />
              <el-option label="页面" value="page" />
              <el-option label="渠道" value="channel" />
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
            <el-button :loading="customLoading" @click="loadCustom">运行</el-button>
          </div>
          <v-chart class="custom-chart" :option="customOption" autoresize v-loading="customLoading" />
          <el-table :data="customRows" size="small" class="custom-table">
            <el-table-column type="index" label="#" width="56" />
            <el-table-column prop="label" :label="dimensionLabel" min-width="180" show-overflow-tooltip />
            <el-table-column :label="metricLabel" width="120" align="right">
              <template #default="{ row }">{{ formatNumber(row.value) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ---------------------------------------------------------- 数据采集 -->
      <el-tab-pane label="数据采集" name="collect">
        <el-row :gutter="14">
          <el-col :xs="24" :lg="12">
            <el-card shadow="never" class="panel-card" v-loading="siteLoading">
              <template #header>
                <div class="panel-head">
                  <span>采集站点</span>
                  <span class="muted">公开采集接口 /analytics/collect</span>
                </div>
              </template>
              <el-table :data="sites" size="small">
                <el-table-column prop="siteName" label="站点" min-width="120" />
                <el-table-column prop="siteKey" label="siteKey" min-width="130">
                  <template #default="{ row }"
                    ><code class="mono">{{ row.siteKey }}</code></template
                  >
                </el-table-column>
                <el-table-column prop="source" label="业务源" width="90" />
                <el-table-column prop="allowedDomains" label="允许域名" min-width="140" show-overflow-tooltip />
                <template #empty><el-empty :image-size="50" description="还没有采集站点" /></template>
              </el-table>
            </el-card>
          </el-col>
          <el-col :xs="24" :lg="12">
            <el-card shadow="never" class="panel-card">
              <template #header>
                <div class="panel-head">
                  <span>一行接入</span>
                  <el-button link type="primary" @click="copySnippet">复制片段</el-button>
                </div>
              </template>
              <pre class="code-block">{{ snippet }}</pre>
              <p class="muted small">SDK 自动完成：访客 ID、30 分钟会话、page_view、SPA 路由变化、批量上报与失败重试。</p>
            </el-card>
          </el-col>
        </el-row>

        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="panel-head">
              <span>CSV 导入</span>
              <span class="muted">离线事件补录 · 最多 5000 行</span>
            </div>
          </template>
          <p class="muted small">
            表头需包含
            <code>eventName</code
            >，可选列：eventUid、subjectType、subjectId、anonymousId、sessionId、occurredTime、pageUrl、keyword、referrer、channel、deviceType、os、browser、country、province、city、props。
          </p>
          <el-input
            v-model="csvText"
            type="textarea"
            :rows="8"
            placeholder="eventName,anonymousId,sessionId,occurredTime,pageUrl&#10;page_view,demo-visitor,demo-session,2026-10-19 10:00:00,/home"
          />
          <div class="import-actions">
            <el-button @click="fillCsvSample">填入示例</el-button>
            <el-button v-hasPermi="['analytics:center:edit']" type="primary" :loading="importing" @click="submitCsv">导入</el-button>
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 会话详情抽屉 -->
    <el-drawer v-model="sessionDrawer" size="560px" :title="sessionDetail?.session?.visitorId || '访问详情'">
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
        <el-timeline>
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
        <el-empty v-if="!sessionDetail.timeline.length" :image-size="50" description="没有匹配到事件" />
      </template>
    </el-drawer>

    <!-- 漏斗编辑 -->
    <el-dialog v-model="funnelDialog" :title="funnelForm.funnelId ? '编辑漏斗' : '新建漏斗'" width="620px">
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
import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import VChart from 'vue-echarts';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  deleteAnalyticsFunnel,
  getAnalyticsBreakdown,
  getAnalyticsOverview,
  getAnalyticsRetention,
  getAnalyticsSession,
  getAnalyticsTrend,
  importAnalyticsCsv,
  listAnalyticsFunnels,
  listAnalyticsSites,
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
  type AnalyticsRetention,
  type AnalyticsSession,
  type AnalyticsSessionDetail,
  type AnalyticsSessionQuery,
  type AnalyticsSite,
  type AnalyticsTrendPoint
} from '@/api/dcs/core/analytics';

use([CanvasRenderer, LineChart, BarChart, GridComponent, LegendComponent, TooltipComponent]);

const source = ref('web');
const days = ref(30);
const tab = ref('overview');
const loading = ref(false);
const rebuilding = ref(false);

const overview = ref<Partial<AnalyticsOverview>>({});
const trend = ref<AnalyticsTrendPoint[]>([]);
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

const sites = ref<AnalyticsSite[]>([]);
const siteLoading = ref(false);
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

const snippet = computed(() => {
  const siteKey = sites.value[0]?.siteKey || 'sd_demo_site';
  // 不能在 SFC 里直接写结束标签，否则会被 Vue 解析器当成脚本块结束
  const open = '<' + 'script';
  const close = '</' + 'script>';
  return [
    `${open} src="/sdk/smartdock-analytics.js">${close}`,
    `${open}>`,
    '  SmartdockAnalytics.init({',
    `    siteKey: '${siteKey}',`,
    "    endpoint: location.origin + '/analytics/collect'",
    '  });',
    close
  ].join('\n');
});

// ---------------------------------------------------------------- 概览
const trendOption = computed<any>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['PV', 'UV', '访问次数'], right: 10, top: 0, itemWidth: 12, itemHeight: 8 },
  grid: { left: 48, right: 20, top: 36, bottom: 32 },
  xAxis: { type: 'category', boundaryGap: false, data: trend.value.map((p) => p.statDate) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      name: 'PV',
      type: 'line',
      smooth: true,
      symbolSize: 5,
      areaStyle: { opacity: 0.08 },
      data: trend.value.map((p) => Number(p.pv || 0))
    },
    { name: 'UV', type: 'line', smooth: true, symbolSize: 5, data: trend.value.map((p) => Number(p.uv || 0)) },
    { name: '访问次数', type: 'line', smooth: true, symbolSize: 5, data: trend.value.map((p) => Number(p.visits || 0)) }
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
    return;
  }
  funnelResult.value = await previewAnalyticsFunnel({ funnelId: funnelId.value, days: days.value });
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
  const alpha = Math.min(0.85, 0.08 + rate / 130);
  return { background: `rgba(64, 158, 255, ${alpha})`, color: alpha > 0.45 ? '#fff' : 'var(--el-text-color-primary)' };
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

// ---------------------------------------------------------------- 自定义分析
const customOption = computed<any>(() => {
  const isDay = customForm.dimension === 'day';
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 52, right: 20, top: 24, bottom: isDay ? 32 : 80 },
    xAxis: {
      type: 'category',
      data: customRows.value.map((p) => p.label),
      axisLabel: isDay ? { rotate: 0 } : { rotate: 35, interval: 0, overflow: 'truncate', width: 90 }
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        type: isDay ? 'line' : 'bar',
        smooth: true,
        symbolSize: 5,
        areaStyle: isDay ? { opacity: 0.08 } : undefined,
        barMaxWidth: 28,
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

// ---------------------------------------------------------------- 采集
const loadSites = async () => {
  siteLoading.value = true;
  try {
    sites.value = await listAnalyticsSites();
  } finally {
    siteLoading.value = false;
  }
};

const copySnippet = async () => {
  try {
    await navigator.clipboard.writeText(snippet.value);
    ElMessage.success('已复制接入片段');
  } catch {
    ElMessage.warning('浏览器不允许剪贴板写入，请手动复制');
  }
};

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
      await loadFunnels();
    } else if (tab.value === 'retention') {
      await loadRetention();
    } else if (tab.value === 'sessions') {
      await loadSessions();
    } else if (tab.value === 'custom') {
      await loadCustom();
    } else if (tab.value === 'collect') {
      await loadSites();
    }
  } finally {
    loading.value = false;
  }
};

const onTabChange = async () => {
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
const deltaValue = (key: string) => Number(overview.value.delta?.[key] ?? 0);
const deltaText = (key: string) => {
  const value = deltaValue(key);
  if (!Number.isFinite(value) || value === 0) return '环比持平';
  return `环比 ${value > 0 ? '+' : ''}${value.toFixed(1)}%`;
};
const deltaTone = (key: string) => {
  const value = deltaValue(key);
  if (!Number.isFinite(value) || value === 0) return 'flat';
  const positiveIsGood = key !== 'bounceRate';
  return value > 0 === positiveIsGood ? 'good' : 'bad';
};

onMounted(async () => {
  await Promise.all([loadOverview(), loadBreakdowns(), loadSites()]);
});
</script>

<style scoped>
.analytics-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.hero-card :deep(.el-card__body) {
  padding: 18px 20px;
}
.hero-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}
.hero-copy h3 {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 650;
}
.hero-copy p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  max-width: 760px;
}
.hero-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.meta-line {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
}
.analytics-tabs {
  min-height: 400px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
.stat {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 12px 14px;
  background: var(--el-fill-color-blank);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stat strong {
  font-size: 21px;
  font-variant-numeric: tabular-nums;
}
.delta {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.delta.good {
  color: var(--el-color-success);
}
.delta.bad {
  color: var(--el-color-danger);
}
.delta.flat {
  color: var(--el-text-color-placeholder);
}
.panel-card {
  margin-bottom: 14px;
}
.panel-card :deep(.el-card__body) {
  padding-top: 12px;
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
.breakdown-row :deep(.el-col) {
  margin-bottom: 14px;
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
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
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
  background: linear-gradient(90deg, var(--el-color-primary), var(--el-color-primary-light-3));
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
.retention-table :deep(.el-table__cell) {
  padding: 4px 0;
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
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
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
.code-block {
  margin: 0;
  padding: 12px;
  border-radius: 8px;
  background: #1e1e1e;
  color: #d4d4d4;
  font-size: 12px;
  line-height: 1.55;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.import-actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}
@media (max-width: 1200px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
