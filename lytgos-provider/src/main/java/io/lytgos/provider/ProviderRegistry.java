package io.lytgos.provider;

import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * provider name → {@link ChatModel} 的显式映射。
 *
 * <p>构造完成后不可变，运行期无法增删。找不到指定名字时抛出
 * {@link LytgosException.UnknownProvider} 并列出已注册的名字——</p>
 * <p>报错必须自带答案：用户看到「unknown provider: dpseek」时，最想知道的就是「那有哪些」。</p>
 *
 * <p>构造 {@link ChatModel} 前先检查 api-key 里是否残留 {@code ${VAR}}：宽松解析
 * 会把缺失的环境变量原样保留，到这里还没解析掉就是变量没设，必须在连接之前报错。</p>
 */
public final class ProviderRegistry {

    private final Map<String, ChatModel> models;

    public ProviderRegistry(List<ProviderConfig> configs) {
        for (ProviderConfig config : configs) {
            String missing = ProviderConfig.unresolvedVariable(config.apiKey());
            if (missing != null) {
                throw new LytgosException.InvalidConfiguration(
                        "环境变量未设置: " + missing + "（provider " + config.name() + " 需要）");
            }
        }
        this.models = ChatModelFactory.createAll(configs);
    }

    public ChatModel require(String name) {
        ChatModel model = models.get(name);
        if (model == null) {
            throw new LytgosException.UnknownProvider(name, models.keySet());
        }
        return model;
    }

    public Set<String> names() {
        return models.keySet();
    }

    public int size() {
        return models.size();
    }
}
