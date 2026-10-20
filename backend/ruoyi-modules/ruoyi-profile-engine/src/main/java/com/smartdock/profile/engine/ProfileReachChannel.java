package com.smartdock.profile.engine;

import java.util.List;
import java.util.Map;

/**
 * 触达通道 SPI（批9 拍板 Q3-A）：画像引擎只定义契约与审计，
 * 具体通道由宿主侧实现并注册为 Spring Bean（站内通知在 demo 模块实现，
 * 邮件/Webhook 将来加实现类即可，流程不动）。
 *
 * @author smartdock
 */
public interface ProfileReachChannel {

    /** 通道编码，对应 profile_reach_batch.channel。 */
    String channel();

    /**
     * 发送给单个系统用户。实现方负责用户存在性校验、幂等（dedupKey）与截断。
     *
     * @return 发送结果；不抛异常（失败降级为 FAILED 明细行）
     */
    Result send(Long userId, String title, String content, String dedupKey);

    /**
     * 该通道能触达的用户 ID 集合（收件人预检用）；
     * 返回 null 表示通道不做预检（按成员 subjectType=user 全量尝试）。
     */
    default List<Long> reachableUserIds(List<Long> candidateUserIds) {
        return null;
    }

    record Result(boolean ok, String error) {

        public static Result success() {
            return new Result(true, null);
        }

        public static Result fail(String error) {
            return new Result(false, error == null ? "unknown" : error);
        }
    }

    /** 通道元信息（管理端展示用）。 */
    default Map<String, Object> describe() {
        return Map.of("channel", channel());
    }
}
