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
}
