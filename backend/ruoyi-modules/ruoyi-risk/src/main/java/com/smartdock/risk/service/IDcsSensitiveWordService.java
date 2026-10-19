package com.smartdock.risk.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import com.smartdock.risk.domain.bo.DcsSensitiveWordBo;
import com.smartdock.risk.domain.bo.SensitiveWordBatchCommand;
import com.smartdock.risk.domain.vo.DcsSensitiveWordVo;
import com.smartdock.risk.domain.vo.SensitiveWordInspectionVo;
import com.smartdock.risk.domain.vo.SensitiveWordStatsVo;

import java.util.Collection;

/**
 * 敏感词管理 Service(供聊天/任务内容过滤使用)。
 *
 * @author smartdock
 */
public interface IDcsSensitiveWordService {

    /** 分页查询 */
    TableDataInfo<DcsSensitiveWordVo> queryPageList(DcsSensitiveWordBo bo, PageQuery pageQuery);

    /** 按ID查询 */
    DcsSensitiveWordVo queryById(Long wordId);

    /** 新增 */
    Long insertByBo(DcsSensitiveWordBo bo);

    /** 修改 */
    Boolean updateByBo(DcsSensitiveWordBo bo);

    /** 批量删除 */
    Boolean deleteByIds(Collection<Long> ids);

    /** 全量口径统计(词库管理页统计卡, 2026-08-23): 词条总数/启用/各动作数 */
    SensitiveWordStatsVo stats();

    /** 批量导入(2026-08-23): 单事务逐词复用新增校验, 重复词跳过; 返回聚合消息 */
    String batchImport(SensitiveWordBatchCommand command);

    /**
     * 命中自测(2026-08-24, 词库管理页"命中测试"): 委托 FilterService 用真实过滤规则(含内置基线)
     * 做只读匹配, 返回命中明细——不产生风险事件不记分。
     *
     * @param text  测试文本
     * @param scene 生效场景(CHAT/FORM/FILE; null=全部词, 2026-09-22 场景化)
     */
    SensitiveWordInspectionVo testMatch(String text, String scene);
}
