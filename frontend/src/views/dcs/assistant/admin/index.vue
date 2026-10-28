<template>
  <div class="app-container dcs-as-admin">
    <!-- 页头与四段导航同一行：首屏内容不再被标题和页签各吃一行（本批：起点 501px → ~130px） -->
    <div class="adm-bar">
      <div class="adm-title">
        <b>AI 助手管理</b>
        <el-tag v-if="cfg.enabled" type="success" size="small" effect="light">已开通</el-tag>
        <el-tag v-else type="info" size="small" effect="light">未开通</el-tag>
        <el-tag type="warning" size="small" effect="plain">试点期</el-tag>
      </div>
      <nav class="adm-seg">
        <button type="button" :class="{ on: tab === 'overview' }" @click="jumpTo('overview')">总览</button>
        <button v-if="canConfig" type="button" :class="{ on: tab === 'caps' }" @click="jumpTo('caps')">能力</button>
        <button v-if="canConfig" type="button" :class="{ on: tab === 'rules' }" @click="jumpTo('rules')">规则</button>
        <button v-if="canConfig || canFeedback" type="button" :class="{ on: tab === 'ops' }" @click="jumpTo('ops')">运营</button>
      </nav>
      <span class="adm-sub">总览看现状 · 能力看它会什么、谁能用 · 规则定租户的规矩 · 运营看出过什么事</span>
    </div>

    <div class="adm-body">
      <!-- ============ 总览：数字 + 健康 + 需要处理 ============ -->
      <div v-show="tab === 'overview'" class="adm-pane">
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
    <!-- 稳流② Q8②：运行健康（run 终态分布 + 追问失败分类；进程内累计=重启归零，够看趋势） -->
    <el-card v-if="checkPermi(['dcs:assistant:config'])" shadow="never" class="adm-card health-card" v-loading="healthLoading">
      <template #header>
        <div class="health-head">
          <b>运行健康</b>
          <span class="cell-note">自本次启动累计（{{ healthSince }} 起）· 服务重启后重新计数</span>
        </div>
      </template>
      <div class="health-grid">
        <div>
          <div class="health-sub">回答轮次收尾方式</div>
          <div class="health-chips">
            <span v-for="(v, k) in health.runOutcomes" :key="k" class="hchip" :class="runTone(String(k))">
              <i>{{ v }}</i>{{ runLabel(String(k)) }}
            </span>
            <span v-if="!Object.keys(health.runOutcomes).length" class="cell-note">暂无数据</span>
          </div>
        </div>
        <div>
          <div class="health-sub">追问建议去向</div>
          <div class="health-chips">
            <span v-for="(v, k) in health.followupOutcomes" :key="k" class="hchip" :class="fuTone(String(k))">
              <i>{{ v }}</i>{{ fuLabel(String(k)) }}
            </span>
            <span v-if="!Object.keys(health.followupOutcomes).length" class="cell-note">暂无数据</span>
          </div>
        </div>
      </div>
      <div class="field-hint">回答太短不发追问、超预算不发追问都属正常节流；「生成超时/解析失败」占比持续偏高时再去查模型侧配置。</div>
    </el-card>
        <div class="ov-grid">
          <section class="adm-sec ov-panel">
            <div class="sec-head">
              <div class="sec-ttl">
                <b>需要处理</b>
                <span class="sec-cnt">{{ attentionItems.length }} 项</span>
              </div>
              <span class="sec-note">只列真的会影响用户的事</span>
            </div>
            <ul class="att-list">
              <li v-for="(it, i) in attentionItems" :key="i">
                <span class="att-dot" :class="it.level"></span>
                <div class="att-tx">
                  <b>{{ it.title }}</b>
                  <p>{{ it.desc }}</p>
                </div>
                <el-button link type="primary" size="small" @click="jumpTo(it.tab)">{{ it.action }} →</el-button>
              </li>
              <li v-if="!attentionItems.length" class="att-ok">
                <span class="att-dot ok"></span>
                <div class="att-tx"><b>都正常</b><p>没有发现会影响用户的问题。</p></div>
              </li>
            </ul>
          </section>

          <section class="adm-sec ov-panel">
            <div class="sec-head">
              <div class="sec-ttl"><b>这一页管什么</b></div>
            </div>
            <ul class="guide-list">
              <li>
                <div class="gl-tx"><b>能力</b><span>助手会什么、谁能用、哪项危险</span></div>
                <el-button link type="primary" size="small" @click="jumpTo('caps')">打开 →</el-button>
              </li>
              <li>
                <div class="gl-tx"><b>规则</b><span>租户开关、模型与费用、每人每天额度</span></div>
                <el-button link type="primary" size="small" @click="jumpTo('rules')">打开 →</el-button>
              </li>
              <li>
                <div class="gl-tx"><b>运营</b><span>真实调用与失败、错答反馈、运行记录、公告</span></div>
                <el-button link type="primary" size="small" @click="jumpTo('ops')">打开 →</el-button>
              </li>
            </ul>
          </section>
        </div>
      </div>

      <!-- ============ 能力：能力清单（权限控制台） + 外部服务 + 行为总纲 ============ -->
      <div v-show="tab === 'caps'" v-if="canConfig" class="adm-pane">
        <section class="adm-sec">
          <div class="sec-head">
            <div class="sec-ttl">
              <b>能力清单</b>
              <span class="sec-cnt">{{ capAvailCount }} 项</span>
              <span class="sec-note">另有 {{ tgRows.length - capAvailCount }} 项因配置未开暂不出现（见底部）</span>
            </div>
            <div class="sec-acts">
              <el-checkbox v-model="capHighOnly" label="只看高危" size="small" />
            </div>
          </div>

          <div class="cap-note">
            一行就是一项能力：名字下面是它什么时候出现，有颜色的徽章表示要多看一眼（中危 / 高危）。下拉改的是<b>本租户的覆盖档</b>，保存后对下一轮新会话生效，进行中的对话不受影响。
          </div>

          <div v-if="capOpenHigh.length" class="cap-health">
            <span class="ch-dot"></span>
            <span>
              要处理 {{ capOpenHigh.length }} 件事：{{ capOpenHigh.map((r) => r.displayName).join('、')
              }}{{ capOpenHigh.length > 1 ? ` ${capOpenHigh.length} 项高危能力全员可用` : ' 这项高危能力全员可用' }}
            </span>
            <span class="acts">
              <el-button size="small" type="primary" @click="tightenFromHealth">一键收紧</el-button>
              <el-button link size="small" class="health-link" @click="showPresetPreview">先看看会变哪几行</el-button>
            </span>
          </div>
          <div v-else class="cap-health ok2">
            <span class="ch-dot"></span>
            <span>没有要处理的事：能力全部按推荐档位</span>
          </div>

          <div v-if="!tgLoading && tgRows.length === 0" class="cap-empty">
            能力清单加载失败——切换页签重试
          </div>

          <div class="cap-switch">
            <el-radio-group v-model="capMode" size="small">
              <el-radio-button value="rows">按行微调</el-radio-button>
              <el-radio-button value="preset">一键推荐</el-radio-button>
            </el-radio-group>
            <span class="sec-note">改完点保存；有未保存改动时底部会出现保存条</span>
          </div>

          <!-- 一键推荐（3 档策略 × 低/中/高危规则表） -->
          <div v-if="capMode === 'preset'" class="preset-wrap">
            <div class="pol-grid">
              <button
                v-for="p in POLICIES"
                :key="p.key"
                type="button"
                class="pol-card"
                :class="{ on: capPolicy === p.key }"
                @click="setPolicy(p.key)"
              >
                <b>{{ p.name }}<span v-if="p.reco" class="pol-reco">推荐</span></b>
                <p>{{ p.desc }}</p>
              </button>
            </div>
            <div class="preset-sum">
              {{ policyName(capPolicy) }} —— 共 <b>{{ capPreview.length }}</b> 行会变
              <span class="sec-note">（其余 {{ tgRows.length - capPreview.length }} 项已符合该档；业务工具由菜单权限控制，不参与推荐）</span>
            </div>
            <table v-if="capPreview.length" class="preset-table">
              <thead>
                <tr><th class="pt-name">能力</th><th>现在</th><th>改成</th><th class="pt-risk">风险</th></tr>
              </thead>
              <tbody>
                <tr v-for="x in capPreview" :key="x.row.key">
                  <td>{{ x.row.displayName }}</td>
                  <td class="pt-from">{{ x.from }}</td>
                  <td class="pt-to">{{ x.to }}</td>
                  <td><span class="risk-badge" :class="x.row.riskLevel">{{ RISK_LABEL[x.row.riskLevel] }}</span></td>
                </tr>
              </tbody>
            </table>
            <div v-else class="preset-none">当前设置已符合{{ policyName(capPolicy) }}，没有需要改的行。</div>
            <details class="rule-box">
              <summary>规则表（选档即按此推）</summary>
              <table class="rule-table">
                <thead><tr><th>风险</th><th>保守</th><th>标准</th><th>宽松</th></tr></thead>
                <tbody>
                  <tr><td>低危 · 只读</td><td>跟随平台</td><td>跟随平台</td><td>跟随平台</td></tr>
                  <tr><td>中危 · 会落数据</td><td>仅管理员</td><td>跟随平台</td><td>跟随平台</td></tr>
                  <tr><td>高危 · 出网 / 外部服务</td><td>关闭</td><td>仅管理员</td><td>跟随平台</td></tr>
                </tbody>
              </table>
            </details>
            <div class="preset-foot">
              <span class="sec-note">应用前会重新拉一次权限：这期间如果有人改过，就中止并让你复核。改动对下一轮新会话生效。</span>
              <el-button size="small" @click="capPolicy = 'standard'">重置</el-button>
              <el-button size="small" type="primary" :disabled="!capPreview.length" :loading="presetApplying" @click="applyPreset">
                应用{{ policyName(capPolicy) }}（{{ capPreview.length }} 行会变）
              </el-button>
            </div>
          </div>

          <!-- 按行微调：一行一个控件 -->
          <div v-else class="cap-rows">
            <div v-for="g in capGroups" :key="g.name" class="cap-group">
              <div class="cap-group-head">
                {{ g.name }}（{{ g.rows.length }}）
                <span class="colmark">当前生效 → 本租户覆盖</span>
              </div>
              <div v-for="row in g.rows" :key="row.key" class="cap-line" :class="{ 'is-locked': row.locked }">
                <div class="cap-name">
                  <b>{{ row.displayName }}</b>
                  <span class="cap-sub">{{ capSub(row) }}</span>
                </div>
                <span class="cap-risk">
                  <span v-if="row.riskLevel !== 'low'" class="risk-badge" :class="row.riskLevel">
                    {{ RISK_LABEL[row.riskLevel] || '—' }}
                  </span>
                </span>
                <el-select
                  v-if="!row.locked"
                  :model-value="capRowValue(row)"
                  size="small"
                  class="cap-select"
                  @change="setRowValue(row, $event)"
                >
                  <el-option :label="`跟随平台 · ${LEVEL_LABEL[row.builtinLevel] || '成员及以上'}`" value="default" />
                  <el-option label="仅管理员" value="admin" />
                  <el-option label="关闭" value="off" />
                </el-select>
                <span v-else class="cap-locked">锁定：仅管理员</span>
                <el-button link size="small" class="cap-detail" @click="openCapDetail(row)">详情</el-button>
              </div>
            </div>
            <div class="cap-biz-note">
              业务工具（查任务、查待办、待审列表、建任务等）不在这张表里——它们由角色菜单权限控制，见「系统管理 → 角色管理」。
            </div>

            <div class="foldbox" :class="{ open: capNaOpen }">
              <div class="fold-head" @click="capNaOpen = !capNaOpen">
                <span class="arr">▶</span><b>当前不会出现（{{ capNaRows.length }}）</b><span>——展开看原因和怎么开</span>
              </div>
              <div v-show="capNaOpen" class="fold-body">
                <div v-for="row in capNaRows" :key="row.key" class="na-line">
                  <div class="cap-name">
                    <b>{{ row.displayName }}</b>
                    <span class="cap-sub">原因：{{ capCondition(row) }}</span>
                  </div>
                  <span class="risk-badge" :class="row.riskLevel">{{ RISK_LABEL[row.riskLevel] }}</span>
                  <el-button size="small" @click="goRuleMemory(row)">去规则段开启</el-button>
                </div>
              </div>
            </div>

            <div class="foldbox" :class="{ open: capOvOpen }">
              <div class="fold-head" @click="capOvOpen = !capOvOpen">
                <span class="arr">▶</span><b>按角色总览</b><span>——成员 / 能发布任务的人 / 管理员各自能用什么</span>
              </div>
              <div v-show="capOvOpen" class="fold-body">
                <div class="fold-note">手机上左右滑动看全三列；点上面的角色药丸 = 只看这个角色能用的。</div>
                <div class="pills">
                  <button
                    v-for="p in CAP_OV_ROLES"
                    :key="p.v"
                    type="button"
                    :class="{ on: capOvRole === p.v }"
                    @click="capOvRole = p.v"
                  >
                    {{ p.label }}
                  </button>
                </div>
                <div class="cap-matrix-wrap">
                  <table class="cap-matrix">
                    <thead>
                      <tr>
                        <th class="cm-name-col">能力</th>
                        <th>风险</th>
                        <th>成员</th>
                        <th>能发布任务的人</th>
                        <th>管理员</th>
                      </tr>
                    </thead>
                    <tbody>
                      <template v-for="g in capOverviewGroups" :key="g.name">
                        <tr class="grp"><td colspan="5">{{ g.name }}</td></tr>
                        <tr v-for="row in g.rows" :key="row.key" :data-risk="row.riskLevel" @click="openCapDetail(row)">
                          <td>
                            <div class="cm-name"><b>{{ row.displayName }}</b></div>
                          </td>
                          <td>
                            <span v-if="row.riskLevel !== 'low'" class="risk-badge" :class="row.riskLevel">
                              {{ RISK_LABEL[row.riskLevel] }}
                            </span>
                          </td>
                          <td v-for="lv in capRoleLevels" :key="lv" class="cm-cell" :class="capCellClass(row, lv)">
                            {{ capCell(row, lv) }}
                          </td>
                        </tr>
                      </template>
                      <tr v-if="!capOverviewGroups.length">
                        <td colspan="5" class="fold-empty">这个角色当前一项都用不了（或被「只看高危」过滤掉了）</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <div class="fold-note">
                  「能发布任务的人」是介于成员和管理员之间的档位（系统里叫发布人）；单位没做这种分工时，这一列与成员列相同。
                </div>
              </div>
            </div>
          </div>

          <div v-if="tgDirty" class="cap-save">
            <span>有 <b>{{ tgDirtyCount }}</b> 项未保存改动，保存后对下一轮新会话生效</span>
            <el-button size="small" :disabled="tgSaving" @click="resetToolGate">放弃改动</el-button>
            <el-button size="small" type="primary" :loading="tgSaving" @click="onSaveToolGate">保存 {{ tgDirtyCount }} 项</el-button>
          </div>
        </section>

        <section class="adm-sec">
          <div class="sec-head">
            <div class="sec-ttl">
              <b>外部服务</b>
              <span class="sec-cnt">{{ mcpRows.length }} 个</span>
            </div>
            <span class="sec-note">把只读的外部 MCP 服务挂进助手工具面；改动对下一轮新会话生效</span>
          </div>
        <el-card shadow="never" class="adm-card" v-loading="mcpLoading">
          <template #header>
            <div class="card-head">
              <b>已注册的服务</b>
              <span class="head-note">试点只收免密钥的只读服务；本地脚本只允许服务器允许目录内的 node 脚本，网络地址须在白名单</span>
              <el-button size="small" type="primary" @click="openMcpForm()">注册服务</el-button>
            </div>
          </template>
          <el-table :data="mcpRows" size="default" stripe>
            <el-table-column label="服务名" prop="serverName" min-width="130" />
            <el-table-column label="说明" prop="displayName" min-width="120" />
            <el-table-column label="方式" width="110">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.transportType === 'STDIO' ? '本地脚本' : '网络服务' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启动方式" min-width="240">
              <template #default="{ row }">{{ row.transportType === 'STDIO' ? row.command + ' ' + (row.args || '') : row.url }}</template>
            </el-table-column>
            <el-table-column label="请求头键名" width="130">
              <!-- G4 三审回修：后端早回了 headerKeys 但表里没有列——管理员看不到已有键，
                   补一个头会静默丢掉其它鉴权头（值永不回显，只显示键名） -->
              <template #default="{ row }">{{ row.headerKeys || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-switch :model-value="row.enabled === '1'" @change="(v: any) => onToggleMcp(row, v)" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button size="small" link type="primary" @click="openMcpForm(row)">编辑</el-button>
                <el-button size="small" link type="primary" @click="openMcpAudit(row.serverName)">审计</el-button>
                <el-button size="small" link type="danger" @click="onDeleteMcp(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
        </section>

        <!-- ============ 5b 批：技能库（在线编辑层，与总纲同构的目录+正文） ============ -->
        <section class="adm-sec">
          <div class="sec-head">
            <div class="sec-ttl">
              <b>技能库</b>
              <span class="sec-cnt">{{ skillRows.length }} 个</span>
            </div>
            <div class="sec-acts">
              <el-button v-hasPermi="['dcs:assistant:skill:edit']" size="small" type="primary" @click="newSkill">新增技能</el-button>
            </div>
          </div>
          <div class="cap-note">技能是助手按需取用的「操作手册」：目录里只放一句话说明，正文用到才读。改完保存立即生效（下一轮新会话起）；「一句话说明」是助手判断什么时候用这个技能的唯一依据。</div>
          <div class="pd-wrap" v-loading="skillLoading">
            <nav class="pd-toc">
              <button
                v-for="row in skillRows"
                :key="row.skillId"
                type="button"
                :class="{ on: !skillNewMode && activeSkillId === row.skillId }"
                @click="openSkill(row)"
              >
                <i>{{ (row.displayName || row.skillId).slice(0, 1) }}</i>
                <span class="pt">{{ row.displayName || row.skillId }}</span>
                <span class="pf">{{ SKILL_SOURCE_TXT[row.source] || row.source }} · {{ SKILL_ROLE_TXT[row.minRole] }}</span>
              </button>
              <button v-if="skillNewMode" type="button" class="on">
                <i>＋</i>
                <span class="pt">新技能（未保存）</span>
                <span class="pf">在线新增</span>
              </button>
              <div v-if="!skillRows.length && !skillLoading" class="pf faint">技能加载失败或为空——切换页签重试</div>
            </nav>
            <article v-if="skillNewMode || activeSkillRow" class="pd-body sk-edit">
              <div class="pd-meta">
                <b>{{ skillNewMode ? '新增技能' : activeSkillRow?.displayName || activeSkillRow?.skillId }}</b>
                <el-tag v-if="!skillNewMode" size="small" effect="plain" :type="activeSourceKey.startsWith('online') ? 'success' : 'info'">
                  {{ SKILL_SOURCE_TXT[activeSourceKey] || activeSourceKey }}
                </el-tag>
                <span v-if="!skillNewMode" class="pd-file">{{ activeSkillRow?.skillId }}</span>
                <span class="sp" />
                <el-button
                  v-if="!skillNewMode && hasOnlineRow"
                  v-hasPermi="['dcs:assistant:skill:edit']"
                  link
                  type="danger"
                  size="small"
                  @click="onRestoreSkill"
                >
                  {{ isBuiltinRow ? '恢复内置原文' : '删除这个技能' }}
                </el-button>
                <el-button v-if="skillNewMode" link type="info" size="small" @click="cancelNewSkill">取消新增</el-button>
              </div>
              <div class="sk-field">
                <label>技能名</label>
                <el-input v-model="skillForm.displayName" maxlength="32" placeholder="给管理员看的名字，如「催办话术」" />
              </div>
              <div class="sk-field sk-id" v-if="skillNewMode">
                <label>技能标识</label>
                <el-input v-model="skillForm.skillId" placeholder="英文小写/数字/中划线，如 weekly-report；保存后不可改" />
              </div>
              <div class="sk-field">
                <label>谁能用</label>
                <el-select v-model="skillForm.minRole" style="width: 220px">
                  <el-option
                    v-for="opt in SKILL_ROLE_OPTS"
                    :key="opt.v"
                    :value="opt.v"
                    :label="opt.label"
                    :disabled="!skillNewMode && isBuiltinRow && opt.level < builtinRoleLevel"
                  />
                </el-select>
                <span v-if="!skillNewMode && isBuiltinRow" class="sk-hint">内置档「{{ SKILL_ROLE_TXT[activeSkillRow?.builtinMinRole ?? ''] || activeSkillRow?.builtinMinRole }}」起，只能升不能降</span>
              </div>
              <div class="sk-field">
                <label>一句话说明</label>
                <el-input
                  v-model="skillForm.description"
                  type="textarea"
                  :rows="2"
                  maxlength="1024"
                  show-word-limit
                  placeholder="什么时候用这个技能、用完产出什么——助手只看这句决定要不要用它"
                />
                <span class="sk-hint">⬆ 助手判断什么时候用这个技能的唯一依据，写给助手看，不写给管理员看</span>
              </div>
              <div class="sk-field">
                <label>正文（详细做法）</label>
                <el-input v-model="skillForm.body" type="textarea" :rows="12" maxlength="20000" show-word-limit class="sk-body" />
                <span class="sk-hint">只在助手决定用这个技能时才整篇读入，平时不占上下文</span>
              </div>
              <div class="sk-acts">
                <el-button
                  v-hasPermi="['dcs:assistant:skill:edit']"
                  type="primary"
                  size="small"
                  :loading="skillSaving"
                  @click="onSaveSkill()"
                >
                  {{ skillForm.disabled ? '保存（仍停用）' : '保存并生效' }}
                </el-button>
                <el-button
                  v-if="!skillNewMode"
                  v-hasPermi="['dcs:assistant:skill:edit']"
                  size="small"
                  :loading="skillSaving"
                  @click="skillForm.disabled ? enableSkill() : disableSkill()"
                >
                  {{ skillForm.disabled ? '重新启用' : '停用（藏起来）' }}
                </el-button>
                <span class="sk-saved">保存后立即生效：下一轮新会话起，助手目录就换成新内容</span>
              </div>
            </article>
            <el-empty v-else description="技能加载失败或为空" :image-size="56" />
          </div>
        </section>

        <section class="adm-sec">
          <div class="sec-head">
            <div class="sec-ttl">
              <b>助手行为总纲</b>
              <span class="sec-cnt">{{ panoSegments.length }} 段</span>
            </div>
            <div class="sec-acts">
              <el-button size="small" @click="copyFullPrompt">复制完整提示词</el-button>
            </div>
          </div>
          <div class="cap-note">这段文字是助手每次回答前都会读的「岗位说明」——只读，随版本更新。点左边目录看某一段，复制按钮拿到的是按拼装顺序的全篇。</div>
          <div class="pd-wrap" v-loading="panoLoading">
            <nav class="pd-toc">
              <button
                v-for="(seg, i) in panoSegments"
                :key="seg.key"
                type="button"
                :class="{ on: activeSegKey === seg.key }"
                @click="activeSegKey = seg.key"
              >
                <i>{{ i + 1 }}</i>
                <span class="pt">{{ seg.title }}</span>
                <span class="pf">prompts/{{ seg.file }}</span>
              </button>
              <div v-if="!panoSegments.length" class="pf faint">提示词加载中…</div>
            </nav>
            <article v-if="activeSegment" class="pd-body">
              <div class="pd-meta">
                <b>{{ activeSegment.title }}</b>
                <el-tag size="small" effect="plain" :type="activeSegment.source === 'external' ? 'warning' : 'info'">
                  {{ SOURCE_TXT[activeSegment.source] || activeSegment.source }}
                </el-tag>
                <span class="pd-file">prompts/{{ activeSegment.file }}</span>
                <span class="sp" />
                <el-button link type="primary" size="small" @click="copyOneSegment">复制这一段</el-button>
              </div>
              <p class="pd-why">{{ segWhy(activeSegment.key) }}</p>
              <pre class="sys-prompt">{{ activeSegment.content }}</pre>
            </article>
            <el-empty v-else description="提示词加载失败或为空——切换页签重试" :image-size="56" />
          </div>
        </section>
      </div>

      <!-- ============ 规则：租户开关 + 模型 + 额度 ============ -->
      <div v-show="tab === 'rules'" v-if="canConfig" class="adm-pane">
        <section class="adm-sec">
          <div class="sec-head">
            <div class="sec-ttl"><b>租户设置</b></div>
            <span class="sec-note">本租户的总开关与默认值；改完立即生效，影响本租户全部用户</span>
          </div>
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
            <!-- 批A-1（2026-10-22 拍板 Q1/Q3）：自动压缩阈值改为只读派生值——按默认模型
                 上下文窗口自动算（窗口 − 输出预留 − 缓冲），管理页不再手改；用户侧也无手动入口 -->
            <el-descriptions-item label="自动压缩">
              <div class="cell-flex">
                <span class="cell-note">
                  到 {{ fmtK(compactionPolicy.triggerTokens) }} tokens 自动打包前文（按默认模型窗口
                  {{ fmtK(compactionPolicy.contextWindow) }} 自动算<template v-if="!compactionPolicy.derived">
                    · 未解析到模型，按 20 万兜底</template>）
                </span>
                <span class="sp" />
                <el-tooltip
                  placement="top"
                  content="触发线 = 模型上下文窗口 − 模型输出额度（最多留 2.1 万）− 1.3 万缓冲。换个上下文更大的模型，触发线自动跟着变；对用户完全自动，无需手动操作。"
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
            <!-- N3 2026-10-23：每租户只认自己的搜索密钥；未配置=本租户联网搜索不可用（平台兜底已删） -->
            <el-descriptions-item label="联网搜索">
              <div class="cell-flex">
                <el-tag size="small" :type="wsState.configured ? 'success' : 'info'">
                  {{ wsState.configured ? '● 已启用' : '○ 未配置' }}
                </el-tag>
                <span class="cell-note ws-key">{{ wsState.configured ? wsState.maskedKey : '本租户未配置搜索密钥，联网搜索不可用' }}</span>
                <el-button link type="primary" size="small" :loading="wsTesting" :disabled="!wsState.configured" @click="onWsTest">测试连通</el-button>
                <el-button v-hasPermi="['system:config:edit']" link type="primary" size="small" @click="wsReplace.open = true">替换密钥</el-button>
                <span v-if="wsTestMsg" class="cell-note" :class="{ 'ws-fail': !wsTestOk }">{{ wsTestMsg }}</span>
              </div>
              <div class="cell-flex" style="margin-top: 4px">
                <span class="cell-note">每个租户使用自己的博查密钥；未配置时该租户的联网搜索工具不可用，也不会计费。</span>
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
        </section>

        <section class="adm-sec">
          <div class="sec-head">
            <div class="sec-ttl"><b>模型与费用</b></div>
            <span class="sec-note">助手回答用哪个模型、用户能不能自己换</span>
          </div>
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
        </section>

        <section class="adm-sec">
          <div class="sec-head">
            <div class="sec-ttl"><b>每人每天额度</b></div>
            <span class="sec-note">先看租户默认，再给个别用户设单人上限；0 = 关停对应能力</span>
          </div>
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
        </section>
      </div>

      <!-- ============ 运营：审计 + 反馈 + 运行记录 + 公告 ============ -->
      <div v-show="tab === 'ops'" v-if="canConfig || canFeedback" class="adm-pane">
        <section v-if="canConfig" class="adm-sec">
          <div class="sec-head">
            <div class="sec-ttl">
              <b>调用审计</b>
              <span class="sec-cnt">{{ auditStats.real }} 条真实调用</span>
            </div>
            <div class="sec-acts">
              <el-select v-model="mcpLogFilter" clearable placeholder="全部外部服务" size="small" style="width: 160px" @change="reloadMcpLog">
                <el-option v-for="s in mcpRows" :key="s.id" :label="s.serverName" :value="s.serverName" />
              </el-select>
              <el-checkbox v-model="auditShowMounts" label="显示挂载记录" size="small" @change="reloadMcpLog" />
            </div>
          </div>
          <div class="audit-sum">
            <span>全部 <b>{{ auditStats.all }}</b></span>
            <span>真实调用 <b>{{ auditStats.real }}</b></span>
            <span>挂载记录 <b>{{ auditStats.mounts }}</b><em>建会话的例行记录，默认收起</em></span>
          </div>
          <el-card shadow="never" class="adm-card ops-card">
            <el-table :data="mcpLogRows" size="small" stripe :row-class-name="auditRowClass">
              <el-table-column label="时间" width="170">
                <template #default="{ row }">{{ formatMcpTime(row.createTime) }}</template>
              </el-table-column>
              <el-table-column label="服务" prop="serverName" width="120" />
              <el-table-column label="动作" width="110">
                <template #default="{ row }">{{ MCP_ACTION_TXT[row.action] ?? row.action }}</template>
              </el-table-column>
              <el-table-column label="结果" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === '1' ? 'success' : 'danger'">{{ row.status === '1' ? '成功' : '失败' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="90">
                <template #default="{ row }">{{ row.durationMs != null ? row.durationMs + 'ms' : '—' }}</template>
              </el-table-column>
              <el-table-column label="详情/错误" min-width="220">
                <template #default="{ row }">{{ row.errorMsg || '—' }}</template>
              </el-table-column>
              <template #empty>
                <el-empty
                  :description="auditShowMounts ? '还没有调用或挂载记录' : '还没有真实调用——右上角可打开挂载记录'"
                  :image-size="56"
                />
              </template>
            </el-table>
            <div class="mcp-log-pager">
              <el-button size="small" :disabled="mcpLogPage <= 1" @click="mcpLogPage--; loadMcpCallLog()">上一页</el-button>
              <span class="field-hint">第 {{ mcpLogPage }} 页 · 共 {{ mcpLogTotal }} 条</span>
              <el-button size="small" :disabled="mcpLogPage * 20 >= mcpLogTotal" @click="mcpLogPage++; loadMcpCallLog()">下一页</el-button>
            </div>
          </el-card>
        </section>

        <section v-if="canFeedback" class="adm-sec">
          <div class="sec-head">
            <div class="sec-ttl">
              <b>错答反馈</b>
              <span v-if="fbTotal > 0" class="sec-cnt">{{ fbTotal }} 条</span>
            </div>
            <span class="sec-note">用户在回答尾部点「错答」会落到这里，先看原因分布再决定改哪里</span>
          </div>
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
        </section>

        <section v-if="canConfig" class="adm-sec">
          <div class="sec-head">
            <div class="sec-ttl"><b>运行记录</b></div>
            <span class="sec-note">AI 替用户执行过的动作账本（只读）</span>
          </div>
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
        </section>

        <section v-if="canConfig" class="adm-sec">
          <div class="sec-head">
            <div class="sec-ttl"><b>助手公告</b></div>
            <span class="sec-note">发布后用户打开助手即见顶部横幅；同一时刻只显示最新一条</span>
          </div>
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
        </section>
      </div>
    </div>

    <!-- ===== 能力详情抽屉（技术名只在这里出现） ===== -->
    <el-drawer v-model="capDetailOpen" title="能力详情" size="460px" append-to-body class="cap-detail-drawer">
      <div v-if="capDetailRow" class="cd">
        <div class="cd-name">
          <b>{{ capDetailRow.displayName }}</b>
          <span class="risk-badge" :class="capDetailRow.riskLevel">{{ RISK_LABEL[capDetailRow.riskLevel] || '—' }}</span>
        </div>
        <div class="cd-sub">{{ capCondition(capDetailRow) || '无条件，随会话出现' }}</div>
        <div class="cd-sec">
          <h4>这条能力能做什么</h4>
          <p>{{ capDesc(capDetailRow) }}</p>
        </div>
        <div class="cd-sec">
          <h4>权限链路（从上到下逐层收窄）</h4>
          <ol class="cd-chain">
            <li>
              <b>租户总闸</b>
              <span>{{ cfg.enabled ? '助手已开通' : '助手已停用——所有能力都不可用' }}</span>
            </li>
            <li>
              <b>出现条件</b>
              <span>{{ capCondition(capDetailRow) || '无条件，每次会话都会注册' }}</span>
            </li>
            <li>
              <b>平台内置门槛</b>
              <span>{{ LEVEL_LABEL[capDetailRow.builtinLevel] || '成员及以上' }}</span>
            </li>
            <li>
              <b>本租户覆盖</b>
              <span>{{ capOverrideText(capDetailRow) }}</span>
            </li>
            <li>
              <b>最终生效</b>
              <span>{{ capEffText(capDetailRow) }}</span>
            </li>
            <li v-if="capDetailRow.askConfirm">
              <b>执行确认</b>
              <span>属于写入类动作，执行前会先弹确认卡征求用户同意</span>
            </li>
          </ol>
        </div>
        <div class="cd-sec">
          <h4>调整这项能力</h4>
          <div class="cd-quick">
            <el-button
              size="small"
              :type="capRowValue(capDetailRow) === 'default' ? 'primary' : 'default'"
              @click="setRowValue(capDetailRow, 'default')"
            >
              跟随平台 · {{ LEVEL_LABEL[capDetailRow.builtinLevel] || '成员及以上' }}
            </el-button>
            <el-button
              size="small"
              :type="capRowValue(capDetailRow) === 'admin' ? 'primary' : 'default'"
              @click="setRowValue(capDetailRow, 'admin')"
            >
              仅管理员
            </el-button>
            <el-button
              size="small"
              :type="capRowValue(capDetailRow) === 'off' ? 'primary' : 'default'"
              @click="setRowValue(capDetailRow, 'off')"
            >
              关闭
            </el-button>
          </div>
          <div class="cd-quick-note">在这里改和清单里改是同一回事，都会进未保存改动。</div>
        </div>
        <div class="cd-sec">
          <h4>技术信息</h4>
          <div class="cd-kv"><span>内部标识</span><code>{{ capDetailRow.toolNames.join(' / ') }}</code></div>
          <div class="cd-kv"><span>风险档</span>{{ RISK_LABEL[capDetailRow.riskLevel] || '—' }}</div>
        </div>
        <div v-if="capSkillBody(capDetailRow)" class="cd-sec">
          <h4>技能正文（选中这个技能时才注入）</h4>
          <pre class="sys-prompt">{{ capSkillBody(capDetailRow) }}</pre>
        </div>
      </div>
    </el-drawer>

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
        <!-- 注册/编辑抽屉 -->
        <el-drawer v-model="mcpFormVisible" :title="mcpForm.id ? '编辑外部服务' : '注册外部服务'" size="480px">
          <el-form label-width="92px">
            <el-form-item label="服务名" required>
              <el-input v-model="mcpForm.serverName" placeholder="小写字母/数字/下划线，3-30 位，如 weather_demo" :disabled="!!mcpForm.id" />
            </el-form-item>
            <el-form-item label="说明">
              <el-input v-model="mcpForm.displayName" placeholder="如：天气查询（试点）" maxlength="100" />
            </el-form-item>
            <el-form-item label="传输类型" required>
              <el-select v-model="mcpForm.transportType" style="width:100%" :disabled="!!mcpForm.id">
                <el-option label="STDIO（node 脚本，试点推荐）" value="STDIO" />
                <el-option label="STREAMABLE_HTTP" value="STREAMABLE_HTTP" />
                <el-option label="SSE" value="SSE" />
              </el-select>
            </el-form-item>
            <template v-if="mcpForm.transportType === 'STDIO'">
              <el-form-item label="命令">
                <el-input model-value="node" disabled />
              </el-form-item>
              <el-form-item label="脚本路径" required>
                <el-input v-model="mcpForm.argsJson" placeholder='JSON 数组，如 ["D:/.../backend/mcp-demo/weather-server.mjs"]' />
              </el-form-item>
            </template>
            <template v-else>
              <el-form-item label="服务地址" required>
                <el-input v-model="mcpForm.url" placeholder="http://127.0.0.1:3000/mcp（host 须在白名单）" />
              </el-form-item>
            </template>
            <el-form-item v-if="mcpForm.transportType !== 'STDIO'" label="请求头">
              <el-input v-model="mcpForm.headers" :placeholder="mcpHeadersPlaceholder" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="mcpForm.remark" type="textarea" :rows="2" maxlength="255" />
            </el-form-item>
            <el-form-item label="立即启用">
              <el-switch v-model="mcpForm.enabledBool" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="mcpFormVisible = false">取消</el-button>
            <el-button type="primary" :loading="mcpSaving" @click="onSaveMcp">保存</el-button>
          </template>
        </el-drawer>
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
import { computed, nextTick, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { checkPermi } from '@/utils/permission';
import {
  getAssistantConfig,
  getAssistantStatus,
  getAssistantAdminStats,
  getAssistantPanorama,
  getAssistantToolGate,
  saveAssistantToolGate,
  listAssistantMcpServers,
  saveAssistantMcpServer,
  toggleAssistantMcpServer,
  deleteAssistantMcpServer,
  pageAssistantMcpCallLog,
  type AssistantMcpServerRow,
  type AssistantMcpCallLog,
  listAssistantSkillsAdmin,
  saveAssistantSkillAdmin,
  deleteAssistantSkillAdmin,
  type AssistantSkillAdminRow,
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
  type AssistantCompactionPolicyView,
  type AssistantPanoramaTool,
  type AssistantPanoramaSkill,
  type AssistantToolGateRow,
  type AssistantQuotaRow,
  getWebsearchInfo,
  testWebsearch,
  getAssistantHealth,
  type AssistantHealthReport
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

/** 四段导航：总览 / 能力 / 规则 / 运营（原 8 个平铺页签，同类项合一） */
const tab = ref('overview');
/** 权限闸（模板里多处复用，集中成 computed 避免逐处 checkPermi） */
const canConfig = computed(() => checkPermi(['dcs:assistant:config']));
const canFeedback = computed(() => checkPermi(['dcs:assistant:feedback:query']));
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
    const res = (await getAssistantConfig()) as unknown as {
      data?: { effective?: AssistantEffectiveConfig; compaction?: AssistantCompactionPolicyView };
    };
    const e = res?.data?.effective ?? (res as { effective?: AssistantEffectiveConfig })?.effective;
    if (e) {
      cfg.value = e;
      // 快刀批 Q6：称呼同步喂全局态（本页保存后悬浮球/抽屉即时换名，无需整页刷新）
      applyAssistantName(e.assistantName);
    }
    const p = res?.data?.compaction ?? (res as { compaction?: AssistantCompactionPolicyView })?.compaction;
    if (p) {
      compactionPolicy.value = p;
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

/* ===== 批A-1：压缩策略=只读派生（后端 /config 下发 compaction 块；本页不提供写入口） ===== */
const compactionPolicy = ref<AssistantCompactionPolicyView>({
  derived: false,
  contextWindow: 200000,
  triggerTokens: 166000,
  keepMessages: 2
});
function fmtK(n?: number | null): string {
  if (!n || n <= 0) {
    return '—';
  }
  return n >= 1000 ? Math.round(n / 1000) + 'K' : String(n);
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

/* ===== 稳流② Q8②：运行健康卡（run 终态分布+追问失败分类；加载失败静默隐藏数字） ===== */
const healthLoading = ref(false);
const health = ref<AssistantHealthReport>({ since: '', runOutcomes: {}, followupOutcomes: {} });
const healthSince = computed(() => (health.value.since ? health.value.since.replace('T', ' ').slice(0, 16) : '—'));
async function loadHealth() {
  healthLoading.value = true;
  try {
    const res = (await getAssistantHealth()) as unknown as { data?: AssistantHealthReport };
    const d = res?.data;
    if (d) {
      health.value = d;
    }
  } catch {
    /* 静默：卡片留「暂无数据」，不拖垮管理页 */
  } finally {
    healthLoading.value = false;
  }
}
/** run 终态 → 人话标签（key=status|reason；后端实值=COMPLETED/INTERRUPTED/FAILED——
 *  glm53 审查 P1-1 实证：旧版按 FINISHED 匹配永不命中，主桶全废） */
function runLabel(k: string): string {
  const [status, reason] = k.split('|');
  if (status === 'COMPLETED') return '正常完成';
  if (status === 'INTERRUPTED') {
    const why = reason === 'client-disconnect' ? '连接断开' : reason === 'zero-event' ? '无响应' : reason === 'interrupt' ? '等用户确认' : reason ?? '-';
    return `中断（${why}）`;
  }
  if (status === 'FAILED') return `失败（${reason ?? '-'}）`;
  return k;
}
function runTone(k: string): string {
  const s = k.split('|')[0];
  return s === 'COMPLETED' ? 'ok' : s === 'FAILED' ? 'bad' : 'mid';
}
/** 追问分类 → 人话标签 */
const FU_LABELS: Record<string, string> = {
  succeeded: '已生成',
  'answer-too-short': '回答太短跳过',
  'pool-rejected': '排队已满丢弃',
  'budget-gated': '超预算不发',
  'empty-raw': '模型空回复',
  'parse-failed': '内容解析失败',
  timeout: '生成超时',
  other: '其他失败'
};
function fuLabel(k: string): string {
  return FU_LABELS[k] ?? k;
}
function fuTone(k: string): string {
  return k === 'succeeded' ? 'ok' : k === 'answer-too-short' || k === 'budget-gated' ? 'mid' : 'bad';
}

/* ===== 能力数据源（能力清单 + 行为总纲） ===== */
const panoLoading = ref(false);
const panoTools = ref<AssistantPanoramaTool[]>([]);
const panoSysPrompt = ref('');
/** 技能正文（清单里点详情看，不再单独占一张卡） */
const panoSkills = ref<AssistantPanoramaSkill[]>([]);
/** 行为总纲分段（来源 md 文件+只读内容，随版本更新） */
interface AssistantPromptSegment {
  key: string;
  title: string;
  file: string;
  source: string;
  content: string;
}
const panoSegments = ref<AssistantPromptSegment[]>([]);
const SOURCE_TXT: Record<string, string> = { external: '外部覆写', classpath: '随版文件', embedded: '内嵌兜底' };
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
    ElMessage.error('能力数据加载失败，请稍后再试');
  } finally {
    panoLoading.value = false;
  }
}

/* ===== 行为总纲：目录 + 正文（题 4），复制走系统剪贴板 ===== */
const activeSegKey = ref('');
const activeSegment = computed(
  () => panoSegments.value.find((s) => s.key === activeSegKey.value) ?? panoSegments.value[0] ?? null
);
watch(
  panoSegments,
  (list) => {
    if (!activeSegKey.value && list.length) activeSegKey.value = list[0].key;
  },
  { immediate: true }
);
/** 「这段管什么」——目录上一句话，避免管理员点开八段才知道哪段讲什么 */
const SEG_WHY: Record<string, string> = {
  role: '告诉助手它是谁、说话什么调子——所有回答的底色。',
  discipline: '业务数据纪律：拿不到真数据就说不清，不许编。',
  'discipline-urge': '催办时的分寸：说事不说人、给出路。',
  layout: '回答怎么排版：日期写 MM-DD、用全角括号、别堆一堆小标题。',
  cheatsheet: '工具速查：什么时候该用哪个工具、参数怎么填。',
  routes: '站内链接怎么给：用户说「打开任务页」时给可点的直达链接。',
  chart: '统计图协议：什么时候画图、画什么图、数据从哪来。',
  memory: '长期记忆的存取规矩：记什么、不记什么、怎么用。'
};
function segWhy(key: string): string {
  return SEG_WHY[key] || '这一段是助手的行为约定，随版本更新。';
}
async function copyText(text: string, okMsg: string) {
  if (!text) {
    ElMessage.warning('这段内容还没加载出来');
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(okMsg);
  } catch {
    ElMessage.warning('复制失败——请手动选中文本复制');
  }
}
function copyFullPrompt() {
  void copyText(panoSysPrompt.value || panoSegments.value.map((s) => s.content).join('\n\n'), '已复制完整提示词');
}
function copyOneSegment() {
  if (activeSegment.value) void copyText(activeSegment.value.content, '已复制这一段');
}

/* ===== 总览：需要处理（只列会影响用户的事，点一下直达对应段） ===== */
const attentionItems = computed(() => {
  const out: Array<{ level: string; title: string; desc: string; action: string; tab: string }> = [];
  if (!cfg.value.enabled) {
    out.push({ level: 'bad', title: '助手是停用状态', desc: '所有用户看不到入口，发消息会被直接拒绝。', action: '去开通', tab: 'rules' });
  }
  if (cfg.value.memoryEnabled !== '1') {
    out.push({ level: 'warn', title: '长期记忆是关闭的', desc: '保存长期记忆这项能力不会出现，用户偏好不会跨会话保留。', action: '去开启', tab: 'rules' });
  }
  const openHigh = tgRows.value.filter((r) => r.riskLevel === 'high' && capAvailable(r) && capLevelOf(r, tgDraft.value) === 0);
  if (openHigh.length) {
    out.push({
      level: 'warn',
      title: `${openHigh.length} 项高危能力对全员开放`,
      desc: `${openHigh.map((r) => r.displayName).join('、')}——出网或接外部服务，建议收到仅管理员。`,
      action: '去收紧',
      tab: 'caps'
    });
  }
  if (auditStats.value.mounts > 0) {
    out.push({
      level: 'info',
      title: '调用记录里大部分是挂载记录',
      desc: `${auditStats.value.all} 条里 ${auditStats.value.mounts} 条是每次建会话的例行挂载，真正的调用只有 ${auditStats.value.real} 条。`,
      action: '看真实调用',
      tab: 'ops'
    });
  }
  const offMcp = mcpRows.value.filter((s) => s.enabled !== '1');
  if (offMcp.length) {
    out.push({ level: 'info', title: `${offMcp.length} 个外部服务已停用`, desc: '对应的能力当前不会出现在会话里。', action: '去看看', tab: 'caps' });
  }
  return out;
});
/** 总览的「去看看」/段内互跳：切段并补一次懒加载 */
function jumpTo(name: string) {
  tab.value = name;
  onTabChange(name);
}

/* ===== 能力清单（原「能力全景」+「工具开关」合并）：一处说清会什么 / 谁能用 / 哪项危险 ===== */
/** 行 UI 模型：key=多工具同档的合并键（详情/反查身份） */
interface TgRowUi extends AssistantToolGateRow {
  key: string;
}
interface CapGroup {
  name: string;
  rows: TgRowUi[];
}
const RISK_LABEL: Record<string, string> = { high: '高危', mid: '中危', low: '低危' };
/** 生效档人话：0=成员及以上（不再造「全员可用」第二个说法） */
const LEVEL_LABEL: Record<number, string> = { 0: '成员及以上', 1: '发布人及以上', 2: '仅管理员' };
const capRoleLevels = [0, 1, 2];

const tgLoading = ref(false);
const tgSaving = ref(false);
const tgRows = ref<TgRowUi[]>([]);
/** 已保存覆盖快照（工具名→off/admin；无键=跟随平台） */
const tgSaved = ref<Record<string, string>>({});
/** 草稿覆盖——保存时整份提交（后端全量覆盖语义，空表=全恢复默认） */
const tgDraft = ref<Record<string, string>>({});
const capHighOnly = ref(false);
const capMode = ref<'rows' | 'preset'>('rows');
/** 折叠组开合（默认收起）与按角色总览的药丸筛选 */
const capNaOpen = ref(false);
const capOvOpen = ref(false);
const capOvRole = ref<'all' | '0' | '1' | '2'>('all');
const CAP_OV_ROLES = [
  { v: 'all', label: '全部' },
  { v: '0', label: '成员' },
  { v: '1', label: '能发布任务的人' },
  { v: '2', label: '管理员' }
] as const;
type CapPolicy = 'conservative' | 'standard' | 'open';
const capPolicy = ref<CapPolicy>('standard');
const presetApplying = ref(false);

const tgDirtyCount = computed(() =>
  tgRows.value.filter((r) => (tgDraft.value[r.toolNames[0]] ?? '') !== (tgSaved.value[r.toolNames[0]] ?? '')).length
);
const tgDirty = computed(() => tgDirtyCount.value > 0);

/** 生效档核心（与服务端 effectiveMinRoleSingle 同口径）：off=-1；admin=max(builtin,2)；
 *  member 与未配置同效——等级 0 就是成员档，所以「全员可用」覆盖项数学上是空操作，UI 不再提供 */
function capLevelOf(row: TgRowUi, map: Record<string, string>): number {
  const v = map[row.toolNames[0]] ?? '';
  if (v === 'off') return -1;
  if (v === 'admin') return Math.max(row.builtinLevel, 2);
  return row.builtinLevel;
}
function capRowValue(row: TgRowUi): string {
  // 'default' 是下拉的哨兵值（'' 会被 el-select 当成空值显示占位符）
  return tgDraft.value[row.toolNames[0]] || 'default';
}
function capEffText(row: TgRowUi): string {
  const e = capLevelOf(row, tgDraft.value);
  if (e < 0) return '本租户已关闭';
  return LEVEL_LABEL[e] ?? '成员及以上';
}
/** 多工具 spec 同档共进退：设值写到全部工具名（effectiveMinRole 任一命中即生效，全写避免先命中的残留旧值） */
function setRowValue(row: TgRowUi, v: string) {
  // 'default'=跟随平台 → 删除覆盖键（后端整列覆盖语义：空表恢复默认）
  const val = v === 'default' ? '' : v;
  const next = { ...tgDraft.value };
  for (const n of row.toolNames) {
    if (val) next[n] = val;
    else delete next[n];
  }
  tgDraft.value = next;
}

/** 分组：平台工具（查询+交互）/ 技能 / 挂进来的外部服务；配置未开的能力收进独立折叠组，不进主清单 */
const CAP_CAT_GROUP: Record<string, string> = { 查询: '平台工具', 交互: '平台工具', 技能: '技能', 外部工具: '挂进来的外部服务' };
const capGroups = computed<CapGroup[]>(() => {
  const order = ['平台工具', '技能', '挂进来的外部服务'];
  const map = new Map<string, TgRowUi[]>();
  for (const r of tgRows.value) {
    if (!capAvailable(r)) continue;
    if (capHighOnly.value && r.riskLevel !== 'high') continue;
    const name = CAP_CAT_GROUP[r.category] ?? r.category;
    const list = map.get(name) ?? [];
    list.push(r);
    map.set(name, list);
  }
  const out = order.filter((n) => map.has(n)).map((name) => ({ name, rows: map.get(name) as TgRowUi[] }));
  for (const [name, rows] of map) {
    if (!order.includes(name)) out.push({ name, rows });
  }
  return out;
});

/** 出现条件（人话）：配置没开/服务没起时说清「当前不会出现」，别让管理员对着开关猜 */
function capCondition(row: TgRowUi): string {
  const first = row.toolNames[0];
  if (first === 'web_search') return wsState.configured ? '会话开启联网后出现' : '本租户未配搜索密钥，当前不会出现';
  if (first === 'save_memory') return cfg.value.memoryEnabled === '1' ? '租户已开启长期记忆' : '租户未开启长期记忆，当前不会出现';
  if (first.startsWith('mcp:')) {
    const s = mcpRows.value.find((x) => `mcp:${x.serverName}` === first);
    if (!s) return '服务已删除，当前不会出现';
    return s.enabled === '1' ? '服务在线时出现' : '服务已停用，当前不会出现';
  }
  return row.gateDesc || '';
}
function capAvailable(row: TgRowUi): boolean {
  return !capCondition(row).includes('不会出现');
}
/** 矩阵格子：先看配置是否让这项能力出现，再按生效档比角色等级 */
function capCell(row: TgRowUi, roleLevel: number): string {
  if (!capAvailable(row)) return '随配置';
  const e = capLevelOf(row, tgDraft.value);
  if (e < 0) return '×';
  return roleLevel >= e ? '✓' : '×';
}
function capCellClass(row: TgRowUi, roleLevel: number): string {
  if (!capAvailable(row)) return 'na';
  const e = capLevelOf(row, tgDraft.value);
  if (e < 0) return 'no';
  return roleLevel >= e ? 'yes' : 'no';
}
const capAvailCount = computed(() => tgRows.value.filter((r) => capAvailable(r)).length);
/** 低危行不挂徽章，改在条件小字里交代（中危/高危仍有徽章） */
function capSub(row: TgRowUi): string {
  const base = capCondition(row) || (row.askConfirm ? '执行前会弹确认卡' : '自动执行');
  return row.riskLevel === 'low' ? `${base} · 低危（只读）` : base;
}
/** 体检句的唯一来源：还有哪些高危能力对全员开放（处理完 = 无事） */
const capOpenHigh = computed(() =>
  tgRows.value.filter((r) => r.riskLevel === 'high' && capAvailable(r) && capLevelOf(r, tgDraft.value) === 0)
);
/** 当前不会出现的配置缺项（含未开启的能力与服务已停的外部服务） */
const capNaRows = computed(() => tgRows.value.filter((r) => !capAvailable(r)));
/** 按角色总览的矩阵行：随「只看高危」与角色药丸过滤 */
const capOverviewGroups = computed<CapGroup[]>(() => {
  const order = ['平台工具', '技能', '挂进来的外部服务'];
  const map = new Map<string, TgRowUi[]>();
  for (const r of tgRows.value) {
    if (capHighOnly.value && r.riskLevel !== 'high') continue;
    if (capOvRole.value !== 'all') {
      // 选定角色时只留「这个角色真的能用」的：配置未开的行不出现，不算能用
      if (!capAvailable(r)) continue;
      const e = capLevelOf(r, tgDraft.value);
      if (e < 0 || Number(capOvRole.value) < e) continue;
    }
    const name = CAP_CAT_GROUP[r.category] ?? r.category;
    const list = map.get(name) ?? [];
    list.push(r);
    map.set(name, list);
  }
  const out = order.filter((n) => map.has(n)).map((name) => ({ name, rows: map.get(name) as TgRowUi[] }));
  for (const [name, rows] of map) {
    if (!order.includes(name)) out.push({ name, rows });
  }
  return out;
});
/** 体检条的两个动作：一键收紧 = 切到推荐的保守档预览；先看看 = 打开一键推荐面板 */
function showPresetPreview() {
  capMode.value = 'preset';
  void nextTick(() => document.querySelector('.preset-wrap')?.scrollIntoView({ block: 'start', behavior: 'smooth' }));
}
function tightenFromHealth() {
  capPolicy.value = 'conservative';
  showPresetPreview();
}
/** 灰行的指路：长期记忆回规则段开，其余外部服务在本页下方启停 */
function goRuleMemory(row: TgRowUi) {
  if (row.toolNames[0] === 'save_memory') {
    jumpTo('rules');
    ElMessage.info('在「规则」页签打开长期记忆开关');
    return;
  }
  ElMessage.info('在下方「外部服务」里启用对应服务');
}

/* ===== 一键推荐（题 8 乙）：3 档策略 × 风险档规则表，预览差异后整列覆盖 ===== */
const POLICIES = [
  { key: 'conservative', name: '保守档', desc: '只读按平台；会落数据的能力收管理员；出网 / 外部服务关掉', reco: false },
  { key: 'standard', name: '标准档', desc: '只读与写操作按平台；出网 / 外部服务收管理员', reco: true },
  { key: 'open', name: '宽松档', desc: '全部按平台默认，不再额外收紧', reco: false }
] as const;
function setPolicy(k: string) {
  capPolicy.value = k as CapPolicy;
}
function policyName(k: CapPolicy): string {
  return POLICIES.find((p) => p.key === k)?.name ?? '';
}
/** 目标生效档：低危恒跟随平台；中危/高危按策略收紧 */
function capTargetLevel(row: TgRowUi, policy: CapPolicy): number {
  const base = row.builtinLevel;
  if (row.riskLevel === 'high') {
    return policy === 'conservative' ? -1 : policy === 'standard' ? Math.max(base, 2) : base;
  }
  if (row.riskLevel === 'mid') {
    return policy === 'conservative' ? Math.max(base, 2) : base;
  }
  return base;
}
function capTargetOverride(row: TgRowUi, target: number): string {
  if (target < 0) return 'off';
  if (target > row.builtinLevel) return 'admin';
  return '';
}
const capPreview = computed(() =>
  tgRows.value
    .map((row) => ({
      row,
      fromLevel: capLevelOf(row, tgSaved.value),
      toLevel: capTargetLevel(row, capPolicy.value)
    }))
    .filter((x) => x.fromLevel !== x.toLevel)
    .map((x) => ({
      ...x,
      from: x.fromLevel < 0 ? '已关闭' : LEVEL_LABEL[x.fromLevel] ?? '—',
      to: x.toLevel < 0 ? '关闭' : LEVEL_LABEL[x.toLevel] ?? '—'
    }))
);
/** 快照比对：后端当前覆盖表与页面快照是否一致（忽略 member——它与默认同效） */
function sameSaved(rows: AssistantToolGateRow[]): boolean {
  const fresh: Record<string, string> = {};
  for (const r of rows) {
    if (r.override && r.override !== 'member') for (const n of r.toolNames) fresh[n] = r.override;
  }
  const keys = new Set([...Object.keys(fresh), ...Object.keys(tgSaved.value)]);
  for (const k of keys) {
    if ((fresh[k] ?? '') !== (tgSaved.value[k] ?? '')) return false;
  }
  return true;
}
/** 应用到全部能力：三道闸（草稿覆盖确认 → 重拉比对防并发 → 生效口径确认）后整列覆盖 */
async function applyPreset() {
  const preview = capPreview.value;
  if (!preview.length) return;
  if (tgDirty.value) {
    try {
      await ElMessageBox.confirm(
        `你还有 ${tgDirtyCount.value} 项手工改动没保存，应用推荐会覆盖它们。继续？`,
        '覆盖手工改动',
        { type: 'warning', confirmButtonText: '继续应用', cancelButtonText: '先取消' }
      );
    } catch {
      return;
    }
  }
  presetApplying.value = true;
  try {
    const res = (await getAssistantToolGate()) as unknown as { data?: AssistantToolGateRow[] };
    if (!sameSaved(res.data ?? [])) {
      await loadToolGate();
      ElMessage.warning('刚有人改过权限——已刷新，请重看「会变几行」再应用');
      return;
    }
    try {
      await ElMessageBox.confirm(
        `按${policyName(capPolicy.value)}改 ${preview.length} 项能力。改动对下一轮新会话生效，进行中的对话不受影响。`,
        '应用推荐',
        { type: 'info', confirmButtonText: `应用 ${preview.length} 项`, cancelButtonText: '再看看' }
      );
    } catch {
      return;
    }
    const next: Record<string, string> = { ...tgSaved.value };
    for (const x of preview) {
      const v = capTargetOverride(x.row, x.toLevel);
      for (const n of x.row.toolNames) {
        if (v) next[n] = v;
        else delete next[n];
      }
    }
    await saveAssistantToolGate(next);
    await loadToolGate();
    capMode.value = 'rows';
    ElMessage.success(`已应用——${preview.length} 项按${policyName(capPolicy.value)}生效，下一轮新会话起作用`);
  } catch {
    // 值域/工具名校验的人话报错已由 request 层弹出
  } finally {
    presetApplying.value = false;
  }
}

/* ===== 能力详情抽屉：技术名只在这里出现 ===== */
const capDetailOpen = ref(false);
const capDetailRow = ref<TgRowUi | null>(null);
function openCapDetail(row: TgRowUi) {
  capDetailRow.value = row;
  capDetailOpen.value = true;
}
/** 平台工具的「能做什么」人话说明（技能/外部服务/业务工具走各自数据） */
const CAP_DESC: Record<string, string> = {
  web_search: '在公开网页里搜最新信息；每搜一次算一次联网额度，出网内容先过安全过滤。',
  read_attachment: '读当前会话里用户上传的附件内容（只读自己会话的文件）。',
  submit_plan: '把要做的事列成一张计划卡给用户确认，没有副作用。',
  todo_write: '维护本次会话的任务清单（整表替换式），用户在侧栏能看到进度。',
  save_memory: '把用户偏好写成长期记忆，跨会话保留；用户可在「AI 助手记忆」页删除。',
  view_skill: '模型按需自取某个技能的详细做法（技能正文只在需要时读取，不常驻）。'
};
function capDesc(row: TgRowUi): string {
  const first = row.toolNames[0];
  if (CAP_DESC[first]) return CAP_DESC[first];
  const sk = panoSkills.value.find((s) => first === `skill:${s.id}`);
  if (sk) return sk.description || '技能：把某类任务的做法写成固定步骤。';
  if (first.startsWith('mcp:')) return '外部 MCP 服务提供的工具，按服务声明的只读工具面挂载。';
  return row.gateDesc || '这项能力没有额外说明。';
}
function capSkillBody(row: TgRowUi): string {
  const first = row.toolNames[0];
  return panoSkills.value.find((s) => first === `skill:${s.id}`)?.body || '';
}
function capOverrideText(row: TgRowUi): string {
  const v = tgDraft.value[row.toolNames[0]] ?? '';
  if (!v) return '未覆盖，跟随平台内置档';
  if (v === 'off') return '本租户设为关闭';
  if (v === 'admin') return '本租户设为仅管理员';
  return '旧值「全员可用」（与跟随平台同效）';
}
async function loadToolGate() {
  tgLoading.value = true;
  try {
    const res = (await getAssistantToolGate()) as unknown as { data?: AssistantToolGateRow[] };
    const rows = res.data ?? [];
    tgRows.value = rows.map((r) => ({ ...r, key: r.toolNames.join('/') }));
    const saved: Record<string, string> = {};
    for (const r of rows) {
      // member 与默认同效（等级 0 就是成员档），归一掉——页面不再显示一个改了等于没改的选项
      if (r.override && r.override !== 'member') for (const n of r.toolNames) saved[n] = r.override;
    }
    tgSaved.value = saved;
    tgDraft.value = { ...saved };
  } catch {
    // 具体错误 request 层已弹；失败态由页面空态文案兜底（切换页签可重试）
  } finally {
    tgLoading.value = false;
  }
}
function resetToolGate() {
  tgDraft.value = { ...tgSaved.value };
}
async function onSaveToolGate() {
  tgSaving.value = true;
  try {
    await saveAssistantToolGate(tgDraft.value);
    tgSaved.value = { ...tgDraft.value };
    ElMessage.success('已保存——下一轮新会话起生效');
  } catch {
    // 值域/工具名校验的人话报错已由 request 层弹出，这里不再叠一层通用文案
  } finally {
    tgSaving.value = false;
  }
}

/* ===== 外部工具页签（批2 Q2=A）：MCP 试点服务注册 + 调用审计 ===== */
const mcpLoading = ref(false);
const mcpSaving = ref(false);
const mcpRows = ref<AssistantMcpServerRow[]>([]);
const mcpFormVisible = ref(false);
const mcpForm = reactive({
  id: '',
  serverName: '',
  displayName: '',
  transportType: 'STDIO' as 'STDIO' | 'STREAMABLE_HTTP' | 'SSE',
  argsJson: '[""]',
  url: '',
  headers: '',
  /** 编辑时已有的请求头键名（提示用——值永不回显；改 headers 须整份重填） */
  headerKeysHint: '',
  remark: '',
  enabledBool: true
});
/** G4 三审回修：请求头占位提示——编辑时展示已有键名（值永不回显），改须整份重填。
 *  放 computed（含双引号示例 JSON，进模板属性会截断——用户实测 vite 报错教训） */
const mcpHeadersPlaceholder = computed(() =>
  mcpForm.id && mcpForm.headerKeysHint
    ? `已有键：${mcpForm.headerKeysHint}。留空保持不变；要改须整份重填（含未变的键）`
    : '可选 JSON 对象，如 {"Authorization":"Bearer ..."}；值加密存储、列表只显示键名');
const mcpLogRows = ref<AssistantMcpCallLog[]>([]);
const mcpLogTotal = ref(0);
const mcpLogPage = ref(1);
const mcpLogFilter = ref('');
/** 默认只看真实调用与失败——挂载成功/失败是每次建会话的例行记录，会把真正要看的一屏淹掉 */
const auditShowMounts = ref(false);
const auditStats = ref({ all: 0, mounts: 0, real: 0 });
const MCP_ACTION_TXT: Record<string, string> = {
  MOUNT_OK: '挂载成功',
  MOUNT_ERROR: '挂载失败',
  CALL_OK: '调用成功',
  CALL_ERROR: '调用失败',
  RATE_LIMITED: '触发限频',
  // G3-A1：elicitation 反问全生命周期（ask=挂起提问；终态=用户三态作答或超时关闭）
  ELICIT_ASK: '反问用户',
  ELICIT_ACCEPT: '反问已作答',
  ELICIT_DECLINE: '反问被拒绝',
  ELICIT_CANCEL: '反问被关闭',
  ELICIT_TIMEOUT: '反问超时关闭'
};
async function loadMcpServers() {
  mcpLoading.value = true;
  try {
    const res = (await listAssistantMcpServers()) as unknown as { data?: AssistantMcpServerRow[] };
    mcpRows.value = res.data ?? [];
  } catch {
    // 具体错误 request 层已弹；空表即空态
  } finally {
    mcpLoading.value = false;
  }
}
async function loadMcpCallLog() {
  try {
    const res = (await pageAssistantMcpCallLog({
      serverName: mcpLogFilter.value || undefined,
      realOnly: !auditShowMounts.value,
      pageNum: mcpLogPage.value,
      pageSize: 20
    })) as unknown as {
      data?: { rows?: AssistantMcpCallLog[]; total?: number; stats?: { all: number; mounts: number; real: number } };
    };
    mcpLogRows.value = res.data?.rows ?? [];
    mcpLogTotal.value = res.data?.total ?? 0;
    if (res.data?.stats) auditStats.value = res.data.stats;
  } catch {
    // 同上：request 层已弹
  }
}
/** 切换「显示挂载记录」/换服务：回到第 1 页再拉 */
function reloadMcpLog() {
  mcpLogPage.value = 1;
  void loadMcpCallLog();
}
/** 外部服务行里的「审计」：带着服务名跳到运营段 */
function openMcpAudit(serverName: string) {
  mcpLogFilter.value = serverName;
  mcpLogPage.value = 1;
  tab.value = 'ops';
  void loadMcpCallLog();
}
/** 失败行标红：真实调用里最该看的就是失败 */
function auditRowClass({ row }: { row: AssistantMcpCallLog }): string {
  return row.status === '1' ? '' : 'audit-fail';
}
function openMcpForm(row?: AssistantMcpServerRow) {
  if (row) {
    mcpForm.id = row.id;
    mcpForm.serverName = row.serverName;
    mcpForm.displayName = row.displayName ?? '';
    mcpForm.transportType = row.transportType;
    mcpForm.argsJson = row.args ?? '[""]';
    mcpForm.url = row.url ?? '';
    mcpForm.headers = '';
    mcpForm.headerKeysHint = row.headerKeys ?? '';
    mcpForm.remark = row.remark ?? '';
    mcpForm.enabledBool = row.enabled === '1';
  } else {
    Object.assign(mcpForm, { id: '', serverName: '', displayName: '', transportType: 'STDIO', argsJson: '[""]', url: '', headers: '', headerKeysHint: '', remark: '', enabledBool: true });
  }
  mcpFormVisible.value = true;
}
async function onSaveMcp() {
  mcpSaving.value = true;
  try {
    await saveAssistantMcpServer({
      id: mcpForm.id || undefined,
      serverName: mcpForm.serverName.trim(),
      displayName: mcpForm.displayName.trim() || null,
      transportType: mcpForm.transportType,
      command: mcpForm.transportType === 'STDIO' ? 'node' : null,
      args: mcpForm.transportType === 'STDIO' ? mcpForm.argsJson.trim() : null,
      url: mcpForm.transportType === 'STDIO' ? null : mcpForm.url.trim(),
      headers: mcpForm.headers.trim() || null,
      remark: mcpForm.remark.trim() || null,
      enabled: mcpForm.enabledBool ? '1' : '0'
    });
    mcpFormVisible.value = false;
    await loadMcpServers();
    await loadMcpCallLog();
    ElMessage.success('已保存——下一轮新会话起挂载');
  } catch {
    // 白名单人话报错 request 层已弹，抽屉保留输入可改
  } finally {
    mcpSaving.value = false;
  }
}
async function onToggleMcp(row: AssistantMcpServerRow, on: boolean) {
  // 乐观更新防连点弹跳：先本地置态，失败回滚
  const prev = row.enabled;
  row.enabled = on ? '1' : '0';
  try {
    await toggleAssistantMcpServer(row.id, on ? '1' : '0');
    await loadMcpCallLog();
    ElMessage.success(on ? '已启用——下一轮新会话挂载' : '已停用——新会话不再挂载');
  } catch {
    row.enabled = prev;
  }
}
async function onDeleteMcp(row: AssistantMcpServerRow) {
  try {
    await ElMessageBox.confirm(
      `删除服务「${row.serverName}」？已挂载会话不受影响，新会话不再挂载。审计记录保留。`,
      '删除外部服务',
      { type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    await deleteAssistantMcpServer(row.id);
    await loadMcpServers();
    await loadMcpCallLog();
    ElMessage.success('已删除');
  } catch {
    // request 层已弹
  }
}
function formatMcpTime(t: string): string {
  if (!t) return '—';
  const d = new Date(Number(t));
  return isNaN(d.getTime()) ? t : d.toLocaleString('zh-CN', { hour12: false });
}

/* ===== 5b 批：技能库在线编辑（能力段；与总纲同构的目录+正文，但可编辑） ===== */
const SKILL_SOURCE_TXT: Record<string, string> = {
  builtin: '随版内置',
  'online-overwrite': '在线覆写',
  'online-new': '在线新增',
  'builtin-disabled': '内置·已停用',
  'online-disabled': '新增·已停用'
};
const SKILL_ROLE_TXT: Record<string, string> = { member: '成员及以上', publisher: '发布人及以上', admin: '仅管理员' };
const SKILL_ROLE_LEVEL: Record<string, number> = { member: 0, publisher: 1, admin: 2 };
const SKILL_ROLE_OPTS = [
  { v: 'member', label: '成员及以上', level: 0 },
  { v: 'publisher', label: '发布人及以上', level: 1 },
  { v: 'admin', label: '仅管理员', level: 2 }
] as const;

const skillLoading = ref(false);
const skillSaving = ref(false);
const skillRows = ref<AssistantSkillAdminRow[]>([]);
const activeSkillId = ref('');
const skillNewMode = ref(false);
const skillForm = reactive({
  skillId: '',
  displayName: '',
  description: '',
  minRole: 'member' as 'member' | 'publisher' | 'admin',
  body: '',
  disabled: false
});
const activeSkillRow = computed(() => skillRows.value.find((r) => r.skillId === activeSkillId.value) ?? null);
const activeSourceKey = computed(() => activeSkillRow.value?.source ?? 'online-new');
/** 在线行存在=可恢复内置/删除；内置基线存在=「谁能用」只升不降约束生效 */
const hasOnlineRow = computed(() => !!activeSkillRow.value && activeSkillRow.value.source !== 'builtin');
const isBuiltinRow = computed(() => !!activeSkillRow.value && !!activeSkillRow.value.builtinMinRole);
const builtinRoleLevel = computed(() => SKILL_ROLE_LEVEL[activeSkillRow.value?.builtinMinRole ?? 'member'] ?? 0);

async function loadSkillAdmin() {
  skillLoading.value = true;
  try {
    const res = (await listAssistantSkillsAdmin()) as unknown as { data?: AssistantSkillAdminRow[] };
    skillRows.value = res?.data ?? [];
    if (!skillNewMode.value) {
      if (skillRows.value.length && !skillRows.value.some((r) => r.skillId === activeSkillId.value)) {
        openSkill(skillRows.value[0]);
      }
    }
  } catch {
    skillRows.value = [];
  } finally {
    skillLoading.value = false;
  }
}

/** 选中某技能：表单回填当前生效内容（在此之上改动保存=生成在线覆写行） */
function openSkill(row: AssistantSkillAdminRow) {
  skillNewMode.value = false;
  activeSkillId.value = row.skillId;
  skillForm.skillId = row.skillId;
  skillForm.displayName = row.displayName || row.skillId;
  skillForm.description = row.description || '';
  skillForm.minRole = row.minRole === 'admin' ? 'admin' : row.minRole === 'publisher' ? 'publisher' : 'member';
  skillForm.body = row.body || '';
  skillForm.disabled = row.source === 'builtin-disabled' || row.source === 'online-disabled';
}

function newSkill() {
  skillNewMode.value = true;
  activeSkillId.value = '';
  skillForm.skillId = '';
  skillForm.displayName = '';
  skillForm.description = '';
  skillForm.minRole = 'member';
  skillForm.body = '';
  skillForm.disabled = false;
}
function cancelNewSkill() {
  skillNewMode.value = false;
  if (skillRows.value.length) {
    openSkill(skillRows.value[0]);
  }
}

/** 保存（新增/覆写/停用一体，题3=乙写后即时广播生效）；错误人话已由 request 层弹出 */
async function persistSkill(disabled: boolean) {
  skillSaving.value = true;
  try {
    await saveAssistantSkillAdmin({
      skillId: skillForm.skillId.trim(),
      displayName: skillForm.displayName.trim(),
      description: skillForm.description.trim(),
      minRole: skillForm.minRole,
      body: skillForm.body,
      disabled: disabled ? 1 : 0
    });
    skillForm.disabled = disabled;
    skillNewMode.value = false;
    ElMessage.success(disabled ? '已保存（仍停用）' : '已保存并生效——下一轮新会话起助手就用新内容');
    activeSkillId.value = skillForm.skillId.trim();
    await loadSkillAdmin();
  } catch {
    /* 校验人话报错已由 request 层弹出 */
  } finally {
    skillSaving.value = false;
  }
}
function onSaveSkill() {
  void persistSkill(skillForm.disabled);
}
function disableSkill() {
  ElMessageBox.confirm(
    `停用后「${skillForm.displayName || skillForm.skillId}」对本租户全员藏起（下一轮新会话起），随时可重新启用。继续？`,
    '停用技能',
    { type: 'warning', confirmButtonText: '停用', cancelButtonText: '再想想' }
  )
    .then(() => void persistSkill(true))
    .catch(() => undefined);
}
function enableSkill() {
  void persistSkill(false);
}
/** 恢复内置原文（有内置基线）/ 彻底删除（纯在线新增）——都走删本租户在线行 */
function onRestoreSkill() {
  const row = activeSkillRow.value;
  if (!row) {
    return;
  }
  const restoreBuiltin = !!row.builtinMinRole;
  ElMessageBox.confirm(
    restoreBuiltin
      ? `删掉本租户对「${row.displayName || row.skillId}」的在线改动，恢复出厂内置原文（下一轮新会话起）。继续？`
      : `「${row.displayName || row.skillId}」是纯在线新增，删除后彻底消失（没有内置原文可恢复）。继续？`,
    restoreBuiltin ? '恢复内置原文' : '删除在线技能',
    { type: 'warning', confirmButtonText: restoreBuiltin ? '恢复内置' : '彻底删除', cancelButtonText: '再想想' }
  )
    .then(async () => {
      skillSaving.value = true;
      try {
        await deleteAssistantSkillAdmin(row.skillId);
        ElMessage.success(restoreBuiltin ? '已恢复内置原文' : '已删除');
        await loadSkillAdmin();
      } catch {
        /* 人话报错已由 request 层弹出 */
      } finally {
        skillSaving.value = false;
      }
    })
    .catch(() => undefined);
}

/** 四段导航的懒加载（总览首屏已带齐数字/健康/能力快照，其余段进到才拉） */
function onTabChange(name: string | number) {
  if (name === 'caps') {
    if (tgRows.value.length === 0 && !tgLoading.value) void loadToolGate();
    if (panoSegments.value.length === 0 && !panoLoading.value) void loadPanorama();
    if (mcpRows.value.length === 0 && !mcpLoading.value) void loadMcpServers();
    if (skillRows.value.length === 0 && !skillLoading.value) void loadSkillAdmin();
  }
  if (name === 'rules' && quotaRows.value.length === 0 && !quotaLoading.value) {
    void loadQuotaPage();
  }
  if (name === 'ops') {
    void loadMcpCallLog();
    void loadFbStats();
    if (fbRows.value.length === 0) void loadFeedback();
    if (logRows.value.length === 0) void loadActionLog();
    if (annRows.value.length === 0 && !annLoading.value) void loadAnnouncements();
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
const wsState = reactive({ configured: false, maskedKey: '' });
const wsTesting = ref(false);
const wsTestMsg = ref('');
const wsTestOk = ref(false);
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

onMounted(() => {
  loadConfig();
  if (canConfig.value) {
    void loadWsInfo();
    loadStats();
    loadModelSection();
    loadHealth();
    // 总览的「需要处理」要用能力快照（风险档/生效档）与审计计数，首屏就把这几份小数据带齐
    void loadPanorama();
    void loadToolGate();
    void loadMcpServers();
    void loadMcpCallLog();
  }
  if (canFeedback.value) {
    void loadFbStats();
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
  /* U-15：KPI 收 26 档 + 契约「数字不染色」——good/mid/bad 语义染色删除，统一正文墨色 */
  font-size: var(--dcs-kpi-size);
  font-weight: 700;
  line-height: 1.2;
  color: var(--app-text);
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
  border-radius: var(--dcs-radius-sm);
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
      border-radius: var(--app-card-radius);
      transition:
        border-color 0.2s,
        box-shadow 0.2s;

      &:hover {
        border-color: var(--el-color-primary-light-5);
        box-shadow: 0 2px 8px rgb(0 0 0 / 6%);
      }

      .num {
        /* U-15：KPI 收 26 档 + 数字不染色（primary → 正文墨色） */
        font-size: var(--dcs-kpi-size);
        font-weight: 600;
        line-height: 1.2;
        color: var(--app-text);
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
    border-radius: var(--app-card-radius);

    /* 稳流② Q8②：运行健康卡（统计条下、页签上）——chips 与统计卡同视觉语系 */
    &.health-card {
      margin-bottom: 18px;

      .health-head {
        display: flex;
        align-items: baseline;
        gap: 10px;

        b {
          font-size: 15px;
        }
      }
      .health-grid {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        gap: 14px 24px;
      }
      .health-sub {
        font-size: 13px;
        font-weight: 600;
        color: var(--dcs-text-secondary);
        margin-bottom: 8px;
      }
      .health-chips {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }
      .hchip {
        display: inline-flex;
        align-items: baseline;
        gap: 6px;
        padding: 4px 10px;
        border-radius: var(--app-control-radius);
        font-size: 12px;
        border: 1px solid var(--el-border-color-light);
        color: var(--dcs-text-secondary);

        i {
          font-style: normal;
          font-weight: 600;
          font-variant-numeric: tabular-nums;
        }
        &.ok i {
          color: var(--el-color-success);
        }
        &.mid i {
          color: var(--el-color-warning);
        }
        &.bad i {
          color: var(--el-color-danger);
        }
      }
      @media (max-width: 992px) {
        .health-grid {
          grid-template-columns: 1fr;
        }
      }
    }

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
    border-radius: var(--app-control-radius);
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
        background: var(--app-raised);
      }
      :deep(.grp-row:hover) td.el-table__cell {
        background: var(--app-raised);
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
      border-radius: var(--app-card-radius);
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
      border-radius: var(--app-control-radius);
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
    border-radius: var(--app-control-radius);
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
    border-radius: var(--app-control-radius);
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
    border-radius: var(--app-control-radius);

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
    border-radius: var(--app-control-radius);
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
    border-radius: var(--dcs-radius-xs);
    background: var(--el-fill-color-darker);
    overflow: hidden;
    flex: none;
    i {
      display: block;
      height: 100%;
      border-radius: var(--dcs-radius-xs);
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

/* ===== 工具开关页签（批1 Q1=B）===== */
.tg-group {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--app-control-radius);
  padding: 8px 12px;
  margin-bottom: 10px;
}
.tg-group-head {
  padding-bottom: 6px;
  margin-bottom: 4px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.tg-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 4px;
}
.tg-row-locked {
  opacity: 0.75;
}
.tg-desc {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tg-status {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tg-status.tg-off {
  color: var(--el-color-danger);
  font-weight: 600;
}
.tg-status.tg-admin {
  color: var(--el-color-warning);
}
.tg-select {
  flex-shrink: 0;
  width: 140px;
}
.tg-batch {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  margin-top: 4px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--app-control-radius);
}
.tg-save {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

/* ===== 外部工具页签（批2 Q2=A）===== */
.mcp-log-pager {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

/* ===== 四段重构（2026-10-27）：总览 / 能力 / 规则 / 运营 ===== */
.dcs-as-admin {
  /* 页头与四段导航同一行——首屏内容起点从 501px 收到 ~130px 以内 */
  padding-top: 10px;

  .adm-bar {
    display: flex;
    align-items: center;
    gap: 20px;
    flex-wrap: wrap;
    margin-bottom: 12px;
    border-bottom: 1px solid var(--el-border-color-light);
  }
  .adm-title {
    display: flex;
    align-items: center;
    gap: 8px;

    b {
      font-size: 18px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
  }
  .adm-seg {
    display: flex;
    gap: 22px;

    button {
      position: relative;
      padding: 6px 0 11px;
      border: none;
      background: transparent;
      font-size: 14px;
      color: var(--dcs-text-secondary);
      cursor: pointer;

      &::after {
        content: '';
        position: absolute;
        right: 0;
        bottom: -1px;
        left: 0;
        height: 2px;
        border-radius: 2px;
        background: transparent;
      }
      &:hover {
        color: var(--dcs-text-primary);
      }
      &.on {
        color: var(--dcs-text-primary);
        font-weight: 600;

        &::after {
          background: var(--el-color-primary);
        }
      }
    }
  }
  .adm-sub {
    margin-left: auto;
    font-size: 12.5px;
    color: var(--dcs-text-secondary);
  }
  /* 段内小节：不再套第二层页签，用标题把内容分开 */
  .adm-sec {
    margin-bottom: 22px;

    &:last-child {
      margin-bottom: 4px;
    }
  }
  .sec-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
    margin-bottom: 9px;
  }
  .sec-ttl {
    display: flex;
    align-items: baseline;
    gap: 9px;
    flex-wrap: wrap;

    b {
      font-size: 15px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
  }
  .sec-cnt {
    display: inline-flex;
    align-items: center;
    padding: 1px 8px;
    border-radius: 10px;
    background: var(--el-fill-color-light);
    font-size: 12px;
    color: var(--dcs-text-secondary);
    font-variant-numeric: tabular-nums;
  }
  .sec-note {
    font-size: 12.5px;
    color: var(--dcs-text-secondary);
  }
  .sec-acts {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  /* ---- 总览两栏 ---- */
  .ov-grid {
    display: grid;
    grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
    gap: 18px;
  }
  .ov-panel {
    padding: 16px 18px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-light);
    border-radius: var(--app-card-radius);
  }
  .att-list {
    list-style: none;
    margin: 0;
    padding: 0;

    li {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      padding: 9px 0;
      border-bottom: 1px dashed var(--el-border-color-lighter);

      &:last-child {
        border-bottom: none;
      }
    }
    .att-dot {
      flex: none;
      width: 8px;
      height: 8px;
      margin-top: 6px;
      border-radius: 50%;
      background: var(--el-color-info);

      &.bad {
        background: var(--el-color-danger);
      }
      &.warn {
        background: var(--el-color-warning);
      }
      &.info {
        background: var(--el-color-primary);
      }
      &.ok {
        background: var(--el-color-success);
      }
    }
    .att-tx {
      flex: 1;
      min-width: 0;

      b {
        font-size: 13.5px;
        font-weight: 600;
        color: var(--dcs-text-primary);
      }
      p {
        margin: 2px 0 0;
        font-size: 12.5px;
        line-height: 1.65;
        color: var(--dcs-text-secondary);
      }
    }
    .att-ok {
      color: var(--dcs-text-secondary);
    }
  }
  .guide-list {
    list-style: none;
    margin: 0;
    padding: 0;

    li {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 11px 0;
      border-bottom: 1px dashed var(--el-border-color-lighter);

      &:last-child {
        border-bottom: none;
      }
    }
    .gl-tx {
      display: flex;
      flex-direction: column;
      gap: 2px;

      b {
        font-size: 13.5px;
        font-weight: 600;
      }
      span {
        font-size: 12.5px;
        color: var(--dcs-text-secondary);
      }
    }
  }

  /* ---- 能力清单 ---- */
  .cap-note {
    margin-bottom: 10px;
    font-size: 12.5px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
  }
  .cap-health {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    flex-wrap: wrap;
    margin-bottom: 10px;
    padding: 8px 12px;
    border: 1px solid var(--el-color-warning-light-8);
    border-radius: var(--app-control-radius);
    background: var(--el-color-warning-light-9);
    font-size: 13px;
    line-height: 1.65;
    color: var(--el-color-warning-dark-2);

    .ch-dot {
      flex: none;
      width: 6px;
      height: 6px;
      margin-top: 8px;
      border-radius: 50%;
      background: var(--el-color-warning);
    }
    .acts {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-left: auto;
    }
    .health-link.el-button {
      height: auto;
      padding: 0;
      font-size: 12px;
      color: var(--dcs-text-secondary);

      &:hover {
        color: var(--dcs-text-primary);
        text-decoration: underline;
      }
    }
    &.ok2 {
      border-color: var(--el-border-color-lighter);
      background: var(--el-fill-color-light);
      color: var(--dcs-text-secondary);

      .ch-dot {
        background: var(--el-color-success);
      }
    }
  }
  .cap-empty {
    margin-bottom: 10px;
    padding: 10px 12px;
    border: 1px dashed var(--el-border-color);
    border-radius: var(--app-control-radius);
    font-size: 13px;
    color: var(--dcs-text-secondary);
  }
  .cap-matrix-wrap {
    overflow-x: auto;
  }
  .cap-matrix {
    width: 100%;
    min-width: 560px;
    border-collapse: collapse;
    font-size: 13px;

    th {
      padding: 7px 8px;
      border-bottom: 1px solid var(--el-border-color);
      font-size: 12px;
      font-weight: 500;
      text-align: center;
      white-space: nowrap;
      color: var(--dcs-text-secondary);
    }
    .cm-name-col {
      text-align: left;
      padding-left: 10px;
    }
    td {
      padding: 0 8px;
      border-bottom: 1px solid var(--el-border-color-lighter);
      height: 44px;
      text-align: center;
      font-variant-numeric: tabular-nums;
    }
    tbody tr {
      cursor: pointer;

      &:hover td {
        background: var(--el-fill-color-lighter);
      }
      &.grp {
        cursor: default;

        td {
          height: 32px;
          padding-top: 6px;
          font-size: 12px;
          font-weight: 600;
          text-align: left;
          color: var(--dcs-text-secondary);
          background: transparent;
        }
        &:hover td {
          background: transparent;
        }
      }
    }
    .cm-name {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 1px;
      padding: 6px 0;

      b {
        font-weight: 600;
        color: var(--dcs-text-primary);
      }
      span {
        font-size: 11.5px;
        color: var(--dcs-text-secondary);
      }
    }
    .cm-cell {
      font-size: 14px;
      font-weight: 600;
      font-variant-numeric: tabular-nums;

      &.yes {
        color: var(--el-color-success);
      }
      &.no {
        color: var(--el-text-color-placeholder);
      }
      &.na {
        font-size: 11.5px;
        font-weight: 500;
        color: var(--el-text-color-placeholder);
      }
    }
  }
  .risk-badge {
    display: inline-block;
    padding: 0 7px;
    border-radius: 8px;
    font-size: 11.5px;
    font-style: normal;
    font-weight: 500;
    line-height: 18px;
    text-align: center;

    &.high {
      background: var(--el-color-danger-light-9);
      color: var(--el-color-danger);
    }
    &.mid {
      background: var(--el-color-warning-light-9);
      color: var(--el-color-warning-dark-2);
    }
    &.low {
      background: var(--el-fill-color-light);
      color: var(--dcs-text-secondary);
    }
  }
  .cap-switch {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    margin: 14px 0 10px;
  }

  /* ---- 一键推荐 ---- */
  .preset-wrap {
    padding: 14px 16px 12px;
    border: 1px solid var(--el-border-color-light);
    border-radius: var(--app-card-radius);
    background: var(--el-fill-color-blank);
  }
  .pol-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
  }
  .pol-card {
    padding: 10px 12px 11px;
    text-align: left;
    border: 1px solid var(--el-border-color-light);
    border-radius: var(--app-control-radius);
    background: var(--el-bg-color);
    cursor: pointer;
    transition:
      border-color 0.15s,
      background 0.15s;

    &:hover {
      border-color: var(--el-color-primary-light-5);
    }
    &.on {
      border-color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
    }
    b {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 13.5px;
      color: var(--dcs-text-primary);
    }
    p {
      margin: 4px 0 0;
      font-size: 12px;
      line-height: 1.6;
      color: var(--dcs-text-secondary);
    }
    .pol-reco {
      padding: 0 6px;
      border-radius: 8px;
      background: var(--el-color-primary);
      font-size: 10.5px;
      font-weight: 500;
      line-height: 16px;
      color: #fff;
    }
  }
  .preset-sum {
    margin: 12px 0 8px;
    font-size: 13px;
    color: var(--dcs-text-primary);

    b {
      font-size: 15px;
      color: var(--el-color-primary);
      font-variant-numeric: tabular-nums;
    }
  }
  .preset-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 12.5px;

    th {
      padding: 6px 8px;
      border-bottom: 1px solid var(--el-border-color);
      font-size: 12px;
      font-weight: 500;
      text-align: left;
      color: var(--dcs-text-secondary);
    }
    .pt-name {
      width: 36%;
    }
    .pt-risk {
      width: 64px;
      text-align: center;
    }
    td {
      padding: 6px 8px;
      border-bottom: 1px solid var(--el-border-color-lighter);
    }
    .pt-from {
      color: var(--dcs-text-secondary);
    }
    .pt-to {
      font-weight: 600;
      color: var(--el-color-success);
    }
    td:last-child {
      text-align: center;
    }
  }
  .preset-none {
    padding: 14px 4px;
    font-size: 12.5px;
    color: var(--dcs-text-secondary);
  }
  .rule-box {
    margin-top: 10px;
    font-size: 12.5px;

    summary {
      cursor: pointer;
      color: var(--dcs-text-secondary);
      user-select: none;
    }
    .rule-table {
      width: 100%;
      margin-top: 8px;
      border-collapse: collapse;

      th,
      td {
        padding: 5px 8px;
        border-bottom: 1px solid var(--el-border-color-lighter);
        text-align: left;
      }
      th {
        font-weight: 500;
        color: var(--dcs-text-secondary);
      }
    }
  }
  .preset-foot {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 10px;
    flex-wrap: wrap;
    margin-top: 12px;

    .sec-note {
      margin-right: auto;
      max-width: 620px;
      line-height: 1.6;
    }
  }

  /* ---- 按行微调：一行一个控件 ---- */
  .cap-group {
    margin-bottom: 10px;
  }
  .cap-group-head {
    display: flex;
    align-items: baseline;
    gap: 10px;
    flex-wrap: wrap;
    margin-bottom: 2px;
    padding: 6px 2px;
    font-size: 13px;
    font-weight: 600;
    color: var(--dcs-text-secondary);
    border-bottom: 1px solid var(--el-border-color-lighter);

    .colmark {
      margin-left: auto;
      font-size: 12px;
      font-weight: 400;
      color: var(--el-text-color-placeholder);
    }
  }
  /* 行与灰行共用的一套名字/条件排版（同一页只有一档字号，别各写一套） */
  .cap-name {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;

    b {
      font-size: 14px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
  }
  .cap-sub {
    font-size: 12px;
    color: var(--dcs-text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .cap-line {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 56px 212px 44px;
    align-items: center;
    gap: 10px;
    min-height: 44px;
    padding: 6px 2px;
    border-bottom: 1px solid var(--el-border-color-lighter);
    font-variant-numeric: tabular-nums;

    &:hover {
      background: var(--el-fill-color-lighter);
    }
    .cap-risk {
      display: flex;
      align-items: center;
      min-width: 0;
    }
    .cap-locked {
      font-size: 12px;
      color: var(--el-text-color-placeholder);
    }
    .cap-select {
      width: 212px;
    }
    .cap-detail.el-button {
      justify-self: end;
      height: auto;
      padding: 0;
      font-size: 12px;
      color: var(--dcs-text-secondary);

      &:hover {
        color: var(--el-color-primary);
      }
    }
    &.is-locked {
      background: var(--el-fill-color-lighter);
    }
  }
  .cap-biz-note {
    margin-top: 8px;
    font-size: 12px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
  }
  /* 折叠组：灰行指路 + 按角色总览（默认收起，标题本身是按钮） */
  .foldbox {
    margin: 12px 0 4px;
    border: 1px solid var(--el-border-color-light);
    border-radius: var(--app-card-radius);
    background: var(--el-bg-color);
  }
  .fold-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 9px 12px;
    font-size: 13px;
    color: var(--dcs-text-secondary);
    cursor: pointer;
    user-select: none;

    b {
      font-size: 13px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    .arr {
      font-size: 10px;
      color: var(--el-text-color-placeholder);
      transition: transform 0.15s;
    }
    &:hover b {
      color: var(--el-color-primary);
    }
  }
  .foldbox.open .fold-head .arr {
    transform: rotate(90deg);
  }
  .fold-body {
    padding: 2px 12px 12px;
  }
  .fold-note {
    padding: 6px 2px 2px;
    font-size: 12px;
    line-height: 1.7;
    color: var(--el-text-color-placeholder);
  }
  .fold-empty {
    height: 44px;
    font-size: 13px;
    text-align: left !important;
    color: var(--dcs-text-secondary);
  }
  .na-line {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto auto;
    align-items: center;
    gap: 10px;
    min-height: 44px;
    padding: 6px 2px;
    border-bottom: 1px solid var(--el-border-color-lighter);
    font-variant-numeric: tabular-nums;

    .cap-sub {
      white-space: normal;
    }
  }
  .pills {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
    padding: 8px 2px 4px;

    button {
      height: 26px;
      padding: 0 12px;
      border: 1px solid var(--el-border-color-light);
      border-radius: 13px;
      background: transparent;
      font-family: inherit;
      font-size: 12px;
      color: var(--dcs-text-secondary);
      cursor: pointer;

      &:hover {
        border-color: var(--el-color-primary-light-5);
        color: var(--dcs-text-primary);
      }
      &.on {
        border-color: var(--el-color-primary);
        background: var(--el-color-primary-light-9);
        color: var(--el-color-primary);
      }
    }
  }
  .cap-save {
    position: sticky;
    bottom: 0;
    z-index: 2;
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    margin-top: 12px;
    padding: 10px 14px;
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: var(--app-control-radius);
    background: var(--el-color-primary-light-9);
    font-size: 12.5px;
    color: var(--dcs-text-primary);

    b {
      color: var(--el-color-primary);
    }
    .el-button {
      margin-left: auto;
    }
    .el-button + .el-button {
      margin-left: 0;
    }
  }

  /* ---- 行为总纲：目录 + 正文 ---- */
  .pd-wrap {
    display: grid;
    grid-template-columns: 240px minmax(0, 1fr);
    gap: 14px;
    align-items: start;
    margin-top: 8px;
  }
  .pd-toc {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 6px;
    border: 1px solid var(--el-border-color-light);
    border-radius: var(--app-card-radius);
    background: var(--el-fill-color-blank);

    button {
      display: grid;
      grid-template-columns: 18px minmax(0, 1fr);
      gap: 2px 8px;
      padding: 7px 8px;
      border: none;
      border-radius: var(--app-control-radius);
      background: transparent;
      text-align: left;
      cursor: pointer;

      &:hover {
        background: var(--el-fill-color-light);
      }
      &.on {
        background: var(--el-color-primary-light-9);

        .pt {
          color: var(--el-color-primary);
          font-weight: 600;
        }
      }
      i {
        grid-row: span 2;
        font-style: normal;
        font-size: 11.5px;
        line-height: 18px;
        text-align: center;
        color: var(--el-text-color-placeholder);
      }
      .pt {
        font-size: 13px;
        color: var(--dcs-text-primary);
      }
      .pf {
        font-size: 11px;
        color: var(--el-text-color-placeholder);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
    .faint {
      padding: 8px;
      font-size: 12px;
      color: var(--el-text-color-placeholder);
    }
  }
  .pd-body {
    min-width: 0;
    padding: 14px 16px;
    border: 1px solid var(--el-border-color-light);
    border-radius: var(--app-card-radius);
    background: var(--el-bg-color);
  }
  .pd-meta {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;

    b {
      font-size: 14px;
      color: var(--dcs-text-primary);
    }
    .pd-file {
      font-size: 11.5px;
      color: var(--el-text-color-placeholder);
    }
    .sp {
      flex: 1;
    }
  }
  .pd-why {
    margin: 8px 0 10px;
    padding: 7px 10px;
    border-radius: var(--app-control-radius);
    background: var(--el-fill-color-lighter);
    font-size: 12.5px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
  }
  /* 5b 批：技能库编辑区（总纲同构骨架上的表单款） */
  .sk-edit .sk-field {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-bottom: 12px;

    > label {
      font-size: 12px;
      font-weight: 600;
      color: var(--el-text-color-secondary);
    }
    .sk-hint {
      font-size: 12px;
      line-height: 1.6;
      color: var(--el-text-color-placeholder);
    }
  }
  .sk-edit .sk-field .el-select {
    align-self: flex-start;
  }
  .sk-edit .sk-body :deep(textarea) {
    font-family: Consolas, 'JetBrains Mono', monospace;
    font-size: 12px;
    line-height: 1.7;
  }
  .sk-edit .sk-acts {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    margin-top: 2px;
    padding-top: 10px;
    border-top: 1px dashed var(--el-border-color-lighter);

    .sk-saved {
      font-size: 12px;
      color: var(--el-text-color-placeholder);
    }
  }
  .pd-body .sys-prompt {
    margin: 0;
    padding: 12px 14px;
    max-height: 520px;
    overflow: auto;
    font-size: 12.5px;
    line-height: 1.8;
    color: var(--dcs-text-regular);
    white-space: pre-wrap;
    word-break: break-word;
    background: var(--el-fill-color-lighter);
    border-radius: var(--app-control-radius);
  }

  /* ---- 运营：审计摘要 ---- */
  .audit-sum {
    display: flex;
    align-items: baseline;
    gap: 18px;
    flex-wrap: wrap;
    margin-bottom: 10px;
    font-size: 12.5px;
    color: var(--dcs-text-secondary);

    b {
      font-size: 15px;
      color: var(--dcs-text-primary);
      font-variant-numeric: tabular-nums;
    }
    em {
      margin-left: 5px;
      font-style: normal;
      font-size: 11.5px;
      color: var(--el-text-color-placeholder);
    }
  }
  .ops-card :deep(.audit-fail) td {
    background: var(--el-color-danger-light-9);
  }
  .ops-card {
    border-radius: var(--app-card-radius);
  }


}

/* 能力详情抽屉（append-to-body：样式不能挂在 .dcs-as-admin 下） */
.cap-detail-drawer {
  .sys-prompt {
    margin: 0;
    padding: 12px 14px;
    max-height: 420px;
    overflow: auto;
    font-size: 12.5px;
    line-height: 1.8;
    color: var(--dcs-text-regular);
    white-space: pre-wrap;
    word-break: break-word;
    background: var(--el-fill-color-lighter);
    border-radius: var(--app-control-radius);
  }
  .cd {
    .cd-name {
      display: flex;
      align-items: center;
      gap: 9px;

      b {
        font-size: 16px;
        color: var(--dcs-text-primary);
      }
    }
    .cd-sub {
      margin-top: 4px;
      font-size: 12.5px;
      color: var(--dcs-text-secondary);
    }
    .cd-sec {
      margin-top: 18px;

      h4 {
        margin: 0 0 7px;
        font-size: 13px;
        font-weight: 600;
        color: var(--dcs-text-primary);
      }
      p {
        margin: 0;
        font-size: 13px;
        line-height: 1.75;
        color: var(--dcs-text-regular);
      }
    }
    .cd-chain {
      margin: 0;
      padding-left: 18px;

      li {
        padding: 3px 0;
        font-size: 13px;
        line-height: 1.65;
        color: var(--dcs-text-secondary);
      }
      b {
        color: var(--dcs-text-primary);
        font-weight: 600;
        margin-right: 8px;
      }
    }
    .cd-quick {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;

      .el-button {
        margin-left: 0;
      }
    }
    .cd-quick-note {
      margin-top: 8px;
      font-size: 12px;
      line-height: 1.7;
      color: var(--el-text-color-placeholder);
    }
    .cd-kv {
      display: flex;
      gap: 10px;
      padding: 4px 0;
      font-size: 12.5px;
      color: var(--dcs-text-secondary);

      span {
        width: 72px;
        flex: none;
        color: var(--el-text-color-placeholder);
      }
      code {
        font-size: 12px;
        word-break: break-all;
      }
    }
  }
}

@media (max-width: 1100px) {
  .dcs-as-admin .ov-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 900px) {
  .dcs-as-admin .pd-wrap {
    grid-template-columns: 1fr;
  }
  .dcs-as-admin .pd-toc {
    flex-direction: row;
    overflow-x: auto;
    gap: 4px;

    button {
      flex: none;
      min-width: 132px;
    }
  }
}
@media (max-width: 760px) {
  .dcs-as-admin .adm-bar {
    gap: 8px;
  }
  .dcs-as-admin .adm-sub {
    display: none;
  }
  .dcs-as-admin .adm-seg {
    width: 100%;
    gap: 16px;
    overflow-x: auto;
  }
  .dcs-as-admin .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .dcs-as-admin .stat-grid .stat-card {
    padding: 12px 14px 11px;
  }
  .dcs-as-admin .health-head {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
  }
  .dcs-as-admin .pol-grid {
    grid-template-columns: 1fr;
  }
  .dcs-as-admin .cap-line {
    grid-template-columns: minmax(0, 1fr) 56px 44px;
    grid-template-areas:
      'name risk more'
      'sel sel sel';
    row-gap: 4px;

    .cap-name {
      grid-area: name;
    }
    .cap-risk {
      grid-area: risk;
      justify-content: flex-end;
    }
    .cap-select,
    .cap-locked {
      grid-area: sel;
      width: 100%;
    }
    .cap-detail.el-button {
      grid-area: more;
      justify-self: end;
    }
  }
  .dcs-as-admin .fold-head {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .dcs-as-admin .na-line {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      'name risk'
      'act act';
    row-gap: 4px;

    .cap-name {
      grid-area: name;
    }
    .risk-badge {
      grid-area: risk;
      justify-self: end;
    }
    .el-button {
      grid-area: act;
      justify-self: start;
      margin-left: 0;
    }
  }
  .dcs-as-admin .cap-health .acts {
    margin-left: 14px;
  }
  .dcs-as-admin .cap-save .el-button {
    margin-left: 0;
  }
}

</style>
