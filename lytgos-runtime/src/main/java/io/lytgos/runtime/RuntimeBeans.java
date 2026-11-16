package io.lytgos.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.audit.ToolInvocationAuditor;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.memory.RecallMemoryTool;
import io.lytgos.memory.SaveMemoryTool;
import io.lytgos.persona.AgentLoader;
import io.lytgos.provider.ProviderRegistry;
import io.lytgos.tool.FileTool;
import io.lytgos.tool.HttpTool;
import io.lytgos.tool.ListDirTool;
import io.lytgos.tool.ShellTool;
import io.lytgos.tool.WriteFileTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * per-Agent 对象的装配 Bean。
 *
 * <p>{@link AgentRuntimeFactory} 是 per-Agent 对象的创建入口：它本身无状态， 单例登记；每次调用产出一份新的运行时。
 *
 * <p>{@link RuntimePool} 是 Web 侧的运行时缓存：按 Agent 懒创建、会话级串行、容器关闭时回收 （含 MCP 子进程）。它实现 {@link
 * AgentInvoker} 端口，控制器只依赖接口。
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeBeans {

  @Bean
  public AgentInvoker agentInvoker(AgentRuntimeFactory factory) {
    return new RuntimePool(factory);
  }

  @Bean
  public AgentRuntimeFactory agentRuntimeFactory(
      WorkspaceLocator locator,
      SandboxChecker sandbox,
      AgentLoader agentLoader,
      ProviderRegistry providers,
      ObjectMapper mapper,
      ToolInvocationAuditor auditor,
      HttpTool httpTool,
      FileTool fileTool,
      WriteFileTool writeFileTool,
      ListDirTool listDirTool,
      ShellTool shellTool,
      SaveMemoryTool saveMemoryTool,
      RecallMemoryTool recallMemoryTool) {
    return new AgentRuntimeFactory(
        locator,
        sandbox,
        agentLoader,
        providers,
        mapper,
        auditor,
        httpTool,
        fileTool,
        writeFileTool,
        listDirTool,
        shellTool,
        saveMemoryTool,
        recallMemoryTool);
  }
}
