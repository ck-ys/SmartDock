package com.smartdock.risk.support;

import java.util.Date;

/**
 * 风控侧对"行为埋点底座"的端口接口（依赖倒置，2026-09-23 风控模块化 grill Q2A 拍板）。
 *
 * <p>BehaviorEventService 是 dcs 模块的共享底座（RFM/登录监听/风控埋点共用），
 * 不能随风控搬走；而 Maven 依赖方向已定为 dcs→risk 单向，risk 模块不能反向 import dcs 接口。
 * 因此 risk 定义本端口只声明自己用到的唯一方法 {@link #record}，
 * 由 dcs 的 BehaviorEventServiceImpl 一并实现（Spring 按类型注入，运行时仍是同一个 bean）。</p>
 *
 * <p>方法语义与 com.smartdock.service.behavior.BehaviorEventService#record 完全一致：
 * 同步 INSERT 一条行为流水（不随调用方事务回滚），userId 为空时内部跳过。</p>
 *
 * @author smartdock
 */
public interface RiskBehaviorSink {

    /**
     * 记录一条用户行为事件（已带租户上下文，同步写入，不随主事务回滚）。
     *
     * @param tenantId  租户 ID
     * @param userId    行为发起用户 ID（不能为空）
     * @param eventType 行为类型 MESSAGE/RISK/TRANSFER/LOGIN
     * @param eventTime 行为发生时间（null 则取当前）
     * @param sourceIp  来源 IP（可空）
     * @param missionId 关联任务 ID（可空）
     * @param traceId   关联转发链 ID（可空）
     * @param metadata  附加元数据 JSON 字符串（可空）
     */
    void record(String tenantId, Long userId, String eventType, Date eventTime,
                String sourceIp, Long missionId, Long traceId, String metadata);
}
