package io.lytgos.cli;

import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.tool.FileTool;
import io.lytgos.tool.HttpTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内置工具的 Bean 声明。
 *
 * <p>两个工具都不随 Agent 变化，全进程各一个；按 Agent 筛选用哪些工具发生在
 * {@link AgentRuntimeFactory} 里，不进容器。</p>
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
}
