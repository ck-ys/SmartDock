<template>
  <div class="dcs-ops-page">
    <DcsOperationsPageHeader
      title="AI 模型配置"
      eyebrow="AI 配置与用量"
      subtitle="统一管理各租户的大模型供应商、密钥、价格参数与连接健康状态，确保业务调用可用且成本可控。"
      :metrics="headerMetrics"
    >
      <template #actions>
        <el-button plain icon="DataAnalysis" @click="goUsageDetail">用量明细</el-button>
        <el-button icon="Refresh" @click="getList">刷新</el-button>
      </template>
    </DcsOperationsPageHeader>

    <!-- 批5(Q7) 三页签 → 批1015c Q1 两页签 IA——模型配置(默认)/价格目录（「默认模型」页签撤下,
         换默认入口分家：助手对话=「AI 助手管理 → 模型选择」、后台 AI=「用户画像 → 画像设置」（批2 Q4 搬家））;
         用量明细整页外迁 ai-usage(页头按钮直达);
         菜单IA重组批3(2026-10-16) 识别域名管理并入驻第 4 页签(原独立菜单 9100 已删,
         页签权限沿用本页菜单 9060 的 dcs:ai:list 锚) -->
    <el-tabs v-model="activeTab" class="ai-config-tabs">
      <el-tab-pane label="模型配置" name="models">
        <!-- 增补批(2026-10-10)：K 批「当前生效模型卡」横条已撤——生效模型看默认★与卡行状态即可，
             信息不上两遍；换默认入口分家（批2 Q4）：助手对话在 AI 助手管理、后台 AI 在画像设置 -->
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="mb-[10px]">
            <el-card shadow="never" class="dcs-ops-filter-card">
              <el-form ref="queryFormRef" :model="queryParams" :inline="true">
                <el-form-item label="租户编号" prop="tenantId">
                  <el-input v-model="queryParams.tenantId" placeholder="请输入租户编号" clearable @keyup.enter="handleQuery" />
                </el-form-item>
                <el-form-item label="模型厂商" prop="provider">
                  <el-select v-model="queryParams.provider" placeholder="全部厂商" clearable style="width: 160px">
                    <el-option v-for="item in providerOptions" :key="item.provider" :label="item.providerName" :value="item.provider" />
                  </el-select>
                </el-form-item>
                <el-form-item label="使用类型" prop="useType">
                  <el-select v-model="queryParams.useType" placeholder="全部类型" clearable style="width: 140px">
                    <el-option label="AI 调用" value="CHAT" />
                    <el-option label="向量化" value="EMBEDDING" />
                  </el-select>
                </el-form-item>
                <el-form-item label="可用性" prop="enabled">
                  <el-select v-model="queryParams.enabled" placeholder="全部状态" clearable style="width: 120px">
                    <el-option label="可用" value="1" />
                    <el-option label="停用" value="0" />
                  </el-select>
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
                  <el-button icon="Refresh" @click="resetQuery">重置</el-button>
                </el-form-item>
              </el-form>
            </el-card>
          </div>
        </transition>

        <!-- R1(2026-10-10 拍板A) 厂商中心：左=已接入厂商列表（真厂标+模型数+接入点），右=厂商详情
             （接入信息+钥匙一处管全厂+厂商级余额+模型卡行）；未接入的预设厂商走「+ 新增厂商」下拉。
             旧表格能力全数迁移：新增/一键预设/修改/删除(含勾选批量)/启停/★默认/连通测试/批量健康
             检查/参数——不倒退 -->
        <!-- 业务批(1015) Q1 拆星 + 批1015c Q1 入口收敛：本页只回答"有哪些模型、各自多少钱、
             钥匙健不健康"；换默认入口分家（批2 Q4 搬家，2026-10-16）：助手对话默认=「AI 助手管理」
             页模型选择卡，后台 AI 默认=「用户画像 → 画像设置」弹窗。
             接口默认★仅作回退兜底（卡行新增可标，解析链兼容存量不动） -->
        <el-alert type="info" :closable="false" show-icon class="mb-2 vc-prio-banner">
          <template #title
            >本页只管：厂商 · 模型 · 价格 · 钥匙。要换默认模型？助手对话去「AI 助手管理 → 模型选择」，画像/评分等后台 AI 去「用户画像 →
            画像设置」</template
          >
          AI 助手对话默认在「AI 助手管理」设；后台 AI 默认（画像/诊断/评分/字段生成）已搬到「用户画像 → 画像设置」里设；本页卡的 ★ 只作接口回退兜底
        </el-alert>
        <div v-loading="loading" class="vendor-center">
          <aside class="vc-nav">
            <div class="vc-nav-head">
              <span>厂商</span>
              <span class="vc-nav-count">{{ vendorGroups.length }}</span>
            </div>
            <div
              v-for="g in vendorGroups"
              :key="g.provider"
              class="vc-nav-item"
              :class="{ 'is-active': g.provider === activeGroup?.provider }"
              role="button"
              tabindex="0"
              @click="selectVendor(g.provider)"
              @keydown.enter="selectVendor(g.provider)"
            >
              <ProviderLogo :provider="g.provider" :size="26" />
              <div class="vc-nav-main">
                <div class="vc-nav-name">{{ getProviderName(g.provider) }}</div>
                <div class="vc-nav-meta">{{ g.models.length }} 个模型</div>
              </div>
              <!-- 业务批(1015) Q1 拆星：左栏星标 chip 退场（批1015c Q1 起默认管理在「AI 助手管理」页）；
                   钥匙健康角标保留 -->
              <!-- 8+=B(2026-10-15)：钥匙健康角标——不点开管理钥匙也能扫一眼每家厂商几把钥匙、健康与否 -->
              <el-tooltip v-if="keyBadgeOf(g).level !== 'none'" :content="keyBadgeOf(g).tip" placement="top">
                <!-- Q6：角标拆"本租户 N + 其他租户 M"——与钥匙管理页签口径一致，不再 2 vs 1 打架 -->
                <span class="ui-pill vc-key-chip" :class="keyPillClass(keyBadgeOf(g).level)"
                  >🔑{{ keyBadgeOf(g).own }}{{ keyBadgeOf(g).others ? '+' + keyBadgeOf(g).others : '' }}</span
                >
              </el-tooltip>
              <el-tooltip
                :content="g.accessConfigured ? '已配置厂商接入（钥匙一处改全厂生效）' : '未配置厂商接入（行内存量钥匙回退）'"
                placement="top"
              >
                <span class="vc-dot" :class="g.accessConfigured ? 'on' : 'off'"></span>
              </el-tooltip>
            </div>
            <el-dropdown v-hasPermi="['dcs:ai:add']" trigger="click" class="vc-nav-add-wrap" @command="handleQuickAdd">
              <div class="vc-nav-add" role="button" tabindex="0">
                <el-icon><Plus /></el-icon>新增厂商
              </div>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="item in providerOptions" :key="item.provider" :command="item.provider">
                    <ProviderLogo :provider="item.provider" :size="16" style="margin-right: 8px; vertical-align: -3px" />
                    {{ item.providerName }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </aside>

          <section class="vc-detail">
            <template v-if="activeGroup">
              <div class="vc-head">
                <ProviderLogo :provider="activeGroup.provider" :size="40" />
                <div class="vc-head-main">
                  <div class="vc-head-name">
                    {{ getProviderName(activeGroup.provider) }}
                    <span class="ui-pill" :class="activeGroup.accessConfigured ? 'ui-pill--pos' : 'ui-pill--muted'">
                      {{ activeGroup.accessConfigured ? '已接入 ✓' : '未接入' }}
                    </span>
                    <!-- 8+=B(2026-10-15)：钥匙健康徽标——绿=全部启用、橙=部分停用/部分查失败、红=全停用或启用中全失败 -->
                    <el-tooltip v-if="activeKeyBadge && activeKeyBadge.level !== 'none'" :content="activeKeyBadge.tip" placement="top">
                      <span class="ui-pill" :class="keyPillClass(activeKeyBadge.level)">{{ activeKeyBadge.text }}</span>
                    </el-tooltip>
                    <span v-else-if="activeKeyBadge" class="ui-pill ui-pill--muted">未配钥匙</span>
                    <!-- 业务批(1015) Q1 拆星：头部「默认★在此」黄标退场（批1015c Q1 起默认管理在「AI 助手管理」页） -->
                  </div>
                  <div class="vc-head-url">
                    {{ activeGroup.baseUrl || '未设置接口地址' }}
                    <template v-if="activeGroup.models.length && activeGroup.accessConfigured">
                      · 钥匙对全部 {{ activeGroup.models.length }} 个模型生效</template
                    >
                  </div>
                  <!-- 增补批(2026-10-10)：余额从每个模型卡行上移到厂商头部一处——同厂各模型共用一把
                       钥匙、同一账户，逐卡重复只添噪音（对齐 ZCode 厂商详情头余额位） -->
                  <div v-if="activeGroup.balanceRow" class="vc-head-balance">
                    <span class="vc-bal-label">余额</span>
                    <el-tooltip
                      :content="balanceTooltip(activeGroup.balanceRow.configId) + '（厂商级一处查询，同厂模型共用同一账户）'"
                      placement="top"
                    >
                      <span class="vc-balance" :class="'bal-' + balanceTagType(activeGroup.balanceRow.configId)">
                        <el-icon v-if="!balanceResults[activeGroup.balanceRow.configId]" class="is-loading"><Loading /></el-icon>
                        {{ balanceText(activeGroup.balanceRow.configId) }}
                      </span>
                    </el-tooltip>
                    <span class="vc-bal-sub">全厂一个账户，模型卡不再逐个显示</span>
                  </div>
                </div>
                <div class="vc-head-actions">
                  <el-button
                    v-if="activeGroup.models.length"
                    v-hasPermi="['dcs:ai:edit']"
                    plain
                    icon="Key"
                    @click="openAccessDialog(activeGroup.models[0])"
                    >管理钥匙</el-button
                  >
                  <!-- 业务批4 Q17-A（2026-10-13 拍板）：体检拆分——主键=全部（原行为），箭头=仅当前厂商卡 -->
                  <div v-if="activeGroup.models.length" v-hasPermi="['dcs:ai:list']" class="hc-split">
                    <el-button plain :loading="healthCheckLoading" icon="SetUp" class="hc-main" @click="handleHealthCheck()"
                      >一键体检（全部）</el-button
                    >
                    <el-dropdown trigger="click" @command="onHealthCheckCommand">
                      <el-button plain :loading="healthCheckLoading" icon="ArrowDown" class="hc-caret" />
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item command="scoped">
                            仅体检「{{ getProviderName(activeGroup.provider) }}」（{{ activeGroup.models.length }} 条）
                          </el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </div>
                  <el-button v-hasPermi="['dcs:ai:add']" type="primary" icon="Plus" @click="handleQuickAdd(activeGroup.provider)">添加模型</el-button>
                </div>
              </div>

              <div class="vc-toolbar">
                <el-radio-group v-model="vcTypeFilter">
                  <el-radio-button value="">全部类型</el-radio-button>
                  <el-radio-button value="CHAT">AI 调用</el-radio-button>
                  <el-radio-button value="EMBEDDING">向量化</el-radio-button>
                </el-radio-group>
                <el-radio-group v-model="vcEnabledFilter">
                  <el-radio-button value="">全部状态</el-radio-button>
                  <el-radio-button value="1">可用</el-radio-button>
                  <el-radio-button value="0">停用</el-radio-button>
                </el-radio-group>
                <span class="vc-toolbar-right">
                  <template v-if="ids.length">
                    <span class="vc-sel-count">已选 {{ ids.length }} 个</span>
                    <el-button v-hasPermi="['dcs:ai:remove']" type="danger" plain icon="Delete" @click="handleDelete()">批量删除</el-button>
                  </template>
                  <span v-else class="vc-hint">
                    {{ vendorModels.length }} 个模型<template v-if="vendorModels.length !== activeGroup.models.length"
                      >（筛自 {{ activeGroup.models.length }}）</template
                    >
                    · 勾选卡片可批量删除
                  </span>
                </span>
              </div>

              <el-empty v-if="!activeGroup.models.length" description="该厂商还没有任何模型配置" :image-size="80">
                <el-button v-hasPermi="['dcs:ai:add']" type="primary" icon="Plus" @click="handleQuickAdd(activeGroup.provider)"
                  >添加第一个模型</el-button
                >
              </el-empty>
              <el-empty v-else-if="!vendorModels.length" description="当前筛选下没有模型" :image-size="72" />
              <div v-else class="vc-models">
                <div v-for="row in vendorModels" :key="row.configId" class="vc-model" :class="{ 'is-off': row.enabled !== '1' }">
                  <el-checkbox
                    :model-value="ids.includes(row.configId)"
                    class="vc-check"
                    :aria-label="'选择 ' + row.modelName"
                    @change="toggleModelSel(row.configId)"
                  />
                  <div class="vc-model-main">
                    <div class="vc-model-name">
                      <span class="vc-model-title">{{ row.modelName }}</span>
                      <span class="ui-pill ui-pill--muted">{{ useTypeLabel(row.useType) }}</span>
                      <span class="ui-pill ui-pill--muted">{{ row.tenantId === '000000' ? '平台默认租户' : '租户 ' + row.tenantId }}</span>
                    </div>
                    <div class="vc-model-meta">
                      {{ formatContext(row.contextWindow) }} 上下文 · {{ billingModeLabel(row.billingMode) }} ·
                      <template v-if="row.accessConfigured">🔑 厂商钥匙</template>
                      <template v-else-if="row.apiKey === '****'">🔑 行内钥匙（存量）</template>
                      <template v-else>未设钥匙</template>
                      <template v-if="row.apiFormat && row.apiFormat !== 'CHAT_COMPLETIONS'">
                        · <span class="ui-pill ui-pill--warn">{{ apiFormatShort(row.apiFormat) }}</span>
                      </template>
                      · <span class="vc-updated">更新于 {{ row.updateTime || '-' }}</span>
                    </div>
                  </div>
                  <div class="vc-model-side">
                    <span class="vc-price">{{ priceText(row) }}</span>
                    <span
                      v-if="healthCheckResults[row.configId]"
                      class="ui-pill"
                      :class="healthCheckResults[row.configId].success ? 'ui-pill--pos' : 'ui-pill--neg'"
                    >
                      {{ healthCheckResults[row.configId].success ? '连通' : '异常'
                      }}{{ healthCheckResults[row.configId].latencyMs ? ' ' + healthCheckResults[row.configId].latencyMs + 'ms' : '' }}
                    </span>
                    <!-- AI 缺口批 Q9：04:00 定时巡检徽标（只读；手动连通测试是上面的实心 tag）。
                         k3 P3-4 回修：停用行不渲染——巡检跳过停用配置，旧健康行会永驻误导 -->
                    <el-tooltip v-if="row.enabled === '1' && patrolHealth[row.configId]" :content="patrolTip(row)" placement="top">
                      <span class="ui-pill" :class="patrolHealth[row.configId].status === 'OK' ? 'ui-pill--pos' : 'ui-pill--neg'">
                        巡检{{ patrolHealth[row.configId].status === 'OK' ? '正常' : '异常' }}
                      </span>
                    </el-tooltip>
                    <el-popover placement="top" trigger="hover" width="230">
                      <template #reference>
                        <span class="vc-param" title="查看参数">参数</span>
                      </template>
                      <div class="param-grid">
                        <span>上下文窗口</span><b>{{ formatContext(row.contextWindow) }} token</b> <span>温度</span
                        ><b>{{ row.temperature ?? '-' }}</b> <span>超时</span><b>{{ row.timeoutSeconds ?? '-' }} 秒</b> <span>思考程度</span
                        ><b>{{ reasoningEffortLabel(row.reasoningEffort) }}</b>
                      </div>
                    </el-popover>
                  </div>
                  <div class="vc-model-actions">
                    <el-tooltip content="连通测试" placement="top">
                      <el-button v-hasPermi="['dcs:ai:edit']" link type="primary" icon="Connection" @click="handleTest(row)" />
                    </el-tooltip>
                    <el-tooltip :content="row.enabled === '1' ? '停用（急刹车：立即对所有人不可调）' : '恢复可用'" placement="top">
                      <el-button
                        v-hasPermi="['dcs:ai:edit']"
                        link
                        :type="row.enabled === '1' ? 'warning' : 'success'"
                        :icon="row.enabled === '1' ? 'CircleClose' : 'CircleCheck'"
                        @click="handleToggleEnabled(row)"
                      />
                    </el-tooltip>
                    <el-tooltip content="修改" placement="top">
                      <el-button v-hasPermi="['dcs:ai:edit']" link type="primary" icon="Edit" @click="handleUpdate(row)" />
                    </el-tooltip>
                    <el-tooltip content="删除" placement="top">
                      <el-button v-hasPermi="['dcs:ai:remove']" link type="danger" icon="Delete" @click="handleDelete(row)" />
                    </el-tooltip>
                  </div>
                </div>
              </div>
            </template>
            <el-empty v-else description="暂无模型配置——点左侧「新增厂商」开始" :image-size="90" />
          </section>
        </div>
      </el-tab-pane>

      <!-- 批5(Q7) 三页签 → 业务反馈批1015c Q1(拍板A) 两页签：「默认模型」页签撤下——
           换默认入口分家：助手对话默认在「AI 助手管理」页模型选择卡；后台 AI 默认（画像/
           评分等）已整体搬进「用户画像 → 画像设置」（批2 Q4 搬家，2026-10-16）；
           本页接口默认★只作回退兜底（卡行新增时
           仍可标★，解析链 is_default→update_time 兼容存量不动） -->
      <!-- 批5(Q7): 价格目录从抽屉升级为页签 -->
      <el-tab-pane label="价格目录" name="prices" lazy>
        <div class="price-toolbar">
          <el-button v-hasPermi="['dcs:ai:add']" type="primary" plain icon="Plus" @click="openPriceDialog()">新增价格</el-button>
          <el-button v-hasPermi="['dcs:ai:edit']" icon="Download" :loading="priceSyncing" @click="onSyncModelsDev">
            从 models.dev 同步
          </el-button>
          <el-button v-hasPermi="['dcs:ai:edit']" icon="Upload" @click="openImportDialog">JSON 导入</el-button>
          <el-button icon="Refresh" @click="loadPriceList">刷新</el-button>
          <span class="price-toolbar-hint"
            >官方价来自 models.dev（同步不覆盖手工行）；币种非 CNY 的模型会被全系统统一拒用（钱包仅支持人民币）</span
          >
        </div>
        <div class="price-table-wrap">
          <el-table v-loading="priceLoading" :data="priceList" class="ui-table">
            <el-table-column label="租户" prop="tenantId" width="90" />
            <el-table-column label="厂商" prop="provider" width="100">
              <template #default="{ row }">{{ getProviderName(row.provider) }}</template>
            </el-table-column>
            <el-table-column label="模型" prop="modelName" min-width="150" show-overflow-tooltip />
            <!-- N1 回修（视觉复核）：来源列前移到模型右边——表格横向超宽时，「这条价是哪来的」
                 是判断能不能被同步覆盖的关键信息，不能藏在滚动条后面 -->
            <el-table-column label="价格来源" width="120" align="center">
              <template #default="{ row }">
                <el-tooltip :content="priceSourceTip(row)" placement="top">
                  <span class="ui-pill" :class="pillOf(priceSourceTagType(row))">{{ priceSourceLabel(row) }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="币种" prop="currency" width="66" align="center" />
            <el-table-column label="输入/1M" width="100" align="right">
              <template #default="{ row }">{{ Number(row.inputPricePerMillion || 0).toFixed(6) }}</template>
            </el-table-column>
            <el-table-column label="缓存输入/1M" width="112" align="right">
              <template #default="{ row }">{{ Number(row.cachedInputPricePerMillion || 0).toFixed(6) }}</template>
            </el-table-column>
            <el-table-column label="输出/1M" width="100" align="right">
              <template #default="{ row }">{{ Number(row.outputPricePerMillion || 0).toFixed(6) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="70" align="center">
              <template #default="{ row }">
                <span class="ui-pill" :class="row.enabled === '1' ? 'ui-pill--pos' : 'ui-pill--muted'">{{
                  row.enabled === '1' ? '启用' : '停用'
                }}</span>
              </template>
            </el-table-column>
            <el-table-column label="来源" prop="sourceUrl" min-width="130" show-overflow-tooltip />
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ row }">
                <el-button v-hasPermi="['dcs:ai:edit']" link type="primary" icon="Edit" @click="openPriceDialog(row)" />
                <el-button v-hasPermi="['dcs:ai:remove']" link type="danger" icon="Delete" @click="handleDeletePrice(row)" />
              </template>
            </el-table-column>
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">还没有价格数据</p>
                <p class="ui-empty__hint">点「从 models.dev 同步」或「新增价格」后，模型成本会在这里逐行列出</p>
              </div>
            </template>
          </el-table>
        </div>
        <pagination
          v-show="priceTotal > 0"
          v-model:page="priceQuery.pageNum"
          v-model:limit="priceQuery.pageSize"
          :total="priceTotal"
          @pagination="loadPriceList"
        />
      </el-tab-pane>

      <!-- 业务反馈批1015b Q5/Q1/Q6：钥匙管理独立页签（表格为主 + 抽屉表单）——
           替换原 820px 弹窗：列表可扫读、新增有独立入口、编辑进抽屉有标题有返回、厂商/租户可切 -->
      <el-tab-pane label="钥匙管理" name="keys" lazy>
        <div class="keys-toolbar">
          <el-select v-model="keysProvider" filterable style="width: 190px" placeholder="厂商" @change="onKeysProviderChange">
            <el-option v-for="g in vendorGroups" :key="g.provider" :label="getProviderName(g.provider)" :value="g.provider" />
          </el-select>
          <el-select v-model="keysTenantId" filterable style="width: 250px" placeholder="看哪个租户" @change="onKeysFilterChange">
            <el-option v-if="keysTenantOptions.length > 1" :value="KEYS_ALL_TENANTS" label="全部租户（与角标同口径）" />
            <el-option v-for="t in keysTenantOptions" :key="t.tenantId" :label="tenantLabel(t)" :value="t.tenantId" />
          </el-select>
          <span class="keys-summary">{{ keysSummary }}</span>
          <div class="keys-toolbar-actions">
            <el-button v-hasPermi="['dcs:ai:edit']" type="primary" icon="Plus" @click="onAddKeyForm()">新增钥匙</el-button>
            <el-button :loading="balanceRefreshingAll" @click="onRefreshAllBalances">全部查余额</el-button>
          </div>
        </div>

        <div class="keys-table-wrap">
          <el-table v-loading="keysLoading" :data="keyList" class="ui-table">
            <el-table-column v-if="keysTenantOptions.length > 1" label="租户" min-width="150">
              <template #default="{ row }">
                <span>{{ tenantNameOf(String(row.tenantId)) }}</span>
                <span v-if="String(row.tenantId) === myTenantId" class="ui-pill ui-pill--muted ml-1">本租户</span>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="70" align="center">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.enabled !== '0'"
                  :aria-label="(row.label || '钥匙') + ' 启停'"
                  v-hasPermi="['dcs:ai:edit']"
                  @change="onToggleKey(row)"
                />
              </template>
            </el-table-column>
            <el-table-column label="名称" min-width="170">
              <template #default="{ row }">
                <span class="keys-name">{{ row.label || '未命名钥匙' }}</span>
                <span v-if="row.primary" class="ui-pill ui-pill--muted">主钥匙</span>
                <span v-if="row.enabled === '0'" class="ui-pill ui-pill--muted">已停用</span>
              </template>
            </el-table-column>
            <el-table-column label="密钥" width="150">
              <template #default="{ row }">
                <code class="keys-mask">{{ row.apiKeyMask || '—' }}</code>
              </template>
            </el-table-column>
            <el-table-column label="余额" width="130" align="right">
              <template #default="{ row }">
                <template v-if="row.balanceStatus === 'UNSUPPORTED'"><span class="keys-muted">--</span></template>
                <template v-else-if="row.balanceAmount != null">
                  <!-- Q6：Kimi/MiniMax 是订阅额度，快照单位是「剩余百分比」而不是金额 -->
                  <span v-if="row.balanceCurrency === '%'" :class="{ 'keys-zero': Number(row.balanceAmount) <= 0 }"
                    >剩余 {{ Number(row.balanceAmount).toFixed(1) }}%</span
                  >
                  <span v-else :class="{ 'keys-zero': Number(row.balanceAmount) <= 0 }"
                    >{{ row.balanceCurrency || 'CNY' }} {{ Number(row.balanceAmount).toFixed(2) }}</span
                  >
                </template>
                <template v-else><span class="keys-muted">--</span></template>
              </template>
            </el-table-column>
            <el-table-column label="最近探测" width="150">
              <template #default="{ row }">
                <span :class="{ 'keys-fail': row.balanceStatus === 'FAILED' }">
                  {{ row.balanceStatus === 'FAILED' ? fmtCheckedAt(row) + ' · 失败' : fmtCheckedAt(row) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180" align="center">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="onEditKey(row)">编辑</el-button>
                <el-button link type="primary" size="small" :loading="balanceRefreshingId === row.id" @click="onRefreshKeyBalance(row)"
                  >查余额</el-button
                >
                <el-button link type="danger" size="small" v-hasPermi="['dcs:ai:remove']" @click="onDeleteKey(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">还没有配置钥匙</p>
                <p class="ui-empty__hint">{{ keysEmptyText }}</p>
              </div>
            </template>
          </el-table>
        </div>
        <div class="keys-footnote">
          余额为厂商账户快照：手动刷新即时更新，每天 04:30
          自动刷新；多把钥匙自动轮询分摊限流，失败/余额耗尽自动切下一把，改动立即生效于该厂商全部模型。
        </div>
      </el-tab-pane>
      <el-tab-pane label="识别域名" name="domains" lazy>
        <!-- 权限对齐后端锚 dcs:ai:list（与菜单 9060 同串；dcs:ai-doc-domain:* 系审查回修，全库无此串） -->
        <DocDomainPanel v-hasPermi="['dcs:ai:list']" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialog.visible" :title="dialog.title" width="1080px" append-to-body class="ai-config-dialog">
      <el-alert type="info" :closable="false" show-icon class="mb-4">
        <template #title>
          API Key 已加密存储在「厂商接入」里——同厂商加第二个模型不用再填 Key；已有密钥可直接获取模型、查询余额和测速。换 Key
          一处改，同厂全部模型即时生效。
        </template>
      </el-alert>

      <el-form ref="configFormRef" :model="form" :rules="rules" label-width="110px">
        <el-divider content-position="left" class="section-title">基础信息</el-divider>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="租户编号" prop="tenantId">
              <el-select
                v-model="form.tenantId"
                filterable
                remote
                reserve-keyword
                clearable
                :remote-method="remoteTenantSearch"
                :loading="tenantLoading"
                placeholder="输入租户编号模糊搜索"
                style="width: 100%"
              >
                <el-option v-for="item in tenantOptions" :key="item.tenantId" :label="tenantLabel(item)" :value="item.tenantId" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模型厂商" prop="provider">
              <el-select v-model="form.provider" placeholder="请选择模型厂商" style="width: 100%" @change="applyPreset">
                <el-option v-for="item in providerOptions" :key="item.provider" :label="item.providerName" :value="item.provider" />
              </el-select>
              <!-- 快刀批 Q9：选 MiniMax 提醒先申请钥匙（钥匙管理里没有 MiniMax 钥匙时选完也跑不通） -->
              <div v-if="form.provider === 'MINIMAX'" class="field-hint">
                国内站接口地址已预填 api.minimax.cn；使用前需先到
                <a href="https://platform.minimax.cn" target="_blank" rel="noopener">MiniMax 开放平台</a>
                申请 API 钥匙并在此录入，否则模型会显示不可用。国际站(api.minimax.io)请改走「自定义」。
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="接入模板">
              <!-- Q5(2026-10-23)：20 家模板=官方地址+接口格式+候选模型一次带出；
                   国内站/国际站、opencode 网关等变体都在这里，选完仍可手改 -->
              <el-select
                v-model="selectedTemplateId"
                filterable
                clearable
                placeholder="选模板一键带出地址/格式/模型（可选）"
                style="width: 100%"
                @change="applyTemplate"
              >
                <el-option v-for="t in filteredTemplates" :key="t.templateId" :label="t.label" :value="t.templateId">
                  <span class="tpl-opt-row">
                    <span class="tpl-opt-name">{{ t.label }}</span>
                    <el-tag size="small" effect="plain" :type="t.apiFormat === 'CHAT_COMPLETIONS' ? 'info' : 'warning'">{{
                      apiFormatShort(t.apiFormat)
                    }}</el-tag>
                  </span>
                </el-option>
              </el-select>
              <div v-if="activeTemplate?.note" class="field-hint">{{ activeTemplate.note }}</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="使用类型" prop="useType">
              <el-radio-group v-model="form.useType">
                <el-radio value="CHAT">AI 调用</el-radio>
                <el-radio value="EMBEDDING">向量化</el-radio>
              </el-radio-group>
              <div class="field-hint">AI 调用: 画像诊断、AI字段、评分、规则推荐; 向量化: Phase 4 相似用户检索</div>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="Base URL" prop="baseUrl">
              <!-- 批1：标准厂商按官方地址自动接入（applyPreset 已随厂商切换预填），仅自定义厂商手填；
                   已有厂商接入时维持只读展示（地址由接入层统一管理） -->
              <el-input
                v-if="form.provider === 'CUSTOM' || accessInfo"
                v-model="form.baseUrl"
                :disabled="!!accessInfo"
                placeholder="请输入 OpenAI 兼容接口地址"
              />
              <div v-else class="field-hint">{{ form.baseUrl || presetBaseUrl(form.provider) }} · 已按厂商官方地址自动接入，无需手填</div>
              <div v-if="accessInfo" class="field-hint">接口地址由厂商接入统一管理，如需修改请点「管理钥匙」</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="接口格式" prop="apiFormat">
              <!-- Q5(2026-10-23)：三种格式与主流客户端一致；非 Chat Completions 只做连通测试 -->
              <el-select v-model="form.apiFormat" style="width: 100%">
                <el-option v-for="f in apiFormats" :key="f.value" :label="f.label" :value="f.value" />
              </el-select>
              <div class="field-hint">{{ apiFormatHint }}</div>
            </el-form-item>
          </el-col>
        </el-row>
        <div v-if="apiPathPreview" class="api-path-preview">
          <span class="api-path-label">实际请求</span>
          <code>{{ apiPathPreview }}</code>
          <span v-if="isNonChatFormat" class="ui-pill ui-pill--warn">仅连通测试</span>
        </div>
        <el-row :gutter="20">
          <el-col :span="12">
            <!-- 批B(多模型改造): 该厂商已有接入 → 钥匙由接入层统一提供, 表单不再出现 Key 输入框 -->
            <el-form-item v-if="accessInfo" label="API Key">
              <div class="access-provided">
                <span class="ui-pill ui-pill--pos">由厂商接入统一提供 ✓</span>
                <span class="access-mask">{{ accessInfo.apiKeyMask }}</span>
                <el-button v-hasPermi="['dcs:ai:edit']" link type="primary" @click="openAccessDialog()">管理钥匙</el-button>
              </div>
              <div class="field-hint">钥匙已加密保存在「厂商接入」里，同厂加模型不用再填；换钥匙一处改全厂生效</div>
            </el-form-item>
            <el-form-item v-else label="API Key" prop="apiKey">
              <el-input v-model="form.apiKey" type="password" show-password placeholder="请输入 API Key（落库加密；本厂以后加模型不用再填）" />
              <div v-if="form.configId && form.apiKeyMask" class="field-hint">当前密钥：{{ form.apiKeyMask }}。留空保存时沿用原密钥。</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模型名称" prop="modelName">
              <el-select
                v-model="form.modelName"
                filterable
                allow-create
                default-first-option
                placeholder="先获取模型，或手动输入模型名"
                style="width: 100%"
                @change="onModelNameChange"
              >
                <!-- 业务批4 Q16-A（2026-10-13 拍板）：免费模型置顶成组＋FREE 徽标；无免费标记时单组回落原形态 -->
                <template v-if="freeModelOptions.length > 0">
                  <el-option-group :label="`免费（${freeModelOptions.length}）`">
                    <el-option v-for="item in freeModelOptions" :key="item.modelId" :label="modelLabel(item)" :value="item.modelId">
                      <span class="probe-opt-row">
                        <span class="probe-opt-name">{{ modelLabel(item) }}</span>
                        <el-tag size="small" type="success" effect="plain">FREE</el-tag>
                      </span>
                    </el-option>
                  </el-option-group>
                  <!-- 回修 dspro P2-8：第二组只含非免费行，标题「付费（N）」名副其实；全免费时不渲染空组 -->
                  <el-option-group v-if="paidModelOptions.length > 0" :label="`付费（${paidModelOptions.length}）`">
                    <el-option v-for="item in paidModelOptions" :key="item.modelId" :label="modelLabel(item)" :value="item.modelId" />
                  </el-option-group>
                </template>
                <template v-else>
                  <el-option v-for="item in modelOptions" :key="item.modelId" :label="modelLabel(item)" :value="item.modelId" />
                </template>
              </el-select>
              <div class="field-hint">下拉内输入即筛选；厂商报了 0 价的免费模型自动置顶并标 FREE</div>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left" class="section-title">连通探测</el-divider>
        <div class="probe-actions">
          <el-button icon="List" :loading="probeLoading.models" :disabled="isNonChatFormat" @click="handleProbeModels">获取模型</el-button>
          <el-button icon="Wallet" :loading="probeLoading.balance" @click="handleProbeBalance">查询余额</el-button>
          <el-button icon="Connection" :loading="probeLoading.speed" :disabled="isNonChatFormat" @click="handleProbeSpeed">测速</el-button>
          <span class="probe-tip">{{
            isNonChatFormat ? '当前接口格式仅支持保存后的连通测试（模型列表/测速按 OpenAI 兼容协议实现）' : '测速会发起一次极小模型调用'
          }}</span>
        </div>
        <el-descriptions v-if="balanceInfo || speedInfo" :column="2" border class="probe-result">
          <el-descriptions-item v-if="balanceInfo" label="余额状态">
            <span class="ui-pill" :class="balanceInfo.available ? 'ui-pill--pos' : 'ui-pill--warn'">{{ balanceInfo.message || '已返回' }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="balanceInfo && balanceInfo.balances?.length" label="余额">
            <span v-for="item in balanceInfo.balances" :key="`${item.currency}-${item.totalBalance}`" class="mr-2">
              {{ item.currency || 'CNY' }} {{ item.totalBalance || '-' }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item v-if="speedInfo" label="测速">
            <span class="ui-pill" :class="speedInfo.success ? 'ui-pill--pos' : 'ui-pill--neg'">{{
              speedInfo.latencyMs ? `${speedInfo.latencyMs} ms` : speedInfo.message
            }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="speedInfo" label="模型响应" :span="2">
            <span class="probe-message">{{ speedInfo.message }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <!-- K 批二期：技术参数收进高级折叠（一般不用动；有自定义值时自动展开并亮徽标） -->
        <el-collapse v-model="advancedOpen" class="ai-config-adv">
          <el-collapse-item name="adv">
            <template #title>
              <span class="ai-config-adv__title">高级设置：模型默认参数（一般不用动）</span>
              <span v-if="advancedCustomCount > 0" class="ui-pill ui-pill--warn ai-config-adv__badge">已自定义 {{ advancedCustomCount }} 项</span>
              <el-tooltip
                placement="top"
                content="这里只是各调用方的默认值：AI 助手会话按用户所选思考档即时覆盖，单发接口也可按请求带参覆盖，没传才回落到这里"
              >
                <el-icon class="ai-config-adv__info"><InfoFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-divider content-position="left" class="section-title">模型参数</el-divider>
            <div class="config-section-grid">
              <div class="config-section-card">
                <div class="section-card-title">推理参数</div>
                <el-row :gutter="16">
                  <el-col :span="12">
                    <el-form-item label="上下文" prop="contextWindow">
                      <!-- 批C 回修（视觉席 P1）：值+后缀拼一起在半宽列被硬裁成「1000K（模型自」——
                           后缀信息下方 field-hint 已完整表述，输入框只留纯值 -->
                      <el-input :model-value="formatContext(form.contextWindow)" disabled />
                      <div class="field-hint">
                        模型出厂能力（自带），由能力目录按模型名自动带出，不可改
                        <span v-if="capabilityMatched" class="ui-pill ui-pill--pos ml-1">已按「{{ form.modelName }}」自动匹配</span>
                      </div>
                    </el-form-item>
                    <el-form-item label="最大输出" prop="maxTokens">
                      <el-input-number
                        v-model="form.maxTokens"
                        :min="256"
                        :max="131072"
                        :step="1024"
                        controls-position="right"
                        placeholder="留空=模型默认"
                        style="width: 100%"
                      />
                      <div class="max-tokens-chips">
                        <el-button
                          v-for="c in [4096, 16384, 65536, 131072]"
                          :key="c"
                          size="small"
                          text
                          type="primary"
                          :class="{ 'chip-on': form.maxTokens === c }"
                          @click="form.maxTokens = c"
                        >
                          {{ c >= 1024 ? c / 1024 + 'K' : c }}
                        </el-button>
                        <el-button size="small" text @click="form.maxTokens = undefined">清空</el-button>
                      </div>
                      <div class="field-hint">单次回答最多吐多少 token；长报告/大表格调大，防截断</div>
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item label="温度" prop="temperature">
                      <el-input-number
                        v-model="form.temperature"
                        :min="0"
                        :max="2"
                        :step="0.1"
                        :precision="2"
                        controls-position="right"
                        style="width: 100%"
                      />
                      <div class="field-hint">采样温度，0 ~ 2</div>
                    </el-form-item>
                    <el-form-item label="超时" prop="timeoutSeconds">
                      <el-input-number v-model="form.timeoutSeconds" :min="5" :max="300" controls-position="right" style="width: 100%" />
                      <div class="field-hint">单位秒，5 ~ 300</div>
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-form-item label="接口默认思考档">
                  <el-select v-model="form.reasoningEffort" placeholder="关闭（默认）" clearable style="width: 100%">
                    <el-option label="关闭（标准模式）" value="" />
                    <el-option label="Low（快速思考）" value="low" />
                    <el-option label="Medium（中等思考）" value="medium" />
                    <el-option label="High（深度思考）" value="high" />
                    <el-option label="Max（最大思考）" value="max" />
                  </el-select>
                  <div class="field-hint">
                    <el-icon class="hint-info"><InfoFilled /></el-icon>
                    只管<b>接口调用</b>没带思考参数时的默认档；AI 助手会话按用户在聊天里选的「快速 / 平衡 / 深入」即时覆盖，不读这里。
                    DeepSeek-R1/o-series 等 reasoner 模型适用，开启后温度锁定为 0。
                  </div>
                </el-form-item>
              </div>

              <div class="config-section-card">
                <div class="section-card-title">计费与余额</div>
                <el-row :gutter="16">
                  <!-- 业务批4 Q4 视觉回修：窄表单列里 9 字选项「官方价＋每次服务费」会省略号截断——计费模式独占整行 -->
                  <el-col :span="24">
                    <el-form-item label="计费模式" prop="billingMode">
                      <el-select v-model="form.billingMode" style="width: 100%" @change="syncInlinePriceForm">
                        <el-option label="官方 token 计费" value="OFFICIAL_TOKEN" />
                        <el-option label="固定单价回退" value="FIXED_PER_CALL" />
                        <!-- 业务批4 Q4-A（2026-10-13 拍板）：官方价打底＋每次调用加收固定服务费 -->
                        <el-option label="官方价＋每次服务费" value="OFFICIAL_PLUS_FEE" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item :label="form.billingMode === 'OFFICIAL_PLUS_FEE' ? '每次服务费' : '调用单价'" prop="unitPricePerCall">
                      <el-input-number
                        v-model="form.unitPricePerCall"
                        :precision="4"
                        :step="0.01"
                        :min="0"
                        controls-position="right"
                        style="width: 100%"
                      />
                      <div class="field-hint">{{ unitPriceHint }}</div>
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item label="服务费率" prop="serviceFeeRate">
                      <el-input-number
                        v-model="form.serviceFeeRate"
                        :precision="2"
                        :step="1"
                        :min="0"
                        :max="1000"
                        controls-position="right"
                        style="width: 100%"
                      />
                      <div class="field-hint">按官方 token 原始成本加收百分比；10 表示 +10%（仅「官方 token 计费」模式生效）</div>
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item label="余额查询" prop="balanceQueryEnabled">
                      <el-switch v-model="form.balanceQueryEnabled" active-value="1" inactive-value="0" active-text="自动" inactive-text="不查" />
                      <div class="field-hint">第三方自定义或无余额接口的厂商可关闭</div>
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <!-- 业务反馈批1015b Q13：积分倍率此前零编辑入口只能改库；订阅租户每回合扣分=基础分×此倍率 -->
                    <el-form-item label="积分倍率" prop="creditMultiplier">
                      <el-input-number
                        v-model="form.creditMultiplier"
                        :precision="1"
                        :step="0.5"
                        :min="0.5"
                        :max="20"
                        controls-position="right"
                        style="width: 100%"
                      />
                      <div class="field-hint">
                        ×1 免费档 / ×2 经济 / ×3 标准 / ≥×4 旗舰；留空视同 ×1，保存即刻生效（积分套餐页倍率表只读展示同值）
                      </div>
                    </el-form-item>
                  </el-col>
                </el-row>
              </div>
            </div>

            <div v-if="form.billingMode === 'OFFICIAL_TOKEN' || form.billingMode === 'OFFICIAL_PLUS_FEE'" class="official-price-panel">
              <div class="official-price-head">
                <div>
                  <div class="official-price-title">官方 token 计费参数</div>
                  <div class="official-price-desc">用于 AI 用量明细按官方输入/缓存输入/输出 token 单价计算成本，可按租户和模型微调。</div>
                </div>
                <span class="ui-pill" :class="inlinePriceForm.priceId ? 'ui-pill--pos' : 'ui-pill--warn'">{{
                  inlinePriceForm.priceId ? '已匹配价格' : '待新增价格'
                }}</span>
              </div>
              <el-row :gutter="16">
                <el-col :span="6">
                  <el-form-item label="租户">
                    <el-input v-model="inlinePriceForm.tenantId" placeholder="000000 表示全局" />
                  </el-form-item>
                </el-col>
                <el-col :span="6">
                  <el-form-item label="币种">
                    <el-select
                      v-model="inlinePriceForm.currency"
                      filterable
                      allow-create
                      default-first-option
                      clearable
                      placeholder="自动匹配，可输入如 CNY/USD"
                      style="width: 100%"
                      @change="normalizeCurrencyInput(inlinePriceForm)"
                      @blur="normalizeCurrencyInput(inlinePriceForm)"
                    >
                      <el-option v-for="item in currencyOptions" :key="item.value" :label="item.label" :value="item.value" />
                    </el-select>
                    <div class="field-hint">默认按浏览器语言匹配，也可直接输入三位币种代码</div>
                  </el-form-item>
                </el-col>
                <el-col :span="6">
                  <el-form-item label="厂商">
                    <el-input :model-value="getProviderName(form.provider || '')" disabled />
                  </el-form-item>
                </el-col>
                <el-col :span="6">
                  <el-form-item label="模型">
                    <el-input v-model="inlinePriceForm.modelName" placeholder="具体模型或 *" />
                  </el-form-item>
                </el-col>
              </el-row>
              <el-row :gutter="16">
                <el-col :span="8">
                  <el-form-item label="输入/1M">
                    <el-input-number
                      v-model="inlinePriceForm.inputPricePerMillion"
                      :precision="6"
                      :step="0.1"
                      :min="0"
                      controls-position="right"
                      style="width: 100%"
                    />
                  </el-form-item>
                </el-col>
                <el-col :span="8">
                  <el-form-item label="缓存输入/1M">
                    <el-input-number
                      v-model="inlinePriceForm.cachedInputPricePerMillion"
                      :precision="6"
                      :step="0.1"
                      :min="0"
                      controls-position="right"
                      style="width: 100%"
                    />
                  </el-form-item>
                </el-col>
                <el-col :span="8">
                  <el-form-item label="输出/1M">
                    <el-input-number
                      v-model="inlinePriceForm.outputPricePerMillion"
                      :precision="6"
                      :step="0.1"
                      :min="0"
                      controls-position="right"
                      style="width: 100%"
                    />
                  </el-form-item>
                </el-col>
              </el-row>
              <el-form-item label="来源 URL">
                <div style="display: flex; gap: 8px; width: 100%">
                  <el-input
                    v-model="inlinePriceForm.sourceUrl"
                    placeholder="主流模型可不填(自动查聚合库); 国产小厂填官方价格页 URL"
                    style="flex: 1"
                  />
                  <el-button v-hasPermi="['dcs:ai:edit']" type="success" plain icon="Search" :loading="priceDetecting" @click="detectPrice"
                    >智能识别</el-button
                  >
                </div>
              </el-form-item>
              <div class="official-price-actions">
                <span>{{ inlinePriceHint }}</span>
                <div>
                  <el-button plain @click="syncInlinePriceForm">重新匹配</el-button>
                  <el-button v-hasPermi="['dcs:ai:edit']" type="primary" :loading="inlinePriceSaving" @click="saveInlinePrice"
                    >保存价格参数</el-button
                  >
                </div>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>

        <el-divider content-position="left" class="section-title">其它</el-divider>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="可用性" prop="enabled">
              <el-radio-group v-model="form.enabled">
                <el-radio label="1">可用（保存即可被调用）</el-radio>
                <el-radio label="0">停用</el-radio>
              </el-radio-group>
              <div class="field-hint">
                新增时即可标为默认★（接口回退兜底）；助手对话默认在「AI 助手管理 → 模型选择」设，后台 AI 默认在「用户画像 → 画像设置」设
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="备注" prop="remark">
              <el-input v-model="form.remark" placeholder="可记录账号用途、额度或模型选择原因" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="cancel">取消</el-button>
          <el-button :loading="buttonLoading" :disabled="accessLoading" type="primary" @click="submitForm">确定</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 业务反馈批1015b Q5：钥匙编辑抽屉（原 820px 弹窗拆成「钥匙管理页签 + 本抽屉表单」；
         新增入口在页签工具栏「新增钥匙」，不再用「改为添加新钥匙」模式开关） -->
    <el-drawer v-model="keyDrawer.visible" :title="keyDrawer.title" size="500px" append-to-body class="ai-key-drawer">
      <el-alert type="info" :closable="false" show-icon class="mb-2">
        <template #title>
          {{
            accessForm.id
              ? '修改这把钥匙：留空的密钥沿用当前值，保存后立即生效于该厂商全部模型。'
              : '新增一把钥匙：与已有钥匙自动轮询分摊限流，失败/余额耗尽自动切下一把。'
          }}
        </template>
      </el-alert>
      <el-form ref="accessFormRef" :model="accessForm" :rules="accessRules" :validate-on-rule-change="false" label-width="110px">
        <el-form-item label="租户 / 厂商">
          <span>{{ tenantNameOf(String(accessForm.tenantId)) }} · {{ getProviderName(String(accessForm.provider)) }}</span>
        </el-form-item>
        <el-form-item label="钥匙标签" prop="label">
          <el-input v-model="accessForm.label" placeholder="如「主号 / 备1」，留空自动编号" style="width: 320px" />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <!-- 批1(2026-10-21 用户需求)：选了厂商就不该让人手填 URL——标准厂商按官方地址
               自动接入（提交时后端同口径兜底补齐），只有「自定义」厂商显示手填框 -->
          <el-input v-if="accessForm.provider === 'CUSTOM'" v-model="accessForm.baseUrl" placeholder="自定义厂商必须填写 OpenAI 兼容接口地址" />
          <div v-else class="field-hint">
            {{ accessForm.baseUrl || presetBaseUrl(accessForm.provider) || '选择厂商后按官方地址自动接入' }} ·
            已按厂商官方地址自动接入，无需手填；要走中转/代理地址请选「自定义」厂商
          </div>
        </el-form-item>
        <el-form-item label="接口格式" prop="apiFormat">
          <el-select v-model="accessForm.apiFormat" style="width: 100%">
            <el-option v-for="f in apiFormats" :key="f.value" :label="f.label" :value="f.value" />
          </el-select>
          <div class="field-hint">
            该厂端点的协议类型；对话主链需 Chat Completions，其它格式仅用于连通测试
          </div>
          <div v-if="keyPathPreview" class="api-path-preview api-path-preview--drawer">
            <span class="api-path-label">实际请求</span>
            <code>{{ keyPathPreview }}</code>
          </div>
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input
            v-model="accessForm.apiKey"
            type="password"
            show-password
            :placeholder="accessHasKey ? '留空=沿用当前密钥' : '新增钥匙必须填写 API Key'"
          />
          <div v-if="accessHasKey" class="field-hint">当前密钥：{{ accessMaskInDialog }}</div>
        </el-form-item>
        <el-form-item label="控制台链接" prop="consoleUrl">
          <el-input v-model="accessForm.consoleUrl" placeholder="可选：厂商控制台直达链接（充值/查用量）" />
          <div v-if="accessForm.consoleUrl" class="field-hint">
            <a :href="accessForm.consoleUrl" target="_blank" rel="noopener">打开厂商控制台 ↗</a>
          </div>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="accessForm.remark" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="keyDrawer.visible = false">取 消</el-button>
        <el-button :loading="accessSubmitting" type="primary" @click="submitAccess">保 存</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="priceDialog.visible" :title="priceDialog.title" width="720px" append-to-body>
      <el-form ref="priceFormRef" :model="priceForm" label-width="120px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="租户编号">
              <el-input v-model="priceForm.tenantId" placeholder="000000 表示全局默认" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="厂商">
              <el-select v-model="priceForm.provider" style="width: 100%">
                <el-option v-for="item in providerOptions" :key="item.provider" :label="item.providerName" :value="item.provider" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="模型名称">
          <el-input v-model="priceForm.modelName" placeholder="可填具体模型，* 表示厂商通配" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="币种">
              <el-select
                v-model="priceForm.currency"
                filterable
                allow-create
                default-first-option
                clearable
                placeholder="自动匹配，可输入如 CNY/USD"
                style="width: 100%"
                @change="normalizeCurrencyInput(priceForm)"
                @blur="normalizeCurrencyInput(priceForm)"
              >
                <el-option v-for="item in currencyOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="输入/1M">
              <el-input-number
                v-model="priceForm.inputPricePerMillion"
                :precision="6"
                :step="0.1"
                :min="0"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="缓存输入/1M">
              <el-input-number
                v-model="priceForm.cachedInputPricePerMillion"
                :precision="6"
                :step="0.1"
                :min="0"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="输出/1M">
              <el-input-number
                v-model="priceForm.outputPricePerMillion"
                :precision="6"
                :step="0.1"
                :min="0"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="状态">
              <el-switch v-model="priceForm.enabled" active-value="1" inactive-value="0" active-text="启用" inactive-text="停用" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="手工覆盖">
              <el-switch v-model="priceForm.manualOverride" active-value="1" inactive-value="0" active-text="锁定" inactive-text="跟随" />
              <div class="field-hint">锁定后「从 models.dev 同步」不会覆盖本行</div>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="来源 URL">
          <el-input v-model="priceForm.sourceUrl" placeholder="官方价格页 URL" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="priceForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="priceSubmitting" @click="submitPriceForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- Q6(2026-10-23)：JSON 导入——批量补价格（自建价目表/厂商对账表导出后直接粘贴） -->
    <el-dialog v-model="importDialog.visible" title="JSON 导入价格" width="680px" append-to-body>
      <el-alert type="info" :closable="false" show-icon class="mb-2">
        <template #title>粘贴价格数组，按 租户+厂商+模型 有则更新无则新增；标记 manualOverride=true 的行不会被 models.dev 同步覆盖。</template>
      </el-alert>
      <el-form label-width="90px">
        <el-form-item label="导入到">
          <span class="ui-pill ui-pill--muted">当前登录租户</span>
          <span class="import-foot-hint ml-2">全平台价格请用平台默认租户账号导入</span>
        </el-form-item>
        <el-form-item label="价格 JSON">
          <el-input v-model="importForm.json" type="textarea" :rows="12" :placeholder="importJsonSample" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="import-foot-hint">字段：provider / modelName / input / output / cachedInput（每百万 token 单价）/ currency / manualOverride</span>
        <el-button @click="importDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="importSubmitting" @click="submitImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="DcsAiConfig" lang="ts">
/**
 * AI 模型配置页。
 *
 * 职责:
 * - 管理 CHAT(AI 调用) 与 EMBEDDING(向量化) 两类模型配置: 厂商/Base URL/API Key/上下文窗口/计费
 * - 一键预设(选厂商预填 Base URL+默认模型) + 在线探测(模型列表/余额/测速, 复用已保存密钥或明文)
 * - 官方 Token 计费参数维护(按 厂商+模型+租户 维度匹配, 支持文档自动识别价格)
 * - 余额批量轮询(每 30s, 业务批3-②: 401 停表/失败不降级/页面不可见暂停) + 活跃配置一键切换 + 批量健康检查 + AI 用量汇总
 *
 * 关键机制:
 * - syncInlinePriceForm 优先调 effective API 回填, 失败回退本地 priceList 按 matchPriceScore 排序匹配
 * - 编辑态 apiKey 留空表示沿用原密钥, 探测按钮走 probeSaved* 系列接口用服务端已存密钥
 * - refreshBalances 轮询失败不再降级逐卡探测(防 403 风暴); 手动「查余额」为单卡 probe(handleProbeBalance), 不经此函数
 */
import { InfoFilled, Loading, Plus } from '@element-plus/icons-vue';
import { isBusinessErrorCode } from '@/utils/businessError';
import ProviderLogo from './ProviderLogo.vue';
import DocDomainPanel from './DocDomainPanel.vue';
import {
  addDcsAiConfig,
  delDcsAiConfig,
  getDcsAiConfig,
  listDcsAiConfig,
  listDcsAiProviderPresets,
  listDcsAiProviderDicts,
  listDcsAiProviderTemplates,
  listDcsAiApiFormats,
  syncDcsAiModelPricesFromModelsDev,
  importDcsAiModelPrices,
  detectDcsAiPrice,
  getDcsAiEffectivePrice,
  upsertDcsAiModelPrice,
  probeDcsAiBalance,
  probeDcsAiModels,
  probeDcsAiSpeed,
  probeSavedDcsAiBalance,
  probeSavedDcsAiModels,
  probeSavedDcsAiSpeed,
  getDcsAiBalances,
  testDcsAiConfig,
  updateDcsAiConfig,
  healthCheckDcsAiConfigs,
  getDcsAiUsageSummary,
  listDcsAiModelPrice,
  addDcsAiModelPrice,
  updateDcsAiModelPrice,
  delDcsAiModelPrice,
  matchAiCapability,
  getDcsAiProviderAccess,
  addDcsAiProviderAccessKey,
  updateDcsAiProviderAccess,
  listDcsAiProviderAccessKeys,
  changeDcsAiKeyStatus,
  deleteDcsAiKey,
  refreshDcsAiKeyBalance,
  refreshAllDcsAiKeyBalances,
  getAiModelHealthLatest,
  type AiModelHealthRow
} from '@/api/dcs/ai';
import type { DcsAiProvider } from '@/api/dcs/ai';
import {
  DcsAiBalanceVO,
  DcsAiBalanceMap,
  DcsAiConfigForm,
  DcsAiConfigQuery,
  DcsAiConfigVO,
  DcsAiModelPriceForm,
  DcsAiModelPriceVO,
  DcsAiModelVO,
  DcsAiProbeForm,
  DcsAiProviderAccessForm,
  DcsAiProviderAccessVO,
  DcsAiProviderPresetVO,
  DcsAiTestResultVO,
  DcsAiApiFormatVO,
  DcsAiProviderTemplateVO
} from '@/api/dcs/ai/types';
import { listTenant } from '@/api/system/tenant';
import { TenantVO } from '@/api/system/tenant/types';
import { useUserStore } from '@/store/modules/user';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const loading = ref(true);
const buttonLoading = ref(false);
const showSearch = ref(true);
const total = ref(0);
const single = ref(true);
const multiple = ref(true);
const ids = ref<Array<string | number>>([]);
const configList = ref<DcsAiConfigVO[]>([]);
const providerOptions = ref<DcsAiProviderPresetVO[]>([]);
const providerDictOptions = ref<any[]>([]);
/** Q5(2026-10-23)：厂商模板（20 家）+ 接口格式字典（3 种），模板选中即带出地址/格式/模型 */
const providerTemplates = ref<DcsAiProviderTemplateVO[]>([]);
const apiFormats = ref<DcsAiApiFormatVO[]>([]);
const selectedTemplateId = ref('');
const priceDetecting = ref(false);
const modelOptions = ref<DcsAiModelVO[]>([]);
/** 业务批4 Q16-A：免费/付费两组派生视图（free=null 的厂商如实留在付费组，不标徽章） */
const freeModelOptions = computed(() => modelOptions.value.filter((o) => o.free === true));
const paidModelOptions = computed(() => modelOptions.value.filter((o) => o.free !== true));
const priceList = ref<DcsAiModelPriceVO[]>([]);
const tenantOptions = ref<TenantVO[]>([]);
const tenantLoading = ref(false);
const priceLoading = ref(false);
const priceTotal = ref(0);
const balanceInfo = ref<DcsAiBalanceVO>();
const speedInfo = ref<DcsAiTestResultVO>();
const balanceResults = ref<DcsAiBalanceMap>({});
let balanceTimer: ReturnType<typeof setInterval> | undefined;
// AI 用量统计
const usageSummary = ref<any>(null);
const headerMetrics = computed(() => [
  { label: '配置总数', value: total.value, hint: '当前筛选范围内的模型配置', tone: 'primary' as const },
  { label: '已启用', value: configList.value.filter((item) => item.enabled === '1').length, hint: '当前页可参与业务调用', tone: 'success' as const },
  {
    label: '模型厂商',
    value: new Set(configList.value.map((item) => item.provider).filter(Boolean)).size,
    hint: '当前页覆盖的供应商',
    tone: 'neutral' as const
  },
  { label: '累计调用', value: usageSummary.value?.totalCalls || 0, hint: '来自 AI 用量汇总', tone: 'warning' as const }
]);
const goUsageDetail = () => proxy.$router.push('/tenant/ai-usage');
// 加载 AI 用量汇总(累计调用次数等), 用于头部指标卡; 失败静默兜底, 不影响列表加载
const loadUsageSummary = async () => {
  try {
    const res: any = await getDcsAiUsageSummary();
    usageSummary.value = res.data || null;
  } catch {
    usageSummary.value = null;
  }
};
const probeLoading = reactive({
  models: false,
  balance: false,
  speed: false
});

const queryFormRef = ref<ElFormInstance>();
const configFormRef = ref<ElFormInstance>();

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});
const priceDialog = reactive<DialogOption>({
  visible: false,
  title: ''
});
const priceSubmitting = ref(false);

// ===== 批B 多模型改造：厂商接入（钥匙单一真源，一厂一行一处改全厂生效）=====
/** 模型弹窗当前 (租户,厂商) 的接入状态；null=未接入（表单切「首次配置需填 Key」形态） */
const accessInfo = ref<DcsAiProviderAccessVO | null>(null);
const accessFormRef = ref<ElFormInstance>();
const accessDialog = reactive<DialogOption>({ visible: false, title: '' }); // 旧弹窗残座（模板已撤）
/** 业务反馈批1015b Q5：钥匙编辑抽屉（替代旧 820px 弹窗的表单区） */
const keyDrawer = reactive<DialogOption>({ visible: false, title: '' });
/** 钥匙管理页签：当前查看的 厂商×租户（Q6：租户可切，角标与列表口径不再打架） */
const keysProvider = ref<DcsAiProvider | ''>('');
const keysTenantId = ref('');
/** 钥匙管理口径对齐（2026-10-16）：厂商组跨租户时默认「全部租户」聚合视图——列表行数=入口角标把数，
 *  根治"角标 2 把、钥匙管理只有 1 把"（第二把在其他租户名下，原来藏在租户下拉里） */
const KEYS_ALL_TENANTS = '__ALL__';
const myTenantId = computed(() => useUserStore().tenantId || '');
const tenantNameOf = (tid: string) => {
  const hit = tenantOptions.value.find((t) => t.tenantId === tid);
  return hit ? hit.companyName || hit.contactUserName || tid : tid;
};
const keysEmptyText = computed(() =>
  keysTenantId.value === KEYS_ALL_TENANTS
    ? '该厂商各租户都还没有钥匙，点右上「新增钥匙」配置第一把'
    : '该租户在此厂商还没有钥匙，点右上「新增钥匙」配置第一把'
);
const keysLoading = ref(false);
const accessSubmitting = ref(false);
/** 弹窗自己的掩码快照（k3 P1：不再借用模型表单的 accessInfo——从列表打开别租户/别厂时掩码会错位） */
const accessMaskInDialog = ref('');
const accessHasKey = computed(() => !!accessMaskInDialog.value);
const initAccessFormData: DcsAiProviderAccessForm = {
  tenantId: '',
  provider: '',
  baseUrl: '',
  apiKey: '',
  apiFormat: 'CHAT_COMPLETIONS',
  consoleUrl: '',
  remark: ''
};
const accessForm = ref<DcsAiProviderAccessForm>({ ...initAccessFormData });
/** 首建必须填 Key；已有接入留空沿用（占位符/校验与后端 upsert 口径一致）；
 *  批1：标准厂商地址自动接入，Base URL 仅「自定义」厂商必填 */
const accessRules = computed(() => ({
  baseUrl: accessForm.value.provider === 'CUSTOM'
    ? [{ required: true, message: '自定义厂商必须填写接口地址', trigger: 'blur' }]
    : [],
  apiKey: accessHasKey.value ? [] : [{ required: true, message: '首次配置接入必须填写 API Key', trigger: 'blur' }]
}));
/** 批1：厂商官方默认地址（providerOptions 即 presets 接口下发） */
const presetBaseUrl = (provider: string) => providerOptions.value.find((item) => item.provider === provider)?.baseUrl || '';
/** 接入状态请求定序令牌：租户/厂商快速切换时丢弃过期响应，防晚到的旧响应覆盖新形态 */
let accessReqSeq = 0;
/** 接入状态加载中（表单保存按钮防抖：未拉到状态就保存会走错分支） */
const accessLoading = ref(false);
/** 租户/厂商变化时拉接入状态：已接入→表单钥匙区改「由厂商接入提供」并回填 baseUrl */
const loadAccessState = async () => {
  const tenantId = String(form.value.tenantId || '');
  const provider = String(form.value.provider || '');
  if (!tenantId || !provider) {
    accessReqSeq++;
    accessInfo.value = null;
    accessLoading.value = false;
    return;
  }
  const seq = ++accessReqSeq;
  accessLoading.value = true;
  try {
    const res: any = await getDcsAiProviderAccess({ tenantId, provider });
    if (seq !== accessReqSeq) {
      return;
    }
    const vo = (res?.data ?? null) as DcsAiProviderAccessVO | null;
    accessInfo.value = vo;
    if (vo) {
      // 已接入→钥匙归接入层管：表单里任何残留明文都不得提交——防"先填 key 再选租户"
      // 把残留值覆盖成接入层好钥匙（upsert 真钥匙即整厂换钥）。掩码态下表单值无意义
      form.value.apiKey = '';
      if (vo.baseUrl) {
        // 表单 baseUrl 展示接入层值（提交时后端忽略行内列），探测也按它走
        form.value.baseUrl = vo.baseUrl;
      }
    }
  } catch {
    if (seq !== accessReqSeq) {
      return;
    }
    // 拉不到按未接入展示（保存时后端守卫兜底），不阻断表单
    accessInfo.value = null;
  } finally {
    if (seq === accessReqSeq) {
      accessLoading.value = false;
    }
  }
};
// ===== AI 缺口批 Q8（2026-10-14）：钥匙池状态 =====
/** 钥匙列表（掩码+启停+主钥匙标记+余额快照） */
const keyList = ref<DcsAiProviderAccessVO[]>([]);
const balanceRefreshingId = ref<number | string | null>(null);
/** Q8 补遗：表单处于「添加新钥匙」形态（无 id 也可能是编辑主钥匙的批B 兼容态，靠它区分） */
const accessAddMode = ref(false);
const balanceRefreshingAll = ref(false);

/** Q6：该厂商有配置的租户清单（组内模型行的租户去重，名字从租户表带出） */
const keysTenantOptions = computed<TenantVO[]>(() => {
  const g = vendorGroups.value.find((x) => x.provider === keysProvider.value);
  if (!g) {
    return [];
  }
  const ids = [...new Set(g.models.map((m) => String(m.tenantId ?? '')).filter(Boolean))];
  return ids.map((id) => {
    const hit = tenantOptions.value.find((t) => t.tenantId === id);
    return hit ?? ({ tenantId: id, companyName: '', contactUserName: '' } as TenantVO);
  });
});
/** Q5：池摘要行——几把/几启用/主钥匙是谁/探测健康度 */
const keysSummary = computed(() => {
  if (!keyList.value.length) {
    return '还没有钥匙';
  }
  const enabled = keyList.value.filter((k) => k.enabled !== '0');
  const primary = keyList.value.find((k) => k.primary);
  const failed = enabled.filter((k) => k.balanceStatus === 'FAILED').length;
  const health = failed ? `探测异常 ${failed}` : '探测正常';
  const scope = keysTenantId.value === KEYS_ALL_TENANTS ? `（跨 ${new Set(keyList.value.map((k) => String(k.tenantId))).size} 个租户）` : '';
  return `共 ${keyList.value.length} 把${scope} · 启用 ${enabled.length} · 主钥匙：${primary?.label || primary?.apiKeyMask || '—'} · ${health}`;
});

/** 钥匙列表总览条（2026-10-15 视觉批）：不开弹窗就能看出几把、几把在用 */
const keyEnabledCount = computed(() => keyList.value.filter((k) => k.enabled !== '0').length);
/** 探测时间 → 「MM-DD HH:mm 探测过」，空=未探测 */
const fmtCheckedAt = (row: DcsAiProviderAccessVO) => {
  const s = row.balanceCheckedAt ? String(row.balanceCheckedAt).replace('T', ' ') : '';
  return s ? `${s.slice(5, 16)} 探测过` : '还没探测过';
};
/** 余额注解：耗尽/失败旧值/正常三种口径（UNSUPPORTED 与未探测在模板分支处理） */
const balanceNote = (row: DcsAiProviderAccessVO) => {
  if (row.balanceStatus === 'FAILED') return '查询失败，显示旧值';
  if (Number(row.balanceAmount) <= 0) return '已耗尽，退出轮询';
  return '厂商账户余额';
};

/** 载入钥匙列表并让表单对齐主钥匙（无行=首配形态） */
const loadKeyList = async (tenantId: string, provider: string) => {
  keysLoading.value = true;
  try {
    const res: any = await listDcsAiProviderAccessKeys({ tenantId, provider });
    keyList.value = (res?.data ?? []) as DcsAiProviderAccessVO[];
  } catch {
    keyList.value = [];
  } finally {
    keysLoading.value = false;
  }
};

/** 表单对齐某把钥匙（编辑形态） */
const onEditKey = (row: DcsAiProviderAccessVO) => {
  accessAddMode.value = false;
  accessForm.value = {
    id: row.id,
    tenantId: row.tenantId,
    provider: row.provider,
    baseUrl: row.baseUrl || '',
    apiKey: '',
    apiFormat: row.apiFormat || 'CHAT_COMPLETIONS',
    consoleUrl: row.consoleUrl || '',
    remark: row.remark || '',
    label: row.label || ''
  };
  accessMaskInDialog.value = row.apiKeyMask || '';
  keyDrawer.title = `编辑钥匙 · ${row.label || row.apiKeyMask || '未命名'}`;
  keyDrawer.visible = true;
};

/** 表单切回新增形态（保留租户/厂商；同厂沿用 Base URL——多把钥匙通常同端点）。
 *  Q8 补遗：置 addMode——无 id 走 POST /access/add
 *  真新增（PUT /access 的无 id=编辑主钥匙是批B 兼容语义，「添加新钥匙」原来永远编辑首行） */
const onAddKeyForm = (tenantId?: string, provider?: string) => {
  accessAddMode.value = true;
  const nextProvider = (provider ?? keysProvider.value ?? accessForm.value.provider ?? '') as DcsAiProvider;
  // 全部租户模式下没有具体租户上下文，新增默认落本租户（要给其他租户加钥匙先在租户下拉切过去）
  const ctxTenant = keysTenantId.value === KEYS_ALL_TENANTS ? myTenantId.value || keysTenantOptions.value[0]?.tenantId || '' : keysTenantId.value;
  const nextTenant = String(tenantId ?? ctxTenant ?? accessForm.value.tenantId ?? '');
  const keepBaseUrl = accessForm.value.provider === nextProvider ? accessForm.value.baseUrl : '';
  accessForm.value = {
    ...initAccessFormData,
    tenantId: nextTenant,
    provider: nextProvider,
    baseUrl: keepBaseUrl || (nextProvider !== 'CUSTOM' ? presetBaseUrl(nextProvider) : ''),
    apiFormat: accessForm.value.provider === nextProvider ? accessForm.value.apiFormat || 'CHAT_COMPLETIONS' : 'CHAT_COMPLETIONS'
  };
  accessMaskInDialog.value = '';
  keyDrawer.title = `新增钥匙 · ${nextProvider ? getProviderName(nextProvider) : ''}（${nextTenant || '未选租户'}）`;
  keyDrawer.visible = true;
  void nextTick(() => accessFormRef.value?.clearValidate());
};

/** 打开「管理钥匙」弹窗：row 优先（厂商中心/列表组头），否则用模型表单当前租户/厂商 */
const openAccessDialog = async (row?: DcsAiConfigVO) => {
  const tenantId = String(row?.tenantId ?? form.value.tenantId ?? '');
  const provider = String(row?.provider ?? form.value.provider ?? '');
  if (!tenantId || !provider || provider === 'undefined') {
    proxy?.$modal.msgWarning('请先选择租户和厂商');
    return;
  }
  // 业务反馈批1015b Q5：入口改为跳「钥匙管理」页签并自动定位该 厂商×租户（不再开弹窗）
  keysProvider.value = provider as DcsAiProvider;
  keysTenantId.value = tenantId;
  activeTab.value = 'keys';
  await reloadKeysView();
};

/** 页签：厂商切换 → 租户下拉换成该厂有配置的租户；多租户默认「全部租户」（与角标同口径），单租户落"我的租户" */
const onKeysProviderChange = async () => {
  const tenants = keysTenantOptions.value.map((t) => String(t.tenantId));
  keysTenantId.value = tenants.length > 1 ? KEYS_ALL_TENANTS : tenants.includes(String(myTenantId.value)) ? String(myTenantId.value) : (tenants[0] ?? '');
  await onKeysFilterChange();
};
/** 页签：厂商/租户任一变化 → 重拉钥匙列表（按当前筛选模式） */
const onKeysFilterChange = async () => {
  await reloadKeysView();
};
/** 按当前筛选重载钥匙列表：「全部租户」= 扁平该厂商全部租户的钥匙（本租户行排前）；单租户走原单对加载 */
const reloadKeysView = async () => {
  if (!keysProvider.value) {
    keyList.value = [];
    return;
  }
  if (keysTenantId.value === KEYS_ALL_TENANTS) {
    const tenants = keysTenantOptions.value.map((t) => String(t.tenantId)).filter(Boolean);
    keysLoading.value = true;
    try {
      const lists = await Promise.all(
        tenants.map(async (tid) => {
          try {
            const res: any = await listDcsAiProviderAccessKeys({ tenantId: tid, provider: String(keysProvider.value) });
            return (res?.data ?? []) as DcsAiProviderAccessVO[];
          } catch {
            return [] as DcsAiProviderAccessVO[];
          }
        })
      );
      const mine = myTenantId.value;
      keyList.value = lists.flat().sort((a, b) => (String(a.tenantId) === mine ? -1 : String(b.tenantId) === mine ? 1 : 0));
    } finally {
      keysLoading.value = false;
    }
    return;
  }
  if (keysTenantId.value) {
    await loadKeyList(keysTenantId.value, String(keysProvider.value));
  } else {
    keyList.value = [];
  }
};

/** 钥匙启停（不能停最后一把启用中的——后端 409 人话兜底） */
const onToggleKey = async (row: DcsAiProviderAccessVO) => {
  const next = row.enabled === '0' ? '1' : '0';
  try {
    await changeDcsAiKeyStatus(row.id!, next as '0' | '1');
    proxy?.$modal.msgSuccess(next === '1' ? '已启用，重新参与轮询' : '已停用（保留余额快照，可随时再启用）');
    await reloadKeysView();
    void refreshKeyBadge(String(row.tenantId), String(row.provider));
  } catch {
    /* 409/错误已统一弹错；列表不翻动 */
  }
};

/** 删除钥匙（软删，不能删最后一把启用中的） */
const onDeleteKey = async (row: DcsAiProviderAccessVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除钥匙「${row.label || row.apiKeyMask}」？该厂模型将不再使用它调用。`);
  } catch {
    return;
  }
  try {
    await deleteDcsAiKey(row.id!);
    proxy?.$modal.msgSuccess('钥匙已删除');
    await reloadKeysView();
    await getList();
  } catch {
    /* 409/错误已统一弹错 */
  }
};

/** 单钥匙余额刷新（实查厂商端点落快照）。失败也要重载：后端已把 FAILED 快照落库，不刷会一直显示旧文案 */
const onRefreshKeyBalance = async (row: DcsAiProviderAccessVO) => {
  balanceRefreshingId.value = row.id!;
  try {
    await refreshDcsAiKeyBalance(row.id!);
  } catch {
    /* 失败已统一弹错（快照保留旧值） */
  } finally {
    await reloadKeysView();
    void refreshKeyBadge(String(row.tenantId), String(row.provider));
    balanceRefreshingId.value = null;
  }
};

/** 整厂余额批量刷新 */
const onRefreshAllBalances = async () => {
  // 业务反馈批1015b Q5：批量刷新作用于页签当前查看的 厂商×租户；全部租户模式=逐租户并刷（2026-10-16 口径对齐）
  const provider = String(keysProvider.value || accessForm.value.provider || '');
  if (!provider) return;
  const targets =
    keysTenantId.value === KEYS_ALL_TENANTS
      ? [...new Set(keyList.value.map((k) => String(k.tenantId)).filter(Boolean))]
      : [String(keysTenantId.value || accessForm.value.tenantId || '')];
  if (!targets[0]) return;
  balanceRefreshingAll.value = true;
  try {
    await Promise.all(targets.map((t) => refreshAllDcsAiKeyBalances({ tenantId: t, provider }).catch(() => undefined)));
  } catch {
    /* 失败已统一弹错（部分钥匙的快照仍已落库） */
  } finally {
    await reloadKeysView();
    targets.forEach((t) => void refreshKeyBadge(t, provider));
    balanceRefreshingAll.value = false;
  }
};

const submitAccess = async () => {
  // 批1：标准厂商地址自动接入（后端同口径兜底，这里先补齐让探测/校验链路都拿到值）
  // N1 回修（dspro P1-4）：只补空值——模板选过国际站（api.z.ai / api.moonshot.cn /
  // dashscope-intl / api.minimax.io / api.siliconflow.com）时不能再被国内预置地址覆盖
  if (accessForm.value.provider !== 'CUSTOM' && !accessForm.value.baseUrl) {
    const preset = presetBaseUrl(accessForm.value.provider);
    if (preset) accessForm.value.baseUrl = preset;
  }
  await accessFormRef.value?.validate();
  accessSubmitting.value = true;
  try {
    // Q8 补遗：新增形态走 /access/add 真插入（首配建首行等价）；编辑形态沿用 PUT
    const res: any = accessAddMode.value ? await addDcsAiProviderAccessKey(accessForm.value) : await updateDcsAiProviderAccess(accessForm.value);
    const n = res?.data?.affectedModelCount;
    proxy?.$modal.msgSuccess(n != null ? `已保存，新钥匙立即生效于该厂商的 ${n} 个模型` : '钥匙已保存');
    // Q8：弹窗保持打开刷新钥匙列表；模型弹窗开着同厂 → 刷新接入状态；列表刷新✓标记
    const added = accessAddMode.value ? (res?.data as DcsAiProviderAccessVO | undefined) : undefined;
    await reloadKeysView();
    // Q5：保存成功收抽屉回列表（新增行落进表格自然可见；连续再加点工具栏「新增钥匙」）
    keyDrawer.visible = false;
    if (dialog.visible) {
      void loadAccessState();
    }
    await getList();
  } catch {
    /* request.ts 已统一弹错 */
  } finally {
    accessSubmitting.value = false;
  }
};

const currencyOptions = [
  { label: 'CNY - 人民币', value: 'CNY' },
  { label: 'USD - 美元', value: 'USD' },
  { label: 'HKD - 港币', value: 'HKD' },
  { label: 'TWD - 新台币', value: 'TWD' },
  { label: 'EUR - 欧元', value: 'EUR' },
  { label: 'JPY - 日元', value: 'JPY' },
  { label: 'KRW - 韩元', value: 'KRW' },
  { label: 'GBP - 英镑', value: 'GBP' },
  { label: 'SGD - 新加坡元', value: 'SGD' },
  { label: 'AUD - 澳元', value: 'AUD' },
  { label: 'CAD - 加元', value: 'CAD' }
];

const regionCurrencyMap: Record<string, string> = {
  CN: 'CNY',
  HK: 'HKD',
  MO: 'MOP',
  TW: 'TWD',
  US: 'USD',
  GB: 'GBP',
  EU: 'EUR',
  FR: 'EUR',
  DE: 'EUR',
  IT: 'EUR',
  ES: 'EUR',
  NL: 'EUR',
  IE: 'EUR',
  JP: 'JPY',
  KR: 'KRW',
  SG: 'SGD',
  AU: 'AUD',
  CA: 'CAD',
  NZ: 'NZD',
  MY: 'MYR',
  TH: 'THB',
  IN: 'INR',
  ID: 'IDR',
  PH: 'PHP',
  VN: 'VND'
};

const languageCurrencyMap: Record<string, string> = {
  zh: 'CNY',
  en: 'USD',
  ja: 'JPY',
  ko: 'KRW'
};

const normalizeCurrency = (value?: string) =>
  String(value || '')
    .trim()
    .toUpperCase();

const getBrowserCurrency = () => {
  if (typeof navigator === 'undefined') return 'CNY';
  const locales = Array.from(new Set([navigator.language, ...(navigator.languages || [])].filter(Boolean)));
  for (const locale of locales) {
    const parts = locale.replace('_', '-').split('-').filter(Boolean);
    const language = parts[0]?.toLowerCase();
    const region = parts
      .slice(1)
      .reverse()
      .find((part) => /^[A-Za-z]{2}$/.test(part))
      ?.toUpperCase();
    if (region && regionCurrencyMap[region]) return regionCurrencyMap[region];
    if (language && languageCurrencyMap[language]) return languageCurrencyMap[language];
  }
  return 'CNY';
};

const normalizeCurrencyInput = (target: DcsAiModelPriceForm) => {
  target.currency = normalizeCurrency(target.currency) || getBrowserCurrency();
};

const initPriceFormData: DcsAiModelPriceForm = {
  tenantId: '000000',
  provider: undefined,
  modelName: '',
  currency: getBrowserCurrency(),
  inputPricePerMillion: 0,
  cachedInputPricePerMillion: 0,
  outputPricePerMillion: 0,
  enabled: '1',
  sourceUrl: '',
  manualOverride: '1',
  remark: ''
};

const initFormData: DcsAiConfigForm = {
  configId: undefined,
  tenantId: '',
  provider: undefined,
  useType: 'CHAT',
  baseUrl: '',
  apiKey: '',
  apiFormat: 'CHAT_COMPLETIONS',
  modelName: '',
  contextWindow: 128000,
  maxTokens: undefined,
  temperature: 0.2,
  timeoutSeconds: 60,
  enabled: '1',
  unitPricePerCall: 0.01,
  balanceQueryEnabled: '1',
  billingMode: 'OFFICIAL_TOKEN',
  serviceFeeRate: 0,
  creditMultiplier: undefined as number | undefined,
  reasoningEffort: '',
  remark: ''
};

const data = reactive<PageData<DcsAiConfigForm, DcsAiConfigQuery>>({
  form: { ...initFormData },
  queryParams: {
    pageNum: 1,
    // R1 厂商中心：全量拉取按厂商分组渲染（真实数据量=每租户几家厂商×几条模型，远小于 500）
    pageSize: 500,
    tenantId: undefined,
    provider: undefined,
    enabled: undefined
  },
  rules: {
    tenantId: [{ required: true, message: '租户编号不能为空', trigger: 'change' }],
    provider: [{ required: true, message: '模型厂商不能为空', trigger: 'change' }],
    baseUrl: [{ required: true, message: 'Base URL不能为空', trigger: 'blur' }],
    apiKey: [
      {
        validator: (_rule: any, value: string, callback: (error?: Error) => void) => {
          // 批B: 已有厂商接入 → 钥匙由接入层管, 表单免填; 未接入时首次配置必填
          if (accessInfo.value) {
            callback();
            return;
          }
          if (!form.value.configId && !value) {
            callback(new Error('API Key不能为空（该厂商首次配置）'));
            return;
          }
          callback();
        },
        trigger: 'blur'
      }
    ],
    modelName: [{ required: true, message: '模型名称不能为空', trigger: 'change' }],
    contextWindow: [{ required: true, message: '上下文窗口不能为空', trigger: 'blur' }],
    enabled: [{ required: true, message: '状态不能为空', trigger: 'change' }]
  }
});

const { queryParams, form, rules } = toRefs(data);
/** E2E-1（TDZ P0）：watch 必须在 toRefs 解构出 form 之后注册——侦听源 getter 在 setup 同步求值，
 *  放在解构前会「Cannot access 'form' before initialization」页面首开即白屏（批B-3 埋雷，E2E 首开抓获） */
watch(
  [() => form.value.tenantId, () => form.value.provider],
  () => {
    void loadAccessState();
  },
  { flush: 'post' }
);
const priceForm = ref<DcsAiModelPriceForm>({ ...initPriceFormData });
const inlinePriceForm = ref<DcsAiModelPriceForm>({ ...initPriceFormData });
const inlinePriceSaving = ref(false);
const priceQuery = reactive({
  pageNum: 1,
  pageSize: 100
});
const priceFormRef = ref<ElFormInstance>();

const getProviderName = (provider: string) => providerOptions.value.find((item) => item.provider === provider)?.providerName || provider;
/** Q5：厂商模板候选——已选厂商时只列该厂模板（国内站/国际站等变体），未选时列全部 */
const filteredTemplates = computed(() => {
  const p = String(form.value.provider || '');
  if (!p) return providerTemplates.value;
  const scoped = providerTemplates.value.filter((t) => t.provider === p);
  return scoped.length ? scoped : providerTemplates.value;
});
const activeTemplate = computed(() => providerTemplates.value.find((t) => t.templateId === selectedTemplateId.value));
const apiFormatPath = (value?: string) =>
  apiFormats.value.find((f) => f.value === (value || 'CHAT_COMPLETIONS'))?.path || '/chat/completions';
const apiFormatShort = (value?: string) =>
  (({ CHAT_COMPLETIONS: 'Chat', ANTHROPIC_MESSAGES: 'Anthropic', RESPONSES: 'Responses' }) as Record<string, string>)[
    value || 'CHAT_COMPLETIONS'
  ] || value || 'Chat';
const isNonChatFormat = computed(() => (form.value.apiFormat || 'CHAT_COMPLETIONS') !== 'CHAT_COMPLETIONS');
const apiFormatHint = computed(() => {
  const format = apiFormats.value.find((f) => f.value === (form.value.apiFormat || 'CHAT_COMPLETIONS'));
  if (!format) return '默认 Chat Completions（OpenAI 兼容），可进对话主链';
  if (format.value === 'CHAT_COMPLETIONS') return `${format.label} · OpenAI 兼容，可进对话主链`;
  return `${format.label} · 仅支持保存后的连通测试，对话主链需 Chat Completions`;
});
/** 实际请求地址预览：与后端 DcsAiApiFormat.requestUrl 同口径（Anthropic 的 baseUrl 带 /v1 时不重复拼） */
const apiPathPreview = computed(() => {
  const base = String(form.value.baseUrl || '').replace(/\/+$/, '');
  if (!base) return '';
  const format = form.value.apiFormat || 'CHAT_COMPLETIONS';
  if (format === 'ANTHROPIC_MESSAGES' && base.endsWith('/v1')) return `${base}/messages`;
  return `${base}${apiFormatPath(format)}`;
});
const keyPathPreview = computed(() => {
  const base = String(accessForm.value.baseUrl || '').replace(/\/+$/, '');
  if (!base) return '';
  const format = accessForm.value.apiFormat || 'CHAT_COMPLETIONS';
  if (format === 'ANTHROPIC_MESSAGES' && base.endsWith('/v1')) return `${base}/messages`;
  return `${base}${apiFormatPath(format)}`;
});
const tenantLabel = (item: TenantVO) => `${item.tenantId} / ${item.companyName || item.contactUserName || '-'}`;
const modelLabel = (item: DcsAiModelVO) => (item.ownedBy ? `${item.modelId} (${item.ownedBy})` : item.modelId);
const formatContext = (value?: number) => (value ? `${Math.round(value / 1000)}K` : '-');
const billingModeLabel = (mode?: string) => {
  const map: Record<string, string> = {
    OFFICIAL_TOKEN: '官方Token',
    FIXED_PER_CALL: '固定单价',
    // m3 P1-3 回修：与下拉选项/用量页统一「官方价＋每次服务费」（全角＋），三处不再漂移
    OFFICIAL_PLUS_FEE: '官方价＋每次服务费'
  };
  return map[mode || ''] || '官方Token';
};
/** 业务批4 Q4-A：调用单价字段随计费模式换语义——OFFICIAL_PLUS_FEE 下=每次服务费 */
const unitPriceHint = computed(() => {
  if (form.value.billingMode === 'OFFICIAL_PLUS_FEE') {
    return '每次调用加收的固定服务费（元）；官方价另按 token 单价计算，实扣=两者相加';
  }
  return '固定单价模式或官方 token 价格缺失时回退使用';
});
const normalizeText = (value?: string | number) =>
  String(value ?? '')
    .trim()
    .toLowerCase();
const currentTenantId = () => String(form.value.tenantId || '000000');
const createInlinePriceDraft = (): DcsAiModelPriceForm => ({
  ...initPriceFormData,
  tenantId: currentTenantId(),
  provider: form.value.provider,
  modelName: form.value.modelName || '*',
  enabled: '1'
});
// 价格匹配评分: 厂商必须命中, 模型精确命中(20)>通配*(10), 租户精确(2)>默认000000(1); 取最高分作为生效价格
const matchPriceScore = (row: DcsAiModelPriceVO) => {
  if (row.enabled === '0') return -1;
  if (normalizeText(row.provider) !== normalizeText(form.value.provider)) return -1;
  const model = normalizeText(form.value.modelName);
  const rowModel = normalizeText(row.modelName);
  const modelScore = rowModel === model ? 20 : rowModel === '*' ? 10 : -1;
  if (modelScore < 0) return -1;
  const tenant = normalizeText(currentTenantId());
  const rowTenant = normalizeText(row.tenantId || '000000');
  const tenantScore = rowTenant === tenant ? 2 : rowTenant === '000000' ? 1 : 0;
  return modelScore + tenantScore;
};
// 在已有 priceList 中按评分挑出当前表单对应的生效价格行(无则返回 undefined)
const findInlinePrice = () =>
  [...priceList.value]
    .map((row) => ({ row, score: matchPriceScore(row) }))
    .filter((item) => item.score >= 0)
    .sort((a, b) => b.score - a.score)[0]?.row;
const inlinePriceHint = computed(() => {
  if (!form.value.provider || !form.value.modelName) return '先选择厂商和模型后再匹配官方价格参数';
  if (inlinePriceForm.value.priceId) {
    return `当前匹配 ${inlinePriceForm.value.tenantId || '000000'} / ${inlinePriceForm.value.modelName}`;
  }
  return '当前模型暂无价格参数，保存后将新增一条可复用的官方计费配置';
});
// 同步内联价格表单: OFFICIAL_TOKEN/OFFICIAL_PLUS_FEE 计费模式才生效(批4回修 P1-1: 第三模式
// 也以官方价打底, 面板 v-if 已放行但此处早退会让编辑态恒空表单+误标"待新增价格"). 优先调
// effective API 回填持久化价格; 失败或无值时回退到本地 priceList 按 matchPriceScore 匹配,
// 仍无则建草稿(保存时新增一条)
const syncInlinePriceForm = async () => {
  if (form.value.billingMode !== 'OFFICIAL_TOKEN' && form.value.billingMode !== 'OFFICIAL_PLUS_FEE') return;
  // 优先调 effective API 加载已持久化价格(切换模型时自动回填)
  if (form.value.provider && form.value.modelName) {
    try {
      const res = await getDcsAiEffectivePrice({
        tenantId: String(form.value.tenantId || currentTenantId()),
        provider: form.value.provider,
        modelName: form.value.modelName
      });
      if (res.data) {
        inlinePriceForm.value = {
          priceId: res.data.priceId,
          tenantId: res.data.tenantId,
          provider: res.data.provider,
          modelName: res.data.modelName,
          currency: res.data.currency,
          inputPricePerMillion: res.data.inputPricePerMillion ?? 0,
          cachedInputPricePerMillion: res.data.cachedInputPricePerMillion ?? 0,
          outputPricePerMillion: res.data.outputPricePerMillion ?? 0,
          sourceUrl: res.data.sourceUrl || '',
          enabled: res.data.enabled,
          remark: res.data.remark || ''
        };
        normalizeCurrencyInput(inlinePriceForm.value);
        return;
      }
    } catch (e) {
      // API 失败时回退到本地 priceList 匹配
    }
  }
  if (!priceList.value.length) {
    await loadPriceList();
  }
  const matched = findInlinePrice();
  inlinePriceForm.value = matched ? { ...matched } : createInlinePriceDraft();
  inlinePriceForm.value.provider = form.value.provider;
  if (!inlinePriceForm.value.modelName) {
    inlinePriceForm.value.modelName = form.value.modelName || '*';
  }
  normalizeCurrencyInput(inlinePriceForm.value);
};
// 保存内联价格: 有 priceId 走 update, 否则 add; 保存后重载 priceList 并重新同步表单
const saveInlinePrice = async () => {
  if (!form.value.provider || !inlinePriceForm.value.modelName) {
    proxy?.$modal.msgWarning('请先选择厂商和模型名称');
    return;
  }
  inlinePriceSaving.value = true;
  try {
    inlinePriceForm.value.provider = form.value.provider;
    inlinePriceForm.value.tenantId = inlinePriceForm.value.tenantId || currentTenantId();
    inlinePriceForm.value.enabled = inlinePriceForm.value.enabled || '1';
    normalizeCurrencyInput(inlinePriceForm.value);
    if (inlinePriceForm.value.priceId) {
      await updateDcsAiModelPrice(inlinePriceForm.value);
    } else {
      await addDcsAiModelPrice(inlinePriceForm.value);
    }
    proxy?.$modal.msgSuccess('官方计费参数已保存');
    await loadPriceList();
    await syncInlinePriceForm();
  } finally {
    inlinePriceSaving.value = false;
  }
};
// 价格自动识别: 主流模型自动查聚合库(无需URL), 国产小厂降级抓取文档URL
const detectPrice = async () => {
  priceDetecting.value = true;
  try {
    const res = await detectDcsAiPrice({
      provider: form.value.provider || '',
      modelName: form.value.modelName || '',
      sourceUrl: inlinePriceForm.value.sourceUrl
    });
    const result = res.data;
    if (result && result.success) {
      if (result.inputPricePerMillion != null) inlinePriceForm.value.inputPricePerMillion = result.inputPricePerMillion;
      if (result.cachedInputPricePerMillion != null) inlinePriceForm.value.cachedInputPricePerMillion = result.cachedInputPricePerMillion;
      if (result.outputPricePerMillion != null) inlinePriceForm.value.outputPricePerMillion = result.outputPricePerMillion;
      if (result.currency) inlinePriceForm.value.currency = result.currency;
      normalizeCurrencyInput(inlinePriceForm.value);
      proxy?.$modal.msgSuccess(`已识别(method=${result.method}, confidence=${result.confidence}), 请核对后保存`);
    } else {
      proxy?.$modal.msgWarning(result?.message || '未能从文档识别价格, 请手动填写');
    }
  } catch (e: any) {
    proxy?.$modal.msgError('识别失败: ' + (e.message || e));
  } finally {
    priceDetecting.value = false;
  }
};

const firstBalance = (configId: number | string) => balanceResults.value[String(configId)]?.balances?.[0];
const balanceText = (configId: number | string) => {
  const info = balanceResults.value[String(configId)];
  if (!info) return '查询中';
  const first = firstBalance(configId);
  if (first?.totalBalance) return `${first.currency || 'CNY'} ${first.totalBalance}`;
  return info.message || '无标准接口';
};
const balanceTooltip = (configId: number | string) => {
  const info = balanceResults.value[String(configId)];
  if (!info) return '余额约每 30 秒自动刷新一次';
  const first = firstBalance(configId);
  if (!first) return info.message || '未返回余额明细';
  return `total=${first.totalBalance || '-'}, granted=${first.grantedBalance || '-'}, topped=${first.toppedUpBalance || '-'}`;
};
const balanceTagType = (configId: number | string): 'success' | 'warning' | 'info' | 'danger' => {
  const info = balanceResults.value[String(configId)];
  if (!info) return 'info';
  if (info.available === true) return 'success';
  if (info.available === false) return 'warning';
  return 'info';
};

// 使用类型: 标签文字 (业务批5 Q1-A：CHAT 由「对话/推理」改「AI 调用」——业务反馈术语
// 生硬且误以为与聊天选模型有关，实际是后台功能调用的兜底口径; EMBEDDING=向量化)
const useTypeLabel = (t?: string): string => {
  if (t === 'CHAT') return 'AI 调用';
  if (t === 'EMBEDDING') return '向量化';
  return t || '-';
};
/** 既有语义色 → 统一药丸四组（全站 UI 契约 §2.6：一屏只有一种药丸形态） */
const pillOf = (tone?: string): string =>
  tone === 'success' ? 'ui-pill--pos' : tone === 'warning' ? 'ui-pill--warn' : tone === 'danger' ? 'ui-pill--neg' : 'ui-pill--muted';
/** 钥匙健康级 → 药丸类（ok 绿 / warn 橙 / bad 红 / none 中性） */
const keyPillClass = (level: 'none' | 'ok' | 'warn' | 'bad'): string =>
  level === 'ok' ? 'ui-pill--pos' : level === 'warn' ? 'ui-pill--warn' : level === 'bad' ? 'ui-pill--neg' : 'ui-pill--muted';

// 业务批(1015) Q1 拆星 + 批1015c Q1 入口收敛：defaultStarTip/handleSetDefaultForRow/
// 「默认模型」页签（defaultGroups/handleSetDefault/助手默认只读卡/PROVIDER_SHORT 徽章族）
// 一并下线——换默认入口分家（批2 Q4 搬家）：助手对话=「AI 助手管理 → 模型选择」，
// 后台 AI（画像/诊断/评分/字段生成）=「用户画像 → 画像设置」

const providerTagType = (p: string): 'primary' | 'success' | 'warning' | 'danger' | 'info' => {
  const map: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
    OPENAI: 'success',
    DEEPSEEK: 'primary',
    QWEN: 'warning',
    ZHIPU: 'danger',
    KIMI: 'info',
    OPENCODE: 'warning',
    STEPFUN: 'success',
    SILICONFLOW: 'primary'
  };
  return map[p] || 'info';
};

// 一键预设: 选厂商后直接拉起新增弹窗并预填
const handleQuickAdd = async (provider: string) => {
  await handleAdd();
  form.value.provider = provider;
  applyPreset();
};

// 行内启用/停用切换
const handleToggleEnabled = async (row: DcsAiConfigVO) => {
  const newEnabled = row.enabled === '1' ? '0' : '1';
  const action = newEnabled === '1' ? '恢复可用' : '停用';
  const tip = newEnabled === '1' ? '' : '（停用=急刹车：立即对所有人不可调）';
  await proxy?.$modal.confirm(`确认${action}厂商「${getProviderName(row.provider)}」的配置？${tip}`);
  await updateDcsAiConfig({ ...row, enabled: newEnabled, apiKey: '****' });
  proxy?.$modal.msgSuccess(`${action}成功`);
  await getList();
};

// CC Switch: 批量健康检查（批A Q6 拍板 2026-10-11 + 审查回修 P1-3/P2-2）：点击先确认再体检。
// 业务批4 Q17-A（2026-10-13 拍板）：可选 configIds 子集（仅当前厂商卡）——不传=全量。
// 全量的 N 必须用无过滤口径现拉（configList 是筛选视图数，直接展示会承诺错数字）；
// 耗时按单条超时上限 60s 给诚实区间，不按乐观 2s/条承诺。
const healthCheckLoading = ref(false);
const healthCheckResults = ref<Record<string | number, DcsAiTestResultVO>>({});
const handleHealthCheck = async (configIds?: (number | string)[]) => {
  const scoped = !!configIds && configIds.length > 0;
  let total = scoped ? configIds!.length : 0;
  if (!scoped) {
    try {
      const cnt: any = await listDcsAiConfig({ pageNum: 1, pageSize: 1 });
      total = Number(cnt.total ?? cnt.data?.total ?? 0);
    } catch {
      total = 0; // 计数失败不阻断体检，确认文案退化为不写具体数
    }
  }
  const scopeText = scoped ? `当前筛选的 ${total} 个` : total > 0 ? `全部 ${total} 个` : '全部';
  const timeText = total > 0 ? `最快约 ${total * 2} 秒，含不可达厂商时最长可能数分钟` : '含不可达厂商时最长可能数分钟';
  try {
    await proxy?.$modal.confirm(`将对${scopeText}模型配置逐个发起真实连通检测（真实 API 调用，${timeText}），期间请勿关闭页面。是否继续？`);
  } catch {
    return; // 取消（MessageBox 取消走 catch 属预期）
  }
  healthCheckLoading.value = true;
  try {
    const res = await healthCheckDcsAiConfigs(scoped ? configIds : undefined);
    const results = res.data || [];
    // 批C C2 P2-1：按 configId 对位——旧按下标映射，表格有筛选/翻页时 configList 与
    // 全量结果数组错位，同厂商多模型会把「异常」贴到别的行
    for (const r of results) {
      if (r.configId != null) {
        healthCheckResults.value[r.configId] = r;
      }
    }
    // 兜底：老后端未回 configId 时退回下标映射（升级窗口期兼容）
    if (results.length > 0 && results.every((r) => r.configId == null)) {
      configList.value.forEach((c, i) => {
        if (results[i]) healthCheckResults.value[c.configId] = results[i];
      });
    }
    const okCount = results.filter((r) => r.success).length;
    // Q2-A：手动体检已落巡检表，重新拉一次徽标数据源——点完马上翻绿，不再等 04:00 定时周期
    await loadPatrolHealth();
    proxy?.$modal.msgSuccess(`健康检查完成: ${okCount}/${results.length} 正常`);
  } catch (e: any) {
    proxy?.$modal.msgError(e.message || '健康检查失败');
  } finally {
    healthCheckLoading.value = false;
  }
};

/** 业务批4 Q17-A：拆分按钮箭头菜单——仅体检当前厂商卡（activeGroup 为当前选中厂商分组） */
function onHealthCheckCommand(cmd: string) {
  if (cmd === 'scoped' && activeGroup.value?.models?.length) {
    handleHealthCheck(activeGroup.value.models.map((m: DcsAiConfigVO) => m.configId));
  }
}

// 活跃行高亮（表格版遗留：厂商中心由卡片 is-off 类承载停用态）

// ===== K 批二期：当前生效模型卡 =====
// k3 七审 P2：卡片数据源独立拉取（pageSize=200 全量，enabled 状态本地筛），不随表格筛选/分页走——
// 用户在表格里筛「停用」或翻页时，「当前生效模型」卡仍始终展示全局启用行；切换备选同样取全量
const cardConfigs = ref<DcsAiConfigVO[]>([]);
const loadCardData = async () => {
  try {
    const res: any = await listDcsAiConfig({ pageNum: 1, pageSize: 200 });
    cardConfigs.value = res.rows || [];
  } catch {
    cardConfigs.value = [];
  }
};

// ==================== 批5(Q1/Q2/Q4/Q7/Q8/Q11): 页签 + 能力目录自动匹配 ====================
/** 当前页签: models=模型配置(默认) / prices=价格目录(lazy 加载)；批1015c Q1 撤「默认模型」页签 */
const activeTab = ref('models');
// 菜单IA重组批3(2026-10-16): 页签写进 URL（?tab=domains 深链，旧地址 /tenant/ai-doc-domain redirect 到此）
const route = useRoute();
const router = useRouter();
const TAB_KEYS = ['models', 'prices', 'keys', 'domains'] as const;
const queryTab = String(route.query.tab || '');
if ((TAB_KEYS as readonly string[]).includes(queryTab)) {
  activeTab.value = queryTab as (typeof TAB_KEYS)[number];
}
watch(activeTab, (tab) => {
  if (route.query.tab !== tab) {
    router.replace({ query: { ...route.query, tab: tab === 'models' ? undefined : tab } });
  }
});
/** 能力目录是否已按当前模型名命中(contextWindow 只读带出提示) */
const capabilityMatched = ref(false);

/** 模型名变更: 串「能力目录自动匹配(填 contextWindow)」与原有价格参数匹配 */
const onModelNameChange = async () => {
  await syncInlinePriceForm();
  await autoMatchCapability();
};

/** Q2/Q13: contextWindow 由能力目录按模型名自动带出(模型出厂能力, 表单只读) */
const autoMatchCapability = async () => {
  capabilityMatched.value = false;
  if (!form.value.provider || !form.value.modelName) return;
  try {
    const res = await matchAiCapability({ provider: String(form.value.provider), model: form.value.modelName });
    const cap = res.data;
    if (cap?.contextWindow) {
      form.value.contextWindow = Number(cap.contextWindow);
      capabilityMatched.value = true;
    }
    // 批C C2：最大输出同样由目录带出做默认值（与上下文不同——可手动改，用户已填则不覆盖）
    if (cap?.maxOutputTokens && form.value.configId == null) {
      form.value.maxTokens = Number(cap.maxOutputTokens);
    }
  } catch {
    // 能力目录不可达不阻断表单(保留原值), 兜底口径后端 FALLBACK 同构
  }
};

// 业务批(1015) Q1 拆星 + 批1015c Q1 入口收敛：行内★换默认与「默认模型」页签的
// defaultGroups/handleSetDefault/助手默认只读卡一并下线——换默认入口分家（批2 Q4 搬家）：
// 助手对话=「AI 助手管理 → 模型选择」，后台 AI=「用户画像 → 画像设置」

const modalFeedback = async (msg: string) => {
  proxy?.$modal.msgSuccess(msg);
};

const priceText = (vo: DcsAiConfigVO) => {
  // 符号跟 vo.currency 走（785be8ce E2E-3 同源：USD 行显示 $ 不误导）
  const sym = vo.currency === 'USD' ? '$' : '¥';
  // 业务批4 Q4-A：官方价＋服务费=双段展示（官方按量价＋每次服务费）
  if (vo.billingMode === 'OFFICIAL_PLUS_FEE') {
    const fee = vo.unitPricePerCall != null ? `${sym}${Number(vo.unitPricePerCall).toFixed(4)}/次服务费` : '服务费未设';
    const base = vo.inputPricePerMillion != null ? `${sym}${Number(vo.inputPricePerMillion).toFixed(2)}/百万` : '';
    return base ? `${base}＋${fee}` : fee;
  }
  if (vo.billingMode === 'OFFICIAL_TOKEN' && vo.inputPricePerMillion != null) {
    return `${sym}${Number(vo.inputPricePerMillion).toFixed(2)}/百万`;
  }
  if (vo.unitPricePerCall != null && Number(vo.unitPricePerCall) === 0) return '免费';
  if (vo.unitPricePerCall != null) return `¥${Number(vo.unitPricePerCall).toFixed(4)}/次`;
  return '';
};

/** 页签激活时按需加载: 价格目录页签拉价格表（批1015c Q1 撤「默认模型」页签, defaults 分支同删） */
const ensureTabData = async (tab: string) => {
  if (tab === 'keys') {
    // Q5：首次进钥匙管理页签——默认定位当前选中厂商（模型配置页的厂商卡），租户落"我的租户"
    if (!keysProvider.value) {
      keysProvider.value = (activeGroup.value?.provider ?? vendorGroups.value[0]?.provider ?? '') as DcsAiProvider;
      await onKeysProviderChange();
    } else if (!keyList.value.length && !keysLoading.value) {
      await onKeysFilterChange();
    }
  } else if (tab === 'prices') {
    await loadPriceList();
  }
};
watch(activeTab, (tab) => {
  void ensureTabData(tab);
});
// ==================== 批5 结束 ====================

// ===== K 批二期：高级折叠（技术参数不出现在默认视线，有自定义值自动展开+徽标） =====
const advancedOpen = ref<string[]>([]);
// 与 initFormData 缺省值逐项对比，差一项算一项；价格面板只对与缺省不同的数值字段计数
// （k3 七审 P2：priceId 存在本身不算自定义——编辑老配置时价格行总会回填，按 priceId 计数
// 会让所有配过价格的配置永远亮徽标）
const countAdvancedCustom = (src: Partial<DcsAiConfigForm>, price: Partial<DcsAiModelPriceForm> = {}): number => {
  let n = 0;
  if (src.contextWindow !== initFormData.contextWindow) n++;
  if (src.temperature !== initFormData.temperature) n++;
  if (src.timeoutSeconds !== initFormData.timeoutSeconds) n++;
  if ((src.reasoningEffort || '') !== (initFormData.reasoningEffort || '')) n++;
  if (src.billingMode !== initFormData.billingMode) n++;
  if (src.unitPricePerCall !== initFormData.unitPricePerCall) n++;
  if (src.serviceFeeRate !== initFormData.serviceFeeRate) n++;
  if (src.balanceQueryEnabled !== initFormData.balanceQueryEnabled) n++;
  if ((price.inputPricePerMillion ?? 0) !== initPriceFormData.inputPricePerMillion) n++;
  if ((price.cachedInputPricePerMillion ?? 0) !== initPriceFormData.cachedInputPricePerMillion) n++;
  if ((price.outputPricePerMillion ?? 0) !== initPriceFormData.outputPricePerMillion) n++;
  return n;
};
const advancedCustomCount = computed(() => countAdvancedCustom(form.value, inlinePriceForm.value));
const reasoningEffortLabel = (v?: string) =>
  (({ low: 'Low（快速思考）', medium: 'Medium（中等思考）', high: 'High（深度思考）', max: 'Max（最大思考）' }) as Record<string, string>)[v || ''] ||
  '关闭';

const loadProviders = async () => {
  // Q5：模板/格式字典与厂商预设并行拉——任一失败不拖垮页面（下拉退化为手填）
  const [presetRes, tplRes, fmtRes] = await Promise.allSettled([
    listDcsAiProviderPresets(),
    listDcsAiProviderTemplates(),
    listDcsAiApiFormats()
  ]);
  providerOptions.value = presetRes.status === 'fulfilled' ? presetRes.value.data || [] : [];
  providerTemplates.value = tplRes.status === 'fulfilled' ? tplRes.value.data || [] : [];
  apiFormats.value =
    fmtRes.status === 'fulfilled' && fmtRes.value.data?.length
      ? fmtRes.value.data
      : [
          { value: 'CHAT_COMPLETIONS', label: 'Chat Completions（OpenAI 兼容）', path: '/chat/completions' },
          { value: 'ANTHROPIC_MESSAGES', label: 'Anthropic Messages', path: '/v1/messages' },
          { value: 'RESPONSES', label: 'OpenAI Responses', path: '/responses' }
        ];
  // 同时加载厂商字典(供下拉显示)
  try {
    const dictRes = await listDcsAiProviderDicts();
    providerDictOptions.value = dictRes.data || [];
  } catch (e) {
    providerDictOptions.value = [];
  }
};

const remoteTenantSearch = async (query: string) => {
  tenantLoading.value = true;
  try {
    const res = await listTenant({
      pageNum: 1,
      pageSize: 20,
      tenantId: query || '',
      contactUserName: '',
      contactPhone: '',
      companyName: ''
    });
    tenantOptions.value = res.rows || [];
  } finally {
    tenantLoading.value = false;
  }
};

const getList = async () => {
  loading.value = true;
  try {
    const res = (await listDcsAiConfig(queryParams.value)) as any;
    configList.value = res.rows || [];
    total.value = res.total || 0;
    // 卡片数据源独立拉取（k3 七审 P2），失败不阻塞表格主流程
    loadCardData();
    // AI 缺口批 Q9：巡检徽标只读独立拉取，同款失败静默（巡检表没数据=徽标不展示）
    loadPatrolHealth();
    // 8+=B(2026-10-15 拍板)：钥匙入口角标——不点开管理钥匙也能扫一眼每家厂商钥匙健康
    void loadKeyBadges(vendorGroups.value);
    await refreshBalances();
  } finally {
    loading.value = false;
  }
};

/* ===== AI 缺口批 Q9：04:00 巡检健康徽标（configId → 最新巡检行） ===== */
const patrolHealth = ref<Record<string, AiModelHealthRow>>({});
const loadPatrolHealth = async () => {
  try {
    const res: any = await getAiModelHealthLatest();
    patrolHealth.value = res?.data || {};
  } catch {
    patrolHealth.value = {};
  }
};
const patrolTip = (row: DcsAiConfigVO) => {
  const h = patrolHealth.value[row.configId];
  if (!h) return '';
  const when = h.checkTime ? String(h.checkTime).replace('T', ' ').slice(0, 16) : '-';
  if (h.status === 'OK') {
    return `每日 04:00 自动巡检：正常${h.latencyMs ? `（${h.latencyMs}ms）` : ''} · ${when}`;
  }
  return `每日 04:00 自动巡检：连续 ${h.failStreak || 1} 天失败 · ${h.detail || '原因未知'} · ${when}`;
};

const loadPriceList = async () => {
  priceLoading.value = true;
  try {
    const res: any = await listDcsAiModelPrice(priceQuery);
    priceList.value = res.rows || [];
    priceTotal.value = res.total || 0;
  } finally {
    priceLoading.value = false;
  }
};

const openPriceDialog = (row?: DcsAiModelPriceVO) => {
  priceForm.value = row ? { ...row } : { ...initPriceFormData };
  if (!priceForm.value.provider && providerOptions.value[0]) {
    priceForm.value.provider = providerOptions.value[0].provider;
  }
  normalizeCurrencyInput(priceForm.value);
  priceDialog.visible = true;
  priceDialog.title = row ? '修改价格参数' : '新增价格参数';
};

/** Q6：价格来源徽标——手工锁定优先展示，其次按同步/导入来源 */
const priceSourceLabel = (row: DcsAiModelPriceVO) => {
  if (row.manualOverride === '1') {
    if (row.priceSource === 'IMPORT') return '导入锁定';
    if (row.priceSource === 'MODELS_DEV') return '同步锁定';
    return '手工';
  }
  if (row.priceSource === 'MODELS_DEV') return 'models.dev';
  if (row.priceSource === 'IMPORT') return 'JSON 导入';
  return '手工';
};
const priceSourceTagType = (row: DcsAiModelPriceVO) => {
  if (row.manualOverride === '1') {
    if (row.priceSource === 'IMPORT') return 'warning';
    if (row.priceSource === 'MODELS_DEV') return 'success';
    return 'primary';
  }
  if (row.priceSource === 'MODELS_DEV') return 'success';
  if (row.priceSource === 'IMPORT') return 'warning';
  return 'info';
};
const priceSourceTip = (row: DcsAiModelPriceVO) => {
  const locked = row.manualOverride === '1';
  if (row.priceSource === 'MODELS_DEV') {
    const when = row.lastSyncedAt ? ` · ${String(row.lastSyncedAt).replace('T', ' ').slice(0, 16)}` : '';
    return locked ? `models.dev 同步${when} · 已锁定，后续同步跳过本行` : `models.dev 同步${when} · 未锁定，下次同步会覆盖`;
  }
  if (row.priceSource === 'IMPORT') {
    return locked ? 'JSON 导入 · 已锁定，同步/导入都不覆盖' : 'JSON 导入 · 未锁定，下次导入或同步会覆盖';
  }
  return locked ? '手工录入 · 已锁定，同步不覆盖' : '手工录入 · 未锁定，可被同步覆盖';
};

const priceSyncing = ref(false);
const onSyncModelsDev = async () => {
  priceSyncing.value = true;
  try {
    const res = await syncDcsAiModelPricesFromModelsDev();
    const d = res.data;
    proxy?.$modal.msgSuccess(
      `同步完成：新增 ${d?.inserted ?? 0} 条、更新 ${d?.updated ?? 0} 条、跳过 ${d?.skipped ?? 0} 条（手工锁定 / 未匹配）。官方价按美元录入，人民币模型请用「JSON 导入」或手工改价`
    );
    await loadPriceList();
  } finally {
    priceSyncing.value = false;
  }
};

const importDialog = reactive<DialogOption>({ visible: false, title: '' });
const importSubmitting = ref(false);
const importForm = reactive({ json: '' });
const importJsonSample = `[
  { "provider": "DEEPSEEK", "modelName": "deepseek-chat", "input": 2, "output": 8, "currency": "CNY", "manualOverride": true }
]`;
const openImportDialog = () => {
  importDialog.visible = true;
};
const submitImport = async () => {
  let parsed: any;
  try {
    parsed = JSON.parse(importForm.json || '[]');
  } catch {
    proxy?.$modal.msgError('JSON 解析失败，请检查括号/引号是否完整');
    return;
  }
  const list = Array.isArray(parsed) ? parsed : parsed?.prices;
  if (!Array.isArray(list) || !list.length) {
    proxy?.$modal.msgWarning('请粘贴价格数组，或 {"prices":[...]} 形式');
    return;
  }
  importSubmitting.value = true;
  try {
    const res = await importDcsAiModelPrices({ prices: list });
    const d = res.data;
    proxy?.$modal.msgSuccess(`导入完成：新增 ${d?.inserted ?? 0} 条、更新 ${d?.updated ?? 0} 条、跳过 ${d?.skipped ?? 0} 条`);
    importDialog.visible = false;
    importForm.json = '';
    await loadPriceList();
  } finally {
    importSubmitting.value = false;
  }
};

const submitPriceForm = async () => {
  if (!priceForm.value.provider || !priceForm.value.modelName) {
    proxy?.$modal.msgWarning('请填写厂商和模型名称');
    return;
  }
  // N2-#9b: 提交防抖(连点第二次按不下去, 不触发防重拦截弹窗)
  if (priceSubmitting.value) return;
  priceSubmitting.value = true;
  try {
    normalizeCurrencyInput(priceForm.value);
    if (priceForm.value.priceId) {
      await updateDcsAiModelPrice(priceForm.value);
    } else {
      await addDcsAiModelPrice(priceForm.value);
    }
    proxy?.$modal.msgSuccess('价格参数已保存');
    priceDialog.visible = false;
    await loadPriceList();
  } finally {
    priceSubmitting.value = false;
  }
};

const handleDeletePrice = async (row: DcsAiModelPriceVO) => {
  await proxy?.$modal.confirm(`确认删除价格参数「${row.provider}/${row.modelName}」？`);
  await delDcsAiModelPrice(row.priceId);
  proxy?.$modal.msgSuccess('删除成功');
  await loadPriceList();
};

// 刷新余额（业务批3-② #13 五连修）：纯轮询链路（getList 后与 30s 定时都走这里，恒静默）——
// 失败不降级逐卡探测（根治"批量失败→逐卡 probe 需 edit 权限→403 连环弹"的降级风暴）；
// 401 静默停表（stoppedByAuth 粘性，防切页签回来空转）；单配置「查余额」按钮走 handleProbeBalance
// 单卡 probe API（非静默、失败有弹窗），不经此函数。
// k3 七审 P2: 查询范围=当前页 ∪ 卡片区启用行(独立拉取的启用行可能在其它页, 余额也须覆盖)
const refreshBalances = async () => {
  const scope = [...configList.value, ...cardConfigs.value.filter((c) => !configList.value.some((r) => r.configId === c.configId))];
  const queryableConfigs = scope.filter((item) => item.balanceQueryEnabled !== '0');
  const queryableIds = queryableConfigs.map((item) => item.configId);
  if (!queryableIds.length) {
    balanceResults.value = {};
    return;
  }
  try {
    const res = await getDcsAiBalances(queryableIds, true);
    balanceResults.value = res.data || {};
  } catch (e: any) {
    // 登录过期：立即停表并置粘性标志（重登回来 onActivated 清除），静默退出不打扰
    if (isBusinessErrorCode(e, 401)) {
      stoppedByAuth = true;
      stopBalancePolling();
      return;
    }
    // 轮询失败：卡片标查询失败（UI 已有态），等 30s 后下一轮自愈；不降级不弹错
    const fallback: DcsAiBalanceMap = {};
    queryableIds.forEach((id) => {
      fallback[String(id)] = { available: false, message: '查询失败', balances: [] };
    });
    balanceResults.value = fallback;
    console.warn('[ai-config] 余额轮询失败:', e?.message || e);
  }
};

const startBalancePolling = () => {
  if (balanceTimer) return;
  // 业务批3-②(#13): 5s→30s——每 5s 真打厂商 API 既刷存在感又耗配额，30s 足够看清趋势
  balanceTimer = setInterval(() => refreshBalances(), 30000);
};

const stopBalancePolling = () => {
  if (balanceTimer) {
    clearInterval(balanceTimer);
    balanceTimer = undefined;
  }
};

// 业务批3-②(#13): 浏览器标签页不可见时停轮询（省厂商外呼），回来恢复；
// 三重门控 startIfVisible 统一起表口径（可见 + 页面活跃 + 未因登录过期停表）——
// pageActive 防 keep-alive 缓存页被 visibility 意外拉起；stoppedByAuth 粘性防 401 后
// 切页签/路由往返空转（重登后 onActivated 清除重新起表）。
// 四席终审 P1-2 回修：标志置位与监听挂载必须在 onMounted 同步段先行——async await 期间
// 组件可能已失活/卸载，续跑回调再起表会复活定时器；getList 抛错也不能跳过起表（否则永不起）。
let pageActive = false;
let stoppedByAuth = false;
const startIfVisible = () => {
  if (document.visibilityState !== 'hidden' && pageActive && !stoppedByAuth) {
    startBalancePolling();
  }
};
const onVisibilityChange = () => {
  if (document.visibilityState === 'hidden') {
    stopBalancePolling();
  } else {
    startIfVisible();
  }
};

const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryFormRef.value?.resetFields();
  handleQuery();
};

// ===== R1(2026-10-10 拍板A) 厂商中心 =====
/** 当前选中厂商（空=自动落第一家）； getList 后被 watch 自动校正到有效值 */
const activeVendorCode = ref('');
/** 详情区本地筛选（不触发重查，纯客户端） */
const vcTypeFilter = ref<'' | 'CHAT' | 'EMBEDDING'>('');
const vcEnabledFilter = ref<'' | '1' | '0'>('');

interface VendorGroup {
  provider: string;
  models: DcsAiConfigVO[];
  accessConfigured: boolean;
  baseUrl?: string;
  /** 厂商级余额取数行：首个开启余额查询的配置（同厂同钥匙=一个账户，余额不必每卡重复） */
  balanceRow?: DcsAiConfigVO;
}
/* 业务批(1015) Q1 拆星：hasDefault/defaultSlots 字段与 slotShortLabel/slotFullLabel
   随主页面星标展示一起下线（批1015c Q1 起默认视图归「AI 助手管理」页模型选择卡） */
/** 左栏厂商列表=只列已接入（数据里出现过配置行）的厂商（对齐 Cherry Studio 侧栏口径：预设
 *  目录不铺侧栏，全部收进「+ 新增厂商」下拉）；同厂多租户行都归堆到一家（钥匙按 租户×厂商
 *  分层，卡行自带租户标签） */
const vendorGroups = computed<VendorGroup[]>(() => {
  const byProvider = new Map<string, DcsAiConfigVO[]>();
  for (const row of configList.value) {
    const list = byProvider.get(row.provider) ?? [];
    list.push(row);
    byProvider.set(row.provider, list);
  }
  return [...byProvider.entries()].map(([provider, models]) => ({
    provider,
    models,
    accessConfigured: models.some((m) => m.accessConfigured),
    baseUrl: models.find((m) => m.baseUrl)?.baseUrl,
    balanceRow: models.find((m) => m.balanceQueryEnabled !== '0')
  }));
});
// 默认选中=第一家厂商（列表只含已接入厂商，零操作即见真数据）
const activeGroup = computed(() => vendorGroups.value.find((g) => g.provider === activeVendorCode.value) ?? vendorGroups.value[0] ?? null);
function selectVendor(provider: string) {
  activeVendorCode.value = provider;
}
watch(activeGroup, (g) => {
  if (g && g.provider !== activeVendorCode.value) {
    activeVendorCode.value = g.provider;
  }
});

/* ===== 8+=B(2026-10-15 拍板)：钥匙入口角标 =====
 * 数据面：厂商组可能跨租户（同厂多租户归堆），按组内出现的 租户×厂商 逐对拉 /access/list
 * 聚合成一把角标；管理钥匙弹窗的保存/启停/删除走 refreshKeyBadge 单点失效。
 * 安全面：VO 只含掩码与状态，角标零新增密钥信息暴露。 */
const keyBadgeStore = ref<Record<string, DcsAiProviderAccessVO[]>>({});
const keyBadgeKey = (tenantId: string, provider: string) => `${tenantId}|${provider}`;

async function fetchKeysInto(store: Record<string, DcsAiProviderAccessVO[]>, tenantId: string, provider: string) {
  try {
    const res: any = await listDcsAiProviderAccessKeys({ tenantId, provider });
    store[keyBadgeKey(tenantId, provider)] = (res?.data ?? []) as DcsAiProviderAccessVO[];
  } catch {
    /* 静默：角标拉不到就不显示，不阻塞页面 */
  }
}
async function loadKeyBadges(groups: VendorGroup[]) {
  const pairs = new Map<string, { tenantId: string; provider: string }>();
  for (const g of groups) {
    for (const m of g.models) {
      if (m.tenantId) {
        pairs.set(keyBadgeKey(String(m.tenantId), g.provider), { tenantId: String(m.tenantId), provider: g.provider });
      }
    }
  }
  const next: Record<string, DcsAiProviderAccessVO[]> = { ...keyBadgeStore.value };
  await Promise.all([...pairs.values()].map((p) => fetchKeysInto(next, p.tenantId, p.provider)));
  keyBadgeStore.value = next;
}
/** 管理钥匙弹窗保存/启停/删除后：只重拉受影响的 租户×厂商 */
async function refreshKeyBadge(tenantId: string, provider: string) {
  const next = { ...keyBadgeStore.value };
  await fetchKeysInto(next, tenantId, provider);
  keyBadgeStore.value = next;
}

interface KeyBadgeInfo {
  level: 'none' | 'ok' | 'warn' | 'bad';
  total: number;
  /** Q6：本租户（登录租户）把数 */
  own: number;
  /** Q6：其他租户把数（角标显示 own+others） */
  others: number;
  enabled: number;
  /** 角标短文案（详情头 tag 用） */
  text: string;
  /** tooltip 逐把分解 */
  tip: string;
}
/** 聚合某厂商组全部钥匙 → 角标信息。
 * 定级：全停用或启用中的全查失败=红；部分停用/部分查失败=橙；其余=绿；零钥匙=灰。 */
function keyBadgeOf(g: VendorGroup): KeyBadgeInfo {
  const tenants = [...new Set(g.models.map((m) => String(m.tenantId ?? '')))];
  const keys = tenants.flatMap((tid) => keyBadgeStore.value[keyBadgeKey(tid, g.provider)] ?? []);
  const total = keys.length;
  // Q6：按租户拆 own/others；tip 附各租户把数明细（租户名从租户选项带出，带不出显编号）
  const myTenant = useUserStore().tenantId || '';
  const own = (keyBadgeStore.value[keyBadgeKey(myTenant, g.provider)] ?? []).length;
  const others = Math.max(0, total - own);
  if (!total) {
    return { level: 'none', total: 0, own: 0, others: 0, enabled: 0, text: '未配钥匙', tip: '该厂商还没有配置任何钥匙' };
  }
  const enabledKeys = keys.filter((k) => k.enabled !== '0');
  const failedKeys = enabledKeys.filter((k) => k.balanceStatus === 'FAILED');
  const allStopped = enabledKeys.length === 0;
  const allFailed = enabledKeys.length > 0 && failedKeys.length === enabledKeys.length;
  const healthy = enabledKeys.length - failedKeys.length;
  const level: KeyBadgeInfo['level'] = allStopped || allFailed ? 'bad' : enabledKeys.length < total || failedKeys.length > 0 ? 'warn' : 'ok';
  const suffix = allStopped ? '全停用 ⚠' : allFailed ? '启用中全失败 ⚠' : healthy === total ? '全部启用' : `${healthy}/${total} 可用`;
  const tip = keys
    .map((k) => {
      const state = k.enabled === '0' ? '已停用' : failedKeys.includes(k) ? '启用 · 余额查询失败' : '启用';
      const bal =
        k.balanceStatus === 'OK' && k.balanceAmount != null
          ? k.balanceCurrency === '%'
            ? ` · 剩余 ${Number(k.balanceAmount).toFixed(1)}%`
            : ` · ${k.balanceCurrency || 'CNY'} ${Number(k.balanceAmount).toFixed(2)}`
          : '';
      return `${k.label || '未命名'} ${k.apiKeyMask || ''}：${state}${bal}`;
    })
    .join('；');
  const tenantBreak = tenants
    .map((tid) => {
      const n = (keyBadgeStore.value[keyBadgeKey(tid, g.provider)] ?? []).length;
      if (!n) {
        return '';
      }
      const hit = tenantOptions.value.find((t) => t.tenantId === tid);
      const name = hit ? hit.companyName || hit.contactUserName || tid : tid;
      return `${name}（${tid === myTenant ? '本租户' : tid}）${n} 把`;
    })
    .filter(Boolean)
    .join(' · ');
  return {
    level,
    total,
    own,
    others,
    enabled: enabledKeys.length,
    text: `🔑 ${own}${others ? `+${others}` : ''} · ${suffix}`,
    tip: `${total} 把钥匙（${g.models.length} 个模型共用）：${tenantBreak}——${tip}`
  };
}
/** 详情头角标（当前选中厂商） */
const activeKeyBadge = computed<KeyBadgeInfo | null>(() => (activeGroup.value ? keyBadgeOf(activeGroup.value) : null));
const vendorModels = computed(() => {
  const g = activeGroup.value;
  if (!g) {
    return [];
  }
  return g.models.filter(
    (m) => (!vcTypeFilter.value || m.useType === vcTypeFilter.value) && (!vcEnabledFilter.value || m.enabled === vcEnabledFilter.value)
  );
});
/** 卡片勾选（替代旧表格 selection）：手工维护全量勾选集，ids/single/multiple 与旧语义一致 */
function toggleModelSel(configId: string | number) {
  const next = [...ids.value];
  const i = next.indexOf(configId);
  if (i >= 0) {
    next.splice(i, 1);
  } else {
    next.push(configId);
  }
  ids.value = next;
  single.value = next.length !== 1;
  multiple.value = !next.length;
}

const resetProbeState = () => {
  modelOptions.value = [];
  balanceInfo.value = undefined;
  speedInfo.value = undefined;
};

const reset = () => {
  form.value = { ...initFormData };
  inlinePriceForm.value = { ...initPriceFormData };
  resetProbeState();
  advancedOpen.value = [];
  capabilityMatched.value = false;
  // N1 回修：模板选择态跟着表单一起清，否则上次「国际站」模板残留会盖掉手动选的厂商/地址
  selectedTemplateId.value = '';
  configFormRef.value?.resetFields();
};

// Q5(2026-10-23)：模板一键带出——厂商/地址/接口格式/默认模型一次到位，变体（国内站/国际站、
// opencode 网关类型）不用手改地址；选完后清空模板态避免与手改值打架
const applyTemplate = (templateId: string) => {
  const tpl = providerTemplates.value.find((t) => t.templateId === templateId);
  if (!tpl) return;
  form.value.provider = tpl.provider;
  form.value.baseUrl = tpl.baseUrl;
  form.value.apiFormat = tpl.apiFormat;
  if (tpl.defaultModel) {
    form.value.modelName = tpl.defaultModel;
  }
  resetProbeState();
  syncInlinePriceForm();
  autoMatchCapability();
};

// 应用厂商预设: 选厂商后自动回填 Base URL + 默认模型名, 重置探测状态并同步官方价格表单
const applyPreset = () => {
  const preset = providerOptions.value.find((item) => item.provider === form.value.provider);
  if (!preset) return;
  form.value.baseUrl = preset.baseUrl;
  form.value.modelName = preset.modelName;
  form.value.apiFormat = preset.apiFormat || 'CHAT_COMPLETIONS';
  selectedTemplateId.value = '';
  resetProbeState();
  syncInlinePriceForm();
  // 批C 回修：模型名是程序赋值不会触发 @change，能力目录匹配必须在这里补挂——
  // 否则「选厂商→默认模型名」路径上下文停留在 initFormData 的 128K 假默认、最大输出不预填
  autoMatchCapability();
};

const handleAdd = async () => {
  reset();
  accessInfo.value = null;
  // 增补批(2026-10-10 用户反馈"历史key不能用"根因)：新增默认预填当前租户——
  // 接入判定要 租户+厂商 双值,租户空着时已配过钥匙的厂商被当"首次配置"要求重填 Key；
  // 超管仍可清空改选其它租户。按预填租户号拉下拉保证回显名正确
  const userStore = useUserStore();
  form.value.tenantId = userStore.tenantId || '';
  await remoteTenantSearch(form.value.tenantId || '');
  await syncInlinePriceForm();
  dialog.visible = true;
  dialog.title = '新增AI模型配置';
};

const handleUpdate = async (row?: DcsAiConfigVO) => {
  reset();
  accessInfo.value = null;
  await remoteTenantSearch('');
  const configId = row?.configId || ids.value[0];
  const res = await getDcsAiConfig(configId);
  Object.assign(form.value, res.data);
  // 修改时清空 apiKey: 留空表示沿用原密钥，探测按钮会调用已保存密钥的后端接口。
  form.value.apiKey = '';
  // 批B: 显式拉接入状态（租户/厂商与上次弹窗态相同时 watch 不触发）
  await loadAccessState();
  await syncInlinePriceForm();
  // 编辑态 contextWindow 用库中已存值(历史配置可能早于能力目录), 不重新匹配覆盖
  capabilityMatched.value = false;
  // K 批二期：有自定义技术参数/已配价格 → 高级折叠自动展开亮徽标；全默认 → 收起
  advancedOpen.value = countAdvancedCustom(form.value, inlinePriceForm.value) > 0 ? ['adv'] : [];
  if (!tenantOptions.value.some((item) => item.tenantId === form.value.tenantId)) {
    tenantOptions.value.unshift({
      tenantId: form.value.tenantId,
      companyName: '',
      id: '',
      username: '',
      contactUserName: '',
      contactPhone: '',
      licenseNumber: '',
      address: '',
      domain: '',
      intro: '',
      remark: '',
      packageId: '',
      expireTime: '',
      accountCount: 0,
      status: '0'
    });
  }
  dialog.visible = true;
  dialog.title = '修改AI模型配置';
};

const cancel = () => {
  reset();
  dialog.visible = false;
};

// 构造探测请求: 必须有厂商+BaseURL; 编辑态无明文密钥时返回 undefined (调用方应改走 probeSaved 系列);
// 新增态: 有明文密钥用明文, 无明文但已有厂商接入 → 带 tenantId 让服务端从接入行补钥(批B 同厂加模型不重填钥匙)
const probePayload = (): DcsAiProbeForm | undefined => {
  if (!form.value.provider || !form.value.baseUrl) {
    proxy?.$modal.msgWarning('请先选择厂商并填写 Base URL');
    return undefined;
  }
  if (!form.value.apiKey || form.value.apiKey === '****') {
    if (form.value.configId) {
      return undefined;
    }
    if (accessInfo.value) {
      return {
        provider: form.value.provider,
        baseUrl: form.value.baseUrl,
        apiKey: '',
        tenantId: String(form.value.tenantId || ''),
        modelName: form.value.modelName,
        temperature: form.value.temperature,
        timeoutSeconds: form.value.timeoutSeconds
      };
    }
    proxy?.$modal.msgWarning('请先填写当前明文 API Key');
    return undefined;
  }
  return {
    provider: form.value.provider,
    baseUrl: form.value.baseUrl,
    apiKey: form.value.apiKey,
    tenantId: String(form.value.tenantId || ''),
    modelName: form.value.modelName,
    temperature: form.value.temperature,
    timeoutSeconds: form.value.timeoutSeconds
  };
};

const shouldUseSavedProbe = () => Boolean(form.value.configId && !form.value.apiKey);
const savedProbePayload = (): DcsAiProbeForm => ({
  provider: form.value.provider,
  baseUrl: form.value.baseUrl,
  modelName: form.value.modelName,
  temperature: form.value.temperature,
  timeoutSeconds: form.value.timeoutSeconds
});

const mergeCurrentModelOption = () => {
  if (!form.value.modelName) return;
  if (!modelOptions.value.some((item) => item.modelId === form.value.modelName)) {
    modelOptions.value.unshift({ modelId: form.value.modelName, ownedBy: 'current' });
  }
};

// 探测可用模型列表: 编辑态无明文密钥走 probeSaved(用服务端密钥), 否则用明文 probe; 把当前已填模型也并入下拉保证可选
const handleProbeModels = async () => {
  const data = probePayload();
  if (!data && !shouldUseSavedProbe()) return;
  probeLoading.models = true;
  try {
    const res = shouldUseSavedProbe() ? await probeSavedDcsAiModels(form.value.configId!, savedProbePayload()) : await probeDcsAiModels(data!);
    // 业务批4 Q16-A：获取后免费置顶（sort 稳定，组内保持厂商原序；当前已填模型随后 unshift 保证可见）
    const rows = res.data || [];
    modelOptions.value = rows.slice().sort((a, b) => Number(b.free === true) - Number(a.free === true));
    mergeCurrentModelOption();
    if (!modelOptions.value.length) {
      proxy?.$modal.msgWarning('未获取到模型列表');
      return;
    }
    if (!form.value.modelName) {
      form.value.modelName = modelOptions.value[0].modelId;
      // 业务批3-①(2026-10-12)：程序赋值不触发 @change，能力目录+内联价格表单都要跟着模型名走
      //（三席 P2 回修：原只补 autoMatchCapability，漏了 syncInlinePriceForm——onModelNameChange 两件套全）
      await onModelNameChange();
    }
    proxy?.$modal.msgSuccess(`已获取 ${modelOptions.value.length} 个模型`);
  } finally {
    probeLoading.models = false;
  }
};

const handleProbeBalance = async () => {
  const data = probePayload();
  if (!data && !shouldUseSavedProbe()) return;
  probeLoading.balance = true;
  try {
    const res = shouldUseSavedProbe() ? await probeSavedDcsAiBalance(form.value.configId!, savedProbePayload()) : await probeDcsAiBalance(data!);
    balanceInfo.value = res.data;
  } finally {
    probeLoading.balance = false;
  }
};

const handleProbeSpeed = async () => {
  const data = probePayload();
  if (!data && !shouldUseSavedProbe()) return;
  if (!form.value.modelName) {
    proxy?.$modal.msgWarning('请先选择模型');
    return;
  }
  probeLoading.speed = true;
  try {
    const res = shouldUseSavedProbe() ? await probeSavedDcsAiSpeed(form.value.configId!, savedProbePayload()) : await probeDcsAiSpeed(data!);
    speedInfo.value = res.data;
    if (res.data.success) {
      proxy?.$modal.msgSuccess(`测速成功：${res.data.latencyMs || '-'} ms`);
    }
  } finally {
    probeLoading.speed = false;
  }
};

// 模型配置保存: 校验通过后, 有 configId 走 update 否则 add; apiKey 留空时后端沿用原密钥; 成功后刷新列表
const submitForm = () => {
  configFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    buttonLoading.value = true;
    try {
      if (form.value.configId) {
        // 批C 回修（dsflash P2-2）：清空走显式信号——后端只认 clearMaxTokens=true 才落 NULL，
        // null 双关会让部分提交调用方静默清列
        await updateDcsAiConfig({ ...form.value, clearMaxTokens: form.value.maxTokens == null });
      } else {
        await addDcsAiConfig(form.value);
      }
      proxy?.$modal.msgSuccess('操作成功');
      dialog.visible = false;
      await getList();
    } finally {
      buttonLoading.value = false;
    }
  });
};

const handleDelete = async (row?: DcsAiConfigVO) => {
  // 批3-1 U1（拍板 2026-10-10「都按推荐」）：删除确认分级——★默认行与批量含★改警示文案
  // 并说清「助手回退」后果，编号不再怼脸；普通行/无★批量维持原文案
  const configIds = row?.configId || ids.value;
  const cnt = Array.isArray(configIds) ? configIds.length : 1;
  const starCount = row
    ? row.isDefault === '1'
      ? 1
      : 0
    : configList.value.filter((r) => ids.value.includes(r.configId) && r.isDefault === '1').length;
  if (row && starCount === 1) {
    await proxy?.$modal.confirm(`「${row.modelName}」是当前默认模型（★），删除后助手调用将回退到下一个可用模型。确认删除？`);
  } else if (starCount > 0) {
    await proxy?.$modal.confirm(`所选 ${cnt} 行中有 ${starCount} 行是默认模型（★），删除后助手调用将回退到下一个可用模型。确认删除？`);
  } else {
    await proxy?.$modal.confirm(`是否确认删除AI模型配置「${configIds}」？`);
  }
  await delDcsAiConfig(configIds);
  if (row && starCount === 1) {
    proxy?.$modal.msgSuccess('已删除默认模型（★）——助手将回退到下一个可用模型');
  } else if (starCount > 0) {
    proxy?.$modal.msgSuccess(`已删除 ${cnt} 行（含默认模型★）——助手将回退到下一个可用模型`);
  } else {
    proxy?.$modal.msgSuccess('删除成功');
  }
  await getList();
};

// 连通性测试: 调 testDcsAiConfig 用服务端已存密钥发一次真实请求, 返回延迟/错误信息
const handleTest = async (row: DcsAiConfigVO) => {
  const res = await testDcsAiConfig(row.configId);
  if (res.data.success) {
    proxy?.$modal.msgSuccess(res.data.latencyMs ? `连通成功：${res.data.latencyMs} ms` : res.data.message || '连通测试成功');
  } else {
    proxy?.$modal.msgError(res.data.message || '连通测试失败');
  }
  // Q2-A：手动体检与 04:00 巡检写同一张表——测完立刻回刷徽标，不让页面停在旧结论
  void loadPatrolHealth();
};

onMounted(() => {
  // 同步段：标志+监听先行（防 async 续跑竞态复活定时器/监听泄漏）
  pageActive = true;
  stoppedByAuth = false;
  document.addEventListener('visibilitychange', onVisibilityChange);
  void (async () => {
    try {
      await loadProviders();
      await remoteTenantSearch('');
      await getList();
      loadUsageSummary();
      // 深扫回修(glm53 P2-1): ?tab=prices/keys 深链直达时初始页签不走 watch(非 immediate),
      // 挂载链尾按当前页签补载, 否则落地空表需手动切页签再切回
      void ensureTabData(activeTab.value);
    } catch (e) {
      console.warn('[ai-config] 初始化加载失败(轮询仍会尝试):', e);
    }
    startIfVisible();
  })();
});
// keep-alive cache: 切回只刷列表(providers/tenant 字典不需重载)
onActivated(() => {
  pageActive = true;
  stoppedByAuth = false;
  void (async () => {
    try {
      await getList();
    } catch (e) {
      console.warn('[ai-config] 激活刷新失败(轮询仍会尝试):', e);
    }
    startIfVisible();
  })();
});
onDeactivated(() => {
  pageActive = false;
  stopBalancePolling();
});
onUnmounted(() => {
  pageActive = false;
  stopBalancePolling();
  document.removeEventListener('visibilitychange', onVisibilityChange);
});
</script>

<style scoped lang="scss">
/* ===== K 批二期：当前生效模型卡 ===== */
/* ===== 批5(Q7/Q11): 三页签 → 批1015c Q1 两页签（默认模型页签撤下, 样式族随删） ===== */
/* ===== 业务反馈批1015b Q5：钥匙管理页签 ===== */
.keys-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.keys-summary {
  color: var(--el-text-color-secondary);
  font-size: 12.5px;
  flex: 1;
  min-width: 200px;
}
.keys-toolbar-actions {
  display: flex;
  gap: 8px;
}
.keys-name {
  font-weight: 600;
  margin-right: 6px;
}
.keys-mask {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.keys-muted {
  color: var(--el-text-color-placeholder);
}
.keys-zero {
  color: var(--el-color-danger);
  font-weight: 600;
}
.keys-fail {
  color: var(--el-color-danger);
}
.keys-footnote {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12.5px;
}
.ai-key-drawer :deep(.el-drawer__footer) {
  text-align: right;
}
.ai-config-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}
.ai-config-tabs :deep(.el-tabs__item) {
  font-size: 14px;
}
/* 批1015c Q1 撤「默认模型」页签：defaults-guide/default-group/default-current/
   default-switch/default-empty/assistant-default-card 样式族随模板一并下线 */
.star-btn {
  padding: 2px 4px;
}
.star-btn.is-starred {
  color: var(--el-color-warning);
  font-size: 13px;
  line-height: 1;
}
.price-unit {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.price-free {
  color: var(--el-color-success);
  font-weight: 600;
}
.hint-info {
  vertical-align: -2px;
  margin-right: 2px;
  color: var(--el-color-primary);
}
.ai-config-adv__info {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
}
.price-toolbar-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 8px;
}

.usage-caller-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.usage-caller-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.usage-caller-empty {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}

/* ===== R1(2026-10-10 拍板A) 厂商中心 ===== */
.vendor-center {
  display: flex;
  gap: 14px;
  align-items: stretch;
  min-width: 0;
}

/* 左栏：厂商导航（真厂标为页面唯一彩色点，其余走 EP 灰阶） */
.vc-nav {
  width: 248px;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-card-radius);
  padding: 12px;
}
.vc-nav-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 2px 4px 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  .vc-nav-count {
    font-size: 12px;
    font-weight: 400;
    color: var(--el-text-color-secondary);
  }
}
.vc-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: var(--app-control-radius);
  border: 1px solid transparent;
  cursor: pointer;
  transition: background-color 0.15s;
  &:hover {
    background: var(--app-raised);
  }
  &.is-active {
    background: var(--app-active);
    border-color: var(--app-control);
    .vc-nav-name {
      color: var(--app-text);
      font-weight: 600;
    }
  }
}
.vc-nav-main {
  flex: 1;
  min-width: 0;
}
.vc-nav-name {
  font-size: 14px;
  color: var(--el-text-color-regular);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.vc-nav-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 1px;
}
.vc-star {
  font-size: 16px;
  color: var(--el-color-warning);
  flex: none;
}
/* 业务批7 Q6：默认★槽位 chip——替换单颗裸★，多槽位多 chip（对话/向量 × 平台/租户） */
/* 业务批(1015) Q1 拆星：chip 样式随模板移除 */
/* 8+=B：钥匙健康角标——形态统一走 .ui-pill，类内只保留 nav 行的不可压缩 */
.vc-key-chip {
  flex: none;
}
/* 业务批(1015) Q1 拆星：.vc-star-chip/.vc-default-tag 随主页面星标一起移除 */
.vc-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
  &.on {
    background: var(--el-color-success);
  }
  &.off {
    background: var(--el-border-color);
  }
}
.vc-nav-add-wrap {
  margin-top: 8px;
}
.vc-nav-add {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 8px 0;
  border: 1px dashed var(--app-control);
  border-radius: var(--app-control-radius);
  color: var(--app-muted);
  font-size: 13px;
  cursor: pointer;
  transition: background-color 0.15s;
  &:hover {
    background: var(--app-raised);
  }
}

/* 右栏：厂商详情 */
.vc-detail {
  flex: 1;
  min-width: 0;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-card-radius);
  padding: 16px 18px 18px;
}
.vc-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--app-rule);
  flex-wrap: wrap;
}
.vc-head-main {
  flex: 1;
  min-width: 240px;
}
.vc-head-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.vc-head-url {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 3px;
  word-break: break-all;
}
/* 厂商级余额（增补批）：一处显示替代逐卡重复 */
.vc-head-balance {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 5px;
  font-size: 12px;
  flex-wrap: wrap;
}
.vc-bal-label {
  color: var(--el-text-color-secondary);
}
.vc-bal-sub {
  color: var(--el-text-color-placeholder);
}
.vc-balance {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 500;
  white-space: nowrap;
  &.bal-success {
    color: var(--el-color-success);
  }
  &.bal-warning {
    color: var(--el-color-warning);
  }
}
.vc-head-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.vc-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0 10px;
  flex-wrap: wrap;
}
.vc-toolbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.vc-sel-count {
  font-size: 13px;
  color: var(--app-muted);
}
.vc-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 模型卡行 */
.vc-models {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.vc-model {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border: 1px solid var(--app-rule);
  border-radius: var(--app-control-radius);
  background: var(--app-surface);
  transition: border-color 0.15s;
  &:hover {
    border-color: var(--app-control);
  }
  &.is-off .vc-model-title {
    color: var(--el-text-color-secondary);
  }
  /* 业务批(1015) Q1 拆星：默认行金底随主页面星标一起移除（默认标识只在「默认模型」页签） */
}
.vc-check {
  flex: none;
  height: auto;
}
/* 业务批(1015) Q1 拆星：.vc-star-btn 样式随模板移除 */
.vc-model-main {
  flex: 1;
  min-width: 0;
}
.vc-model-name {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.vc-model-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--el-text-color-regular);
  word-break: break-all;
}
.vc-model-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 3px;
}
/* 视觉席窄屏批注：时间戳整段不拆行，防「更新/于」被断在两行 */
.vc-updated {
  white-space: nowrap;
}
.vc-model-side {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.vc-price {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.vc-param {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  border-bottom: 1px dashed var(--el-border-color);
  cursor: help;
}
.vc-model-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: none;
}

/* 窄屏：厂商导航折到顶部横向滚动（不锁死左右布局）；模型卡换行堆叠——
   卡内 flex 元素多，极窄下 main 被压到逐字符竖排（5.3fh 视觉复审 P0），改为
   main 独占整行 + side/actions 顺延换行 */
@media (max-width: 992px) {
  .vendor-center {
    flex-direction: column;
  }
  .vc-nav {
    width: 100%;
    flex-direction: row;
    align-items: center;
    overflow-x: auto;
    .vc-nav-head,
    .vc-nav-count {
      flex: none;
    }
    .vc-nav-item {
      flex: none;
      .vc-nav-main {
        width: auto;
        min-width: 0;
      }
      .vc-nav-meta {
        display: none;
      }
    }
    .vc-nav-add-wrap {
      margin-top: 0;
      flex: none;
    }
  }
  .vc-model {
    flex-wrap: wrap;
    .vc-model-main {
      flex: 1 1 100%;
    }
    .vc-model-side {
      margin-left: 28px;
      justify-content: flex-start;
    }
  }
}

/* 表格参数列：上下文值为主面，悬浮看全部推理参数 */
.param-cell {
  color: var(--el-text-color-regular);
}
.param-grid {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 6px 12px;
  font-size: 13px;
  span {
    color: var(--el-text-color-secondary);
  }
  b {
    font-weight: 600;
    color: var(--el-text-color-primary);
    text-align: right;
  }
}

/* ===== K 批二期：高级折叠（照文件检测已验收模式） ===== */
.ai-config-adv {
  border-top: 1px dashed var(--el-border-color-lighter);
  border-bottom: none;
  margin-top: 4px;

  &__title {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  &__badge {
    margin-left: 10px;
  }

  :deep(.el-collapse-item__header) {
    background: transparent;
    border-bottom: none;
    height: 40px;
    line-height: 40px;
  }

  :deep(.el-collapse-item__wrap) {
    background: transparent;
    border-bottom: none;
  }

  :deep(.el-collapse-item__content) {
    padding-bottom: 4px;
  }
}

.probe-message {
  display: inline-block;
  max-width: 640px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fee-cell {
  margin-top: 3px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.price-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

/* 375：表格在容器内横滚（父链逐级 min-width:0），不让整页横向溢出 */
.dcs-ops-page {
  min-width: 0;
}
.dcs-ops-page :deep(.el-tabs__content),
.dcs-ops-page :deep(.el-tab-pane) {
  min-width: 0;
}
.price-table-wrap,
.keys-table-wrap {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
  /* 2026-10-27 拍板 Q5=丙：内容区底色改成灰画布后，这两张「透明表头 + 白行」的表格
     直接铺在灰底上会被看成表格被切掉一截；给它们一张自己的纸面，表头与表身同底色 */
  background: var(--app-surface);
  border: 1px solid var(--app-rule);
  border-radius: var(--app-card-radius);
}
/* 只覆盖 EP 表格 CSS 变量，不重写组件（契约 §2.5） */
.price-table-wrap :deep(.el-table),
.keys-table-wrap :deep(.el-table) {
  --el-table-header-bg-color: transparent;
  --el-table-tr-bg-color: var(--app-surface);
  --el-table-row-hover-bg-color: var(--app-raised);
  --el-table-border-color: var(--app-rule);
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
}
.price-table-wrap :deep(.el-table) {
  /* 列宽之和：外层容器一处横滚，避免与 el-table 内部滚动条叠加 */
  min-width: 1138px;
}
.keys-table-wrap :deep(.el-table) {
  min-width: 1000px;
}

.ai-config-table {
  :deep(.nowrap-column .cell) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  :deep(.el-table-fixed-column--right) {
    background: var(--app-surface) !important;
  }

  :deep(.row-active .el-table-fixed-column--right) {
    background: var(--app-raised) !important;
  }
}

.ai-config-dialog {
  :deep(.el-dialog) {
    max-width: calc(100vw - 32px);
  }

  :deep(.el-dialog__body) {
    max-height: calc(100vh - 180px);
    overflow-y: auto;
    padding-top: 12px;
  }
}

/* 分区标题：用 el-divider 做轻量分组，统一呼吸节奏 */
.section-title {
  :deep(.el-divider__text) {
    font-size: 14px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }
}

.config-section-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
}

.config-section-card,
.official-price-panel {
  padding: 14px 14px 4px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--app-card-radius);
  background: var(--el-fill-color-extra-light);
}

.section-card-title,
.official-price-title {
  margin-bottom: 12px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
}

.official-price-panel {
  margin-top: 14px;
  padding-bottom: 14px;
}

.official-price-head,
.official-price-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.official-price-head {
  margin-bottom: 10px;
}

.official-price-desc,
.official-price-actions {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.official-price-actions {
  margin-top: 4px;
}

/* 数值参数行下方的单位/取值范围提示，让标签更短、对齐更整齐 */
.field-hint {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

/* Q5：接入模板下拉行（名称 + 格式徽标） */
.tpl-opt-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.tpl-opt-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Q5：实际请求地址预览条——等宽字体、可横向滚动，窄屏不撑破弹窗 */
.api-path-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: -4px 0 12px 110px;
  padding: 6px 10px;
  border-radius: var(--app-control-radius);
  background: var(--el-fill-color-lighter);
  border: 1px dashed var(--el-border-color);

  code {
    font-family: var(--el-font-family-mono, monospace);
    font-size: 12px;
    color: var(--el-color-primary);
    word-break: break-all;
  }
}
.api-path-preview--drawer {
  margin: 6px 0 0;
}
.api-path-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.import-foot-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
@media (max-width: 640px) {
  .api-path-preview {
    margin-left: 0;
  }
}

/* 业务批4 Q16-A：模型下拉免费行的「名称+FREE 徽章」行布局 */
.probe-opt-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;

  .probe-opt-name {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

/* 业务批4 Q17-A：体检拆分按钮组（主键+箭头视觉合一，模仿 split-button 形态） */
.hc-split {
  display: inline-flex;
  margin-right: 12px;

  .hc-main {
    border-right: none;
    border-top-right-radius: 0;
    border-bottom-right-radius: 0;
  }
  .hc-caret {
    border-top-left-radius: 0;
    border-bottom-left-radius: 0;
    padding-left: 6px;
    padding-right: 6px;
  }
}

/* 批C C1：最大输出预设 chips（4K/16K/64K/128K 快捷档）；回修（视觉席）：当前值档位加粗+下划线，
   用户能一眼看出值对应哪一档（chips 本身同色，不加态无区分） */
.max-tokens-chips {
  display: flex;
  gap: 2px;
  margin-top: 4px;

  :deep(.el-button + .el-button) {
    margin-left: 0;
  }

  :deep(.el-button.chip-on) {
    font-weight: 600;
    text-decoration: underline;
    text-underline-offset: 3px;
  }
}

/* 探测按钮独立成组：左缩进对齐表单输入框，按钮按内容宽度排布 */
.probe-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding-left: 110px;

  .probe-tip {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

.probe-result {
  margin-top: 14px;
}

@media (max-width: 992px) {
  .config-section-grid {
    grid-template-columns: 1fr;
  }

  .probe-actions {
    padding-left: 0;
  }

  .official-price-head,
  .official-price-actions {
    align-items: flex-start;
    flex-direction: column;
  }
}

/* 厂商视觉: 缩写徽章 + 颜色区分(color-not-only 规则, 色彩+文字双通道) */
.provider-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
/* 批B 厂商分组组头：接入状态 tag + 管理钥匙入口（挂在跨行合并的厂商格内） */
.provider-access-line {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 4px;
}
/* 批B 模型弹窗「由厂商接入统一提供」形态 */
.access-provided {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.access-provided .access-mask {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-family: monospace;
}
/* 批1015c Q1 撤「默认模型」页签：provider-badge/badge-* 徽章族（配合已删的
   PROVIDER_SHORT）随模板一并下线——厂商标识统一走 ProviderLogo 组件 */
.provider-name {
  font-size: 13px;
  color: var(--app-text);
}

/* 状态指示灯: 圆点+文字(state-clarity 规则) */
.status-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 13px;
}
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.dot-on {
  background: var(--dcs-color-success);
}
.dot-off {
  background: var(--app-faint);
}

/* API Key 设置状态图标 */
.key-set {
  color: var(--dcs-color-success);
  font-size: 16px;
}
.key-unset {
  color: var(--dcs-color-danger);
  font-size: 16px;
}

/* ===== 钥匙池卡片行（2026-10-15 视觉批：表格改卡片，对齐模型卡语言；EP 标准色板） ===== */
.kp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.kp-count {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.kp-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.kp-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 16px;
  border: 1px solid var(--app-rule);
  border-radius: var(--app-card-radius);
  background: var(--app-surface);
  transition: border-color 0.2s ease;

  &:hover {
    border-color: var(--app-control);
  }
  &.is-off {
    background: var(--app-raised);

    .kp-label {
      color: var(--el-text-color-secondary);
    }
  }
}
.kp-main {
  flex: 1;
  min-width: 0;
}
.kp-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.kp-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--app-faint);

  &.on {
    background: var(--dcs-color-success);
  }
}
.kp-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kp-sub {
  margin-top: 3px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.kp-key {
  font-family: var(--el-font-family-mono, 'JetBrains Mono', Consolas, monospace);
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.kp-sep {
  color: var(--app-rule);
}
.kp-balance {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  min-width: 150px;
}
.kp-amount {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  font-variant-numeric: tabular-nums;

  &.muted {
    color: var(--el-text-color-placeholder);
    font-size: 14px;
  }
  &.zero {
    color: var(--el-color-danger);
  }
}
.kp-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.kp-note.fail {
  color: var(--el-color-danger);
}
.kp-actions {
  flex: none;
  display: flex;
  align-items: center;
  gap: 4px;
  padding-left: 12px;
  border-left: 1px solid var(--app-rule);
}
.kp-foot {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>

<style lang="scss">
/* 活跃行高亮(CC Switch: 当前活跃配置视觉突出) */
.el-table .row-active {
  background-color: var(--app-raised) !important;
  td {
    border-left: 1px solid var(--app-rule);
  }
}
</style>
