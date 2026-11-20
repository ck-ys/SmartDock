package com.smartdock.assistant.domain.gateway;

/**
 * 本实例身份读口（批 7 · 7C-1，2026-11-20）。
 *
 * <p>存在理由：run 登记要把「归属实例」写进 {@code dcs_assistant_run.owner_instance}，
 * 取值来自 legacy 的 {@code AssistantInstanceContext}（实例 id + Redis 租约那一套仍在
 * 引擎侧，批 9 随引擎收口）。新根 service 按规则 8 不许 import legacy，故把「当前实例 id」
 * 这一条读取收成接口放新根、实现落 infrastructure。</p>
 *
 * <p>语义与旧调用逐行一致：返回构造时确定的实例 id 字符串（配置固定则固定，否则
 * {@code hostname:pid:短码}），永不返回 null。</p>
 */
public interface InstanceIdentityGateway {

    /** @return 本进程的助手实例 id（非空） */
    String currentInstanceId();
}
