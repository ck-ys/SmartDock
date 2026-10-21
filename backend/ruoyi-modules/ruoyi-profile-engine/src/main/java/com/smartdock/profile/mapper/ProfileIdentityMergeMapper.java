package com.smartdock.profile.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.smartdock.profile.identity.ProfileIdentityMerge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 身份合并审计 Mapper(批10 Q6 补 unmerge 面)。手写 SQL 自带租户条件。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileIdentityMergeMapper extends BaseMapperPlus<ProfileIdentityMerge, ProfileIdentityMerge> {

    /** 合并记录列表(图谱管理页; 可按主体过滤任一侧)。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        <script>
        SELECT * FROM profile_identity_merge
        WHERE tenant_id = #{tenantId}
          <if test="source != null and source != ''">AND source = #{source}</if>
          <if test="subject != null and subject != ''">
            AND (from_subject_id = #{subject} OR to_subject_id = #{subject})
          </if>
        ORDER BY occurred_at DESC
        LIMIT 100
        </script>
        """)
    List<ProfileIdentityMerge> selectRecent(@Param("tenantId") String tenantId,
                                            @Param("source") String source,
                                            @Param("subject") String subject);

    /**
     * 拆分-事件回改: 合并后错误归并到 to 的"匿名来源"事件改回 from。
     * 匿名来源的判据=identifiers 无 user_id(登录事件的 user_id 是登录主体自己的,
     * 不能跟着拆走), 且匿名 ID 正是被并入的 from 主体。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE profile_event
        SET subject_id = #{m.fromSubjectId}
        WHERE tenant_id = #{m.tenantId} AND source = #{m.source}
          AND subject_id = #{m.toSubjectId}
          AND anonymous_id = #{m.fromSubjectId}
          AND identifiers ->> 'user_id' IS NULL
          AND occurred_time >= #{m.occurredAt}
        """)
    int reassignEvents(@Param("m") ProfileIdentityMerge m);

    /** 拆分-链接回挂: 被并入的匿名标识当前挂在 to 名下, 拆回 from。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE profile_identity_link
        SET subject_id = #{m.fromSubjectId}, update_time = CURRENT_TIMESTAMP
        WHERE tenant_id = #{m.tenantId} AND source = #{m.source}
          AND identifier_type = 'anonymous_id'
          AND identifier_value = #{m.fromSubjectId}
          AND subject_id = #{m.toSubjectId}
        """)
    int repointLink(@Param("m") ProfileIdentityMerge m);

    /** 标记已拆分(乐观: reversed_at IS NULL 防并发双拆)。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE profile_identity_merge
        SET reversed_at = CURRENT_TIMESTAMP, reversed_by = #{actor}
        WHERE merge_id = #{mergeId} AND reversed_at IS NULL
        """)
    int markReversed(@Param("mergeId") Long mergeId, @Param("actor") String actor);
}
