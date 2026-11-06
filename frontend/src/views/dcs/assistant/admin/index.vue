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
              <li v-if="canConfig">
                <div class="gl-tx"><b>能力</b><span>助手会什么、谁能用、哪项危险</span></div>
                <el-button link type="primary" size="small" @click="jumpTo('caps')">打开 →</el-button>
              </li>
              <li v-if="canConfig">
                <div class="gl-tx"><b>规则</b><span>租户开关、模型与费用、每人每天额度</span></div>
                <el-button link type="primary" size="small" @click="jumpTo('rules')">打开 →</el-button>
              </li>
              <li v-if="canConfig || canFeedback">
                <div class="gl-tx"><b>运营</b><span>真实调用与失败、错答反馈、运行记录、公告</span></div>
                <el-button link type="primary" size="small" @click="jumpTo('ops')">打开 →</el-button>
              </li>
            </ul>
          </section>
        </div>
      </div>

      <!-- ============ 能力：一条控制台（工具栏 + 表格 + 行内改档 + 勾选批量 + 底部出口） ============ -->
      <div v-show="tab === 'caps'" v-if="canConfig" class="adm-pane">
        <section class="adm-sec cap-card">
          <!-- 工具栏：位置永远固定（搜能力 / 按角色看 / 只看高危 / 一键收紧） -->
          <div class="cap-bar">
            <div class="cap-bar-ttl">
              <b>能力</b>
              <span class="cap-bar-cnt">{{ capAvailCount }} 项<template v-if="capHighCount"> · {{ capHighCount }} 项高危</template></span>
              <span
                class="cap-tip"
                title="查任务、查待办、待审列表、建任务等业务工具由角色菜单权限控制，见「系统管理 → 角色管理」"
              >业务工具另由角色菜单控制</span>
            </div>
            <div class="cap-bar-tools">
              <el-input v-model="capQuery" class="cap-search" size="small" clearable placeholder="搜能力名" />
              <div class="cap-lens" role="group" aria-label="按角色看">
                <button
                  v-for="p in CAP_LENS"
                  :key="p.v"
                  type="button"
                  :aria-pressed="capLens === p.v"
                  @click="capLens = p.v"
                >
                  {{ p.label }}
                </button>
              </div>
              <el-checkbox v-model="capHighOnly" label="只看高危" size="small" />
              <el-checkbox
                class="cap-all"
                :model-value="capAllSel"
                :indeterminate="capSomeSel"
                size="small"
                @change="toggleCapAll"
              >
                全选
              </el-checkbox>
              <el-button size="small" @click="openPreset(capOpenHigh.length ? 'conservative' : 'standard')">
                {{ capOpenHigh.length ? '一键收紧' : '一键推荐' }}
              </el-button>
            </div>
          </div>

          <!-- 体检一句话：合规状态先说，处理动作在工具栏右端（不再单独占一条色带） -->
          <div class="cap-health" :class="{ ok2: !capOpenHigh.length }">
            <span class="ch-dot"></span>
            <template v-if="capOpenHigh.length">
              <b class="ch-lead">{{ capOpenHigh.length }} 项高危能力对全员开放</b>
              <span class="ch-names">
                <button v-for="r in capOpenHigh" :key="r.key" type="button" @click="openCapDetail(r)">{{ r.displayName }}</button>
              </span>
            </template>
            <span v-else class="ch-lead">
              {{ capOvDraftCount ? `已收紧 ${capOvDraftCount} 项 · 其余跟随平台默认` : '能力全部跟随平台默认' }}
            </span>
          </div>

          <div v-if="!tgLoading && tgRows.length === 0" class="cap-empty">能力清单加载失败——切换页签重试</div>

          <div v-if="capGroups.length" class="cap-tbl">
            <template v-for="(g, gi) in capGroups" :key="g.name">
              <div class="cap-ghead" :class="{ first: gi === 0 }">
                <b>{{ g.name }}</b>
                <span class="n">{{ g.rows.length }} 项</span>
                <span class="hint">{{ CAP_GROUP_HINT[g.name] || '' }}</span>
              </div>
              <template v-for="row in g.rows" :key="row.key">
                <div class="cap-item">
                  <div
                    class="cap-row"
                    :class="{ closed: capLevelOf(row, tgDraft) < 0, dim: capLensDim(row), sel: capSel.has(row.key) }"
                    tabindex="0"
                    @click="openCapDetail(row)"
                    @keydown="onCapRowKey($event, row)"
                  >
                    <el-checkbox
                      class="cap-cb"
                      :model-value="capSel.has(row.key)"
                      :aria-label="`选择 ${row.displayName}`"
                      @click.stop
                      @change="toggleCapSel(row)"
                    />
                    <div class="cap-main">
                      <div class="cap-line">
                        <b class="cap-nm">{{ row.displayName }}</b>
                        <span v-if="row.riskLevel !== 'low'" class="risk-badge" :class="row.riskLevel">
                          {{ RISK_LABEL[row.riskLevel] }}
                        </span>
                        <span v-else class="lowdot" role="img" aria-label="低危" title="低危" />
                        <span v-if="capCondition(row)" class="cap-when" :title="capCondition(row)">· {{ capCondition(row) }}</span>
                      </div>
                      <div class="cap-sub" :title="capDesc(row)">
                        {{ capDesc(row) }}<span v-if="capCondition(row)" class="cap-when-m"> · {{ capCondition(row) }}</span>
                      </div>
                    </div>
                    <div class="cap-act">
                      <button
                        type="button"
                        class="gate-btn"
                        :class="{ 'gate-no': capLensDim(row), 'gate-off': capLevelOf(row, tgDraft) < 0 }"
                        :aria-expanded="capOpenGate === row.key"
                        :aria-label="`${row.displayName}的可用范围：${capGateText(row)}，点开可以改`"
                        @click.stop="toggleCapGate(row)"
                      >
                        {{ capGateText(row) }}
                        <span v-if="capRowValue(row) === 'admin'" class="gate-own">· 已收紧</span>
                        <svg class="car" viewBox="0 0 12 12" aria-hidden="true">
                          <path d="M2.5 4.5 6 8l3.5-3.5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
                        </svg>
                      </button>
                      <svg class="cap-go" viewBox="0 0 12 12" aria-hidden="true">
                        <path d="M4.5 2.5 8 6l-3.5 3.5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                    </div>
                  </div>
                  <div v-if="capOpenGate === row.key" class="cap-x">
                    <span class="lbl">改成</span>
                    <button
                      v-for="o in capOptionsFor(row)"
                      :key="o.key"
                      type="button"
                      class="xopt"
                      :class="{ on: o.key === capRowValue(row), dis: o.disabled }"
                      :disabled="o.disabled"
                      @click.stop="pickCapOption(row, o)"
                    >
                      {{ o.label }}<span v-if="o.key === capRowValue(row)" class="cur">（当前）</span>
                    </button>
                    <span class="kbd">选完即收起；点底部「保存」才生效</span>
                  </div>
                </div>
              </template>
            </template>
          </div>
          <!-- 有清单但筛空才说「没有符合条件」；清单本身为空时以上一条「加载失败」为准，不再两句同屏 -->
          <div v-else-if="tgRows.length && !tgLoading" class="cap-empty">没有符合条件的能力</div>

          <div v-if="capLens !== 'all'" class="cap-note">
            正在以「{{ capLensName }}」的身份看：✓ 是能用的行，× 写了它需要的最低角色；暗掉的行这个角色用不到。
          </div>

          <div class="cap-foot">
            <span>{{ capOvSavedCount ? `已保存 ${capOvSavedCount} 处覆盖` : '全部跟随平台默认' }}</span>
            <span class="sp" />
            <!-- 低频编辑区不再各占一段：出口与页脚同一行，点开在下方展开 -->
            <span class="cap-more-links">
              <button type="button" @click="openCapMore('skill')">技能库 <b>{{ skillRows.length }}</b></button>
              <button type="button" @click="openCapMore('pano')">行为总纲 <b>{{ panoSegments.length }} 段</b></button>
            </span>
            <el-button v-if="capOvSavedCount" link size="small" class="cap-reset" @click="resetAllOverrides">恢复全部跟随平台</el-button>
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

          <!-- 业务工具的归属说明已收进顶栏的「业务工具另由角色菜单控制」提示，不再单占一段 -->
          <!-- 底部吸附区：勾选批量与未保存改动都停在这里，滚到哪都看得见（不遮表格内容） -->
          <div v-if="capSel.size || tgDirty" class="cap-dock">
            <div v-if="capSel.size" class="cap-batch">
              <span class="n">已选 <b>{{ capSel.size }}</b> 项</span>
              <el-button size="small" @click="batchCapSet('admin')">批量收紧</el-button>
              <el-button size="small" @click="batchCapSet('off')">批量关闭</el-button>
              <el-button link size="small" @click="clearCapSel">清除选择</el-button>
              <span class="kbd">↑↓ 移动 · 回车看详情 · 空格勾选</span>
            </div>
            <div v-if="tgDirty" class="cap-save">
              <span>有 <b>{{ tgDirtyCount }}</b> 项未保存改动，保存后对下一轮新会话生效</span>
              <el-button size="small" :disabled="tgSaving" @click="resetToolGate">放弃改动</el-button>
              <el-button size="small" type="primary" :loading="tgSaving" @click="onSaveToolGate">保存 {{ tgDirtyCount }} 项</el-button>
            </div>
          </div>
        </section>


        <!-- ============ 5b 批：技能库（低频编辑区，默认收起，标题即出口） ============ -->
        <section class="adm-sec cap-fold cap-more cap-more-skill" :class="{ open: skillOpen }">
          <div class="sec-head cap-fold-head" @click="skillOpen = !skillOpen">
            <span class="fold-arr">▶</span>
            <div class="sec-ttl">
              <b>技能库</b>
              <span class="sec-cnt">{{ skillRows.length }} 个</span>
              <span class="sec-note">助手按需取用的操作手册；展开可改说明与正文</span>
            </div>
            <div class="sec-acts" @click.stop>
              <el-button v-hasPermi="['dcs:assistant:skill:edit']" size="small" @click="confirmNewSkill(); skillOpen = true">
                新增技能
              </el-button>
            </div>
          </div>
          <div v-show="skillOpen" class="cap-fold-body">
          <div class="cap-note">技能是助手按需取用的「操作手册」：目录里只放一句话说明，正文用到才读。改完保存立即生效（下一轮新会话起）；「一句话说明」是助手判断什么时候用这个技能的唯一依据。</div>
          <div class="pd-wrap" v-loading="skillLoading">
            <nav class="pd-toc">
              <button
                v-for="row in skillRows"
                :key="row.skillId"
                type="button"
                :class="{ on: !skillNewMode && activeSkillId === row.skillId }"
                @click="confirmOpenSkill(row)"
              >
                <i class="ski">{{ (row.displayName || row.skillId).slice(0, 1) }}</i>
                <span class="pt">{{ row.displayName || row.skillId }}</span>
                <span class="pf">{{ SKILL_SOURCE_TXT[row.source] || row.source }} · {{ SKILL_ROLE_TXT[row.minRole] }}</span>
                <!-- 题2 案3：技能取用统计（用次 / 最近一次，两列键值；无记录=0/从未） -->
                <span
                  class="pu"
                  :class="{ 'pu-zero': !row.useCount && !row.lastUsedAt }"
                  :title="`本租户累计取用 ${row.useCount ?? 0} 次；最近一次 ${row.lastUsedAt ? row.lastUsedAt : '从未使用'}`"
                >
                  <span class="pu-k">用次</span>
                  <span class="pu-v">{{ row.useCount ?? 0 }}</span>
                  <span class="pu-k">最近一次</span>
                  <span class="pu-v">{{ skillLastUsedText(row.lastUsedAt) }}</span>
                </span>
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
          </div>
        </section>

        <section class="adm-sec cap-fold cap-more cap-more-pano" :class="{ open: panoOpen }">
          <div class="sec-head cap-fold-head" @click="panoOpen = !panoOpen">
            <span class="fold-arr">▶</span>
            <div class="sec-ttl">
              <b>助手行为总纲</b>
              <span class="sec-cnt">{{ panoSegments.length }} 段</span>
              <span class="sec-note">只读 · 随版本更新；展开可逐段查看或复制</span>
            </div>
            <div class="sec-acts" @click.stop>
              <el-button size="small" @click="copyFullPrompt">复制完整提示词</el-button>
            </div>
          </div>
          <div v-show="panoOpen" class="cap-fold-body">
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
            <el-descriptions-item label="每日回合上限">
              <template v-if="subActive">
                <template v-if="cfg.dailyRoundLimitPerUser > 0"
                  >每人 <b>不限</b> 回合 <span class="cell-note">积分消费按积分扣；只有配 0 才是急停</span></template
                >
                <template v-else>每人 <b class="q-stopped">已停用</b> <span class="cell-note">租户默认 0 = 急停</span></template>
              </template>
              <template v-else>每人 {{ cfg.dailyRoundLimitPerUser }} 回合 · 明早 0 点恢复</template>
            </el-descriptions-item>
            <!-- 业务批3-④：租户联网默认（每人每日免费次数；0=全租户关停。改值走「用户额度」页单人覆盖或租户配置 API，本页先展示） -->
            <el-descriptions-item label="每日联网次数">
              <template v-if="subActive">
                按套餐档位<span class="cell-note"
                  >未配档时兜底 {{ cfg.websearchLimitPerUser ?? 20 }} 次{{
                    (cfg.websearchLimitPerUser ?? 20) === 0 ? ' · 已关停' : ''
                  }}</span
                >
              </template>
              <template v-else>
                每人 {{ cfg.websearchLimitPerUser ?? 20 }} 次<template v-if="(cfg.websearchLimitPerUser ?? 20) === 0"> · 已关停</template>
              </template>
            </el-descriptions-item>
            <!-- 题3：人民币符号紧贴数字（本页原「¥ 50.00 / ¥1.02」两套写法并存） -->
            <el-descriptions-item label="租户日预算">¥{{ Number(cfg.dailyBudgetAmount).toFixed(2) }} / 天</el-descriptions-item>
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
                  :max-height="isNarrow ? undefined : 260"
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
            <div class="sec-ttl">
              <b>每人每天额度</b>
              <!-- Q31（2026-10-30 用户反馈 / 2026-10-31 审查回修）：积分消费下回合正数=不限次，但 0 仍是急停、
                   联网仍按套餐档位每天计数——所以只降噪，不锁编辑入口（锁了会让 0 值急停解不开、联网次数没法重置） -->
              <el-tag v-if="subActive" size="small" type="info" effect="plain" class="sec-off-tag">积分消费 · 回合不限</el-tag>
            </div>
            <span class="sec-note">{{
              subActive
                ? '积分消费：回合只要不是 0 就按不限次执行（0 = 急停）；联网按订阅套餐档位、每天照常计数——「重置今日」仍要用'
                : '先看租户默认，再给个别用户设单人上限；0 = 关停对应能力'
            }}</span>
          </div>
        <el-card shadow="never" class="adm-card" :class="{ 'quota-off': subActive }">
          <!-- Q31 回修：原版整区停用会把仍生效的闸关死（回合 0 急停 / 联网日计数），改为「降噪 + 保留编辑入口」 -->
          <div v-if="subActive" class="quota-offline">
            <span class="qo-ico">ⓘ</span>
            <span>
              当前租户按<b>积分消费</b>：对话扣积分，回合只要不是 0 就按<b>不限次</b>执行；联网每天按<b>套餐档位</b>计数。
              所以这里的<b>正数</b>在积分态不影响实际限制，但 <b>0 仍然生效</b>——回合填 0（或单人回合设 0）= 对话急停，联网填 0 = 关闭联网。
              三个入口都保留：<b>「重置今日」会同时清回合和联网计数</b>，联网次数用完必须用它。
            </span>
          </div>
          <!-- 概览条：左=租户默认口径（来自本页配置卡数据源）；右=本页速览（口径=当前页，不跨页误导） -->
          <div class="quota-overview">
            <div class="qo-item qo-tenant">
              <span class="qo-k">租户默认</span>
              <span class="qo-v">
                <template v-if="subActive"
                  ><template v-if="cfg.dailyRoundLimitPerUser > 0"
                    >每人 <b>不限</b> 回合/天 <span class="qo-note">积分消费按积分扣，正数不设上限</span></template
                  ><template v-else
                    >每人 <b class="q-stopped">已停用</b> 回合/天
                    <span class="qo-note">租户默认配了 0 = 急停，点「改默认」可解除</span></template
                  >
                  · {{ cfg.websearchLimitPerUser ?? 20 }} 次联网/天
                  <span class="qo-note">{{
                    (cfg.websearchLimitPerUser ?? 20) > 0 ? '套餐配了档位以套餐为准，此处兜底' : '配了 0 = 关闭联网'
                  }}</span></template
                >
                <template v-else
                  >每人 {{ cfg.dailyRoundLimitPerUser }} 回合/天 · {{ cfg.websearchLimitPerUser ?? 20 }} 次联网/天
                  <span class="qo-note">设过单人上限的不受此默认影响</span></template
                >
              </span>
              <!-- 业务批7 Q8：租户默认行内编辑（端点早已存在，此前缺页面入口）。
                   glm53 P2-2 回修：权限串与 PUT /config 端点对齐用 config（quota:edit 菜单
                   11935 授权 0 角色=对租户管理员不可达，仅超管可见）
                   Q31：积分消费下回合正数不设上限，但 0 仍是急停、联网仍按套餐计数——
                   保留编辑入口（2026-10-31 审查回修：整区锁死会让 0 值急停解不开、联网次数没法重置） -->
              <el-popover
                v-if="checkPermi(['dcs:assistant:config'])"
                v-model:visible="defaultPopoverOpen"
                placement="bottom"
                :width="340"
                trigger="click"
                @hide="onDefaultPopoverHide"
              >
                <template #reference>
                  <el-button
                    size="small"
                    text
                    type="primary"
                    class="qo-edit-btn"
                    :title="
                      subActive
                        ? '积分消费下：回合 0 = 急停、正数 = 不限；联网 0 = 关闭、正数只在套餐未配档时兜底'
                        : '改本租户每人每天的回合数 / 联网次数默认值'
                    "
                    >改默认</el-button
                  >
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
                  <!-- Q8 深化：订阅租户提示——2026-10-31 审查回修：讲清「正数不限 / 0 才急停 / 联网仍计数」 -->
                  <div v-if="subActive" class="qo-form-tip">
                    ⓘ 积分消费中：回合数<b>正数不设上限</b>，只有填 <b>0</b> 才是急停（立刻停全租户对话）；联网数 0 = 关闭，正数在套餐未配档时兜底
                  </div>
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
            <!-- Q31：积分消费下「已用完 / 快用完」按原始回合上限算会撒谎（实际不限次），停用这两个统计 -->
            <div v-if="!subActive" class="qo-item">
              <span class="qo-k"><i class="qo-dot qo-red" />已用完</span>
              <span class="qo-v"
                ><b>{{ quotaPageStats.exhausted }}</b> 人 <span class="qo-note">本页</span></span
              >
            </div>
            <div v-if="!subActive" class="qo-item">
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
          <el-table :data="quotaRows" v-loading="quotaLoading">
            <el-table-column label="用户" min-width="150">
              <template #default="{ row }">
                <span>{{ row.nickName || row.userName }}</span>
                <span class="quota-sub">{{ row.userName ? '@' + row.userName : '' }}</span>
              </template>
            </el-table-column>
            <!-- 题6：宽屏下「用户」到「部门」之间曾有约 800px 空白——把「谁能用」（disabled 列的真实语义）
                 前置成第二列，宽屏第一屏就能读到「这个人现在能不能用」，不靠横滑找最右的状态列 -->
            <el-table-column label="谁能用" width="96" align="center">
              <template #default="{ row }">
                <el-tag :type="row.disabled === '1' ? 'danger' : 'success'" size="small">{{ row.disabled === '1' ? '已禁用' : '可用' }}</el-tag>
              </template>
            </el-table-column>
            <!-- 题2：窄屏收起低价值列（部门），把宽度让给「今日用量」与钉右的操作列；桌面不变 -->
            <el-table-column v-if="!isNarrow" label="部门" prop="deptName" width="130" show-overflow-tooltip />
            <el-table-column label="今日用量" width="170" align="center">
              <template #default="{ row }">
                <!-- UI-UX 批：比例可视化进度条——蓝(正常)→橙(≥80%)→红(用完)，一眼找出快用完的人
                     Q31：积分消费下回合不限次，进度条与「x / 上限」都失去意义，改显「已用 / 不限」 -->
                <div class="quota-turn-cell">
                  <div class="q-usage">
                    <div v-if="!subActive" class="q-usage-bar">
                      <i :class="quotaBarTier(row)" :style="{ width: quotaBarPct(row) + '%' }" />
                    </div>
                    <span
                      v-if="subActive"
                      class="q-unlimited"
                      :title="
                        quotaRoundStopped(row)
                          ? '该用户回合上限为 0 = 对话急停；点「限额」改成 0 以上的数字即可恢复'
                          : '积分消费按积分扣，回合正数不设上限；今天已用 ' + row.turnsUsedToday + ' 回合'
                      "
                      >{{ row.turnsUsedToday }} /
                      <b v-if="!quotaRoundStopped(row)">不限</b><b v-else class="q-stopped">已停用</b></span
                    >
                    <span
                      v-else
                      :class="{ 'quota-exhausted': row.effectiveRoundLimit > 0 && row.turnsUsedToday >= row.effectiveRoundLimit }"
                    >
                      {{ row.turnsUsedToday }} / {{ row.effectiveRoundLimit }}
                    </span>
                  </div>
                  <div v-if="row.roundLimitOverride != null || row.websearchLimitOverride != null" class="q-badges">
                    <el-tag
                      v-if="row.roundLimitOverride != null"
                      size="small"
                      type="warning"
                      class="quota-tag"
                      :title="
                        !subActive
                          ? '该用户单独设了回合上限，不受租户默认影响'
                          : row.roundLimitOverride === 0
                            ? '积分消费下回合上限 0 = 该用户对话急停；点「限额」改成 0 以上即可恢复'
                            : '积分消费下回合正数不设上限（只有 0 = 急停）'
                      "
                      >单人{{ row.roundLimitOverride }}回合</el-tag
                    >
                    <el-tag
                      v-if="row.websearchLimitOverride != null"
                      size="small"
                      type="warning"
                      class="quota-tag"
                      :title="
                        !subActive
                          ? '该用户单独设了联网次数上限，不受租户默认影响'
                          : row.websearchLimitOverride === 0
                            ? '积分消费下联网上限 0 = 关闭该用户联网；点「限额」改成 0 以上即可恢复'
                            : '积分消费下联网按套餐档位计数，正数仅在套餐未配档时兜底'
                      "
                      >联网单人{{ row.websearchLimitOverride }}</el-tag
                    >
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="本月费用" width="110" align="right">
              <template #default="{ row }">¥{{ fmtMoney(row.monthCost) }}</template>
            </el-table-column>
            <!-- 题2：操作列钉右用表格原生 fixed（CSS sticky 在列被压缩时会糊到前一列上，原生层会独立留位） -->
            <el-table-column label="操作" width="200" align="center" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="checkPermi(['dcs:assistant:quota:edit'])"
                  link
                  type="primary"
                  size="small"
                  :disabled="!subActive && row.turnsUsedToday === 0"
                  :title="
                    subActive
                      ? '清空该用户今日的回合与联网计数——积分消费下联网每天照常计数，用完靠这里重置'
                      : row.turnsUsedToday === 0
                        ? '今天还没用过，无需重置'
                        : '把该用户今日已用回合清零'
                  "
                  @click="onQuotaReset(row)"
                  >重置今日</el-button
                >
                <el-button
                  v-if="checkPermi(['dcs:assistant:quota:edit'])"
                  link
                  type="primary"
                  size="small"
                  :title="
                    subActive
                      ? '给该用户单独设回合 / 联网上限；积分消费下 0 = 停用对应能力，正数不设上限（联网在套餐未配档时兜底）'
                      : '给该用户单独设回合 / 联网上限'
                  "
                  @click="openQuotaLimits(row)"
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
                :description="
                  subActive
                    ? '没找到用户——换个关键词试试；积分消费下回合默认不限，改过单人上限（含 0 = 停用）的会在这里显示'
                    : '没找到用户——换个关键词试试；用户额度默认跟随租户设置（每人每天各自的回合数），改过单人的会在这里显示'
                "
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
            <!-- 题5：全页 11 处表格都有加载态，唯独审计表没有——翻页时旧行无提示挂着、还能连点下一页错页 -->
            <el-table :data="mcpLogRows" v-loading="mcpLogLoading" size="small" :row-class-name="auditRowClass">
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
              <!-- 题2：窄屏收起「耗时」（低价值，已并入钉右的详情列语境）；桌面保留 -->
              <el-table-column v-if="!isNarrow" label="耗时" width="90">
                <template #default="{ row }">{{ row.durationMs != null ? row.durationMs + 'ms' : '—' }}</template>
              </el-table-column>
              <!-- 题6+回修：调用成功行兜「—」（历史行 error_msg 曾写耗时）；挂载/反问行的 error_msg 是结果详情、
                   限频失败行没有错误文本——这三类按动作放行显示，不再被「成功=—」一刀切吞掉 -->
              <el-table-column label="详情/错误" min-width="220" :fixed="isNarrow ? 'right' : false">
                <template #default="{ row }">{{
                  row.action === 'CALL_OK' ? '—' : row.errorMsg || (row.action === 'RATE_LIMITED' ? '已限频，稍后再试' : '—')
                }}</template>
              </el-table-column>
              <template #empty>
                <el-empty
                  :description="mcpLogLoading ? '加载中…' : mcpLogError ? '加载失败——刷新页面或稍后再试' : auditShowMounts ? '还没有调用或挂载记录' : '还没有真实调用——右上角可打开挂载记录'"
                  :image-size="56"
                />
              </template>
            </el-table>
            <div class="mcp-log-pager">
              <el-button size="small" :disabled="mcpLogLoading || mcpLogPage <= 1" @click="mcpLogPage--; loadMcpCallLog()">上一页</el-button>
              <span class="field-hint">第 {{ mcpLogPage }} 页 · 共 {{ mcpLogTotal }} 条</span>
              <el-button size="small" :disabled="mcpLogLoading || mcpLogPage * 20 >= mcpLogTotal" @click="mcpLogPage++; loadMcpCallLog()"
                >下一页</el-button
              >
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
        <el-card shadow="never" class="adm-card">
          <!-- 题4：加载失败不再整卡消失（原 v-if="fbStats" 把卡片一起藏了）——块内给重试入口 -->
          <div v-if="fbStatsError" class="fb-fail">
            <span>反馈统计没加载出来——可能是网络抖动，点右侧重试。</span>
            <el-button size="small" :loading="fbStatsLoading" @click="retryFbStats">重试</el-button>
          </div>
          <!-- AI 缺口批 Q10：反馈聚合——好评率大数字 + 点踩原因 CSS 横条 + 周趋势小卡 -->
          <div v-else-if="fbStats" class="fb-stats" v-loading="fbStatsLoading">
            <div class="fb-rate">
              <div class="fb-rate-num" :class="fbRateTone">{{ fbStats.upRate == null ? '—' : `${fbStats.upRate}%` }}</div>
              <div class="fb-rate-lbl">好评率（近 {{ fbStats.windowDays }} 天）</div>
              <!-- 题3：彩色 emoji 换单色拇指图标（与下方评价列、全页线性图标同一套） -->
              <div class="fb-rate-sub">
                <IconSpan name="thumbs-up" /> {{ fbStats.up }} · <IconSpan name="thumbs-down" /> {{ fbStats.down }}
              </div>
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
              <!-- 题4：没样本时不再摆一串全 0 的行，只留一句引导 -->
              <template v-if="fbStats.total">
                <div v-for="w in fbStats.trend || []" :key="w.week" class="fb-trend-row">
                  <span class="fb-trend-week">{{ String(w.week).substring(5) }}</span>
                  <span class="fb-trend-nums"><IconSpan name="thumbs-up" />{{ w.up }} <IconSpan name="thumbs-down" />{{ w.down }}</span>
                </div>
              </template>
              <div v-else class="fb-empty">还没有样本——用户点过赞或踩之后这里会出现趋势</div>
            </div>
          </div>
          <div v-else class="fb-fail"><span>反馈统计加载中…</span></div>
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
            <!-- 题4：空态交给表格插槽——原来的表内「暂无数据」+ 表下插画会把「没有」说两遍 -->
            <template #empty>
              <el-empty :description="fbLoading ? '加载中…' : fbListError ? '加载失败——稍后再试或刷新页面' : '还没有反馈——用户在回答尾部点「点踩」时会落到这里'" :image-size="60" />
            </template>
          </el-table>
          <div v-if="fbTotal > fbRows.length" class="fb-more">
            <el-button link type="primary" :loading="fbLoading" @click="loadFeedback">加载更多（{{ fbRows.length }} / {{ fbTotal }}）</el-button>
          </div>
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
            <!-- 题4：空态分两种——首次进来别预设「你查过了」（原来一进来就说「换个关键词试试」）；
                 空态交给表格插槽，表内「暂无数据」不再和插画叠两份 -->
            <template #empty>
              <el-empty
                :description="
                  logLoading
                    ? '加载中…'
                    : logSearched
                      ? '没有符合条件的运行记录——换个关键词或放宽时间范围试试'
                      : 'AI 替用户执行确认过的写操作（如发催办）会记在这里；现在还没有记录'
                "
                :image-size="60"
              />
            </template>
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
            <!-- 题4：同上，空态只留插槽这一份 -->
            <template #empty>
              <el-empty :description="annLoading ? '加载中…' : annListError ? '加载失败——稍后再试或刷新页面' : '还没有公告——发布后用户打开 AI 助手即见顶部横幅'" :image-size="60" />
            </template>
          </el-table>
        </el-card>
        </section>
      </div>
    </div>

    <!-- ===== 能力抽屉：一行的全部信息 + 唯一的改档入口（技术名只在这里出现） ===== -->
    <el-drawer v-model="capDetailOpen" size="480px" append-to-body class="cap-detail-drawer" :with-header="false">
      <div v-if="capDetailRow" class="cd">
        <div class="cd-head">
          <div class="cd-head-tx">
            <h3 class="cd-title">
              {{ capDetailRow.displayName }}
              <span v-if="capDetailRow.riskLevel !== 'low'" class="risk-badge" :class="capDetailRow.riskLevel">
                {{ RISK_LABEL[capDetailRow.riskLevel] }}
              </span>
              <span v-else class="lowrisk">低危</span>
            </h3>
            <div class="cd-sub">{{ capGroupName(capDetailRow) }} · 平台内置档「{{ LEVEL_LABEL[capDetailRow.builtinLevel] }}」</div>
          </div>
          <button type="button" class="cd-x" aria-label="关闭" @click="capDetailOpen = false">×</button>
        </div>

        <div class="cd-body">
          <div class="cd-sec">
            <h4>这项能力会做什么</h4>
            <p>{{ capDesc(capDetailRow) }}</p>
          </div>

          <div class="cd-sec">
            <div class="cd-lb">
              <h4>谁能用</h4>
              <span class="lb-note">只能收紧，不能放宽</span>
              <span class="sp" />
              <el-button v-if="capDraftKey !== 'default'" link size="small" @click="capDraftKey = 'default'">
                恢复跟随平台
              </el-button>
            </div>
            <div class="cap-opts" role="radiogroup" aria-label="谁能用">
              <div
                v-for="o in capLevelOptions"
                :key="o.key"
                class="cap-opt"
                :class="{ on: capDraftKey === o.key, dis: o.disabled }"
                role="radio"
                :aria-checked="capDraftKey === o.key"
                :aria-disabled="o.disabled"
                @click="!o.disabled && (capDraftKey = o.key)"
              >
                <span class="rd" />
                <div class="opt-tx">
                  <div class="t">
                    {{ o.label }}<span v-if="o.tag" class="now">{{ o.tag }}</span>
                  </div>
                  <div class="d">{{ o.desc }}</div>
                </div>
              </div>
            </div>
            <div v-if="capDetailRow.locked" class="cap-lock-note">代码强制项：内置就是仅管理员，页面不能调。</div>
          </div>

          <div class="cd-sec">
            <h4>生效链路</h4>
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
                <span>{{ LEVEL_LABEL[capDetailRow.builtinLevel] }}</span>
              </li>
              <li>
                <b>本租户覆盖</b>
                <span>{{ capDraftText }}</span>
              </li>
              <li>
                <b>实际可调用</b>
                <span :class="{ 'cd-changed': capDrawerDirty }">{{ capDraftEffText }}</span>
              </li>
              <li v-if="capDetailRow.askConfirm">
                <b>执行确认</b>
                <span>写入类动作，执行前先弹确认卡征求用户同意</span>
              </li>
            </ol>
          </div>

          <details class="cd-tech">
            <summary>技术信息</summary>
            <div class="cd-kv"><span>内部标识</span><code>{{ capDetailRow.toolNames.join(' / ') }}</code></div>
            <div class="cd-kv"><span>风险档</span>{{ RISK_LABEL[capDetailRow.riskLevel] }}</div>
          </details>

          <div v-if="capSkillBody(capDetailRow)" class="cd-sec">
            <h4>技能正文（选中这个技能时才注入）</h4>
            <pre class="sys-prompt">{{ capSkillBody(capDetailRow) }}</pre>
          </div>
        </div>

        <div class="cd-foot">
          <span class="cd-dirty">
            {{ capDrawerDirty ? '改了，还没保存' : capDraftKey !== 'default' ? '这一项已改过' : '' }}
          </span>
          <span class="sp" />
          <el-button size="small" @click="capDetailOpen = false">取消</el-button>
          <el-button size="small" type="primary" :disabled="!capDrawerDirty" :loading="tgSaving" @click="saveCapLevel">
            保存并生效
          </el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 一键推荐：3 档策略 × 风险档规则，整列预览后再应用 -->
    <el-dialog v-model="presetOpen" :title="presetTitle" width="640px" append-to-body class="reco-dialog">
      <div class="reco-sub">按风险档一次改一批。先看「现在 → 改成」再应用；选比现在松的档，就是撤销之前的收紧。</div>
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
          <span class="pol-cnt">{{ capDiffFor(p.key).length ? `会改 ${capDiffFor(p.key).length} 行` : '无需改' }}</span>
        </button>
      </div>
      <div class="reco-sum">
        {{ policyName(capPolicy) }} · 共 <b>{{ capPreview.length }}</b> 行会变
        <span class="sec-note">（其余 {{ tgRows.length - capPreview.length }} 项已符合该档；业务工具由菜单权限控制，不参与推荐）</span>
      </div>
      <table v-if="capPreview.length" class="preset-table">
        <thead>
          <tr><th class="pt-name">能力</th><th>现在</th><th>改成</th><th class="pt-risk">风险</th></tr>
        </thead>
        <tbody>
          <tr v-for="x in capPreview" :key="x.row.key">
            <td class="pt-nm">{{ x.row.displayName }}</td>
            <td class="pt-from">{{ x.from }}</td>
            <td class="pt-to">{{ x.to }}<span v-if="x.loosen" class="pt-loosen">放宽</span></td>
            <td><span class="risk-badge" :class="x.row.riskLevel">{{ RISK_LABEL[x.row.riskLevel] }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="preset-none">当前设置已符合{{ policyName(capPolicy) }}，没有需要改的行。</div>
      <details class="rule-box">
        <summary>规则表 · 选档即按此推</summary>
        <table class="rule-table">
          <thead>
            <tr>
              <th>风险</th>
              <th :class="{ on: capPolicy === 'conservative' }">保守</th>
              <th :class="{ on: capPolicy === 'standard' }">标准</th>
              <th :class="{ on: capPolicy === 'open' }">宽松</th>
            </tr>
          </thead>
          <tbody>
            <tr><td>低危 · 只读</td><td>跟随平台</td><td>跟随平台</td><td>跟随平台</td></tr>
            <tr>
              <td>中危 · 会落数据</td>
              <td :class="{ on: capPolicy === 'conservative' }">仅管理员</td>
              <td :class="{ on: capPolicy === 'standard' }">跟随平台</td>
              <td :class="{ on: capPolicy === 'open' }">跟随平台</td>
            </tr>
            <tr>
              <td>高危 · 出网 / 外部服务</td>
              <td :class="{ on: capPolicy === 'conservative' }">关闭</td>
              <td :class="{ on: capPolicy === 'standard' }">仅管理员</td>
              <td :class="{ on: capPolicy === 'open' }">跟随平台</td>
            </tr>
          </tbody>
        </table>
      </details>
      <template #footer>
        <div class="preset-foot">
          <span class="sec-note">应用前会重拉一次权限：这期间有人改过就中止让你复核。改动对下一轮新会话生效。</span>
          <el-button size="small" @click="presetOpen = false">取消</el-button>
          <el-button
            size="small"
            type="primary"
            :disabled="!capPreview.length"
            :loading="presetApplying"
            @click="applyPreset"
          >
            应用{{ policyName(capPolicy) }}（{{ capPreview.length }} 行会变）
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 批6(Q13): 能力覆盖弹窗——三模板(三档可调/仅开·关/不支持), 只影响本租户, 官方 seed 升级不冲掉 -->
    <el-dialog v-model="ovDialog" title="覆盖该模型的能力映射" width="640px" append-to-body class="ov-dialog">
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
    <el-dialog v-model="quotaLimitsDialog" title="设置单人额度上限" width="640px" append-to-body class="q-dialog">
      <el-alert type="info" :closable="false" show-icon class="ov-alert">
        <template #title>每列留空 = 该项跟随租户默认；填数字 = 只对这一个人生效（两列互不影响）</template>
        <template v-if="subActive" #default>
          积分消费中：回合上限只有 <b>0</b> 才是急停（正数不设上限）；联网上限 0 = 关闭该用户联网，正数在套餐未配档时兜底。
        </template>
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
    <el-dialog v-model="wsReplace.open" title="替换联网搜索密钥（博查）" width="480px" append-to-body class="ws-dialog">
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
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRouter } from 'vue-router';
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
import { skillLastUsedText } from './skillUsageText';

