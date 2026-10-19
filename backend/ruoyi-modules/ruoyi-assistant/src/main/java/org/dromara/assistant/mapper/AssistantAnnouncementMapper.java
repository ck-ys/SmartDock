package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.assistant.domain.DcsAssistantAnnouncement;

/**
 * 助手公告 Mapper（AI 缺口批 Q11，2026-10-14）。管理端/调度查询显式租户谓词。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantAnnouncementMapper extends BaseMapper<DcsAssistantAnnouncement> {

    /** 该租户最新一条生效公告（用户侧横幅数据源；无=null 前端不渲染横幅） */
    @Select("SELECT * FROM dcs_assistant_announcement "
        + "WHERE tenant_id=#{tenantId} AND status='1' ORDER BY publish_time DESC, id DESC LIMIT 1")
    DcsAssistantAnnouncement selectActive(@Param("tenantId") String tenantId);
}
