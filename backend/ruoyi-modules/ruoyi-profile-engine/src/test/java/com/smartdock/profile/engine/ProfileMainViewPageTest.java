package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartdock.profile.mapper.ProfileScoreSnapshotMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import com.smartdock.profile.query.ProfileMainViewRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * 画像主视图服务层契约测试(单源重构 M1 B 片·题0-甲):
 * 身份类型路由(user→sys_user 联表, 其余→主体登记聚合)/分页封顶/分数列同闸。
 * SQL 本体属活体验证范畴(批末三审对质), 此处钉住服务层行为不被无声改掉。
 *
 * @author smartdock
 */
@Tag("local")
class ProfileMainViewPageTest {

    private ProfileSubjectMapper subjectMapper;
    private ProfilePiiService piiService;
    private ProfileQueryService service;

    @BeforeAll
    static void seedPureUnitTestInfrastructure() {
        // TenantHelper.setDynamic 要 tenant.enable 上下文才生效(无 Spring 环境下被禁用),
        // JsonUtils 静态初始化要 ObjectMapper——同 ProfileScoreUnconfiguredGuardTest 配方
        org.springframework.context.ConfigurableApplicationContext ctx =
            Mockito.mock(org.springframework.context.ConfigurableApplicationContext.class);
        org.springframework.core.env.ConfigurableEnvironment env =
            Mockito.mock(org.springframework.core.env.ConfigurableEnvironment.class);
        Mockito.when(env.getProperty("tenant.enable")).thenReturn("true");
        Mockito.when(ctx.getEnvironment()).thenReturn(env);
        Mockito.when(ctx.getBean(org.springframework.core.env.Environment.class)).thenReturn(env);
        Mockito.when(ctx.getBean(ObjectMapper.class)).thenReturn(new ObjectMapper());
        new SpringUtils().setApplicationContext(ctx);
    }

    @BeforeEach
    void buildService() {
        subjectMapper = Mockito.mock(ProfileSubjectMapper.class);
        piiService = Mockito.mock(ProfilePiiService.class);
        Mockito.when(piiService.canViewScore()).thenReturn(true);
        service = new ProfileQueryService(subjectMapper,
            Mockito.mock(ProfileScoreSnapshotMapper.class),
            Mockito.mock(ProfileScoreEngine.class),
            Mockito.mock(ProfileDimensionService.class),
            Mockito.mock(ProfileRuleService.class),
            piiService);
        TenantHelper.setDynamic("000000");
    }

    @AfterEach
    void unbindTenant() {
        TenantHelper.clearDynamic();
    }

    @Test
    void userViewRoutesToSysUserJoinAndBlankDefaultsToUser() {
        stubUserPage(row("1", "88"));
        TableDataInfo<ProfileMainViewRow> out = service.mainViewPage(null, null, new PageQuery(10, 1));
        assertEquals(1, out.getRows().size());

        // 空身份类型归一为 user → 走 sys_user 联表口
        Mockito.verify(subjectMapper).selectUserMainPage(any(), eq("000000"), isNull());
        Mockito.verify(subjectMapper, Mockito.never()).selectSubjectMainPage(any(), any(), any(), any());
    }

    @Test
    void nonUserTypeRoutesToSubjectAggregation() {
        Mockito.when(subjectMapper.selectSubjectMainPage(any(), any(), any(), any()))
            .thenAnswer(inv -> pageWith(inv.getArgument(0), row("m-9", "62")));
        service.mainViewPage("member", " 张三 ", new PageQuery(10, 1));

        Mockito.verify(subjectMapper).selectSubjectMainPage(any(), eq("000000"), eq("member"), eq("张三"));
        Mockito.verify(subjectMapper, Mockito.never()).selectUserMainPage(any(), any(), any());
    }

    @Test
    void pageSizeIsCappedAt200() {
        stubUserPage(row("1", null));
        PageQuery pageQuery = new PageQuery(99999, 1);
        service.mainViewPage("user", null, pageQuery);

        ArgumentCaptor<IPage<ProfileMainViewRow>> captor = ArgumentCaptor.forClass(IPage.class);
        Mockito.verify(subjectMapper).selectUserMainPage(captor.capture(), eq("000000"), isNull());
        assertEquals(200, captor.getValue().getSize());
    }

    @Test
    void scoreColumnIsNulledWithoutColumnPermission() {
        Mockito.when(piiService.canViewScore()).thenReturn(false);
        stubUserPage(row("1", "88"));

        TableDataInfo<ProfileMainViewRow> out = service.mainViewPage("user", null, new PageQuery(10, 1));

        // 与 subjectPage 同闸: 无列级分数权限总分置空; 占位标记与待复核不敏感保留
        assertNull(out.getRows().get(0).getTotalScore());
        assertTrue(Boolean.TRUE.equals(out.getRows().get(0).getNoDimensions()));
        assertTrue(Boolean.TRUE.equals(out.getRows().get(0).getPendingReview()));
    }

    @Test
    void rankingBlankSubjectTypeDefaultsToUser() {
        // B 片三审回修: 排行空白类型归一 user——此前传 null 不过滤, user/member 分套后混排成不可比名次
        Mockito.when(subjectMapper.selectRanking(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.isNull(), Mockito.anyInt()))
            .thenReturn(java.util.Collections.emptyList());
        service.ranking("dcs", " ", null, 10);
        Mockito.verify(subjectMapper).selectRanking(Mockito.eq("000000"), Mockito.eq("dcs"),
            Mockito.eq("user"), Mockito.isNull(), Mockito.eq(10));
    }

    private void stubUserPage(ProfileMainViewRow row) {
        Mockito.when(subjectMapper.selectUserMainPage(any(), any(), any()))
            .thenAnswer(inv -> pageWith(inv.getArgument(0), row));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private IPage<ProfileMainViewRow> pageWith(IPage page, ProfileMainViewRow row) {
        page.setRecords(List.of(row));
        page.setTotal(1);
        return page;
    }

    private ProfileMainViewRow row(String subjectId, String score) {
        ProfileMainViewRow row = new ProfileMainViewRow();
        row.setSubjectType("user");
        row.setSubjectId(subjectId);
        row.setEventCount(0L);
        row.setNoDimensions(Boolean.TRUE);
        row.setPendingReview(Boolean.TRUE);
        row.setTotalScore(score == null ? null : new BigDecimal(score));
        return row;
    }
}
