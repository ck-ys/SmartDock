package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.core.DatabaseSkillRepository;
import org.dromara.assistant.core.SkillCacheBroadcaster;
import org.dromara.assistant.core.SkillAssets;
import org.dromara.assistant.domain.DcsAssistantSkill;
import org.dromara.assistant.mapper.AssistantSkillMapper;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能在线层管理端点（5b 批，题4 乙/题5 甲）：列表/详情/保存/删除（=恢复内置）。
 *
 * <p>写权限独立串 {@code dcs:assistant:skill:edit}（超管默认有，租户管理员按需勾——
 * 敏感级与「改租户预算」不同权）。保存四防呆：①对内置覆写最低角色只升不降；②同租户
 * 名称唯一（覆写同名内置=允许）；③正文 ≤2 万字＋一句话说明 ≤1024；④写后即时失效缓存
 * ＋Redis 广播（题3=乙）。删除在线行=下层（内置/目录）自动露出，恢复出厂无损。</p>
 */
@Tag(name = "助手技能管理", description = "技能在线层：列表/详情/保存/删除")
@RestController
@RequestMapping("/dcs/assistant/skill")
@RequiredArgsConstructor
public class AssistantSkillAdminController extends BaseController {

    /** 题5 防呆③上限：正文（字）与一句话说明（字，对齐 DDL varchar(1024)） */
    static final int BODY_MAX = 20_000;
    static final int DESC_MAX = 1024;

    private final AssistantSkillMapper skillMapper;

