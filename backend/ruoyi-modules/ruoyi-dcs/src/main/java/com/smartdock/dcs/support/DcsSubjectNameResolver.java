package com.smartdock.dcs.support;

import com.smartdock.dcs.mapper.DcsUserLookupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 画像事件主体显示名解析: DCS 事件只带 userId, 画像主体列表需要可读名称。
 *
 * <p>只读 sys_user 的 nick_name/user_name; 查不到或异常时返回 null,
 * 由画像引擎保留旧值(upsert 的 COALESCE 语义), 不影响事件写入。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DcsSubjectNameResolver {

    private final DcsUserLookupMapper userLookupMapper;

    public String resolve(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = userLookupMapper.selectBriefsByIds(List.of(userId));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            String name = firstText(row, "nick_name", "nickName");
            if (StringUtils.isBlank(name)) {
                name = firstText(row, "user_name", "userName");
            }
            return StringUtils.isBlank(name) ? null : name;
        } catch (Exception e) {
            // 名称是展示元数据, 不值得阻断事件; 下一条事件会再尝试
            log.debug("[Dcs] subject name resolve failed user={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private String firstText(Map<String, Object> row, String first, String second) {
        Object v = row.get(first);
        if (v == null) {
            v = row.get(second);
        }
        return v == null ? null : String.valueOf(v);
    }
}
