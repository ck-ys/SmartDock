package com.smartdock.assistant.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import com.smartdock.assistant.domain.model.DcsAssistantUserPref;
import com.smartdock.assistant.domain.bo.AssistantUserPrefBo;
import com.smartdock.assistant.domain.vo.AssistantUserPrefVo;
import com.smartdock.assistant.mapper.AssistantUserPrefMapper;

import java.util.Date;
import java.util.Set;

/**
 * 助手「常规偏好」按人读写（批C Q8 案1，2026-11-15；2026-11-13 待办板已拍）。
 *
 * <p>口径：全量覆盖式保存（不传的项落默认值）；缺行返回默认值且 {@code saved=false}。
 * 非法枚举值直接拒绝，不静默改成默认——静默改会把用户的错设置变成"看起来保存成功了"。</p>
 */
@Service
@RequiredArgsConstructor
public class AssistantUserPrefService {

    /** 七项默认值：与前端 prefsStore 的 DEFAULTS 一一对应，改一处必须改两处 */
    public static final String DEF_SEND_KEY = "enter";
    public static final boolean DEF_STREAM_OUTPUT = true;
    public static final boolean DEF_TOOLS_OPEN = false;
    public static final boolean DEF_AUTO_RETRY = true;
    public static final String DEF_MOTION = "system";
    public static final String DEF_ANSWER_SIZE = "standard";
    public static final boolean DEF_ASK_AUTO_CONTINUE = true;

    private static final Set<String> SEND_KEYS = Set.of("enter", "ctrl-enter");
    private static final Set<String> MOTIONS = Set.of("system", "full", "reduced");
    private static final Set<String> ANSWER_SIZES = Set.of("standard", "large");

    private final AssistantUserPrefMapper mapper;

    /** 读本人偏好；缺行=默认值，saved=false */
    public AssistantUserPrefVo load(String tenantId, Long userId) {
        DcsAssistantUserPref row = mapper.selectOwn(tenantId, userId);
        AssistantUserPrefVo vo = new AssistantUserPrefVo();
        if (row == null) {
            vo.setSaved(false);
            vo.setSendKey(DEF_SEND_KEY);
            vo.setStreamOutput(DEF_STREAM_OUTPUT);
            vo.setToolsOpen(DEF_TOOLS_OPEN);
            vo.setAutoRetry(DEF_AUTO_RETRY);
            vo.setMotion(DEF_MOTION);
            vo.setAnswerSize(DEF_ANSWER_SIZE);
            vo.setAskAutoContinue(DEF_ASK_AUTO_CONTINUE);
            return vo;
        }
        vo.setSaved(true);
        vo.setSendKey(row.getSendKey());
        vo.setStreamOutput(flag(row.getStreamOutput()));
        vo.setToolsOpen(flag(row.getToolsOpen()));
        vo.setAutoRetry(flag(row.getAutoRetry()));
        vo.setMotion(row.getMotion());
        vo.setAnswerSize(row.getAnswerSize());
        vo.setAskAutoContinue(flag(row.getAskAutoContinue()));
        return vo;
    }

    /**
     * 全量覆盖保存。
     *
     * @throws ServiceException 枚举值不合法（全局异常处理回 code=500 的人话报错，操作日志记 FAIL）
     */
    public void save(String tenantId, Long userId, AssistantUserPrefBo bo) {
        try {
            String sendKey = pick(bo == null ? null : bo.getSendKey(), DEF_SEND_KEY, SEND_KEYS, "发送键");
            String motion = pick(bo == null ? null : bo.getMotion(), DEF_MOTION, MOTIONS, "动效强度");
            String answerSize = pick(bo == null ? null : bo.getAnswerSize(), DEF_ANSWER_SIZE, ANSWER_SIZES, "回答字号");
            mapper.upsertOwn(IdWorker.getId(), tenantId, userId,
                sendKey,
                bit(bo == null ? null : bo.getStreamOutput(), DEF_STREAM_OUTPUT),
                bit(bo == null ? null : bo.getToolsOpen(), DEF_TOOLS_OPEN),
                bit(bo == null ? null : bo.getAutoRetry(), DEF_AUTO_RETRY),
                motion,
                answerSize,
                bit(bo == null ? null : bo.getAskAutoContinue(), DEF_ASK_AUTO_CONTINUE),
                new Date());
        } catch (IllegalArgumentException e) {
            // pick 保持纯函数（单测不依赖 Spring）；在这里换成业务异常，好让全局处理与操作日志都认
            throw new ServiceException(e.getMessage());
        }
    }

    /** 库里的 '0' 才算关；缺值/老数据按开（与前端 flag 语义一致） */
    public static boolean flag(String v) {
        return !"0".equals(v);
    }

    public static String bit(Boolean v, boolean def) {
        boolean b = v == null ? def : v;
        return b ? "1" : "0";
    }

    public static String pick(String v, String def, Set<String> allowed, String label) {
        if (v == null || v.isEmpty()) {
            return def;
        }
        if (!allowed.contains(v)) {
            // 回显前截断：非法值可能是一整段长文本，原样回显会进响应与操作日志
            String shown = v.length() > 32 ? v.substring(0, 32) + "…" : v;
            throw new IllegalArgumentException(label + "只能是 " + String.join(" / ", allowed) + "，收到：" + shown);
        }
        return v;
    }
}
