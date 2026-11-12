package io.lytgos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.agent.Profile;
import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.tool.Tool;
import io.lytgos.core.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("McpServerManager：先过白名单，再起进程，最后握手")
class McpServerManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 冒烟用的假 server：绕开真实网络，只验证协议链路。 */
    private static final String FAKE_SERVER_CLASS = "io.lytgos.tool.mcp.FakeMcpServer";

    @TempDir
    Path workspace;

    @Test
    @DisplayName("启动后拿到 server 暴露的全部工具")
    void discoversTools() {
        try (McpServerManager manager = start(List.of(fakeServer("fake", List.of())), Set.of("java"))) {
            List<Tool> tools = manager.tools();

            assertEquals(2, tools.size(), "假 server 声明了两个工具");
            assertEquals(List.of("get_current_weather", "get_forecast"),
                    tools.stream().map(tool -> tool.spec().name()).toList(),
                    "工具名必须原样保留，不加前缀");

            Tool weather = tools.get(0);
            assertTrue(weather.spec().description().startsWith("[mcp:fake] "),
                    "描述要以来源 server 开头，tool list 里才看得出这能力来自外部进程: "
                            + weather.spec().description());
            assertTrue(weather.spec().inputSchema().contains("\"city\""),
                    "inputSchema 要原样透传，否则模型不知道参数长什么样");
            assertEquals("McpTool[fake]", weather.source());
            assertEquals(List.of("fake"), manager.serverNames());
        }
    }

    @Test
    @DisplayName("命令不在白名单时拒绝启动，消息含 sandbox，且没有子进程产生")
    void rejectsCommandOutsideWhitelist() {
        Set<Long> before = childPids();

        LytgosException.SandboxViolation violation = assertThrows(
                LytgosException.SandboxViolation.class,
                () -> start(List.of(fakeServer("fake", List.of())), Set.of("node")));

        assertTrue(violation.getMessage().contains("sandbox"),
                "消息里必须有 sandbox 字面量，验收脚本靠它判定拦截生效: " + violation.getMessage());
        assertEquals(before, childPids(), "白名单拒绝时不该有任何子进程被创建");
    }

    @Test
    @DisplayName("命令不存在时启动失败，消息含 server 名与 command")
    void failsOnMissingCommand() {
        Profile.McpServer missing = new Profile.McpServer(
                "ghost", null, "lytgos-no-such-command-xyz", List.of(), Map.of());

        LytgosException.InvalidConfiguration failure = assertThrows(
                LytgosException.InvalidConfiguration.class,
                () -> start(List.of(missing), Set.of("lytgos-no-such-command-xyz")));

        assertTrue(failure.getMessage().contains("ghost"),
                "必须指出是哪个 server 起不来: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("lytgos-no-such-command-xyz"),
                "必须指出是哪条命令: " + failure.getMessage());
    }

    @Test
    @DisplayName("缺少 name 的声明在启动前就被拒绝")
    void rejectsDeclarationWithoutName() {
        Profile.McpServer nameless = new Profile.McpServer(null, null, javaExecutable(), List.of(), Map.of());

        LytgosException.InvalidConfiguration failure = assertThrows(
                LytgosException.InvalidConfiguration.class,
                () -> start(List.of(nameless), Set.of("java")));

        assertTrue(failure.getMessage().contains("name"));
    }

    @Test
    @DisplayName("MCP 工具与内置工具重名时拒绝装配，消息含两侧来源")
    void rejectsDuplicateNameWithBuiltin() {
        try (McpServerManager manager = start(List.of(fakeServer("fake", List.of())), Set.of("java"))) {
            Tool builtin = new Tool() {
                @Override
                public ToolSpec spec() {
                    return new ToolSpec("get_current_weather", "内置的同名工具", "{\"type\":\"object\"}");
                }

                @Override
                public String execute(Map<String, Object> arguments) {
                    return "内置";
                }
            };

            List<Tool> all = new java.util.ArrayList<>();
            all.add(builtin);
            all.addAll(manager.tools());

            LytgosException.InvalidConfiguration failure = assertThrows(
                    LytgosException.InvalidConfiguration.class,
                    () -> new ToolRegistry(all));

            assertTrue(failure.getMessage().contains("get_current_weather"),
                    "必须指出撞的是哪个工具名: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("McpTool[fake]"),
                    "必须指出 MCP 侧来源，否则用户不知道该改哪个 server: " + failure.getMessage());
        }
    }

    @Test
    @DisplayName("close 之后子进程被回收")
    void closeStopsChildProcess() {
        Set<Long> before = childPids();
        McpServerManager manager = start(List.of(fakeServer("fake", List.of())), Set.of("java"));

        Set<Long> started = new HashSet<>(childPids());
        started.removeAll(before);
        assertEquals(1, started.size(), "应当恰好拉起一个子进程");

        manager.close();

        long pid = started.iterator().next();
        assertTrue(waitUntilGone(pid, 10_000),
                "close() 之后子进程 " + pid + " 仍在运行，会在用户机器上留下孤儿进程");
    }

    @Test
    @DisplayName("远程地址不在域名白名单时拒绝启动，消息含 sandbox")
    void rejectsRemoteUrlOutsideWhitelist() {
        Profile.McpServer remote = new Profile.McpServer(
                "amap", "https://mcp.amap.com/mcp?key=k", null, List.of(), Map.of());

        LytgosException.InvalidConfiguration failure = assertThrows(
                LytgosException.InvalidConfiguration.class,
                () -> start(List.of(remote), Set.of("java")));

        assertTrue(failure.getMessage().contains("sandbox"),
                "域名被拒必须能被验收脚本识别: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("mcp.amap.com"),
                "必须指出是哪个域名被拒: " + failure.getMessage());
    }

    @Test
    @DisplayName("远程服务连不上时报告 server 名与地址，不泄漏 query 里的 key")
    void failsOnUnreachableRemote() {
        Profile.McpServer remote = new Profile.McpServer(
                "dead", "http://127.0.0.1:1/mcp?key=secret-value", null, List.of(), Map.of());

        LytgosException.InvalidConfiguration failure = assertThrows(
                LytgosException.InvalidConfiguration.class,
                () -> start(List.of(remote), Set.of("java"), Set.of("127.0.0.1")));

        assertTrue(failure.getMessage().contains("dead"),
                "必须指出是哪个 server 连不上: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("127.0.0.1"),
                "必须指出是哪个地址: " + failure.getMessage());
        assertFalse(failure.getMessage().contains("secret-value"),
                "错误消息与日志不能带上 query 里的 key: " + failure.getMessage());
    }

    @Test
    @DisplayName("远程地址拆分后 base + endpoint 能原样拼回，query 里的 key 不会在 endpoint 解析时被丢掉")
    void splitRemoteUrlKeepsQuery() {
        assertTarget("https://mcp.amap.com/mcp?key=abc",
                "https://mcp.amap.com", "/mcp?key=abc");
        assertTarget("https://mcp.amap.com/mcp",
                "https://mcp.amap.com", "/mcp");
        assertTarget("http://127.0.0.1:8080/mcp?key=x&v=1",
                "http://127.0.0.1:8080", "/mcp?key=x&v=1");
        assertTarget("http://127.0.0.1:8080/mcp",
                "http://127.0.0.1:8080", "/mcp");
    }

    @Test
    @DisplayName("地址只有域名时 endpoint 留空，交给 SDK 的默认 /mcp")
    void splitRemoteUrlWithoutPath() {
        McpServerManager.RemoteTarget target =
                McpServerManager.splitRemoteUrl("https://mcp.example.com");

        assertEquals("https://mcp.example.com", target.baseUri());
        assertNull(target.endpoint(),
                "没有 path 时不能给 endpoint 传空串——那会把请求打到根路径，而不是 MCP 惯例的 /mcp");
    }

    @Test
    @DisplayName("错误消息带出 cause 链，且原文 URL 一律替换成脱敏版本")
    void describeIncludesCauseAndRedactsUrl() {
        String url = "https://mcp.amap.com/mcp?key=secret-value";
        RuntimeException root = new IllegalStateException("request to " + url + " returned INVALID_USER_KEY");
        RuntimeException outer = new RuntimeException("Client failed to initialize by explicit API call", root);

        String text = McpServerManager.describe(outer, url);

        assertTrue(text.contains("explicit API call"),
                "外层消息要保留，否则看不出是哪一步失败的: " + text);
        assertTrue(text.contains("INVALID_USER_KEY"),
                "cause 必须带出来，否则真正的原因全被吞掉: " + text);
        assertFalse(text.contains("secret-value"),
                "cause 里带的完整 URL 会让 key 漏进日志: " + text);
        assertTrue(text.contains("?***"), text);
    }

    private static void assertTarget(String url, String baseUri, String endpoint) {
        McpServerManager.RemoteTarget target = McpServerManager.splitRemoteUrl(url);

        assertEquals(baseUri, target.baseUri(), "baseUri 只保留 scheme + authority: " + url);
        assertEquals(endpoint, target.endpoint(), "endpoint 必须带上 path 与 query: " + url);
        assertEquals(url, target.baseUri() + target.endpoint(),
                "拆完拼回去必须与配置地址完全一致，否则请求会打到别的地址");
    }

    private McpServerManager start(List<Profile.McpServer> servers, Set<String> allowedCommands) {
        return start(servers, allowedCommands, Set.of());
    }

    private McpServerManager start(List<Profile.McpServer> servers, Set<String> allowedCommands,
                                   Set<String> allowedDomains) {
        SandboxChecker sandbox = new SandboxChecker(workspace, allowedCommands, allowedDomains);
        return McpServerManager.start(servers, sandbox, MAPPER);
    }

    /** 用当前 JVM 拉一个 {@link FakeMcpServer}，classpath 指向测试运行的同一套。 */
    private static Profile.McpServer fakeServer(String name, List<String> extraArgs) {
        List<String> args = new java.util.ArrayList<>(List.of(
                "-cp", System.getProperty("java.class.path"), FAKE_SERVER_CLASS));
        args.addAll(extraArgs);
        return new Profile.McpServer(name, null, javaExecutable(), args, Map.of());
    }

    private static String javaExecutable() {
        String name = File.separatorChar == '\\' ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    private static Set<Long> childPids() {
        return ProcessHandle.current().children()
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    private static boolean waitUntilGone(long pid, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
