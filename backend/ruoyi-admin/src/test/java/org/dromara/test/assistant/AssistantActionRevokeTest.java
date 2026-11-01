package org.dromara.test.assistant;

import org.dromara.assistant.service.AssistantActionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三批④（2026-11-01）：撤回资格/回执锚点纯函数单测（不起 Spring）。
 *
 * <p>护栏重点：①canRevoke 矩阵（DONE+锚点+白名单缺一不可，REVOKED/FAILED/REJECTED/
 * 无锚点/非白名单全拒）；②REVOCABLE_TOOLS 内容钉死（双处清单的助手侧半边——
 * provider 侧另半边在 DcsToolProvider.revoke，漂移即此断言红）；③parseRefId 尾段
 * 直读（「（记录 #id）」尾段完整可解析=markDone/withRefTail 拼接口径成立；
 * 无尾段/垃圾→null 不猜）。</p>
 */
@Tag("local")
class AssistantActionRevokeTest {

    /** canRevoke 矩阵：唯一放行组合=DONE + refId≠null + 白名单工具 */
    @Test
    void canRevokeMatrix() {
        assertTrue(AssistantActionService.canRevoke("DONE", 123L, "save_memory"), "DONE+锚点+save_memory→可撤");
        assertTrue(AssistantActionService.canRevoke("DONE", 456L, "dcs_create_task"), "DONE+锚点+dcs_create_task→可撤");

        assertFalse(AssistantActionService.canRevoke("REVOKED", 123L, "save_memory"), "已撤回过→不可再撤");
        assertFalse(AssistantActionService.canRevoke("FAILED", 123L, "save_memory"), "失败执行→不可撤");
        assertFalse(AssistantActionService.canRevoke("REJECTED", 123L, "save_memory"), "拒绝留痕行→不可撤");
        assertFalse(AssistantActionService.canRevoke("EXECUTING", 123L, "save_memory"), "在途执行→不可撤");
        assertFalse(AssistantActionService.canRevoke("DONE", null, "save_memory"), "无回执锚点→无可撤业务对象");
        assertFalse(AssistantActionService.canRevoke("DONE", 123L, "act_remind"), "非白名单工具→不可撤");
        assertFalse(AssistantActionService.canRevoke(null, 123L, "save_memory"), "状态空→不可撤");
    }

    /** 可撤白名单内容钉死：只认 save_memory / dcs_create_task（新增须双处登记，见常量注释） */
    @Test
    void revocableToolsPinned() {
        assertEquals(java.util.Set.of("save_memory", "dcs_create_task"),
            AssistantActionService.REVOCABLE_TOOLS, "白名单漂移=完成卡撤回按钮口径漂移，须与 provider.revoke 同批改");
    }

    /** parseRefId：尾段「（记录 #id）」直读；无尾段/锚点不在结尾/垃圾→null（不猜） */
    @Test
    void parseRefIdReadsTailOnly() {
        assertEquals(123L, AssistantActionService.parseRefId("已记住：称呼我为小林（记录 #123）"));
        assertEquals(910000000000201L,
            AssistantActionService.parseRefId(AssistantActionService.withRefTail("执".repeat(600), 910000000000201L)),
            "长摘要截断后尾段仍完整落在结尾（withRefTail 顺序钉死的消费方证明）");

        assertNull(AssistantActionService.parseRefId("已记住：称呼我为小林"), "无尾段→null");
        assertNull(AssistantActionService.parseRefId(null));
        assertNull(AssistantActionService.parseRefId("  "));
        // 锚点不在结尾（拼接口径外的人工形态）不猜——防「正文里恰好含（记录 #…）」误读
        assertNull(AssistantActionService.parseRefId("（记录 #9）后面还有正文"));
        assertNull(AssistantActionService.parseRefId("结果（记录 #abc）"), "锚点非数字→null");
    }
}
