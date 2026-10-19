package org.dromara.test.assistant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * P0-Task0 依赖可解析探针：验证 AgentScope 七件套依赖真正进入 admin 测试 classpath。
 * <p>设计口径（P0 计划 Task 0 Step 1）：
 * <ul>
 *   <li>一律 @Tag("p0probe")（禁 dev 标签——pom surefire groups=dev 会把探针混入 264 个存量测试默认回归）</li>
 *   <li>运行期 Class.forName 探测（非编译期 import）：类名不对=运行 FAIL 而非编译失败，天然 TDD 红</li>
 *   <li>类名以 AgentScope 2.0.3 实际为准调准；PASS/FAIL 都是证据（FAIL 全文留 document/evidence/assistant-p0/）</li>
 * </ul>
 * 跑法：.codex/run-dcs-test.ps1 -TestClass P0DependencyProbeTest -Groups p0probe
 */
@Tag("p0probe")
class P0DependencyProbeTest {

    /** agentscope-core 核心类可达性（真实主类=ReActAgent，jar 实证 2026-10-03：io/agentscope/core/ 下顶层仅 ReActAgent+Version） */
    @Test
    void agentScopeCoreReachable() throws Exception {
        Class<?> c = Class.forName("io.agentscope.core.ReActAgent");
        assertNotNull(c);
    }

    /** extensions-jdbc 核心类可达性 */
    @Test
    void jdbcExtensionReachable() throws Exception {
        Class.forName("io.agentscope.extensions.jdbc.JdbcDistributedStore");
    }

    /** agui-starter 自动配置类可达性（jar 实证 2026-10-03：真实类=io.agentscope.spring.boot.agui.mvc.AgentscopeAguiMvcAutoConfiguration，
     *  同包还有 AguiMvcController/AguiRestController/DefaultAgentResolver/ThreadSessionManager——T3/T6 Resolver 一族的真身素材） */
    @Test
    void aguiStarterReachable() throws Exception {
        Class.forName("io.agentscope.spring.boot.agui.mvc.AgentscopeAguiMvcAutoConfiguration");
    }

    /** extensions-redis 与项目 Redisson 3.52.0 的兼容探针：本件传递 redisson 4.2.0 已被 exclusion 排除（顶掉基线会炸 NameMapper），
     *  此处验证其状态存储核心类在 3.52.0 classpath 下仍可加载——真兼容结论留给 T11 记忆并发 CAS 实测 */
    @Test
    void redisExtensionReachable() throws Exception {
        Class.forName("io.agentscope.extensions.redis.state.redisson.RedissonAgentStateStore");
    }
}
