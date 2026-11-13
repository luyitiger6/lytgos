package io.lytgos.provider;

import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.ChatModel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * provider name → {@link ChatModel} 的显式映射，{@code ChatModel} 按需创建。
 *
 * <p>构造时只登记配置，不建任何模型：{@code provider list} 这类只读命令只需要名字与
 * 模型 id，不该因为一个没设的环境变量而失败。{@link #require(String)} 首次取用某个
 * provider 时才构造并缓存它——{@code ${ENV_VAR}} 占位的严格校验也在这一刻发生。</p>
 *
 * <p>映射完全由配置决定，运行期无法增删；不使用 {@code ObjectProvider}、
 * {@code getBeansOfType} 或任何类型扫描（{@code AGENTS.md} 4.2）。</p>
 *
 * <p>找不到指定名字时抛出 {@link LytgosException.UnknownProvider} 并列出已注册的名字——
 * 报错必须自带答案：用户看到「unknown provider: dpseek」时，最想知道的就是「那有哪些」。</p>
 */
public final class ProviderRegistry {

    private final Map<String, ProviderConfig> configs;

    private final Map<String, ChatModel> created = new ConcurrentHashMap<>();

    public ProviderRegistry(List<ProviderConfig> configs) {
        Map<String, ProviderConfig> byName = new LinkedHashMap<>();
        for (ProviderConfig config : configs) {
            if (byName.putIfAbsent(config.name(), config) != null) {
                throw new LytgosException.InvalidConfiguration("provider 重名: " + config.name());
            }
        }
        this.configs = Collections.unmodifiableMap(byName);
    }

    /**
     * 按名取用；首次调用构造并缓存 {@link ChatModel}。
     *
     * <p>api-key 里残留 {@code ${VAR}} 说明变量没设（配置加载是宽松的），
     * 在连接之前就报错，且消息里带变量名——用户不需要猜是哪个 key。</p>
     */
    public ChatModel require(String name) {
        if (configs.isEmpty()) {
            // 空列表时最该告诉用户的是「你还没配 provider」，而不是「未知 provider: xxx」
            throw new LytgosException.InvalidConfiguration(
                    "配置文件里没有任何 provider，无法提供 " + name + "；先在 providers 段添加一个");
        }
        ProviderConfig config = configs.get(name);
        if (config == null) {
            throw new LytgosException.UnknownProvider(name, configs.keySet());
        }
        ChatModel model = created.get(name);
        if (model != null) {
            return model;
        }
        String missing = ProviderConfig.unresolvedVariable(config.apiKey());
        if (missing != null) {
            throw new LytgosException.InvalidConfiguration(
                    "环境变量未设置: " + missing + "（provider " + name + " 需要）");
        }
        return created.computeIfAbsent(name, key -> ChatModelFactory.create(config));
    }

    /** 已配置的 provider 名；不触发任何模型构造。 */
    public Set<String> names() {
        return configs.keySet();
    }

    public int size() {
        return configs.size();
    }
}
