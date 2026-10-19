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
        + " parse_status='PENDING', parse_error=NULL, parsed_text=NULL, line_count=0,"
        + " update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND del_flag='1'")
    int revive(@Param("id") Long id, @Param("fileName") String fileName, @Param("suffix") String suffix);

    /** G4(批G 1008)：启动恢复——滞留未终态行（进程重启丢内存队列）重新入队自愈用 */
    @Select("SELECT * FROM dcs_assistant_attachment WHERE parse_status IN ('PENDING','PARSING')"
        + " AND del_flag='0'")
    List<DcsAssistantAttachment> listStuck();

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
     *  双向自毁的根因即 updateById 整实体回写） */
    @Update("UPDATE dcs_assistant_attachment SET parse_status='PARSING', update_time=CURRENT_TIMESTAMP"
        + " WHERE id=#{id} AND del_flag='0'")
    int markParsing(@Param("id") Long id);

    /** 解析成功回写（R14-F14-01/04 修：只动解析四列，不触 del_flag/属主等快照列） */
    @Update("UPDATE dcs_assistant_attachment SET parse_status=#{parseStatus}, parsed_text=#{parsedText},"
        + " line_count=#{lineCount}, parse_error=NULL, update_time=CURRENT_TIMESTAMP WHERE id=#{id}")
    int finishParseSuccess(@Param("id") Long id, @Param("parseStatus") String parseStatus,
                           @Param("parsedText") String parsedText, @Param("lineCount") int lineCount);

    /** 解析失败回写（僵尸超时判定/启动恢复源文件缺失/看门狗取消/解析异常共用；同款只动显式列） */
    @Update("UPDATE dcs_assistant_attachment SET parse_status='FAILED', parse_error=#{parseError},"
        + " update_time=CURRENT_TIMESTAMP WHERE id=#{id}")
    int finishParseFailure(@Param("id") Long id, @Param("parseError") String parseError);

    /** GDPR 清人：物理删该用户全部附件行（R14-F14-02 修：purgeUser 原四表缺附件） */
    @Delete("DELETE FROM dcs_assistant_attachment WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    int purgeUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);
}
