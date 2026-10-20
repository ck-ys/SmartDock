<template>
  <div class="app-container dcs-as-admin">
    <!-- 页头（批C 重构）：标题+开通状态一屏可见，统计条零操作出数（五红线①） -->
    <div class="adm-head">
      <div class="head-txt">
        <div class="head-title">
          <b>AI 助手管理</b>
          <el-tag v-if="cfg.enabled" type="success" size="small" effect="light">已开通</el-tag>
          <el-tag v-else type="info" size="small" effect="light">未开通</el-tag>
          <el-tag type="warning" size="small" effect="plain">试点期</el-tag>
        </div>
        <span class="head-sub">本租户的助手开关、模型、工具与费用，这一页看清</span>
      </div>
    </div>

    <!-- 统计条（方案A重构 2026-10-11）：白底描边统计卡 + 数字统一主蓝（费用不再用橙色——EP 只认四级语义色），hover 边框亮起 -->
    <div v-if="checkPermi(['dcs:assistant:config'])" class="stat-grid" v-loading="statsLoading">
      <div class="stat-card">
        <div class="num">{{ stats?.conversations ?? '—' }}</div>
        <div class="lbl">会话</div>
      </div>
      <div class="stat-card">
        <div class="num">{{ stats?.messages ?? '—' }}</div>
        <div class="lbl">消息</div>
      </div>
      <div class="stat-card">
        <div class="num">{{ stats?.actions ?? '—' }}</div>
        <div class="lbl">动作执行</div>
      </div>
      <div class="stat-card">
        <div class="num">{{ stats?.feedbacks ?? '—' }}</div>
        <div class="lbl">反馈</div>
      </div>
      <div class="stat-card">
        <div class="num">{{ stats ? '¥' + Number(stats.monthCost ?? 0).toFixed(2) : '—' }}</div>
        <div class="lbl">本月费用</div>
      </div>
    </div>

    <el-tabs v-model="tab" class="adm-tabs" @tab-change="onTabChange">
      <!-- 页签一：租户开关（方案A重构 2026-10-11：kvrow 手拼列 → el-descriptions 边框式描述列表，标签列 168px 统一对齐；状态长说明收进 ⓘ） -->
      <el-tab-pane label="租户设置" name="config">
        <el-card shadow="never" class="adm-card" v-loading="loading">
          <el-descriptions :column="1" border class="adm-desc">
            <el-descriptions-item label="AI 助手开关">
              <div class="cell-flex">
                <el-switch v-model="cfg.enabled" :loading="saving" :before-change="beforeToggle" @change="onToggle" />
                <span class="cell-note">{{ cfg.enabled ? '已开通，立即生效' : '已停用，所有用户不可见' }}</span>
                <span class="sp" />
                <el-tooltip
                  placement="top"
                  :content="
                    cfg.enabled
                      ? '发布人与管理员可见悬浮球；停用后立即拒绝新请求，已批代办按账本处理（未执行取消 / 已执行保留记录 / 结果未知先核查）'
                      : '本租户所有用户看不到悬浮球，AI 请求直接拒绝'
                  "
                >
                  <span class="hint-dot">ⓘ</span>
                </el-tooltip>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="停用助手">
              <div class="cell-flex">
                <el-button type="danger" plain size="small" :disabled="!cfg.enabled" @click="openDisable">立即停用</el-button>
                <span class="cell-note">停用前会再弹一次确认</span>
              </div>
            </el-descriptions-item>
            <!-- 快刀批 Q6：助手对外称呼——用户端欢迎语/帮助弹窗/引导等 8 处随此变；清空=恢复平台默认 -->
            <el-descriptions-item label="助手称呼">
              <div class="cell-flex">
                <el-input
                  v-model="nameEdit"
                  size="small"
                  maxlength="50"
                  placeholder="默认 SmartDock 小助手"
                  style="width: 180px"
                  @change="onNameSave"
                />
                <span class="cell-note">用户端下次进入页面生效 · 清空=恢复默认</span>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="每日回合上限">每人 {{ cfg.dailyRoundLimitPerUser }} 回合 · 明早 0 点恢复</el-descriptions-item>
            <!-- 业务批3-④：租户联网默认（每人每日免费次数；0=全租户关停。改值走「用户额度」页单人覆盖或租户配置 API，本页先展示） -->
            <el-descriptions-item label="每日联网次数">
              每人 {{ cfg.websearchLimitPerUser ?? 20 }} 次<template v-if="(cfg.websearchLimitPerUser ?? 20) === 0"> · 已关停</template>
            </el-descriptions-item>
            <el-descriptions-item label="租户日预算">¥ {{ Number(cfg.dailyBudgetAmount).toFixed(2) }} / 天</el-descriptions-item>
            <!-- 批17 Q2：上下文压缩阈值——会话占用超过该值时自动把前文打包成摘要（用户也可 /压缩 手动打包） -->
            <el-descriptions-item label="自动压缩阈值">
              <div class="cell-flex">
                <el-input-number
                  v-model="compactionTokens"
                  :min="4000"
                  :max="500000"
                  :step="10000"
                  :loading="saving"
                  size="small"
                  controls-position="right"
                  style="width: 150px"
                  @change="onCompactionSave"
                />
                <span class="cell-note">tokens · 到线自动打包前文</span>
                <span class="sp" />
                <el-tooltip
                  placement="top"
                  content="一段对话的上下文占用超过这个数时，助手会自动把前面的对话打包成摘要，腾出空间继续聊（界面里会出现「已压缩」分隔条，关键结论都在摘要里）。调小=更早压缩更省额度；调大=保留更多原文。修改后对新建的会话生效。"
                >
                  <span class="hint-dot">ⓘ</span>
                </el-tooltip>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="长期记忆">
              <!-- 二站④ AU-53：开关真打通——save_memory 工具+逐轮注入+记忆页真数据同此开关 -->
              <div class="cell-flex">
                <el-switch v-model="memoryOn" :loading="saving" @change="onMemoryToggle" />
                <span class="cell-note">{{ memoryOn ? '已开启，用户偏好跨会话生效' : '未开启' }}</span>
                <span class="sp" />
                <el-tooltip placement="top" content="开启后用户偏好跨会话生效；各人可在「AI 助手记忆」页查看和删除自己的记忆。">
                  <span class="hint-dot">ⓘ</span>
                </el-tooltip>
              </div>
            </el-descriptions-item>
            <!-- 五需求批 R4b / Q7-C：每日摘要——08:00 给发布人推未交摘要通知（复用站内通知+SSE） -->
            <el-descriptions-item label="每日摘要">
              <div class="cell-flex">
                <el-switch v-model="digestOn" :loading="saving" @change="onDigestToggle" />
                <span class="cell-note">{{ digestOn ? '每天 08:00 给发布人推送未交摘要' : '未开启' }}</span>
                <span class="sp" />
                <el-tooltip
                  placement="top"
                  content="开启后每天早上 8 点给有进行中任务的发布人发一条站内摘要（谁还没交、离截止还有多久），安静日不打扰；可在通知中心查看。"
                >
                  <span class="hint-dot">ⓘ</span>
                </el-tooltip>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="钱包余额">{{ walletTxt }}</el-descriptions-item>
            <!-- 业务反馈批1015b Q14：联网搜索卡片行——密钥打码显示/一键测连通/替换密钥（先测后存）；
                 平台默认开关=没配 key 的租户借平台 key（显式勾选，费用记平台账） -->
            <el-descriptions-item label="联网搜索">
              <div class="cell-flex">
                <el-tag size="small" :type="wsState.configured ? 'success' : 'info'">
                  {{ wsState.configured ? '● 已启用' : '○ 未配置' }}
                </el-tag>
                <span class="cell-note ws-key">{{ wsState.configured ? wsState.maskedKey : '还没配置搜索密钥，联网搜索不可用' }}</span>
                <el-button link type="primary" size="small" :loading="wsTesting" @click="onWsTest">测试连通</el-button>
                <el-button v-hasPermi="['system:config:edit']" link type="primary" size="small" @click="wsReplace.open = true">替换密钥</el-button>
                <span v-if="wsTestMsg" class="cell-note" :class="{ 'ws-fail': !wsTestOk }">{{ wsTestMsg }}</span>
              </div>
              <div class="cell-flex" style="margin-top: 4px">
                <el-switch
                  v-model="wsState.platformDefault"
                  :loading="wsSavingSwitch"
                  :disabled="!wsCanEditConfig || userStore.tenantId !== '000000'"
                  @change="(v: any) => onPlatformDefaultToggle(v)"
                />
                <span class="cell-note">作为全平台默认：开启后，没配自己密钥的租户共用这把（按次计费、费用记平台账）</span>
                <span v-if="userStore.tenantId !== '000000'" class="cell-note">仅平台管理员（默认租户）可改</span>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="消费方式">
              <!-- 用户反馈(1014)：原「服务码价目」是死文案不随消费方式开关变——复用 getAssistantStatus.billingMode 动态显示当前租户是哪种 -->
              <div class="cell-flex">
                <el-tag size="small" :type="billingMode === 'credits' ? 'primary' : 'info'">
                  {{ billingMode === 'credits' ? '积分订阅' : '钱包按量' }}
                </el-tag>
                <span class="cell-note"
                  >{{ billingMode === 'credits' ? '对话扣积分不扣钱包' : '对话按实际用量扣钱包（元）' }} · 切换与明细见计费中心</span
                >
              </div>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- 批6(Q5/Q13)+方案A重构 2026-10-11：模型选择卡描述列表化；★ 星号标默认 → 「默认」tag（业内惯例）; 覆盖弹窗在页尾 -->
        <el-card shadow="never" class="adm-card model-card" v-loading="modelLoading">
          <template #header>
            <div class="card-head">
              <b>模型选择</b>
              <span class="head-note">助手回答用哪个模型，在这里选；模型接入和价格在「AI 模型配置」页管</span>
            </div>
          </template>
          <el-descriptions :column="1" border class="adm-desc">
            <el-descriptions-item label="助手默认模型">
              <div class="cell-flex">
                <el-select
                  v-model="modelForm.assistantDefaultConfigId"
                  style="width: 340px"
                  placeholder="选择本租户可用的 AI 调用模型"
                  :disabled="modelOptions.length === 0"
                  @change="onDefaultChange"
                >
                  <!-- 批B(Q3:A) 按厂商分组（ZCode 同款）：同厂多模型归堆好找 -->
                  <el-option-group v-for="g in groupedModelOptions" :key="g.provider" :label="g.label">
                    <el-option v-for="o in g.options" :key="o.configId" :value="o.configId" :label="optionLabel(o)">
                      <div class="opt-row">
                        <span class="opt-main">
                          <!-- 业务批4 Q14-A：行首厂商真标（复用 AI 模型配置页 ProviderLogo，未收录降字母徽章） -->
                          <ProviderLogo :provider="o.provider" :size="16" />
                          {{ o.modelName }}
                          <el-tag v-if="o.isDefault === '1'" size="small" effect="plain" class="def-tag">默认</el-tag>
                        </span>
                        <span class="opt-price">{{ optionPrice(o) }}</span>
                      </div>
                    </el-option>
                  </el-option-group>
                </el-select>
                <el-button size="small" :disabled="!modelForm.assistantDefaultConfigId" :loading="testLoading" @click="onTestConn"
                  >测试连接</el-button
                >
                <el-tag v-if="testResult" :type="testResult.success ? 'success' : 'danger'" size="small">
                  {{ testResult.success ? '连通' : '不通' }}{{ testResult.latencyMs ? ' · ' + testResult.latencyMs + 'ms' : '' }}
                </el-tag>
                <span v-if="modelOptions.length === 0" class="cell-note">本租户还没有可用的 AI 调用模型——先去「AI 模型配置」页接入</span>
              </div>
              <div class="cell-sub">用户没自选时走这个模型；模型失效会自动回落可用模型（接口默认★→最近可用）</div>
            </el-descriptions-item>

            <!-- 业务反馈批2 Q4（2026-10-16 拍板 A：整体搬进画像设置）：「后台 AI 默认」槽从本卡
                 移除——模型+思考档/最大输出参数统一在「用户画像 → 画像设置」弹窗改（保存仍走同一
                 POST /dcs/assistant/model-setting，库表零迁移只换家门）。此处留一版灰字指路（搬家
                 过渡期熟客不迷路，稳定后可删）；助手自己的默认模型不受影响，继续住这 -->
            <el-descriptions-item>
              <template #label>
                <span class="moved-slot-label">后台 AI 默认</span>
              </template>
              <div class="moved-slot-note">画像/评分/字段生成等后台 AI 的模型与参数，已统一搬到「用户画像 → 画像设置」里管理。</div>
            </el-descriptions-item>

            <!-- 该模型能力（只读带出——Q13: 目录事实, 改动走✎覆盖；方案A: 并入描述列表，操作右对齐收口在虚线分隔下） -->
            <el-descriptions-item v-if="capability" label="该模型能力">
              <div class="cell-flex" style="margin-bottom: 4px">
                <el-tag :type="capSourceTag.type" size="small">{{ capSourceTag.label }}</el-tag>
              </div>
              <div class="cap-row">
                <span class="k">思考档位</span>
                <span class="v">{{ semanticText }}</span>
              </div>
              <div class="cap-row">
                <span class="k">上下文窗口</span>
                <span class="v">{{ capability.contextWindow ? fmtCap(capability.contextWindow) : '目录未收录（按兜底 200K）' }}</span>
              </div>
              <div class="cap-row">
                <span class="k">最大输出</span>
                <span class="v">{{
                  capability.maxOutputTokens ? capability.maxOutputTokens.toLocaleString() + ' token / 次' : '目录未收录（按模型默认）'
                }}</span>
              </div>
              <div class="cap-row">
                <span class="k">支持输入</span>
                <span class="v">{{ capSupportsText }}</span>
              </div>
              <div class="cap-row">
                <span class="k">单价</span>
                <span class="v">{{ currentPriceText }}</span>
              </div>
              <div class="cap-actions">
                <el-button v-hasPermi="['dcs:ai:edit']" link type="primary" size="small" icon="Edit" @click="openOverride">覆盖</el-button>
                <el-button
                  v-if="capability.source === 'MANUAL' && checkPermi(['dcs:ai:edit'])"
                  link
                  type="warning"
                  size="small"
                  icon="RefreshLeft"
                  @click="onResetCap"
                  >恢复官方</el-button
                >
              </div>
            </el-descriptions-item>

            <el-descriptions-item label="允许用户自选模型">
              <div class="cell-flex">
                <el-tooltip :disabled="!!modelForm.assistantDefaultConfigId" content="先选定助手默认模型，才能打开用户自选" placement="top">
                  <span>
                    <el-switch
                      v-model="allowSwitchOn"
                      :disabled="!modelForm.assistantDefaultConfigId"
                      :loading="savingModel"
                      @change="onAllowSwitch"
                    />
                  </span>
                </el-tooltip>
                <span class="cell-note">
                  {{
                    allowSwitchOn
                      ? '已允许：用户在聊天输入条可换模型，换之前就能看到单价'
                      : '未允许：所有用户固定用上面的助手默认模型（默认关，防钱包被烧）'
                  }}
                </span>
              </div>
              <!-- 业务批4 Q3（拍板α）：模型开放表——自选开时展开，勾定用户能换哪些；
                   ⚠未定价行可见但用户侧不可选（CNY 闸），一键跳 AI 模型配置页补价；
                   业务批4 Q15-B（2026-10-13 拍板）：同厂模型聚堆＋组头 banner（logo+厂名+数量），
                   厂商列整个省掉——与上方下拉的分组语言一致；组头行不参与勾选/悬停 -->
              <div v-if="allowSwitchOn" class="scope-box">
                <div class="scope-head">
                  <span>用户能换哪些模型（勾选=白名单；一个不勾=全部可换）</span>
                  <el-button link type="primary" size="small" @click="gotoPricePage">去「AI 模型配置」页补价格 →</el-button>
                </div>
                <el-table
                  :data="scopeGroupedRows"
                  size="small"
                  class="scope-table"
                  max-height="260"
                  :span-method="scopeSpanMethod"
                  :row-class-name="scopeRowClass"
                >
                  <el-table-column width="46">
                    <template #default="{ row }">
                      <!-- Q15-B：组头 banner 必须放在首列——span-method 让首列整行横跨 4 列，
                           后三列格子被 [0,0] 吸收，banner 放别列会被藏掉（E2E 实证 banners 全空） -->
                      <div v-if="row.__group" class="scope-grp">
                        <ProviderLogo :provider="row.provider" :size="16" />
                        <span class="scope-grp-name">{{ row.label }}</span>
                        <span class="scope-grp-count">{{ row.count }} 个模型</span>
                      </div>
                      <el-checkbox
                        v-else
                        :model-value="scopeChecked.has(String(row.configId))"
                        :disabled="scopeSaving"
                        @change="(v: boolean | string | number) => toggleScope(String(row.configId), v === true)"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column label="模型" min-width="220">
                    <template #default="{ row }">
                      <template v-if="!row.__group">
                        <span>{{ row.modelName }}</span>
                        <el-tag v-if="String(row.configId) === String(modelForm.assistantDefaultConfigId)" size="small" effect="plain" class="def-tag"
                          >默认</el-tag
                        >
                      </template>
                    </template>
                  </el-table-column>
                  <el-table-column label="单价" width="170">
                    <template #default="{ row }">
                      <span v-if="!row.__group">{{ optionPrice(row) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="用户侧" width="150">
                    <template #default="{ row }">
                      <template v-if="!row.__group">
                        <el-tag v-if="userSelectable(row)" size="small" type="success" effect="plain">可选</el-tag>
                        <el-tooltip v-else content="人民币价格未配置——用户选择器不显示此模型（计费闸只认 ¥ 价）" placement="top">
                          <el-tag size="small" type="warning" effect="plain">⚠ 未定价不可选</el-tag>
                        </el-tooltip>
                      </template>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-tab-pane>

      <!-- 页签二：能力全景（Q4/3.1 批C）：全部工具+系统提示词只读——助手的"说明书"；
           dspro P1-1 回修：端点已收紧到 config 权限，页签守卫与统计条同款对齐 -->
      <el-tab-pane v-if="checkPermi(['dcs:assistant:config'])" label="能力全景" name="panorama" lazy>
        <el-card shadow="never" class="adm-card" v-loading="panoLoading">
          <template #header>
            <div class="card-head">
              <b>助手会用的工具</b>
              <span class="head-note">
                共 {{ panoTools.length }} 个 · 自动执行 {{ autoCount }} 个 · 需用户确认 {{ askCount }} 个——确认类工具执行前会先弹卡片征求同意
              </span>
            </div>
          </template>
          <el-empty v-if="!panoLoading && panoTools.length === 0" description="工具清单加载失败或为空——切换页签重试" :image-size="60" />
          <el-table v-else :data="panoTools" size="default" stripe>
            <el-table-column label="工具" min-width="150">
              <template #default="{ row }">
                <div class="tool-cell">
                  <span class="tool-name">{{ row.displayName }}</span>
                  <span class="tool-code">{{ row.name }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="分类" width="90">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="row.category === '动作' ? 'warning' : row.category === '交互' ? 'primary' : 'info'">
                  {{ row.category }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="批次" width="80">
              <template #default="{ row }">{{ STATION_TXT[row.station] ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="执行方式" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.behavior === '需确认' ? 'warning' : 'success'" effect="light">
                  {{ row.behavior }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="使用门槛" prop="gateDesc" min-width="140">
              <template #default="{ row }">{{ row.gateDesc || '所有用户' }}</template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- 业务批7 Q9：技能库（➕ 面板可选的技能第一次在管理端可见——名称/门槛/注入方式/正文折叠） -->
        <!-- k3 P1-1：补 pano-prompt 类——.sys-prompt/.el-collapse 的 scoped 样式全嵌套在
             .pano-prompt 下（与下方总纲卡同款），缺了它 pre 无 white-space/max-height 约束长文溢出 -->
        <el-card shadow="never" class="adm-card pano-prompt" v-loading="panoLoading">
          <template #header>
            <div class="card-head">
              <b>技能库（➕ 面板）</b>
              <span class="head-note">共 {{ panoSkills.length }} 个 · 会话中选中才注入 · 未选中的会话不产生额外消耗</span>
            </div>
          </template>
          <el-collapse v-if="panoSkills.length > 0">
            <el-collapse-item v-for="s in panoSkills" :key="s.id" :name="s.id">
              <template #title>
                <div class="card-head skill-head">
                  <b>{{ s.name }}</b>
                  <el-tag size="small" effect="plain" :type="s.minRoleLevel >= 1 ? 'warning' : 'info'">
                    {{ s.minRoleLevel >= 1 ? '发布人及以上' : '所有用户' }}
                  </el-tag>
                  <span class="head-note">{{ s.description }}</span>
                </div>
              </template>
              <pre class="sys-prompt">{{ s.body }}</pre>
            </el-collapse-item>
          </el-collapse>
          <el-empty v-else description="技能清单加载失败或为空——切换页签重试" :image-size="60" />
        </el-card>

        <!-- 系统提示词只读折叠（改提示词=后端发版或 config/prompts 覆写，页面只看不改）；
             批2 Q8：总纲升级为分段视图——每段=来源 md 文件+标题+内容，按拼装顺序排列 -->
        <el-card shadow="never" class="adm-card pano-prompt">
          <el-collapse>
            <el-collapse-item name="sys">
              <template #title>
                <div class="card-head">
                  <b>助手行为总纲</b>
                  <span class="head-note">系统提示词的分段展示（只读，随版本更新；共 {{ panoSegments.length }} 段按拼装顺序排列）</span>
                </div>
              </template>
              <template v-if="panoSegments.length > 0">
                <div v-for="seg in panoSegments" :key="seg.key" class="prompt-seg">
                  <div class="seg-head">
                    <b class="seg-title">{{ seg.title }}</b>
                    <span class="seg-file">prompts/{{ seg.file }}</span>
                    <el-tag size="small" effect="plain" :type="seg.source === 'external' ? 'warning' : 'info'">
                      {{ SOURCE_TXT[seg.source] ?? seg.source }}
                    </el-tag>
                  </div>
                  <pre class="sys-prompt">{{ seg.content }}</pre>
                </div>
              </template>
              <pre v-else class="sys-prompt">{{ panoSysPrompt || '加载中…' }}</pre>
            </el-collapse-item>
          </el-collapse>
        </el-card>
      </el-tab-pane>

      <!-- 页签三：错答反馈（perm=dcs:assistant:feedback:query；N 条待处理跳这）——方案A: 括号手拼计数 → el-badge 红色角标 -->
      <el-tab-pane v-if="checkPermi(['dcs:assistant:feedback:query'])" name="feedback">
        <template #label>错答反馈<el-badge v-if="fbTotal > 0" :value="fbTotal" class="tab-badge" /></template>
        <el-card v-if="fbStats" shadow="never" class="adm-card">
          <!-- AI 缺口批 Q10：反馈聚合——好评率大数字 + 点踩原因 CSS 横条 + 周趋势小卡 -->
          <div class="fb-stats">
            <div class="fb-rate">
              <div class="fb-rate-num" :class="fbRateTone">{{ fbStats.upRate == null ? '—' : `${fbStats.upRate}%` }}</div>
              <div class="fb-rate-lbl">好评率（近 {{ fbStats.windowDays }} 天）</div>
              <div class="fb-rate-sub">👍 {{ fbStats.up }} · 👎 {{ fbStats.down }}</div>
            </div>
            <div class="fb-reasons">
              <div class="fb-sec-title">点踩原因分布</div>
              <div v-for="r in fbStats.downReasons || []" :key="r.reason" class="fb-reason-row">
                <span class="fb-reason-name" :title="r.reason">{{ r.reason }}</span>
                <span class="fb-reason-bar"><i :style="{ width: reasonPct(r.cnt) + '%' }" /></span>
                <span class="fb-reason-cnt">{{ r.cnt }}</span>
              </div>
              <div v-if="!fbStats.downReasons?.length" class="fb-empty">近窗口还没有点踩</div>
            </div>
            <div class="fb-trend">
              <div class="fb-sec-title">近 {{ Math.ceil((fbStats.windowDays || 28) / 7) }} 周趋势</div>
              <div v-for="w in fbStats.trend || []" :key="w.week" class="fb-trend-row">
                <span class="fb-trend-week">{{ String(w.week).substring(5) }}</span>
                <span class="fb-trend-nums">👍{{ w.up }} 👎{{ w.down }}</span>
              </div>
              <div v-if="!fbStats.total" class="fb-empty">还没有样本</div>
            </div>
          </div>
        </el-card>
        <el-card shadow="never" class="adm-card">
          <el-table :data="fbRows" v-loading="fbLoading" size="small">
            <el-table-column label="评价" width="80">
              <template #default="{ row }">
                <el-tag :type="row.rating === 'UP' ? 'success' : 'danger'" size="small"
                  ><IconSpan :name="row.rating === 'UP' ? 'thumbs-up' : 'thumbs-down'" /> {{ row.rating === 'UP' ? '有用' : '错答' }}</el-tag
                >
              </template>
            </el-table-column>
            <el-table-column label="反馈说明" prop="note" show-overflow-tooltip />
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ fmtIsoTime(row.createTime) }}</template>
            </el-table-column>
          </el-table>
          <div v-if="fbTotal > fbRows.length" class="fb-more">
            <el-button link type="primary" @click="loadFeedback">加载更多（{{ fbRows.length }} / {{ fbTotal }}）</el-button>
          </div>
          <el-empty v-if="!fbLoading && fbRows.length === 0" description="还没有反馈——用户在回答尾部点「点踩」时会落到这里" :image-size="60" />
        </el-card>
      </el-tab-pane>
      <!-- 页签四：运行记录（二站① AU-52：动作账本只读，perm=config 与租户开关同源；2026-10-11 日常查询+标准分页） -->
      <el-tab-pane v-if="checkPermi(['dcs:assistant:config'])" label="运行记录" name="actions" lazy>
        <el-card shadow="never" class="adm-card">
          <!-- 查询栏（2026-10-11）：关键词模糊（动作/结果/操作人）+ 状态 + 时间范围 -->
          <div class="log-filter">
            <el-input
              v-model="logQuery.keyword"
              placeholder="动作 / 结果 / 操作人（模糊）"
              clearable
              style="width: 240px"
              @keyup.enter="onLogSearch"
              @clear="onLogSearch"
            />
            <el-select v-model="logQuery.status" clearable placeholder="状态" style="width: 130px" @change="onLogSearch">
              <el-option v-for="(st, key) in LOG_ST" :key="key" :label="st.label" :value="key" />
            </el-select>
            <el-date-picker
              v-model="logRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              style="width: 260px"
              @change="onLogSearch"
            />
            <el-button type="primary" :loading="logLoading" @click="onLogSearch">查询</el-button>
            <el-button @click="onLogReset">重置</el-button>
          </div>
          <el-table :data="logRows" v-loading="logLoading" size="small">
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ fmtIsoTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="动作" width="110">
              <template #default="{ row }">{{ ACT[row.toolName] ?? row.toolName }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="logSt(row.status).type" size="small">{{ logSt(row.status).label }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="结果" prop="resultSummary" show-overflow-tooltip>
              <template #default="{ row }">{{ row.resultSummary || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作人" prop="userName" width="120">
              <template #default="{ row }">{{ row.userName || '—' }}</template>
            </el-table-column>
          </el-table>
          <div class="log-pager">
            <el-pagination
              v-model:current-page="logQuery.pageNum"
              :page-size="logQuery.pageSize"
              :total="logTotal"
              layout="total, prev, pager, next, jumper"
              @current-change="loadActionLog"
            />
          </div>
          <el-empty
            v-if="!logLoading && logRows.length === 0"
            description="没有符合条件的运行记录——换个关键词或放宽时间范围试试；AI 替用户执行确认过的写操作（如发催办）会记到这里"
            :image-size="60"
          />
        </el-card>
      </el-tab-pane>

      <!-- 业务批3-③(#16)：用户额度——单人上限覆盖/重置今日额度/禁用；读走 config 权限，操作走 quota:edit -->
      <!-- UI-UX 批（2026-10-13，frontend-design+element-plus-aesthetic）：概览条+用量进度条+费用千分位 -->
      <el-tab-pane v-if="checkPermi(['dcs:assistant:config'])" label="用户额度" name="quota" lazy>
        <el-card shadow="never" class="adm-card">
          <!-- 概览条：左=租户默认口径（来自本页配置卡数据源）；右=本页速览（口径=当前页，不跨页误导） -->
          <div class="quota-overview">
            <div class="qo-item qo-tenant">
              <span class="qo-k">租户默认</span>
              <span class="qo-v">
                每人 {{ cfg.dailyRoundLimitPerUser }} 回合/天 · {{ cfg.websearchLimitPerUser ?? 20 }} 次联网/天
                <span class="qo-note">设过单人上限的不受此默认影响</span>
              </span>
              <!-- 业务批7 Q8：租户默认行内编辑（端点早已存在，此前缺页面入口）。
                   glm53 P2-2 回修：权限串与 PUT /config 端点对齐用 config（quota:edit 菜单
                   11935 授权 0 角色=对租户管理员不可达，仅超管可见） -->
              <el-popover v-if="checkPermi(['dcs:assistant:config'])" placement="bottom" :width="340" trigger="click">
                <template #reference>
                  <el-button size="small" text type="primary" class="qo-edit-btn">改默认</el-button>
                </template>
                <div class="qo-form">
                  <div class="qo-form-row" :class="{ 'qo-danger': defaultEdit.round === 0 }">
                    <span>每人每天回合数</span>
                    <el-input-number v-model="defaultEdit.round" :min="0" :max="10000" size="small" style="width: 132px" />
                  </div>
                  <div class="qo-form-row" :class="{ 'qo-danger': defaultEdit.web === 0 }">
                    <span>每人每天联网次数</span>
                    <el-input-number v-model="defaultEdit.web" :min="0" :max="10000" size="small" style="width: 132px" />
                  </div>
                  <!-- Q8 深化：动态提示——平时讲语义，从非 0 改成 0 时立刻升级为关停警示 -->
                  <div class="qo-form-tip" :class="{ 'qo-tip-danger': defaultShutting.any }">
                    <template v-if="defaultShutting.any"
                      >⚠ 保存后将立刻关停全租户的：{{
                        [defaultShutting.round ? '对话' : '', defaultShutting.web ? '联网' : ''].filter(Boolean).join(' 和 ')
                      }}（紧急刹车）</template
                    >
                    <template v-else>0 = 关停对应能力（紧急刹车）；保存后对全租户未设单人上限的人生效</template>
                  </div>
                  <!-- Q8 深化：订阅租户提示——Q1 起订阅期回合不限次，回合默认实际空转，不提示会造成"改了没用"的困惑 -->
                  <div v-if="subActive" class="qo-form-tip">ⓘ 本租户订阅生效中：回合数默认不参与限制（订阅期不限次），联网默认仍生效</div>
                  <!-- Q8 深化：生效预览（与单人上限弹窗 q-preview 同款语义，保存前看得见确切效果） -->
                  <div class="qo-preview">
                    <span class="q-preview-k">保存后生效</span>
                    <span class="q-preview-v">{{ defaultPreviewText }}</span>
                  </div>
                  <div class="qo-form-btns">
                    <el-button size="small" @click="resetDefaultEdit">还原</el-button>
                    <el-button size="small" type="primary" :loading="defaultSaving" @click="saveTenantDefaults">保存</el-button>
                  </div>
                </div>
              </el-popover>
            </div>
            <div class="qo-item">
              <span class="qo-k"><i class="qo-dot qo-red" />已用完</span>
              <span class="qo-v"
                ><b>{{ quotaPageStats.exhausted }}</b> 人 <span class="qo-note">本页</span></span
              >
            </div>
            <div class="qo-item">
              <span class="qo-k"><i class="qo-dot qo-orange" />快用完</span>
              <span class="qo-v"
                ><b>{{ quotaPageStats.near }}</b> 人 <span class="qo-note">本页 · ≥80%</span></span
              >
            </div>
            <div class="qo-item">
              <span class="qo-k"><i class="qo-dot qo-gray" />已禁用</span>
              <span class="qo-v"
                ><b>{{ quotaPageStats.disabled }}</b> 人 <span class="qo-note">本页</span></span
              >
            </div>
          </div>
          <div class="log-filter">
            <el-input
              v-model="quotaQuery.keyword"
              placeholder="姓名 / 账号（模糊）"
              clearable
              style="width: 220px"
              @keyup.enter="loadQuotaPage"
              @clear="loadQuotaPage"
            />
            <el-select v-model="quotaQuery.disabledOnly" clearable placeholder="状态" style="width: 130px" @change="loadQuotaPage">
              <el-option label="已禁用" value="1" />
            </el-select>
            <el-button type="primary" :loading="quotaLoading" @click="loadQuotaPage">查询</el-button>
            <span class="qo-total">共 {{ quotaTotal }} 人（全量）</span>
          </div>
          <el-table :data="quotaRows" v-loading="quotaLoading" stripe>
            <el-table-column label="用户" min-width="150">
              <template #default="{ row }">
                <span>{{ row.nickName || row.userName }}</span>
                <span class="quota-sub">{{ row.userName ? '@' + row.userName : '' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="部门" prop="deptName" width="130" show-overflow-tooltip />
            <el-table-column label="今日用量" width="170" align="center">
              <template #default="{ row }">
                <!-- UI-UX 批：比例可视化进度条——蓝(正常)→橙(≥80%)→红(用完)，一眼找出快用完的人 -->
                <div class="quota-turn-cell">
                  <div class="q-usage">
                    <div class="q-usage-bar">
                      <i :class="quotaBarTier(row)" :style="{ width: quotaBarPct(row) + '%' }" />
                    </div>
                    <span :class="{ 'quota-exhausted': row.effectiveRoundLimit > 0 && row.turnsUsedToday >= row.effectiveRoundLimit }">
                      {{ row.turnsUsedToday }} / {{ row.effectiveRoundLimit }}
                    </span>
                  </div>
                  <div v-if="row.roundLimitOverride != null || row.websearchLimitOverride != null" class="q-badges">
                    <el-tag
                      v-if="row.roundLimitOverride != null"
                      size="small"
                      type="warning"
                      class="quota-tag"
                      title="该用户单独设了回合上限，不受租户默认影响"
                      >单人{{ row.roundLimitOverride }}回合</el-tag
                    >
                    <el-tag
                      v-if="row.websearchLimitOverride != null"
                      size="small"
                      type="warning"
                      class="quota-tag"
                      title="该用户单独设了联网次数上限，不受租户默认影响"
                      >联网单人{{ row.websearchLimitOverride }}</el-tag
                    >
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="本月费用" width="110" align="right">
              <template #default="{ row }">¥{{ fmtMoney(row.monthCost) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="row.disabled === '1' ? 'danger' : 'success'" size="small">{{ row.disabled === '1' ? '已禁用' : '正常' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" align="center">
              <template #default="{ row }">
                <el-button
                  v-if="checkPermi(['dcs:assistant:quota:edit'])"
                  link
                  type="primary"
                  size="small"
                  :disabled="row.turnsUsedToday === 0"
                  :title="row.turnsUsedToday === 0 ? '今天还没用过，无需重置' : '把该用户今日已用回合清零'"
                  @click="onQuotaReset(row)"
                  >重置今日</el-button
                >
                <el-button v-if="checkPermi(['dcs:assistant:quota:edit'])" link type="primary" size="small" @click="openQuotaLimits(row)"
                  >限额</el-button
                >
                <el-button
                  v-if="checkPermi(['dcs:assistant:quota:edit'])"
                  link
                  :type="row.disabled === '1' ? 'success' : 'danger'"
                  size="small"
                  @click="onQuotaToggle(row)"
                  >{{ row.disabled === '1' ? '启用' : '禁用' }}</el-button
                >
              </template>
            </el-table-column>
            <template #empty>
              <el-empty
                description="没找到用户——换个关键词试试；用户额度默认跟随租户设置（每人每天各自的回合数），改过单人的会在这里显示"
                :image-size="60"
              />
            </template>
          </el-table>
          <div class="log-pager">
            <el-pagination
              v-model:current-page="quotaQuery.pageNum"
              :page-size="quotaQuery.pageSize"
              :total="quotaTotal"
              layout="total, prev, pager, next, jumper"
              @current-change="loadQuotaPage"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <!-- 批4 Q11：公告横幅——发布即生效（用户助手顶部横幅），撤回即消失；每用户新公告会重新弹出 -->
      <el-tab-pane v-if="checkPermi(['dcs:assistant:config'])" label="助手公告" name="announcement" lazy>
        <el-card shadow="never" class="adm-card">
          <div class="ann-form">
            <el-input
              v-model="annForm.title"
              placeholder="公告标题（必填，用户打开助手即可见）"
              maxlength="120"
              show-word-limit
              style="width: 320px"
            />
            <el-select v-model="annForm.style" style="width: 140px">
              <el-option label="普通（蓝色）" value="NORMAL" />
              <el-option label="维护（警示色）" value="MAINTENANCE" />
            </el-select>
            <el-input v-model="annForm.content" placeholder="正文可选（与标题同行显示，尽量简短）" maxlength="1000" style="flex: 1" />
            <el-button type="primary" :loading="annSaving" @click="onPublishAnn">{{ annForm.id ? '保存修改' : '发布公告' }}</el-button>
            <el-button v-if="annForm.id" @click="resetAnnForm">取消编辑</el-button>
          </div>
          <div class="field-hint">同一时刻只显示最新一条生效公告；编辑重发会刷新时间置顶，所有用户（含已关闭旧公告的）重新看到。</div>
          <el-table :data="annRows" v-loading="annLoading" size="small" style="margin-top: 10px">
            <el-table-column label="标题" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.title }}</span>
                <el-tag v-if="row.style === 'MAINTENANCE'" type="warning" size="small" effect="plain" class="ml-5px">维护</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="正文" prop="content" min-width="220" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === '1' ? 'success' : 'info'" size="small">{{ row.status === '1' ? '生效中' : '已撤回' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发布时间" width="160">
              <template #default="{ row }">{{ fmtIsoTime(row.publishTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="onEditAnn(row)">编辑</el-button>
                <el-button v-if="row.status === '1'" link type="danger" @click="onRevokeAnn(row)">撤回</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!annLoading && annRows.length === 0" description="还没有公告——发布后用户打开 AI 助手即见顶部横幅" :image-size="60" />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 批6(Q13): 能力覆盖弹窗——三模板(三档可调/仅开·关/不支持), 只影响本租户, 官方 seed 升级不冲掉 -->
    <el-dialog v-model="ovDialog" title="覆盖该模型的能力映射" width="560px" append-to-body class="ov-dialog">
      <el-alert type="info" :closable="false" show-icon class="ov-alert">
        <template #title>只影响本租户；「恢复官方」可随时回到官方目录。覆盖的是模型事实（有哪些档），用户选哪档仍由聊天输入条决定。</template>
      </el-alert>
      <el-form label-width="110px">
        <el-form-item label="模型">
          <el-input :model-value="ovForm.provider + ' · ' + ovForm.modelName" disabled />
        </el-form-item>
        <el-form-item label="能力类型">
          <el-radio-group v-model="ovForm.tierKind" @change="onTemplateChange">
            <el-radio value="TIERS">三档可调</el-radio>
            <el-radio value="SWITCH">仅开·关</el-radio>
            <el-radio value="NONE">不支持思考</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="ovForm.tierKind !== 'NONE'" label="原生档位">
          <el-select
            v-model="ovForm.tierValues"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入后回车添加档位"
            style="width: 100%"
          >
            <el-option v-for="t in ovForm.tierValues" :key="t" :label="t" :value="t" />
          </el-select>
          <div class="field-hint">该模型真实支持的原生档位（照官方文档填，如 low / high / max 或 enabled / disabled）</div>
        </el-form-item>
        <el-form-item v-if="ovForm.tierKind !== 'NONE'" label="语义档映射">
          <div class="sem-rows">
            <div v-for="s in SEM_KEYS" :key="s.key" class="sem-row">
              <span class="sem-name">{{ s.label }}</span>
              <el-select v-model="ovForm.semanticMap[s.key]" clearable placeholder="不可用" style="width: 180px">
                <el-option v-for="t in ovForm.tierValues" :key="t" :label="t" :value="t" />
              </el-select>
            </div>
          </div>
          <div class="field-hint">用户看到的「快速 / 平衡 / 深入」分别落到哪个原生档；留空=输入条不显示该档</div>
        </el-form-item>
        <!-- 业务批3-①(Q2A)：支持输入三勾选框——文本恒支持固定展示不可取消（ZCode 高级设置同款口径） -->
        <el-form-item label="支持输入">
          <div class="supports-row">
            <el-tag type="info" effect="plain" size="small" class="text-always">文本锁定</el-tag>
            <el-checkbox v-model="ovForm.supports.image">图片</el-checkbox>
            <el-checkbox v-model="ovForm.supports.video">视频</el-checkbox>
            <el-checkbox v-model="ovForm.supports.audio">音频</el-checkbox>
          </div>
          <div class="field-hint">当前生效：{{ capSupportsText }}；保存后按勾选记录（仅本租户），「恢复官方」可回到目录值</div>
        </el-form-item>
        <el-form-item label="上下文窗口">
          <el-input-number
            v-model="ovForm.contextWindow"
            :min="1000"
            :step="1000"
            :controls="false"
            placeholder="留空=按兜底 200K"
            style="width: 180px"
          />
          <span class="unit-note">token</span>
        </el-form-item>
        <el-form-item label="最大输出">
          <el-input-number
            v-model="ovForm.maxOutputTokens"
            :min="256"
            :max="131072"
            :step="1024"
            :controls="false"
            placeholder="留空=按模型默认"
            style="width: 180px"
          />
          <span class="unit-note">token / 次</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ovDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingModel" @click="onSaveOverride">保存覆盖</el-button>
      </template>
    </el-dialog>

    <!-- 业务批3-③(#16)+批④(#17)：单人限额覆盖弹窗——两列独立「留空=跟随租户默认」 -->
    <el-dialog v-model="quotaLimitsDialog" title="设置单人额度上限" width="500px" append-to-body class="q-dialog">
      <el-alert type="info" :closable="false" show-icon class="ov-alert">
        <template #title>每列留空 = 该项跟随租户默认；填数字 = 只对这一个人生效（两列互不影响）</template>
      </el-alert>
      <el-form label-width="120px">
        <el-form-item label="用户">
          <el-input :model-value="quotaLimitsUser" disabled />
        </el-form-item>
        <el-form-item label="每日回合上限">
          <el-input-number
            v-model="quotaLimitsForm.roundLimit"
            :min="1"
            :max="10000"
            :disabled="quotaLimitsFollowDefault"
            :placeholder="`默认 ${tenantRoundDefault}`"
            style="width: 200px"
          />
          <span class="unit-note">回合 / 天</span>
        </el-form-item>
        <el-form-item label="每日联网次数">
          <el-input-number
            v-model="quotaLimitsForm.websearchLimit"
            :min="1"
            :max="10000"
            :disabled="quotaLimitsFollowDefault"
            :placeholder="`默认 ${tenantWebDefault}`"
            style="width: 200px"
          />
          <span class="unit-note">次 / 天</span>
        </el-form-item>
        <el-form-item label=" " label-width="120px">
          <el-checkbox v-model="quotaLimitsFollowDefault">两项都跟随租户默认（清空单人覆盖）</el-checkbox>
        </el-form-item>
        <!-- UI-UX 批：生效预览提级为醒目块——保存前看得见保存后的确切效果（灰底小字易被忽略） -->
        <div class="q-preview">
          <span class="q-preview-k">保存后该用户生效</span>
          <span class="q-preview-v">{{ quotaLimitsCurrentText }}</span>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="quotaLimitsDialog = false">取 消</el-button>
        <el-button type="primary" :loading="quotaSaving" @click="onSaveQuotaLimits">保存</el-button>
      </template>
    </el-dialog>

    <!-- 业务反馈批1015b Q14：替换搜索密钥——先测试后保存（保存走系统配置 API，缓存自动刷新） -->
    <el-dialog v-model="wsReplace.open" title="替换联网搜索密钥（博查）" width="520px" append-to-body>
      <el-alert type="info" :closable="false" show-icon class="mb-2" title="密钥以 sk- 开头；保存后立即生效，正在进行的对话下一轮就能联网。" />
      <el-form label-width="90px">
        <el-form-item label="新密钥">
          <el-input v-model="wsReplace.key" type="password" show-password placeholder="粘贴博查后台的 API Key（sk-...）" />
        </el-form-item>
      </el-form>
      <div v-if="wsReplace.testMsg" class="cell-note" :class="{ 'ws-fail': !wsReplace.testOk }">{{ wsReplace.testMsg }}</div>
      <template #footer>
        <el-button :loading="wsReplace.testing" @click="onWsTestCandidate">先测试一下</el-button>
        <el-button @click="wsReplace.open = false">取 消</el-button>
        <el-button type="primary" :loading="wsReplace.saving" :disabled="!wsReplace.key.trim().startsWith('sk-')" @click="onWsSaveKey"
          >保 存</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * AI 助手管理端页（原型屏E，批F）。后端端点全就绪：GET/PUT /dcs/assistant/config
 * （toggle 走 PUT，null 字段=保留现值）+ feedback/list（feedback:query）+ tenant-wallet
 * + action/log（二站① AU-52 运行记录只读页签，perm=config 同源）。
 * 知识包页签=链路未建（真需求出现再立项）；运行记录已上线。
 * 权限=dcs:assistant:config（菜单 11927 同源）。
 */
import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { checkPermi } from '@/utils/permission';
import {
  getAssistantConfig,
  getAssistantStatus,
  getAssistantAdminStats,
  getAssistantPanorama,
  listActionLog,
  listFeedback,
  getFeedbackStats,
  listAssistantAnnouncements,
  publishAssistantAnnouncement,
  revokeAssistantAnnouncement,
  updateAssistantConfig,
  pageAssistantQuota,
  resetAssistantQuota,
  updateAssistantQuotaLimits,
  setAssistantQuotaDisabled,
  type AssistantActionLogRow,
  type AssistantAnnouncementRow,
  type AssistantEffectiveConfig,
  type AssistantPanoramaTool,
  type AssistantPanoramaSkill,
  type AssistantQuotaRow,
  getWebsearchInfo,
  testWebsearch
} from '@/api/dcs/assistant';
import { listTenantWallet } from '@/api/dcs/tenant-wallet';
import { addConfig, listConfig, updateConfig } from '@/api/system/config';
// 业务批4 Q14-A/Q15-B：厂商真标复用 AI 模型配置页组件（未收录厂商降级字母徽章）
import ProviderLogo from '@/views/dcs/ai-config/ProviderLogo.vue';
import {
  getAssistantModelOptions,
  getAssistantModelSetting,
  matchAiCapability,
  overrideAiCapability,
  resetAiCapability,
  saveAssistantModelSettingPost,
  testDcsAiConfig,
  type DcsAiConfigVO
} from '@/api/dcs/ai';
import { useUserStore } from '@/store/modules/user';
import IconSpan from '@/components/DcsAssistant/IconSpan.vue';
import { ACT, LOG_ST, logSt } from '@/components/DcsAssistant/useAssistant';
import { fmtIsoTime } from '@/components/DcsAssistant/util';
import { applyAssistantName } from '@/components/DcsAssistant/nameState';

const userStore = useUserStore();
const router = useRouter();

const tab = ref('config');
const loading = ref(false);
const saving = ref(false);
const cfg = ref<AssistantEffectiveConfig>({
  rowPresent: false,
  enabled: false,
  dailyRoundLimitPerUser: 50,
  dailyBudgetAmount: '20',
  memoryEnabled: '0',
  compactionTriggerTokens: 0,
  digestEnabled: '0'
});
const walletTxt = ref('—');

/* ===== 业务批7 Q8：租户默认值行内编辑（概览条「改默认」弹层；PUT /config 部分提交语义安全）。
   深化设计（重中之重，2026-10-14 拍板）：① 0=关停行内标红+动态警示；② 从非 0 改 0 保存前二次确认；
   ③ 保存后生效预览（与单人上限弹窗同款语义）；④ 订阅租户提示（Q1 起订阅期回合不限次，默认空转） ===== */
const defaultEdit = reactive({ round: 50, web: 20 });
const defaultSaving = ref(false);
/** 订阅生效中（billingMode=credits）：回合默认空转，仅提示不阻拦（管理员可能为订阅到期提前配好） */
const subActive = ref(false);
/** 消费方式（用户反馈1014：原「服务码价目」死文案改动态）——credits=积分订阅 / wallet=钱包按量 */
const billingMode = ref<'credits' | 'wallet'>('wallet');
watch(
  cfg,
  (c) => {
    if (c) {
      defaultEdit.round = c.dailyRoundLimitPerUser ?? 50;
      defaultEdit.web = c.websearchLimitPerUser ?? 20;
    }
  },
  { immediate: true }
);
function resetDefaultEdit() {
  defaultEdit.round = cfg.value?.dailyRoundLimitPerUser ?? 50;
  defaultEdit.web = cfg.value?.websearchLimitPerUser ?? 20;
}
/** 保存后生效预览：0 直译"关停"，不说"0 回合"这种没人懂的话 */
const defaultPreviewText = computed(() => {
  const rt = defaultEdit.round > 0 ? `${defaultEdit.round} 回合/天` : '对话关停';
  const wt = defaultEdit.web > 0 ? `${defaultEdit.web} 次联网/天` : '联网关停';
  return `每人 ${rt} · ${wt}`;
});
/** 关停检测：当前值非 0 且编辑值为 0（=本次操作将触发紧急刹车） */
const defaultShutting = computed(() => {
  const r0 = (cfg.value?.dailyRoundLimitPerUser ?? 50) > 0 && defaultEdit.round === 0;
  const w0 = (cfg.value?.websearchLimitPerUser ?? 20) > 0 && defaultEdit.web === 0;
  return { round: r0, web: w0, any: r0 || w0 };
});
async function saveTenantDefaults() {
  if (defaultShutting.value.any) {
    try {
      await ElMessageBox.confirm(
        `把默认值设为 0 会立刻停掉全租户的${defaultShutting.value.round && defaultShutting.value.web ? '对话和联网' : defaultShutting.value.round ? '对话' : '联网'}，确定保存？`,
        '关停确认',
        { type: 'warning', confirmButtonText: '确认关停', cancelButtonText: '再想想' }
      );
    } catch {
      return;
    }
  }
  defaultSaving.value = true;
  try {
    await updateAssistantConfig({ dailyRoundLimitPerUser: defaultEdit.round, websearchLimitPerUser: defaultEdit.web });
    ElMessage.success('租户默认已保存（未设单人上限的用户即刻生效）');
    await loadConfig();
  } catch {
    ElMessage.error('保存失败，请稍后再试');
  } finally {
    defaultSaving.value = false;
  }
}

async function loadConfig() {
  loading.value = true;
  try {
    const res = (await getAssistantConfig()) as unknown as { data?: { effective?: AssistantEffectiveConfig } };
    const e = res?.data?.effective ?? (res as { effective?: AssistantEffectiveConfig })?.effective;
    if (e) {
      cfg.value = e;
      // 快刀批 Q6：称呼同步喂全局态（本页保存后悬浮球/抽屉即时换名，无需整页刷新）
      applyAssistantName(e.assistantName);
    }
  } catch {
    ElMessage.error('配置加载失败，请稍后再试');
  } finally {
    loading.value = false;
  }
  // Q8 深化④：订阅态随配置一起刷（status 端点登录即可，失败静默=按非订阅展示）
  try {
    const s = (await getAssistantStatus()) as unknown as { data?: { billingMode?: string }; billingMode?: string };
    billingMode.value = (s?.data ?? s)?.billingMode === 'credits' ? 'credits' : 'wallet';
    subActive.value = billingMode.value === 'credits';
  } catch {
    billingMode.value = 'wallet';
    subActive.value = false;
  }
  // 钱包余额（独立容错：无钱包权限显示 —；list 是管理视角全租户，按当前租户+MAIN 过滤）
  try {
    const w = (await listTenantWallet()) as unknown as {
      rows?: Array<{ tenantId: string; walletType: string; availableAmount: number; currency: string }>;
      data?: Array<{ tenantId: string; walletType: string; availableAmount: number; currency: string }>;
    };
    const list = w?.rows ?? w?.data ?? [];
    const main = list.find((x) => x.tenantId === userStore.tenantId && x.walletType === 'MAIN') ?? list.find((x) => x.walletType === 'MAIN');
    if (main) {
      walletTxt.value = `可用 ¥ ${Number(main.availableAmount).toFixed(2)}`;
    }
  } catch {
    /* 钱包不可达/无权限保持 — */
  }
}

/** 停用走二次确认（原型 openDisable：开关直开免确认，关闭必须确认） */
function beforeToggle(): Promise<boolean> {
  if (cfg.value.enabled) {
    // 当前开→即将关：拦截给确认框
    return ElMessageBox.confirm('停用后本租户立即看不到悬浮球，AI 请求直接拒绝；已批代办按账本处理。确定停用？', '停用 AI 助手', {
      type: 'warning',
      confirmButtonText: '确定停用',
      cancelButtonText: '再想想'
    })
      .then(() => true)
      .catch(() => false);
  }
  return Promise.resolve(true);
}
async function onToggle() {
  saving.value = true;
  try {
    // enabled 写侧是码值串 '1'/'0'（AU-18：entity String 码值，发 boolean 会被后端静默判关）
    await updateAssistantConfig({ enabled: cfg.value.enabled ? '1' : '0' });
    ElMessage.success(cfg.value.enabled ? '已开通' : '已停用');
  } catch {
    ElMessage.error('保存失败，请稍后再试');
    cfg.value.enabled = !cfg.value.enabled;
  } finally {
    saving.value = false;
  }
}
/** 「停用助手…」行内按钮（批H P2-6/dspro P1-6）：与开关同款二次确认，不得绕过直停 */
async function openDisable() {
  const ok = await beforeToggle();
  if (!ok) {
    return;
  }
  cfg.value.enabled = false;
  void onToggle();
}

/* ===== 长期记忆开关（二站④ AU-53）：码值串 '1'/'0' 同 enabled 口径 ===== */
const memoryOn = computed({
  get: () => cfg.value.memoryEnabled === '1',
  set: (v: boolean) => {
    cfg.value.memoryEnabled = v ? '1' : '0';
  }
});
async function onMemoryToggle() {
  saving.value = true;
  try {
    await updateAssistantConfig({ memoryEnabled: cfg.value.memoryEnabled });
    ElMessage.success(memoryOn.value ? '长期记忆已开启——新会话即生效（进行中的旧会话需重开）' : '长期记忆已关闭');
  } catch {
    ElMessage.error('保存失败，请稍后再试');
    cfg.value.memoryEnabled = memoryOn.value ? '0' : '1';
  } finally {
    saving.value = false;
  }
}

/* ===== 批17 Q2：自动压缩阈值（change 即存；失败回滚旧值） ===== */
const compactionTokens = computed({
  get: () => cfg.value.compactionTriggerTokens ?? 60000,
  set: (v: number | undefined) => {
    cfg.value.compactionTriggerTokens = v ?? 60000;
  }
});
async function onCompactionSave(_v: number | undefined, oldVal: number | undefined) {
  saving.value = true;
  try {
    await updateAssistantConfig({ compactionTriggerTokens: cfg.value.compactionTriggerTokens });
    ElMessage.success('压缩阈值已保存——新会话起按新阈值自动打包');
  } catch {
    ElMessage.error('保存失败，请稍后再试');
    cfg.value.compactionTriggerTokens = oldVal ?? 60000;
  } finally {
    saving.value = false;
  }
}

/* ===== 每日摘要开关（五需求批 R4b / Q7-C）：码值串 '1'/'0' 同 enabled 口径 ===== */
const digestOn = computed({
  get: () => cfg.value.digestEnabled === '1',
  set: (v: boolean) => {
    cfg.value.digestEnabled = v ? '1' : '0';
  }
});
async function onDigestToggle() {
  saving.value = true;
  try {
    await updateAssistantConfig({ digestEnabled: cfg.value.digestEnabled === '1' ? '1' : '0' });
    ElMessage.success(digestOn.value ? '每日摘要已开启——明早 08:00 起给发布人推送未交摘要' : '每日摘要已关闭');
  } catch {
    ElMessage.error('保存失败，请稍后再试');
    cfg.value.digestEnabled = digestOn.value ? '0' : '1';
  } finally {
    saving.value = false;
  }
}

/* ===== 助手称呼（快刀批 Q6）：改完回车/失焦生效，清空=回落平台默认 ===== */
const nameEdit = ref('');
watch(
  () => cfg.value.assistantName,
  (v) => {
    nameEdit.value = v ?? '';
  },
  { immediate: true }
);
async function onNameSave() {
  saving.value = true;
  try {
    const n = nameEdit.value.trim();
    await updateAssistantConfig({ assistantName: n });
    await loadConfig();
    ElMessage.success(n ? `助手称呼已改为「${n}」——用户下次进入页面生效` : '已恢复默认「SmartDock 小助手」');
  } catch {
    ElMessage.error('称呼保存失败，请稍后再试');
  } finally {
    saving.value = false;
  }
}

/* ===== 错答反馈页签 ===== */
const fbRows = ref<Array<{ rating: string; note?: string; createTime?: string }>>([]);
const fbTotal = ref(0);
const fbLoading = ref(false);
// AI 缺口批 Q10：反馈聚合统计（好评率/原因分布/周趋势）
const fbStats = ref<any>(null);
const fbRateTone = computed(() => {
  const r = fbStats.value?.upRate;
  if (r == null) return '';
  return Number(r) >= 80 ? 'good' : Number(r) >= 50 ? 'mid' : 'bad';
});
const reasonPct = (cnt: number) => {
  const list: any[] = fbStats.value?.downReasons || [];
  const max = Math.max(...list.map((r) => Number(r.cnt) || 0), 1);
  return Math.round((Number(cnt) / max) * 100);
};
async function loadFbStats() {
  try {
    const res: any = await getFeedbackStats(28);
    fbStats.value = res?.data || null;
  } catch {
    fbStats.value = null;
  }
}
async function loadFeedback() {
  if (fbRows.value.length === 0) {
    void loadFbStats();
  }
  fbLoading.value = true;
  try {
    const res = (await listFeedback({ pageNum: Math.floor(fbRows.value.length / 20) + 1, pageSize: 20 })) as unknown as {
      rows?: Array<{ rating: string; note?: string; createTime?: string }>;
      total?: number;
    };
    fbRows.value = [...fbRows.value, ...(res?.rows ?? [])];
    fbTotal.value = res?.total ?? fbRows.value.length;
  } catch {
    ElMessage.error('反馈加载失败，请稍后再试');
  } finally {
    fbLoading.value = false;
  }
}

/* ===== 助手公告页签（批4 Q11）：发布/编辑/撤回，发布即生效；首开加载挂在 onTabChange ===== */
const annRows = ref<AssistantAnnouncementRow[]>([]);
const annLoading = ref(false);
const annSaving = ref(false);
const annForm = reactive<{ id?: string; title: string; content: string; style: string }>({
  id: undefined,
  title: '',
  content: '',
  style: 'NORMAL'
});
function resetAnnForm() {
  annForm.id = undefined;
  annForm.title = '';
  annForm.content = '';
  annForm.style = 'NORMAL';
}
async function loadAnnouncements() {
  annLoading.value = true;
  try {
    const res = (await listAssistantAnnouncements()) as unknown as { rows?: AssistantAnnouncementRow[] };
    annRows.value = res?.rows ?? [];
  } catch {
    ElMessage.error('公告加载失败，请稍后再试');
  } finally {
    annLoading.value = false;
  }
}
async function onPublishAnn() {
  if (!annForm.title.trim()) {
    ElMessage.warning('公告标题必填');
    return;
  }
  annSaving.value = true;
  try {
    await publishAssistantAnnouncement({
      id: annForm.id,
      title: annForm.title.trim(),
      content: annForm.content.trim() || undefined,
      style: annForm.style
    });
    ElMessage.success(annForm.id ? '公告已更新（用户重开助手可见新内容）' : '公告已发布（用户打开助手即见顶部横幅）');
    resetAnnForm();
    loadAnnouncements();
  } catch {
    ElMessage.error('发布失败，请稍后再试');
  } finally {
    annSaving.value = false;
  }
}
function onEditAnn(row: AssistantAnnouncementRow) {
  annForm.id = row.id;
  annForm.title = row.title;
  annForm.content = row.content ?? '';
  annForm.style = row.style ?? 'NORMAL';
}
async function onRevokeAnn(row: AssistantAnnouncementRow) {
  try {
    await ElMessageBox.confirm(`撤回「${row.title}」？用户助手的横幅将立即消失。`, '撤回公告', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await revokeAssistantAnnouncement(row.id);
    ElMessage.success('公告已撤回');
    loadAnnouncements();
  } catch {
    ElMessage.error('撤回失败，请稍后再试');
  }
}
/* ===== 运行记录页签（二站① AU-52；2026-10-11 日常查询+标准分页） ===== */ const logRows = ref<AssistantActionLogRow[]>([]);
const logTotal = ref(0);
const logLoading = ref(false);
const logQuery = reactive({ pageNum: 1, pageSize: 20, keyword: '', status: '', beginTime: '', endTime: '' });
const logRange = ref<[string, string] | null>(null);
// logSt/LOG_ST 批I 收口到 useAssistant 与会话页账本共用（单一真源）
async function loadActionLog() {
  logLoading.value = true;
  try {
    const res = (await listActionLog({
      pageNum: logQuery.pageNum,
      pageSize: logQuery.pageSize,
      keyword: logQuery.keyword || undefined,
      status: logQuery.status || undefined,
      beginTime: logQuery.beginTime || undefined,
      endTime: logQuery.endTime || undefined
    })) as unknown as {
      rows?: AssistantActionLogRow[];
      total?: number;
    };
    logRows.value = res?.rows ?? [];
    logTotal.value = res?.total ?? 0;
  } catch {
    ElMessage.error('运行记录加载失败，请稍后再试');
  } finally {
    logLoading.value = false;
  }
}
/** 查询：daterange → 起止闭区间（当天 00:00:00 ~ 23:59:59），回到第 1 页 */
function onLogSearch() {
  logQuery.beginTime = logRange.value?.[0] ? logRange.value[0] + ' 00:00:00' : '';
  logQuery.endTime = logRange.value?.[1] ? logRange.value[1] + ' 23:59:59' : '';
  logQuery.pageNum = 1;
  loadActionLog();
}
function onLogReset() {
  logQuery.keyword = '';
  logQuery.status = '';
  logRange.value = null;
  onLogSearch();
}

/* ===== 模型选择卡（批6，Q5/Q13）：助手默认+能力只读带出+自选开关 ===== */
interface CapVo {
  tierKind: 'TIERS' | 'SWITCH' | 'NONE';
  tierValues: string[];
  semanticMap: Record<string, string>;
  contextWindow: number | null;
  /** 批C C2：最大输出 + 输入类型（目录透出） */
  maxOutputTokens?: number | null;
  supportsImage?: boolean;
  supportsVideo?: boolean;
  supportsAudio?: boolean;
  source: 'SEED' | 'MANUAL' | 'FALLBACK';
  matchedPattern?: string | null;
}
const SEM_KEYS = [
  { key: 'FAST', label: '快速' },
  { key: 'BALANCED', label: '平衡' },
  { key: 'DEEP', label: '深入' }
] as const;

const modelLoading = ref(false);
const savingModel = ref(false);
const modelOptions = ref<DcsAiConfigVO[]>([]);
/** 批B(Q3:A) 按厂商分组（ZCode 同款）：同厂多模型归堆；★已由后端排最前，组内保持原序 */
const groupedModelOptions = computed(() => {
  const groups: Array<{ provider: string; label: string; options: DcsAiConfigVO[] }> = [];
  for (const o of modelOptions.value) {
    const provider = String(o.provider || '');
    let g = groups.find((x) => x.provider === provider);
    if (!g) {
      g = { provider, label: o.providerName || provider || '其他', options: [] };
      groups.push(g);
    }
    g.options.push(o);
  }
  return groups;
});
const modelForm = reactive({
  assistantDefaultConfigId: undefined as number | string | undefined
});
/** 助手默认最近一次保存值（三审 m3-P0-1）：onDefaultChange 失败必须回滚下拉——否则本地
 *  残留的未保存值会被后续保存当 payload 一并发送，助手默认被幽灵落库。
 *  批2 Q4（2026-10-16）：后台默认槽整体搬进画像设置，backendDefaultConfigId/
 *  lastSavedBackend 及 onBackendDefaultChange 已随行移除 */
const lastSavedAssistant = ref<number | string | undefined>(undefined);

/**
 * 业务批4 Q15-B（2026-10-13 拍板）：开放表按厂商「首见聚堆」——维持后端相对次序（★默认最前→默认厂商自然排头），
 * 仅把同厂行挪到一起；每厂前插一行组头哨兵（__group）横跨全表，数据行结构不变（勾选/单价照常）。
 */
type ScopeRow = (DcsAiConfigVO & { __group?: true }) | { __group: true; provider: string; label: string; count: number };
const scopeGroupedRows = computed<ScopeRow[]>(() => {
  const out: ScopeRow[] = [];
  const byProvider = new Map<string, DcsAiConfigVO[]>();
  for (const o of modelOptions.value) {
    const provider = String(o.provider || '');
    let g = byProvider.get(provider);
    if (!g) {
      g = [];
      byProvider.set(provider, g);
    }
    g.push(o);
  }
  for (const [provider, rows] of byProvider) {
    out.push({ __group: true, provider, label: rows[0]?.providerName || provider || '其他', count: rows.length });
    out.push(...rows);
  }
  return out;
});

/** 组头行整行横跨 4 列（勾选/模型/单价/用户侧），其余列格子隐藏；数据行返回 undefined 走默认 [1,1] */
function scopeSpanMethod({ row, columnIndex }: { row: ScopeRow; columnIndex: number }): [number, number] | undefined {
  if (row.__group) {
    return columnIndex === 0 ? [1, 4] : [0, 0];
  }
  return undefined;
}

/** 组头行样式钩子：压掉悬停高亮（管理表语义=分组标题不是可交互行） */
function scopeRowClass({ row }: { row: ScopeRow }): string {
  return row.__group ? 'grp-row' : '';
}

const capability = ref<CapVo | null>(null);
const testLoading = ref(false);
const testResult = ref<{ success: boolean; latencyMs?: number } | null>(null);
const ovDialog = ref(false);
const ovForm = reactive({
  provider: '',
  modelName: '',
  tierKind: 'SWITCH' as 'TIERS' | 'SWITCH' | 'NONE',
  tierValues: [] as string[],
  semanticMap: {} as Record<string, string | undefined>,
  contextWindow: undefined as number | undefined,
  maxOutputTokens: undefined as number | undefined,
  /** 业务批3-①(Q2A)：支持输入三勾选（回显当前生效值，保存时恒定提交——见下方 P1 回修注释） */
  supports: { image: false, video: false, audio: false }
});
/** 三席审查 P1 回修（2026-10-12）：支持输入三勾选恒定提交当前回显值——回显=当前生效值
 *  （MANUAL 优先、无 MANUAL 时=SEED 值），所见即所存；不再做"未碰勾选=不发字段"的条件提交，
 *  否则租户显式覆盖会在下一次只改其它字段的保存中被 SEED 值静默重算 */
/** 当前生效输入类型一行话（能力卡与覆盖弹窗提示共用同一口径：文本恒支持，无附加项=仅文本） */
const capSupportsText = computed(() => {
  const cap = capability.value;
  const extras = [cap?.supportsImage ? '图片' : '', cap?.supportsVideo ? '视频' : '', cap?.supportsAudio ? '音频' : ''].filter(Boolean);
  return extras.length ? ['文本', ...extras].join(' + ') : '仅文本';
});

const allowSwitchOn = computed({
  get: () => settingAllowSwitch.value === '1',
  set: (v: boolean) => {
    settingAllowSwitch.value = v ? '1' : '0';
  }
});
const settingAllowSwitch = ref('0');

/** 当前选中行（options 里找 configId）——String() 归一比较（批8-3 回修 glm53 P2-6：
 *  19 位雪花经 BigNumberSerializer 转字符串、16 位保持 number，严格 === 端点异构即 miss） */
const currentRow = computed(() => modelOptions.value.find((o) => String(o.configId) === String(modelForm.assistantDefaultConfigId)));

const capSourceTag = computed(() => {
  const s = capability.value?.source;
  if (s === 'MANUAL') return { type: 'warning' as const, label: '本租户已覆盖' };
  if (s === 'SEED') return { type: 'success' as const, label: '官方目录' };
  return { type: 'info' as const, label: '目录兜底' };
});

/** 思考档位一行话：三语义档→原生档（Q13 双层档位） */
const semanticText = computed(() => {
  const cap = capability.value;
  if (!cap) return '—';
  if (cap.tierKind === 'NONE') return '不支持思考——输入条不显示思考档';
  const parts = SEM_KEYS.map((s) => {
    const to = cap.semanticMap?.[s.key];
    return `${s.label}→${to || '（不可用）'}`;
  });
  const native = cap.tierValues?.length ? `（原生档位：${cap.tierValues.join(' / ')}）` : '';
  return parts.join(' · ') + native;
});

const currentPriceText = computed(() => {
  const row = currentRow.value;
  if (!row) return '—';
  if (row.billingMode === 'FIXED_PER_CALL') {
    return row.unitPricePerCall != null ? `¥${Number(row.unitPricePerCall).toFixed(4)}/次` : '按次计费';
  }
  // 业务批4 Q4-A：官方价＋服务费=官方按量价＋每次服务费双段（回修 glm53 P2-5：币种符跟价格
  // 目录走——USD 行显示 $，与 optionPrice 下拉同口径，不硬编码 ¥ 误导对账）
  if (row.billingMode === 'OFFICIAL_PLUS_FEE') {
    const sym = row.currency === 'USD' ? '$' : '¥';
    const inp = row.inputPricePerMillion != null ? `${sym}${Number(row.inputPricePerMillion).toFixed(2)}/百万` : null;
    const fee = row.unitPricePerCall != null ? `${sym}${Number(row.unitPricePerCall).toFixed(4)}/次服务费` : null;
    if (inp && fee) return `${inp} ＋ ${fee}（实扣=两者相加）`;
    if (fee) return `${fee}（官方价未录）`;
    return '官方价＋服务费';
  }
  const inp = row.inputPricePerMillion != null ? Number(row.inputPricePerMillion).toFixed(2) : null;
  const out = row.outputPricePerMillion != null ? Number(row.outputPricePerMillion).toFixed(2) : null;
  if (inp == null && out == null) return '价格未配置';
  return `¥${inp ?? '—'} / ¥${out ?? '—'} 每百万 token（输入 / 输出）`;
});

/** 窗口数→人话（200000→200K, 1000000→1M） */
function fmtCap(n: number): string {
  if (n >= 1_000_000 && n % 1_000_000 === 0) return `${n / 1_000_000}M`;
  if (n >= 1000) return `${Math.round(n / 1000)}K`;
  return String(n);
}

function optionLabel(o: DcsAiConfigVO): string {
  // 方案A 2026-10-11：★ 星号从名称里摘除——默认模型改用下拉行内的「默认」tag 标注（业内惯例）
  return o.modelName;
}
function optionPrice(o: DcsAiConfigVO): string {
  // 超级 E2E 2026-10-10 修复：符号跟价格目录币种走——USD 行显示 $ 而非误导的 ¥
  const sym = o.currency === 'USD' ? '$' : '¥';
  // 业务批4 Q4-A：官方价＋服务费=双段展示（官方按量价＋每次服务费；回修 dspro P2-8：未设时
  // 如实显示缺省 ¥0.01——后端 resolveUnitPrice 按缺省收，不能宣称「未设」让管理员误以为不收）
  if (o.billingMode === 'OFFICIAL_PLUS_FEE') {
    const fee = o.unitPricePerCall != null ? `${sym}${Number(o.unitPricePerCall).toFixed(4)}/次服务费` : `${sym}0.0100/次服务费（缺省）`;
    const base = o.inputPricePerMillion != null ? `${sym}${Number(o.inputPricePerMillion).toFixed(2)}/百万` : '';
    return base ? `${base}＋${fee}` : fee;
  }
  if (o.billingMode === 'FIXED_PER_CALL') return o.unitPricePerCall != null ? `${sym}${Number(o.unitPricePerCall).toFixed(4)}/次` : '按次计费';
  if (o.inputPricePerMillion == null) return '价格未配置';
  return `${sym}${Number(o.inputPricePerMillion).toFixed(2)}/百万`;
}

async function loadModelSection() {
  modelLoading.value = true;
  try {
    const [s, o] = await Promise.all([
      getAssistantModelSetting() as unknown as Promise<{
        data?: {
          assistantDefaultConfigId?: number | string | null;
          backendDefaultConfigId?: number | string | null;
          allowUserSwitch?: string;
          switchScope?: string | null;
        };
      }>,
      getAssistantModelOptions() as unknown as Promise<{ data?: DcsAiConfigVO[] }>
    ]);
    modelOptions.value = o?.data ?? [];
    const sv = s?.data;
    modelForm.assistantDefaultConfigId = sv?.assistantDefaultConfigId ?? undefined;
    lastSavedAssistant.value = modelForm.assistantDefaultConfigId;
    settingAllowSwitch.value = sv?.allowUserSwitch ?? '0';
    // 业务批4 Q3：开放表勾选集随设置行恢复（switch_scope JSON 数组串）
    parseScope(sv?.switchScope);
    await refreshCapability();
  } catch {
    ElMessage.error('模型设置加载失败，请稍后再试');
  } finally {
    modelLoading.value = false;
  }
}

/** 能力只读带出：按当前选中行的 provider/modelName 查目录 */
async function refreshCapability() {
  const row = currentRow.value;
  capability.value = null;
  testResult.value = null;
  if (!row?.provider || !row.modelName) return;
  try {
    const res = (await matchAiCapability({ provider: String(row.provider), model: row.modelName })) as unknown as { data?: CapVo };
    capability.value = res?.data ?? null;
  } catch {
    /* 能力目录不可达保持空卡, 不阻断设置 */
  }
}

async function onDefaultChange() {
  const id = modelForm.assistantDefaultConfigId;
  if (!id) return;
  if (savingModel.value) {
    // 在飞时改动（glm53 P2）：v-model 已写本地，静默 return=界面与库不一致，回滚+提示
    modelForm.assistantDefaultConfigId = lastSavedAssistant.value;
    ElMessage.info('上一次保存还在进行中，本次改动已还原，请稍后再试');
    return;
  }
  savingModel.value = true;
  try {
    await saveAssistantModelSettingPost({ assistantDefaultConfigId: id });
    lastSavedAssistant.value = id;
    ElMessage.success('助手默认模型已保存，新会话生效');
    await refreshCapability();
  } catch {
    // 回滚下拉到服务端真值（m3-P0-1）：v-model 已写本地，不滚回去的话这个"幽灵值"
    // 会被后续后台槽保存当 payload 带上——助手默认被静默改写且无法对账
    modelForm.assistantDefaultConfigId = lastSavedAssistant.value;
    ElMessage.error('保存失败，已还原为上次保存的助手默认，请稍后再试');
  } finally {
    savingModel.value = false;
  }
}

async function onAllowSwitch() {
  const id = modelForm.assistantDefaultConfigId;
  if (!id || savingModel.value) return;
  savingModel.value = true;
  try {
    await saveAssistantModelSettingPost({ assistantDefaultConfigId: id, allowUserSwitch: settingAllowSwitch.value });
    ElMessage.success(allowSwitchOn.value ? '已允许用户自选——聊天输入条会出现模型选择' : '已关闭用户自选，全部走助手默认模型');
  } catch {
    ElMessage.error('保存失败，请稍后再试');
    settingAllowSwitch.value = allowSwitchOn.value ? '0' : '1';
  } finally {
    savingModel.value = false;
  }
}

/* ===== 业务批4 Q3：模型开放表（switch_scope 白名单勾选） ===== */
/** 勾选集（config_id 字串）；空=不限（全部可换）。保存节流：勾选变化即存 */
const scopeChecked = ref(new Set<string>());
const scopeSaving = ref(false);
function userSelectable(o: DcsAiConfigVO): boolean {
  // 用户侧可选口径与后端 CNY 闸同源：人民币价 + 按量（FIXED_PER_CALL 也算有价）；
  // step5 FE-6 回修：补 FIXED_PER_CALL 且按次单价 0 的真免费豁免支
  //（enforceCnyGate/listAssistantSelectableWithPrice 都有此支，漏掉会把免费模型标"不可选"反向撒谎）
  const fixedFree = o.billingMode === 'FIXED_PER_CALL' && Number(o.unitPricePerCall ?? 1) === 0;
  return fixedFree || (o.currency === 'CNY' && (o.inputPricePerMillion != null || o.billingMode === 'FIXED_PER_CALL'));
}
function toggleScope(configId: string, on: boolean) {
  const next = new Set(scopeChecked.value);
  if (on) {
    next.add(configId);
  } else {
    next.delete(configId);
  }
  scopeChecked.value = next;
  void saveScope(next);
}
async function saveScope(next: Set<string>) {
  const id = modelForm.assistantDefaultConfigId;
  if (!id) return;
  scopeSaving.value = true;
  try {
    // 空集=发空串清空（step5 FE-8：勿改成 null——后端 update 对 null 是"保持原值"的部分提交
    // 语义，mapper COALESCE 同口径；发 null 会让清空动作静默失效）。''入库后 switchScopeIds
    // 的 isBlank 判不限，链路自洽
    await saveAssistantModelSettingPost({ assistantDefaultConfigId: id, switchScope: next.size ? JSON.stringify([...next]) : '' });
  } catch {
    ElMessage.error('白名单保存失败，请重试');
    // 回滚视觉（重读服务端真值）
    try {
      const s = (await getAssistantModelSetting()) as unknown as { data?: { switchScope?: string | null } };
      parseScope(s?.data?.switchScope);
    } catch {
      /* 忽略 */
    }
  } finally {
    scopeSaving.value = false;
  }
}
function parseScope(raw: string | null | undefined) {
  try {
    const arr = raw ? (JSON.parse(raw) as unknown) : null;
    scopeChecked.value = new Set(Array.isArray(arr) ? arr.map(String) : []);
  } catch {
    scopeChecked.value = new Set();
  }
}
function gotoPricePage() {
  // 业务批4 Q3-A（2026-10-13 拍板）：后端驱动路由——没授「AI 模型配置」菜单的角色路由表里没有
  // 该路径，裸 push 是点了没反应的死钮；预检可达性，不可达给人话提示
  const resolved = router.resolve('/tenant/ai-config');
  if (!resolved.matched.length) {
    ElMessage.warning('你没有「AI 模型配置」页的菜单权限——请联系管理员开通后再来补价格');
    return;
  }
  router.push('/tenant/ai-config');
}

async function onTestConn() {
  const id = modelForm.assistantDefaultConfigId;
  if (!id) return;
  testLoading.value = true;
  try {
    const res = (await testDcsAiConfig(id)) as unknown as { data?: { success?: boolean; latencyMs?: number } };
    testResult.value = { success: !!res?.data?.success, latencyMs: res?.data?.latencyMs };
  } catch {
    testResult.value = { success: false };
  } finally {
    testLoading.value = false;
  }
}

/* ===== ✎ 覆盖弹窗（Q13 三模板） ===== */
/** 语义档联动的基准快照与抑制位：弹窗打开/切模板时程序性改档位，不应触发"自动调整"提示 */
let semWatchSuppress = false;
let prevTiers: string[] = [];
function resetSemBase() {
  semWatchSuppress = true;
  prevTiers = [...ovForm.tierValues];
}

/**
 * 业务批3-①（#11 语义档智能联动）：原生档位增删后，失效的语义档落点自动跳到
 * 被删档的最近幸存档（删中间档→贴邻档；档位删光→清空落点）。
 * 替代旧体验：删档后语义档仍指旧值，保存被后端 500「语义档落点必须在档位列表内」拦下。
 */
watch(
  () => [...ovForm.tierValues],
  (tiers) => {
    if (semWatchSuppress) {
      semWatchSuppress = false;
      prevTiers = tiers;
      return;
    }
    if (ovForm.tierKind === 'NONE') {
      prevTiers = tiers;
      return;
    }
    const valid = new Set(tiers);
    const hadRemoval = prevTiers.some((t) => !valid.has(t));
    const adjusted: string[] = [];
    if (hadRemoval) {
      for (const s of SEM_KEYS) {
        const cur = ovForm.semanticMap[s.key];
        if (!cur || valid.has(cur)) continue;
        // 被删档在旧列表中的位置 → 幸存档也按旧列表位置算距离（三席 P2：新旧下标混用会把
        // 删中间档退化成"同位置顶替"），并列时取前驱（如 low 被删时 disabled 与 high 并列，快速档取更轻的 disabled）
        const oldIdx = prevTiers.indexOf(cur);
        let best: string | undefined;
        let bestDist = Number.MAX_SAFE_INTEGER;
        for (let i = 0; i < tiers.length; i++) {
          const d = Math.abs(prevTiers.indexOf(tiers[i]) - oldIdx);
          if (d < bestDist) {
            bestDist = d;
            best = tiers[i];
          }
        }
        if (best) {
          ovForm.semanticMap[s.key] = best;
          adjusted.push(`${s.label}→${best}`);
        } else {
          ovForm.semanticMap[s.key] = undefined;
          adjusted.push(`${s.label}→清空`);
        }
      }
      if (adjusted.length) {
        ElMessage.info('原生档位变化，已自动调整语义档：' + adjusted.join('，'));
      }
    }
    prevTiers = tiers;
  }
);

function openOverride() {
  const cap = capability.value;
  const row = currentRow.value;
  if (!cap || !row) return;
  ovForm.provider = String(row.provider);
  ovForm.modelName = row.modelName;
  ovForm.tierKind = cap.tierKind;
  ovForm.tierValues = [...(cap.tierValues ?? [])];
  ovForm.semanticMap = { ...(cap.semanticMap ?? {}) };
  ovForm.contextWindow = cap.contextWindow ?? undefined;
  ovForm.maxOutputTokens = cap.maxOutputTokens ?? undefined;
  ovForm.supports = { image: !!cap.supportsImage, video: !!cap.supportsVideo, audio: !!cap.supportsAudio };
  resetSemBase();
  ovDialog.value = true;
}

/** 三模板预填（TIERS=low/high/max 三档映射, SWITCH=enabled/disabled 全开, NONE=空） */
function onTemplateChange(kind: 'TIERS' | 'SWITCH' | 'NONE') {
  if (kind === 'TIERS') {
    ovForm.tierValues = ['low', 'high', 'max'];
    ovForm.semanticMap = { FAST: 'low', BALANCED: 'high', DEEP: 'max' };
  } else if (kind === 'SWITCH') {
    ovForm.tierValues = ['enabled', 'disabled'];
    ovForm.semanticMap = { FAST: 'disabled', BALANCED: 'enabled', DEEP: 'enabled' };
  } else {
    ovForm.tierValues = [];
    ovForm.semanticMap = {};
  }
  // 模板自带整套合法映射，属程序性改动——抑制一次联动提示并重置基准
  resetSemBase();
}

async function onSaveOverride() {
  if (ovForm.tierKind !== 'NONE' && ovForm.tierValues.length === 0) {
    ElMessage.warning('请先填原生档位（或选择「不支持思考」）');
    return;
  }
  savingModel.value = true;
  try {
    const sem: Record<string, string> = {};
    for (const s of SEM_KEYS) {
      const v = ovForm.semanticMap[s.key];
      if (v) sem[s.key] = v;
    }
    await overrideAiCapability({
      provider: ovForm.provider,
      modelName: ovForm.modelName,
      tierKind: ovForm.tierKind,
      tierValues: ovForm.tierKind === 'NONE' ? [] : ovForm.tierValues,
      semanticMap: ovForm.tierKind === 'NONE' ? {} : sem,
      contextWindow: ovForm.contextWindow ?? null,
      maxOutputTokens: ovForm.maxOutputTokens ?? null,
      // P1 回修：三列恒定提交回显值（回显=当前生效值），不再条件展开
      supportsImage: ovForm.supports.image,
      supportsVideo: ovForm.supports.video,
      supportsAudio: ovForm.supports.audio
    });
    ElMessage.success('已保存覆盖——只影响本租户');
    ovDialog.value = false;
    await refreshCapability();
  } catch (e) {
    ElMessage.error((e as { msg?: string })?.msg || '保存失败，请稍后再试');
  } finally {
    savingModel.value = false;
  }
}

async function onResetCap() {
  const row = currentRow.value;
  if (!row) return;
  try {
    await ElMessageBox.confirm('恢复官方目录的映射？本租户对「' + row.modelName + '」的覆盖将删除。', '恢复官方', {
      type: 'warning',
      confirmButtonText: '确定恢复',
      cancelButtonText: '再想想'
    });
  } catch {
    return;
  }
  savingModel.value = true;
  try {
    await resetAiCapability({ provider: String(row.provider), model: row.modelName });
    ElMessage.success('已恢复官方映射');
    await refreshCapability();
  } catch {
    ElMessage.error('恢复失败，请稍后再试');
  } finally {
    savingModel.value = false;
  }
}

/* ===== 统计头（批C）：全租户四计数+本月费用，加载失败各数显 —（不拖垮页面） ===== */
const statsLoading = ref(false);
const stats = ref<import('@/api/dcs/assistant').AssistantAdminStats | null>(null);
async function loadStats() {
  statsLoading.value = true;
  try {
    const res = (await getAssistantAdminStats()) as unknown as { data?: import('@/api/dcs/assistant').AssistantAdminStats };
    stats.value = res?.data ?? null;
  } catch {
    stats.value = null;
  } finally {
    statsLoading.value = false;
  }
}

/* ===== 能力全景页签（批C）：工具清单+系统提示词（批A 端点消费） ===== */
const panoLoading = ref(false);
const panoTools = ref<AssistantPanoramaTool[]>([]);
const panoSysPrompt = ref('');
/** 业务批7 Q9：技能库（折叠看正文；注入方式与 SkillAssets 口径一致） */
const panoSkills = ref<AssistantPanoramaSkill[]>([]);
/** 批2 Q8：总纲分段视图（来源 md 文件+只读内容，随版本更新） */
interface AssistantPromptSegment {
  key: string;
  title: string;
  file: string;
  source: string;
  content: string;
}
const panoSegments = ref<AssistantPromptSegment[]>([]);
const SOURCE_TXT: Record<string, string> = { external: '外部覆写', classpath: '随版文件', embedded: '内嵌兜底' };
const STATION_TXT: Record<number, string> = { 1: '一期', 2: '二站', 3: '本期' };
const autoCount = computed(() => panoTools.value.filter((t) => t.behavior !== '需确认').length);
const askCount = computed(() => panoTools.value.filter((t) => t.behavior === '需确认').length);
async function loadPanorama() {
  panoLoading.value = true;
  try {
    const res = (await getAssistantPanorama()) as unknown as {
      data?: { tools?: AssistantPanoramaTool[]; sysPrompt?: string; promptSegments?: AssistantPromptSegment[] };
    };
    panoTools.value = res?.data?.tools ?? [];
    panoSysPrompt.value = res?.data?.sysPrompt ?? '';
    panoSegments.value = res?.data?.promptSegments ?? [];
    panoSkills.value = (res?.data as unknown as { skills?: AssistantPanoramaSkill[] })?.skills ?? [];
  } catch {
    ElMessage.error('能力全景加载失败，请稍后再试');
  } finally {
    panoLoading.value = false;
  }
}

/** lazy 页签首访加载（批C）：能力全景切到才拉（工作面不预载用不到的数据） */
/** 公告页签首开即拉列表（lazy 页签，与 panorama/quota 同款 onTabChange 模式）。
 *  dspro P2-5 回修：反馈页签每次切入重刷统计——原「fbRows 非空只刷一次」会让管理员
 *  处理完反馈后看到的仍是进页那一刻的好评率/原因分布（核心工作流恰是看数据→处理→再看）。 */
function onTabChange(name: string | number) {
  if (name === 'panorama' && panoTools.value.length === 0 && !panoLoading.value) {
    void loadPanorama();
  }
  if (name === 'quota' && quotaRows.value.length === 0 && !quotaLoading.value) {
    void loadQuotaPage();
  }
  if (name === 'announcement' && annRows.value.length === 0 && !annLoading.value) {
    void loadAnnouncements();
  }
  if (name === 'feedback') {
    void loadFbStats();
  }
}

/* ===== 业务批3-③(#16)：用户额度页签 ===== */
const quotaRows = ref<AssistantQuotaRow[]>([]);
const quotaTotal = ref(0);
const quotaLoading = ref(false);
const quotaQuery = reactive({ keyword: '', disabledOnly: '', pageNum: 1, pageSize: 10 });
/** 限额弹窗（空输入+勾选跟随默认=清空覆盖） */
const quotaLimitsDialog = ref(false);
const quotaSaving = ref(false);
const quotaLimitsUser = ref('');
const quotaLimitsUserId = ref<number | string>('');
const quotaLimitsForm = reactive({ roundLimit: undefined as number | undefined, websearchLimit: undefined as number | undefined });
const quotaLimitsFollowDefault = ref(false);

/** 租户默认回合/联网上限（弹窗占位提示与"当前生效"话术用；批④起联网列落 config） */
const tenantRoundDefault = computed(() => cfg.value?.dailyRoundLimitPerUser ?? 50);
const tenantWebDefault = computed(() => cfg.value?.websearchLimitPerUser ?? 20);

/** UI-UX 批（2026-10-13）：本页速览——已用完/快用完(≥80%)/已禁用人数（口径=当前页，
 *  概览条明示"本页"防跨页误读；不加后端汇总接口，纯前端可算） */
const quotaPageStats = computed(() => {
  const rows = quotaRows.value;
  // 回修（dspro P2-4）：限额≤0=租户急停口径——计入"已用完"列（急停=全员不可用，比单独加格轻），
  // 不再出现"数字格红字已用完 + 概览 0 人 + 蓝条正常"三处自相矛盾
  const exhausted = rows.filter((r) => r.effectiveRoundLimit <= 0 || r.turnsUsedToday >= r.effectiveRoundLimit).length;
  const near = rows.filter((r) => {
    if (r.effectiveRoundLimit <= 0 || r.turnsUsedToday >= r.effectiveRoundLimit) return false;
    return r.turnsUsedToday / r.effectiveRoundLimit >= 0.8;
  }).length;
  const disabled = rows.filter((r) => r.disabled === '1').length;
  return { exhausted, near, disabled };
});

/** 用量进度条：百分比封顶 100（上限 0/未知的脏数据不画条只显数字）与色档（蓝正常/橙≥80%/红用完） */
const quotaBarPct = (row: AssistantQuotaRow): number => {
  if (!row.effectiveRoundLimit || row.effectiveRoundLimit <= 0) return 0;
  return Math.min(100, Math.round((row.turnsUsedToday / row.effectiveRoundLimit) * 100));
};
const quotaBarTier = (row: AssistantQuotaRow): string => {
  if (row.effectiveRoundLimit <= 0) return 't-gray';
  if (row.turnsUsedToday >= row.effectiveRoundLimit) return 't-red';
  if (quotaBarPct(row) >= 80) return 't-orange';
  return 't-blue';
};

/** UI-UX 批：费用千分位（¥1,234.56——裸 6 位小数读不出量级，管理页费用列统一口径） */
const fmtMoney = (v?: number | null): string => {
  // 回修（glm53 P2-6）：null/undefined/NaN 一律落 0 展示，不让 toLocaleString 把 NaN 原样上屏
  const n = Number(v);
  return (Number.isFinite(n) ? n : 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
};
/** 回修（m3 P1-1）：勾"跟随租户默认"后实时按保存结果展示，不再停留在单人旧值误导；
 *  批④起双列各自跟随（round/websearch 可一列覆盖一列默认，后端四象限分流承接） */
const quotaLimitsCurrentText = computed(() => {
  if (quotaLimitsFollowDefault.value) {
    return `默认 ${tenantRoundDefault.value} 回合 · ${tenantWebDefault.value} 次联网 / 天`;
  }
  const r = quotaLimitsForm.roundLimit;
  const w = quotaLimitsForm.websearchLimit;
  const rt = r != null ? `单人 ${r}` : `默认 ${tenantRoundDefault.value}`;
  const wt = w != null ? `单人 ${w}` : `默认 ${tenantWebDefault.value}`;
  return `${rt} 回合 · ${wt} 次联网 / 天`;
});

async function loadQuotaPage() {
  quotaLoading.value = true;
  try {
    const res = (await pageAssistantQuota({
      keyword: quotaQuery.keyword || undefined,
      disabledOnly: quotaQuery.disabledOnly || undefined,
      pageNum: quotaQuery.pageNum,
      pageSize: quotaQuery.pageSize
    })) as unknown as { data?: { rows?: AssistantQuotaRow[]; total?: number } };
    quotaRows.value = res?.data?.rows ?? [];
    quotaTotal.value = res?.data?.total ?? 0;
  } catch {
    ElMessage.error('用户额度加载失败，请稍后再试');
  } finally {
    quotaLoading.value = false;
  }
}

/** 重置今日额度（确认后 DEL 当日计数，该用户立刻恢复可用） */
async function onQuotaReset(row: AssistantQuotaRow) {
  const name = row.nickName || row.userName;
  try {
    await ElMessageBox.confirm(
      `把「${name}」今天的已用回合（${row.turnsUsedToday} / ${row.effectiveRoundLimit}）清零？清零后 ${name} 今天还能继续聊。`,
      '重置今日额度',
      { type: 'warning', confirmButtonText: '确定重置', cancelButtonText: '再想想' }
    );
  } catch {
    return;
  }
  try {
    await resetAssistantQuota(row.userId);
    ElMessage.success(`已重置——${name} 今天的额度已恢复满额`);
    await loadQuotaPage();
  } catch {
    ElMessage.error('重置失败，请稍后再试');
  }
}

function openQuotaLimits(row: AssistantQuotaRow) {
  quotaLimitsUserId.value = row.userId;
  quotaLimitsUser.value = `${row.nickName || row.userName}${row.deptName ? ' · ' + row.deptName : ''}`;
  quotaLimitsForm.roundLimit = row.roundLimitOverride ?? undefined;
  quotaLimitsForm.websearchLimit = row.websearchLimitOverride ?? undefined;
  // 批④起双列独立：勾选态=两列都无覆盖（一列覆盖一列默认时打开为未勾，可按列清空输入）
  quotaLimitsFollowDefault.value = row.roundLimitOverride == null && row.websearchLimitOverride == null;
  quotaLimitsDialog.value = true;
}

async function onSaveQuotaLimits() {
  quotaSaving.value = true;
  try {
    // 勾「跟随默认」=两列全清；否则每列独立（空输入=该列跟随默认，后端四象限分流承接）
    const follow = quotaLimitsFollowDefault.value;
    await updateAssistantQuotaLimits({
      userId: quotaLimitsUserId.value,
      roundLimit: follow ? null : (quotaLimitsForm.roundLimit ?? null),
      websearchLimit: follow ? null : (quotaLimitsForm.websearchLimit ?? null)
    });
    ElMessage.success(
      follow
        ? '已恢复两项跟随租户默认'
        : `已保存：${quotaLimitsForm.roundLimit ?? '默认'} 回合 · ${quotaLimitsForm.websearchLimit ?? '默认'} 次联网 / 天`
    );
    quotaLimitsDialog.value = false;
    await loadQuotaPage();
  } catch (e) {
    ElMessage.error((e as { msg?: string })?.msg || '保存失败，请稍后再试');
  } finally {
    quotaSaving.value = false;
  }
}

/** 禁用/启用（双向确认：禁用要说明后果，启用直接做） */
async function onQuotaToggle(row: AssistantQuotaRow) {
  const disabling = row.disabled !== '1';
  const name = row.nickName || row.userName;
  if (disabling) {
    try {
      await ElMessageBox.confirm(`暂停「${name}」使用 AI 助手？${name} 发消息会收到「管理员已暂停」提示，可随时再启用。`, '禁用 AI 助手', {
        type: 'warning',
        confirmButtonText: '确定禁用',
        cancelButtonText: '再想想'
      });
    } catch {
      return;
    }
  }
  try {
    await setAssistantQuotaDisabled(row.userId, disabling);
    ElMessage.success(disabling ? `已禁用——${name} 暂时不能使用 AI 助手` : `已启用——${name} 可以继续使用了`);
    await loadQuotaPage();
  } catch {
    ElMessage.error(disabling ? '禁用失败，请稍后再试' : '启用失败，请稍后再试');
  }
}

/* ===== 业务反馈批1015b Q14：联网搜索卡片（Q8 跟随平台默认模型开关已按用户要求撤销 2026-10-15） ===== */
const WS_KEY = 'dcs.assistant.websearch.key';
const WS_PLATFORM_KEY = 'dcs.assistant.websearch.platform_default';
const wsState = reactive({ configured: false, maskedKey: '', platformDefault: false });
const wsTesting = ref(false);
const wsTestMsg = ref('');
const wsTestOk = ref(false);
const wsSavingSwitch = ref(false);
/** 参数编辑权限（写走系统配置 API；无权限只读+提示） */
const wsCanEditConfig = computed(() => checkPermi(['system:config:edit']));
const wsReplace = reactive({ open: false, key: '', testing: false, saving: false, testMsg: '', testOk: false });

async function loadWsInfo() {
  try {
    const res = (await getWebsearchInfo()) as unknown as { data?: typeof wsState & { configured: boolean } };
    const d = res?.data;
    if (d) {
      wsState.configured = !!d.configured;
      wsState.maskedKey = d.maskedKey || '';
      wsState.platformDefault = !!d.platformDefault;
    }
  } catch {
    /* 读失败不阻塞页面，卡片按未配置显示 */
  }
}

async function onWsTest() {
  wsTesting.value = true;
  wsTestMsg.value = '';
  try {
    const res = (await testWebsearch()) as unknown as { data?: { ok: boolean; message: string } };
    wsTestOk.value = !!res?.data?.ok;
    wsTestMsg.value = res?.data?.message || '测试完成';
  } catch {
    wsTestOk.value = false;
    wsTestMsg.value = '测试请求失败';
  } finally {
    wsTesting.value = false;
  }
}

async function onWsTestCandidate() {
  const key = wsReplace.key.trim();
  if (!key.startsWith('sk-')) {
    wsReplace.testOk = false;
    wsReplace.testMsg = '密钥应以 sk- 开头';
    return;
  }
  wsReplace.testing = true;
  wsReplace.testMsg = '';
  try {
    const res = (await testWebsearch(key)) as unknown as { data?: { ok: boolean; message: string } };
    wsReplace.testOk = !!res?.data?.ok;
    wsReplace.testMsg = res?.data?.message || '测试完成';
  } catch {
    wsReplace.testOk = false;
    wsReplace.testMsg = '测试请求失败';
  } finally {
    wsReplace.testing = false;
  }
}

/** sys_config upsert（走系统配置 API 自带缓存刷新；行不在则建） */
async function upsertSysConfig(configKey: string, configValue: string, configName: string) {
  const res: any = await listConfig({ pageNum: 1, pageSize: 10, configKey });
  const row = res?.rows?.[0];
  if (row) {
    await updateConfig({ ...row, configValue });
  } else {
    await addConfig({ configName, configKey, configValue, configType: 'Y', remark: 'AI 助手管理页写入' } as never);
  }
}

async function onWsSaveKey() {
  const key = wsReplace.key.trim();
  if (!key.startsWith('sk-')) {
    ElMessage.warning('密钥应以 sk- 开头');
    return;
  }
  wsReplace.saving = true;
  try {
    await upsertSysConfig(WS_KEY, key, 'AI助手联网搜索密钥');
    ElMessage.success('搜索密钥已保存，立即生效');
    wsReplace.open = false;
    wsReplace.key = '';
    wsReplace.testMsg = '';
    await loadWsInfo();
  } catch {
    /* request.ts 已统一弹错 */
  } finally {
    wsReplace.saving = false;
  }
}

async function onPlatformDefaultToggle(v: boolean | string | number) {
  wsSavingSwitch.value = true;
  try {
    await upsertSysConfig(WS_PLATFORM_KEY, v ? 'true' : 'false', 'AI助手搜索密钥全平台共用开关');
    ElMessage.success(v ? '已开启：没配密钥的租户将共用平台密钥（费用记平台账）' : '已关闭：各租户须自配搜索密钥');
  } catch {
    wsState.platformDefault = !v;
  } finally {
    wsSavingSwitch.value = false;
  }
}

onMounted(() => {
  loadConfig();
  if (checkPermi(['dcs:assistant:config'])) {
    void loadWsInfo();
  }
  if (checkPermi(['dcs:assistant:config'])) {
    loadStats();
    loadModelSection();
    loadActionLog();
  }
  if (checkPermi(['dcs:assistant:feedback:query'])) {
    loadFeedback();
  }
});
</script>

<style lang="scss" scoped>
/* AI 缺口批 Q11：公告发布行——单行排布（标题/样式/正文/发布钮），窄屏自然换行 */
.ann-form {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
/* AI 缺口批 Q10：反馈统计条 */
.fb-stats {
  display: flex;
  gap: 18px;
  align-items: stretch;
}
.fb-rate {
  flex: none;
  width: 170px;
  text-align: center;
  padding: 8px 0;
}
.fb-rate-num {
  font-size: 34px;
  font-weight: 700;
  line-height: 1.2;
}
.fb-rate-num.good {
  color: var(--el-color-success);
}
.fb-rate-num.mid {
  color: var(--el-color-warning);
}
.fb-rate-num.bad {
  color: var(--el-color-danger);
}
.fb-rate-lbl {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.fb-rate-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.fb-reasons {
  flex: 1.4;
  min-width: 0;
}
.fb-trend {
  flex: 1;
  min-width: 0;
}
.fb-sec-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  margin-bottom: 6px;
}
.fb-reason-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 5px;
}
.fb-reason-name {
  flex: none;
  width: 150px;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fb-reason-bar {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  overflow: hidden;
}
.fb-reason-bar i {
  display: block;
  height: 100%;
  background: var(--el-color-danger);
  opacity: 0.75;
}
.fb-reason-cnt {
  flex: none;
  width: 32px;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.fb-trend-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  padding: 2px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.fb-trend-row:last-child {
  border-bottom: none;
}
.fb-trend-week {
  color: var(--el-text-color-secondary);
}
.fb-empty {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  padding: 6px 0;
}
.dcs-as-admin {
  /* 方案A追加 2026-10-11：1320→1720 封顶——1920 办公屏内容区(~1670px)完全铺满消灭右侧空白，2K 带鱼屏防过度拉伸 */
  max-width: 1720px;

  .adm-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 16px;

    .head-title {
      display: flex;
      align-items: center;
      gap: 10px;

      b {
        font-size: 20px;
        font-weight: 600;
        color: var(--dcs-text-primary);
      }
    }
    .head-sub {
      display: block;
      margin-top: 6px;
      font-size: 13px;
      color: var(--dcs-text-secondary);
    }
  }

  /* 统计卡（方案A 2026-10-11）：白底描边卡 + 数字统一主蓝（含费用——EP 只认四级语义色），hover 边框亮起；28px 数字 + 13px 灰标签 */
  .stat-grid {
    display: grid;
    grid-template-columns: repeat(5, 1fr);
    gap: 16px;
    margin-bottom: 18px;

    .stat-card {
      padding: 18px 20px 16px;
      background: var(--el-bg-color);
      border: 1px solid var(--el-border-color-light);
      border-radius: 8px;
      transition:
        border-color 0.2s,
        box-shadow 0.2s;

      &:hover {
        border-color: var(--el-color-primary-light-5);
        box-shadow: 0 2px 8px rgb(0 0 0 / 6%);
      }

      .num {
        font-size: 28px;
        font-weight: 600;
        line-height: 1.2;
        color: var(--el-color-primary);
        font-variant-numeric: tabular-nums;
      }
      .lbl {
        font-size: 13px;
        color: var(--dcs-text-secondary);
        margin-top: 5px;
      }
    }
  }

  .adm-card {
    border-radius: 10px;

    /* 卡片头两件套（批2 视觉回修：原先只在 .model-card 内定义，能力全景两卡没吃到，
       说明字未灰化弱化——提为 adm-card 共用） */
    .card-head {
      display: flex;
      align-items: baseline;
      gap: 10px;

      b {
        font-size: 14px;
        color: var(--dcs-text-primary);
      }
      .head-note {
        font-size: 12.5px;
        color: var(--dcs-text-secondary);
      }
    }
  }
  /* ===== 描述列表（方案A 2026-10-11）：el-descriptions 边框式，标签列 168px 统一对齐（替代旧 kvrow 110px 手拼列） ===== */
  .adm-desc {
    :deep(.el-descriptions__label) {
      width: 168px;
      min-width: 168px;
      font-weight: 500;
      background: var(--el-fill-color-lighter);
    }
    :deep(.el-descriptions__content) {
      font-size: 13.5px;
      color: var(--dcs-text-regular);
    }
  }
  .cell-flex {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    row-gap: 4px;

    .sp {
      flex: 1;
    }
  }
  .cell-note {
    font-size: 12.5px;
    color: var(--dcs-text-secondary);
  }
  /* 批2 Q4 搬家指路：槽行整体搬进画像设置，原位置留删除线标签+灰底指路条（过渡期） */
  .moved-slot-label {
    text-decoration: line-through;
    color: var(--el-text-color-placeholder);
  }
  .moved-slot-note {
    padding: 6px 10px;
    border-radius: 6px;
    background: var(--el-fill-color-lighter);
    font-size: 12px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
  }
  .cell-sub {
    margin-top: 6px;
    font-size: 12.5px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
  }
  /* 业务批4 Q3：模型开放表（自选开关下的白名单勾选表） */
  .scope-box {
    margin-top: 10px;
    width: 100%;

    .scope-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 6px;
      font-size: 12.5px;
      color: var(--dcs-text-secondary);
    }
    .scope-table {
      width: 100%;

      /* 业务批4 Q15-B：组头 banner（logo+厂名+数量）与悬停压制（组头=分组标题非交互行） */
      :deep(.grp-row) td.el-table__cell {
        background: #f8fafc;
      }
      :deep(.grp-row:hover) td.el-table__cell {
        background: #f8fafc;
      }
      .scope-grp {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-weight: 600;
        color: var(--dcs-text-secondary);
        /* m3 P2-3 回修：超长厂商名省略号收口，不撑破组头 banner */
        min-width: 0;
        max-width: 100%;

        .scope-grp-name {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .scope-grp-count {
          flex: none;
          font-weight: 400;
          font-size: 12px;
          color: var(--dcs-text-secondary);
          opacity: 0.85;
        }
      }
    }
  }
  .hint-dot {
    cursor: help;
    color: var(--dcs-text-secondary);
    font-size: 13px;
    line-height: 1;
  }
  /* 页签红色角标（替代旧括号手拼计数） */
  .tab-badge {
    margin-left: 6px;
    transform: translateY(-2px);
  }
  .fb-more {
    text-align: center;
    padding-top: 6px;
  }
  /* 运行记录查询栏 + 分页（2026-10-11） */
  .log-filter {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    row-gap: 8px;
    margin-bottom: 12px;
  }
  .log-pager {
    display: flex;
    justify-content: flex-end;
    padding-top: 12px;
  }

  /* ===== 能力全景（批C） ===== */
  .pano-prompt {
    margin-top: 14px;

    :deep(.el-collapse) {
      border: none;
    }
    :deep(.el-collapse-item__header) {
      height: 44px;
      font-size: 14px;
    }
    :deep(.el-collapse-item__wrap) {
      background: var(--el-fill-color-light);
      border-radius: 8px;
      margin-bottom: 4px;
    }
    :deep(.el-collapse-item__content) {
      padding-bottom: 0;
    }
    .sys-prompt {
      margin: 0;
      padding: 14px 18px;
      max-height: 320px;
      overflow: auto;
      font-size: 12.5px;
      line-height: 1.8;
      color: var(--dcs-text-regular);
      white-space: pre-wrap;
      word-break: break-word;
    }
    /* 批2 Q8 总纲分段：段头=标题+来源文件+来源标签，内容复用 sys-prompt 底版 */
    .prompt-seg {
      margin-bottom: 14px;
      border: 1px solid var(--dcs-border-color-light);
      border-radius: 6px;
      overflow: hidden;

      .seg-head {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 8px 12px;
        background: var(--el-fill-color);
        font-size: 12.5px;

        .seg-title {
          color: var(--dcs-text-primary);
        }
        .seg-file {
          color: var(--dcs-text-secondary);
          font-family: var(--el-font-family, monospace);
        }
      }
      .sys-prompt {
        /* 批2 视觉回修：去段内限高——总纲是只读参考面，段内滚动切段阅读体验 */
        max-height: none;
      }
      &:last-child {
        margin-bottom: 0;
      }
    }
  }
  .tool-cell {
    display: flex;
    flex-direction: column;
    gap: 2px;

    .tool-name {
      font-size: 13.5px;
      color: var(--dcs-text-primary);
    }
    .tool-code {
      font-size: 12px;
      color: var(--dcs-text-placeholder);
      font-family: monospace;
    }
  }

  /* ===== 模型选择卡（批6） ===== */
  .model-card {
    margin-top: 14px;

    .card-head {
      display: flex;
      align-items: baseline;
      gap: 10px;

      b {
        font-size: 14px;
        color: var(--dcs-text-primary);
      }
      .head-note {
        font-size: 12.5px;
        color: var(--dcs-text-secondary);
      }
    }
    .opt-row {
      display: flex;
      justify-content: space-between;
      gap: 16px;

      /* 业务批4 Q14-A：行首带厂商 logo 后主段改 flex 对齐 */
      .opt-main {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        min-width: 0;
      }
      .opt-price {
        color: var(--dcs-text-secondary);
        font-size: 12px;
      }
      .def-tag {
        margin-left: 6px;
      }
    }
    /* 该模型能力（方案A：并入描述列表——cap-row 键值行保留，操作按钮虚线分隔右对齐收口） */
    .cap-row {
      display: flex;
      gap: 10px;
      padding: 4px 0;
      font-size: 13px;
      line-height: 1.6;

      .k {
        width: 80px;
        flex: none;
        color: var(--dcs-text-secondary);
      }
      .v {
        color: var(--dcs-text-regular);
      }
    }
    .cap-actions {
      display: flex;
      justify-content: flex-end;
      gap: 4px;
      padding: 6px 0 0;
      margin-top: 2px;
      border-top: 1px dashed var(--el-border-color-lighter);
    }
  }
}

/* ===== ✎ 覆盖弹窗（业务批3-① 修复样式垮塌）=====
   弹窗 append-to-body 挂在 body 下，脱离本组件 .dcs-as-admin DOM 子树——
   嵌在 .model-card 里的弹窗类选择器祖先链断裂全部失效（对齐 ai-config 弹窗先例：
   顶层弹窗类 + 后代选择器，class 由 el-dialog 落到弹层根，不依赖页面祖先） */
.ov-dialog {
  .field-hint {
    font-size: 12px;
    color: var(--dcs-text-placeholder);
    line-height: 1.6;
  }
  /* 支持输入行（Q2A）：锁定灰签 + 三勾选横排 */
  .supports-row {
    display: flex;
    align-items: center;
    gap: 14px;

    .text-always {
      cursor: default;
    }
  }
  .unit-note {
    margin-left: 8px;
    font-size: 12px;
    color: var(--dcs-text-secondary);
  }
  .sem-rows {
    display: flex;
    flex-direction: column;
    gap: 6px;
    width: 100%;

    .sem-row {
      display: flex;
      align-items: center;
      gap: 10px;

      .sem-name {
        width: 40px;
        flex: none;
        font-size: 13px;
        color: var(--dcs-text-regular);
      }
    }
  }
  .ov-alert {
    margin-bottom: 12px;
  }
}

/* 业务批3-③(#16)：用户额度页签——账号副字/单人覆盖角标/用满标红；限额弹窗同 .ov-dialog 口径 */
.q-dialog {
  .field-hint {
    font-size: 12px;
    color: var(--dcs-text-placeholder);
    line-height: 1.6;
    padding-left: 120px;
  }
  /* UI-UX 批：保存后生效预览块——主色浅底强调，勾跟随/填数字即时刷新 */
  .q-preview {
    margin-left: 120px;
    margin-top: 4px;
    display: flex;
    align-items: baseline;
    gap: 10px;
    background: var(--el-color-primary-light-9);
    border-radius: 6px;
    padding: 8px 14px;
    .q-preview-k {
      font-size: 12px;
      color: var(--el-color-primary);
      flex: none;
    }
    .q-preview-v {
      font-size: 13.5px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
  }
  .unit-note {
    margin-left: 8px;
    font-size: 12px;
    color: var(--dcs-text-secondary);
  }
  .ov-alert {
    margin-bottom: 12px;
  }
}
.quota-sub {
  margin-left: 6px;
  font-size: 12px;
  color: var(--dcs-text-secondary);
}
/* UI-UX 批（2026-10-13）：概览条——租户默认口径(左,实值) + 本页速览(右,色点+计数)。
 * 横向一行不占竖向空间；浅底块分组，色点与表格进度条同色系（红=用完/橙=快用完/灰=禁用） */
.quota-overview {
  display: flex;
  align-items: stretch;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  .qo-item {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 8px 14px;
    background: var(--el-fill-color-light);
    border-radius: 6px;
    min-width: 96px;
  }
  .qo-tenant {
    flex: 1;
    min-width: 260px;
  }
  .qo-k {
    font-size: 12px;
    color: var(--dcs-text-secondary);
    display: flex;
    align-items: center;
    gap: 5px;
  }
  .qo-v {
    font-size: 13.5px;
    color: var(--dcs-text-primary);
    b {
      font-size: 17px;
      font-weight: 700;
      color: var(--dcs-text-primary);
    }
  }
  .qo-note {
    font-size: 11.5px;
    color: var(--dcs-text-placeholder);
    font-weight: 400;
    margin-left: 4px;
  }
  .qo-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    display: inline-block;
    &.qo-red {
      background: var(--el-color-danger);
    }
    &.qo-orange {
      background: var(--el-color-warning);
    }
    &.qo-gray {
      background: var(--el-text-color-placeholder);
    }
  }
}
/* 业务批7 Q8：租户默认「改默认」弹层（popover 内容样式；深化=关停标红/警示/预览） */
.qo-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  .qo-form-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 12.5px;
    color: var(--dcs-text-secondary);
    padding: 2px 4px;
    border-radius: 5px;

    /* Q8 深化①：值为 0 的行整体标红——关停态一眼可见，不只靠输入框里的数字 */
    &.qo-danger {
      color: var(--el-color-danger);
      background: var(--el-color-danger-light-9);
    }
  }
  .qo-form-tip {
    font-size: 11.5px;
    line-height: 1.6;
    color: var(--dcs-text-placeholder);

    /* Q8 深化②：从非 0 改 0 时，提示从灰说明升级为红警示 */
    &.qo-tip-danger {
      color: var(--el-color-danger);
      font-weight: 600;
    }
  }
  /* Q8 深化③：生效预览——.q-preview 定义在 .q-dialog 嵌套内，popover teleport 到 body
     祖先链断（本页 .ov-dialog 注释同款坑），这里完整自带样式不依赖页面祖先 */
  .qo-preview {
    display: flex;
    align-items: baseline;
    gap: 10px;
    background: var(--el-color-primary-light-9);
    border-radius: 6px;
    padding: 8px 14px;
    .q-preview-k {
      font-size: 12px;
      color: var(--el-color-primary);
      flex: none;
    }
    .q-preview-v {
      font-size: 13.5px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
  }
  .qo-form-btns {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  }
}
/* 共 N 人：筛选行右缘的轻量计数（与 total 分页口径一致） */
.qo-total {
  margin-left: 16px;
  font-size: 12.5px;
  color: var(--dcs-text-secondary);
  align-self: center;
}
/* 视觉回修（S6 P2）：数字恒居中成一条竖线；「单人N」徽标换行沉到数字下方，不再横向推挤数字 */
.quota-turn-cell {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  line-height: 1.3;
}
.q-badges {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  justify-content: center;
}
/* UI-UX 批：用量进度条——细条 4px 不抢行高，色档一眼分正常/快用完/用完（数字保留精确值） */
.q-usage {
  display: flex;
  align-items: center;
  gap: 8px;
  font-variant-numeric: tabular-nums;
  .q-usage-bar {
    width: 64px;
    height: 4px;
    border-radius: 2px;
    background: var(--el-fill-color-darker, #e4e7ed);
    overflow: hidden;
    flex: none;
    i {
      display: block;
      height: 100%;
      border-radius: 2px;
      transition: width 0.3s;
      &.t-blue {
        background: var(--el-color-primary);
      }
      &.t-orange {
        background: var(--el-color-warning);
      }
      &.t-red {
        background: var(--el-color-danger);
      }
      &.t-gray {
        background: var(--el-text-color-placeholder);
      }
    }
  }
}
.quota-tag {
  height: 18px;
  padding: 0 6px;
}
.quota-exhausted {
  color: var(--el-color-danger);
  font-weight: 600;
}
</style>
