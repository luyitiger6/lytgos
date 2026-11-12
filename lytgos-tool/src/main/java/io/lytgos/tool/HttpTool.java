package io.lytgos.tool;

import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.tool.Tool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 内置 {@code http_get}：让 Agent 能读网页与调用公开 API。
 *
 * <p>三个刻意的限制：</p>
 * <ul>
 *   <li><b>不跟随重定向</b>——白名单校验发生在请求发出前，一旦跟随 302，
 *       最终落点就脱离了校验范围。宁可让模型看到 302 自己再发一次。</li>
 *   <li><b>只看域名不看 IP</b>——域名解析到内网地址（DNS rebinding）这一层没挡，
 *       第 1 周接受这个风险；企业场景要补自定义 Resolver，记在这里而不是假装不存在。</li>
 *   <li><b>响应截断</b>——一个网页可以有几 MB，直接塞给模型既烧 token 又挤掉上下文。</li>
 * </ul>
 */
public final class HttpTool implements Tool {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 回填给模型的最大字符数。超出部分截断并注明，让模型知道内容不完整。 */
    private static final int MAX_RESPONSE_CHARS = 4000;

    private final SandboxChecker sandbox;
    private final HttpClient client;

    public HttpTool(SandboxChecker sandbox) {
        this.sandbox = sandbox;
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ToolSpec spec() {
        // 白名单写进描述：模型不知道能用哪些域名就只能猜。
        // 实测直接问「北京天气」时它猜了 wttr.in，被沙箱拒后整轮中止——
        // 治理上是对的（不能给它试错探测的空间），但代价是本次对话白跑。
        // 把规则提前告诉它，才是既不放弃拦截也不浪费轮次的做法。
        Set<String> allowed = sandbox.allowedDomains();
        String scope = allowed.isEmpty()
                ? "当前未配置任何允许的域名，调用一定失败。"
                : "只允许访问以下域名，其他一律被沙箱拒绝："
                        + String.join(", ", allowed.stream().sorted().toList()) + "。";
        return new ToolSpec(
                "http_get",
                "发送 HTTP GET 请求并返回响应正文。" + scope + "不跟随重定向。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "url": {
                              "type": "string",
                              "description": "完整的 http:// 或 https:// URL；query 里的 % 与空格必须先做百分号编码（%25 / %20）"
                            }
                          },
                          "required": ["url"]
                        }
                        """);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object raw = arguments.get("url");
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new IllegalArgumentException("缺少参数 url");
        }

        URI uri = sandbox.checkDomain(String.valueOf(raw));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", "lytgos/0.1")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("请求失败: " + uri + " → " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断: " + uri, e);
        }

        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + response.body());
        }

        String body = response.body() == null ? "" : response.body();
        if (body.length() <= MAX_RESPONSE_CHARS) {
            return body;
        }
        return body.substring(0, MAX_RESPONSE_CHARS)
                + "\n\n[响应已截断，原始长度 " + body.length() + " 字符]";
    }
}
