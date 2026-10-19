package org.dromara.common.tenant.helper;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.context.model.SaStorage;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.plugins.IgnoreStrategy;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.GlobalConstants;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.core.utils.reflect.ReflectUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;

import java.util.Stack;
import java.util.function.Supplier;

/**
 * 租户助手
 *
 * <p><b>三种租户上下文模式</b>(理解这三个模式是用对本类的关键):</p>
 * <ol>
 *   <li><b>static(静态)</b>: 从 Sa-Token 登录态拿 tenantId(正常 HTTP 请求内的默认模式)</li>
 *   <li><b>dynamic(动态)</b>: {@link #setDynamic} 手动指定, 覆盖 static;
 *       global=true 写 Redis(跨请求/跨线程), global=false 仅当前线程 ThreadLocal</li>
 *   <li><b>ignore(忽略)</b>: {@link #ignore(Supplier)} 临时关闭租户过滤, 查全租户数据;
 *       可重入(Stack 计数), Supplier 结束自动恢复</li>
 * </ol>
 * <p>优先级: dynamic(线程 ThreadLocal) &gt; dynamic(Redis global) &gt; static(登录态)。
 * ignore 模式独立于三者, 直接跳过 MyBatis-Plus 租户拦截器。</p>
 *
 * @author Lion Li
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TenantHelper {
    // ===== DCS 调用指南 =====
    // DCS 引用频次: 40+ 个文件
    // 典型用法:
    //   - 正常请求: getTenantId() 拿当前租户(自动从登录态)
    //   - 跨租户查询: TenantHelper.ignore(() -> mapper.selectAll()) 查全租户
    //   - MQ/定时线程: setDynamic(tenantId, true) 写 Redis 全局租户(线程无登录态)
    // ⚠️ DCS 踩过的坑:
    //   - MQ 消费线程/@Async 线程无 ThreadLocal 登录态, getTenantId() 返回 null
    //     解决: 生产者发消息时带 tenantId, 消费者 setDynamic 恢复(见 DcsStreamMqConsumer)
    //   - ScheduleScanner 扫描全租户: 用 ignore 包裹单租户处理逻辑, 避免租户过滤
    //   - ignore() 是可重入的(Stack), 嵌套调用安全; 但 Supplier 抛异常时 finally 会恢复
    // 安全边界: ignore 模式会查全租户数据, 仅限管理后台/定时任务, 禁止在用户请求路径用
    // ===== DCS 调用指南 结束 =====

    private static final String DYNAMIC_TENANT_KEY = GlobalConstants.GLOBAL_REDIS_KEY + "dynamicTenant";

    private static final ThreadLocal<String> TEMP_DYNAMIC_TENANT = new ThreadLocal<>();

    private static final ThreadLocal<Stack<Integer>> REENTRANT_IGNORE = ThreadLocal.withInitial(Stack::new);

    /**
     * 租户功能是否启用
     */
    public static boolean isEnable() {
        return Convert.toBool(SpringUtils.getProperty("tenant.enable"), false);
    }

    private static IgnoreStrategy getIgnoreStrategy() {
        Object ignoreStrategyLocal = ReflectUtils.getStaticFieldValue(ReflectUtils.getField(InterceptorIgnoreHelper.class, "IGNORE_STRATEGY_LOCAL"));
        if (ignoreStrategyLocal instanceof ThreadLocal<?>) {
            ThreadLocal<?> ignoreStrategyThreadLocal = (ThreadLocal<?>) ignoreStrategyLocal;
            Object ignoreStrategyObject = ignoreStrategyThreadLocal.get();
            if (ignoreStrategyObject instanceof IgnoreStrategy) {
                IgnoreStrategy ignoreStrategy = (IgnoreStrategy) ignoreStrategyObject;
                return ignoreStrategy;
            }
        }
        return null;
    }

    /**
     * 开启忽略租户(开启后需手动调用 {@link #disableIgnore()} 关闭)
     */
    private static void enableIgnore() {
        IgnoreStrategy ignoreStrategy = getIgnoreStrategy();
        if (ObjectUtil.isNull(ignoreStrategy)) {
            InterceptorIgnoreHelper.handle(IgnoreStrategy.builder().tenantLine(true).build());
        } else {
            ignoreStrategy.setTenantLine(true);
        }
        Stack<Integer> reentrantStack = REENTRANT_IGNORE.get();
        reentrantStack.push(reentrantStack.size() + 1);
    }

    /**
     * 关闭忽略租户
     */
    private static void disableIgnore() {
        IgnoreStrategy ignoreStrategy = getIgnoreStrategy();
        if (ObjectUtil.isNotNull(ignoreStrategy)) {
            boolean noOtherIgnoreStrategy = !Boolean.TRUE.equals(ignoreStrategy.getDynamicTableName())
                && !Boolean.TRUE.equals(ignoreStrategy.getBlockAttack())
                && !Boolean.TRUE.equals(ignoreStrategy.getIllegalSql())
                && !Boolean.TRUE.equals(ignoreStrategy.getDataPermission())
                && CollectionUtil.isEmpty(ignoreStrategy.getOthers());
            Stack<Integer> reentrantStack = REENTRANT_IGNORE.get();
            boolean empty = reentrantStack.isEmpty() || reentrantStack.pop() == 1;
            if (noOtherIgnoreStrategy && empty) {
                InterceptorIgnoreHelper.clearIgnoreStrategy();
            } else if (empty) {
                ignoreStrategy.setTenantLine(false);
            }
        }
    }

    /**
     * 在忽略租户中执行
     *
     * @param handle 处理执行方法
     */
    public static void ignore(Runnable handle) {
        enableIgnore();
        try {
            handle.run();
        } finally {
            disableIgnore();
        }
    }

    /**
     * 在忽略租户中执行
     *
     * @param handle 处理执行方法
     */
    public static <T> T ignore(Supplier<T> handle) {
        enableIgnore();
        try {
            return handle.get();
        } finally {
            disableIgnore();
        }
    }

    /**
     * 设置动态租户(仅当前线程, 不写 Redis)。
     *
     * <p>等价于 {@link #setDynamic(String, boolean) setDynamic(tenantId, false)}。
     * 适用于: 在当前线程内临时切换租户(如 MQ 消费线程), 请求结束 ThreadLocal 自动清理。</p>
     *
     * @param tenantId 租户id
     */
    public static void setDynamic(String tenantId) {
        setDynamic(tenantId, false);
    }

    /**
     * 设置动态租户(一直有效 需要手动清理)
     * <p>
     * 如果为未登录状态下 那么只在当前线程内生效
     *
     * @param tenantId 租户id
     * @param global   是否全局生效
     */
    public static void setDynamic(String tenantId, boolean global) {
        if (!isEnable()) {
            return;
        }
        if (!LoginHelper.isLogin() || !global) {
            TEMP_DYNAMIC_TENANT.set(tenantId);
            return;
        }
        String cacheKey = DYNAMIC_TENANT_KEY + ":" + LoginHelper.getUserId();
        RedisUtils.setCacheObject(cacheKey, tenantId);
        SaHolder.getStorage().set(cacheKey, tenantId);
    }

    /**
     * 获取动态租户(一直有效 需要手动清理)
     * <p>
     * 如果为未登录状态下 那么只在当前线程内生效
     */
    public static String getDynamic() {
        if (!isEnable()) {
            return null;
        }
        if (!LoginHelper.isLogin()) {
            return TEMP_DYNAMIC_TENANT.get();
        }
        // 如果线程内有值 优先返回
        String tenantId = TEMP_DYNAMIC_TENANT.get();
        if (StringUtils.isNotBlank(tenantId)) {
            return tenantId;
        }
        SaStorage storage = SaHolder.getStorage();
        String cacheKey = DYNAMIC_TENANT_KEY + ":" + LoginHelper.getUserId();
        tenantId = storage.getString(cacheKey);
        // 如果为 -1 说明已经查过redis并且不存在值 则直接返回null
        if (StringUtils.isNotBlank(tenantId)) {
            return tenantId.equals("-1") ? null : tenantId;
        }
        tenantId = RedisUtils.getCacheObject(cacheKey);
        storage.set(cacheKey, StringUtils.isBlank(tenantId) ? "-1" : tenantId);
        return tenantId;
    }

    /**
     * 清除动态租户
     */
    public static void clearDynamic() {
        if (!isEnable()) {
            return;
        }
        if (!LoginHelper.isLogin()) {
            TEMP_DYNAMIC_TENANT.remove();
            return;
        }
        TEMP_DYNAMIC_TENANT.remove();
        String cacheKey = DYNAMIC_TENANT_KEY + ":" + LoginHelper.getUserId();
        RedisUtils.deleteObject(cacheKey);
        SaHolder.getStorage().delete(cacheKey);
    }

    /**
     * 在动态租户中执行
     *
     * @param handle 处理执行方法
     */
    public static void dynamic(String tenantId, Runnable handle) {
        setDynamic(tenantId);
        try {
            handle.run();
        } finally {
            clearDynamic();
        }
    }

    /**
     * 在动态租户中执行
     *
     * @param handle 处理执行方法
     */
    public static <T> T dynamic(String tenantId, Supplier<T> handle) {
        setDynamic(tenantId);
        try {
            return handle.get();
        } finally {
            clearDynamic();
        }
    }

    /**
     * 获取当前租户id(动态租户优先)
     */
    public static String getTenantId() {
        if (!isEnable()) {
            return null;
        }
        String tenantId = TenantHelper.getDynamic();
        if (StringUtils.isBlank(tenantId)) {
            tenantId = LoginHelper.getTenantId();
        }
        return tenantId;
    }

}
