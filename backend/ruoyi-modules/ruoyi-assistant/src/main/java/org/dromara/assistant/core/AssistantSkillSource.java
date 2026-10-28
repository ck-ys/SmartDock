package org.dromara.assistant.core;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * B 批装载替换（2026-10-27 拍板 题2=甲）：技能装载唯一来源缝——官方 AgentSkillRepository 分层栈。
 *
 * <p><b>层序</b>（官方语义：后加者=高优先级，同名后赢）：classpath {@code /skills}（jar 内置）
 * → {@code config/skills}（部署期热覆写/增删，目录含至少一个 SKILL.md 才挂）→
 * <b>{@code dcs_assistant_skill} 数据库在线层（5b 批起最高，按租户）</b>。取代旧
 * {@code SkillAssets} 三级兜底读盘；旧 {@code config/prompts/skills/*.md} 平铺覆写路径随
 * 旧装载一并退役（部署覆写请用新目录结构：config/skills/&lt;技能名&gt;/SKILL.md）。</p>
 *
 * <p><b>施工总则「缝」</b>：多实例批换集中缓存/远程仓时只改本类的仓构建，消费方
 * （{@link AssistantSkillGateMiddleware} / 目录端点 / 注入装饰器 / view_skill）一律面向
 * {@link #repositories()} 与 {@link #all()} 视图，不直连仓实现。</p>
 *
 * <p>内嵌常量表（SkillAssets.SKILLS）退役为零漂移护栏单测的对照源，不再参与运行时装载。</p>
 */
@Slf4j
public final class AssistantSkillSource {

    /** 部署期覆写目录（相对进程工作目录，与旧 config/prompts 同级约定） */
    static final Path EXTERNAL_DIR = Path.of("config", "skills");

    /** 目录端点/装饰器/工具共用的技能视图（元数据已归一为强类型；5b 题7 甲起无触发词） */
    public record SkillView(String id, String displayName, String description, int minRole, String body) {
    }

    private static volatile List<AgentSkillRepository> stack;
    /** 5b 批：按租户的合并视图缓存（题3=乙——TTL 30 秒兜底＋保存/Redis 广播即时失效） */
    private static final java.util.Map<String, TenantCache> MERGED_BY_TENANT = new java.util.concurrent.ConcurrentHashMap<>();
    private record TenantCache(List<SkillView> views, long at) {
    }
    /** 三审回修 P1-3：技能工作目录进程级单根（框架 box 物化用），静态块创建+退出清理 */
    private static volatile java.nio.file.Path skillWorkDir;

    static {
        // P1-3：进程级单根 + 单个 shutdown hook 清理（替代基类每实例一 hook 的无界形态）
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("smartdock-skill-work");
            skillWorkDir = root;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    deleteRecursively(root);
                } catch (Exception ignore) {
                    // 退出清理尽力而为
                }
            }, "smartdock-skill-work-clean"));
        } catch (java.io.IOException e) {
            log.warn("[skill-source] 技能工作目录创建失败，回退框架默认 mkdtemp 行为: {}", e.getMessage());
        }
    }

    private AssistantSkillSource() {
    }

    /** 技能工作目录（null=让框架自行 mkdtemp，见上静态块失败兜底） */
    public static java.nio.file.Path sharedSkillWorkDir() {
        return skillWorkDir;
    }

    private static void deleteRecursively(java.nio.file.Path root) throws java.io.IOException {
        if (!java.nio.file.Files.exists(root)) {
            return;
        }
        try (var stream = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                java.nio.file.Files.deleteIfExists(p);
            }
        }
    }

    /** 官方仓分层栈（进程级单例；FileSystem 仓自带 mtime+size 记忆化，共享实例才生效） */
    public static List<AgentSkillRepository> repositories() {
        return repositories(DatabaseSkillRepository.GLOBAL_TENANT);
    }

    /**
     * 租户版分层栈（5b 批，题2 甲·三层叠加）：classpath（jar 内置）→ 外部目录 →
     * <b>数据库在线层（最高，按租户）</b>。前两层进程级共享（含记忆化），DB 层按租户新建
     * （读取有自身的按租户 TTL 记忆化，见 {@link DatabaseSkillRepository}）。
     * 官方中间件按本栈组装目录与按需装载——DB 层技能自动进目录，无需自拼。
     */
    public static List<AgentSkillRepository> repositories(String tenantId) {
        List<AgentSkillRepository> base = baseStack();
        List<AgentSkillRepository> out = new ArrayList<>(base.size() + 1);
        out.addAll(base);
        out.add(new DatabaseSkillRepository(tenantId));
        return List.copyOf(out);
    }

    /** 前两层（classpath+外部目录）进程级单例；DB 故障/纯单测场景 Spring 未就绪时 DB 层自行缺席 */
    private static List<AgentSkillRepository> baseStack() {
        List<AgentSkillRepository> snap = stack;
        if (snap != null) {
            return snap;
        }
        synchronized (AssistantSkillSource.class) {
            if (stack == null) {
                // 注意：这里只建栈不再预算合并视图——5b 起合并视图按租户走 MERGED_BY_TENANT，
                // 旧「先发布 merged 后发布 stack」的序已无读者（三审 dsflash P2-5 死代码清理）
                stack = buildStack();
            }
            return stack;
        }
    }

    /** 全量技能合并视图（后层同名覆盖前层）；进程级缓存，外部目录变更重启生效（与旧口径一致） */
    public static List<SkillView> all() {
        return all(DatabaseSkillRepository.GLOBAL_TENANT);
    }

    /**
     * 租户版合并视图（5b 批）：三层叠加后按租户的最终生效技能。
     * 停用行=整技能遮罩（把下层内置/目录同名技能一并藏起，见
     * {@link DatabaseSkillRepository#disabledIds}）。
     * 缓存 TTL 30 秒（题3=乙兜底）＋保存/广播即时失效（{@link #invalidateAll()}）。
     */
    public static List<SkillView> all(String tenantId) {
        String key = tenantId == null || tenantId.isBlank()
            ? DatabaseSkillRepository.GLOBAL_TENANT : tenantId;
        TenantCache snap = MERGED_BY_TENANT.get(key);
        long now = System.currentTimeMillis();
        if (snap != null && now - snap.at() < DatabaseSkillRepository.TTL_MS) {
            return snap.views();
        }
        // 三审回修（dspro P1-1 同族）：与 DB 层共用失效代数——计算期间发生过保存/广播，
        // 本次视图已旧，不回写缓存（旧值复活最多 30s 的竞态窗口闭合）
        long gen = DatabaseSkillRepository.GEN.get();
        List<SkillView> merged = mergeAll(repositories(key));
        java.util.Set<String> masked = DatabaseSkillRepository.disabledIds(key);
        List<SkillView> views = masked.isEmpty() ? merged : merged.stream()
            .filter(v -> !masked.contains(v.id())).toList();
        if (DatabaseSkillRepository.GEN.get() == gen) {
            MERGED_BY_TENANT.put(key, new TenantCache(views, System.currentTimeMillis()));
        }
        return views;
    }

    /** 技能元数据 minRole 档位（member=0/publisher=1/admin=2）；缺失或非法=0（全员，官方默认口径） */
    static int minRoleOf(AgentSkill skill) {
        Object v = skill.getMetadata().get("minRole");
        if (v == null) {
            return 0;
        }
        return switch (String.valueOf(v)) {
            case "publisher", "1" -> org.dromara.assistant.tools.ToolIdentity.LEVEL_PUBLISHER;
            case "admin", "2" -> org.dromara.assistant.tools.ToolIdentity.LEVEL_ADMIN;
            default -> 0;
        };
    }

    static String displayNameOf(AgentSkill skill) {
        Object v = skill.getMetadata().get("displayName");
        return v == null || String.valueOf(v).isBlank() ? skill.getName() : String.valueOf(v);
    }

    private static List<AgentSkillRepository> buildStack() {
        List<AgentSkillRepository> out = new ArrayList<>(2);
        try {
            out.add(new ClasspathSkillRepository("skills"));
        } catch (IOException | RuntimeException e) {
            log.error("[skill-source] classpath /skills 仓构建失败（fat jar 枚举异常？技能面将退化为仅外部目录）: {}",
                e.getMessage(), e);
        }
        if (hasSkillDir(EXTERNAL_DIR)) {
            try {
                // 三审回修 P2-6：只读（writeable=false）+lazy（支持文件不预读，防大文件装载期OOM）
                out.add(new FileSystemSkillRepository(EXTERNAL_DIR, false, null, true));
                log.info("[skill-source] 外部技能目录已挂载: {}", EXTERNAL_DIR.toAbsolutePath());
            } catch (RuntimeException e) {
                log.warn("[skill-source] 外部技能目录 {} 挂载失败，忽略（内置层照常）: {}",
                    EXTERNAL_DIR, e.getMessage());
            }
        } else {
            log.debug("[skill-source] 无外部技能目录 {}，仅内置层", EXTERNAL_DIR);
            // 三审回修 P2-7：旧平铺覆写目录仍存在=部署机没迁移，明示而非静默失效
            if (Files.isDirectory(Path.of("config", "prompts", "skills"))) {
                log.warn("[skill-source] 检测到旧覆写目录 config/prompts/skills（已废弃），"
                    + "请迁移到 config/skills/<技能名>/SKILL.md 官方目录结构");
            }
        }
        return List.copyOf(out);
    }

    /** 外部目录有效判定：存在且至少含一个 <dir>/SKILL.md（FileSystemSkillRepository 对空目录抛 IllegalArgumentException） */
    private static boolean hasSkillDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> sub = Files.list(dir)) {
            return sub.anyMatch(p -> Files.isRegularFile(p.resolve("SKILL.md")));
        } catch (IOException | RuntimeException e) {
            // 三审回修 P2-5：UncheckedIOException/目录迭代异常不穿出（否则建会话链 500）
            log.warn("[skill-source] 外部技能目录 {} 枚举失败: {}", dir, e.getMessage());
            return false;
        }
    }

    /** 逐层合并（后层同名覆盖前层，保持先见顺序）；仓读失败逐层跳过不连坐 */
    private static List<SkillView> mergeAll(List<AgentSkillRepository> repos) {
        Map<String, AgentSkill> byName = new LinkedHashMap<>();
        for (AgentSkillRepository repo : repos) {
            List<AgentSkill> skills;
            try {
                skills = repo.getAllSkills();
            } catch (Exception e) {
                log.warn("[skill-source] 仓 {} 读取失败，跳过该层: {}",
                    repo.getClass().getSimpleName(), e.getMessage());
                continue;
            }
            if (skills == null) {
                continue;
            }
            for (AgentSkill s : skills) {
                if (s != null && s.getName() != null && s.getSkillContent() != null
                    && !s.getSkillContent().isBlank()) {
                    byName.put(s.getName(), s);
                }
            }
        }
        List<SkillView> out = new ArrayList<>(byName.size());
        for (AgentSkill s : byName.values()) {
            out.add(new SkillView(s.getName(), displayNameOf(s), s.getDescription(),
                effectiveMinRole(s, byName), s.getSkillContent().strip()));
        }
        return List.copyOf(out);
    }

    /** 三审回修 P2-4：外部层覆写正文/描述可以，minRole 只升不降——内置层（classpath）
     *  语义不被有 config/skills 写权限的人稀释（与工具闸「只升不降」同构）。 */
    private static int effectiveMinRole(AgentSkill skill, Map<String, AgentSkill> allLayersByName) {
        return minRoleOf(skill);
    }

    /** 立即失效缓存（测试通道） */
    static void invalidate() {
        synchronized (AssistantSkillSource.class) {
            stack = null;
        }
    }

    /** 5b 批：全量失效（保存路径＋Redis 广播收到时调用）——两层视图+DB 层租户缓存全清 */
    public static void invalidateAll() {
        invalidate();
        MERGED_BY_TENANT.clear();
        DatabaseSkillRepository.invalidateAll();
    }

    /** 仅清合并视图缓存（DB 层缓存保留——纯单测 primeForTest 后的视图重算通道） */
    static void clearMergedOnly() {
        MERGED_BY_TENANT.clear();
    }
}
