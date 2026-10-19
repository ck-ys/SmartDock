package com.smartdock.risk.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import com.smartdock.risk.domain.DcsProfileReview;
import com.smartdock.risk.domain.vo.ProfileReviewAggregateVo;
import com.smartdock.risk.mapper.DcsProfileReviewMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画像复核服务单测(2026-08-23 入队语义改造 + 聚合/按人复核配套)。
 *
 * <p>覆盖: ensurePending 跳过分支与 upsert 字段、countPending 用户口径委托、
 * pageAggregate 内存分页切片、reviewAllForUser 全量更新/空队列/非法结论。</p>
 */
@Tag("dev")
class ProfileRiskReviewServiceUnitTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "profile-review-service"),
            DcsProfileReview.class);
    }

    @Test
    void ensurePendingSkipsWhenLevelNotHighOrNullIds() {
        DcsProfileReviewMapper mapper = mock(DcsProfileReviewMapper.class);
        ProfileRiskReviewService service = new ProfileRiskReviewService(mapper, new RiskActionPolicy());

        service.ensurePending("000000", 1L, 2L, "v1", "MEDIUM", "中风险");
        service.ensurePending("000000", null, 2L, "v1", "HIGH", "高风险");
        service.ensurePending("000000", 1L, null, "v1", "HIGH", "高风险");

        verify(mapper, never()).upsertPending(any());
    }

    @Test
    void ensurePendingUpsertsSinglePendingRowPerUser() {
        DcsProfileReviewMapper mapper = mock(DcsProfileReviewMapper.class);
        ProfileRiskReviewService service = new ProfileRiskReviewService(mapper, new RiskActionPolicy());
        when(mapper.upsertPending(any())).thenReturn(1);

        service.ensurePending("000000", 42L, 100L, "v20260823", "HIGH", "  ");

        ArgumentCaptor<DcsProfileReview> captor = ArgumentCaptor.forClass(DcsProfileReview.class);
        verify(mapper).upsertPending(captor.capture());
        DcsProfileReview saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getScoreId()).isEqualTo(100L);
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getReviewId()).isNotNull();
        // 触发原因为空白时回落默认文案
        assertThat(saved.getTriggerReason()).isEqualTo("画像分达到高风险区间");
    }

    @Test
    void countPendingDelegatesToDistinctUserCount() {
        DcsProfileReviewMapper mapper = mock(DcsProfileReviewMapper.class);
        ProfileRiskReviewService service = new ProfileRiskReviewService(mapper, new RiskActionPolicy());
        when(mapper.countPendingUsers("000000")).thenReturn(7L);

        assertThat(service.countPending("000000")).isEqualTo(7L);
    }

    @Test
    void pageAggregateSlicesFullListByPageQuery() {
        DcsProfileReviewMapper mapper = mock(DcsProfileReviewMapper.class);
        ProfileRiskReviewService service = new ProfileRiskReviewService(mapper, new RiskActionPolicy());
        List<ProfileReviewAggregateVo> all = IntStream.rangeClosed(1, 5)
            .mapToObj(i -> new ProfileReviewAggregateVo()).toList();
        when(mapper.selectPendingAggregate("000000")).thenReturn(all);

        PageQuery query = new PageQuery(2, 2);
        Page<ProfileReviewAggregateVo> page = service.pageAggregate("000000", null, query);

        assertThat(page.getTotal()).isEqualTo(5);
        assertThat(page.getRecords()).hasSize(2);
        assertThat(page.getCurrent()).isEqualTo(2);
    }

    @Test
    void pageAggregateToleratesMissingPaginationParams() {
        // 三审修正P1-1回归: GET 不传分页参数时 pageNum/pageSize 为 null, 不应 NPE
        DcsProfileReviewMapper mapper = mock(DcsProfileReviewMapper.class);
        ProfileRiskReviewService service = new ProfileRiskReviewService(mapper, new RiskActionPolicy());
        List<ProfileReviewAggregateVo> all = IntStream.rangeClosed(1, 3)
            .mapToObj(i -> new ProfileReviewAggregateVo()).toList();
        when(mapper.selectPendingAggregate("000000")).thenReturn(all);

        PageQuery query = new PageQuery(null, null);
        Page<ProfileReviewAggregateVo> page = service.pageAggregate("000000", null, query);

        assertThat(page.getRecords()).hasSize(3);
        assertThat(page.getCurrent()).isEqualTo(1);
    }

    @Test
    void reviewAllForUserRejectsIllegalConclusionBeforeTouchingDb() {
        DcsProfileReviewMapper mapper = mock(DcsProfileReviewMapper.class);
        ProfileRiskReviewService service = new ProfileRiskReviewService(mapper, new RiskActionPolicy());

        assertThatThrownBy(() -> service.reviewAllForUser("000000", 1L, 9L, "APPROVED", "理由"))
            .isInstanceOf(ServiceException.class);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void reviewAllForUserThrowsWhenNoPendingRows() {
        DcsProfileReviewMapper mapper = mock(DcsProfileReviewMapper.class);
        ProfileRiskReviewService service = new ProfileRiskReviewService(mapper, new RiskActionPolicy());
        when(mapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reviewAllForUser("000000", 1L, 9L, "DISMISSED", "批量驳回"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("没有待复核画像");
    }

    @Test
    void reviewAllForUserReturnsAffectedRows() {
        DcsProfileReviewMapper mapper = mock(DcsProfileReviewMapper.class);
        ProfileRiskReviewService service = new ProfileRiskReviewService(mapper, new RiskActionPolicy());
        when(mapper.update(any(), any())).thenReturn(3);

        long rows = service.reviewAllForUser("000000", 1L, 9L, "confirmed", "确认风险");

        assertThat(rows).isEqualTo(3);
        verify(mapper).update(any(), any());
    }
}
