package io.lytgos.cli;

import io.lytgos.provider.ProviderConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * {@code .lytgos/config.yaml} 的绑定结果。
 *
 * <p>解析交给 {@link WorkspaceConfigProvider}（{@code YamlPropertySourceLoader} + {@code Binder}）；
 * 本类只做两件事：暴露绑定后的不可变视图、判定值里残留的 {@code ${VAR}} 占位。</p>
 *
 * <p>{@code ${ENV_VAR}} 占位在加载时宽松解析：环境变量存在就替换，缺失则原样保留。
 * 严格性推迟到构造 {@code ChatModel} 之前——{@code provider list} 这类只读命令
 * 不该因为一个没设的环境变量而跑不起来。</p>
 */
public final class WorkspaceConfig {

    private final List<ProviderConfig> providers;
    private final Set<String> allowedDomains;
    private final Set<String> allowedCommands;

    WorkspaceConfig(List<ProviderConfig> providers,
                    List<String> allowedDomains,
                    List<String> allowedCommands) {
        this.providers = List.copyOf(providers);
        this.allowedDomains = Set.copyOf(allowedDomains == null ? List.of() : allowedDomains);
        this.allowedCommands = Set.copyOf(allowedCommands == null ? List.of() : allowedCommands);
    }

    public static WorkspaceConfig load(Path configFile) {
        return WorkspaceConfigProvider.load(configFile);
    }

    public List<ProviderConfig> providers() {
        return providers;
    }

    public Set<String> allowedDomains() {
        return allowedDomains;
    }

    public Set<String> allowedCommands() {
        return allowedCommands;
    }

    /** 该值里是否还有没解析掉的环境变量占位。 */
    public static boolean isMissingEnv(String value) {
        return missingEnvVariable(value) != null;
    }

    /** 从残留占位里取出变量名；不是占位时返回 {@code null}。 */
    public static String missingEnvVariable(String value) {
        return ProviderConfig.unresolvedVariable(value);
    }
}
