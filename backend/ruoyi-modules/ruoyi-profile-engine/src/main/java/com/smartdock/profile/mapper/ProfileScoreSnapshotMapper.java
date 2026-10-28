package com.smartdock.profile.mapper;

import com.smartdock.profile.engine.ProfileScoreSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 分数快照 Mapper。
 *
 * <p>dimension_detail 是 jsonb, PG 不接受 varchar 参数隐式转换——
 * 插入必须走 {@link #insertJsonb} 的 ::jsonb 显式强转(标准 insert 会报
 * "column is of type jsonb but expression is of type character varying")。</p>
 *
 * @author smartdock
 */
@Mapper
public interface ProfileScoreSnapshotMapper extends BaseMapperPlus<ProfileScoreSnapshot, ProfileScoreSnapshot> {

    /**
     * 快照落库(jsonb 强转版, 唯一写入口)。
     */
    @Insert("""
        INSERT INTO profile_score_snapshot
            (snapshot_id, tenant_id, source, subject_type, subject_id, total_score,
             dimension_detail, rule_version, prev_snapshot_id, calc_time, create_by, create_time)
        VALUES
            (#{s.snapshotId}, #{s.tenantId}, #{s.source}, #{s.subjectType}, #{s.subjectId}, #{s.totalScore},
             #{s.dimensionDetail}::jsonb, #{s.ruleVersion}, #{s.prevSnapshotId}, #{s.calcTime},
             #{s.createBy}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("s") ProfileScoreSnapshot s);

    /**
     * 后处理 _extra 回写(dspro P1-3: 先落库再跑后处理, extra 合并回本条快照)。
     * 显式带 tenant_id——TenantLine 见条件列已存在会跳过追加。
     */
    @Update("""
        UPDATE profile_score_snapshot
        SET dimension_detail = #{detail}::jsonb
        WHERE snapshot_id = #{snapshotId} AND tenant_id = #{tenantId}
        """)
    int updateDimensionDetail(@Param("snapshotId") Long snapshotId, @Param("tenantId") String tenantId,
                              @Param("detail") String detail);

    /**
     * 各维度在“每主体最新快照”中的覆盖次数(按主体类型一套)。
     */
    @Select("""
        SELECT key AS dim_code, COUNT(*) AS covered
        FROM (
            SELECT DISTINCT ON (subject_id) dimension_detail
            FROM profile_score_snapshot
            WHERE tenant_id = #{tenantId} AND source = #{source} AND subject_type = #{subjectType}
              AND del_flag = '0'
            ORDER BY subject_id, calc_time DESC, snapshot_id DESC
        ) t, LATERAL jsonb_each(t.dimension_detail)
        GROUP BY key
        """)
    List<Map<String, Object>> selectDimensionCoverage(@Param("tenantId") String tenantId,
                                                      @Param("source") String source,
                                                      @Param("subjectType") String subjectType);

    /**
     * 有最新快照的主体数(按主体类型)。
     */
    @Select("""
        SELECT COUNT(*) FROM (
            SELECT DISTINCT ON (subject_id) 1
            FROM profile_score_snapshot
            WHERE tenant_id = #{tenantId} AND source = #{source} AND subject_type = #{subjectType}
              AND del_flag = '0'
            ORDER BY subject_id, calc_time DESC, snapshot_id DESC
        ) t
        """)
    Long countLatestSnapshotSubjects(@Param("tenantId") String tenantId,
                                     @Param("source") String source,
                                     @Param("subjectType") String subjectType);

    /**
     * 最近一次算分时间(按主体类型)。
     */
    @Select("""
        SELECT MAX(calc_time) FROM profile_score_snapshot
        WHERE tenant_id = #{tenantId} AND source = #{source} AND subject_type = #{subjectType}
          AND del_flag = '0'
        """)
    Date selectLastCalcTime(@Param("tenantId") String tenantId,
                            @Param("source") String source,
                            @Param("subjectType") String subjectType);
}
