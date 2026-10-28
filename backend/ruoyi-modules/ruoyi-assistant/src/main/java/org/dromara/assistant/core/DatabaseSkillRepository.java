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

    /** 失效代数：invalidateAll() 递增——装载期间的旧快照不得回写覆盖已失效缓存（三审 dspro P1-1）；
     *  包内可见：合并视图 AssistantSkillSource.all 与本层共用同一代数 */
    static final java.util.concurrent.atomic.AtomicLong GEN =
        new java.util.concurrent.atomic.AtomicLong();

    private record Cached(List<AgentSkill> skills, java.util.Set<String> disabled, long at) {
    }

    private final String tenantId;

    public DatabaseSkillRepository(String tenantId) {
        this.tenantId = tenantId == null || tenantId.isBlank() ? GLOBAL_TENANT : tenantId;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        Cached snap = CACHE.get(tenantId);
        if (snap != null && System.currentTimeMillis() - snap.at() < TTL_MS) {
            return snap.skills();
        }
        long gen = GEN.get();
        Load loaded = load();
        // 装载成功且装载期间未发生失效（保存/广播）才落缓存；失败沿用旧快照不入缓存
        if (loaded.ok() && GEN.get() == gen) {
            CACHE.put(tenantId, new Cached(loaded.skills(), loaded.disabled(),
                System.currentTimeMillis()));
        }
        return loaded.skills();
    }

    /**
     * 本租户被停用（disabled=1）的技能标识集——停用是「整技能遮罩」语义：在线层停用行
     * 必须把下层（内置/目录）同名技能一并藏起，而不是被下层顶回。合并视图
     * （AssistantSkillSource.all）与门闸中间件构造路径都读本集；空集=无停用。
     */
    public static java.util.Set<String> disabledIds(String tenantId) {
        String key = tenantId == null || tenantId.isBlank() ? GLOBAL_TENANT : tenantId;
        Cached snap = CACHE.get(key);
        if (snap == null || System.currentTimeMillis() - snap.at() >= TTL_MS) {
            // 触发一次正规装载（装载含禁用行解析），再读缓存
            new DatabaseSkillRepository(key).getAllSkills();
            snap = CACHE.get(key);
        }
        return snap == null ? java.util.Set.of() : snap.disabled();
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

    /** 全部租户缓存清空（保存路径＋Redis 广播收到时）；递增代数使在途装载的旧快照作废 */
    static void invalidateAll() {
        GEN.incrementAndGet();
        CACHE.clear();
    }

    /** 纯函数单测通道：直灌缓存绕开 Spring（无停用行） */
    static void primeForTest(String tenantId, List<AgentSkill> skills) {
        primeForTest(tenantId, skills, java.util.Set.of());
    }

    /** 纯函数单测通道：直灌缓存（生效技能＋停用遮罩集） */
    static void primeForTest(String tenantId, List<AgentSkill> skills, java.util.Set<String> disabled) {
        CACHE.put(tenantId, new Cached(List.copyOf(skills), java.util.Set.copyOf(disabled),
            System.currentTimeMillis()));
    }

    /** 装载结果：生效技能＋停用遮罩集；ok=装载成功（失败沿用旧快照，不落缓存） */
    private record Load(List<AgentSkill> skills, java.util.Set<String> disabled, boolean ok) {
        static Load empty() {
            return new Load(List.of(), java.util.Set.of(), false);
        }
    }

    /** 读表→官方 AgentSkill（同 skill_id 租户行胜全局行；disabled=1 行进遮罩集） */
    private Load load() {
        AssistantSkillMapper mapper;
        try {
            mapper = SpringUtils.getBean(AssistantSkillMapper.class);
        } catch (Exception noSpring) {
            log.debug("[skill-db] Spring 未就绪（纯单测场景），数据库层缺席");
            return Load.empty();
        }
        try {
            // 全量行（含 disabled）在 Java 侧分拣：生效行建技能，停用行进遮罩集
            List<DcsAssistantSkill> rows = mapper.selectAllForTenant(tenantId);
            Map<String, DcsAssistantSkill> byId = new LinkedHashMap<>();
            java.util.Set<String> disabled = new java.util.LinkedHashSet<>();
            for (DcsAssistantSkill row : rows) {
                if (row == null || row.getSkillId() == null) {
                    continue;
                }
                // 排序 tenant_id 在后=租户行，同 skill_id 覆盖 000000 行
                byId.put(row.getSkillId(), row);
            }
            List<AgentSkill> out = new ArrayList<>(byId.size());
            for (DcsAssistantSkill row : byId.values()) {
                if (row.getDisabled() != null && row.getDisabled() == 1) {
                    disabled.add(row.getSkillId());
                    continue;
                }
                if (row.getBody() == null || row.getBody().isBlank()) {
                    continue;
                }
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
            log.debug("[skill-db] 租户 {} 生效 {} 条在线技能行，停用遮罩 {} 条",
                tenantId, out.size(), disabled.size());
            return new Load(List.copyOf(out), java.util.Set.copyOf(disabled), true);
        } catch (Exception e) {
            // DB 故障三审回修（dspro P1-2/dsflash P1-4）：不得把「空技能+空遮罩」当正常结果缓存——
            // 那等于停用 fail-open 30 秒（被藏技能全员复活）。沿用上一次成功快照（含旧遮罩）；
            // 冷缓存无旧值时才本层缺席（内置层照常，遮罩语义退化为无 DB 行=内置默认，可接受）
            log.warn("[skill-db] 租户 {} 在线层读取失败，沿用上次快照（无则本层缺席）: {}",
                tenantId, e.getMessage());
            Cached prev = CACHE.get(tenantId);
            return prev == null ? Load.empty()
                : new Load(prev.skills(), prev.disabled(), false);
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