const userStore = useUserStore();
const router = useRouter();
/** 题2：窄屏收起低价值列/放开内层限高都用它（760 是拍板口径，与壳层 992 断点各管一段） */
const isNarrow = useMediaQuery('(max-width: 760px)');

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
/** 题5：弹层开着时，别处保存（如助手称呼）会整体刷新 cfg，把这里没保存的草稿静默重置回旧值 */
const defaultPopoverOpen = ref(false);
const syncDefaultEdit = () => {
  defaultEdit.round = cfg.value?.dailyRoundLimitPerUser ?? 50;
  defaultEdit.web = cfg.value?.websearchLimitPerUser ?? 20;
};
/** 订阅生效中（billingMode=credits）：回合默认空转，仅提示不阻拦（管理员可能为订阅到期提前配好） */
const subActive = ref(false);
/** 消费方式（用户反馈1014：原「服务码价目」死文案改动态）——credits=积分订阅 / wallet=钱包按量 */
const billingMode = ref<'credits' | 'wallet'>('wallet');
watch(
  cfg,
  (c) => {
    // 弹层打开期间跳过覆写，关闭后（onDefaultPopoverHide）再对齐最新配置
    if (c && !defaultPopoverOpen.value) syncDefaultEdit();
  },
  { immediate: true }
);
/** 关闭弹层：恢复「跟随配置」，下次打开从最新值起编 */
function onDefaultPopoverHide() {
  syncDefaultEdit();
}
function resetDefaultEdit() {
  syncDefaultEdit();
}
/** 保存后生效预览：0 直译"关停"，不说"0 回合"这种没人懂的话；
 *  Q31 回修（2026-10-31）：积分消费下回合正数=不限（只有 0 是急停），联网正数只在套餐未配档时兜底 */
