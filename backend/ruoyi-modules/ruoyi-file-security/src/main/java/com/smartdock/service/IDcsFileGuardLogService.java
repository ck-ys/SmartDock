package com.smartdock.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import com.smartdock.domain.vo.DcsFileGuardLogVo;
import com.smartdock.domain.vo.GuardStatsVo;

/**
 * 文件安全检测命中记录服务接口（Q4=A：概览条 + 检测记录页签）
 *
 * @author smartdock
 */
public interface IDcsFileGuardLogService {

    /** 近 7 天命中概览（total/denied/reviewed/observed，按当前租户） */
    GuardStatsVo stats7d();

    /** 检测记录分页（时间倒序） */
    TableDataInfo<DcsFileGuardLogVo> page(PageQuery pageQuery);
}
