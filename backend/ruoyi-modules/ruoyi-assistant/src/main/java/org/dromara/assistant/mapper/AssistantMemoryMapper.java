package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.assistant.domain.DcsAssistantMemory;

import java.util.Date;
import java.util.List;

/**
 * 助手长期记忆 Mapper（二站④ AU-53）。显式租户谓词红线同
 * {@link AssistantFavoriteMapper}（@InterceptorIgnore + SQL 显式 tenant_id）——
 * 助手链路在流线程裸租户上下文跑（PD-2 契约），禁依赖行级插件自动拼租户。
 *
 * <p>ckys3 解锁板题7/题8/题10（2026-11-12）：清单按状态分档（ACTIVE/SUPERSEDED/ARCHIVED）、
 * 编辑改内容、同主题覆盖留痕、last_used_at 回写、90 天归档。所有写语句都带
 * tenant_id+user_id 双谓词（属主越权=影响 0 行）。</p>
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantMemoryMapper extends BaseMapper<DcsAssistantMemory> {

    /** 用户记忆清单（新→旧；属主+租户双谓词） */
    @Select("SELECT * FROM dcs_assistant_memory WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND del_flag='0' ORDER BY id DESC")
    List<DcsAssistantMemory> listByUser(@Param("tenantId") String tenantId,
                                        @Param("userId") Long userId);

    /** 按状态取清单（题7/题10：ACTIVE 主列表；SUPERSEDED/ARCHIVED 历史区） */
    @Select("SELECT * FROM dcs_assistant_memory WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND del_flag='0' AND status=#{status} ORDER BY id DESC")
    List<DcsAssistantMemory> listByUserStatus(@Param("tenantId") String tenantId,
                                              @Param("userId") Long userId,
                                              @Param("status") String status);

    /** 生效中条数（容量闸+记忆页条数用量；只算 ACTIVE） */
    @Select("SELECT COUNT(*) FROM dcs_assistant_memory WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND del_flag='0' AND status='ACTIVE'")
    int countByUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 按内容精确查重（同内容幂等不双落） */
    @Select("SELECT COUNT(*) FROM dcs_assistant_memory WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND content=#{content} AND del_flag='0' AND status='ACTIVE'")
    int countByContent(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                       @Param("content") String content);

    /** 同主题的生效中条目（题8：命中则被新条覆盖；可能多条→全归档） */
    @Select("SELECT * FROM dcs_assistant_memory WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND topic_key=#{topicKey} AND del_flag='0' AND status='ACTIVE'")
    List<DcsAssistantMemory> listActiveByTopic(@Param("tenantId") String tenantId,
                                               @Param("userId") Long userId,
                                               @Param("topicKey") String topicKey);

    /** 本人改一条（题7 行内编辑；属主+租户双谓词，改他人=0 行） */
    @Update("UPDATE dcs_assistant_memory SET content=#{content}, update_by=#{userId},"
        + " update_time=#{updateTime} WHERE tenant_id=#{tenantId} AND user_id=#{userId}"
        + " AND id=#{id} AND del_flag='0'")
    int updateOwnContent(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                         @Param("id") Long id, @Param("content") String content,
                         @Param("updateTime") Date updateTime);

    /** 同主题覆盖留痕（题8：旧条→SUPERSEDED + 指向新条） */
    @Update("UPDATE dcs_assistant_memory SET status='SUPERSEDED', superseded_by=#{newId},"
        + " update_by=#{userId}, update_time=#{updateTime}"
        + " WHERE tenant_id=#{tenantId} AND user_id=#{userId} AND id=#{id} AND del_flag='0'")
    int supersedeOwn(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                     @Param("id") Long id, @Param("newId") Long newId,
                     @Param("updateTime") Date updateTime);

    /** 注入命中后回写 last_used_at（题10：归档判据；只动 ACTIVE 行） */
    @Update("<script>UPDATE dcs_assistant_memory SET last_used_at=#{now}"
        + " WHERE tenant_id=#{tenantId} AND user_id=#{userId} AND status='ACTIVE' AND id IN"
        + " <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
        + "</script>")
    int touchUsed(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                  @Param("ids") List<Long> ids, @Param("now") Date now);

    /**
     * 90 天未用归档（题10 案1）。
     *
     * <p>只归档 last_used_at 非空且早于阈值的 ACTIVE 行——从未被注入过的条目不按创建时间
     * 误伤（拍板页口径：不是按创建时间）。归档不删数据，可恢复。</p>
     */
    @Update("UPDATE dcs_assistant_memory SET status='ARCHIVED', archived_at=#{now}"
        + " WHERE status='ACTIVE' AND del_flag='0' AND last_used_at IS NOT NULL"
        + " AND last_used_at < #{before}")
    int archiveStale(@Param("before") Date before, @Param("now") Date now);

    /** 清空本人全部（题10：含归档/历史；物理删——用户明确要求「想重来能清空」） */
    @Delete("DELETE FROM dcs_assistant_memory WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    int clearAllOwn(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 本人删单条（属主+租户双谓词；物理删——记忆无审计追责诉求，量级小） */
    @Delete("DELETE FROM dcs_assistant_memory WHERE tenant_id=#{tenantId}"
        + " AND user_id=#{userId} AND id=#{id}")
    int deleteOwn(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                  @Param("id") Long id);
}
