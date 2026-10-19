package org.dromara.common.core.constant;

/**
 * 缓存的key 常量
 *
 * @author Lion Li
 */
public interface CacheConstants {

    /**
     * 在线用户 redis key
     */
    String ONLINE_TOKEN_KEY = "online_tokens:";

    /**
     * 参数管理 cache key
     */
    String SYS_CONFIG_KEY = "sys_config:";

    /**
     * 字典管理 cache key
     */
    String SYS_DICT_KEY = "sys_dict:";

    /**
     * 登录账户密码错误次数 redis key（V1，6.2 带租户前缀；已被 V2 取代，仅作过渡期清零兼容）
     */
    String PWD_ERR_CNT_KEY = "pwd_err_cnt:";

    /**
     * 登录账户密码错误次数 redis key V2（深挖审核 Q7，2026-09-27）：
     * 计数改 INCR 原子自增——V1 的 JSON 字符串值与 RAtomicLong 不兼容，必须换段；
     * 旧 key TTL 最长 lockTime 分钟自然过期，零迁移
     */
    String PWD_ERR_CNT_V2_KEY = "pwd_err_cnt2:";

}