    /** 管理列表：生效技能（三层叠加后）∪ 停用行（管理台要看得见停用的），带来源徽章与内置档 */
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list() {
        String tenantId = LoginHelper.getTenantId();
        Map<String, DcsAssistantSkill> dbRows = indexBySkillId(skillMapper.selectAllForTenant(tenantId));
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SkillAssets.LoadedSkill s : SkillAssets.skills(tenantId)) {
            Map<String, Object> row = baseRow(s);
            DcsAssistantSkill mine = rowFor(dbRows, tenantId, s.id());
            DcsAssistantSkill global = rowFor(dbRows, DatabaseSkillRepository.GLOBAL_TENANT, s.id());
            boolean hasBuiltin = !builtinMinRoleName(s.id()).isEmpty();
            String source;
            if (mine != null) {
                source = hasBuiltin ? "online-overwrite" : "online-new";
            } else if (global != null && (global.getDisabled() == null || global.getDisabled() == 0)) {
                source = "online-overwrite";
            } else {
                source = "builtin";
            }
            row.put("source", source);
            row.put("builtinMinRole", builtinMinRoleName(s.id()));
            out.add(row);
            seen.add(s.id());
        }
        // 停用行：不在生效视图里（disabled=1 把技能整体藏了），管理台单独补出
        for (String key : dbRows.keySet()) {
            DcsAssistantSkill r = dbRows.get(key);
            if (r.getDisabled() == null || r.getDisabled() == 0 || !key.startsWith(tenantId + ":")
                || seen.contains(r.getSkillId())) {
                continue;
            }
            Map<String, Object> row = baseRow(new SkillAssets.LoadedSkill(r.getSkillId(),
                r.getDisplayName() == null || r.getDisplayName().isBlank() ? r.getSkillId() : r.getDisplayName(),
                r.getDescription(), DatabaseSkillRepository.minRoleOf(r.getMinRole()), r.getBody()));
            row.put("source", builtinMinRoleName(r.getSkillId()).isEmpty()
                ? "online-disabled" : "builtin-disabled");
            row.put("builtinMinRole", builtinMinRoleName(r.getSkillId()));
            out.add(row);
            seen.add(r.getSkillId());
        }
        return R.ok(out);
    }

    /** 详情：生效内容（merged）+ 本租户在线行（编辑回填用）+ 内置基线（恢复预览用） */
    @GetMapping("/{skillId}")
    public R<Map<String, Object>> detail(@PathVariable String skillId) {
        String tenantId = LoginHelper.getTenantId();
        SkillAssets.LoadedSkill eff = findEffective(skillId, tenantId);
        if (eff == null) {
            return R.fail("没有这个技能，或当前租户不可见");
        }
        Map<String, Object> out = baseRow(eff);
        out.put("source", "builtin");
        out.put("builtinMinRole", builtinMinRoleName(skillId));
        DcsAssistantSkill mine = skillMapper.selectOne(new LambdaQueryWrapper<DcsAssistantSkill>()
            .eq(DcsAssistantSkill::getTenantId, tenantId).eq(DcsAssistantSkill::getSkillId, skillId));
        if (mine != null) {
            out.put("online", mine);
            boolean hasBuiltin = !builtinMinRoleName(skillId).isEmpty();
            if (mine.getDisabled() != null && mine.getDisabled() == 1) {
                out.put("source", hasBuiltin ? "builtin-disabled" : "online-disabled");
            } else {
                out.put("source", hasBuiltin ? "online-overwrite" : "online-new");
            }
        }
        return R.ok(out);
    }

    /**
     * 保存（新增/覆写/停用一体）：写本租户行 → 即时失效＋广播。
     * 只升不降对内置校验；名称唯一（同租户）校验；长度上限校验。
     */
    @Log(title = "助手技能", businessType = BusinessType.UPDATE)
    @PostMapping("/save")
    @SaCheckPermission("dcs:assistant:skill:edit")
    public R<Void> save(@RequestBody DcsAssistantSkill body) {
        String tenantId = LoginHelper.getTenantId();
        Long userId = LoginHelper.getUserId();
        String skillId = body.getSkillId() == null ? null : body.getSkillId().trim();
        if (skillId == null || skillId.isBlank() || !skillId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            return R.fail("技能标识只能是英文小写/数字/中划线（≤64 位），用于目录与工具调用");
        }
        if (body.getDescription() == null || body.getDescription().isBlank()) {
            return R.fail("一句话说明不能为空——它是助手判断什么时候用这个技能的唯一依据");
        }
        if (body.getDescription().length() > DESC_MAX) {
            return R.fail("一句话说明超长（" + body.getDescription().length() + " > " + DESC_MAX
                + "）——它是每轮都进目录的文本，写太长会吃掉提示词预算");
        }
        if (body.getBody() == null || body.getBody().isBlank()) {
            return R.fail("正文不能为空");
        }
        if (body.getBody().length() > BODY_MAX) {
            return R.fail("正文超长（" + body.getBody().length() + " > " + BODY_MAX + " 字上限）");
        }
        int minRole = DatabaseSkillRepository.minRoleOf(body.getMinRole());
        // 题5 防呆①：对内置技能覆写只升不降（SkillAssets.SKILLS=内置零漂移对照源）
        for (SkillAssets.Skill builtin : SkillAssets.SKILLS) {
            if (builtin.id().equals(skillId) && minRole < builtin.minRole()) {
                return R.fail("内置技能的最低角色只能升不能降（内置档 "
                    + DatabaseSkillRepository.minRoleName(builtin.minRole()) + " 起）");
            }
        }
        // 同租户名称唯一（覆写同名内置=允许；新建撞别的在线技能=拒绝）
        DcsAssistantSkill exists = skillMapper.selectOne(new LambdaQueryWrapper<DcsAssistantSkill>()
            .eq(DcsAssistantSkill::getTenantId, tenantId).eq(DcsAssistantSkill::getSkillId, skillId));
        boolean isNew = exists == null;
        if (isNew) {
            boolean onlineTaken = SkillAssets.skills(tenantId).stream()
                .anyMatch(s -> s.id().equals(skillId));
            if (onlineTaken) {
                return R.fail("技能标识已存在（同名覆写走编辑，改名请新建别的标识）");
            }
        }
        DcsAssistantSkill row = isNew ? new DcsAssistantSkill() : exists;
        row.setTenantId(tenantId);
        row.setSkillId(skillId);
        row.setDisplayName(body.getDisplayName());
        row.setDescription(body.getDescription());
        row.setMinRole(DatabaseSkillRepository.minRoleName(minRole));
        row.setBody(body.getBody());
        row.setDisabled(body.getDisabled() != null && body.getDisabled() == 1 ? 1 : 0);
        row.setUpdateBy(userId);
        if (isNew) {
            row.setCreateBy(userId);
            row.setCreateTime(new java.util.Date());
            skillMapper.insert(row);
        } else {
            row.setUpdateTime(new java.util.Date());
            skillMapper.updateById(row);
        }
        // 题3=乙：本机即时失效＋全机广播（广播丢失 30 秒兜底）
        SkillCacheBroadcaster.publishReload(tenantId);
        return R.ok(isNew ? "已新增并生效（下一轮新会话起）" : "已保存并生效（下一轮新会话起）");
    }

    /** 删除在线行：内置技能=恢复出厂原文；在线新增技能=彻底删除 */
    @Log(title = "助手技能", businessType = BusinessType.DELETE)
    @DeleteMapping("/{skillId}")
    @SaCheckPermission("dcs:assistant:skill:edit")
    public R<Void> delete(@PathVariable String skillId) {
        String tenantId = LoginHelper.getTenantId();
        int deleted = skillMapper.delete(new LambdaQueryWrapper<DcsAssistantSkill>()
            .eq(DcsAssistantSkill::getTenantId, tenantId).eq(DcsAssistantSkill::getSkillId, skillId));
        if (deleted > 0) {
            SkillCacheBroadcaster.publishReload(tenantId);
        }
        if (deleted > 0) {
            return R.ok("已删除在线版本（内置原文自动恢复）");
        }
        return R.fail("没有本租户的在线版本可删");
    }

    private SkillAssets.LoadedSkill findEffective(String skillId, String tenantId) {
        for (SkillAssets.LoadedSkill s : SkillAssets.skills(tenantId)) {
            if (s.id().equals(skillId)) {
                return s;
            }
        }
        return null;
    }

    private Map<String, Object> baseRow(SkillAssets.LoadedSkill s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("skillId", s.id());
        row.put("displayName", s.name());
        row.put("description", s.description());
        row.put("minRole", DatabaseSkillRepository.minRoleName(s.minRole()));
        row.put("body", s.body());
        return row;
    }

    /** 内置档名（无内置=空串，纯在线技能） */
    private String builtinMinRoleName(String skillId) {
        for (SkillAssets.Skill builtin : SkillAssets.SKILLS) {
            if (builtin.id().equals(skillId)) {
                return DatabaseSkillRepository.minRoleName(builtin.minRole());
            }
        }
        return "";
    }

    private Map<String, DcsAssistantSkill> indexBySkillId(List<DcsAssistantSkill> rows) {
        Map<String, DcsAssistantSkill> out = new LinkedHashMap<>();
        for (DcsAssistantSkill r : rows) {
            out.put(r.getTenantId() + ":" + r.getSkillId(), r);
        }
        return out;
    }

    private DcsAssistantSkill rowFor(Map<String, DcsAssistantSkill> idx, String tenantId, String skillId) {
        return idx.get(tenantId + ":" + skillId);
    }
}
