package io.lytgos.cli;

import io.lytgos.provider.ProviderConfig;
import picocli.CommandLine.Command;

import java.util.List;

/**
 * {@code lytgos provider}：查看配置里声明了哪些 provider。
 *
 * <p>只读，不建立任何连接。真正的握手发生在 {@code lytgos chat} 首次调用模型时，
 * 所以这里走宽松配置加载——没设 key 也能看清「我配了什么、缺哪个变量」。</p>
 *
 * <p><b>不打印 api-key 的值</b>：终端输出会进 shell 历史、CI 日志、截图。
 * 只报「已配置 / 未配置（变量名）」，足够定位问题。</p>
 */
@Command(
        name = "provider",
        description = "查看配置的模型 provider",
        subcommands = { ProviderCommand.ListAll.class })
public final class ProviderCommand implements Runnable {

    private final LazyContext context;

    public ProviderCommand(LazyContext context) {
        this.context = context;
    }

    @Override
    public void run() {
        list();
    }

    static void list() {
        CliContext context = CliContext.instance();
        List<ProviderConfig> providers = context.config().providers();

        if (providers.isEmpty()) {
            System.out.println("（没有配置 provider，编辑 " + context.workspace().configFile() + " 添加）");
            return;
        }

        System.out.printf("%-14s %-22s %-38s %s%n", "NAME", "MODEL", "BASE-URL", "API-KEY");
        for (ProviderConfig provider : providers) {
            String missing = WorkspaceConfig.missingEnvVariable(provider.apiKey());
            String keyState = missing == null ? "已配置" : "未配置（环境变量 " + missing + "）";
            System.out.printf("%-14s %-22s %-38s %s%n",
                    provider.name(), provider.model(), provider.baseUrl(), keyState);
        }
    }

    @Command(name = "list", description = "列出所有 provider")
    static final class ListAll implements Runnable {

        private final LazyContext context;

        ListAll(LazyContext context) {
            this.context = context;
        }

        @Override
        public void run() {
            list();
        }
    }
}
