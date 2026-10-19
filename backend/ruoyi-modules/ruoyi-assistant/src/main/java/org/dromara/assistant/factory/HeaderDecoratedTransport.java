package org.dromara.assistant.factory;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 请求头装饰传输层（2026-10-08 OPENCODE 接入）。
 *
 * <p>OpenCode Go 网关官方要求（opencode.ai/docs/go）：①自报身份的 User-Agent
 * （勿用通用 SDK 名，网关按客户端识别做路由）；②每会话稳定的 x-opencode-session
 * 头（服务端做会话路由与 prompt 缓存优化）。本装饰器包装底层 HttpTransport，
 * 在 execute/stream 前注入固定头，其余行为原样透传——非 OPENCODE 厂商不包装，零影响。</p>
 *
 * <p>资源口径（三审 P2-1）：delegate 的生命周期归构造方（现用共享单例，
 * 见 AssistantModelFactory.OPENCODE_DELEGATE），close() 为 no-op 不下传——
 * 防共享 client 被某个装饰器提前关闭。</p>
 */
public class HeaderDecoratedTransport implements HttpTransport {

    private final HttpTransport delegate;
    private final Map<String, String> extraHeaders;

    public HeaderDecoratedTransport(HttpTransport delegate, Map<String, String> extraHeaders) {
        this.delegate = delegate;
        this.extraHeaders = Map.copyOf(extraHeaders);
    }

    private HttpRequest decorate(HttpRequest request) {
        Map<String, String> headers = new HashMap<>(request.getHeaders() == null ? Map.of() : request.getHeaders());
        headers.putAll(extraHeaders);
        return HttpRequest.builder()
            .url(request.getUrl())
            .method(request.getMethod())
            .headers(headers)
            .body(request.getBody())
            .build();
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        return delegate.execute(decorate(request));
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        return delegate.stream(decorate(request));
    }

    @Override
    public void close() {
        // no-op：delegate 归构造方持有（共享单例），装饰器不拥有其生命周期
    }
}
