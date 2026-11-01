package org.dromara.assistant.core;

import org.dromara.common.core.service.ConfigService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;

/**
 * 超大工具结果驱逐阈值读取（题①，2026-10-31 拍板 B：默认租户设一次全租户继承，租户可覆盖）。
 *
 * <p>阈值走系统参数 {@code dcs.assistant.tool_evict.threshold}（键风格对齐
 * {@code dcs.assistant.websearch.key}，管理员在「参数设置」页按租户新增）：
 * 先读本租户行，非法再读默认租户（000000）行，仍非法回 {@link #DEFAULT}。
 * 判据=可转 int 且 {@code 1000 ≤ v ≤ 1000000}——空/非数字/越界一律视为未配置，
 * 不做钳制（配 100 期望生效 1000 属于误配，静默改值会让管理员以为改了）。</p>
 *
 * <p><b>任意线程可安全调用</b>：读取点只在建 agent 路径（{@code AssistantAgentFactory.create}），
 * 工具执行线程零新增读取。两段读取（本租户+默认租户）都按「先存后还」包
 * {@link TenantHelper#dynamic}（照 {@code AssistantStatusController.callInTenant} 口径）：
 * dynamic 的 finally clearDynamic 在登录线程上会连超管「切换单位」的持久态一起删，
 * 先 getDynamic 存底、finally setDynamic(prev, true) 还原——建 agent 的 supplier 线程
 * 无 Sa-Token 上下文，dynamic 只走 ThreadLocal，同样安全；未来若登录线程复用本读取
 * （如管理端预览阈值）也不会吞切换态。</p>
 *
 * <p>读不到/读挂了一律回 {@link #DEFAULT}，绝不炸建会话（驱逐是优化不是命脉）。
 * 阈值刻意不进装配指纹（assemblyFingerprint）：缓存 agent 不重建、只影响新建会话
 * ——拍板口径，非缺陷。</p>
 */
public final class AssistantEvictionThreshold {

    /** 系统参数 key：驱逐阈值（字符数）；管理员在参数设置页按当前租户新增 */
    public static final String CONFIG_KEY = "dcs.assistant.tool_evict.threshold";

    /** 默认阈值：与框架 ToolResultEvictionConfig.DEFAULT_MAX_RESULT_CHARS 同值（~20K tokens） */
    public static final int DEFAULT = 80_000;

    /** 合法下界：驱逐预览头尾各 2000 字，阈值比预览还小=每条都驱逐，视为误配 */
    static final int LOWER = 1_000;

    /** 合法上界：驱逐本意是卸载超大结果而非关闸，超过 100 万视为误配 */
    static final int UPPER = 1_000_000;

    /** 默认租户行：000000 设一次，未配/配错的租户继承它 */
    private static final String DEFAULT_TENANT = "000000";

    private AssistantEvictionThreshold() {
    }

    /**
     * 两级解析（纯函数）：先本租户原始值，非法再默认租户原始值，仍非法回 80000。
     */
    public static int resolve(String tenantRaw, String defaultRaw) {
        Integer own = parse(tenantRaw);
        if (own != null) {
            return own;
        }
        Integer fallback = parse(defaultRaw);
        return fallback != null ? fallback : DEFAULT;
    }

    /** 单值判据：可转 int 且 1000 ≤ v ≤ 1000000；null/空白/非数字/越界返回 null。 */
    private static Integer parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            int v = Integer.parseInt(s);
            return (v >= LOWER && v <= UPPER) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 建会话路径唯一读取口：本租户 → 默认租户 000000 → 80000；任一异常回默认不外抛。
     */
    public static int read(ConfigService cfg, String tenantId) {
        try {
            return resolve(readInTenant(cfg, tenantId), readInTenant(cfg, DEFAULT_TENANT));
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    /**
     * 指定租户内读参数（null 归一为空串=未配置）。不能裸套 {@link TenantHelper#dynamic}：
     * 其 finally 的 clearDynamic 在登录线程上会删超管「切换单位」持久态（Redis+SaStorage），
     * 故照 callInTenant 先存后还。
     */
    private static String readInTenant(ConfigService cfg, String tenantId) {
        if (cfg == null || StringUtils.isBlank(tenantId)) {
            return "";
        }
        // 租户开关判定单独 try：纯单测无 Spring 上下文时 isEnable 会抛，按「未启用」直读
        // （生产 tenant.enable=true 恒走下方 dynamic 分支，与 WebSearchTools.readTenantKey 同款语义）
        boolean tenantEnabled = false;
        try {
            tenantEnabled = TenantHelper.isEnable();
        } catch (Exception ignored) {
            // 无 Spring 上下文
        }
        if (!tenantEnabled) {
            // 【刻意保留，非缺陷】该直读分支（无租户上下文读 sys_config）仅纯单测可触发——
            // 生产环境租户功能恒启用，恒走下方先存后还分支
            return normalize(cfg.getConfigValue(CONFIG_KEY));
        }
        String prevDynamic = TenantHelper.getDynamic();
        try {
            return normalize(TenantHelper.dynamic(tenantId, () -> cfg.getConfigValue(CONFIG_KEY)));
        } finally {
            if (StringUtils.isNotBlank(prevDynamic)) {
                TenantHelper.setDynamic(prevDynamic, true);
            }
        }
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw;
    }
}
