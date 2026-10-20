package com.smartdock.profile.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.profile.domain.ProfileReachBatch;
import com.smartdock.profile.domain.ProfileReachItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 触达审计 Mapper（批9 Q3-A）。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileReachBatchMapper extends BaseMapperPlus<ProfileReachBatch, ProfileReachBatch> {

    @Insert("""
        INSERT INTO profile_reach_item
            (item_id, tenant_id, reach_id, subject_type, subject_id, user_id, user_name,
             dedup_key, status, error_msg, sent_time, create_time)
        VALUES
            (#{i.itemId}, #{i.tenantId}, #{i.reachId}, #{i.subjectType}, #{i.subjectId}, #{i.userId},
             #{i.userName}, #{i.dedupKey}, #{i.status}, #{i.errorMsg}, #{i.sentTime}, CURRENT_TIMESTAMP)
        """)
    int insertItem(@Param("i") ProfileReachItem item);

    @Select("""
        SELECT * FROM profile_reach_item
        WHERE reach_id = #{reachId}
        ORDER BY status DESC, item_id
        LIMIT #{limit}
        """)
    List<ProfileReachItem> selectItems(@Param("reachId") Long reachId, @Param("limit") int limit);

    @Select("""
        SELECT * FROM profile_reach_item
        WHERE reach_id = #{reachId} AND status = 'FAILED'
        ORDER BY item_id
        """)
    List<ProfileReachItem> selectFailedItems(@Param("reachId") Long reachId);

    @Select("""
        <script>
        SELECT * FROM profile_reach_batch
        WHERE tenant_id = #{tenantId} AND del_flag = '0'
        <if test="segmentId != null">
          AND segment_id = #{segmentId}
        </if>
        <if test="channel != null and channel != ''">
          AND channel = #{channel}
        </if>
        ORDER BY create_time DESC
        </script>
        """)
    IPage<ProfileReachBatch> selectBatchPageScript(IPage<ProfileReachBatch> page,
                                                   @Param("tenantId") String tenantId,
                                                   @Param("segmentId") Long segmentId,
                                                   @Param("channel") String channel);
}
