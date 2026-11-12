package io.lytgos.tool;

import io.lytgos.core.LytgosException;
import io.lytgos.core.tool.SandboxChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HttpTool：白名单在发请求之前生效")
class HttpToolTest {

    @TempDir
    Path workspace;

    private HttpTool tool() {
        return new HttpTool(new SandboxChecker(
                workspace, Set.of(), Set.of("geocoding-api.open-meteo.com")));
    }

    @Test
    @DisplayName("声明的工具名是 http_get")
    void declaresHttpGet() {
        assertEquals("http_get", tool().spec().name());
        assertTrue(tool().spec().inputSchema().contains("\"url\""));
    }

    @Test
    @DisplayName("工具描述里直接写出允许的域名，不让模型去猜")
    void specDeclaresAllowedDomains() {
        String description = tool().spec().description();
        assertTrue(description.contains("geocoding-api.open-meteo.com"),
                "描述里必须出现实际白名单，否则模型会自己编域名: " + description);
        assertTrue(description.contains("沙箱拒绝"),
                "要让模型知道超范围不是报错回退，而是直接拒绝: " + description);
    }

    @Test
    @DisplayName("空白名单时描述里明说一定失败")
    void specSaysEmptyWhitelistAlwaysFails() {
        HttpTool blocked = new HttpTool(new SandboxChecker(workspace, Set.of(), Set.of()));
        assertTrue(blocked.spec().description().contains("一定失败"));
    }

    @Test
    @DisplayName("非白名单域名在建立连接前就被拒绝")
    void rejectsNonWhitelistedBeforeNetwork() {
        LytgosException.SandboxViolation violation = assertThrows(
                LytgosException.SandboxViolation.class,
                () -> tool().execute(Map.of("url", "https://example.com")));
        assertTrue(violation.getMessage().contains("sandbox"));
    }

    @Test
    @DisplayName("缺少 url 参数时报错，且不提网")
    void rejectsMissingUrl() {
        assertThrows(IllegalArgumentException.class, () -> tool().execute(Map.of()));
    }

    @Test
    @DisplayName("空白名单下连白名单域名的请求也发不出去")
    void emptyWhitelistBlocksEverything() {
        HttpTool blocked = new HttpTool(new SandboxChecker(workspace, Set.of(), Set.of()));
        assertThrows(LytgosException.SandboxViolation.class,
                () -> blocked.execute(Map.of("url", "https://geocoding-api.open-meteo.com/v1/search")));
    }
}
