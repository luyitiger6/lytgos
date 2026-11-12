package io.lytgos.core.tool;

import io.lytgos.core.LytgosException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SandboxChecker：三类校验与空名单默认拒绝")
class SandboxCheckerTest {

    @TempDir
    Path tempDir;

    private Path workspace;

    private SandboxChecker checker() throws IOException {
        workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        return new SandboxChecker(workspace, Set.of("echo"), Set.of("api.open-meteo.com"));
    }

    @Test
    @DisplayName("工作区内的路径放行")
    void allowsPathInsideWorkspace() throws IOException {
        SandboxChecker sandbox = checker();
        Path resolved = sandbox.checkPath(workspace.resolve("AGENTS.md"));
        assertTrue(resolved.isAbsolute(), "返回的应是绝对路径");
    }

    @Test
    @DisplayName("../ 穿越被拒")
    void rejectsParentTraversal() throws IOException {
        SandboxChecker sandbox = checker();
        LytgosException.SandboxViolation violation = assertThrows(
                LytgosException.SandboxViolation.class,
                () -> sandbox.checkPath(workspace.resolve("../outside.txt")));
        assertTrue(violation.getMessage().contains("sandbox"), "消息必须带 sandbox 字面量供验收脚本判定");
    }

    @Test
    @DisplayName("绝对路径越界被拒")
    void rejectsAbsolutePathOutside() throws IOException {
        SandboxChecker sandbox = checker();
        assertThrows(LytgosException.SandboxViolation.class,
                () -> sandbox.checkPath(tempDir.resolve("outside.txt")));
    }

    @Test
    @DisplayName("白名单域名放行，大小写不敏感")
    void allowsWhitelistedDomain() throws IOException {
        SandboxChecker sandbox = checker();
        assertEquals("API.Open-Meteo.com",
                sandbox.checkDomain("https://API.Open-Meteo.com/v1/forecast?latitude=1").getHost());
    }

    @Test
    @DisplayName("非白名单域名被拒，消息含 sandbox")
    void rejectsNonWhitelistedDomain() throws IOException {
        SandboxChecker sandbox = checker();
        LytgosException.SandboxViolation violation = assertThrows(
                LytgosException.SandboxViolation.class,
                () -> sandbox.checkDomain("https://example.com"));
        assertTrue(violation.getMessage().contains("sandbox"));
        assertTrue(violation.getMessage().contains("example.com"));
    }

    @Test
    @DisplayName("空白名单 = 全部拒绝")
    void emptyDomainWhitelistRejectsEverything() throws IOException {
        Files.createDirectories(tempDir.resolve("empty"));
        SandboxChecker sandbox = new SandboxChecker(tempDir.resolve("empty"), Set.of(), Set.of());
        LytgosException.SandboxViolation violation = assertThrows(
                LytgosException.SandboxViolation.class,
                () -> sandbox.checkDomain("https://api.open-meteo.com/v1/forecast"));
        assertTrue(violation.getMessage().contains("白名单为空"));
    }

    @Test
    @DisplayName("把白名单域名塞进 userinfo 的写法被拒")
    void rejectsUserInfoTrick() throws IOException {
        SandboxChecker sandbox = checker();
        assertThrows(LytgosException.SandboxViolation.class,
                () -> sandbox.checkDomain("https://api.open-meteo.com@evil.example.com/"));
    }

    @Test
    @DisplayName("非 http/https 协议被拒")
    void rejectsNonHttpScheme() throws IOException {
        SandboxChecker sandbox = checker();
        assertThrows(LytgosException.SandboxViolation.class,
                () -> sandbox.checkDomain("file:///etc/passwd"));
    }

    @Test
    @DisplayName("query 里的裸 % 归为参数错误，不归为沙箱违规")
    void rejectsMalformedPercentEncodingAsArgumentError() throws IOException {
        SandboxChecker sandbox = checker();
        // 模型自己拼 URL 时常这么写：%l %c %t 都不是合法的百分号转义（% 后必须跟两位十六进制）。
        // 归为 InvalidToolArgument 而不是 SandboxViolation：前者会被 ToolExecutor 回填给
        // 模型自己改正，后者会中断整轮对话——而这不是攻击，只是拼错了。
        LytgosException.InvalidToolArgument violation = assertThrows(
                LytgosException.InvalidToolArgument.class,
                () -> sandbox.checkDomain("https://wttr.in/Beijing?format=%l:+%c+%t"));
        String message = violation.getMessage();
        assertTrue(message.contains("格式非法"), "消息应指明是格式问题: " + message);
        assertTrue(message.contains("%25"), "消息应告诉调用方怎么改: " + message);
    }

    @Test
    @DisplayName("allowedDomains 对外只读：统一小写且不可修改")
    void exposesAllowedDomainsAsImmutableSet() throws IOException {
        workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        SandboxChecker sandbox = new SandboxChecker(
                workspace, Set.of(), Set.of("API.Open-Meteo.com", "api.open-meteo.com"));
        assertEquals(Set.of("api.open-meteo.com"), sandbox.allowedDomains(),
                "构造时已统一小写并去重");
        assertThrows(UnsupportedOperationException.class,
                () -> sandbox.allowedDomains().add("evil.com"),
                "返回的集合必须不可修改，否则运行期能绕过白名单");
    }

    @Test
    @DisplayName("命令白名单：命中放行，未命中被拒，空名单全拒")
    void checksCommandWhitelist() throws IOException {
        SandboxChecker sandbox = checker();
        assertEquals("echo", sandbox.checkCommand("echo hello"));
        assertEquals("echo", sandbox.checkCommand("C:\\Windows\\System32\\echo.exe hello"));

        assertThrows(LytgosException.SandboxViolation.class,
                () -> sandbox.checkCommand("rm -rf /"));

        SandboxChecker empty = new SandboxChecker(tempDir.resolve("workspace"), Set.of(), Set.of());
        LytgosException.SandboxViolation violation = assertThrows(
                LytgosException.SandboxViolation.class, () -> empty.checkCommand("echo hi"));
        assertTrue(violation.getMessage().contains("白名单为空"));
    }

    @Test
    @DisplayName("带空格的路径不能被当成参数分隔——否则 Program Files 下的 java 会变成 Program")
    void acceptsCommandPathWithSpaces() throws IOException {
        SandboxChecker sandbox = checker();

        assertEquals("echo", sandbox.checkCommand("C:\\Program Files\\Git\\usr\\bin\\echo.exe"),
                "整串取出的文件名已在白名单时，不能按空格切分");
        assertEquals("echo", sandbox.checkCommand("C:/Program Files/Git/usr/bin/echo.exe"),
                "正斜杠路径同样适用");
        assertEquals("echo", sandbox.checkCommand("\"C:\\Program Files\\Git\\echo.exe\" --version"),
                "引号包裹时取引号内的路径，引号后才是参数");
        assertEquals("echo", sandbox.checkCommand("echo -n hi"),
                "内联参数仍照旧按第一个空格切——第 1 周的行为不能回退");
    }
}
