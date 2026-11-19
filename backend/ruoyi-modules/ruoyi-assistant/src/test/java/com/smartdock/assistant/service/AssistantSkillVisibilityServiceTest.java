package com.smartdock.assistant.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 技能可见性统一入口的纯函数护栏（批 4 题 2 案 1，2026-11-19）。
 *
 * <p>四类断言：① 档位阶梯（member/publisher/admin）② 只升不降（矩阵覆盖不许降内置档）
 * ③ off 传播（-1 对谁都不可见）④ 非法入参 fail-closed（null/空白 → -1，id 先 trim）。
 * 全部纯内存，不起 Spring、不连库。</p>
 *
 * @author smartdock
 */
@Tag("local")
class AssistantSkillVisibilityServiceTest {

    private static final int MEMBER = 0;
    private static final int PUBLISHER = 1;
    private static final int ADMIN = 2;

    private final AssistantSkillVisibilityService svc = new AssistantSkillVisibilityService();

    @Test
    void roleLadder() {
        // 内置档 member：成员/发布人/管理员都看得见
        assertThat(svc.visible("data-reading", MEMBER, MEMBER, Map.of())).isTrue();
        assertThat(svc.visible("data-reading", MEMBER, PUBLISHER, Map.of())).isTrue();
        assertThat(svc.visible("data-reading", MEMBER, ADMIN, Map.of())).isTrue();
        // 内置档 publisher：成员看不见，发布人起可见
        assertThat(svc.visible("create-mission", PUBLISHER, MEMBER, Map.of())).isFalse();
        assertThat(svc.visible("create-mission", PUBLISHER, PUBLISHER, Map.of())).isTrue();
        // 内置档 admin：只有管理员
        assertThat(svc.visible("admin-only", ADMIN, PUBLISHER, Map.of())).isFalse();
        assertThat(svc.visible("admin-only", ADMIN, ADMIN, Map.of())).isTrue();
    }

    @Test
    void offHidesFromEveryoneIncludingAdmin() {
        Map<String, String> gate = Map.of("skill:create-mission", "off");
        assertThat(svc.effectiveMinRole("create-mission", PUBLISHER, gate)).isEqualTo(-1);
        assertThat(svc.visible("create-mission", PUBLISHER, PUBLISHER, gate)).isFalse();
        assertThat(svc.visible("create-mission", PUBLISHER, ADMIN, gate)).isFalse();
    }

    @Test
    void overrideMayOnlyRaiseNeverLower() {
        // 内置 member + 覆盖 admin → 2（提档生效）
        assertThat(svc.effectiveMinRole("data-reading", MEMBER, Map.of("skill:data-reading", "admin")))
            .isEqualTo(ADMIN);
        // 内置 admin + 覆盖 member → 仍是 2（只升不降，降档请求被忽略）
        assertThat(svc.effectiveMinRole("admin-only", ADMIN, Map.of("skill:admin-only", "member")))
            .isEqualTo(ADMIN);
        // 内置 publisher + 覆盖 member → 仍是 1
        assertThat(svc.effectiveMinRole("create-mission", PUBLISHER, Map.of("skill:create-mission", "member")))
            .isEqualTo(PUBLISHER);
    }

    @Test
    void nullGateFallsBackToBuiltin() {
        // 生产链路上 effective().toolGate() 恒非 null（行不存在→Map.of()，坏 JSON→全关），
        // 此处只钉「传 null 也不炸、按内置档作答」这一兜底语义
        assertThat(svc.effectiveMinRole("create-mission", PUBLISHER, null)).isEqualTo(PUBLISHER);
        assertThat(svc.visible("create-mission", PUBLISHER, MEMBER, null)).isFalse();
    }

    @Test
    void illegalSkillIdFailsClosed() {
        // 多审并发席 P2-2：null/空白必须判不可见，不许按内置档作答（否则 off 覆盖会被静默绕过）
        assertThat(svc.effectiveMinRole(null, MEMBER, Map.of())).isEqualTo(-1);
        assertThat(svc.effectiveMinRole("   ", MEMBER, Map.of())).isEqualTo(-1);
        assertThat(svc.visible(null, MEMBER, ADMIN, Map.of())).isFalse();
        assertThat(svc.visible("   ", MEMBER, ADMIN, Map.of())).isFalse();
    }

    @Test
    void idIsTrimmedBeforeGateLookup() {
        // 带首尾空格时若不算 trim，键变成 "skill: data-reading " 查不到覆盖 → 管理员配的 off 失效
        Map<String, String> gate = Map.of("skill:data-reading", "off");
        assertThat(svc.effectiveMinRole(" data-reading ", MEMBER, gate)).isEqualTo(-1);
        assertThat(svc.visible(" data-reading ", MEMBER, ADMIN, gate)).isFalse();
    }
}
