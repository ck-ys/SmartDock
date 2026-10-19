package org.dromara.test.assistant;

import org.dromara.assistant.domain.DcsAssistantFavorite;
import org.dromara.assistant.mapper.AssistantFavoriteMapper;
import org.dromara.assistant.service.AssistantFavoriteService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C2 收藏跨设备服务单测（2026-10-07）：上限 6/去重/长度 200/清单序（新→旧）。
 * 纯 Mockito 无库依赖。
 */
@Tag("dev")
class AssistantFavoriteServiceTest {

    private final AssistantFavoriteMapper mapper = mock(AssistantFavoriteMapper.class);
    private final AssistantFavoriteService service = new AssistantFavoriteService(mapper);

    private static DcsAssistantFavorite row(String content) {
        DcsAssistantFavorite f = new DcsAssistantFavorite();
        f.setContent(content);
        return f;
    }

    /** 新增成功：入库一次并返回含新条的清单（listByUser 两次：查重→insert 后重查） */
    @Test
    void addPersistsAndReturnsLatest() {
        when(mapper.listByUser("000000", 1L))
            .thenReturn(List.of(row("旧一")))
            .thenReturn(List.of(row("新问句"), row("旧一")));
        when(mapper.insert(any(DcsAssistantFavorite.class))).thenReturn(1);
        List<String> out = service.add("000000", 1L, "新问句");
        assertTrue(out.contains("新问句"));
        verify(mapper).insert(any(DcsAssistantFavorite.class));
    }

    /** 上限 6：已满拒绝且不落库（人话文案） */
    @Test
    void addRejectsWhenFull() {
        when(mapper.listByUser("000000", 1L))
            .thenReturn(IntStream.rangeClosed(1, 6).mapToObj(i -> row("问句" + i)).toList());
        ServiceException ex = assertThrows(ServiceException.class, () -> service.add("000000", 1L, "第七条"));
        assertTrue(ex.getMessage().contains("6"));
        verify(mapper, never()).insert(any(DcsAssistantFavorite.class));
    }

    /** 去重：同内容幂等返回现清单，不重复插入 */
    @Test
    void addDeduplicates() {
        when(mapper.listByUser("000000", 1L)).thenReturn(List.of(row("重复问句")));
        List<String> out = service.add("000000", 1L, "重复问句");
        assertEquals(1, out.size());
        verify(mapper, never()).insert(any(DcsAssistantFavorite.class));
    }

    /** 长度 200 超限拒绝（与列宽一致，防 DB 报错面） */
    @Test
    void addRejectsOverlongContent() {
        assertThrows(ServiceException.class,
            () -> service.add("000000", 1L, "长".repeat(201)));
        verify(mapper, never()).insert(any(DcsAssistantFavorite.class));
    }

    /** 空白/空串拒绝 */
    @Test
    void addRejectsBlank() {
        assertThrows(ServiceException.class, () -> service.add("000000", 1L, "   "));
        verify(mapper, never()).insert(any(DcsAssistantFavorite.class));
    }

    /** 删除：按内容删并回清单 */
    @Test
    void removeDeletesByContent() {
        when(mapper.deleteByContent(anyString(), any(), anyString())).thenReturn(1);
        when(mapper.listByUser("000000", 1L)).thenReturn(List.of(row("剩下")));
        List<String> out = service.remove("000000", 1L, "删我");
        assertEquals(List.of("剩下"), out);
        verify(mapper).deleteByContent("000000", 1L, "删我");
    }
}