const defaultPreviewText = computed(() => {
  if (subActive.value) {
    const rt = defaultEdit.round > 0 ? '回合不限（正数不设上限）' : '对话急停（配 0）';
    const wt =
      defaultEdit.web > 0 ? `联网按套餐档位（未配档兜底 ${defaultEdit.web} 次/天）` : '联网关闭（配 0）';
    return `每人 ${rt} · ${wt}`;
  }
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
      walletTxt.value = `可用 ¥${Number(main.availableAmount).toFixed(2)}`;
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
/** F5（2026-10-31 复审回修）：列表加载失败与「还没有反馈」分开说——空态不再谎报数据不存在 */
const fbListError = ref(false);
// AI 缺口批 Q10：反馈聚合统计（好评率/原因分布/周趋势）
const fbStats = ref<any>(null);
/** 题4：统计卡自己兜失败态——原来 catch 里把 fbStats 置 null，整张卡跟着消失 */
const fbStatsError = ref(false);
const fbStatsLoading = ref(false);
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
  // F3（2026-10-31 复审回修）：进行中直接返回——进运营页签时「守卫兜底发」与「显式发」会同步连发两次，幂等接口也是白费请求
  if (fbStatsLoading.value) return;
  fbStatsLoading.value = true;
  fbStatsError.value = false;
  try {
    const res: any = await getFeedbackStats(28);
    // 回修+F4（2026-10-31 复审回修）：data 为 null 时落到诚实空态，兜底对象补全字段——缺 windowDays/up/down 会渲染出「好评率（近 天）」和孤立的「 · 」
    fbStats.value = res?.data ?? { total: 0, windowDays: 28, up: 0, down: 0, upRate: null };
  } catch {
    fbStats.value = null;
    fbStatsError.value = true;
  } finally {
    fbStatsLoading.value = false;
  }
}
/** 题4：失败态的重试入口（块内按钮，不弹整屏报错） */
function retryFbStats() {
  // 回修：先拉列表再补统计——先把 fbStatsError 清掉的话，loadFeedback 的守卫会连带再发一次统计请求（一次重试打两次）
  if (fbRows.value.length === 0) void loadFeedback();
  void loadFbStats();
}
async function loadFeedback() {
  // 题5：进行中直接返回——原来双击「加载更多」会把同一页数据追加两遍
  if (fbLoading.value) return;
  if (fbRows.value.length === 0 && !fbStats.value && !fbStatsError.value) {
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
    fbListError.value = false; // F5：成功才清失败标志
  } catch {
    fbListError.value = true; // F5：失败态空态要说「加载失败」，不说「还没有」
    ElMessage.error('反馈加载失败，请稍后再试');
  } finally {
    fbLoading.value = false;
  }
}

/* ===== 助手公告页签（批4 Q11）：发布/编辑/撤回，发布即生效；首开加载挂在 onTabChange ===== */
const annRows = ref<AssistantAnnouncementRow[]>([]);
const annLoading = ref(false);
/** F5：公告列表加载失败与「还没有公告」分开说 */
const annListError = ref(false);
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
    annListError.value = false; // F5：成功才清失败标志
  } catch {
    annListError.value = true; // F5：失败态空态要说「加载失败」，不说「还没有」
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
      // F1 回修（2026-10-31）：空串照发——原 `|| undefined` 会让「清空正文」被 JSON 丢掉，
      // 后端拿不到字段就保持旧正文，页面却提示「已更新」。null 才是「本次不改」。
      content: annForm.content.trim(),
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
/** 题4：空态要分「首次进入」和「查过没结果」两种说法，别一进来就预设用户查过 */
const logSearched = ref(false);
// logSt/LOG_ST 批I 收口到 useAssistant 与会话页账本共用（单一真源）
let logSeq = 0; // 回修：与调用审计同款请求序号——翻页/筛选连点时先发后到的旧响应直接丢弃
let logRenderedPage = 1; // 回修：最后一次成功渲染的页码——v-model 翻页先改页码再请求，失败回到这里
async function loadActionLog() {
  const seq = ++logSeq;
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
    if (seq !== logSeq) return;
    logRows.value = res?.rows ?? [];
    logTotal.value = res?.total ?? 0;
    logRenderedPage = logQuery.pageNum;
  } catch {
    // F6（2026-10-31 复审回修）：回滚与报错都只认最新一次请求——被序号淘汰的旧请求失败不该弹错
    if (seq === logSeq) {
      logQuery.pageNum = logRenderedPage;
      ElMessage.error('运行记录加载失败，请稍后再试');
    }
  } finally {
    if (seq === logSeq) logLoading.value = false;
  }
}
/** 查询：daterange → 起止闭区间（当天 00:00:00 ~ 23:59:59），回到第 1 页 */
function onLogSearch() {
  logSearched.value = true;
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
  // F13（2026-10-31 复审回修）：建议全部指向能力/规则/审计段，这几段都只对配置权限开放——
  // 无权限者点了「去开通/去收紧/去看真实调用」只能白跑一趟，整组不显示
  if (canConfig.value) {
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
  }
  return out;
});
/** 总览的「去看看」/段内互跳：切段并补一次懒加载；从能力段带着未保存技能时先确认 */
async function jumpTo(name: string) {
  if (tab.value === 'caps' && name !== 'caps' && !(await confirmLeaveIfDirty())) return;
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

const tgLoading = ref(false);
const tgSaving = ref(false);
const tgRows = ref<TgRowUi[]>([]);
/** 已保存覆盖快照（工具名→off/admin；无键=跟随平台） */
const tgSaved = ref<Record<string, string>>({});
/** 草稿覆盖——保存时整份提交（后端全量覆盖语义，空表=全恢复默认） */
const tgDraft = ref<Record<string, string>>({});
const capHighOnly = ref(false);
/** 搜能力名（2026-11-01 丁案）：匹配显示名 / 出现条件 / 分组名，三个字就能缩小范围 */
const capQuery = ref('');
/** 勾选批量：存 row.key（toolNames.join('/')），重载清单时清空避免留陈旧键 */
const capSel = ref<Set<string>>(new Set());
/** 行内改档：当前展开的那一行（同一时刻只开一条，不遮别的行） */
const capOpenGate = ref<string | null>(null);
/** 按角色看：全部 / 成员 / 发布人 / 管理员。只换「谁能用」那一列的读法，不另起一套版式 */
const capLens = ref<'all' | '0' | '1' | '2'>('all');
const CAP_LENS = [
  { v: 'all', label: '全部' },
  { v: '0', label: '成员' },
  { v: '1', label: '发布人' },
  { v: '2', label: '管理员' }
] as const;
/** 折叠组开合（默认收起）：当前不会出现 / 技能库 / 行为总纲 */
const capNaOpen = ref(false);
const skillOpen = ref(false);
const panoOpen = ref(false);
/** 一键推荐浮层（原「按行微调 / 一键推荐」模式切换已废——切换会把清单整个换掉） */
const presetOpen = ref(false);
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
  // 'default' 是「跟随平台」的哨兵值（'' 会被当成空值）
  return tgDraft.value[row.toolNames[0]] || 'default';
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
  const q = capQuery.value.trim().toLowerCase();
  const map = new Map<string, TgRowUi[]>();
  for (const r of tgRows.value) {
    if (!capAvailable(r)) continue;
    if (capHighOnly.value && r.riskLevel !== 'high') continue;
    const name = CAP_CAT_GROUP[r.category] ?? r.category;
    // 搜名字 / 干什么 / 出现条件 / 分组名：拼的是行里真正显示的那几句话，
    // 否则用户照着屏幕搜「联网额度」「自动执行」会搜不到，看起来像坏了
    if (q && !`${r.displayName} ${capDesc(r)} ${capCondition(r)} ${name}`.toLowerCase().includes(q)) continue;
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
/** 当前可见的全部行（全选/键盘上下都按这个顺序走，和屏幕上看到的一致） */
const capFlatRows = computed<TgRowUi[]>(() => capGroups.value.flatMap((g) => g.rows));
const capHighCount = computed(() => tgRows.value.filter((r) => r.riskLevel === 'high' && capAvailable(r)).length);

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
const capAvailCount = computed(() => tgRows.value.filter((r) => capAvailable(r)).length);
/** 按角色看时，这个角色够不着的行（够不着就暗下去，不删——删了会以为能力不存在） */
function capLensDim(row: TgRowUi): boolean {
  if (capLens.value === 'all') return false;
  const lv = capLevelOf(row, tgDraft.value);
  return lv < 0 ? false : Number(capLens.value) < lv;
}
const capLensName = computed(() => CAP_LENS.find((p) => p.v === capLens.value)?.label ?? '全部');
function capGroupName(row: TgRowUi): string {
  return CAP_CAT_GROUP[row.category] ?? row.category;
}
/** 「谁能用」单元格文案：全部分角色读法都收在这一处，模板里不堆三元 */
function capGateText(row: TgRowUi): string {
  const lv = capLevelOf(row, tgDraft.value);
  if (lv < 0) return '已关闭';
  if (capLens.value === 'all') return LEVEL_LABEL[lv] ?? '';
  return capLensDim(row) ? `× 需${LEVEL_LABEL[lv]}` : '✓ 可用';
}
/** 行内改档开合：同一时刻只开一条；点行本身仍然进抽屉看详情 */
function toggleCapGate(row: TgRowUi) {
  capOpenGate.value = capOpenGate.value === row.key ? null : row.key;
}
/** 行内选档：只改草稿，等底部保存才落库（与抽屉同一套草稿语义） */
function pickCapOption(row: TgRowUi, o: { key: 'default' | 'admin' | 'off'; disabled?: boolean }) {
  if (o.disabled || tgSaving.value) return;
  setRowValue(row, o.key);
  capOpenGate.value = null;
}
/* ===== 勾选批量（2026-11-01 丁案）：勾几行一起收紧/关闭，仍然只改草稿 ===== */
function toggleCapSel(row: TgRowUi) {
  const next = new Set(capSel.value);
  if (next.has(row.key)) next.delete(row.key);
  else next.add(row.key);
  capSel.value = next;
}
function clearCapSel() {
  capSel.value = new Set();
}
const capAllSel = computed(() => capFlatRows.value.length > 0 && capFlatRows.value.every((r) => capSel.value.has(r.key)));
const capSomeSel = computed(() => !capAllSel.value && capFlatRows.value.some((r) => capSel.value.has(r.key)));
/**
 * 表头全选只动「当前可见」的行：先勾了 5 行再搜索，点全选不会把那 5 行静默丢掉
 * （之前是整体替换，筛选一开就丢；计数又按全量算，两头语义打架）。
 */
function toggleCapAll() {
  const next = new Set(capSel.value);
  if (capAllSel.value) {
    for (const r of capFlatRows.value) next.delete(r.key);
  } else {
    for (const r of capFlatRows.value) next.add(r.key);
  }
  capSel.value = next;
}
/** 批量改档：一次只算一份草稿，锁死/已是最严的行跳过并说清跳了几项 */
function batchCapSet(kind: 'admin' | 'off') {
  if (tgSaving.value) return; // 保存请求在飞时不再改草稿（否则新改动会被乐观标记成已保存）
  const next = { ...tgDraft.value };
  let changed = 0;
  let skipped = 0;
  for (const row of tgRows.value) {
    if (!capSel.value.has(row.key)) continue;
    // 已经不在清单里的行（服务停用/配置关了）不参与批量，避免改到看不见的能力
    if (!capAvailable(row)) {
      skipped++;
      continue;
    }
    if (row.locked || (kind === 'admin' && row.builtinLevel >= 2)) {
      skipped++;
      continue;
    }
    // 已关闭比「仅管理员」更严：批量收紧不能把 off 反向放开
    if (kind === 'admin' && capLevelOf(row, tgDraft.value) < 0) {
      skipped++;
      continue;
    }
    if (capRowValue(row) === kind) continue;
    for (const n of row.toolNames) {
      if (kind === 'off') next[n] = 'off';
      else if (kind === 'admin') next[n] = 'admin';
    }
    changed++;
  }
  if (!changed) {
    ElMessage.info(skipped ? `选中的 ${skipped} 项由代码强制或已是最严，没得改` : '选中的能力和当前一样，没有变化');
    return;
  }
  tgDraft.value = next;
  ElMessage.success(`已把 ${changed} 项加入待保存改动${skipped ? `（${skipped} 项跳过）` : ''}——点底部「保存」才生效`);
}
/** 键盘操作：↑↓ 换行、回车看详情、空格勾选（只在行本身聚焦时接管，子元素不抢） */
function onCapRowKey(e: KeyboardEvent, row: TgRowUi) {
  if (e.target !== e.currentTarget) return;
  if (e.key === 'Enter') {
    e.preventDefault();
    openCapDetail(row);
    return;
  }
  if (e.key === ' ' || e.key === 'Spacebar') {
    e.preventDefault();
    toggleCapSel(row);
    return;
  }
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
  e.preventDefault();
  const rows = [...(e.currentTarget as HTMLElement).closest('.cap-tbl')?.querySelectorAll<HTMLElement>('.cap-row') ?? []];
  const i = rows.indexOf(e.currentTarget as HTMLElement);
  rows[i + (e.key === 'ArrowDown' ? 1 : -1)]?.focus();
}
/** 底部出口：打开对应折叠区并滚过去（都是同一个页面里的低频编辑区） */
function openCapMore(kind: 'skill' | 'pano') {
  if (kind === 'skill') skillOpen.value = true;
  else panoOpen.value = true;
  void nextTick(() => {
    document.querySelector(`.cap-more-${kind}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
}
const CAP_GROUP_HINT: Record<string, string> = {
  平台工具: '会话里随时可用',
  技能: '助手按需取用',
  挂进来的外部服务: '服务离线时整组消失'
};
/** 覆盖计数：体检条按草稿（改完立刻有反应），页脚按已保存（说清真正生效的） */
const capOvSavedCount = computed(() => tgRows.value.filter((r) => !!tgSaved.value[r.toolNames[0]]).length);
const capOvDraftCount = computed(() => tgRows.value.filter((r) => !!tgDraft.value[r.toolNames[0]]).length);
/** 体检句的唯一来源：还有哪些高危能力对全员开放（处理完 = 无事） */
const capOpenHigh = computed(() =>
  tgRows.value.filter((r) => r.riskLevel === 'high' && capAvailable(r) && capLevelOf(r, tgDraft.value) === 0)
);
/** 当前不会出现的配置缺项（含未开启的能力与服务已停的外部服务） */
const capNaRows = computed(() => tgRows.value.filter((r) => !capAvailable(r)));
/** 体检条右侧的入口：告警态开「保守档」预览（按钮写一键收紧），无事态开「标准档」 */
function openPreset(policy: CapPolicy) {
  capPolicy.value = policy;
  presetOpen.value = true;
}
/** 从「一键收紧」进来的标题要跟着说人话；切到别的档就退回推荐的说法 */
const presetTitle = computed(() => (capPolicy.value === 'conservative' ? '一键收紧（保守档）' : '一键推荐'));
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
/** 档位数字越大越严，但「关闭」比「仅管理员」更严——按「谁能用」的宽窄排序才不会把收紧标成放宽 */
function capPerm(v: number): number {
  return v < 0 ? 0 : 3 - v;
}
/** 任一策略在当前已保存状态下会变几行（三张档位卡直接写行数，选之前就能横向比较） */
function capDiffFor(policy: CapPolicy) {
  return tgRows.value
    .map((row) => ({
      row,
      fromLevel: capLevelOf(row, tgSaved.value),
      toLevel: capTargetLevel(row, policy)
    }))
    .filter((x) => x.fromLevel !== x.toLevel);
}
const capPreview = computed(() =>
  capDiffFor(capPolicy.value).map((x) => ({
    ...x,
    from: x.fromLevel < 0 ? '已关闭' : (LEVEL_LABEL[x.fromLevel] ?? '—'),
    to: x.toLevel < 0 ? '关闭' : (LEVEL_LABEL[x.toLevel] ?? '—'),
    /** 整列覆盖可能把手工关掉的行重新打开——会变松的行必须当场标出来 */
    loosen: capPerm(x.toLevel) > capPerm(x.fromLevel)
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
/** 应用到全部能力：浮层本身就是确认（先看整列预览）→ 重拉比对防并发 → 整列覆盖 */
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
    presetOpen.value = false;
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
/** 抽屉里的草稿档（default / admin / off）——点保存才写进 tgDraft 并提交 */
const capDraftKey = ref<'default' | 'admin' | 'off'>('default');
function openCapDetail(row: TgRowUi) {
  capDetailRow.value = row;
  capDraftKey.value = capRowValue(row) as 'default' | 'admin' | 'off';
  capOpenGate.value = null;
  capDetailOpen.value = true;
}
/**
 * 能选的档只有后端真支持的三种：跟随平台 / 仅管理员 / 关闭。
 * 内置档是「发布人及以上」时不给「成员」选项（effectiveMinRoleSingle 只升不降，给了就是假开关）。
 * 抽屉与行内展开共用这一份（capOptionsFor），两处选项永远一致。
 */
type CapOpt = { key: 'default' | 'admin' | 'off'; label: string; desc: string; tag?: string; disabled?: boolean };
function capOptionsFor(row: TgRowUi | null): CapOpt[] {
  if (!row) return [];
  const b = row.builtinLevel;
  const opts: CapOpt[] = [
    {
      key: 'default',
      label: `跟随平台 · ${LEVEL_LABEL[b]}`,
      desc: b === 0 ? '租户内所有人都能让助手做这件事' : b === 1 ? '能发布任务的人和管理员能用' : '只有管理员能用',
      tag: row.locked ? '代码强制' : b >= 2 ? '已是可设最严' : '平台默认'
    }
  ];
  if (b < 2 && !row.locked) opts.push({ key: 'admin', label: '仅管理员', desc: '只有管理员能让助手做这件事' });
  if (!row.locked) opts.push({ key: 'off', label: '关闭这项能力', desc: '任何会话里都不会再出现' });
  return opts;
}
const capLevelOptions = computed(() => capOptionsFor(capDetailRow.value));
const capDrawerDirty = computed(() => !!capDetailRow.value && capDraftKey.value !== capRowValue(capDetailRow.value));
const capDraftText = computed(() => {
  if (capDraftKey.value === 'off') return '本租户设为关闭';
  if (capDraftKey.value === 'admin') return '本租户设为仅管理员';
  return '未覆盖，跟随平台内置档';
});
const capDraftEffText = computed(() => {
  const row = capDetailRow.value;
  if (!row) return '';
  if (capDraftKey.value === 'off') return '不会出现';
  if (capDraftKey.value === 'admin') return LEVEL_LABEL[Math.max(row.builtinLevel, 2)];
  return LEVEL_LABEL[row.builtinLevel];
});
async function saveCapLevel() {
  const row = capDetailRow.value;
  if (!row) return;
  setRowValue(row, capDraftKey.value);
  const ok = await onSaveToolGate();
  if (ok) capDetailOpen.value = false;
}
/** 一键恢复：清空整张覆盖表（含手工关掉的行），落库后重新拉一次 */
async function resetAllOverrides() {
  try {
    await ElMessageBox.confirm(
      `把 ${capOvSavedCount.value} 处覆盖全部清掉，所有能力回到平台默认档（含你手工关掉的那些）。下一轮新会话生效。`,
      '恢复全部跟随平台',
      { type: 'warning', confirmButtonText: '恢复全部', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  tgSaving.value = true;
  try {
    await saveAssistantToolGate({});
    await loadToolGate();
    ElMessage.success('已恢复全部跟随平台');
  } catch {
    // request 层已弹人话报错
  } finally {
    tgSaving.value = false;
  }
}
/** 平台工具的「能做什么」人话说明（技能/外部服务/业务工具走各自数据） */
const CAP_DESC: Record<string, string> = {
  web_search: '在公开网页里搜最新信息；每搜一次算一次联网额度，出网内容先过安全过滤。',
  read_attachment: '读当前会话里用户上传的附件内容（只读自己会话的文件）。',
  submit_plan: '把要做的事列成一张计划卡给用户确认，没有副作用。',
  todo_write: '维护本次会话的任务清单（整表替换式），用户在侧栏能看到进度。',
  save_memory: '把用户偏好写成长期记忆，跨会话保留；用户可在「AI 助手记忆」页删除。',
  ask_user: '拿不准需求时弹一张选择题卡（最多 4 题、每题 2-4 个选项），用户选完再继续，不写入任何数据。',
  read_file: '回读助手工作区里被归档的长工具结果（只在本会话目录、只读），长文件可以分段读。',
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
    // 重载后旧的勾选键可能已经不存在（服务删了/能力改名），清掉免得批量改到看不见的行
    capSel.value = new Set();
    capOpenGate.value = null;
  } catch {
    // 具体错误 request 层已弹；失败态由页面空态文案兜底（切换页签可重试）
  } finally {
    tgLoading.value = false;
  }
}
function resetToolGate() {
  tgDraft.value = { ...tgSaved.value };
}
async function onSaveToolGate(): Promise<boolean> {
  // 快照必须在 await 之前取：请求在飞时用户又改了草稿，不能把没发出去的那份记成「已保存」（会静默丢改动）
  const payload = { ...tgDraft.value };
  tgSaving.value = true;
  try {
    await saveAssistantToolGate(payload);
    tgSaved.value = { ...payload };
    // 保存成功即收掉批量勾选：这批动作已经提交，留着容易对同一批再点一次
    capSel.value = new Set();
    ElMessage.success('已保存——下一轮新会话起生效');
    return true;
  } catch {
    // 值域/工具名校验的人话报错已由 request 层弹出，这里不再叠一层通用文案
    return false;
  } finally {
    tgSaving.value = false;
  }
}

/* ===== 外部工具页签（批2 Q2=A）：MCP 试点服务注册 + 调用审计 ===== */
const mcpLoading = ref(false);
const mcpRows = ref<AssistantMcpServerRow[]>([]);
const mcpLogRows = ref<AssistantMcpCallLog[]>([]);
const mcpLogTotal = ref(0);
const mcpLogPage = ref(1);
/** 题5：审计表唯一的加载态缺口——翻页时既要有遮罩，也要挡住连点 */
const mcpLogLoading = ref(false);
/** F5：审计表加载失败与「还没有真实调用」分开说 */
const mcpLogError = ref(false);
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
/** 题5：翻页/筛选连点时只认最后一次请求，先发后到的旧响应直接丢弃（否则显示错页内容） */
let mcpLogSeq = 0;
let mcpLogRenderedPage = 1; // 回修：最后一次成功渲染的页码——按钮是先改页码再请求，失败要回到这里，别让「第 2 页」配第 1 页内容
async function loadMcpCallLog() {
  const seq = ++mcpLogSeq;
  mcpLogLoading.value = true;
  mcpLogError.value = false; // F5：发起新一轮请求先清失败标志
  try {
    const res = (await pageAssistantMcpCallLog({
      serverName: mcpLogFilter.value || undefined,
      realOnly: !auditShowMounts.value,
      pageNum: mcpLogPage.value,
      pageSize: 20
    })) as unknown as {
      data?: { rows?: AssistantMcpCallLog[]; total?: number; stats?: { all: number; mounts: number; real: number } };
    };
    if (seq !== mcpLogSeq) return;
    mcpLogRows.value = res.data?.rows ?? [];
    mcpLogTotal.value = res.data?.total ?? 0;
    if (res.data?.stats) auditStats.value = res.data.stats;
    mcpLogRenderedPage = mcpLogPage.value;
  } catch {
    if (seq === mcpLogSeq) {
      mcpLogPage.value = mcpLogRenderedPage;
      mcpLogError.value = true; // F5：失败态空态要说「加载失败」，不说「还没有」
    }
    // 同上：request 层已弹
  } finally {
    if (seq === mcpLogSeq) mcpLogLoading.value = false;
  }
}
/** 切换「显示挂载记录」/换服务：回到第 1 页再拉 */
function reloadMcpLog() {
  mcpLogPage.value = 1;
  void loadMcpCallLog();
}
/** 外部服务行里的「审计」：带着服务名跳到运营段（离开编辑先过脏检查） */
async function openMcpAudit(serverName: string) {
  // 走 jumpTo 统一入口：脏检查 + 运营段懒加载。只设 tab 会绕过 onTabChange，
  // 反馈统计/公告/运行记录永远停在「加载中」或假空态（三审 P1）
  const prevFilter = mcpLogFilter.value;
  const prevPage = mcpLogPage.value;
  mcpLogFilter.value = serverName;
  mcpLogPage.value = 1;
  await jumpTo('ops');
  // 跳转被脏检查拦下（用户选「继续编辑」留下）时恢复原值：别让残留的服务名在之后
  // 手动进运营时悄悄过滤调用记录（缺失审计 L1，2026-11-01）
  if (tab.value !== 'ops') {
    mcpLogFilter.value = prevFilter;
    mcpLogPage.value = prevPage;
  }
}
/** 失败行标红：真实调用里最该看的就是失败 */
function auditRowClass({ row }: { row: AssistantMcpCallLog }): string {
  return row.status === '1' ? '' : 'audit-fail';
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

/* ===== 脏检查（5b 清尾·件3）：技能改一半切走不再静默丢——切技能/新增/离能力段先确认 ===== */
// 基线取初始快照而不是空串：没进过能力页签时 snapshotSkill() 也不等于 ''，空串会让路由守卫对没碰过表单的人误弹「未保存」
const skillBaseline = ref(snapshotSkill());
function snapshotSkill(): string {
  return JSON.stringify([
    skillForm.skillId.trim(), skillForm.displayName.trim(), skillForm.description.trim(),
    skillForm.minRole, skillForm.body, skillForm.disabled
  ]);
}
const skillDirty = computed(() => snapshotSkill() !== skillBaseline.value);
function markSkillClean() {
  skillBaseline.value = snapshotSkill();
}
/** 未保存时确认离开：真离开返回 true，用户留下返回 false。
 *  page 模式把「能力权限草稿」也算进来（批量勾几行很容易攒下一堆没保存的改动）。 */
async function confirmLeaveIfDirty(mode: 'skill' | 'page' = 'skill'): Promise<boolean> {
  const dirty = mode === 'page' ? skillDirty.value || tgDirty.value : skillDirty.value;
  if (!dirty) return true;
  const what = skillDirty.value && tgDirty.value ? '技能编辑和能力权限改动都' : skillDirty.value ? '编辑内容还' : '能力权限改动还';
  try {
    await ElMessageBox.confirm(`${what}没保存，离开会丢掉这些改动。`, '有未保存的改动',
      { type: 'warning', confirmButtonText: '离开并丢弃', cancelButtonText: '继续编辑' });
    return true;
  } catch {
    return false;
  }
}
async function confirmOpenSkill(row: AssistantSkillAdminRow) {
  if (!(await confirmLeaveIfDirty())) return;
  openSkill(row);
}
/** 题5：整页路由离开也要拦——原来只有页内切技能/切段有确认，点侧边菜单走人时正文（最多 2 万字）直接丢。
 *  只在真有未保存改动时弹，保存后放行；复用同一句确认文案。 */
onBeforeRouteLeave(async () => {
  if (!skillDirty.value && !tgDirty.value) return true;
  return await confirmLeaveIfDirty('page');
});
/** 回修：F5/关标签页不走 vue-router，脏状态下同样要拦——浏览器原生提示，文案无法自定义 */
onMounted(() => {
  const onUnload = (e: BeforeUnloadEvent) => {
    if (!skillDirty.value && !tgDirty.value) return;
    e.preventDefault();
    e.returnValue = '';
  };
  window.addEventListener('beforeunload', onUnload);
  onUnmounted(() => window.removeEventListener('beforeunload', onUnload));
});
async function confirmNewSkill() {
  if (!(await confirmLeaveIfDirty())) return;
  newSkill();
}

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
  markSkillClean();
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
  markSkillClean();
}
async function cancelNewSkill() {
  // F2（2026-10-31 深审回修）：新增模式里打了一半的正文（上限 2 万字）也是「未保存的改动」，
  // 取消新增和切技能/路由离开同口径，先问再丢。
  if (!(await confirmLeaveIfDirty())) return;
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
    // F7（2026-10-31 复审回修）：保存一成功立刻清脏——列表刷新是网络往返，期间走人会被守卫误弹「未保存」
    markSkillClean();
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
async function onRestoreSkill() {
  const row = activeSkillRow.value;
  if (!row) {
    return;
  }
  // F2（2026-10-31 深审回修）：恢复/删除都会整份重灌表单，未保存编辑会静默丢——
  // 与切技能/新增/路由离开同一条脏检查，先问再动。
  if (!(await confirmLeaveIfDirty())) return;
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
        // 恢复内置后该技能仍在列表（内置层露出）——重开表单刷掉残留的已删在线行内容
        const fresh = skillRows.value.find((r) => r.skillId === row.skillId);
        if (fresh) {
          openSkill(fresh);
        }
      } catch {
        /* 人话报错已由 request 层弹出 */
      } finally {
        skillSaving.value = false;
      }
    })
    .catch(() => undefined);
}

/** 四段导航的懒加载（总览首屏已带齐数字/健康/能力快照，其余段进到才拉）。
 *  三审 P1：按各段权限分别收口——单一权限角色进段不该对无权端点连发请求（每条 403 都会弹通知）。 */
function onTabChange(name: string | number) {
  if (name === 'caps' && canConfig.value) {
    if (tgRows.value.length === 0 && !tgLoading.value) void loadToolGate();
    if (panoSegments.value.length === 0 && !panoLoading.value) void loadPanorama();
    if (mcpRows.value.length === 0 && !mcpLoading.value) void loadMcpServers();
    if (skillRows.value.length === 0 && !skillLoading.value) void loadSkillAdmin();
  }
  if (name === 'rules' && canConfig.value && quotaRows.value.length === 0 && !quotaLoading.value) {
    void loadQuotaPage();
  }
  if (name === 'ops') {
    if (canConfig.value) {
      void loadMcpCallLog();
      if (logRows.value.length === 0) void loadActionLog();
      if (annRows.value.length === 0 && !annLoading.value) void loadAnnouncements();
    }
    if (canFeedback.value) {
      // 回修：先拉列表再拉统计——先把 fbStatsError 清掉的话，loadFeedback 的守卫会连带再发一次统计（总览首次失败时进本页签双发）
      if (fbRows.value.length === 0) void loadFeedback();
      void loadFbStats();
    }
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

/** Q31 回修（2026-10-31）：积分消费下只有「0」是真的限制——回合 0 = 急停；正数一律按不限次执行。
 *  判定口径与后端 billingAwareLimits 一致：单人覆盖 > 租户默认，0 不被套餐/不限覆盖。 */
function quotaRoundStopped(row: AssistantQuotaRow): boolean {
  return (row.roundLimitOverride ?? cfg.value?.dailyRoundLimitPerUser ?? 0) <= 0;
}

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
  if (subActive.value) {
    // Q31 回修：积分消费下回合正数=不限、0=急停；联网正数只在套餐未配档时兜底
    const r = quotaLimitsFollowDefault.value ? null : quotaLimitsForm.roundLimit;
    const w = quotaLimitsFollowDefault.value ? null : quotaLimitsForm.websearchLimit;
    const rt =
      r == null
        ? `回合不限（租户默认 ${tenantRoundDefault.value > 0 ? '正数' : '0 = 急停'}）`
        : r === 0
          ? '回合已停用（0 = 急停）'
          : '回合不限（正数不设上限）';
    const wt =
      w == null
        ? `联网按套餐档位（未配档兜底 ${tenantWebDefault.value} 次）`
        : w === 0
          ? '联网已关闭（0）'
          : `联网按套餐档位（未配档兜底 ${w} 次）`;
    return `${rt} · ${wt}`;
  }
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
      subActive.value
        ? `把「${name}」今天的回合与联网计数清零？（积分消费下回合不限次，联网每天按套餐档位计数，用完要靠这里重置）`
        : `把「${name}」今天的已用回合（${row.turnsUsedToday} / ${row.effectiveRoundLimit}）清零？清零后 ${name} 今天还能继续聊。`,
      '重置今日额度',
      { type: 'warning', confirmButtonText: '确定重置', cancelButtonText: '再想想' }
    );
  } catch {
    return;
  }
  try {
    await resetAssistantQuota(row.userId);
    ElMessage.success(
      subActive.value
        ? `已重置——${name} 今天的回合与联网计数已清零`
        : `已重置——${name} 今天的额度已恢复满额`
    );
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
  flex-wrap: wrap;
  gap: 18px;
  align-items: stretch;
}
.fb-rate {
  /* 题2：定宽 170 的块在窄屏会顶破——允许换行、窄屏由媒体查询改整行 */
  flex: 0 0 170px;
  max-width: 100%;
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
  /* 题3：小标题与正文同 12px 会失去层级——按就近档升到 13（弱化交给次级灰，不靠缩字号） */
  font-size: 13px;
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
/* 题4：统计卡自己的失败/加载态（块内提示，不整屏报错） */
.fb-fail {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  font-size: 12px;
  color: var(--dcs-text-secondary);

  span {
    min-width: 0;
  }
  .el-button {
    margin-left: auto;
  }
}
.dcs-as-admin {
  /* 2026-11-01 三稿：二稿的 1240 阅读列在 1920 上成了「窄列居中岛」（两侧各空 212px），
     用户点名太居中。改成：1920 内容区（1664）内不再封顶，超宽屏才收到 1760；
     ≥1600 视口能力清单摊成两列，让内容真实占住宽度。1440 及以下本来 ≤1240，版式不变。 */

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
          font-size: 16px;
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
        font-size: 12px;
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
      font-size: 14px;
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
    font-size: 12px;
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
    font-size: 12px;
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
      font-size: 12px;
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

    /* 题6：EP 的 .el-input__wrapper{flex-grow:1} 也命中日期框，1920 实测被吃到 1048px——
       只改 width 无效，必须同时压掉 flex-grow（frontend/AGENTS.md：attribute 值≠渲染值） */
    :deep(.el-date-editor) {
      flex-grow: 0 !important;
      width: 340px !important;
      max-width: 100%;
    }
  }
  .log-pager {
    display: flex;
    justify-content: flex-end;
    padding-top: 12px;
  }
  /* 题3：全局 theme-framed.scss:802 把「所有文字按钮」统一染灰（.el-button.is-link），
     压过了按钮自己声明的 danger——只在本页把它们提回危险红，不动全局主题 */
  :deep(.el-button.is-link.el-button--danger),
  :deep(.el-button.is-text.el-button--danger) {
    color: var(--el-color-danger) !important;

    &:hover {
      color: var(--el-color-danger) !important;
    }
  }
  /* 题2：操作列钉右改用 el-table 原生 fixed="right"（EP 会自己铺定位层与分隔线）。
     曾试过 CSS position: sticky 版本，768 下操作列会压在「本月费用」列的文字上——别回退这条路。 */
  /* 题6 的外部服务清单上限已于 2026-11-01 Q2=乙 撤掉：卡片内部不再套第二层宽度上限 */

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
      font-size: 12px;
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
        font-size: 12px;

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
      font-size: 14px;
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
        font-size: 12px;
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
      font-size: 14px;
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
    /* Q31：原「改默认」没有定位，会作为行内元素掉到文案下面、悬在条子中间；
       改成钉在租户默认条的右上角（钱包态/停用态都读得出来这是个控件） */
    position: relative;
    padding-right: 84px;
    .qo-edit-btn {
      position: absolute;
      right: 8px;
      top: 6px;
      margin: 0;
    }
  }
  .qo-k {
    font-size: 12px;
    color: var(--dcs-text-secondary);
    display: flex;
    align-items: center;
    gap: 5px;
  }
  .qo-v {
    font-size: 14px;
    color: var(--dcs-text-primary);
    b {
      font-size: 16px;
      font-weight: 700;
      color: var(--dcs-text-primary);
    }
  }
  .qo-note {
    font-size: 12px;
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
    font-size: 12px;
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
    font-size: 12px;
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
      font-size: 14px;
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
  font-size: 12px;
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
/* Q31：积分消费下「已用 / 不限」——只读口径，用灰字压一档，别读成正常额度 */
.q-usage .q-unlimited {
  color: var(--dcs-text-secondary);
  b {
    color: var(--el-color-primary);
    font-weight: 600;
  }
}
/* Q31 回修（2026-10-31）：0 = 急停的行不显示「不限」，改「已停用」危险色 */
.q-stopped {
  color: var(--el-color-danger);
  font-weight: 600;
}
.q-usage .q-unlimited b.q-stopped {
  color: var(--el-color-danger);
}
/* Q31：积分消费停用态——额度相关的两块统一降噪（仍可读），
   「禁用/启用」是账号闸不是额度闸，保持可用、不受此规则影响 */
.quota-off {
  .quota-overview {
    opacity: 0.72;
    filter: grayscale(40%);
  }
  .quota-turn-cell {
    opacity: 0.78;
  }
}
.quota-offline {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 12px;
  padding: 9px 12px;
  border: 1px solid var(--el-color-info-light-7);
  border-radius: var(--app-control-radius);
  background: var(--el-color-info-light-9);
  color: var(--dcs-text-secondary);
  font-size: 12px;
  line-height: 1.6;
  b {
    color: var(--dcs-text-primary);
    font-weight: 600;
  }
  .qo-ico {
    color: var(--el-color-info);
    flex: none;
  }
}
.sec-off-tag {
  margin-left: 8px;
  font-weight: 400;
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
    font-size: 12px;
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
      font-size: 16px;
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
    font-size: 12px;
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
        font-size: 14px;
        font-weight: 600;
        color: var(--dcs-text-primary);
      }
      p {
        margin: 2px 0 0;
        font-size: 12px;
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
        font-size: 14px;
        font-weight: 600;
      }
      span {
        font-size: 12px;
        color: var(--dcs-text-secondary);
      }
    }
  }

  /* ---- 能力清单 ---- */
  .cap-note {
    margin-bottom: 10px;
    font-size: 12px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
  }
  /* 体检一句：卡内一条安静的状态行（不再单独一条色带，分割感从这里开始收） */
  .cap-health {
    display: flex;
    align-items: center;
    gap: 9px;
    flex-wrap: wrap;
    min-height: 28px;
    padding: 0 2px 8px;
    font-size: 13px;
    line-height: 1.65;
    color: var(--dcs-text-secondary);

    .sp {
      flex: 1;
    }
    .ch-dot {
      flex: none;
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--el-color-warning);
    }
    .ch-lead {
      font-size: 13px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    .ch-names {
      display: inline-flex;
      align-items: center;
      gap: 2px;
      flex-wrap: wrap;
      font-size: 12px;

      button {
        padding: 0;
        border: 0;
        background: transparent;
        font: inherit;
        color: inherit;
        text-decoration: underline;
        text-underline-offset: 2px;
        text-decoration-color: color-mix(in srgb, currentColor 32%, transparent);
        cursor: pointer;

        &:hover {
          text-decoration-color: currentColor;
        }
      }
    }
    /* 无事态：绿点 + 一句话，语气放平 */
    &.ok2 {
      .ch-dot {
        background: var(--el-color-success);
      }
      .ch-lead {
        font-weight: 400;
        color: var(--dcs-text-secondary);
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
  .risk-badge {
    display: inline-block;
    padding: 0 7px;
    border-radius: 8px;
    font-size: 12px;
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
      /* 原 #b88230 在中危底色上只有 3.1:1，压深到 5.5:1 */
      color: #8a5a10;
    }
    &.low {
      background: var(--el-fill-color-light);
      color: var(--dcs-text-secondary);
    }
  }

  /* 按角色看：只换「谁能用」一列的读法，不另起版式 */
  .cap-lens {
    display: inline-flex;
    padding: 2px;
    border-radius: 7px;
    background: var(--el-fill-color-light);

    button {
      height: 23px;
      padding: 0 8px;
      border: 0;
      border-radius: 5px;
      background: transparent;
      font-family: inherit;
      font-size: 12px;
      color: var(--dcs-text-secondary);
      white-space: nowrap;
      cursor: pointer;

      &:hover {
        color: var(--dcs-text-primary);
      }
      &[aria-pressed='true'] {
        background: var(--el-bg-color);
        font-weight: 600;
        color: var(--dcs-text-primary);
        box-shadow: 0 1px 2px rgb(16 24 40 / 8%);
      }
    }
  }

  /* 能力表：名字左、属性右（风险 + 谁能用成簇），整行可点进抽屉 */
  /* 能力卡（2026-11-01 二稿）：本卡内二级/常规文字统一提到 ≥4.5:1。
     选中行与批量条是浅蓝底（primary-light-9），运行态的 #5f7083 在上面只有 4.45:1，这里一并压深。
     整页已经收在 1240 阅读列里，这里只负责给清单一块安静的白底，不再各自算宽度。 */
  .cap-card {
    padding: 16px 20px 12px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-light);
    border-radius: var(--app-card-radius);
    --dcs-text-secondary: #5b6673;
    --el-text-color-secondary: #5b6673;
    --dcs-text-regular: #4b5563;
    --el-text-color-regular: #4b5563;
  }
  /* 工具栏（丁案）：卡片第一行，位置永远固定 */
  .cap-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
    padding-bottom: 8px;
  }
  .cap-bar-ttl {
    display: flex;
    align-items: baseline;
    gap: 8px;
    flex-wrap: wrap;

    b {
      font-size: 16px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
  }
  .cap-bar-cnt {
    font-size: 12.5px;
    color: var(--dcs-text-secondary);
    font-variant-numeric: tabular-nums;
  }
  /* 业务工具归属的说明：一行小字，靠 title 展开详情，不再单占一段 */
  .cap-tip {
    font-size: 12.5px;
    color: var(--dcs-text-secondary);
    cursor: help;
    border-bottom: 1px dashed var(--el-border-color);
  }
  .cap-bar-tools {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }
  .cap-search {
    width: 168px;
  }

  /* 能力清单（2026-11-01 二稿）：两行式设置清单，不做表格——
     一行只有「勾选 / 名字+条件 / 档位」三块：没有表头、没有风险列、没有详情列、没有本租户列。
     分组靠留白分，不靠色带；行间不画满宽横线，靠两行文字自己的节奏分条（悬停才给浅底）。 */
  .cap-tbl {
    margin-top: 2px;
  }
  /* 2026-11-01 三稿：宽屏两列。分组标题横跨两列，每条「行 + 行内改档」留在自己那一列，
     不用再靠居中留白撑版面；1440 及以下保持单列（二稿版式）。 */
  @media (min-width: 1600px) {
    .cap-tbl {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      column-gap: 32px;
      align-items: start;
    }
    .cap-ghead {
      grid-column: 1 / -1;
    }
    /* 两列后单行只剩 ~620px，长说明允许折两行——宁可行高不均，也不把说明截成半句。
       选择器多带一层 .cap-tbl：基础 .cap-sub（nowrap）写在后面，同权重会被它压掉。 */
    .cap-tbl .cap-sub {
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      white-space: normal;
    }
  }
  .cap-item {
    min-width: 0;
  }
  .cap-cb {
    height: auto;
    margin: 0;
  }
  .cap-ghead {
    display: flex;
    align-items: baseline;
    gap: 8px;
    flex-wrap: wrap;
    padding: 20px 2px 4px;
    font-size: 12px;

    &.first {
      padding-top: 6px;
    }
    b {
      font-size: 12.5px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    .n,
    .hint {
      font-size: 12px;
      color: var(--dcs-text-secondary);
    }
  }
  .cap-row {
    display: grid;
    grid-template-columns: 22px minmax(0, 1fr) auto;
    column-gap: 10px;
    align-items: center;
    min-height: 52px;
    margin: 0;
    padding: 8px 6px;
    border-radius: 6px;
    cursor: pointer;
    outline: none;

    &:hover {
      background: var(--el-fill-color-lighter);

      .cap-go {
        transform: translateX(2px);
        opacity: 1;
      }
    }
    &.sel {
      background: var(--el-color-primary-light-9);
    }
    &:focus-visible {
      outline: 2px solid var(--el-color-primary);
      outline-offset: -2px;
    }
    &.closed .cap-nm {
      color: var(--dcs-text-secondary);
      text-decoration: line-through;
      text-decoration-color: var(--dcs-text-placeholder);
    }
    &.dim {
      opacity: 0.62;
    }
    &.dim:hover {
      opacity: 1;
    }
  }
  /* 名字一行、条件一行；桌面上条件单行省略（title 给全文），手机上折行显示 */
  .cap-main {
    min-width: 0;
  }
  .cap-line {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }
  .cap-nm {
    flex: none;
    max-width: 60%;
    font-size: 14px;
    font-weight: 600;
    line-height: 20px;
    color: var(--dcs-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  /* 出现条件跟在名字后面（短句），窄屏收进第二行——字段不丢，只换位置 */
  .cap-when {
    min-width: 0;
    font-size: 12.5px;
    color: var(--dcs-text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .cap-when-m {
    display: none;
  }
  /* 行与灰行共用的一套名字排版（同一页只有一档字号，别各写一套） */
  .cap-name {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;

    b {
      font-size: 14px;
      font-weight: 600;
      color: var(--dcs-text-primary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }
  .cap-sub {
    margin-top: 1px;
    font-size: 12.5px;
    line-height: 18px;
    color: var(--dcs-text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .lowdot {
    display: inline-block;
    flex: none;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #6b7280;
  }
  /* 右侧动作簇：谁都能点的一档按钮 + 一个只做提示的箭头（整行本身就进抽屉） */
  .cap-act {
    display: flex;
    align-items: center;
    gap: 2px;
    margin-left: 8px;
    font-variant-numeric: tabular-nums;
  }
  .gate-btn {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    max-width: 100%;
    height: 26px;
    padding: 0 6px;
    border: 1px solid transparent;
    border-radius: 5px;
    background: transparent;
    font: inherit;
    font-size: 12.5px;
    color: var(--dcs-text-regular);
    white-space: nowrap;
    cursor: pointer;

    &:hover,
    &[aria-expanded='true'] {
      border-color: var(--el-border-color);
      background: var(--el-bg-color);
    }
    .car {
      flex: none;
      width: 12px;
      height: 12px;
      color: var(--dcs-text-secondary);
    }
    &.gate-no {
      color: var(--dcs-text-secondary);
    }
    &.gate-off {
      color: var(--dcs-text-secondary);
      text-decoration: line-through;
      text-decoration-color: var(--dcs-text-placeholder);
    }
  }
  .gate-own {
    font-size: 11.5px;
    font-weight: 400;
    color: var(--dcs-text-secondary);
  }
  .cap-go {
    flex: none;
    width: 12px;
    height: 12px;
    margin-left: 6px;
    color: var(--dcs-text-secondary);
    opacity: 0.55;
    transition:
      transform 0.15s,
      opacity 0.15s;
  }
  /* 抽屉抬头仍用文字（列表里低危改灰点，不再写「低危」两个字） */
  .lowrisk {
    font-size: 12px;
    color: var(--dcs-text-secondary);
  }
  /* 行内改档：在行下方展开，不弹层、不遮别的行 */
  .cap-x {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    margin: 0 0 6px 38px;
    padding: 6px 10px 8px;
    border-radius: 6px;
    background: var(--el-fill-color-lighter);

    .lbl {
      font-size: 12.5px;
      color: var(--dcs-text-secondary);
    }
    .xopt {
      padding: 3px 10px;
      border: 1px solid var(--el-border-color);
      border-radius: 6px;
      background: var(--el-bg-color);
      font: inherit;
      font-size: 12.5px;
      color: var(--dcs-text-regular);
      cursor: pointer;

      &:hover {
        border-color: var(--el-color-primary-light-5);
        color: var(--el-color-primary);
      }
      &.on {
        border-color: var(--el-color-primary-light-5);
        background: var(--el-color-primary-light-9);
        color: #1d4ed8;
        font-weight: 500;
      }
      &.dis {
        opacity: 0.5;
        cursor: not-allowed;
      }
    }
    .kbd {
      margin-left: auto;
      font-size: 12px;
      color: var(--dcs-text-secondary);
    }
  }
  /* 底部吸附区：批量条与保存条同住一层，一起停在视口底部 */
  .cap-dock {
    position: sticky;
    bottom: 0;
    z-index: 2;
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-top: 10px;
  }
  /* 勾选批量条：勾了才出现，不占地 */
  .cap-batch {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    padding: 7px 10px;
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: 7px;
    background: var(--el-color-primary-light-9);
    font-size: 12.5px;
    color: var(--dcs-text-regular);

    .n b {
      font-variant-numeric: tabular-nums;
    }
    /* 链接按钮在浅蓝底上同样压深一档（EP 的 link 色不跟卡内 token） */
    .el-button.is-link {
      color: #1d4ed8;
    }
    .kbd {
      margin-left: auto;
      font-size: 12px;
      color: var(--dcs-text-secondary);
    }
  }
  /* 页脚：已保存几处 + 三个低频出口 + 恢复；同一行，不再各占一段 */
  .cap-foot {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    margin-top: 12px;
    padding: 10px 2px 0;
    border-top: 1px solid var(--el-border-color-lighter);
    font-size: 12px;
    color: var(--dcs-text-secondary);

    .sp {
      flex: 1;
    }
    .cap-more-links {
      display: inline-flex;
      align-items: center;
      gap: 14px;
      flex-wrap: wrap;

      button {
        height: 22px;
        padding: 0;
        border: 0;
        background: transparent;
        font: inherit;
        color: #1d4ed8;
        cursor: pointer;

        b {
          margin-left: 2px;
          font-weight: 500;
          font-variant-numeric: tabular-nums;
        }
        &:hover {
          text-decoration: underline;
        }
      }
    }
    .cap-reset.el-button {
      height: auto;
      padding: 0;
      font-size: 12px;
    }
  }
  /* 外部服务：一行一个服务，不再用 8 列表格（长命令会把表格撑出横向滚动） */
  .svc-list {
    border-top: 1px solid var(--el-border-color-lighter);
  }
  .svc-line {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    padding: 10px 2px;
    border-bottom: 1px solid var(--el-border-color-lighter);

    b {
      font-size: 14px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 12px;
      color: var(--dcs-text-secondary);
    }
    .st {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-size: 12px;
      color: var(--dcs-text-secondary);

      .d {
        width: 5px;
        height: 5px;
        border-radius: 50%;
        background: var(--el-color-success);
      }
    }
    &.off .st .d {
      background: var(--el-text-color-placeholder);
    }
    .muted {
      font-size: 12px;
      color: var(--dcs-text-secondary);
    }
    .svc-cmd {
      max-width: 260px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .svc-hd {
      color: var(--el-text-color-placeholder);
    }
    .acts {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-left: auto;
    }
    .acts .el-button {
      margin-left: 0;
    }
  }
  /* 低频编辑区（技能库/行为总纲）：标题本身就是出口，默认收起 */
  .cap-fold .cap-fold-head {
    align-items: center;
    justify-content: flex-start;
    margin-bottom: 0;
    cursor: pointer;
    user-select: none;

    .fold-arr {
      flex: none;
      font-size: 12px;
      color: var(--el-text-color-placeholder);
      transition: transform 0.15s;
    }
    .sec-acts {
      margin-left: auto;
    }
    &:hover b {
      color: var(--el-color-primary);
    }
  }
  .cap-fold.open .cap-fold-head {
    margin-bottom: 9px;
  }
  .cap-fold.open .fold-arr {
    transform: rotate(90deg);
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
      font-size: 12px;
      color: var(--dcs-text-secondary);
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
    color: var(--dcs-text-secondary);
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
  .cap-save {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    padding: 10px 14px;
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: var(--app-control-radius);
    background: var(--el-color-primary-light-9);
    font-size: 12px;
    color: var(--dcs-text-primary);

    b {
      /* 数量在浅蓝底上，#2f6feb 只有 4:1，压深一档 */
      color: #1d4ed8;
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
        font-size: 12px;
        line-height: 18px;
        text-align: center;
        color: var(--el-text-color-placeholder);
      }
      /* 技能行多一块统计键值（第三行），序号图标要跨满三行 */
      i.ski {
        grid-row: span 3;
      }
      .pt {
        font-size: 13px;
        color: var(--dcs-text-primary);
      }
      .pf {
        font-size: 12px;
        color: var(--el-text-color-placeholder);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      /* 题2 案3：两列键值（用次 / 最近一次），窄栏也放得下；数字用等宽。
         11px 小字用常规墨色保证可读；无记录时数值降到占位色，避免和真实数据同权 */
      .pu {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr);
        gap: 2px 8px;
        font-size: 11px;
        color: var(--el-text-color-regular);
        font-variant-numeric: tabular-nums;
        span {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        .pu-k {
          color: var(--el-text-color-regular);
        }
        .pu-v {
          text-align: right;
          font-weight: 500;
        }
        &.pu-zero .pu-v {
          color: var(--el-text-color-placeholder);
          font-weight: 400;
        }
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
      font-size: 12px;
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
    font-size: 12px;
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
    font-size: 12px;
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
    font-size: 12px;
    color: var(--dcs-text-secondary);

    b {
      font-size: 16px;
      color: var(--dcs-text-primary);
      font-variant-numeric: tabular-nums;
    }
    em {
      margin-left: 5px;
      font-style: normal;
      font-size: 12px;
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

/* 抽屉根节点由 el-drawer 渲染，身上没有 scope id——这两条必须走 :global */
:global(.cap-detail-drawer) {
  max-width: 100%;
}
:global(.cap-detail-drawer .el-drawer__body) {
  display: flex;
  flex-direction: column;
  padding: 0;
}
.cap-detail-drawer {
  .sys-prompt {
    margin: 0;
    padding: 12px 14px;
    max-height: 420px;
    overflow: auto;
    font-size: 12px;
    line-height: 1.8;
    color: var(--dcs-text-regular);
    white-space: pre-wrap;
    word-break: break-word;
    background: var(--el-fill-color-lighter);
    border-radius: var(--app-control-radius);
  }
  .cd {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-height: 0;
  }
  .cd-head {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    padding: 14px 17px 12px;
    border-bottom: 1px solid var(--el-border-color-light);
  }
  .cd-head-tx {
    min-width: 0;
  }
  .cd-title {
    display: flex;
    align-items: center;
    gap: 7px;
    margin: 0;
    font-size: 16px;
    font-weight: 600;
    line-height: 22px;
    color: var(--dcs-text-primary);
  }
  .cd-sub {
    margin-top: 3px;
    font-size: 12px;
    color: var(--dcs-text-secondary);
  }
  .cd-x {
    margin-left: auto;
    width: 26px;
    height: 26px;
    border: 0;
    border-radius: 6px;
    background: transparent;
    font-size: 18px;
    line-height: 1;
    color: var(--el-text-color-placeholder);
    cursor: pointer;

    &:hover {
      background: var(--el-fill-color-light);
      color: var(--dcs-text-primary);
    }
  }
  .cd-body {
    flex: 1;
    min-height: 0;
    padding: 14px 17px 16px;
    overflow: auto;
  }
  .cd-sec {
    margin-top: 16px;

    &:first-child {
      margin-top: 0;
    }
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
  .cd-lb {
    display: flex;
    align-items: baseline;
    gap: 8px;
    margin-bottom: 7px;

    h4 {
      margin: 0;
    }
    .lb-note {
      font-size: 12px;
      color: var(--el-text-color-placeholder);
    }
    .sp {
      flex: 1;
    }
  }
  .cap-opts {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .cap-opt {
    display: flex;
    align-items: flex-start;
    gap: 9px;
    padding: 9px 11px;
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
    &.dis {
      opacity: 0.5;
      cursor: default;
    }
    .rd {
      flex: none;
      width: 13px;
      height: 13px;
      margin-top: 2px;
      border: 1px solid var(--el-border-color);
      border-radius: 50%;
      background: var(--el-bg-color);
      box-sizing: border-box;
    }
    &.on .rd {
      border-color: var(--el-color-primary);
      box-shadow: inset 0 0 0 3px var(--el-color-primary);
    }
    .opt-tx {
      min-width: 0;
    }
    .t {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 13px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    .d {
      margin-top: 2px;
      font-size: 12px;
      line-height: 1.6;
      color: var(--dcs-text-secondary);
    }
    .now {
      padding: 0 5px;
      border-radius: 4px;
      background: var(--el-fill-color);
      font-size: 12px;
      font-weight: 500;
      line-height: 15px;
      color: var(--dcs-text-secondary);
    }
  }
  .cap-lock-note {
    margin-top: 6px;
    font-size: 12px;
    color: var(--el-text-color-placeholder);
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
      margin-right: 8px;
      font-weight: 600;
      color: var(--dcs-text-primary);
    }
    .cd-changed {
      color: var(--el-color-primary);
      font-weight: 600;
    }
  }
  .cd-tech {
    margin-top: 16px;
    font-size: 12px;

    summary {
      color: var(--dcs-text-secondary);
      cursor: pointer;
      user-select: none;
    }
  }
  .cd-kv {
    display: flex;
    gap: 10px;
    padding: 4px 0;
    font-size: 12px;
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
  .cd-foot {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 17px;
    border-top: 1px solid var(--el-border-color-light);
    background: var(--el-bg-color);

    .sp {
      flex: 1;
    }
    .cd-dirty {
      font-size: 12px;
      color: var(--el-text-color-placeholder);
    }
    .el-button {
      margin-left: 0;
    }
  }
}

/* ===== 一键推荐浮层（append-to-body） ===== */
.reco-dialog {
  .reco-sub {
    margin: -4px 0 12px;
    font-size: 12px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
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
      font-size: 14px;
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
      font-size: 12px;
      font-weight: 500;
      line-height: 16px;
      color: #fff;
    }
    .pol-cnt {
      display: inline-block;
      margin-top: 6px;
      font-size: 12px;
      color: var(--dcs-text-secondary);
    }
    &.on .pol-cnt {
      font-weight: 600;
      color: var(--el-color-primary);
    }
  }
  .reco-sum {
    margin: 12px 0 8px;
    font-size: 13px;
    color: var(--dcs-text-primary);

    b {
      font-size: 16px;
      font-variant-numeric: tabular-nums;
      color: var(--el-color-primary);
    }
    .sec-note {
      font-size: 12px;
      color: var(--dcs-text-secondary);
    }
  }
  .preset-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 12px;

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
    .pt-nm {
      color: var(--dcs-text-primary);
    }
    .pt-from {
      color: var(--dcs-text-secondary);
    }
    .pt-to {
      font-weight: 600;
      color: var(--el-color-success);
    }
    .pt-loosen {
      display: inline-block;
      margin-left: 6px;
      padding: 0 5px;
      border-radius: 4px;
      background: var(--el-color-warning-light-9);
      font-size: 12px;
      font-weight: 500;
      line-height: 16px;
      color: var(--el-color-warning-dark-2);
    }
    td:last-child {
      text-align: center;
    }
  }
  .preset-none {
    padding: 14px 4px;
    font-size: 12px;
    color: var(--dcs-text-secondary);
  }
  .rule-box {
    margin-top: 10px;
    font-size: 12px;

    summary {
      color: var(--dcs-text-secondary);
      cursor: pointer;
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
      .on {
        background: var(--el-color-primary-light-9);
        font-weight: 600;
        color: var(--el-color-primary);
      }
    }
  }
  .preset-foot {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 10px;
    flex-wrap: wrap;

    .sec-note {
      margin-right: auto;
      max-width: 420px;
      font-size: 12px;
      line-height: 1.6;
      text-align: left;
      color: var(--dcs-text-secondary);
    }
  }
}

@media (max-width: 1100px) {
  .dcs-as-admin .ov-grid {
    grid-template-columns: 1fr;
  }
}
/* 题6：统计条补 992 断点（与同卡健康格子同档）——768 落在 761-1100 无人管区间，费用一长就撑破卡片 */
@media (max-width: 992px) {
  .dcs-as-admin .stat-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
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
  /* F11（2026-10-31 复审回修）：公告表单在窄屏被按钮挤成一条缝、占位截断——输入放开行内定宽/弹性占满一行，完整可见 */
  .dcs-as-admin .ann-form .el-input {
    flex: 1 1 100% !important;
    width: 100% !important;
  }
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
  .dcs-as-admin .cap-row {
    grid-template-columns: 20px minmax(0, 1fr) auto;
    column-gap: 8px;
    padding: 10px 8px;

    .cap-cb {
      align-self: center;
    }
    /* 手机上名字不截断：宁可让档位按钮换行，也不把「技能：催办话术」切成「技能：催办…」 */
    .cap-nm {
      max-width: none;
    }
    .cap-sub {
      display: -webkit-box;
      white-space: normal;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }
    .cap-when {
      display: none;
    }
    .cap-when-m {
      display: inline;
    }
    /* 手机上整行可点，箭头省掉；「已收紧」也不在窄屏挤档位（抽屉里仍有） */
    .cap-go,
    .gate-own {
      display: none;
    }
  }
  .dcs-as-admin .cap-x {
    margin-left: 28px;

    .kbd {
      display: none;
    }
  }
  /* 手机上把复选框与「谁能用」的点击面积放大到指头够得着 */
  .dcs-as-admin .cap-cb {
    padding: 6px 0;
  }
  .dcs-as-admin .gate-btn {
    padding: 6px 8px;
    margin-right: -8px;
  }
  .dcs-as-admin .cap-all {
    display: none;
  }
  .dcs-as-admin .cap-batch .kbd {
    display: none;
  }
  .dcs-as-admin .cap-bar-tools {
    width: 100%;
    gap: 8px;
  }
  .dcs-as-admin .cap-search {
    flex: 1 1 100%;
    width: auto;
  }
  .dcs-as-admin .cap-ghead .hint {
    display: none;
  }
  .dcs-as-admin .svc-line .acts {
    width: 100%;
    margin-left: 0;
    justify-content: flex-end;
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
  .dcs-as-admin .cap-save .el-button {
    margin-left: 0;
  }
  /* 浮层挂在 body 下，且根节点没有 scope id——媒体查询里用 :global 才生效 */
  :global(.reco-dialog) {
    width: calc(100% - 16px) !important;
    max-width: calc(100% - 16px);
    margin: 8px auto;
  }
  .reco-dialog .pol-grid {
    grid-template-columns: 1fr;
    gap: 6px;
  }
  .reco-dialog .preset-foot .sec-note {
    display: none;
  }

  /* ===== 题1：≤760 窄屏规则包——设置表堆叠 / 下拉满宽 / 弹层不超屏 / 分页只留前后页 ===== */
  .dcs-as-admin .adm-desc :deep(.el-descriptions__table),
  .dcs-as-admin .adm-desc :deep(.el-descriptions__body) {
    display: block;
    width: 100%;
  }
  /* tbody/tr 没有类名，只改 table 的 display 不够——行仍是 table-row，格子会被行宽撑到 611px */
  .dcs-as-admin .adm-desc :deep(.el-descriptions__body table > tbody),
  .dcs-as-admin .adm-desc :deep(.el-descriptions__body table > tbody > tr) {
    display: block;
    width: 100%;
  }
  .dcs-as-admin .adm-desc :deep(.el-descriptions__cell) {
    display: block;
    width: 100%;
  }
  .dcs-as-admin .adm-desc :deep(.el-descriptions__label) {
    width: 100%;
    min-width: 0;
    border-right: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }
  .dcs-as-admin .adm-desc :deep(.el-descriptions__content) {
    width: 100%;
  }
  /* 默认模型下拉在模板里 inline 写死 340px——窄屏必须 !important 才压得住 */
  .dcs-as-admin .cell-flex :deep(.el-select),
  .dcs-as-admin .cell-flex :deep(.el-input) {
    width: 100% !important;
    max-width: 100%;
  }
  /* 分页器：窄屏只留 上一页 / 页码 / 下一页 */
  .dcs-as-admin .log-pager {
    justify-content: center;
  }
  .dcs-as-admin .log-pager :deep(.el-pagination__total),
  .dcs-as-admin .log-pager :deep(.el-pagination__jump) {
    display: none;
  }
  /* 页签按钮 28×36 → 44 高（窄屏是手指在点） */
  .dcs-as-admin .adm-seg button {
    display: inline-flex;
    align-items: center;
    min-height: 44px;
    padding: 0 2px;
  }
  /* 反馈统计条：横排三块挤压、标题断行 → 纵向三段 */
  .dcs-as-admin .fb-stats {
    flex-direction: column;
    gap: 12px;
  }
  .dcs-as-admin .fb-rate,
  .dcs-as-admin .fb-reasons,
  .dcs-as-admin .fb-trend {
    flex: 1 1 auto;
    width: 100%;
    min-width: 0;
  }
  .dcs-as-admin .fb-reason-name {
    width: 96px;
  }
  /* 浮层/抽屉：宽度上限＝屏宽（append-to-body，根节点没有 scope id，必须 :global）。
     F14（2026-10-31 复审回修）：原 width !important 会把 500-760 视口区间的弹窗也拉满宽——改只封顶，放得下就保持原宽 */
  :global(.ov-dialog),
  :global(.q-dialog),
  :global(.ws-dialog) {
    max-width: calc(100% - 16px);
  }
  :global(.q-dialog .q-preview),
  :global(.q-dialog .field-hint) {
    margin-left: 0;
    padding-left: 0;
  }
  :global(.mcp-form-drawer.el-drawer) {
    width: 100vw !important;
    max-width: 100vw;
  }
}

</style>
