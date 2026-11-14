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
}
