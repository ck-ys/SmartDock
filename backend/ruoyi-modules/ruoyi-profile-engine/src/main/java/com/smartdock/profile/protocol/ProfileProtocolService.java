package com.smartdock.profile.protocol;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartdock.profile.mapper.ProfileProtocolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 画像接入协议服务: 校验、发布、当前版本、历史、导出。
 *
 * <p>发布是不可变动作: 同 source 旧 PUBLISHED 先归档, 新版本写入 manifest 快照与
 * checksum; 发布后只能新建版本, 不能原地改。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileProtocolService {

    private final ProfileProtocolMapper protocolMapper;

    public ProfileProtocol current(String source) {
        return protocolMapper.selectCurrent(currentTenant(), source);
    }

    public List<ProfileProtocol> history(String source) {
        return protocolMapper.selectHistory(currentTenant(), source);
    }

    public List<String> validate(Map<String, Object> manifest) {
        return ProfileManifestValidator.validate(manifest);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileProtocol publish(String source, String version, Map<String, Object> manifest, Long userId) {
        if (StringUtils.isBlank(source)) {
            throw new ServiceException("协议 source 不能为空");
        }
        if (source.length() > 32) {
            throw new ServiceException("协议 source 长度不能超过 32");
        }
        List<String> errors = validate(manifest);
        if (!errors.isEmpty()) {
            throw new ServiceException("协议校验失败: " + String.join("; ", errors));
        }
        String effectiveVersion = StringUtils.isBlank(version) ? metadataVersion(manifest) : version.trim();
        if (StringUtils.isBlank(effectiveVersion)) {
            throw new ServiceException("协议版本不能为空");
        }
        if (effectiveVersion.length() > 32) {
            throw new ServiceException("协议版本长度不能超过 32");
        }
        String tenantId = currentTenant();
        String json = JsonUtils.toJsonString(manifest);

        protocolMapper.archivePublished(tenantId, source);

        ProfileProtocol protocol = new ProfileProtocol();
        protocol.setProtocolId(IdWorker.getId());
        protocol.setTenantId(tenantId);
        protocol.setSource(source);
        protocol.setVersion(effectiveVersion);
        protocol.setStatus("PUBLISHED");
        protocol.setManifest(json);
        protocol.setChecksum(sha256(json));
        protocol.setPublishedAt(new Date());
        protocol.setPublishedBy(userId);
        protocol.setCreateBy(userId);
        protocolMapper.insertJsonb(protocol);
        log.info("[Profile] protocol published source={} version={} checksum={}",
            source, effectiveVersion, protocol.getChecksum());
        return protocol;
    }

    public String export(String source) {
        ProfileProtocol current = current(source);
        if (current == null) {
            throw new ServiceException("该 source 尚未发布画像协议: " + source);
        }
        return current.getManifest();
    }

    private String metadataVersion(Map<String, Object> manifest) {
        Object metadata = manifest.get("metadata");
        if (metadata instanceof Map<?, ?> map && map.get("version") != null) {
            return String.valueOf(map.get("version")).trim();
        }
        return null;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String currentTenant() {
        try {
            String t = TenantHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        try {
            String t = LoginHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 非登录上下文
        }
        return com.smartdock.profile.engine.ProfileTenantContext.require();
    }
}
