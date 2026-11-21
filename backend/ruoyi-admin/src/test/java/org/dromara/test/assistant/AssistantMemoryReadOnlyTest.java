package org.dromara.test.assistant;

import com.smartdock.assistant.domain.model.AssistantMemory;
import com.smartdock.assistant.mapper.AssistantMemoryMapper;
import com.smartdock.assistant.mapper.AssistantMemoryPrefMapper;
import com.smartdock.assistant.service.AssistantMemoryService;
import com.smartdock.assistant.support.AssistantMemoryFacts;
import com.smartdock.assistant.support.AssistantPiiFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆只读摘要 vs 注入摘要的写行为（2026-11-15 三审席1 P1 的回归锁；批 6 跟随拆分改构造，2026-11-20）。
 *
 * <p>为什么必须有这条测试：{@code /status/context} 每次悬停都会读记忆，若它顺手回写
 * {@code last_used_at}，题10 的「90 天未用归档」就永远判不出来（所有看过的记忆都算"用过"）。
 * 行为差异是纯副作用，编译期看不出来，只能靠 mock 验证调用次数。</p>
 */
@Tag("local")
@DisplayName("长期记忆：只读摘要不写 last_used_at，注入摘要写一次")
class AssistantMemoryReadOnlyTest {

    @Test
    @DisplayName("digestReadOnly 不回写；digest 回写一次且走 30s 缓存")
    void readOnlyDoesNotTouchUsed() {
        AssistantMemoryMapper memoryMapper = mock(AssistantMemoryMapper.class);
        AssistantMemoryPrefMapper prefMapper = mock(AssistantMemoryPrefMapper.class);
        AssistantPiiFilter piiFilter = mock(AssistantPiiFilter.class);
        AssistantMemoryService service = new AssistantMemoryService(memoryMapper, prefMapper, piiFilter);

        when(prefMapper.selectOwn("000000", 7L)).thenReturn(null); // 缺行=本人开关默认开
        AssistantMemory row = new AssistantMemory();
        row.setId(99L);
        row.setContent("以后叫我老王");
        when(memoryMapper.listByUserStatus("000000", 7L, AssistantMemoryFacts.ST_ACTIVE))
            .thenReturn(List.of(row));

        // ① 读接口路径：两次都拿到摘要，但绝不回写 last_used_at
        assertNotNull(service.digestReadOnly("000000", 7L, "以后我该怎么称呼你"));
        assertNotNull(service.digestReadOnly("000000", 7L, "以后我该怎么称呼你"));
        verify(memoryMapper, never()).touchUsed(any(), any(), any(), any());

        // ② 注入路径：第一次算完回写一次，第二次命中 30s 缓存不再写
        assertNotNull(service.digest("000000", 7L, "以后我该怎么称呼你"));
        verify(memoryMapper, times(1)).touchUsed(eq("000000"), eq(7L), anyList(), any(Date.class));
        assertNotNull(service.digest("000000", 7L, "以后我该怎么称呼你"));
        verify(memoryMapper, times(1)).touchUsed(eq("000000"), eq(7L), anyList(), any(Date.class));

        // ③ 只读路径没有把结果塞进缓存（否则第二次 digest 会 0 次回写——上面 ② 已验证为 1 次）
        assertEquals(1, service.digestReadOnly("000000", 7L, "以后我该怎么称呼你").count());
    }
}
