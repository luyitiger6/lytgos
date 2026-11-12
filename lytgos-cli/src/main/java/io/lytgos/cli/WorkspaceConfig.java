package io.lytgos.cli;

import io.lytgos.core.LytgosException;
import io.lytgos.provider.ProviderConfig;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code .lytgos/config.yaml} 的解析结果。
 *
 * <p>{@code ${ENV_VAR}} 占位在加载时解析。变量缺失直接失败并指出变量名——
 * 若静默替换成空串，用户看到的是「401 未授权」，要绕一大圈才能查到是环境变量没设。</p>
 */
public final class WorkspaceConfig {

    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** 宽松加载时，缺失环境变量被替换成的占位前缀。 */
    private static final String MISSING_ENV_PREFIX = "${missing:";

    /** 该值是否是「环境变量未设置」的占位。 */
    public static boolean isMissingEnv(String value) {
        return value != null && value.startsWith(MISSING_ENV_PREFIX) && value.endsWith("}");
    }

    /** 从占位里取出变量名；不是占位时返回 {@code null}。 */
    public static String missingEnvVariable(String value) {
        return isMissingEnv(value)
                ? value.substring(MISSING_ENV_PREFIX.length(), value.length() - 1)
                : null;
    }

    private final List<ProviderConfig> providers;
    private final Set<String> allowedDomains;
    private final Set<String> allowedCommands;

    private WorkspaceConfig(List<ProviderConfig> providers,
                            Set<String> allowedDomains,
                            Set<String> allowedCommands) {
        this.providers = List.copyOf(providers);
        this.allowedDomains = Set.copyOf(allowedDomains);
        this.allowedCommands = Set.copyOf(allowedCommands);
    }

    public static WorkspaceConfig load(Path configFile) {
        return load(configFile, true);
    }

    /**
     * @param strictEnv true 时环境变量缺失即失败；false 时保留 {@code ${VAR}} 原样，
     *                  供不需要连接模型的只读命令（如 {@code provider list}）使用
     */
    public static WorkspaceConfig load(Path configFile, boolean strictEnv) {
        if (!Files.isRegularFile(configFile)) {
            throw new LytgosException.InvalidConfiguration("配置文件不存在: " + configFile);
        }
        Map<String, Object> root = parse(configFile);

        List<ProviderConfig> providers = new ArrayList<>();
        asMap(root.get("providers")).forEach((name, raw) -> {
            Map<String, Object> entry = asMap(raw);
            providers.add(new ProviderConfig(
                    name,
                    resolve(text(entry.get("model")), name, strictEnv),
                    resolve(text(entry.get("api-key")), name, strictEnv),
                    resolve(text(entry.get("base-url")), name, strictEnv)));
        });

        Map<String, Object> sandbox = asMap(root.get("sandbox"));
        return new WorkspaceConfig(
                providers,
                new LinkedHashSet<>(asStrings(sandbox.get("allowed-domains"))),
                new LinkedHashSet<>(asStrings(sandbox.get("allowed-commands"))));
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

    private static Map<String, Object> parse(Path configFile) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        Object parsed;
        try {
            parsed = yaml.load(Files.readString(configFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LytgosException.InvalidConfiguration("无法读取配置文件: " + configFile);
        } catch (RuntimeException e) {
            throw new LytgosException.InvalidConfiguration(
                    "配置文件不是合法 YAML: " + configFile + " → " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new LytgosException.InvalidConfiguration("配置文件为空或不是键值映射: " + configFile);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String resolve(String value, String providerName, boolean strictEnv) {
        if (value == null) {
            return null;
        }
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String variable = matcher.group(1);
            String env = System.getenv(variable);
            if (env == null || env.isBlank()) {
                if (strictEnv) {
                    throw new LytgosException.InvalidConfiguration(
                            "环境变量未设置: " + variable + "（provider " + providerName + " 需要）");
                }
                env = MISSING_ENV_PREFIX + variable + "}";
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(env));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).strip();
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, inner) -> result.put(String.valueOf(key), inner));
        return result;
    }

    private static List<String> asStrings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).map(String::strip).toList();
    }
}
