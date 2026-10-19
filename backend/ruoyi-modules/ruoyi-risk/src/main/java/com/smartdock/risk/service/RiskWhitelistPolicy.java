package com.smartdock.risk.service;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import com.smartdock.risk.domain.DcsRiskWhitelist;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;

/**
 * Backend-authoritative validation for risk whitelist entries.
 */
@Component
public class RiskWhitelistPolicy {

    public static final String TYPE_IP = "IP";
    public static final String TYPE_USER = "USER";

    public void prepareCreate(DcsRiskWhitelist entry) {
        entry.setWhitelistId(null);
        entry.setTenantId(null);
        entry.setCreateDept(null);
        entry.setCreateBy(null);
        entry.setCreateTime(null);
        entry.setUpdateBy(null);
        entry.setUpdateTime(null);

        entry.setListType(StrUtil.trim(entry.getListType()).toUpperCase(Locale.ROOT));
        entry.setValue(StrUtil.trim(entry.getValue()));
        entry.setReason(StrUtil.trim(entry.getReason()));
        entry.setEnabled(StrUtil.blankToDefault(entry.getEnabled(), "1"));

        if (!TYPE_IP.equals(entry.getListType()) && !TYPE_USER.equals(entry.getListType())) {
            throw new ServiceException("白名单类型仅支持 IP/USER；DOMAIN 尚未接入聊天链接策略");
        }
        if (StrUtil.isBlank(entry.getValue()) || entry.getValue().length() > 200) {
            throw new ServiceException("白名单值不能为空且不能超过 200 个字符");
        }
        if (entry.getReason() != null && entry.getReason().length() > 200) {
            throw new ServiceException("白名单原因不能超过 200 个字符");
        }
        if (!"0".equals(entry.getEnabled()) && !"1".equals(entry.getEnabled())) {
            throw new ServiceException("启用状态只能是 0 或 1");
        }
        if (TYPE_IP.equals(entry.getListType()) && !isValidIp(entry.getValue())) {
            throw new ServiceException("IP 白名单值必须是有效的 IPv4 或 IPv6 地址");
        }
        if (TYPE_USER.equals(entry.getListType()) && !entry.getValue().matches("\\d{1,19}")) {
            throw new ServiceException("USER 白名单值必须是数字用户 ID");
        }
    }

    private boolean isValidIp(String value) {
        if (value.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            for (String part : value.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        if (!value.contains(":") || !value.matches("[0-9A-Fa-f:.]+")) {
            return false;
        }
        try {
            return InetAddress.getByName(value) instanceof Inet6Address;
        } catch (Exception ignored) {
            return false;
        }
    }
}
