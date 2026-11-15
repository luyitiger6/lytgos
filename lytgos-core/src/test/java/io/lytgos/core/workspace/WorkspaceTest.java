package io.lytgos.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Workspace：初始化必须建出可直接聊天的默认 Agent")
class WorkspaceTest {

  @TempDir Path dir;

  @Test
  @DisplayName("initialize 建出 agents/default/AGENT.md 且带 frontmatter")
  void createsDefaultAgentFile() throws IOException {
    int created = Workspace.at(dir).initialize();

    Path agentFile = dir.resolve("agents/default/AGENT.md");
    assertTrue(Files.isRegularFile(agentFile), "默认 Agent 文件应存在: " + agentFile);
    String content = Files.readString(agentFile, StandardCharsets.UTF_8);
    assertTrue(content.stripLeading().startsWith("---"), "AGENT.md 应以 frontmatter 开头");
    assertTrue(created > 0, "首次初始化应报告新建条目数");
  }

  @Test
  @DisplayName("重复 initialize 不覆盖用户改过的默认 Agent")
  void keepsEditedDefaultAgent() throws IOException {
    Workspace workspace = Workspace.at(dir);
    workspace.initialize();
    Path agentFile = dir.resolve("agents/default/AGENT.md");
    String edited = "---\nname: default\n---\n自定义内容\n";
    Files.writeString(agentFile, edited, StandardCharsets.UTF_8);

    workspace.initialize();

    assertEquals(edited, Files.readString(agentFile, StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("initialize 建出 6 个目录、3 个 bootstrap、config 与空的 MEMORY.md")
  void createsFullLayout() throws IOException {
    Workspace.at(dir).initialize();

    for (String name : Workspace.DIRECTORIES) {
      assertTrue(Files.isDirectory(dir.resolve(name)), "缺目录: " + name);
    }
    assertTrue(Files.isDirectory(dir.resolve("output")), "output/ 是模型写文件的默认目标，必须存在");
    for (String name : Workspace.BOOTSTRAP_FILES) {
      assertTrue(Files.isRegularFile(dir.resolve(name)), "缺 bootstrap 文件: " + name);
    }
    assertTrue(Files.isRegularFile(dir.resolve(Workspace.CONFIG_FILE)), "缺 config.yaml");

    Path memoryIndex = dir.resolve("memory/MEMORY.md");
    assertTrue(Files.isRegularFile(memoryIndex), "应建出 memory/MEMORY.md");
    assertEquals(
        "", Files.readString(memoryIndex, StandardCharsets.UTF_8), "MEMORY.md 必须是空文件，占位文案会被当成记忆注入");
  }
}
