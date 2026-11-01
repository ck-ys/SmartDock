package org.dromara.assistant.persist;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 多实例批 3：陈旧 ASKING 只救“死主”会话——第一步是正确解出 session_id 归属。
 * 解析错的后果：拿错 thread 去查 run，可能把还在等用户确认的会话误翻成 allowed。
 */
@Tag("local")
class AssistantStaleAskingRecoveryParseTest {

    @Test
    void parsesStandardSessionId() {
        AssistantStaleAskingRecovery.SessionRef ref =
            AssistantStaleAskingRecovery.parse("t:000000:42:thread-abc");
        assertEquals("000000", ref.tenantId());
        assertEquals(42L, ref.userId());
        assertEquals("thread-abc", ref.threadId());
    }

    @Test
    void threadIdMayContainColon() {
        AssistantStaleAskingRecovery.SessionRef ref =
            AssistantStaleAskingRecovery.parse("t:000000:42:a:b:c");
        assertEquals("a:b:c", ref.threadId(), "只按前三个冒号切分，threadId 原样保留");
    }

    @Test
    void malformedIdsAreSkipped() {
        assertNull(AssistantStaleAskingRecovery.parse(null));
        assertNull(AssistantStaleAskingRecovery.parse("thread-abc"));
        assertNull(AssistantStaleAskingRecovery.parse("t::42:th"));
        assertNull(AssistantStaleAskingRecovery.parse("t:000000:abc:th"));
        assertNull(AssistantStaleAskingRecovery.parse("t:000000:42:"));
        assertNull(AssistantStaleAskingRecovery.parse("t:000000:42"));
    }
}
