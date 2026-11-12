package io.lytgos.persona;

import io.lytgos.core.LytgosException;
import io.lytgos.core.agent.Profile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 解析 {@code .lytgos/agents/<name>/AGENT.md}。
 *
 * <p>文件由两段组成：{@code ---} 包围的 YAML frontmatter 与之后的正文。
 * 前者决定「用哪个模型、有哪些工具」，后者是「任务指令」，两者都要交给
 * {@link io.lytgos.core.agent.PromptBuilder}，所以一并返回。</p>
 *
 * <p>解析失败一律抛 {@link LytgosException.InvalidAgentDefinition} 并带上文件路径与
 * 具体字段：Agent 定义是用户手写的，报错信息若只说「格式错误」，用户除了猜没有别的办法。</p>
 */
public final class AgentLoader {

    private static final String DELIMITER = "---";

    private final Yaml yaml;

    public AgentLoader() {
        LoaderOptions options = new LoaderOptions();
        // 默认的 Constructor 能根据 !!com.example.Foo 标签直接实例化任意类；AGENT.md 是用户可写文件，
        // 这里只允许基本类型（Map / List / String / Number / Boolean）
        options.setAllowDuplicateKeys(false);
        // 挡住 YAML 炸弹：别名展开是 YAML 特有的内存耗尽入口
        options.setMaxAliasesForCollections(50);
        this.yaml = new Yaml(new SafeConstructor(options));
    }

    /**
     * 加载一个 Agent 定义。
     *
     * @param agentFile 指向 {@code AGENT.md}
     */
    public AgentDefinition load(Path agentFile) {
        String raw;
        try {
            raw = Files.readString(agentFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LytgosException.InvalidAgentDefinition("无法读取 Agent 定义: " + agentFile);
        }

        String content = raw.stripLeading();
        if (!content.startsWith(DELIMITER)) {
            throw new LytgosException.InvalidAgentDefinition(
                    "AGENT.md 必须以 " + DELIMITER + " 开头（缺少 frontmatter）: " + agentFile);
        }
        int frontStart = content.indexOf(DELIMITER) + DELIMITER.length();
        int frontEnd = content.indexOf(DELIMITER, frontStart);
        if (frontEnd < 0) {
            throw new LytgosException.InvalidAgentDefinition(
                    "AGENT.md 缺少结束的 " + DELIMITER + ": " + agentFile);
        }

        String frontYaml = content.substring(frontStart, frontEnd);
        String instructions = content.substring(frontEnd + DELIMITER.length()).strip();

        Profile profile = toProfile(parseYaml(frontYaml, agentFile), agentFile);
        return new AgentDefinition(profile, instructions);
    }

    /** 加载指定名称的 Agent。 */
    public AgentDefinition load(Path agentsRoot, String agentName) {
        if (agentName == null || agentName.isBlank()
                || agentName.contains("/") || agentName.contains("\\") || agentName.contains("..")) {
            throw new LytgosException.InvalidAgentDefinition("Agent 名称不合法: " + agentName);
        }
        return load(agentsRoot.resolve(agentName).resolve("AGENT.md"));
    }

    private Map<String, Object> parseYaml(String frontYaml, Path agentFile) {
        Object parsed;
        try {
            parsed = yaml.load(frontYaml);
        } catch (RuntimeException e) {
            throw new LytgosException.InvalidAgentDefinition(
                    "frontmatter 不是合法 YAML: " + agentFile + " → " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new LytgosException.InvalidAgentDefinition(
                    "frontmatter 为空或不是键值映射: " + agentFile);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Profile toProfile(Map<String, Object> front, Path agentFile) {
        Map<String, Object> providerMap = asMap(front.get("provider"));
        try {
            Profile.ProviderRef provider = new Profile.ProviderRef(
                    text(providerMap.get("name")),
                    text(providerMap.get("model")));

            return new Profile(
                    text(front.get("name")),
                    text(front.get("description")),
                    text(front.get("identity")),
                    provider,
                    asStrings(front.get("tools")),
                    asMcpServers(front.get("mcp_servers")),
                    asStrings(front.get("channels")),
                    asStrings(front.get("bootstrap")),
                    asMap(front.get("settings")));
        } catch (LytgosException.InvalidAgentDefinition e) {
            // 补上文件路径：同一份工作区里可能有多个 Agent，只报字段名用户不知道该改哪个
            throw new LytgosException.InvalidAgentDefinition(e.getMessage() + "（" + agentFile + "）");
        }
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
        return list.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<Profile.McpServer> asMcpServers(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Profile.McpServer> servers = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> entry = asMap(item);
            Map<String, String> env = new LinkedHashMap<>();
            asMap(entry.get("env")).forEach((k, v) -> env.put(k, String.valueOf(v)));
            servers.add(new Profile.McpServer(
                    text(entry.get("name")),
                    text(entry.get("url")),
                    text(entry.get("command")),
                    asStrings(entry.get("args")),
                    env));
        }
        return servers;
    }

    /** 一份完整的 Agent 定义：结构化配置 + 正文指令。 */
    public record AgentDefinition(Profile profile, String instructions) {
    }
}
