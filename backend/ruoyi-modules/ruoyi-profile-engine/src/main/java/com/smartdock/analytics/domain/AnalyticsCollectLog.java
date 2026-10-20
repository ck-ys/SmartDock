package com.smartdock.analytics.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 采集丢弃留痕（批 7）：按（批次 × 原因）聚合一行，爬虫灌水不会把日志表灌成新攻击面。
 *
 * <p>drop_reason 口径：robot（机器人整批拒）/ domain_mismatch（跨域整批拒）/
 * oversize（单批超限拒）/ duplicate（eventUid 幂等跳过）/ schema_invalid（逐事件校验失败）。
 * 明文 IP 不落库，只存 SHA-256 前 16 位哈希用于分组排查。</p>
 *
 * @author smartdock
 */
@Data
@TableName("analytics_collect_log")
public class AnalyticsCollectLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "log_id", type = IdType.ASSIGN_ID)
    private Long logId;

    private String tenantId;

    private String source;

    private String siteKey;

    /**
     * robot / domain_mismatch / oversize / duplicate / schema_invalid
     */
    private String dropReason;

    private Integer eventCount;

    private String ipHash;

    private String userAgent;

    private String originHost;

    private String detail;

    private Date createTime;
}
