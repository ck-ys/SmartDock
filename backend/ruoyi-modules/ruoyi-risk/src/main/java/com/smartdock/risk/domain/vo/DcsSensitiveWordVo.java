package com.smartdock.risk.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.risk.domain.DcsSensitiveWord;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 敏感词配置展示对象(列表/详情接口返回)。
 *
 * <p>由 DcsSensitiveWord 实体经 MapStruct(@AutoMapper) 映射而来, 仅暴露前端需要的只读字段,
 * 避免实体直接对外; 含审计字段 createTime/updateTime, 用于前端展示创建与最近修改时间。</p>
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsSensitiveWord.class)
public class DcsSensitiveWordVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long wordId;
    private String tenantId;
    private String wordText;
    private String category;
    private String action;
    private String enabled;
    private String builtin;
    private String remark;
    /** 生效场景 CSV(2026-09-22): CHAT/FORM/FILE 逗号组合, 存量行默认 CHAT */
    private String scene;
    private Date createTime;
    private Date updateTime;

    /** 近 7 天该词被判误报次数(U03 反馈表聚合, 词库调优参考; 非表字段由列表接口 enrich) */
    private Long fpCnt;
    /** 近 7 天该词命中事件数(dcs_risk_event.hit_word 聚合, 误报率分母; 非表字段由列表接口 enrich) */
    private Long hitCnt;
    /** 疑似过严标记(误报率>=50% 且命中>=5 次, 拍板 2026-09-23): 前端橙色提示标数据源 */
    private Boolean overstrict;
}
