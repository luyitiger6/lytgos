package io.lytgos.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("AgentLoader.catalog：条目与错误两段结构")
class AgentLoaderCatalogTest {

  @TempDir Path agentsRoot;

  private void writeAgent(String directoryName, String content) throws IOException {
    Path dir = agentsRoot.resolve(directoryName);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("AGENT.md"), content, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("正常目录：条目按目录名排序，字段来自 frontmatter")
  void listsValidAgents() throws IOException {
    writeAgent(
        "zeta",
        """
            ---
            name: zeta
            description: 后一个
            provider:
              name: deepseek
              model: deepseek-chat
            ---
            指令
            """);
    writeAgent(
        "alpha",
        """
            ---
            name: alpha
            description: 前一个
            provider:
              name: deepseek
              model: deepseek-reasoner
            tools:
              - file_read
            ---
            指令
            """);

    AgentCatalog catalog = new AgentLoader().catalog(agentsRoot);

    assertTrue(catalog.errors().isEmpty(), "不应有错误条目: " + catalog.errors());
    assertEquals(
        List.of("alpha", "zeta"), catalog.items().stream().map(AgentCatalog.Entry::name).toList());
    AgentCatalog.Entry alpha = catalog.items().get(0);
    assertEquals("前一个", alpha.description());
    assertEquals("deepseek", alpha.provider());
    assertEquals("deepseek-reasoner", alpha.model());
    assertEquals(List.of("file_read"), alpha.tools());
  }

  @Test
  @DisplayName("坏 frontmatter 进 errors，不拖垮合法条目")
  void collectsBrokenAgentAsError() throws IOException {
    writeAgent(
        "default",
        """
            ---
            name: default
            provider:
              name: deepseek
              model: deepseek-chat
            ---
            指令
            """);
    writeAgent(
        "broken",
        """
            ---
            name: broken
            provider:
              name: deepseek
            ---
            指令
            """);
    Files.createDirectories(agentsRoot.resolve("empty-dir"));

    AgentCatalog catalog = new AgentLoader().catalog(agentsRoot);

    assertEquals(
        List.of("default"), catalog.items().stream().map(AgentCatalog.Entry::name).toList());
    assertEquals(
        List.of("broken", "empty-dir"),
        catalog.errors().stream().map(AgentCatalog.EntryError::name).toList());
    assertTrue(
        catalog.errors().get(0).message().contains("provider.model"),
        "实际消息: " + catalog.errors().get(0).message());
    assertTrue(
        catalog.errors().get(1).message().contains("无法读取"),
        "实际消息: " + catalog.errors().get(1).message());
  }

  @Test
  @DisplayName("空目录与不存在的根都返回空结果")
  void returnsEmptyForEmptyOrMissingDirectory(@TempDir Path emptyDir) {
    AgentLoader loader = new AgentLoader();

    AgentCatalog empty = loader.catalog(emptyDir);
    AgentCatalog missing = loader.catalog(emptyDir.resolve("no-such-dir"));

    assertTrue(empty.items().isEmpty());
    assertTrue(empty.errors().isEmpty());
    assertTrue(missing.items().isEmpty());
    assertTrue(missing.errors().isEmpty());
  }

  @Test
  @DisplayName("agents 根下的普通文件被忽略")
  void ignoresRegularFiles() throws IOException {
    Files.writeString(agentsRoot.resolve("README.md"), "not an agent", StandardCharsets.UTF_8);

    AgentCatalog catalog = new AgentLoader().catalog(agentsRoot);

    assertTrue(catalog.items().isEmpty());
    assertTrue(catalog.errors().isEmpty());
  }
}
