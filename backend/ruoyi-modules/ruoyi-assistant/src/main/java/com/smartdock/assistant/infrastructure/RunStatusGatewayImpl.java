package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.domain.gateway.RunStatusGateway;
import com.smartdock.assistant.legacy.mapper.AssistantRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link RunStatusGateway} 的唯一实现（批 7 · 7B，2026-11-20）。
 *
 * <p>run 表 Mapper 仍在 legacy（run 记录的写路径属 7C），本实现是唯一跨线点：只做
 * 「查列表 + 换成集合」，不改查询口径。新根 query 服务只认接口，不认 legacy。</p>
 *
 * <p><b>空值语义</b>：{@code null} 与空列表一律返回<b>空集、不抛异常</b>（旧实现遇 null 会 NPE；
 * 列表查询按 MyBatis 契约不会返回 null，这里是有意的防御性收口）。两个分支都返回<b>可变</b>
 * {@code HashSet}，调用方对返回值增删不会踩 {@code Set.of()} 的不可变异常（多审 dspro P2-1/P2-5）。</p>
 */
@Component
@RequiredArgsConstructor
public class RunStatusGatewayImpl implements RunStatusGateway {

    private final AssistantRunMapper runMapper;

    @Override
    public Set<String> runningThreadIds(String tenantId, Long userId) {
        List<String> rows = runMapper.selectRunningThreadIds(tenantId, userId);
        return rows == null ? new HashSet<>() : new HashSet<>(rows);
    }
}
