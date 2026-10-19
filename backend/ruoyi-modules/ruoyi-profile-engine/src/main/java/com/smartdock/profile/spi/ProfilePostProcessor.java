package com.smartdock.profile.spi;

import com.smartdock.profile.engine.ProfileScoreSnapshot;
import lombok.Data;

import java.util.Map;

/**
 * 快照后处理挂点(优先级 ordered, 越小越先)。
 *
 * <p>设计用途: AI 增强打分(assistant 侧装饰)/风控复核(risk 侧装饰)/标签二次加工。
 * 引擎内核不认识这些模块——它们反向依赖引擎实现本接口。</p>
 *
 * @author smartdock
 */
public interface ProfilePostProcessor {

    /**
     * 快照落库后的处理机会(引擎先落库、再以已存在行调用本链——dspro P1-3 回修,
     * snapshotId 可安全作外键引用)。实现可往 extra 追加附加字段(引擎合并回本条
     * 快照的 dimension_detail._extra, 一次 UPDATE), 但不应改动各维度 score/total
     * (那是算子与权重的领地)。
     */
    void process(SnapshotContext ctx);

    /**
     * 快照上下文: 已落库快照(id 有效) + 可变附加信息(回写本条快照 _extra 字段)。
     */
    @Data
    class SnapshotContext {
        private final ProfileScoreSnapshot snapshot;
        /** 后处理产物(引擎合并回本条快照 dimension_detail._extra) */
        private final Map<String, Object> extra;
    }
}
