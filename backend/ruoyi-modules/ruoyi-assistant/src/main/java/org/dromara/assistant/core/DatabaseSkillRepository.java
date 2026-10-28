package org.dromara.assistant.core;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantSkill;
import org.dromara.assistant.mapper.AssistantSkillMapper;
import org.dromara.assistant.tools.ToolIdentity;
import org.dromara.common.core.utils.SpringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库技能仓（5b 批，题2 甲·三层叠加的最高层）：官方 {@link AgentSkillRepository} 的
 * 只读实现，按租户读 {@code dcs_assistant_skill} 生效行（000000 全局基线＋本租户覆盖）。
 *
 * <p><b>租户绑定</b>：实例按 tenantId 构造（建会话路径在请求线程完成）；进程级按租户
 * 记忆化读取结果，TTL 30 秒（题3=乙的兜底重读）＋保存/Redis 广播即时失效
 * （{@link #invalidateAll()}）。Spring 未就绪（纯单测）时本层静默缺席，内置层照常。</p>
 *
 * <p>写方法（save/delete）恒 false：管理台写入走管理端点直写表＋失效广播，
 * 不经官方仓写面——官方中间件对只读仓不会发起写调用。</p>
 */
@Slf4j
public final class DatabaseSkillRepository implements AgentSkillRepository {

    /** 全局基线租户（RuoYi 默认租户，与模型配置全局行同口径） */
    public static final String GLOBAL_TENANT = "000000";

    /** 读取 TTL（题3=乙：30 秒兜底重读，广播丢失自愈） */
    public static final long TTL_MS = 30_000L;

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(List<AgentSkill> skills, long at) {
    }

    private final String tenantId;

    public DatabaseSkillRepository(String tenantId) {
        this.tenantId = tenantId == null || tenantId.isBlank() ? GLOBAL_TENANT : tenantId;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        Cached snap = CACHE.get(tenantId);
        long now = System.currentTimeMillis();
        if (snap != null && now - snap.at() < TTL_MS) {
            return snap.skills();
        }
        List<AgentSkill> loaded = load();
        CACHE.put(tenantId, new Cached(loaded, now));
        return loaded;
    }

    @Override
    public AgentSkill getSkill(String name) {
        for (AgentSkill s : getAllSkills()) {
            if (s.getName().equals(name)) {
                return s;
            }
        }
        return null;
    }

    @Override
    public List<String> getAllSkillNames() {
        List<String> out = new ArrayList<>();
        for (AgentSkill s : getAllSkills()) {
            out.add(s.getName());
        }
        return out;
    }

    @Override
    public boolean skillExists(String name) {
        return getSkill(name) != null;
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        log.warn("[skill-db] 官方仓写面不支持（管理台写入走管理端点直写表），save 被忽略");
        return false;
    }

    @Override
    public boolean delete(String name) {
        log.warn("[skill-db] 官方仓写面不支持（管理台删除走管理端点直写表），delete 被忽略");
        return false;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("database", "dcs_assistant_skill[" + tenantId + "]", false);
    }

    @Override
    public String getSource() {
        return "database";
    }

    @Override
    public void setWriteable(boolean writeable) {
        // 只读仓：恒 false
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    @Override
    public void close() {
        // 无资源持有
    }

    /** 全部租户缓存清空（保存路径＋Redis 广播收到时） */
    static void invalidateAll() {
        CACHE.clear();
    }

    /** 纯函数单测通道：直灌缓存绕开 Spring */
    static void primeForTest(String tenantId, List<AgentSkill> skills) {
        CACHE.put(tenantId, new Cached(List.copyOf(skills), System.currentTimeMillis()));
    }

    /** 读表→官方 AgentSkill（同 skill_id 租户行胜全局行；minRole 只升不降对内置由合并层兜底） */
    private List<AgentSkill> load() {
        AssistantSkillMapper mapper;
        try {
            mapper = SpringUtils.getBean(AssistantSkillMapper.class);
        } catch (Exception noSpring) {
            log.debug("[skill-db] Spring 未就绪（纯单测场景），数据库层缺席");
            return List.of();
        }
        try {
            List<DcsAssistantSkill> rows = mapper.selectEffective(tenantId);
            Map<String, DcsAssistantSkill> byId = new LinkedHashMap<>();
            for (DcsAssistantSkill row : rows) {
                if (row == null || row.getSkillId() == null || row.getBody() == null
                    || row.getBody().isBlank()) {
                    continue;
                }
                // 排序 tenant_id 在后=租户行，同 skill_id 覆盖 000000 行
                byId.put(row.getSkillId(), row);
            }
            List<AgentSkill> out = new ArrayList<>(byId.size());
            for (DcsAssistantSkill row : byId.values()) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("displayName",
                    row.getDisplayName() == null || row.getDisplayName().isBlank()
                        ? row.getSkillId() : row.getDisplayName());
                meta.put("minRole", String.valueOf(minRoleOf(row.getMinRole())));
                meta.put("origin", "database");
                out.add(AgentSkill.builder()
                    .name(row.getSkillId())
                    .description(row.getDescription() == null ? "" : row.getDescription())
                    .skillContent(row.getBody())
                    // 注意：不能 .metadata(map) 整体替换——会把 name/description 从 metadata 里抹掉
                    //（官方 Builder 校验读 metadata，IllegalArgument: must have name/description）
                    .putMetadata("displayName", meta.get("displayName"))
                    .putMetadata("minRole", meta.get("minRole"))
                    .putMetadata("origin", "database")
                    .source("database")
                    .build());
            }
            log.debug("[skill-db] 租户 {} 生效 {} 条在线技能行", tenantId, out.size());
            return List.copyOf(out);
        } catch (Exception e) {
            // DB 故障=本层缺席（内置/目录层照常），不连坐建会话链
            log.warn("[skill-db] 租户 {} 在线层读取失败，本层缺席（内置层照常）: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    /** minRole 串→档位（缺失/非法=member 全员，与 frontmatter minRoleOf 同口径） */
    public static int minRoleOf(String minRole) {
        if (minRole == null) {
            return ToolIdentity.LEVEL_MEMBER;
        }
        return switch (minRole) {
            case "publisher", "1" -> ToolIdentity.LEVEL_PUBLISHER;
            case "admin", "2" -> ToolIdentity.LEVEL_ADMIN;
            default -> ToolIdentity.LEVEL_MEMBER;
        };
    }

    /** 档位→minRole 串（写库方向） */
    public static String minRoleName(int level) {
        return switch (level) {
            case ToolIdentity.LEVEL_ADMIN -> "admin";
            case ToolIdentity.LEVEL_PUBLISHER -> "publisher";
            default -> "member";
        };
    }
}
