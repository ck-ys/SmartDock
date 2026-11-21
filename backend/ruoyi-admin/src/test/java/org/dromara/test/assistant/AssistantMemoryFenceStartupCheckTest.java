package org.dromara.test.assistant;

import com.smartdock.assistant.support.AssistantMemoryFenceStartupCheck;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批 7 前置 D1 多审 P1（席2）：索引漏跑 = 修复静默失效 → 启动 fail-fast 且提示先跑迁移。
 * 二轮席3 加固后是双断言：栅栏索引（有效且唯一）+ pref 唯一键（按列集）。
 * 变异验证（2026-11-20）：条件改恒抛后 passesWhenIndexPresent 变红，证明测试咬得住。
 */
@Tag("local")
class AssistantMemoryFenceStartupCheckTest {

    /** 双查询桩：按 SQL 内容分流——栅栏查 uk_asst_memory_active_topic，pref 查表名 */
    private static JdbcTemplate jt(Integer fenceCount, Integer prefCount) {
        JdbcTemplate t = mock(JdbcTemplate.class);
        when(t.queryForObject(anyString(), eq(Integer.class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            if (sql.contains("uk_asst_memory_active_topic")) {
                return fenceCount;
            }
            return prefCount;
        });
        return t;
    }

    @Test
    void refusesStartupWhenFenceIndexMissing() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new AssistantMemoryFenceStartupCheck(jt(0, 1)).check());
        assertTrue(ex.getMessage().contains("uk_asst_memory_active_topic"), ex.getMessage());
        assertTrue(ex.getMessage().contains("2026-11-20_memory_active_topic_unique.sql"),
            "提示里要指到迁移文件: " + ex.getMessage());
    }

    @Test
    void refusesStartupWhenFenceQueryReturnsNull() {
        assertThrows(IllegalStateException.class,
            () -> new AssistantMemoryFenceStartupCheck(jt(null, 1)).check());
    }

    /** 二轮席3 P2-11：pref 唯一键也要自检——缺了它「应用正常启动、用户点开关才 500」 */
    @Test
    void refusesStartupWhenPrefUniqueMissing() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new AssistantMemoryFenceStartupCheck(jt(1, 0)).check());
        assertTrue(ex.getMessage().contains("assistant_memory_pref"), ex.getMessage());
        assertTrue(ex.getMessage().contains("2026-11-20_memory_pref_constraints.sql"),
            "提示里要指到 pref 迁移文件: " + ex.getMessage());
    }

    @Test
    void passesWhenBothPresent() {
        assertDoesNotThrow(() -> new AssistantMemoryFenceStartupCheck(jt(1, 1)).check());
    }
}
