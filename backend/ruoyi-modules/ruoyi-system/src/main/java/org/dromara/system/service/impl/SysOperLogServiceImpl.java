package org.dromara.system.service.impl;

import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.DateUtils;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.core.utils.ip.AddressUtils;
import org.dromara.common.log.event.OperLogEvent;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.SysOperLog;
import org.dromara.system.domain.bo.SysOperLogBo;
import org.dromara.system.domain.vo.SysOperLogVo;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.mapper.SysOperLogMapper;
import org.dromara.system.service.ISysOperLogService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 操作日志 服务层处理
 *
 * @author Lion Li
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SysOperLogServiceImpl implements ISysOperLogService {

    /** 部门名缓存 TTL：部门表极小，60 秒足够挡住高频操作日志，又能让改名很快生效 */
    private static final long DEPT_NAME_TTL_MS = 60_000L;

    private final SysOperLogMapper baseMapper;
    private final SysDeptMapper deptMapper;

    /** deptId -> [部门名, 取到时刻]；查不到/已删的部门不缓存，下次再试（避免把空值钉死） */
    private final Map<Long, Object[]> deptNameCache = new ConcurrentHashMap<>();

    /**
     * 操作日志记录
     *
     * @param operLogEvent 操作日志事件
     */
    @Async
    @EventListener
    public void recordOper(OperLogEvent operLogEvent) {
        // 2026-10-30 题9：部门名按 deptId 现查，别用登录快照——组织架构改名后旧会话会一直写旧名。
        // 查不到（部门被删/无权限/异常）就保留快照名：审计日志宁可写当时看到的，也不能空着。
        String liveName = currentDeptName(operLogEvent.getDeptId());
        if (StringUtils.isNotBlank(liveName)) {
            operLogEvent.setDeptName(liveName);
        }
        SysOperLogBo operLog = MapstructUtils.convert(operLogEvent, SysOperLogBo.class);
        // 远程查询操作地点
        operLog.setOperLocation(AddressUtils.getRealAddressByIP(operLog.getOperIp()));
        insertOperlog(operLog);
    }

    /** 按部门 ID 取当前名称（60 秒缓存）；任何异常都返回 null，由调用方退回快照名。 */
    private String currentDeptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Object[] hit = deptNameCache.get(deptId);
        if (hit != null && now - (Long) hit[1] < DEPT_NAME_TTL_MS) {
            return (String) hit[0];
        }
        try {
            SysDept dept = deptMapper.selectById(deptId);
            if (dept == null || StringUtils.isBlank(dept.getDeptName())) {
                deptNameCache.remove(deptId);
                return null;
            }
            deptNameCache.put(deptId, new Object[]{dept.getDeptName(), now});
            return dept.getDeptName();
        } catch (Exception e) {
            log.warn("[SysOperLog] 部门名实时查询失败 deptId={}，沿用登录快照名: {}", deptId, e.getMessage());
            return null;
        }
    }

    /**
     * 分页查询操作日志列表
     *
     * @param operLog   查询条件
     * @param pageQuery 分页参数
     * @return 操作日志分页列表
     */
    @Override
    public TableDataInfo<SysOperLogVo> selectPageOperLogList(SysOperLogBo operLog, PageQuery pageQuery) {
        LambdaQueryWrapper<SysOperLog> lqw = buildQueryWrapper(operLog);
        if (StringUtils.isBlank(pageQuery.getOrderByColumn())) {
            lqw.orderByDesc(SysOperLog::getOperId);
        }
        Page<SysOperLogVo> page = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(page);
    }

    private LambdaQueryWrapper<SysOperLog> buildQueryWrapper(SysOperLogBo operLog) {
        Map<String, Object> params = operLog.getParams();
        Date beginTime = DateUtils.parseDate(params.get("beginTime"));
        Date endTime = DateUtils.parseDate(params.get("endTime"));
        return new LambdaQueryWrapper<SysOperLog>()
            .like(StringUtils.isNotBlank(operLog.getOperIp()), SysOperLog::getOperIp, operLog.getOperIp())
            .like(StringUtils.isNotBlank(operLog.getTitle()), SysOperLog::getTitle, operLog.getTitle())
            .eq(operLog.getBusinessType() != null && operLog.getBusinessType() > 0,
                SysOperLog::getBusinessType, operLog.getBusinessType())
            .func(f -> {
                if (ArrayUtil.isNotEmpty(operLog.getBusinessTypes())) {
                    f.in(SysOperLog::getBusinessType, Arrays.asList(operLog.getBusinessTypes()));
                }
            })
            .eq(operLog.getStatus() != null,
                SysOperLog::getStatus, operLog.getStatus())
            .like(StringUtils.isNotBlank(operLog.getOperName()), SysOperLog::getOperName, operLog.getOperName())
            .between(beginTime != null && endTime != null,
                SysOperLog::getOperTime, beginTime, endTime);
    }

    /**
     * 新增操作日志
     *
     * @param bo 操作日志对象
     */
    @Override
    public void insertOperlog(SysOperLogBo bo) {
        SysOperLog operLog = MapstructUtils.convert(bo, SysOperLog.class);
        operLog.setOperTime(new Date());
        baseMapper.insert(operLog);
    }

    /**
     * 查询系统操作日志集合
     *
     * @param operLog 操作日志对象
     * @return 操作日志集合
     */
    @Override
    public List<SysOperLogVo> selectOperLogList(SysOperLogBo operLog) {
        LambdaQueryWrapper<SysOperLog> lqw = buildQueryWrapper(operLog);
        return baseMapper.selectVoList(lqw.orderByDesc(SysOperLog::getOperId));
    }

    /**
     * 批量删除系统操作日志
     *
     * @param operIds 需要删除的操作日志ID
     * @return 结果
     */
    @Override
    public int deleteOperLogByIds(Long[] operIds) {
        return baseMapper.deleteByIds(Arrays.asList(operIds));
    }

    /**
     * 查询操作日志详细
     *
     * @param operId 操作ID
     * @return 操作日志对象
     */
    @Override
    public SysOperLogVo selectOperLogById(Long operId) {
        return baseMapper.selectVoById(operId);
    }

    /**
     * 清空操作日志
     */
    @Override
    public void cleanOperLog() {
        baseMapper.delete(new LambdaQueryWrapper<>());
    }
}
