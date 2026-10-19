package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.domain.DcsAiCreditLedger;

import java.util.List;
import java.util.Map;

/**
 * AI 积分流水 Mapper（五需求批 R5）。
 *
 * <p>类级 {@code @InterceptorIgnore(tenantLine="true")}：计费装饰器在反应式线程落账
 * 无登录租户上下文，全部谓词显式带 tenantId（钱包 Mapper 同款口径）。</p>
 */
@InterceptorIgnore(tenantLine = "true")
public interface DcsAiCreditLedgerMapper extends BaseMapperPlus<DcsAiCreditLedger, DcsAiCreditLedger> {

    /**
     * DEDUCT 行按来源列求和（一次查询拿周池/窗口两本账共用）：since 起的
     * weekly_credits 与 tank_credits 总量 + 窗口内最早一笔时间（窗口恢复时刻的锚点，
     * dspro P1-2：恒等式 now-(now-h) 永远算不出真实恢复时长）。周池=本周一 0 点起，窗口=now-5h 起。
     * 六审回修（dspro P1-2 续）：oldest 只锚 weekly 来源行——纯 tank 行（周池尽后走加油包）
     * 不限窗，混入锚点会把恢复时长话术低估到最长一个窗口（FILTER 须 PG9.4+）。
     */
    @Select("SELECT COALESCE(SUM(weekly_credits), 0) AS weekly, COALESCE(SUM(tank_credits), 0) AS tank,"
        + " MIN(create_time) FILTER (WHERE weekly_credits > 0) AS oldest"
        + " FROM dcs_ai_credit_ledger"
        + " WHERE tenant_id = #{tenantId} AND biz_type = 'DEDUCT' AND create_time >= #{since}")
    Map<String, Object> sumDeductSince(@Param("tenantId") String tenantId, @Param("since") java.util.Date since);

    /** 加油包累计充值总量（余额=充值-DEDUCT 行 tank 拆分） */
    @Select("SELECT COALESCE(SUM(credits), 0) FROM dcs_ai_credit_ledger"
        + " WHERE tenant_id = #{tenantId} AND biz_type = 'BOOST'")
    long sumBoostGranted(@Param("tenantId") String tenantId);

    /** 幂等键存在性（计费重放短路判定） */
    @Select("SELECT COUNT(*) FROM dcs_ai_credit_ledger WHERE idempotency_key = #{key}")
    int existsByIdempotencyKey(@Param("key") String key);

    /** 近 N 条流水（用户侧明细弹层）。
     *  六审回修（glm53flash 视觉）：create_time 与雪花 id 在补数据/时钟修正场景可能脱序，
     *  对账视图按业务时间排——id 仅作次序稳定器 */
    @Select("SELECT * FROM dcs_ai_credit_ledger WHERE tenant_id = #{tenantId} ORDER BY create_time DESC, id DESC LIMIT #{limit}")
    List<DcsAiCreditLedger> recentOf(@Param("tenantId") String tenantId, @Param("limit") int limit);

    /**
     * 幂等插入（六审回修 dspro P0-1 方案A 前置）：deduct 并入 usage 落账事务后，
     * 不能再用 catch(DuplicateKeyException) 吸收重放——PG 同事务内唯一冲突会把事务置
     * aborted，usage 插入也随之 25P02 回滚。改 ON CONFLICT 消化（须带部分索引的
     * WHERE 谓词才能命中 uk_credit_ledger_idem）。返回 0=重放被吸收。
     */
    @Insert("INSERT INTO dcs_ai_credit_ledger (id, tenant_id, biz_type, credits, weekly_credits, tank_credits,"
        + " scene, remark, idempotency_key, create_by, create_time)"
        + " VALUES (#{id}, #{tenantId}, #{bizType}, #{credits}, #{weeklyCredits}, #{tankCredits},"
        + " #{scene}, #{remark}, #{idempotencyKey}, #{createBy}, #{createTime})"
        + " ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING")
    int insertIgnoreIdem(DcsAiCreditLedger row);

    /**
     * 业务批8 Q6 双视角流水：批量查用户昵称（消耗人列）。sys_user 跨租户查询——
     * 本 Mapper 类级 @InterceptorIgnore(tenantLine) 使本方法不受租户插件拦截；
     * 不过滤 del_flag（已删用户的流水也要能显示是谁）。
     * E2E 实锤修复（2026-10-14）：PG 把不带引号的别名折成小写（userId→userid），
     * nickNameMap 按 "userId" 取键恒 null，消耗人列全程回退「用户 N」——别名必须加引号。
     */
    @Select("<script>SELECT user_id AS \"userId\", nick_name AS \"nickName\" FROM sys_user"
        + " WHERE user_id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<Map<String, Object>> selectNickNames(@Param("ids") java.util.Collection<Long> ids);

    /** 业务批8 Q6：近 since 起该租户有流水的操作人（按人筛选下拉数据源；dspro P2-6 上限防大租户全量） */
    @Select("SELECT DISTINCT l.create_by FROM dcs_ai_credit_ledger l"
        + " WHERE l.tenant_id = #{tenantId} AND l.create_by IS NOT NULL AND l.create_time >= #{since}"
        + " LIMIT 200")
    List<Long> distinctOperators(@Param("tenantId") String tenantId, @Param("since") java.util.Date since);
}
