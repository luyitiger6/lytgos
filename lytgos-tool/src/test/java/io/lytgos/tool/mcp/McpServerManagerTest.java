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
                "ghost", "lytgos-no-such-command-xyz", List.of(), Map.of());

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
        Profile.McpServer nameless = new Profile.McpServer(null, javaExecutable(), List.of(), Map.of());

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

    private McpServerManager start(List<Profile.McpServer> servers, Set<String> allowedCommands) {
        SandboxChecker sandbox = new SandboxChecker(workspace, allowedCommands, Set.of());
        return McpServerManager.start(servers, sandbox, MAPPER);
    }

    /** 用当前 JVM 拉一个 {@link FakeMcpServer}，classpath 指向测试运行的同一套。 */
    private static Profile.McpServer fakeServer(String name, List<String> extraArgs) {
        List<String> args = new java.util.ArrayList<>(List.of(
                "-cp", System.getProperty("java.class.path"), FAKE_SERVER_CLASS));
        args.addAll(extraArgs);
        return new Profile.McpServer(name, javaExecutable(), args, Map.of());
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
