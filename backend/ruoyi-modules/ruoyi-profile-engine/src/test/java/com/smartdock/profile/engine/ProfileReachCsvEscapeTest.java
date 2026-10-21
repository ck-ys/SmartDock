package com.smartdock.profile.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV 公式注入转义测试(批11 Q5): =/+/-/@/TAB/CR 开头的单元格前置单引号。
 * csvCell 是私有纯函数, 反射调用只测转义逻辑本身。
 */
@Tag("local")
class ProfileReachCsvEscapeTest {

    private String csvCell(String value) throws Exception {
        Method m = ProfileReachService.class.getDeclaredMethod("csvCell", String.class);
        m.setAccessible(true);
        return (String) m.invoke(new ProfileReachService(null, null, null, null, null, null, null, null, null), value);
    }

    @Test
    void formulaPrefixesGetSingleQuoteGuard() throws Exception {
        assertThat(csvCell("=cmd|'/c calc'")).isEqualTo("'=cmd|'/c calc'");
        assertThat(csvCell("+1+1")).isEqualTo("'+1+1");
        assertThat(csvCell("-2+3")).isEqualTo("'-2+3");
        assertThat(csvCell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(csvCell("\ttab")).startsWith("'\t");
    }

    @Test
    void normalValuesUnchangedAndStillQuotedWhenNeeded() throws Exception {
        assertThat(csvCell("/home")).isEqualTo("/home");
        assertThat(csvCell("hello,world")).isEqualTo("\"hello,world\"");
        assertThat(csvCell("普通值")).isEqualTo("普通值");
        assertThat(csvCell(null)).isEmpty();
    }
}
