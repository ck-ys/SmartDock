package com.smartdock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import com.smartdock.domain.DcsFileGuardLog;
import com.smartdock.domain.vo.DcsFileGuardLogVo;
import com.smartdock.domain.vo.GuardStatsVo;
import com.smartdock.mapper.DcsFileGuardLogMapper;
import com.smartdock.service.IDcsFileGuardLogService;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 文件安全检测命中记录服务（Q4=A）
 *
 * <p>概览四数用 4 次 selectCount（管理弹窗低频调用，数据量=命中数，无需聚合优化）；
 * 租户过滤由 MyBatis-Plus 租户插件自动追加。</p>
 *
 * @author smartdock
 */
@RequiredArgsConstructor
@Service
public class DcsFileGuardLogServiceImpl implements IDcsFileGuardLogService {

    private final DcsFileGuardLogMapper baseMapper;

    @Override
    public GuardStatsVo stats7d() {
        Date since = new Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000);
        GuardStatsVo vo = new GuardStatsVo();
        vo.setTotal(countSince(since, null));
        vo.setDenied(countSince(since, "DENIED"));
        vo.setReviewed(countSince(since, "REVIEWED"));
        vo.setObserved(countSince(since, "OBSERVED"));
        return vo;
    }

    @Override
    public TableDataInfo<DcsFileGuardLogVo> page(PageQuery pageQuery) {
        LambdaQueryWrapper<DcsFileGuardLog> lqw = new LambdaQueryWrapper<DcsFileGuardLog>()
            .orderByDesc(DcsFileGuardLog::getCreateTime);
        // 显式声明 Page 类型：MP 的 Page 同时实现 IPage 与 List，直接内联会让 build() 重载歧义
        Page<DcsFileGuardLogVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(result);
    }

    private Long countSince(Date since, String disposition) {
        LambdaQueryWrapper<DcsFileGuardLog> lqw = new LambdaQueryWrapper<DcsFileGuardLog>()
            .ge(DcsFileGuardLog::getCreateTime, since);
        if (disposition != null) {
            lqw.eq(DcsFileGuardLog::getDisposition, disposition);
        }
        return baseMapper.selectCount(lqw);
    }
}
