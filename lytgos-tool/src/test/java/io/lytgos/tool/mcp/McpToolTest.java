package io.lytgos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.agent.Profile;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.tool.Tool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("McpTool：把远端调用包成 Tool，成功与失败都回填")
class McpToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FAKE_SERVER_CLASS = "io.lytgos.tool.mcp.FakeMcpServer";

    @TempDir
    static Path workspace;

    private static McpServerManager healthy;

    @BeforeAll
    static void startHealthyServer() {
        healthy = McpServerManager.start(
                List.of(server("fake", List.of())),
                new SandboxChecker(workspace, Set.of("java"), Set.of()),
                MAPPER);
    }

    @AfterAll
    static void stopHealthyServer() {
        if (healthy != null) {
            healthy.close();
        }
    }

    @Test
    @DisplayName("调用参数原样发出去，返回的内容转成文本")
    void forwardsArgumentsAndReturnsText() {
        Tool weather = toolNamed("get_current_weather");

        String result = weather.execute(Map.of("city", "上海"));

        assertTrue(result.contains("上海"),
                "参数没被转发到 server：返回里应当出现请求的城市: " + result);
        assertTrue(result.contains("4.4°C"),
                "server 的文本内容应当原样回到模型: " + result);
    }

    @Test
    @DisplayName("server 报 isError 时抛出，让 ToolExecutor 转成回填文本")
    void errorResultBecomesException() {
        try (McpServerManager failing = McpServerManager.start(
                List.of(server("broken", List.of("--fail-on-call"))),
                new SandboxChecker(workspace, Set.of("java"), Set.of()),
                MAPPER)) {

            Tool tool = failing.tools().get(0);
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> tool.execute(Map.of("city", "北京")));

            assertTrue(failure.getMessage().contains("上游服务不可用"),
                    "server 给出的错误原因要带出来，模型才知道该怎么改: " + failure.getMessage());
        }
    }

    @Test
    @DisplayName("工具名不改写，描述带来源前缀")
    void keepsNameAndPrefixesSource() {
        Tool weather = toolNamed("get_current_weather");
        assertEquals("get_current_weather", weather.spec().name());
        assertTrue(weather.spec().description().contains("[mcp:fake]"));
    }

    private static Tool toolNamed(String name) {
        return healthy.tools().stream()
                .filter(tool -> tool.spec().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("server 没有提供工具 " + name));
    }

    private static Profile.McpServer server(String name, List<String> extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "-cp", System.getProperty("java.class.path"), FAKE_SERVER_CLASS));
        args.addAll(extraArgs);
        String javaName = File.separatorChar == '\\' ? "java.exe" : "java";
        String executable = Path.of(System.getProperty("java.home"), "bin", javaName).toString();
        return new Profile.McpServer(name, executable, args, Map.of());
    }
}
