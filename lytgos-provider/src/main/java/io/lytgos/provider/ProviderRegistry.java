package io.lytgos.provider;

import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.ChatModel;

import java.util.Map;
import java.util.Set;

/**
 * provider name → {@link ChatModel} 的显式映射。
 *
 * <p>构造完成后不可变，运行期无法增删。找不到指定名字时抛出
 * {@link LytgosException.UnknownProvider} 并列出已注册的名字——</p>
 * <p>报错必须自带答案：用户看到「unknown provider: dpseek」时，最想知道的就是「那有哪些」。</p>
 */
public final class ProviderRegistry {

    private final Map<String, ChatModel> models;

    public ProviderRegistry(Map<String, ChatModel> models) {
        this.models = Map.copyOf(models);
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
