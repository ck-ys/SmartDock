package com.smartdock.risk.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 敏感词命中自测结果 VO(2026-08-24, 词库管理页"命中测试")。
 *
 * <p>纯只读验证: 用真实过滤规则(租户+平台词库+内置基线)匹配管理员输入的测试文本,
 * 展示命中词与动作——不产生风险事件、不给任何账号记分。</p>
 *
 * @author smartdock
 */
@Data
public class SensitiveWordInspectionVo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 命中明细(按首次命中去重) */
    private List<Hit> hits;

    /** 是否含阻断词(发送该文本会被拦截) */
    private boolean blocked;

    /** 是否含转人工复核词 */
    private boolean reviewRequired;

    /**
     * 单个命中: word=命中原词(管理页验证场景明文展示), action=MASK/BLOCK/REVIEW,
     * [start,end)=在输入文本中的字符区间(基于归一化文本, 仅定位参考)。
     */
    @Data
    public static class Hit implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String word;
        private String action;
        private int start;
        private int end;
    }
}
