package io.lytgos.runtime;

import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.memory.MemoryStore;
import io.lytgos.memory.RecallMemoryTool;
import io.lytgos.memory.SaveMemoryTool;
import io.lytgos.tool.FileTool;
import io.lytgos.tool.HttpTool;
import io.lytgos.tool.ListDirTool;
import io.lytgos.tool.ShellTool;
import io.lytgos.tool.WriteFileTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内置工具的 Bean 声明。
 *
 * <p>七个工具都不随 Agent 变化，全进程各一个；按 Agent 筛选用哪些工具发生在 {@link AgentRuntimeFactory} 里，不进容器。
 */
@Configuration(proxyBeanMethods = false)
public class ToolBeans {

  @Bean
  public HttpTool httpTool(SandboxChecker sandbox) {
    return new HttpTool(sandbox);
  }

  @Bean
  public FileTool fileTool(SandboxChecker sandbox, WorkspaceLocator locator) {
    return new FileTool(sandbox, locator.get().root());
  }

  @Bean
  public WriteFileTool writeFileTool(SandboxChecker sandbox, WorkspaceLocator locator) {
    return new WriteFileTool(sandbox, locator.get().root());
  }

  @Bean
  public ListDirTool listDirTool(SandboxChecker sandbox, WorkspaceLocator locator) {
    return new ListDirTool(sandbox, locator.get().root());
  }

  @Bean
  public ShellTool shellTool(
      SandboxChecker sandbox, WorkspaceLocator locator, WorkspaceConfig config) {
    return new ShellTool(sandbox, locator.get().root(), config.allowedCommands());
  }

  @Bean
  public SaveMemoryTool saveMemoryTool(MemoryStore store) {
    return new SaveMemoryTool(store);
  }

  @Bean
  public RecallMemoryTool recallMemoryTool(MemoryStore store) {
    return new RecallMemoryTool(store);
  }
}
