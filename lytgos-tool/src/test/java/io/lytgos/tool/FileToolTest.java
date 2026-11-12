package io.lytgos.tool;

import io.lytgos.core.LytgosException;
import io.lytgos.core.tool.SandboxChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FileTool：只能读工作区内的文本文件")
class FileToolTest {

    @TempDir
    Path root;

    private FileTool tool() {
        return new FileTool(new SandboxChecker(root, Set.of(), Set.of()), root);
    }

    @Test
    @DisplayName("读取工作区内的相对路径")
    void readsFileInsideWorkspace() throws IOException {
        Files.writeString(root.resolve("AGENTS.md"), "# 工作区说明", StandardCharsets.UTF_8);
        assertEquals("# 工作区说明", tool().execute(Map.of("path", "AGENTS.md")));
    }

    @Test
    @DisplayName(".. 穿越被拒")
    void rejectsParentTraversal() {
        LytgosException.SandboxViolation violation = assertThrows(
                LytgosException.SandboxViolation.class,
                () -> tool().execute(Map.of("path", "../outside.txt")));
        assertTrue(violation.getMessage().contains("sandbox"));
    }

    @Test
    @DisplayName("工作区外的绝对路径被拒")
    void rejectsAbsolutePathOutside() {
        Path outside = root.getParent().resolve("outside.txt");
        assertThrows(LytgosException.SandboxViolation.class,
                () -> tool().execute(Map.of("path", outside.toString())));
    }

    @Test
    @DisplayName("目标是目录时明确报错，而不是抛 IO 异常")
    void rejectsDirectory() throws IOException {
        Files.createDirectories(root.resolve("memory"));
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> tool().execute(Map.of("path", "memory")));
        assertTrue(thrown.getMessage().contains("不是普通文件"));
    }

    @Test
    @DisplayName("超长文件被截断并注明原始长度")
    void truncatesLongFile() throws IOException {
        String longContent = "x".repeat(9000);
        Files.writeString(root.resolve("big.txt"), longContent, StandardCharsets.UTF_8);

        String result = tool().execute(Map.of("path", "big.txt"));

        assertTrue(result.startsWith("x".repeat(100)));
        assertTrue(result.contains("文件已截断"));
        assertTrue(result.contains("9000"));
        assertTrue(result.length() < 9000);
    }
}
