package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileSegmentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;
import java.util.Map;

/**
 * 分群运行批次 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileSegmentRunMapper extends BaseMapperPlus<ProfileSegmentRun, ProfileSegmentRun> {

    /**
     * 每个分群最近一条 run 的状态(批4A 徽标原料)。不能挂 last_run_id: 超限 SKIPPED
     * 刻意不更新分群统计, 徽标要的是「最近一次发生了什么」——含跳过信号。
     * DISTINCT ON 随 create_time DESC 取最新, 兼查当前租户。
     */
    @Select("""
        <script>
        SELECT DISTINCT ON (segment_id) segment_id, status
        FROM profile_segment_run
        WHERE tenant_id = #{tenantId}
          AND del_flag = '0'
          AND segment_id IN
          <foreach collection="segmentIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY segment_id, create_time DESC, run_id DESC
        </script>
        """)
    List<Map<String, Object>> selectLatestStatus(@Param("tenantId") String tenantId,
                                                 @Param("segmentIds") List<Long> segmentIds);
}
