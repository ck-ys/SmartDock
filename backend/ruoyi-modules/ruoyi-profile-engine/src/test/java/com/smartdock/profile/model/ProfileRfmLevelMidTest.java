package com.smartdock.profile.model;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 批5A: RFM 分层阈值可配——缺省 3.5 与旧实现逐位一致, 自定义阈值改变分界。
 *
 * @author smartdock
 */
@Tag("local")
class ProfileRfmLevelMidTest {

    private static final List<BigDecimal> SAMPLES = List.of(
        BigDecimal.valueOf(1.0), BigDecimal.valueOf(2.4), BigDecimal.valueOf(3.4),
        BigDecimal.valueOf(3.5), BigDecimal.valueOf(3.6), BigDecimal.valueOf(4.2), BigDecimal.valueOf(5.0));

    /** 旧口径锚点: of(r,f,m) 三参版按 MID=3.5 分界 */
    @Test
    void defaultMidMatchesLegacyBitwise() {
        int checked = 0;
        for (BigDecimal r : SAMPLES) {
            for (BigDecimal f : SAMPLES) {
                for (BigDecimal m : SAMPLES) {
                    String legacy = legacyOf(r, f, m);
                    assertEquals(legacy, ProfileRfmLevel.of(r, f, m), "r=" + r + " f=" + f + " m=" + m);
                    // 显式传 3.5 与缺省一致
                    assertEquals(legacy, ProfileRfmLevel.of(r, f, m, BigDecimal.valueOf(3.5)), "explicit 3.5 r=" + r);
                    // null mid 与缺省一致(容错)
                    assertEquals(legacy, ProfileRfmLevel.of(r, f, m, null), "null mid r=" + r);
                    checked++;
                }
            }
        }
        assertEquals(343, checked);
    }

    /** 自定义阈值生效: mid=4.0 时 (4.0,4.0,4.0) 从高变低组 */
    @Test
    void customMidShiftsBoundary() {
        BigDecimal r = BigDecimal.valueOf(4.0);
        BigDecimal f = BigDecimal.valueOf(4.0);
        BigDecimal m = BigDecimal.valueOf(4.0);
        assertEquals("重要价值客户", ProfileRfmLevel.of(r, f, m, BigDecimal.valueOf(3.5)));
        assertEquals("一般挽留客户", ProfileRfmLevel.of(r, f, m, BigDecimal.valueOf(4.1)));
        // 边界值本身: 4.0 >= 4.0 仍算高
        assertEquals("重要价值客户", ProfileRfmLevel.of(r, f, m, BigDecimal.valueOf(4.0)));
    }

    /** 旧实现逐字复刻(阈值 3.5)——迁移后仍逐位一致的锚 */
    private String legacyOf(BigDecimal r, BigDecimal f, BigDecimal m) {
        boolean rHigh = r.compareTo(BigDecimal.valueOf(3.5)) >= 0;
        boolean fHigh = f.compareTo(BigDecimal.valueOf(3.5)) >= 0;
        boolean mHigh = m.compareTo(BigDecimal.valueOf(3.5)) >= 0;
        List<String> order = new ArrayList<>(List.of(
            "重要价值客户", "重要保持客户", "重要发展客户", "重要挽留客户",
            "一般价值客户", "一般保持客户", "一般发展客户"));
        int idx = (rHigh ? 1 : 0) * 4 + (fHigh ? 1 : 0) * 2 + (mHigh ? 1 : 0);
        // 000..111 与 of() 分支序一致: 111重要价值 011重要保持 101重要发展 001重要挽留 110一般价值 010一般保持 100一般发展 000一般挽留
        int[] map = {7, 3, 5, 1, 6, 2, 4, 0};
        return map[idx] < 7 ? order.get(map[idx]) : "一般挽留客户";
    }
}
