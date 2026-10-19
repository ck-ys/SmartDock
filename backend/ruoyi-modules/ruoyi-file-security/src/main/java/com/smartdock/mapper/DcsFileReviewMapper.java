package com.smartdock.mapper;

import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.DcsFileReview;
import com.smartdock.domain.vo.DcsFileReviewVo;

import java.util.List;

/**
 * 文件审核单 Mapper（12b）
 *
 * <p>CAS 惯例照 DcsReviewTaskMapper.markPassed：WHERE 带 status='PENDING' 条件更新，
 * 影响行数=0 即"已被处理"（多审核员并发，全可见+原子抢占模式）。本表无 version 列，
 * CAS 只押 status（一 oss 一单 + 终态语义下足够）。</p>
 *
 * @author smartdock
 */
public interface DcsFileReviewMapper extends BaseMapperPlus<DcsFileReview, DcsFileReviewVo> {

    /** 按 ossId 查唯一审核单（读拦截热路径，走 UNIQUE(oss_id) 索引） */
    DcsFileReview selectByOssId(@Param("ossId") Long ossId);

    /** 按 ossId 批量查审核单（listByIds 占位标记用，一次 IN 免 N+1） */
    List<DcsFileReview> selectByOssIds(@Param("ossIds") List<Long> ossIds);

    /** CAS 通过：PENDING→APPROVED，0 行=该单已被处理 */
    int markApproved(@Param("tenantId") String tenantId, @Param("reviewId") Long reviewId,
                     @Param("reviewerId") Long reviewerId, @Param("reviewerName") String reviewerName);

    /** CAS 驳回：PENDING→REJECTED（必填理由），0 行=该单已被处理 */
    int markRejected(@Param("tenantId") String tenantId, @Param("reviewId") Long reviewId,
                     @Param("reviewerId") Long reviewerId, @Param("reviewerName") String reviewerName,
                     @Param("reason") String reason);

    /** 删 oss 级联闭单：PENDING 单批量置 REJECTED（reason='源文件已删除'），防幽灵待审单 */
    int closePendingByOssIds(@Param("ossIds") List<Long> ossIds, @Param("reason") String reason,
                             @Param("operatorId") Long operatorId);

    /** 待审数（徽标/设置页统计） */
    long countPending(@Param("tenantId") String tenantId);
}
