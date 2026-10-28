package com.smartdock.profile.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「未配置打分口径」护栏的契约测试(单源重构第一批 B 片):
 * 无启用维度的快照 detail 必须带 _noDimensions 标记, 且此类快照不入低分复核队列。
 * 纯函数测试, 不碰数据库。
 *
 * @author smartdock
 */
@Tag("local")
class ProfileScoreUnconfiguredGuardTest {

    @Test
    void flagKeyIsStableForFrontendContract() {
        // 前端(D 片)按此键显示「未配置打分规则」, 改名等于破坏契约
        assertTrue("_noDimensions".equals(ProfileScoreEngine.UNCONFIGURED_FLAG));
    }

    @Test
    void unconfiguredDetailIsRecognized() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(ProfileScoreEngine.UNCONFIGURED_FLAG, true);
        assertTrue(ProfileScoreEngine.isUnconfiguredDetail(detail));
    }

    @Test
    void normalDetailIsNotUnconfigured() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("act_freq", Map.of("dimName", "活跃度", "score", 88));
        assertFalse(ProfileScoreEngine.isUnconfiguredDetail(detail));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertFalse(ProfileScoreEngine.isUnconfiguredDetail(null));
        assertFalse(ProfileScoreEngine.isUnconfiguredDetail(new LinkedHashMap<>()));
    }
}
