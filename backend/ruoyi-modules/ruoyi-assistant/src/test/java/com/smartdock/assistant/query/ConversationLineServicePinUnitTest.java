package com.smartdock.assistant.query;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smartdock.assistant.domain.model.AssistantConversation;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.service.ConversationMetaService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批 7 · 7B（2026-11-20）：会话线错误码与归属判定钉。
 *
 * <p>为什么补：7B 把「归属校验 + 403」「标题 1-60 字 400」原样搬进新根服务，并把
 * {@code by-thread} 的异主判定从控制器下沉——这几条是外部可观察契约（前端按 code 分流），
 * 活体只能证明「当前数据上对」，这里钉「给定输入下必定 403/400」，且顺序不变
 * （标题先校验再查库：mapper 传 null，谁把顺序调过来就 NPE 自己证伪）。</p>
 */
@Tag("local")
class ConversationLineServicePinUnitTest {

    private static final String TENANT = "000000";
    private static final Long USER_ID = 9100000001L;
    private static final Long OTHER_USER = 9100000002L;
    private static final Long CONV_ID = 100L;

    /** {@code LambdaUpdateWrapper.getSqlSet()} 要把方法引用解析成列名，需要 MP 的实体元数据缓存；
     *  纯单测没有 Spring / MapperScan 帮忙初始化，这里手工种一次（不连库、不扫包）。 */
    static {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            AssistantConversation.class);
    }

    private static ServiceException catchServiceException(Runnable r) {
        return assertThrows(ServiceException.class, r::run);
    }

    /** 读消息：会话不存在/异主 → 403（不区分，防探测） */
    @Test
    void 读消息异主或不存在一律403() {
        AssistantConversationMapper mapper = mock(AssistantConversationMapper.class);
        when(mapper.selectOwned(anyString(), anyLong(), anyLong())).thenReturn(null);
        // messageMapper 传 null：403 必须在查消息前抛出——顺序变了就 NPE
        ConversationQueryService svc = new ConversationQueryService(mapper, null, null, null);

        assertEquals(403, catchServiceException(() -> svc.listMessages(TENANT, OTHER_USER, CONV_ID, 10, 0)).getCode());
        assertEquals(403, catchServiceException(() -> svc.listMessagesTail(TENANT, OTHER_USER, CONV_ID, 10)).getCode());
    }

    /** 归档 / 恢复：异主 → 403 */
    @Test
    void 元数据写异主一律403() {
        AssistantConversationMapper mapper = mock(AssistantConversationMapper.class);
        when(mapper.selectOwned(anyString(), anyLong(), anyLong())).thenReturn(null);
        ConversationMetaService svc = new ConversationMetaService(mapper);

        assertEquals(403, catchServiceException(() -> svc.archiveConversation(TENANT, OTHER_USER, CONV_ID)).getCode());
        assertEquals(403, catchServiceException(() -> svc.restoreConversation(TENANT, OTHER_USER, CONV_ID)).getCode());
        assertEquals(403, catchServiceException(() -> svc.renameConversation(TENANT, OTHER_USER, CONV_ID, "新标题")).getCode());
    }

    /** 标题规则：空白 / 超 60 字 → 400；且在查库之前判（mapper 传 null 当探针） */
    @Test
    void 标题长度校验先于查库且返回400() {
        ConversationMetaService noMapper = new ConversationMetaService(null);
        assertEquals(400, catchServiceException(() -> noMapper.renameConversation(TENANT, USER_ID, CONV_ID, null)).getCode());
        assertEquals(400, catchServiceException(() -> noMapper.renameConversation(TENANT, USER_ID, CONV_ID, "   ")).getCode());
        assertEquals(400, catchServiceException(
            () -> noMapper.renameConversation(TENANT, USER_ID, CONV_ID, "字".repeat(61))).getCode());
    }

    /** by-thread 归属：异主与不存在同为 null；同主原样返回（响应文案由 controller 给） */
    @Test
    void byThread异主与不存在同判null() {
        AssistantConversationMapper mapper = mock(AssistantConversationMapper.class);
        AssistantConversation own = new AssistantConversation();
        own.setId(CONV_ID);
        own.setUserId(USER_ID);
        AssistantConversation other = new AssistantConversation();
        other.setId(CONV_ID);
        other.setUserId(OTHER_USER);
        when(mapper.selectByThread(TENANT, "t-own")).thenReturn(own);
        when(mapper.selectByThread(TENANT, "t-other")).thenReturn(other);
        when(mapper.selectByThread(TENANT, "t-404")).thenReturn(null);

        ConversationQueryService svc = new ConversationQueryService(mapper, null, null, null);
        assertNotNull(svc.conversationByThreadOwned(TENANT, USER_ID, "t-own"));
        assertNull(svc.conversationByThreadOwned(TENANT, USER_ID, "t-other"), "异主必须同判 null（防探测）");
        assertNull(svc.conversationByThreadOwned(TENANT, USER_ID, "t-404"));
    }

    /** 元数据写「写入什么值」也要钉（多审 dspro P2-2）：状态值写反、条件漏租户，前者会让归档变恢复，
     *  后者会跨租户改行——两条都不会被 403 断言抓到。这里直接抓 wrapper 的 set 值与条件列。 */
    @Test
    void 归档写入的状态值逐字为ARCHIVED() {
        AssistantConversationMapper mapper = mock(AssistantConversationMapper.class);
        when(mapper.selectOwned(anyString(), anyLong(), anyLong())).thenReturn(owned());
        new ConversationMetaService(mapper).archiveConversation(TENANT, USER_ID, CONV_ID);

        LambdaUpdateWrapper<AssistantConversation> wrapper = captureUpdate(mapper);
        assertTrue(wrapper.getSqlSet().startsWith("status="), "归档 set 的列必须是 status，实际: " + wrapper.getSqlSet());
        assertThat(wrapper.getParamNameValuePairs().values())
            .as("归档写的值必须是 ARCHIVED").contains("ARCHIVED").doesNotContain("ACTIVE");
        assertThat(wrapper.getSqlSegment())
            .as("归档条件必须带租户与会话 id").contains("tenant_id").containsPattern("\\bid\\b");
    }

    @Test
    void 恢复写入的状态值逐字为ACTIVE() {
        AssistantConversationMapper mapper = mock(AssistantConversationMapper.class);
        when(mapper.selectOwned(anyString(), anyLong(), anyLong())).thenReturn(owned());
        new ConversationMetaService(mapper).restoreConversation(TENANT, USER_ID, CONV_ID);

        LambdaUpdateWrapper<AssistantConversation> wrapper = captureUpdate(mapper);
        assertTrue(wrapper.getSqlSet().startsWith("status="), "恢复 set 的列必须是 status，实际: " + wrapper.getSqlSet());
        assertThat(wrapper.getParamNameValuePairs().values())
            .as("恢复写的值必须是 ACTIVE").contains("ACTIVE").doesNotContain("ARCHIVED");
        assertThat(wrapper.getSqlSegment()).contains("tenant_id").containsPattern("\\bid\\b");
    }

    /** 重命名写的是 trim 后的值（防「存了带空格的原串」这类静默漂移） */
    @Test
    void 重命名写入trim后的标题() {
        AssistantConversationMapper mapper = mock(AssistantConversationMapper.class);
        when(mapper.selectOwned(anyString(), anyLong(), anyLong())).thenReturn(owned());
        new ConversationMetaService(mapper).renameConversation(TENANT, USER_ID, CONV_ID, "  新标题  ");

        LambdaUpdateWrapper<AssistantConversation> wrapper = captureUpdate(mapper);
        assertTrue(wrapper.getSqlSet().startsWith("title="), "重命名 set 的列必须是 title，实际: " + wrapper.getSqlSet());
        assertThat(wrapper.getParamNameValuePairs().values()).contains("新标题");
        assertThat(wrapper.getSqlSegment()).contains("tenant_id").containsPattern("\\bid\\b");
    }

    private static AssistantConversation owned() {
        AssistantConversation row = new AssistantConversation();
        row.setId(CONV_ID);
        row.setUserId(USER_ID);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static LambdaUpdateWrapper<AssistantConversation> captureUpdate(
        AssistantConversationMapper mapper) {
        ArgumentCaptor<Wrapper<AssistantConversation>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), cap.capture());
        return (LambdaUpdateWrapper<AssistantConversation>) cap.getValue();
    }
}
