package com.smartdock.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import com.smartdock.domain.bo.DcsFileReviewQueryBo;
import com.smartdock.domain.vo.DcsFileReviewVo;

import java.util.List;

/**
 * 文件审核单服务（12b 文件审核中心 P1）
 *
 * @author smartdock
 */
public interface IDcsFileReviewService {

    /**
     * 分页查询审核单（待审/已审页签共用，status 必传）
     */
    TableDataInfo<DcsFileReviewVo> queryList(DcsFileReviewQueryBo bo, PageQuery pageQuery);

    /**
     * 待审单数（侧边栏徽标与页签计数数据源）
     */
    long pendingCount();

    /**
     * 单条详情（预览用）
     */
    DcsFileReviewVo queryById(Long reviewId);

    /**
     * 批量通过（Q5-A 一期）：逐条 CAS 容错——已不存在的/已被他人处理的进 conflict，不炸整批
     *
     * @param reviewIds 审核单ID列表
     * @return 成功/冲突两组单ID
     */
    ApproveResult approve(List<Long> reviewIds);

    /**
     * 驳回单条：CAS + 同事务驳回通知（Q6-A 理由必填）
     *
     * @param reviewId 审核单ID
     * @param reason   驳回理由
     */
    void reject(Long reviewId, String reason);

    /**
     * 批量通过结果：success=本次通过的单；conflict=已不存在或已被他人处理（0 行 CAS）的单
     */
    record ApproveResult(List<Long> success, List<Long> conflict) {
    }
}
