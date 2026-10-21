package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileModelSnapshot;
import com.smartdock.profile.query.ProfileModelSubjectRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;
import java.util.Map;

/**
 * 模型分数快照 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileModelSnapshotMapper extends BaseMapperPlus<ProfileModelSnapshot, ProfileModelSnapshot> {

    /**
     * 批10 Q2: 快照链事务级库锁。必须在事务内调用; 事务提交/回滚时自动释放,
     * 崩溃不留死锁。语义=「取前链+写快照」在数据库层面不可分割, 链分叉结构上不可能。
     */
    @Select("SELECT pg_advisory_xact_lock(hashtext(#{key}))")
    void advisoryXactLock(@Param("key") String key);

    @Insert("""
        INSERT INTO profile_model_snapshot
            (model_snapshot_id, tenant_id, source, model_id, model_code, model_version,
             subject_type, subject_id, model_score, normalized_score, level, detail,
             run_id, prev_snapshot_id, calc_time, create_by, create_time)
        VALUES
            (#{s.modelSnapshotId}, #{s.tenantId}, #{s.source}, #{s.modelId}, #{s.modelCode},
             #{s.modelVersion}, #{s.subjectType}, #{s.subjectId}, #{s.modelScore},
             #{s.normalizedScore}, #{s.level}, #{s.detail}::jsonb, #{s.runId},
             #{s.prevSnapshotId}, #{s.calcTime}, #{s.createBy}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("s") ProfileModelSnapshot s);

    /**
     * 每个主体最新一条模型快照(分页)。
     */
    @Select("""
        <script>
        SELECT sub.subject_type, sub.subject_id, sub.display_name, sub.event_count, sub.last_event_time,
               ms.model_score, ms.normalized_score, ms.level, ms.detail, ms.model_version, ms.calc_time
        FROM profile_subject sub
        JOIN LATERAL (
            SELECT model_score, normalized_score, level, detail, model_version, calc_time
            FROM profile_model_snapshot
            WHERE tenant_id = #{tenantId} AND source = #{source} AND model_code = #{modelCode}
              AND subject_type = sub.subject_type AND subject_id = sub.subject_id
              AND del_flag = '0'
              AND model_version = #{modelVersion}
              AND (run_id IS NULL OR EXISTS (SELECT 1 FROM profile_model_run pr WHERE pr.run_id = profile_model_snapshot.run_id AND pr.status = 'DONE'))
            ORDER BY calc_time DESC, model_snapshot_id DESC
            LIMIT 1
        ) ms ON TRUE
        WHERE sub.tenant_id = #{tenantId} AND sub.source = #{source} AND sub.del_flag = '0'
        <if test="level != null and level != ''">
            AND ms.level = #{level}
        </if>
        <if test="keyword != null and keyword != ''">
            AND (sub.subject_id ILIKE '%' || #{keyword} || '%'
                 OR sub.display_name ILIKE '%' || #{keyword} || '%')
        </if>
        ORDER BY ms.normalized_score DESC NULLS LAST, sub.subject_id
        </script>
        """)
    com.baomidou.mybatisplus.core.metadata.IPage<ProfileModelSubjectRow> selectLatestPage(
        com.baomidou.mybatisplus.core.metadata.IPage<ProfileModelSubjectRow> page,
        @Param("tenantId") String tenantId,
        @Param("source") String source,
        @Param("modelCode") String modelCode,
        @Param("modelVersion") String modelVersion,
        @Param("level") String level,
        @Param("keyword") String keyword);
    /**
     * 每个主体最新一条模型快照(全量, 概览用; 10^4 以内可接受)。
     */
    @Select("""
        SELECT sub.subject_type, sub.subject_id, sub.display_name, sub.event_count, sub.last_event_time,
               ms.model_score, ms.normalized_score, ms.level, ms.detail, ms.model_version, ms.calc_time
        FROM profile_subject sub
        JOIN LATERAL (
            SELECT model_score, normalized_score, level, detail, model_version, calc_time
            FROM profile_model_snapshot
            WHERE tenant_id = #{tenantId} AND source = #{source} AND model_code = #{modelCode}
              AND subject_type = sub.subject_type AND subject_id = sub.subject_id
              AND del_flag = '0'
              AND model_version = #{modelVersion}
              AND (run_id IS NULL OR EXISTS (SELECT 1 FROM profile_model_run pr WHERE pr.run_id = profile_model_snapshot.run_id AND pr.status = 'DONE'))
            ORDER BY calc_time DESC, model_snapshot_id DESC
            LIMIT 1
        ) ms ON TRUE
        WHERE sub.tenant_id = #{tenantId} AND sub.source = #{source} AND sub.del_flag = '0'
        ORDER BY ms.normalized_score DESC NULLS LAST, sub.subject_id
        """)
    List<ProfileModelSubjectRow> selectLatestAll(@Param("tenantId") String tenantId,
                                                 @Param("source") String source,
                                                 @Param("modelCode") String modelCode,
                                                 @Param("modelVersion") String modelVersion);

    @Select("""
        SELECT model_snapshot_id AS "modelSnapshotId", model_score AS "modelScore",
               normalized_score AS "normalizedScore", level, detail, model_version AS "modelVersion",
               subject_type AS "subjectType", subject_id AS "subjectId",
               prev_snapshot_id AS "prevSnapshotId", calc_time AS "calcTime"
        FROM profile_model_snapshot
        WHERE tenant_id = #{tenantId} AND source = #{source} AND model_code = #{modelCode}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId} AND del_flag = '0'
          AND (run_id IS NULL OR EXISTS (SELECT 1 FROM profile_model_run pr WHERE pr.run_id = profile_model_snapshot.run_id AND pr.status = 'DONE'))
        ORDER BY calc_time DESC, model_snapshot_id DESC
        LIMIT #{limit}
        """)
    List<Map<String, Object>> selectHistory(@Param("tenantId") String tenantId,
                                           @Param("source") String source,
                                           @Param("modelCode") String modelCode,
                                           @Param("subjectType") String subjectType,
                                           @Param("subjectId") String subjectId,
                                           @Param("limit") int limit);

    /**
     * 每日趋势: 每个主体每天取最后一条, 再按天聚合。
     */
    @Select("""
        SELECT day, count(*) AS subjects, avg(normalized_score) AS "avgScore"
        FROM (
            SELECT DISTINCT ON (subject_type, subject_id, to_char(calc_time, 'YYYY-MM-DD'))
                   to_char(calc_time, 'YYYY-MM-DD') AS day, normalized_score
            FROM profile_model_snapshot
            WHERE tenant_id = #{tenantId} AND source = #{source} AND model_code = #{modelCode}
              AND calc_time >= now() - make_interval(days => #{days})
              AND del_flag = '0'
              AND (run_id IS NULL OR EXISTS (SELECT 1 FROM profile_model_run pr WHERE pr.run_id = profile_model_snapshot.run_id AND pr.status = 'DONE'))
            ORDER BY subject_type, subject_id, to_char(calc_time, 'YYYY-MM-DD'), calc_time DESC
        ) t
        GROUP BY day
        ORDER BY day
        """)
    List<Map<String, Object>> selectTrend(@Param("tenantId") String tenantId,
                                          @Param("source") String source,
                                          @Param("modelCode") String modelCode,
                                          @Param("days") int days);

    @Select("""
        SELECT level, count(*) AS cnt
        FROM (
            SELECT DISTINCT ON (subject_type, subject_id) level
            FROM profile_model_snapshot
            WHERE tenant_id = #{tenantId} AND source = #{source} AND model_code = #{modelCode}
              AND del_flag = '0'
              AND (run_id IS NULL OR EXISTS (SELECT 1 FROM profile_model_run pr WHERE pr.run_id = profile_model_snapshot.run_id AND pr.status = 'DONE'))
            ORDER BY subject_type, subject_id, calc_time DESC, model_snapshot_id DESC
        ) t
        GROUP BY level
        """)
    List<Map<String, Object>> selectLevelDistribution(@Param("tenantId") String tenantId,
                                                      @Param("source") String source,
                                                      @Param("modelCode") String modelCode);

    /**
     * 每个主体最新一条模型快照 ID(写前链用)。
     */
    @Select("""
        SELECT DISTINCT ON (subject_type, subject_id)
               subject_type AS "subjectType", subject_id AS "subjectId",
               model_snapshot_id AS "modelSnapshotId"
        FROM profile_model_snapshot
        WHERE tenant_id = #{tenantId} AND source = #{source} AND model_code = #{modelCode}
          AND del_flag = '0'
          AND (run_id IS NULL OR EXISTS (SELECT 1 FROM profile_model_run pr WHERE pr.run_id = profile_model_snapshot.run_id AND pr.status = 'DONE'))
        ORDER BY subject_type, subject_id, calc_time DESC, model_snapshot_id DESC
        """)
    List<Map<String, Object>> selectLatestIds(@Param("tenantId") String tenantId,
                                              @Param("source") String source,
                                              @Param("modelCode") String modelCode);

    /**
     * 指定 run 的全部快照(版本对比用, 批9 Q1-A)。
     * 批10 R5: 联 profile_subject 取显示名——版本对比 Top 变化主体不再匿名。
     */
    @Select("""
        SELECT s.subject_type AS "subjectType", s.subject_id AS "subjectId",
               sub.display_name AS "displayName",
               s.model_score AS "modelScore", s.normalized_score AS "normalizedScore",
               s.level, s.detail, s.model_version AS "modelVersion", s.calc_time AS "calcTime"
        FROM profile_model_snapshot s
        LEFT JOIN profile_subject sub
          ON sub.tenant_id = s.tenant_id AND sub.source = s.source
         AND sub.subject_type = s.subject_type AND sub.subject_id = s.subject_id
        WHERE s.tenant_id = #{tenantId} AND s.run_id = #{runId} AND s.del_flag = '0'
        ORDER BY s.subject_type, s.subject_id
        """)
    List<Map<String, Object>> selectByRun(@Param("tenantId") String tenantId,
                                           @Param("runId") Long runId);
}