package org.dromara.test.arch;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包名迁移守卫（批6，2026-10-18）：业务代码一律 com.smartdock.*，
 * org.dromara.* 只保留框架公共件（common/system/...）。
 * 若有人在 org.dromara.dcs 下新增类，本测试在构建期即红。
 *
 * @author smartdock
 */
@Tag("dev")
class PackageMigrationGuardTest {

    @Test
    void legacyDcsPackageMustBeAbsentFromClasspath() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> roots = cl.getResources("org/dromara/dcs");
        List<URL> found = Collections.list(roots);
        assertTrue(found.isEmpty(),
            () -> "org.dromara.dcs 残留在 classpath: " + found + " —— 业务代码必须放 com.smartdock.*");
    }

    /** 批 2（2026-11-19）：旧助手根整棵改名到 com.smartdock.assistant.legacy 后，
     *  非 clean 构建会让新旧两套 class 并存（组件扫描双注册同名 bean，启动期 ConflictingBeanDefinitionException），
     *  本断言在构建期即红并直接给出修法。 */
    @Test
    void legacyAssistantPackageMustBeAbsentFromClasspath() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> roots = cl.getResources("org/dromara/assistant");
        List<URL> found = Collections.list(roots);
        assertTrue(found.isEmpty(),
            () -> "org.dromara.assistant 残留在 classpath: " + found
                + " —— 批 2 已整棵改名到 com.smartdock.assistant.legacy，请 mvn clean 后重建");
    }

    /** 批 4（2026-11-19 题 4 案 1）：技能线 8 件搬进新四层后，旧全名在整个 classpath 上必须一个不剩。
     *
     *  <p>与批 3 的消包守卫同动机、不同形态：这次四个包都还在（只是变薄），没法断言「目录不存在」，
     *  只能<b>点名</b>。防的是「没 clean 就编译」——旧产物与新产物同名类并存，组件扫描双注册，
     *  启动期 {@code ConflictingBeanDefinitionException}（批 2 实际发生过，查因成本远高于这 8 行）。</p>
     *
     *  <p><b>不排除 test-classes</b>（2026-11-19 多审契约席 P2-2 回修）：{@code @SpringBootTest} 的上下文
     *  同样按 {@code scanBasePackages=com.smartdock} 扫整个 classpath，测试树根也在其中；同名测试替身或
     *  旧编译产物留在 test-classes 时，主类 + 测试树照样双注册。原写法过滤掉 test-classes 会让守卫在
     *  这种真实故障形态下保持绿色——那是假绿，已删。测试树需要负向夹具时请用非同名类。</p>
     *
     *  <p>清单随搬移同步维护：多一条少一条都会红，这正是目的。</p> */
    @Test
    void batch4MovedSkillClassesMustBeAbsentFromClasspath() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String fqn : BATCH4_MOVED_TO_FOUR_LAYERS) {
            String path = fqn.replace('.', '/') + ".class";
            List<URL> found = Collections.list(cl.getResources(path));
            assertTrue(found.isEmpty(),
                () -> fqn + " 残留在 classpath: " + found
                    + " —— 批 4 已把它搬进新四层，请 mvn clean 后重建");
        }
    }

    /** 批 4 搬走的 8 个类的旧全名（包还在，所以只能点名） */
    private static final List<String> BATCH4_MOVED_TO_FOUR_LAYERS = List.of(
        "com.smartdock.assistant.legacy.controller.AssistantSkillAdminController",
        "com.smartdock.assistant.legacy.controller.AssistantSkillViewerController",
        "com.smartdock.assistant.legacy.controller.AssistantCapabilityPanoramaController",
        "com.smartdock.assistant.legacy.service.AssistantSkillUsageService",
        "com.smartdock.assistant.legacy.mapper.AssistantSkillMapper",
        "com.smartdock.assistant.legacy.mapper.AssistantSkillUsageMapper",
        "com.smartdock.assistant.legacy.domain.DcsAssistantSkill",
        "com.smartdock.assistant.legacy.domain.DcsAssistantSkillUsage");

    /** 批 3（2026-11-19 多审 dspro P2-2）：额度线整线搬移消包后，billing/limiter 两个旧包
     *  在主 classes 上必须一个资源都不剩——非 clean 增量构建会让旧包 class 与新四层同名类并存，
     *  同样触发组件扫描双注册。只盯主 classes（target/classes 与 jar 内）；test-classes 不算——
     *  测试树 3 个 legacy.billing 存量测试是已登记债务（批 9 随包名清理），且不进组件扫描。 */
    @Test
    void batch3DissolvedPackagesMustBeAbsentFromClasspath() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String pkg : new String[] {"com/smartdock/assistant/legacy/limiter",
                                        "com/smartdock/assistant/legacy/billing"}) {
            List<URL> found = Collections.list(cl.getResources(pkg)).stream()
                .filter(u -> !u.toString().contains("/target/test-classes/"))
                .toList();
            assertTrue(found.isEmpty(),
                () -> pkg + " 残留在主 classes: " + found
                    + " —— 批 3 已消包（额度线搬进新四层），请 mvn clean 后重建");
        }
    }
}
