package com.smartdock.mapper;

import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.DcsFileReviewConfig;
import com.smartdock.domain.vo.DcsFileReviewConfigVo;

/**
 * 租户级文件审核配置 Mapper（12b）
 *
 * <p>保存走 upsert（ON CONFLICT (tenant_id) DO UPDATE）——两管理员并发首存不再 UNIQUE 冲突（合同 P1-3）。</p>
 *
 * @author smartdock
 */
public interface DcsFileReviewConfigMapper extends BaseMapperPlus<DcsFileReviewConfig, DcsFileReviewConfigVo> {

    /**
     * 幂等 upsert：每租户一行，存在则更新开关、类别与 file-guard 检测三列（批三）。
     *
     * @return 影响行数（upsert 恒为 1）
     */
    int upsertConfig(@Param("configId") Long configId, @Param("tenantId") String tenantId,
                     @Param("enabled") String enabled, @Param("categories") String categories,
                     @Param("operatorId") Long operatorId,
                     @Param("guardMode") String guardMode, @Param("guardWhitelist") String guardWhitelist,
                     @Param("guardDeepScanMaxMb") Integer guardDeepScanMaxMb);
}
