package com.smartdock.service;

import com.smartdock.domain.bo.DcsAiProviderAccessBo;
import com.smartdock.domain.vo.DcsAiProviderAccessVo;

import java.util.List;

/**
 * 厂商接入层 Service 接口（批B，2026-10-09 多模型改造；AI 缺口批 Q8 2026-10-14 钥匙池扩展）。
 *
 * <p>钥匙真源：每租户每厂商<b>一或多行</b>（Q8 放开唯一约束），运行时轮询选路
 * （ProviderKeyPool），探测类端点固定主钥匙（enabled 按 id 升序第一行）。</p>
 *
 * @author smartdock
 */
public interface IDcsAiProviderAccessService {

    /**
     * 查询某租户某厂商的接入（主钥匙行；不存在返回 null，前端据此显示「首次配置需填 Key」形态）。
     */
    DcsAiProviderAccessVo getAccess(String tenantId, String provider);

    /**
     * 新增/编辑接入（upsert 语义，兼容批B 旧表单：bo.id 空=编辑主钥匙行/无行则新建）。
     *
     * <p>apiKey 留空/传 **** = 沿用原密钥（新建时必须给钥匙）。编辑走跨租户访问守卫
     * （同 ensureCanAccessAiConfig 口径：本租户或超管）。</p>
     *
     * @param bo 接入业务对象
     * @return 更新后的接入（掩码出参），affectedModelCount=本次变更已覆盖的模型行数
     */
    DcsAiProviderAccessVo updateAccess(DcsAiProviderAccessBo bo);

    /**
     * Q8 补遗（2026-10-15 管理钥匙 UI 实测）：显式新增一把钥匙——upsert 的「无 id=编辑主钥匙」
     * 兼容语义让 UI「添加新钥匙」成了死路径（永远编辑首行，插不进第二把）。本方法绕开兼容
     * 分支强制 insert：必须给真 API Key；label 缺省自动编号；enabled 缺省启用。
     *
     * @param bo 接入业务对象（id 忽略）
     * @return 新增后的钥匙行（掩码出参）
     */
    DcsAiProviderAccessVo addKey(DcsAiProviderAccessBo bo);

    /**
     * Q8 钥匙列表（掩码+启停+主钥匙标记+余额快照）。
     *
     * @param tenantId 租户编号（守卫：本租户或超管）
     * @param provider 模型厂商
     * @return 全部未删钥匙行（含 disabled），按 id 升序（首行=主钥匙）
     */
    List<DcsAiProviderAccessVo> listKeys(String tenantId, String provider);

    /**
     * Q8 钥匙启停（禁用≠删除：保留历史与余额快照，选路跳过）。
     * 守卫：不能禁用该厂商最后一把 enabled 钥匙（会切断全部模型调用）。
     */
    void changeKeyStatus(Long id, String enabled);

    /**
     * Q8 删除钥匙（软删）。守卫同启停：不能删最后一把 enabled 钥匙。
     */
    void deleteKey(Long id);

    /**
     * Q8 单钥匙余额刷新：向厂商余额端点实查（按钥匙行指定的那把 key）并落快照。
     *
     * <p>余额是「钥匙（账号）」的属性——多把钥匙各自一个厂商侧账户。不支持余额接口的
     * 厂商返回 UNSUPPORTED 状态（此后定时刷新零外呼）；探测异常标 FAILED 保留旧快照。</p>
     *
     * @param tenantId 操作者租户（守卫：本租户或超管；行归属真实租户）
     * @param id 钥匙行主键
     * @return 刷新后的行（掩码出参，含快照）
     */
    DcsAiProviderAccessVo refreshBalance(String tenantId, Long id);

    /**
     * Q8 整厂钥匙余额批量刷新（手动「全部刷新」）：逐把探测落快照，单把失败不断整批。
     * UNSUPPORTED 行跳过零外呼。
     */
    List<DcsAiProviderAccessVo> refreshAllBalances(String tenantId, String provider);

    /**
     * Q8 定时余额刷新（每日 04:30）：全库 enabled 且非 UNSUPPORTED 钥匙串行探测
     * （每把间隔 ≥500ms 防厂商限流），失败标 FAILED 保留旧值。调度线程无登录态。
     */
    void scheduledBalanceRefresh();
}
