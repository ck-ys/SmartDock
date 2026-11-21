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

    /** 批 5（2026-11-19 题 1/题 4 案 1）：MCP 管理线 6 件搬进新四层后，旧全名在 classpath 上必须一个不剩。
     *  同批 4 动机：四个 legacy 子包都还在（只是变薄），只能点名；防「没 clean 就编译」的双注册。 */
    @Test
    void batch5MovedMcpClassesMustBeAbsentFromClasspath() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String fqn : BATCH5_MOVED_TO_FOUR_LAYERS) {
            String path = fqn.replace('.', '/') + ".class";
            List<URL> found = Collections.list(cl.getResources(path));
            assertTrue(found.isEmpty(),
                () -> fqn + " 残留在 classpath: " + found
                    + " —— 批 5 已把它搬进新四层，请 mvn clean 后重建");
        }
    }

    /** 批 5 搬走的 6 个类的旧全名（包还在，所以只能点名） */
    private static final List<String> BATCH5_MOVED_TO_FOUR_LAYERS = List.of(
        "com.smartdock.assistant.legacy.controller.AssistantMcpController",
        "com.smartdock.assistant.legacy.controller.AssistantToolGateController",
        "com.smartdock.assistant.legacy.domain.DcsMcpServer",
        "com.smartdock.assistant.legacy.domain.DcsMcpCallLog",
        "com.smartdock.assistant.legacy.mapper.DcsMcpServerMapper",
        "com.smartdock.assistant.legacy.mapper.DcsMcpCallLogMapper");

    /** 批 6（2026-11-20 题 1 案 1）：记忆/收藏/反馈线 15 件搬进新四层、旧记忆服务改名 Turn 后，
     *  16 个旧全名在 classpath 上必须一个不剩。同批 4/5 动机：四个 legacy 子包都还在（只是变薄），
     *  只能点名；防「没 clean 就编译」的双注册（含旧 AssistantMemoryService 与新的
     *  AssistantMemoryTurnService 并存时会话装配拿错 bean 的风险）。 */
    @Test
    void batch6MovedMemoryClassesMustBeAbsentFromClasspath() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String fqn : BATCH6_MOVED_TO_FOUR_LAYERS) {
            String path = fqn.replace('.', '/') + ".class";
            List<URL> found = Collections.list(cl.getResources(path));
            assertTrue(found.isEmpty(),
                () -> fqn + " 残留在 classpath: " + found
                    + " —— 批 6 已把它搬进新四层（或改名为 Turn），请 mvn clean 后重建");
        }
    }

    /** 批 6 搬走 / 改名的 16 个类的旧全名（包还在，所以只能点名） */
    private static final List<String> BATCH6_MOVED_TO_FOUR_LAYERS = List.of(
        // 记忆线 7 件（旧服务改名为 Turn，旧全名随之下线）
        "com.smartdock.assistant.legacy.controller.AssistantMemoryController",
        "com.smartdock.assistant.legacy.service.AssistantMemoryService",
        "com.smartdock.assistant.legacy.domain.DcsAssistantMemory",
        "com.smartdock.assistant.legacy.domain.DcsAssistantMemoryPref",
        "com.smartdock.assistant.legacy.mapper.AssistantMemoryMapper",
        "com.smartdock.assistant.legacy.mapper.AssistantMemoryPrefMapper",
        "com.smartdock.assistant.legacy.job.AssistantMemoryArchiveJob",
        // 收藏线 4 件
        "com.smartdock.assistant.legacy.controller.AssistantFavoriteController",
        "com.smartdock.assistant.legacy.service.AssistantFavoriteService",
        "com.smartdock.assistant.legacy.domain.DcsAssistantFavorite",
        "com.smartdock.assistant.legacy.mapper.AssistantFavoriteMapper",
        // 反馈线 4 件
        "com.smartdock.assistant.legacy.controller.AssistantFeedbackController",
        "com.smartdock.assistant.legacy.service.AssistantFeedbackService",
        "com.smartdock.assistant.legacy.domain.DcsAssistantFeedback",
        "com.smartdock.assistant.legacy.mapper.AssistantFeedbackMapper",
        // 脱敏共享件 1 件
        "com.smartdock.assistant.legacy.security.AssistantPiiFilter");

    /** 批 7B（2026-11-20）：会话线 2 实体 + 2 mapper 搬进新四层后，旧全名在 classpath 上必须一个不剩。
     *  同批 4/5/6 动机：四个 legacy 子包都还在（只是变薄），只能点名；防「没 clean 就编译」的双注册。
     *  本批两个风险叠加：同简单类名会撞 MyBatis 别名（两处都在 typeAliasesPackage 覆盖内），
     *  同名 mapper 会撞 {@code @MapperScan} 的 bean 名（批 2 实际踩过的形态）。 */
    @Test
    void batch7bMovedConversationClassesMustBeAbsentFromClasspath() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String fqn : BATCH7B_MOVED_TO_FOUR_LAYERS) {
            String path = fqn.replace('.', '/') + ".class";
            List<URL> found = Collections.list(cl.getResources(path));
            assertTrue(found.isEmpty(),
                () -> fqn + " 残留在 classpath: " + found
                    + " —— 批 7B 已把它搬进新四层，请 mvn clean 后重建");
        }
    }

    /** 批 7B 搬走的 4 个类的旧全名（包还在，所以只能点名） */
    private static final List<String> BATCH7B_MOVED_TO_FOUR_LAYERS = List.of(
        "com.smartdock.assistant.legacy.domain.DcsAssistantConversation",
        "com.smartdock.assistant.legacy.domain.DcsAssistantMessage",
        "com.smartdock.assistant.legacy.mapper.AssistantConversationMapper",
        "com.smartdock.assistant.legacy.mapper.AssistantMessageMapper");

    /** 批 7C-1（2026-11-20）：台账线 2 实体 + 2 mapper 搬进新四层后，旧全名在 classpath 上必须一个不剩。
     *  动机同 7B：legacy 的 domain / mapper 两个子包都还在（只是变薄），只能点名；防「没 clean 就编译」
     *  造成 MyBatis 别名撞名与 {@code @MapperScan} bean 名双注册。 */
    @Test
    void batch7c1MovedRunClassesMustBeAbsentFromClasspath() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String fqn : BATCH7C1_MOVED_TO_FOUR_LAYERS) {
            String path = fqn.replace('.', '/') + ".class";
            List<URL> found = Collections.list(cl.getResources(path));
            assertTrue(found.isEmpty(),
                () -> fqn + " 残留在 classpath: " + found
                    + " —— 批 7C-1 已把它搬进新四层，请 mvn clean 后重建");
        }
    }

    /** 批 7C-1 搬走的 4 个类的旧全名（包还在，所以只能点名） */
    private static final List<String> BATCH7C1_MOVED_TO_FOUR_LAYERS = List.of(
        "com.smartdock.assistant.legacy.domain.DcsAssistantRun",
        "com.smartdock.assistant.legacy.domain.DcsAssistantRunIssue",
        "com.smartdock.assistant.legacy.mapper.AssistantRunMapper",
        "com.smartdock.assistant.legacy.mapper.AssistantRunIssueMapper");

    /** 批 7C-2（2026-11-20）：三个实体三个 mapper 搬进新四层 + legacy 会话壳整个删除。
     *  动机同 7C-1：legacy 的 domain / mapper / service 三个子包都还在，只能点名。 */
    @Test
    void batch7c2MovedPurgeClassesMustBeAbsentFromClasspath() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String fqn : BATCH7C2_MOVED_TO_FOUR_LAYERS) {
            String path = fqn.replace('.', '/') + ".class";
            List<URL> found = Collections.list(cl.getResources(path));
            assertTrue(found.isEmpty(),
                () -> fqn + " 残留在 classpath: " + found
                    + " —— 批 7C-2 已把它搬进新四层（或整个删除），请 mvn clean 后重建");
        }
    }

    /** 批 7C-2 搬走/删除的 7 个类的旧全名（包还在，所以只能点名） */
    private static final List<String> BATCH7C2_MOVED_TO_FOUR_LAYERS = List.of(
        "com.smartdock.assistant.legacy.domain.DcsAssistantAction",
        "com.smartdock.assistant.legacy.domain.DcsAssistantAttachment",
        "com.smartdock.assistant.legacy.domain.DcsAssistantCompaction",
        "com.smartdock.assistant.legacy.mapper.AssistantActionMapper",
        "com.smartdock.assistant.legacy.mapper.AssistantAttachmentMapper",
        "com.smartdock.assistant.legacy.mapper.AssistantCompactionMapper",
        "com.smartdock.assistant.legacy.service.AssistantConversationService");

    /** 批 8A（2026-11-21）：自动化/公告/报告外壳 17 件搬进新四层后，旧全名在 classpath 上必须一个不剩。
     *  动机同 7B/7C：legacy 的 automation 各子包与 controller/domain/mapper/service 都还在（只是变薄），
     *  只能点名；防「没 clean 就编译」造成 MyBatis 别名撞名与 {@code @MapperScan} bean 名双注册
     *  （同名 DcsAutomation 实体与同名 mapper 双份并存，批 2 实际踩过的形态）。 */
    @Test
    void batch8aMovedAutomationShellClassesMustBeAbsentFromClasspath() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String fqn : BATCH8A_MOVED_TO_FOUR_LAYERS) {
            String path = fqn.replace('.', '/') + ".class";
            List<URL> found = Collections.list(cl.getResources(path));
            assertTrue(found.isEmpty(),
                () -> fqn + " 残留在 classpath: " + found
                    + " —— 批 8A 已把它搬进新四层，请 mvn clean 后重建");
        }
    }

    /** 批 8A 搬走的 17 个类的旧全名（包还在，所以只能点名） */
    private static final List<String> BATCH8A_MOVED_TO_FOUR_LAYERS = List.of(
        "com.smartdock.assistant.legacy.automation.domain.DcsAutomation",
        "com.smartdock.assistant.legacy.automation.domain.DcsAutomationRun",
        "com.smartdock.assistant.legacy.automation.domain.AutomationBo",
        "com.smartdock.assistant.legacy.automation.domain.vo.AutomationVo",
        "com.smartdock.assistant.legacy.automation.domain.vo.AutomationRunVo",
        "com.smartdock.assistant.legacy.automation.mapper.DcsAutomationMapper",
        "com.smartdock.assistant.legacy.automation.mapper.DcsAutomationRunMapper",
        "com.smartdock.assistant.legacy.automation.service.AutomationSchedule",
        "com.smartdock.assistant.legacy.automation.service.AutomationNotifier",
        "com.smartdock.assistant.legacy.automation.service.AutomationFailurePolicy",
        "com.smartdock.assistant.legacy.automation.config.AutomationConfig",
        "com.smartdock.assistant.legacy.controller.AssistantAutomationController",
        "com.smartdock.assistant.legacy.controller.AssistantAnnouncementController",
        "com.smartdock.assistant.legacy.domain.DcsAssistantAnnouncement",
        "com.smartdock.assistant.legacy.mapper.AssistantAnnouncementMapper",
        "com.smartdock.assistant.legacy.controller.AssistantReportController",
        "com.smartdock.assistant.legacy.service.AssistantReportService");

    /** 批 8B（2026-11-21）：内核 4 件（Service/Runner/ScanJob/每日摘要 job）经红桥+紫桥换根后，
     *  旧全名在 classpath 上必须一个不剩。同批 8A 动机；这四件是带 @Scheduled 的活组件，
     *  双注册的最坏形态是同一定时任务被两份 bean 各跑一遍（重复推送/重复烧模型）。 */
    @Test
    void batch8bMovedKernelClassesMustBeAbsentFromClasspath() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String fqn : BATCH8B_MOVED_TO_FOUR_LAYERS) {
            String path = fqn.replace('.', '/') + ".class";
            List<URL> found = Collections.list(cl.getResources(path));
            assertTrue(found.isEmpty(),
                () -> fqn + " 残留在 classpath: " + found
                    + " —— 批 8B 已把它搬进新四层，请 mvn clean 后重建");
        }
    }

    /** 批 8B 搬走的 4 个类的旧全名（包还在，所以只能点名） */
    private static final List<String> BATCH8B_MOVED_TO_FOUR_LAYERS = List.of(
        "com.smartdock.assistant.legacy.automation.service.AssistantAutomationService",
        "com.smartdock.assistant.legacy.automation.service.AutomationRunner",
        "com.smartdock.assistant.legacy.automation.job.AutomationScanJob",
        "com.smartdock.assistant.legacy.job.AssistantDailyDigestJob");

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
