package com.smartdock.risk.support;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.service.UserService;
import com.smartdock.risk.domain.DcsRiskEvent;
import com.smartdock.risk.domain.vo.DcsRiskEventVo;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 风险数据用户名补齐器（原 DcsRiskController 私有 helper，拆分后下沉为共享组件）。
 *
 * <p>用途：风险事件/处置/复核 VO 默认只携带 userId，前端展示需要 userName/nickName。
 * 该组件统一调用 {@link UserService} 查询并回填，避免 4 个 Controller 各写一份。
 *
 * <p>容错：用户可能已被删除（合成账号、跨租户），查询失败时静默忽略，
 * 仅保留 userId 以保证记录可追溯但不泄露其他租户身份。
 *
 * @author smartdock
 */
@Component
@RequiredArgsConstructor
public class RiskUserEnricher {

    private final UserService userService;

    /**
     * 把单个风险事件实体转为 VO 并补齐 userName / nickName。
     *
     * @param e 风险事件实体，可为 null
     * @return 带 userName/nickName 的 VO；入参为 null 时返回 null
     */
    public DcsRiskEventVo enrichEventVo(DcsRiskEvent e) {
        if (e == null) return null;
        DcsRiskEventVo vo = new DcsRiskEventVo();
        BeanUtils.copyProperties(e, vo);
        fillUserName(e.getUserId(), vo::setUserName, vo::setNickName);
        return vo;
    }

    /**
     * 批量把事件实体转为 VO（逐个补用户名）。大盘/事件流水行数有限，逐个查可接受。
     */
    public List<DcsRiskEventVo> enrichEventVo(List<DcsRiskEvent> events) {
        List<DcsRiskEventVo> list = new ArrayList<>(events.size());
        for (DcsRiskEvent e : events) {
            list.add(enrichEventVo(e));
        }
        return list;
    }

    /**
     * 补齐任意 VO 的 userName/nickName 字段（处置、复核等复用）。
     *
     * @param userId   用户 ID，null 时直接返回不查
     * @param userName 接收 userName 的 setter
     * @param nickName 接收 nickName 的 setter
     */
    public void fillUserName(Long userId, Consumer<String> userName, Consumer<String> nickName) {
        if (userId == null) return;
        try {
            userName.accept(userService.selectUserNameById(userId));
            nickName.accept(userService.selectNicknameById(userId));
        } catch (Exception ignored) {
            // Synthetic/deleted users remain traceable by userId without leaking another tenant's identity.
        }
    }
}
