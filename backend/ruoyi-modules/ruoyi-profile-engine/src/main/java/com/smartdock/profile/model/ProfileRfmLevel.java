package com.smartdock.profile.model;

import java.math.BigDecimal;

/**
 * RFM 八类客户分层(与 DCS 口径一致, 阈值按 1~5 分的中位 3.5)。
 *
 * @author smartdock
 */
public final class ProfileRfmLevel {

    public static final BigDecimal MID = BigDecimal.valueOf(3.5);

    private ProfileRfmLevel() {
    }

    public static String of(BigDecimal r, BigDecimal f, BigDecimal m) {
        boolean rHigh = r != null && r.compareTo(MID) >= 0;
        boolean fHigh = f != null && f.compareTo(MID) >= 0;
        boolean mHigh = m != null && m.compareTo(MID) >= 0;
        if (rHigh && fHigh && mHigh) {
            return "重要价值客户";
        }
        if (!rHigh && fHigh && mHigh) {
            return "重要保持客户";
        }
        if (rHigh && !fHigh && mHigh) {
            return "重要发展客户";
        }
        if (!rHigh && !fHigh && mHigh) {
            return "重要挽留客户";
        }
        if (rHigh && fHigh) {
            return "一般价值客户";
        }
        if (!rHigh && fHigh) {
            return "一般保持客户";
        }
        if (rHigh) {
            return "一般发展客户";
        }
        return "一般挽留客户";
    }

    /**
     * 通用模型的粗档位: 按归一化分。
     */
    public static String generic(BigDecimal normalized) {
        if (normalized == null) {
            return "未评分";
        }
        if (normalized.compareTo(BigDecimal.valueOf(80)) >= 0) {
            return "高价值";
        }
        if (normalized.compareTo(BigDecimal.valueOf(60)) >= 0) {
            return "中价值";
        }
        return "低价值";
    }
}
