package org.dromara.test.assistant;

import org.dromara.assistant.controller.AssistantConversationController;
import org.dromara.assistant.core.AssistantPlanSignal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ②（2026-10-31 拍板）：计划卡取消立即解锁——owner 三元组 + 归属决策。
 *
 * <p>pending 从裸截止时刻扩成 (deadline, tenantId, userId)：清闸只认提交人本人，
 * 租户源全链=登录快照（与 PlanTools 的 ToolIdentity.from 同源）。置错会出两种事故——
 * 一边是别人能把你的闸清掉（越权），另一边是本人清不掉（超管切租户后动态租户≠快照，
 * 只剩 30 分钟 TTL 兜底）。两侧都钉死；controller 归属三路（no-op/403/清闸）一并纯函数锚定。</p>
 */
@Tag("local")
class AssistantPlanCancelTest {

    private static final String TENANT_A = "000000";
    private static final String TENANT_B = "999999";
    private static final Long USER_U = 7L;
    private static final Long USER_V = 8L;

    @Test
    void ownerCanClearOwnPendingGate() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        signal.submit("pc-1", TENANT_A, USER_U, "催办三步走", List.of("查名单", "发催办"));
        assertTrue(signal.isPending("pc-1"), "submit 后进待确认态");

        assertTrue(signal.clearPending("pc-1", TENANT_A, USER_U), "本人清闸成功");
        assertFalse(signal.isPending("pc-1"), "清闸后写操作放行");
        assertFalse(signal.clearPending("pc-1", TENANT_A, USER_U), "再清=无 pending 幂等 no-op");
    }

    @Test
    void sameTenantOtherUserCannotClear() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        signal.submit("pc-2", TENANT_A, USER_U, null, List.of("a"));

        assertFalse(signal.clearPending("pc-2", TENANT_A, USER_V), "同租户异主清不动");
        assertTrue(signal.isPending("pc-2"), "闸仍在——异主的写操作照旧被拒");
    }

    @Test
    void otherTenantSameThreadCannotClear() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        signal.submit("pc-3", TENANT_A, USER_U, null, List.of("a"));

        // 他租户伪造同 threadId：租户+人都不匹配=不动，不能清走对方的闸
        assertFalse(signal.clearPending("pc-3", TENANT_B, USER_V), "他租户同 threadId 清不动");
        assertTrue(signal.isPending("pc-3"));
    }

    @Test
    void noPendingClearIsIdempotentNoOp() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        assertFalse(signal.clearPending("pc-none", TENANT_A, USER_U), "无 pending 清=no-op");
        assertFalse(signal.clearPending(null, TENANT_A, USER_U), "空 threadId 同样 no-op");
        assertFalse(signal.clearPending(" ", TENANT_A, USER_U));
    }

    @Test
    void expiredPendingSelfReleases() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        // ttl=0：deadline=submit 时刻，isPending 读取时必然已过期（时间不倒流）
        signal.submit("pc-ttl", TENANT_A, USER_U, null, List.of("a"), 0L);
        assertFalse(signal.isPending("pc-ttl"), "TTL 到期自动放开（用户关页面后的兜底）");
        assertFalse(signal.clearPending("pc-ttl", TENANT_A, USER_U), "过期已被 isPending 收走，再清=无 pending");
    }

    @Test
    void clearGateTenantMustMatchLoginSnapshotNotDynamic() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        // 超管登录快照租户 A 提交计划（ToolIdentity.from→user.getTenantId()），
        // 切租户后会话行落动态租户 B——清闸 owner 比对必须认快照 A
        signal.submit("pc-super", TENANT_A, USER_U, null, List.of("a"));

        // 反向先行：用动态租户 B 清同一把闸=owner 不匹配清不动
        assertFalse(signal.clearPending("pc-super", TENANT_B, USER_U),
            "动态租户 B 清不动——清闸租户=快照 A 契约");
        assertTrue(signal.isPending("pc-super"), "闸仍在");

        // 本人用登录快照租户 A：登录租户（快照）≠动态租户时仍可清闸
        assertTrue(signal.clearPending("pc-super", TENANT_A, USER_U),
            "登录租户（快照 A）≠动态租户 B 时，本人用快照租户仍可清闸");
        assertFalse(signal.isPending("pc-super"), "清闸后写操作放行");
    }

    @Test
    void controllerOwnershipDecisionThreeWays() {
        assertEquals(AssistantConversationController.PlanCancelAction.NOOP,
            AssistantConversationController.planCancelAction(null, USER_U), "会话行不存在（他租户/未创建）=幂等 no-op");
        assertEquals(AssistantConversationController.PlanCancelAction.FORBIDDEN,
            AssistantConversationController.planCancelAction(USER_V, USER_U), "同租户异主=403");
        assertEquals(AssistantConversationController.PlanCancelAction.CLEAR,
            AssistantConversationController.planCancelAction(USER_U, USER_U), "本人=清闸");
    }
}
