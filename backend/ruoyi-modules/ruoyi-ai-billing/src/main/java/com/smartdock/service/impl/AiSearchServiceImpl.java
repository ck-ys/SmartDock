package com.smartdock.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.smartdock.domain.AiSearchService;
import com.smartdock.domain.vo.AiSearchBalanceVo;
import com.smartdock.domain.vo.AiSearchProviderVo;
import com.smartdock.domain.vo.AiSearchServiceVo;
import com.smartdock.mapper.AiSearchServiceMapper;
import com.smartdock.service.IAiSearchService;
import com.smartdock.service.support.SearchKeyPool;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 联网搜索服务实现（额度三级管控批 Q16=1，2026-11-26）。
 *
 * <p><b>账户余额 vs 资源包次数是两本账</b>（板上点名的坑）：现在这家企业的
 * 「账户余额 ￥0.00」和「资源包 949 次」可以同时成立，所以界面必须分两列显示，
 * 不许把次数换算成金额硬比。资源包厂商没有查询接口，只能估算（本地累计调用次数）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSearchServiceImpl implements IAiSearchService {

    /** 博查余额接口（官方文档：GET /v1/fund/remaining → data.remaining，单位元） */
    private static final String BOCHA_BALANCE_URL = "https://api.bocha.cn/v1/fund/remaining";
    private static final String PROVIDER_BOCHA = "BOCHA";
    private static final int TIMEOUT_MS = 8000;

    private final AiSearchServiceMapper searchMapper;

    /** 搜索钥匙池（多把轮询 + 冷却 + 零余额跳过；Q3=1）。@Nullable 便于纯单测不启 Spring */
    private final SearchKeyPool searchKeyPool;

    @Override
    public List<AiSearchServiceVo> list(String tenantId) {
        if (StrUtil.isBlank(tenantId)) {
            return List.of();
        }
        List<AiSearchService> rows = TenantHelper.ignore(() -> searchMapper.selectList(
            new LambdaQueryWrapper<AiSearchService>()
                .eq(AiSearchService::getTenantId, tenantId)
                .eq(AiSearchService::getDelFlag, "0")
                .orderByAsc(AiSearchService::getId)));
        List<AiSearchServiceVo> out = new ArrayList<>(rows.size());
        for (AiSearchService row : rows) {
            AiSearchServiceVo vo = new AiSearchServiceVo();
            vo.setId(row.getId());
            vo.setTenantId(row.getTenantId());
            vo.setProvider(row.getProvider());
            vo.setLabel(row.getLabel());
            vo.setEnabled(row.getEnabled());
            // 加密列不外泄：出参只给掩码（与钥匙管理页同口径）
            vo.setApiKeyMask(maskKey(row.getApiKey()));
            vo.setHasKey(row.getApiKey() != null && !row.getApiKey().isBlank());
            vo.setConsoleUrl(row.getConsoleUrl());
            vo.setBalanceAmount(row.getBalanceAmount());
            vo.setBalanceCurrency(row.getBalanceCurrency());
            vo.setBalanceCheckedAt(row.getBalanceCheckedAt());
            vo.setBalanceStatus(row.getBalanceStatus());
            vo.setPackTotal(row.getPackTotal());
            vo.setPackUsed(row.getPackUsed());
            vo.setPackExpireAt(row.getPackExpireAt());
            out.add(vo);
        }
        return out;
    }

    /** key 掩码：只露前 6 后 4（太短就整体打码）——与钥匙管理页的展示口径一致 */
    static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String t = key.trim();
        if (t.length() <= 12) {
            return "****";
        }
        return t.substring(0, 6) + "****" + t.substring(t.length() - 4);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiSearchService save(String tenantId, AiSearchService bo, Long operatorId) {
        if (StrUtil.isBlank(tenantId)) {
            throw new ServiceException("租户不能为空", 400);
        }
        String provider = StrUtil.blankToDefault(bo.getProvider(), PROVIDER_BOCHA);
        if (bo.getPackTotal() != null && bo.getPackTotal() < 0) {
            throw new ServiceException("资源包总次数不能是负数", 400);
        }
        return TenantHelper.ignore(() -> {
            // 多把钥匙（2026-11-26 Q2=1）：不再按 tenant+provider 幂等——同一厂商可挂多把。
            // 定位唯一一行的依据改成 id：id 为空=新建一条，id 有值=更新那一条。
            AiSearchService existing = bo.getId() == null ? null : searchMapper.selectActiveById(bo.getId());
            if (existing == null) {
                AiSearchService row = new AiSearchService();
                row.setId(IdWorker.getId());
                row.setTenantId(tenantId);
                row.setProvider(provider);
                row.setLabel(bo.getLabel());
                row.setEnabled(StrUtil.blankToDefault(bo.getEnabled(), "1"));
                row.setApiKey(bo.getApiKey());
                row.setConsoleUrl(bo.getConsoleUrl());
                row.setPackTotal(bo.getPackTotal());
                row.setPackUsed(0);
                row.setPackExpireAt(bo.getPackExpireAt());
                row.setRemark(bo.getRemark());
                row.setDelFlag("0");
                row.setCreateBy(operatorId);
                row.setCreateTime(new Date());
                searchMapper.insert(row);
                return row;
            }
            // 更新：key 留空=不改（与钥匙管理的「留空不覆盖」同口径）
            // 越权守卫：id 命中别的租户的行时不许改（超管可跨租户，与 requireOwned 同口径）
            if (!existing.getTenantId().equals(tenantId) && !LoginHelper.isSuperAdmin()) {
                throw new ServiceException("无权操作其他租户的搜索服务", 403);
            }
            existing.setLabel(bo.getLabel());
            if (StrUtil.isNotBlank(bo.getApiKey())) {
                existing.setApiKey(bo.getApiKey());
            }
            existing.setConsoleUrl(bo.getConsoleUrl());
            existing.setPackTotal(bo.getPackTotal());
            // 管理员把总量改小到已用量以下时，把已用也压到总量（否则出现「用了 1200 / 1000」）
            if (bo.getPackTotal() != null && existing.getPackUsed() != null
                && existing.getPackUsed() > bo.getPackTotal()) {
                existing.setPackUsed(bo.getPackTotal());
            }
            existing.setPackExpireAt(bo.getPackExpireAt());
            existing.setRemark(bo.getRemark());
            existing.setUpdateBy(operatorId);
            existing.setUpdateTime(new Date());
            searchMapper.updateById(existing);
            return existing;
        });
    }

    @Override
    public void changeStatus(String tenantId, Long id, String enabled) {
        requireOwned(tenantId, id);
        TenantHelper.ignore(() -> {
            AiSearchService row = new AiSearchService();
            row.setId(id);
            row.setEnabled("1".equals(enabled) ? "1" : "0");
            searchMapper.updateById(row);
            return null;
        });
    }

    @Override
    public void remove(String tenantId, Long id) {
        requireOwned(tenantId, id);
        TenantHelper.ignore(() -> {
            searchMapper.deleteById(id);
            return null;
        });
    }

    @Override
    public AiSearchBalanceVo refreshBalance(String tenantId, Long id) {
        AiSearchService row = requireOwned(tenantId, id);
        if (!PROVIDER_BOCHA.equalsIgnoreCase(row.getProvider())) {
            // 其他厂商的查询方式不一样（金额 / 次数 / 百分比 / 根本查不到）——查不到就说查不到
            markBalance(row, null, "UNSUPPORTED");
            return balanceVo("UNSUPPORTED", null, "这家厂商还没接入余额查询，去它的控制台看");
        }
        String key = row.getApiKey();
        if (StrUtil.isBlank(key)) {
            markBalance(row, null, "UNSUPPORTED");
            return balanceVo("UNSUPPORTED", null, "还没填密钥，查不了余额");
        }
        try (HttpResponse hr = HttpRequest.get(BOCHA_BALANCE_URL)
            .header("Authorization", "Bearer " + key.trim())
            .timeout(TIMEOUT_MS)
            .execute()) {
            String body = hr.body();
            if (hr.getStatus() != 200) {
                markBalance(row, null, "ERROR");
                log.warn("[SearchService] 余额查询非 200: {} body={}", hr.getStatus(),
                    body == null ? "" : body.substring(0, Math.min(body.length(), 200)));
                return balanceVo("ERROR", null, "查询失败（HTTP " + hr.getStatus() + "），请稍后再试");
            }
            // 结构：{ code: 200, data: { remaining: "12.34" } }——解析不到就按 ERROR，不编数
            BalanceParse parsed = parseBalanceBody(body);
            if (parsed.status() != BalanceStatus.OK) {
                markBalance(row, null, parsed.status().name());
                return balanceVo(parsed.status().name(), null, parsed.message());
            }
            markBalance(row, parsed.amount(), "OK");
            return balanceVo("OK", parsed.amount(), null);
        } catch (Exception e) {
            markBalance(row, null, "ERROR");
            log.warn("[SearchService] 余额查询异常 id={}", id, e);
            return balanceVo("ERROR", null, "查询失败：" + e.getMessage());
        }
    }

    /** 查余额结果出参（三态；非 OK 时 amount 恒为 null——绝不拿 0 顶替） */
    private static AiSearchBalanceVo balanceVo(String status, BigDecimal amount, String message) {
        AiSearchBalanceVo vo = new AiSearchBalanceVo();
        vo.setStatus(status);
        vo.setBalanceAmount(amount);
        vo.setBalanceCurrency(amount == null ? null : "CNY");
        vo.setMessage(message);
        return vo;
    }

    /** 余额快照落行：查不到时 amount=null（保留旧值会让界面显示一个过期数当真值） */
    private void markBalance(AiSearchService row, BigDecimal amount, String status) {
        TenantHelper.ignore(() -> {
            AiSearchService patch = new AiSearchService();
            patch.setId(row.getId());
            patch.setBalanceAmount(amount);
            patch.setBalanceCurrency(amount == null ? null : "CNY");
            patch.setBalanceStatus(status);
            patch.setBalanceCheckedAt(new Date());
            searchMapper.updateById(patch);
            return null;
        });
    }

    /* ===== 余额探测解析（Q16 单列一类，板 07 末尾）：纯函数，单测直调 ===== */

    /** 探测结论三态：OK=拿到钱数 / UNSUPPORTED=这家没法查 / ERROR=查了但失败 */
    public enum BalanceStatus { OK, UNSUPPORTED, ERROR }

    /**
     * 解析结论。{@code amount} 只在 OK 时有值；其余为 null——
     * <b>绝不拿 0 顶替</b>（0 会被读成「没钱了」）。
     */
    public record BalanceParse(BalanceStatus status, BigDecimal amount, String message) {
    }

    /**
     * 博查余额响应解析（纯函数）：{@code { code:200, data:{ remaining:"12.34" } }} → OK + 金额。
     *
     * <p>三条失败路径各自给人话，别混成一句「失败」：响应不是 JSON / 缺 data.remaining /
     * remaining 不是数字。金额为负也照收（欠费是真实状态，抹成 0 就是假账）。</p>
     */
    public static BalanceParse parseBalanceBody(String body) {
        if (body == null || body.isBlank()) {
            return new BalanceParse(BalanceStatus.ERROR, null, "厂商返回了空响应");
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            return new BalanceParse(BalanceStatus.ERROR, null, "厂商返回的不是 JSON，请核对接口是否变了");
        }
        com.fasterxml.jackson.databind.JsonNode remaining = root.path("data").path("remaining");
        if (remaining.isMissingNode() || remaining.isNull()) {
            return new BalanceParse(BalanceStatus.ERROR, null, "响应里没有 remaining 字段，请核对厂商接口是否变了");
        }
        String text = remaining.asText("").trim();
        try {
            return new BalanceParse(BalanceStatus.OK, new BigDecimal(text), null);
        } catch (NumberFormatException e) {
            return new BalanceParse(BalanceStatus.ERROR, null, "remaining 不是数字（" + text + "）");
        }
    }

    /**
     * 资源包剩余次数（纯函数，界面「包 949/1000 · 估算」用）：总量 − 系统累计调用，下限 0。
     *
     * <p>总量为 null = 没买包，返回 null（界面显示「—」而不是 0/0）。
     * 已用量为 null 当 0（新接入还没搜过）。<b>跨系统调用算不到</b>，所以界面必须标「估算」。</p>
     */
    public static Integer packRemaining(Integer packTotal, Integer packUsed) {
        if (packTotal == null) {
            return null;
        }
        int used = packUsed == null ? 0 : Math.max(0, packUsed);
        return Math.max(0, packTotal - used);
    }

    @Override
    public void countSearch(String tenantId, String provider) {
        String p = StrUtil.blankToDefault(provider, PROVIDER_BOCHA);
        try {
            // Q4=1：只给「这次实际用的那把」减 1——拿钥匙池记下的 lastPickedId。
            // 拿不到（池空/没走池）就不记：宁可少记一次估算，也不能把所有行都减一遍。
            Long pickedId = searchKeyPool == null ? null : searchKeyPool.lastPickedId(tenantId, p);
            if (pickedId == null) {
                log.debug("[SearchService] 没有本次使用的钥匙 id，跳过资源包计数 tenant={} provider={}",
                    tenantId, p);
                return;
            }
            TenantHelper.ignore(() -> searchMapper.incPackUsedById(pickedId));
        } catch (Exception e) {
            // 估计算不准不影响本次搜索——记 WARN 就够（板上口径：以厂商后台为准）
            log.warn("[SearchService] 资源包估算计数失败 tenant={} provider={}", tenantId, provider, e);
        }
    }

    @Override
    public String resolveApiKey(String tenantId, String provider) {
        String p = StrUtil.blankToDefault(provider, PROVIDER_BOCHA);
        try {
            // Q3=1：走钥匙池——多把轮询 + 冷却跳过 + 零余额跳过。
            // 池空（没建行/查询失败）返回 null，调用方回落到系统参数（存量租户零影响）。
            AiSearchService row = searchKeyPool == null ? null : searchKeyPool.pick(tenantId, p);
            if (row == null) {
                return null;
            }
            String key = row.getApiKey();
            return StrUtil.isBlank(key) ? null : key;
        } catch (Exception e) {
            log.warn("[SearchService] 搜索 key 读取失败 tenant={}", tenantId, e);
            return null;
        }
    }

    /**
     * 搜索调用成功/失败回报（Q3=1）：让钥匙池清冷却 / 按错误码冷却。
     * 调用方是工具层，拿不到异常细节时传 null 即视为成功。
     */
    @Override
    public void reportSearchResult(String tenantId, String provider, Throwable error) {
        if (searchKeyPool == null) {
            return;
        }
        String p = StrUtil.blankToDefault(provider, PROVIDER_BOCHA);
        try {
            if (error == null) {
                searchKeyPool.reportSuccess(tenantId, p);
            } else {
                searchKeyPool.reportFailure(tenantId, p, error);
            }
        } catch (Exception e) {
            log.warn("[SearchService] 搜索钥匙池回报失败 tenant={} provider={}", tenantId, p, e);
        }
    }

    @Override
    public List<AiSearchProviderVo> providers() {
        AiSearchProviderVo bocha = new AiSearchProviderVo();
        bocha.setProvider(PROVIDER_BOCHA);
        bocha.setName("博查 Bocha");
        bocha.setConsoleUrl("https://open.bochaai.com/");
        bocha.setBalanceSupported(true);
        bocha.setUnitPrice("按量 ￥0.036 / 次；体验包 ￥3.6 / 1000 次");
        return List.of(bocha);
    }

    /** 行归属守卫：必须存在且属于本租户（超管可跨租户——与钥匙管理同口径） */
    private AiSearchService requireOwned(String tenantId, Long id) {
        AiSearchService row = TenantHelper.ignore(() -> searchMapper.selectById(id));
        if (row == null || !"0".equals(row.getDelFlag())) {
            throw new ServiceException("搜索服务行不存在或已删除", 404);
        }
        if (!LoginHelper.isSuperAdmin() && !row.getTenantId().equals(tenantId)) {
            throw new ServiceException("无权操作其他租户的搜索服务", 403);
        }
        return row;
    }
}
