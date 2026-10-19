package org.dromara.assistant.service;

import lombok.RequiredArgsConstructor;
import org.dromara.assistant.domain.DcsAssistantFavorite;
import org.dromara.assistant.mapper.AssistantFavoriteMapper;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 助手常用问句收藏服务（C2 收藏跨设备，2026-10-07）。
 *
 * <p>用户级私有（查询/增删一律 tenant+user 双谓词）；上限 6 条与前端 chips 同口径；
 * 内容上限 200 字符（DB 列宽一致，超长前置拒绝）。物理删（无回收价值）。</p>
 */
@Service
@RequiredArgsConstructor
public class AssistantFavoriteService {

    /** 上限（与前端 favStore MAX 一致；改=前后端同步） */
    public static final int MAX = 6;
    /** 内容上限（=dcs_assistant_favorite.content 列宽） */
    public static final int MAX_LEN = 200;

    private final AssistantFavoriteMapper favoriteMapper;

    /** 用户收藏清单（新→旧） */
    public List<String> list(String tenantId, Long userId) {
        return favoriteMapper.listByUser(tenantId, userId)
            .stream().map(DcsAssistantFavorite::getContent).toList();
    }

    /** 新增（去重+上限 6+长度校验；返回最新清单，前端免二次拉取） */
    public List<String> add(String tenantId, Long userId, String content) {
        String t = content == null ? "" : content.trim();
        if (t.isEmpty()) {
            throw new ServiceException("收藏内容为空");
        }
        if (t.length() > MAX_LEN) {
            throw new ServiceException("收藏内容超过 " + MAX_LEN + " 字，换短一点的问法吧");
        }
        List<DcsAssistantFavorite> cur = favoriteMapper.listByUser(tenantId, userId);
        if (cur.size() >= MAX) {
            throw new ServiceException("最多收藏 " + MAX + " 条，先删一条再收");
        }
        if (cur.stream().anyMatch(f -> f.getContent().equals(t))) {
            return cur.stream().map(DcsAssistantFavorite::getContent).toList();
        }
        DcsAssistantFavorite row = new DcsAssistantFavorite();
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setContent(t);
        row.setCreateBy(userId);
        favoriteMapper.insert(row);
        return list(tenantId, userId);
    }

    /** 删除（返回最新清单） */
    public List<String> remove(String tenantId, Long userId, String content) {
        favoriteMapper.deleteByContent(tenantId, userId, content);
        return list(tenantId, userId);
    }
}
