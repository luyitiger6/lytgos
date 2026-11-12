package io.lytgos.tool.mcp;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个手写的最小 MCP server，只实现协议里够用的一小段，用 stdio 通信。
 *
 * <p>为什么不用官方 SDK 的 server 端来做测试夹具：客户端和服务端用同一套代码，
 * SDK 内部的互操作 bug 会被两边一起掩盖。这里只依赖 JDK 和协议文本，
 * 客户端能跟它握手成功，才真的说明「符合规范的任意 server 都能接进来」。</p>
 *
 * <p>刻意暴露两个工具，其中一个返回错误，用来覆盖「出错要回填给模型」的路径。
 * 行为由 {@link #main} 的命令行参数 {@code --fail-on-call} 控制。</p>
 */
public final class FakeMcpServer {

    /** 协议版本：与 SDK 2.1.0 支持的最新版一致。 */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private static final Pattern ID_PATTERN =
            Pattern.compile("\"id\"\\s*:\\s*(\"([^\"]*)\"|(-?\\d+))");

    private static final Pattern CITY_PATTERN =
            Pattern.compile("\"city\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * 工具清单。刻意写成单行字符串：stdio 传输是按行分帧的，
     * 响应体里只要出现一个换行，客户端就只读到前半截、剩下半截当成下一条消息，
     * 表现为「请求发出去了一直没回应」。
     */
    private static final String TOOLS =
            "{\"name\":\"get_current_weather\",\"description\":\"查询指定城市的当前天气\","
                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"city\":"
                    + "{\"type\":\"string\",\"description\":\"城市名，例如 北京\"}},"
                    + "\"required\":[\"city\"]}},"
                    + "{\"name\":\"get_forecast\",\"description\":\"查询指定城市未来几天的天气预报\","
                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"city\":"
                    + "{\"type\":\"string\"}},\"required\":[\"city\"]}}";

    private FakeMcpServer() {
    }

    /**
     * @param args 传 {@code --fail-on-call} 时，任何 tools/call 都返回错误结果
     */
    public static void main(String[] args) throws Exception {
        boolean failOnCall = false;
        for (String arg : args) {
            if ("--fail-on-call".equals(arg)) {
                failOnCall = true;
            }
        }

        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        String line;
        while ((line = in.readLine()) != null) {
            String message = line.strip();
            if (message.isEmpty()) {
                continue;
            }
            String id = extractId(message);

            if (message.contains("\"method\":\"initialize\"")) {
                respond(out, id, "{\"protocolVersion\":\"" + PROTOCOL_VERSION + "\","
                        + "\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"fake-localweather\",\"version\":\"1.0.0\"}}");
            } else if (message.contains("\"method\":\"tools/list\"")) {
                respond(out, id, "{\"tools\":[" + TOOLS + "]}");
            } else if (message.contains("\"method\":\"tools/call\"")) {
                respond(out, id, callTool(message, failOnCall));
            } else if (message.contains("\"method\":\"ping\"")) {
                respond(out, id, "{}");
            } else if (id != null) {
                // 其余请求统一回「未实现」，让客户端知道这条路走不通而不是一直等
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
            }
            // 没有 id 的是通知（例如 notifications/initialized），按协议不回应
        }
    }

    private static String callTool(String message, boolean failOnCall) {
        String city = "北京";
        Matcher matcher = CITY_PATTERN.matcher(message);
        if (matcher.find()) {
            city = matcher.group(1);
        }
        if (failOnCall) {
            return "{\"content\":[{\"type\":\"text\",\"text\":\"上游服务不可用，请稍后再试\"}],\"isError\":true}";
        }
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + city
                + "当前气温 4.4°C，湿度 92%，降水 0mm\"}],\"isError\":false}";
    }

    private static void respond(PrintStream out, String id, String result) {
        if (id == null) {
            return;
        }
        out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
    }

    /** 原样取出 id 的字面量形式（数字或带引号字符串），通知返回 {@code null}。 */
    private static String extractId(String message) {
        Matcher matcher = ID_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }
}
