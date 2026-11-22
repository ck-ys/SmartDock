package com.smartdock.assistant.service;

import com.smartdock.assistant.domain.bo.AutomationBo;
import com.smartdock.assistant.domain.gateway.TenantConfigGateway;
import com.smartdock.assistant.domain.model.Automation;
import com.smartdock.assistant.domain.model.AutomationRun;
import com.smartdock.assistant.domain.vo.AutomationVo;
import com.smartdock.assistant.mapper.AutomationMapper;
import com.smartdock.assistant.mapper.AutomationRunMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批 10 · 10A-4：{@link AssistantAutomationService} 的首个测试（451 行、3 个生产调用方、此前零测试）。
 *
 * <p><b>为什么补这里</b>：本类是「定时任务 + 用户数据 + 自动停用」三件事的交汇点，出错的形态都不是
 * 编译错，而是静默错：可见性判定漏一条谓词 = 看到别人的自动化；5 条上限不加锁 = 并发超卖；
 * 补跑窗口算反 = 该跑的记 MISSED、或积压全补跑；内置项被当自建项编辑 = 管理员把系统项改坏。</p>
 *
 * <p><b>钉什么</b>：①可见性/归属的三道门；②上限与锁的先后；③补跑窗口两侧边界；
 * ④内置项只能开关；⑤失败计数达阈值即停用；⑥状态以租户配置为准。不测 SQL（那是
 * {@code RunLedgerRealDbTest} 同族的活），只测编排与判定。</p>
 */
@Tag("local")
class AssistantAutomationServiceUnitTest {

    private static final String TENANT = "000000";
    private static final Long USER = 9100000030L;
    private static final Long OTHER = 9100000031L;

    private final AutomationMapper automationMapper = mock(AutomationMapper.class);
    private final AutomationRunMapper runMapper = mock(AutomationRunMapper.class);
    private final TenantConfigGateway tenantConfig = mock(TenantConfigGateway.class);
    private final AutomationRunner runner = mock(AutomationRunner.class);
    private final AutomationNotifier notifier = mock(AutomationNotifier.class);
    private final AutomationFailurePolicy failurePolicy = mock(AutomationFailurePolicy.class);

    private AssistantAutomationService svc() {
        return new AssistantAutomationService(automationMapper, runMapper, tenantConfig, runner,
            notifier, failurePolicy);
    }

    // ══════════════════════════════════════════════════════════════════
    // ① 可见性与归属
    // ══════════════════════════════════════════════════════════════════

