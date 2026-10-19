package com.smartdock.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.smartdock.risk.domain.DcsWordFpFeedback;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 机检误报按词反馈 Mapper（风控2.0 U03）。
 *
 * @author smartdock
 */
@Mapper
public interface DcsWordFpFeedbackMapper extends BaseMapper<DcsWordFpFeedback> {

    /**
     * 窗口内按词聚合误报数（大盘 TOP 词表误报率分子）。
     *
     * @param startTime 窗口起点
     * @return Map: word=词面, fpCnt=误报次数
     */
    List<Map<String, Object>> fpCountByWord(@Param("startTime") Date startTime);

    /**
     * 误报反馈总记录数（批4 2026-10-18：大盘 falseFeedbackCount 的新统计源，
     * 替代退役的旧明细表口径；统计范围=当前租户（租户拦截器自动拼接 WHERE tenant_id）。
     */
    @Select("SELECT COUNT(*) FROM dcs_word_fp_feedback")
    long countAll();
}
