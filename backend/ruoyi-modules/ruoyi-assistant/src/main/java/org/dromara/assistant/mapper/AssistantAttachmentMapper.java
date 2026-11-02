package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.assistant.domain.DcsAssistantAttachment;

import java.util.List;

/**
 * 助手会话附件 Mapper（ATTACH-1，2026-10-07）。显式租户谓词红线同
 * {@link AssistantConversationMapper}（@InterceptorIgnore + SQL 显式 tenant_id）。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantAttachmentMapper extends BaseMapper<DcsAssistantAttachment> {

    /** 同会话附件清单（旧→新；属主+租户双谓词） */
    @Select("SELECT * FROM dcs_assistant_attachment WHERE tenant_id=#{tenantId}"
        + " AND thread_id=#{threadId} AND user_id=#{userId} AND del_flag='0' ORDER BY id")
    List<DcsAssistantAttachment> listByThread(@Param("tenantId") String tenantId,
                                              @Param("threadId") String threadId,
                                              @Param("userId") Long userId);

    /** G2(批G 1008)：同 (tenant, thread, oss) 查重含软删行——复活判定用（唯一索引
     *  uk(tenant,thread,oss) 不含 del_flag，软删行仍占键位，insert 必撞 500）。
     *  取代旧 selectByOss（del_flag='0' 过滤版，复活判定下无调用方） */
    @Select("SELECT * FROM dcs_assistant_attachment WHERE tenant_id=#{tenantId}"
        + " AND thread_id=#{threadId} AND oss_id=#{ossId}")
    DcsAssistantAttachment selectByOssAny(@Param("tenantId") String tenantId,
                                          @Param("threadId") String threadId,
                                          @Param("ossId") Long ossId);

    /** G2(批G 1008)：复活软删行（同文件重挂）——显式 UPDATE 回 del_flag='0' 并重置解析态 */
    @Update("UPDATE dcs_assistant_attachment SET del_flag='0', file_name=#{fileName}, suffix=#{suffix},"
        + " parse_status='PENDING', parse_owner=NULL, parse_error=NULL, parsed_text=NULL, line_count=0,"
        + " update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND del_flag='1'")
    int revive(@Param("id") Long id, @Param("fileName") String fileName, @Param("suffix") String suffix);

    /**
     * 多实例批 3：原子认领滞留解析行——谁 UPDATE 到谁解析，两个实例同时启动/巡检
     * 不会把同一行各排一遍。阈值由调用方给（分钟）：启动恢复用 PENDING 0 分钟（进程
     * 重启丢的是本机队列，PENDING 行立刻可以重排）+ PARSING 10 分钟（别台还在跑的长
     * 解析不抢）；每分钟巡检用 5/10 分钟。UPDATE...RETURNING 保证并发下每行只归一个实例。
     *
     * <p>四审回修（dspro-P1-2）：认领同时写 {@code parse_owner}——没有归属时，B 启动会把
     * A 排队中的 PENDING 一起认领，A 迟到的任务又把 PARSED 翻回 PARSING；现在 A 的
     * markParsing/finish 都校验 owner，认领被换走就自动放弃。</p>
     */
    @Select("UPDATE dcs_assistant_attachment SET parse_status='PARSING', parse_owner=#{owner},"
        + " update_time=CURRENT_TIMESTAMP"
        + " WHERE id IN (SELECT id FROM dcs_assistant_attachment"
        + "   WHERE del_flag='0' AND ("
        + "     (parse_status='PENDING' AND update_time < CURRENT_TIMESTAMP - make_interval(mins => #{pendingMins}))"
        + "     OR (parse_status='PARSING' AND update_time < CURRENT_TIMESTAMP - make_interval(mins => #{parsingMins})))"
        + "   ORDER BY update_time LIMIT #{batch})"
        + " AND del_flag='0' AND ("
        + "   (parse_status='PENDING' AND update_time < CURRENT_TIMESTAMP - make_interval(mins => #{pendingMins}))"
        + "   OR (parse_status='PARSING' AND update_time < CURRENT_TIMESTAMP - make_interval(mins => #{parsingMins})))"
        + " RETURNING *")
    List<DcsAssistantAttachment> claimStale(@Param("batch") int batch,
                                            @Param("pendingMins") int pendingMins,
                                            @Param("parsingMins") int parsingMins,
                                            @Param("owner") String owner);

    /**
     * 四审（dspro-P1-2）：入队前认领归属——只认“无主或本机重入”的 PENDING/PARSING 行；
     * 已被别台换主/已终态/已删除返回 0，调用方直接放弃，绝不重复解析。
     */
    @Update("UPDATE dcs_assistant_attachment SET parse_owner=#{owner}, update_time=CURRENT_TIMESTAMP"
        + " WHERE id=#{id} AND del_flag='0' AND parse_status IN ('PENDING','PARSING')"
        + " AND (parse_owner IS NULL OR parse_owner=#{owner})")
    int claimForParse(@Param("id") Long id, @Param("owner") String owner);

    /** 随会话保留期清理：按上限分批物理删（附件>180 天，与 conversation 同口径） */
    @Delete("DELETE FROM dcs_assistant_attachment WHERE id IN (SELECT id FROM dcs_assistant_attachment"
        + " WHERE create_time < (CURRENT_TIMESTAMP - INTERVAL '180 days') LIMIT #{batch})")
    int deleteExpired(@Param("batch") int batch);

    /** 属主软删（AU-05A 移除通道：腾挂载位；租户+属主双谓词，影响行数=0 即无权/不存在） */
    @Update("UPDATE dcs_assistant_attachment SET del_flag='1' WHERE id=#{id}"
        + " AND tenant_id=#{tenantId} AND user_id=#{userId} AND del_flag='0'")
    int softDeleteOwned(@Param("tenantId") String tenantId,
                        @Param("userId") Long userId,
                        @Param("id") Long id);

    /** 解析开工置 PARSING（G4 计时起点）。R14-F14-04 修：显式列更新+del_flag='0' 守卫——
     *  排队期间被用户移除的行返 0（调用方跳过），且绝不把快照 del_flag 写回（复活/再软删
     *  双向自毁的根因即 updateById 整实体回写）。
     *  四审（dspro-P1-2）：再带 owner 守卫——认领被别台换走后本机迟到任务自动放弃；
     *  PENDING/PARSING 之外（PARSED/TRUNCATED/FAILED）一律不翻回，终态不可被迟到任务覆盖。 */
    @Update("UPDATE dcs_assistant_attachment SET parse_status='PARSING', update_time=CURRENT_TIMESTAMP"
        + " WHERE id=#{id} AND del_flag='0' AND parse_owner=#{owner}"
        + " AND parse_status IN ('PENDING','PARSING')")
    int markParsing(@Param("id") Long id, @Param("owner") String owner);

    /** 解析成功回写（R14-F14-01/04 修：只动解析四列，不触 del_flag/属主等快照列）。
     *  四审（dspro-P1-2）：owner+状态双守卫，迟到/被换主的任务不能覆盖成功结果。 */
    @Update("UPDATE dcs_assistant_attachment SET parse_status=#{parseStatus}, parsed_text=#{parsedText},"
        + " line_count=#{lineCount}, parse_error=NULL, update_time=CURRENT_TIMESTAMP"
        + " WHERE id=#{id} AND parse_owner=#{owner} AND parse_status='PARSING'")
    int finishParseSuccess(@Param("id") Long id, @Param("owner") String owner,
                           @Param("parseStatus") String parseStatus,
                           @Param("parsedText") String parsedText, @Param("lineCount") int lineCount);

    /** 解析失败回写（僵尸超时判定/启动恢复源文件缺失/看门狗取消/解析异常共用；同款只动显式列）。
     *  四审（dspro-P1-2）：解析线程用 owned 版，owner+状态双守卫；看门狗/读时僵尸仍走无主版。 */
    @Update("UPDATE dcs_assistant_attachment SET parse_status='FAILED', parse_error=#{parseError},"
        + " update_time=CURRENT_TIMESTAMP WHERE id=#{id}")
    int finishParseFailure(@Param("id") Long id, @Param("parseError") String parseError);

    /** 解析线程的失败回写（带 owner 守卫；被换主/已终态返回 0，不覆盖别人的结果） */
    @Update("UPDATE dcs_assistant_attachment SET parse_status='FAILED', parse_error=#{parseError},"
        + " update_time=CURRENT_TIMESTAMP"
        + " WHERE id=#{id} AND parse_owner=#{owner} AND parse_status='PARSING'")
    int finishParseFailureOwned(@Param("id") Long id, @Param("owner") String owner,
                                @Param("parseError") String parseError);

    /** GDPR 清人：物理删该用户全部附件行（R14-F14-02 修：purgeUser 原四表缺附件） */
    @Delete("DELETE FROM dcs_assistant_attachment WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    int purgeUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);
}