    @Test
    void 详情_自建项异主一律403_内置项本租户可见() {
        when(automationMapper.selectOne(TENANT, 1L)).thenReturn(auto(1L, OTHER, "0"));
        assertEquals(Integer.valueOf(403), assertThrows(ServiceException.class,
            () -> svc().detail(TENANT, USER, 1L, false)).getCode(),
            "别人的自建项必须 403（能读到=越权）");

        when(automationMapper.selectOne(TENANT, 2L)).thenReturn(auto(2L, 0L, "1"));
        when(tenantConfig.digestEnabled(TENANT)).thenReturn(true);
        when(runMapper.countByAutomation(TENANT, 2L)).thenReturn(0);
        when(runMapper.countSuccess(TENANT, 2L)).thenReturn(0);
        assertEquals(2L, svc().detail(TENANT, USER, 2L, false).getId(),
            "内置项是本租户公共资产，任何成员都可见");

        when(automationMapper.selectOne(TENANT, 3L)).thenReturn(null);
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class,
            () -> svc().detail(TENANT, USER, 3L, false)).getCode(), "不存在必须 404");
    }

    @Test
    void 内置项_不许编辑或删除_只能开关() {
        when(automationMapper.selectOne(TENANT, 9L)).thenReturn(auto(9L, 0L, "1"));

        ServiceException edit = assertThrows(ServiceException.class,
            () -> svc().update(TENANT, USER, 9L, bo("标题", "提示", "DAILY", "09:00", null)));
        assertEquals(Integer.valueOf(400), edit.getCode(),
            "内置项被当自建项编辑=管理员能改坏系统项，必须 400 挡住");
        assertTrue(edit.getMessage().contains("内置"), "文案要说明是内置项，否则用户以为界面坏了");

        assertEquals(Integer.valueOf(400), assertThrows(ServiceException.class,
            () -> svc().delete(TENANT, USER, 9L)).getCode());
        verify(automationMapper, never()).softDeleteOwn(anyString(), anyLong(), anyLong());
    }

    @Test
    void 内置项开关_无管理权限403_有权限则写租户配置() {
        assertEquals(Integer.valueOf(403), assertThrows(ServiceException.class,
            () -> svc().toggleBuiltin(TENANT, USER, true, false)).getCode(),
            "普通成员不许开关内置项");
        verify(tenantConfig, never()).setDigestEnabled(anyString(), org.mockito.ArgumentMatchers.anyBoolean());

        Automation builtin = auto(9L, 0L, "1");
        when(automationMapper.selectBuiltin(TENANT)).thenReturn(builtin);
        when(tenantConfig.digestEnabled(TENANT)).thenReturn(true);
        when(runMapper.countByAutomation(TENANT, 9L)).thenReturn(0);
        when(runMapper.countSuccess(TENANT, 9L)).thenReturn(0);
        when(automationMapper.selectOne(TENANT, 9L)).thenReturn(builtin);

        svc().toggleBuiltin(TENANT, USER, true, true);
        // 真相在租户配置：只改表里的 enabled 列会让列表页显示与实际不符
        verify(tenantConfig).setDigestEnabled(TENANT, true);
    }

    // ══════════════════════════════════════════════════════════════════
    // ② 5 条上限与串行锁
    // ══════════════════════════════════════════════════════════════════

    @Test
    void 新建_达上限400_且先取锁再计数() {
        when(automationMapper.countOwn(TENANT, USER)).thenReturn(AutomationSchedule.MAX_PER_USER);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> svc().create(TENANT, USER, bo("标题", "提示", "DAILY", "09:00", null)));
        assertEquals(Integer.valueOf(400), ex.getCode());
        assertTrue(ex.getMessage().contains(String.valueOf(AutomationSchedule.MAX_PER_USER)),
            "文案要说出上限数字，否则用户不知道删几条");

        // 顺序：先锁再数。反了=check-then-act，并发能超卖
        var order = org.mockito.Mockito.inOrder(automationMapper);
        order.verify(automationMapper).lockUserSlot(TENANT, USER);
        order.verify(automationMapper).countOwn(TENANT, USER);
        verify(automationMapper, never()).insertOne(any(Automation.class));
    }

    @Test
    void 新建_落库字段齐全_默认开启且算好下次执行() {
        when(automationMapper.countOwn(TENANT, USER)).thenReturn(0);

        svc().create(TENANT, USER, bo("  标题  ", "  提示  ", "DAILY", "09:00", null));

        ArgumentCaptor<Automation> cap = ArgumentCaptor.forClass(Automation.class);
        verify(automationMapper).insertOne(cap.capture());
        Automation a = cap.getValue();
        assertEquals("标题", a.getTitle(), "标题要 trim（带空格的标题会让列表对齐错乱）");
        assertEquals("提示", a.getPrompt());
        assertEquals("1", a.getEnabled(), "新建默认开启");
        assertEquals("0", a.getBuiltin(), "用户建的一律不是内置项");
        assertEquals("Asia/Shanghai", a.getTimeZone(), "时区铁律：固定东八区");
        assertEquals(USER, a.getCreateBy());
        assertTrue(a.getNextRunAt() != null && a.getNextRunAt().isAfter(LocalDateTime.now()),
            "下次执行必须算在将来，实际：" + a.getNextRunAt());
    }

    @Test
    void 新建_每周不选天400() {
        when(automationMapper.countOwn(TENANT, USER)).thenReturn(0);
        assertEquals(Integer.valueOf(400), assertThrows(ServiceException.class,
            () -> svc().create(TENANT, USER, bo("标题", "提示", "WEEKLY", "09:00", null))).getCode(),
            "每周却不选天=永远不会跑，必须挡");
        verify(automationMapper, never()).insertOne(any(Automation.class));
    }

    @Test
    void 新建_校验失败不落库() {
        when(automationMapper.countOwn(TENANT, USER)).thenReturn(0);
        // 标题空 / 标题超长 / 提示空 / 时间格式错 四类
        for (AutomationBo bad : List.of(
            bo("", "提示", "DAILY", "09:00", null),
            bo("x".repeat(61), "提示", "DAILY", "09:00", null),
            bo("标题", "", "DAILY", "09:00", null),
            bo("标题", "提示", "DAILY", "9:00", null))) {
            assertEquals(Integer.valueOf(400), assertThrows(ServiceException.class,
                () -> svc().create(TENANT, USER, bad)).getCode(), "非法入参必须 400");
        }
        verify(automationMapper, never()).insertOne(any(Automation.class));
    }

    @Test
    void 删除_异主404_不存在不报错外泄() {
        when(automationMapper.selectOne(TENANT, 1L)).thenReturn(auto(1L, OTHER, "0"));
        // 异主走 requireVisible → 403（不是 404，与 detail 同口径）
        assertEquals(Integer.valueOf(403), assertThrows(ServiceException.class,
            () -> svc().delete(TENANT, USER, 1L)).getCode());
        verify(automationMapper, never()).softDeleteOwn(anyString(), anyLong(), anyLong());
    }

    // ══════════════════════════════════════════════════════════════════
    // ③ 立即运行：并发去重 + 排队满如实报错
    // ══════════════════════════════════════════════════════════════════

    @Test
    void 立即运行_已在跑409_不插运行记录() {
        when(automationMapper.selectOne(TENANT, 1L)).thenReturn(auto(1L, USER, "0"));
        when(runMapper.countRunning(TENANT, 1L)).thenReturn(1);

        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
            () -> svc().runNow(TENANT, USER, 1L)).getCode(),
            "同一自动化不许并发跑（并发=双扣费+双通知）");
        verify(runMapper, never()).insertIgnore(any(AutomationRun.class));
    }

    @Test
    void 立即运行_唯一索引挡下也算在跑409() {
        when(automationMapper.selectOne(TENANT, 1L)).thenReturn(auto(1L, USER, "0"));
        when(runMapper.countRunning(TENANT, 1L)).thenReturn(0);
        when(runMapper.insertIgnore(any(AutomationRun.class))).thenReturn(0);

        // countRunning 与 insertIgnore 之间仍可能有竞态；唯一索引是最后一道门
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
            () -> svc().runNow(TENANT, USER, 1L)).getCode());
        verify(runner, never()).submit(anyString(), anyLong());
    }

    @Test
    void 立即运行_排队满409而不是假装成功() {
        when(automationMapper.selectOne(TENANT, 1L)).thenReturn(auto(1L, USER, "0"));
        when(runMapper.countRunning(TENANT, 1L)).thenReturn(0);
        when(runMapper.insertIgnore(any(AutomationRun.class))).thenReturn(1);
        when(runner.submit(eq(TENANT), anyLong())).thenReturn(false);

        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
            () -> svc().runNow(TENANT, USER, 1L)).getCode(),
            "队列满必须如实报错——假成功会让用户等一个永远不会来的结果");
    }

    @Test
    void 立即运行_成功则先标记RUNNING再提交执行() {
        when(automationMapper.selectOne(TENANT, 1L)).thenReturn(auto(1L, USER, "0"));
        when(runMapper.countRunning(TENANT, 1L)).thenReturn(0);
        when(runMapper.insertIgnore(any(AutomationRun.class))).thenReturn(1);
        when(runner.submit(eq(TENANT), anyLong())).thenReturn(true);

        Long runId = svc().runNow(TENANT, USER, 1L);
        assertTrue(runId != null && runId > 0, "必须返回新运行 id");

        var order = org.mockito.Mockito.inOrder(runMapper, automationMapper, runner);
        order.verify(runMapper).insertIgnore(any(AutomationRun.class));
        order.verify(automationMapper).markRun(eq(TENANT), eq(1L), eq("RUNNING"), eq(runId), any());
        order.verify(runner).submit(TENANT, runId);
    }

    // ══════════════════════════════════════════════════════════════════
    // ④ 认领：补跑窗口两侧边界
    // ══════════════════════════════════════════════════════════════════

    @Test
    void 认领_超过6小时补跑窗口记MISSED且不执行() {
        LocalDateTime now = LocalDateTime.now(AssistantAutomationService.ZONE);
        Automation a = auto(1L, USER, "0");
        // 计划时刻在 6 小时窗口之外
        a.setNextRunAt(now.minus(AutomationSchedule.CATCH_UP_WINDOW).minusMinutes(30));
        when(automationMapper.claimDue(any(), eq(50))).thenReturn(List.of(a));
        when(runMapper.insertIgnore(any(AutomationRun.class))).thenReturn(1);

        assertTrue(svc().claimDue().isEmpty(),
            "错过补跑窗口的不许执行（积压全补跑=瞬间打爆模型额度）");

        ArgumentCaptor<AutomationRun> cap = ArgumentCaptor.forClass(AutomationRun.class);
        verify(runMapper).insertIgnore(cap.capture());
        assertEquals("MISSED", cap.getValue().getStatus(), "要记 MISSED 让用户知道那一次没跑");
        assertNull(cap.getValue().getThreadId(), "MISSED 没有执行过程，不该有会话线程");
        verify(runMapper).finishMissed(cap.getValue().getId());
    }

    @Test
    void 认领_窗口内正常认领并把下次推到将来() {
        LocalDateTime now = LocalDateTime.now(AssistantAutomationService.ZONE);
        Automation a = auto(1L, USER, "0");
        a.setNextRunAt(now.minus(Duration.ofMinutes(10)));   // 在 6h 窗口内
        when(automationMapper.claimDue(any(), eq(50))).thenReturn(List.of(a));
        when(runMapper.insertIgnore(any(AutomationRun.class))).thenReturn(1);

        List<AssistantAutomationService.Claimed> claimed = svc().claimDue();
        assertEquals(1, claimed.size(), "窗口内必须认领");
        assertEquals(TENANT, claimed.get(0).tenantId());

        // 先推进 next_run_at 再执行（open-webui 同款）：顺序反了会重复认领同一时刻
        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(automationMapper).advanceNextRun(eq(TENANT), eq(1L), next.capture());
        assertTrue(next.getValue().isAfter(now), "下次执行必须推到将来，实际：" + next.getValue());
        verify(runMapper).insertIgnore(any(AutomationRun.class));
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑤ 悬挂收口
    // ══════════════════════════════════════════════════════════════════

    @Test
    void 悬挂收口_迁移成功才计失败并通知_已终态行跳过() {
        AutomationRun stale = new AutomationRun();
        stale.setId(11L);
        stale.setTenantId(TENANT);
        stale.setAutomationId(1L);
        stale.setScheduledFor(LocalDateTime.now().minusHours(1));
        when(runMapper.selectStale(any())).thenReturn(List.of(stale));
        when(runMapper.failStale(eq(11L), any(), anyString())).thenReturn(1);
        when(failurePolicy.record(TENANT, 1L)).thenReturn(1);
        Automation a = auto(1L, USER, "0");
        when(automationMapper.selectOne(TENANT, 1L)).thenReturn(a);

        assertEquals(1, svc().sweepStale());
        verify(automationMapper).markRun(eq(TENANT), eq(1L), eq("FAILED"), eq(11L), any());
        verify(failurePolicy).record(TENANT, 1L);
        // 题5 甲承诺「结果一定进通知」——超时收口也必须发，否则服务重启丢的那次用户完全无感
        verify(notifier).failure(eq(a), eq(11L), anyString(), eq(false));
    }

    @Test
    void 悬挂收口_失败达阈值通知里带自动停用说明() {
        AutomationRun stale = new AutomationRun();
        stale.setId(12L);
        stale.setTenantId(TENANT);
        stale.setAutomationId(1L);
        when(runMapper.selectStale(any())).thenReturn(List.of(stale));
        when(runMapper.failStale(eq(12L), any(), anyString())).thenReturn(1);
        when(failurePolicy.record(TENANT, 1L)).thenReturn(AutomationSchedule.FAILURE_LIMIT);
        Automation a = auto(1L, USER, "0");
        when(automationMapper.selectOne(TENANT, 1L)).thenReturn(a);

        svc().sweepStale();
        verify(notifier).failure(eq(a), eq(12L), anyString(), eq(true));
    }

    @Test
    void 悬挂收口_已删自动化不发通知() {
        AutomationRun stale = new AutomationRun();
        stale.setId(13L);
        stale.setTenantId(TENANT);
        stale.setAutomationId(1L);
        when(runMapper.selectStale(any())).thenReturn(List.of(stale));
        when(runMapper.failStale(eq(13L), any(), anyString())).thenReturn(1);
        when(failurePolicy.record(TENANT, 1L)).thenReturn(1);
        Automation deleted = auto(1L, USER, "0");
        deleted.setDelFlag("1");
        when(automationMapper.selectOne(TENANT, 1L)).thenReturn(deleted);

        svc().sweepStale();
        verify(notifier, never()).failure(any(), anyLong(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑥ 列表筛选与状态派生
    // ══════════════════════════════════════════════════════════════════

    @Test
    void 列表_非法筛选词退化为ALL() {
        when(automationMapper.selectBuiltin(TENANT)).thenReturn(auto(9L, 0L, "1"));
        when(automationMapper.selectVisible(TENANT, USER, "ALL")).thenReturn(List.of());

        svc().list(TENANT, USER, "乱写的", false);
        verify(automationMapper).selectVisible(TENANT, USER, "ALL");

        svc().list(TENANT, USER, null, false);
        verify(automationMapper, org.mockito.Mockito.times(2)).selectVisible(TENANT, USER, "ALL");
    }

    @Test
    void 列表_内置项状态以租户配置为准() {
        Automation builtin = auto(9L, 0L, "1");
        builtin.setEnabled("0");     // 表里是关的
        when(automationMapper.selectBuiltin(TENANT)).thenReturn(builtin);
        when(automationMapper.selectVisible(TENANT, USER, "ALL")).thenReturn(List.of(builtin));
        when(tenantConfig.digestEnabled(TENANT)).thenReturn(true);  // 配置里是开的
        when(runMapper.countByAutomation(TENANT, 9L)).thenReturn(0);
        when(runMapper.countSuccess(TENANT, 9L)).thenReturn(0);

        AutomationVo vo = svc().list(TENANT, USER, "ALL", false).get(0);
        assertEquals("1", vo.getEnabled(),
            "内置项状态必须读配置（表里的 enabled 列只是占位，按列显示=界面与真相不符）");
        assertFalse(vo.isCanEdit(), "内置项不可编辑");
        assertFalse(vo.isCanToggle(), "无管理权限时不可开关");
    }

    @Test
    void 列表_连续失败次数空值补0() {
        Automation a = auto(1L, USER, "0");
        a.setConsecutiveFailures(null);
        when(automationMapper.selectBuiltin(TENANT)).thenReturn(auto(9L, 0L, "1"));
        when(automationMapper.selectVisible(TENANT, USER, "ALL")).thenReturn(List.of(a));
        when(runMapper.countByAutomation(TENANT, 1L)).thenReturn(0);
        when(runMapper.countSuccess(TENANT, 1L)).thenReturn(0);

        assertEquals(0, svc().list(TENANT, USER, "ALL", false).get(0).getConsecutiveFailures(),
            "新建实体没默认值时透传 null 会让前端显示「失败 null 次」");
    }

    @Test
    void 预览_非法日程400_合法返回5次() {
        assertEquals(Integer.valueOf(400), assertThrows(ServiceException.class,
            () -> svc().preview("DAILY", "25:00", null)).getCode(), "非法时间必须 400");
        assertEquals(5, svc().preview("DAILY", "09:00", null).size(), "默认给未来 5 次");
    }

    // ══════════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════════

    private static Automation auto(Long id, Long userId, String builtin) {
        Automation a = new Automation();
        a.setId(id);
        a.setTenantId(TENANT);
        a.setUserId(userId);
        a.setTitle("自动化" + id);
        a.setPrompt("提示" + id);
        a.setScheduleType("DAILY");
        a.setScheduleTime("09:00");
        a.setEnabled("1");
        a.setBuiltin(builtin);
        a.setTimeZone("Asia/Shanghai");
        a.setDelFlag("0");
        return a;
    }

    private static AutomationBo bo(String title, String prompt, String type, String time, String weekdays) {
        AutomationBo b = new AutomationBo();
        b.setTitle(title);
        b.setPrompt(prompt);
        b.setScheduleType(type);
        b.setScheduleTime(time);
        b.setWeekdays(weekdays);
        return b;
    }
}
