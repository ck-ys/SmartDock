package com.smartdock.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.ratelimiter.annotation.RateLimiter;
import org.dromara.common.ratelimiter.enums.LimitType;
import org.dromara.common.web.core.BaseController;
import com.smartdock.service.support.FaviconProxyService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.net.URLConnection;

/**
 * 业务反馈批1015c Q6：搜索来源 favicon 代理端点。
 *
 * <p>对话页所有登录用户消费，故不加 @SaCheckPermission。<b>三审 P0-1 修正（2026-10-15）：
 * 前端用 &lt;img&gt; 标签加载，带不了 sa-token 鉴权头——本路径已加入 security.excludes
 * 匿名放行，配套 IP 限流 60 次/分兜底防刷。</b>图标 miss 返回 404——前端 img onerror
 * 回退域名首字母色块（内网环境常态路径）。</p>
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/ai/favicon")
public class AiFaviconController extends BaseController {

    private final FaviconProxyService faviconProxyService;

    /** 匿名可达，必须限流（三审 P1-2）：按 IP 120 次/分（glm53 P2：学校 NAT 全班共 IP，
     *  60/分会被一张搜索回复的十几个来源瞬间打满整体退化字母块；120 仍是防刷量级） */
    @RateLimiter(time = 60, count = 120, limitType = LimitType.IP)
    @GetMapping
    public ResponseEntity<byte[]> favicon(@RequestParam("host") String host) {
        byte[] data = faviconProxyService.fetch(host);
        if (data == null || data.length == 0) {
            return ResponseEntity.notFound().build();
        }
        // 图标内容天然不可变（同 host 同图），让浏览器缓存 7 天与 Redis TTL 对齐，
        // 免去每次挂载来源卡都回源（dspro P2-7）
        String mime;
        try {
            String guessed = URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(data));
            mime = guessed != null ? guessed : "image/x-icon";
        } catch (Exception e) {
            mime = "image/x-icon";
        }
        return ResponseEntity.ok()
            .header("Cache-Control", "public, max-age=604800")
            .contentType(MediaType.parseMediaType(mime)).body(data);
    }
}
