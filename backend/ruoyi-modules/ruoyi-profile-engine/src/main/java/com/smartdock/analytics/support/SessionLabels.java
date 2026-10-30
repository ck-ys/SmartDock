package com.smartdock.analytics.support;

import org.dromara.common.core.utils.StringUtils;

/**
 * 会话明细的身份标签规则（2026-10-30 题10）。
 *
 * <p>抽成纯函数便于单测：登录主体判定与匿名访客短标签不该散落在服务里。</p>
 *
 * @author smartdock
 */
public final class SessionLabels {

    private SessionLabels() {
    }

    /** 登录主体：subjectType=user 且 subjectId 是数字才认，其余返回 null（按匿名处理）。 */
    public static Long loggedInUserId(String subjectType, String subjectId) {
        if (subjectType == null || !"user".equalsIgnoreCase(subjectType.trim()) || StringUtils.isBlank(subjectId)) {
            return null;
        }
        try {
            return Long.parseLong(subjectId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 匿名访客标签：取匿名牌（缺失退回访问者键）末 4 位，形如「访客 #0003」。 */
    public static String visitorLabel(String anonymousId, String visitorId) {
        String base = StringUtils.isNotBlank(anonymousId) ? anonymousId : visitorId;
        return "访客 #" + tail(base, 4, "----");
    }

    /** 取标识末 n 位（不足则原样）；空白给占位。会话短标签共用一个尺子。 */
    public static String tail(String key, int n, String blank) {
        if (StringUtils.isBlank(key)) {
            return blank;
        }
        String s = key.trim();
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
