package com.smartdock.profile.spi;

/**
 * 自定义维度策略(Java 代码维度)。
 *
 * <p>注册为 Spring Bean 即被引擎发现; 维度治理表里 calc_mode=STRATEGY 的行通过
 * calc_config.strategyBean 指向本接口实现。</p>
 *
 * <p>与 EVENT_AGG 的分工: 能用 JSON 算子表达的一律用 EVENT_AGG;
 * 需要复杂判定(状态机推演/外部系统调用/多步聚合)才写策略类。</p>
 *
 * @author smartdock
 */
public interface DimensionStrategy {

    /** 归属业务源(与维度行的 source 对齐) */
    String source();

    /** 维度编码(与维度行的 dim_code 对齐) */
    String dimCode();

    /**
     * 计算单主体本维度得分。
     *
     * <p>实现约束: 只读(ctx.events() 查询), 禁止写任何表;
     * 抛异常由引擎捕获并按 missing 策略处理, 不中断其他维度。</p>
     */
    DimensionResult calc(DimensionContext ctx);
}
