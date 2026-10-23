package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.DcsAiUsage;
import com.smartdock.domain.vo.DcsAiUsageVo;

/**
 * AI 调用消耗台账 Mapper。
 *
 * @author smartdock
 */
public interface DcsAiUsageMapper extends BaseMapperPlus<DcsAiUsage, DcsAiUsageVo> {

    /**
     * Q3-A（2026-10-23 拍板）：租户最近一次成功调用过的配置。
     * 默认模型缺失/关闭时，用户侧沿用上次选择，不再给空下拉。
     * 排除 PATROL 自证行，只认真实业务调用；按 create_time 倒序取首行
     * （走 create_time 索引，不做全历史 GROUP BY）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT config_id
          FROM dcs_ai_usage
         WHERE tenant_id = #{tenantId}
           AND config_id IS NOT NULL
           AND success = '1'
           AND caller <> 'PATROL'
         ORDER BY create_time DESC
         LIMIT 1
        """)
    Long latestUsedConfigId(@Param("tenantId") String tenantId);
}
