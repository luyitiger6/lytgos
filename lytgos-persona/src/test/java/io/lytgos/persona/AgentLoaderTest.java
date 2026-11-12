package io.lytgos.persona;

import io.lytgos.core.LytgosException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AgentLoader：frontmatter 解析与字段级报错")
class AgentLoaderTest {

    @TempDir
    Path agentsRoot;

    private Path write(String name, String content) throws IOException {
        Path dir = agentsRoot.resolve(name);
        Files.createDirectories(dir);
        Path file = dir.resolve("AGENT.md");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("解析 frontmatter 与正文指令")
    void parsesFrontmatterAndInstructions() throws IOException {
        Path file = write("default", """
                ---
                name: default
                description: 默认助手
                identity: |
                  你是助手。
                provider:
                  name: deepseek
                  model: deepseek-chat
                tools:
                  - http_get
                  - file_read
                channels:
                  - cli
                bootstrap:
                  - AGENTS.md
                  - SOUL.md
                settings:
                  maxIterations: 5
                ---

                按用户要求回答。
                """);

        AgentLoader.AgentDefinition definition = new AgentLoader().load(file);

        assertEquals("default", definition.profile().name());
        assertEquals("默认助手", definition.profile().description());
        assertEquals("你是助手。", definition.profile().identity());
        assertEquals("deepseek", definition.profile().provider().name());
        assertEquals("deepseek-chat", definition.profile().provider().model());
        assertEquals(List.of("http_get", "file_read"), definition.profile().tools());
        assertEquals(List.of("AGENTS.md", "SOUL.md"), definition.profile().bootstrap());
        assertEquals(5, definition.profile().maxIterations());
        assertEquals("按用户要求回答。", definition.instructions());
    }

    @Test
    @DisplayName("缺 provider.model 时消息字面包含 provider.model")
    void reportsMissingProviderModel() throws IOException {
        Path file = write("broken", """
                ---
                name: broken
                provider:
                  name: deepseek
                ---

                指令
                """);

        LytgosException.InvalidAgentDefinition thrown = assertThrows(
                LytgosException.InvalidAgentDefinition.class,
                () -> new AgentLoader().load(file));

        assertTrue(thrown.getMessage().contains("provider.model"), "实际消息: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("broken"), "消息应带文件路径，方便定位是哪个 Agent");
    }

    @Test
    @DisplayName("缺 provider.name 时消息字面包含 provider.name")
    void reportsMissingProviderName() throws IOException {
        Path file = write("broken", """
                ---
                name: broken
                provider:
                  model: deepseek-chat
                ---
                """);

        LytgosException.InvalidAgentDefinition thrown = assertThrows(
                LytgosException.InvalidAgentDefinition.class,
                () -> new AgentLoader().load(file));

        assertTrue(thrown.getMessage().contains("provider.name"), "实际消息: " + thrown.getMessage());
    }

    @Test
    @DisplayName("没有 frontmatter 时拒绝加载")
    void rejectsFileWithoutFrontmatter() throws IOException {
        Path file = write("plain", "# 只是一段普通 Markdown\n");

        LytgosException.InvalidAgentDefinition thrown = assertThrows(
                LytgosException.InvalidAgentDefinition.class,
                () -> new AgentLoader().load(file));

        assertTrue(thrown.getMessage().contains("frontmatter"));
    }

    @Test
    @DisplayName("缺结束的 --- 时拒绝加载")
    void rejectsUnterminatedFrontmatter() throws IOException {
        Path file = write("unterminated", """
                ---
                name: x
                provider:
                  name: p
                  model: m
                """);

        assertThrows(LytgosException.InvalidAgentDefinition.class,
                () -> new AgentLoader().load(file));
    }

    @Test
    @DisplayName("按名称加载时拒绝路径穿越")
    void rejectsTraversalInAgentName() {
        assertThrows(LytgosException.InvalidAgentDefinition.class,
                () -> new AgentLoader().load(agentsRoot, "../etc"));
    }
}
