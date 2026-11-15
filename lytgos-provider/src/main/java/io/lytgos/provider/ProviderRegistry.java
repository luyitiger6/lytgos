package io.lytgos.provider;

import io.lytgos.core.LytgosException;
import io.lytgos.core.audit.LlmCallAuditor;
import io.lytgos.core.chat.ChatModel;
import io.lytgos.core.util.LogSafe;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * provider name → {@link ChatModel} 的显式映射，{@code ChatModel} 按需创建。
 *
 * <p>构造时只登记配置，不建任何模型：{@code provider list} 这类只读命令只需要名字与 模型 id，不该因为一个没设的环境变量而失败。{@link
 * #require(String)} 首次取用某个 provider 时才构造并缓存它——{@code ${ENV_VAR}} 占位的严格校验也在这一刻发生。
 *
 * <p>映射完全由配置决定，运行期无法增删；不使用 {@code ObjectProvider}、 {@code getBeansOfType} 或任何类型扫描（{@code AGENTS.md}
 * 4.2）。
 *
 * <p>找不到指定名字时抛出 {@link LytgosException.UnknownProviderException} 并列出已注册的名字—— 报错必须自带答案：用户看到「unknown
 * provider: dpseek」时，最想知道的就是「那有哪些」。
 *
 * <p>每个模型实例都包一层 {@link AuditedChatModel}：审计入口在 provider 边界上，
 * 不在任何业务调用点——只要模型被调用，就一定有一行审计，不依赖调用方记得埋点。
 */
public final class ProviderRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderRegistry.class);

  private final Map<String, ProviderConfig> configs;

  private final LlmCallAuditor auditor;

  private final Map<String, ChatModel> created = new ConcurrentHashMap<>();

  public ProviderRegistry(List<ProviderConfig> configs, LlmCallAuditor auditor) {
    Map<String, ProviderConfig> byName = new LinkedHashMap<>();
    for (ProviderConfig config : configs) {
      if (byName.putIfAbsent(config.name(), config) != null) {
        throw new LytgosException.InvalidConfigurationException("provider 重名: " + config.name());
      }
    }
    this.configs = Collections.unmodifiableMap(byName);
    this.auditor = Objects.requireNonNull(auditor, "auditor");
    // 登记结果来自配置而不是类路径：这行日志就是「启动时到底认了哪几个 provider」的现场证据。
    LOG.info("provider 已登记 {} 个: {}", byName.size(), describe(byName));
  }

  /** 形如 {@code deepseek(deepseek-flash), openai(gpt-4o)}，逐个经 LogSafe 处理。 */
  private static String describe(Map<String, ProviderConfig> configs) {
    return String.join(
        ", ",
        configs.values().stream()
            .map(
                config ->
                    LogSafe.sanitize(config.name()) + "(" + LogSafe.sanitize(config.model()) + ")")
            .toList());
  }

  /**
   * 按名取用；首次调用构造并缓存 {@link ChatModel}。
   *
   * <p>api-key 里残留 {@code ${VAR}} 说明变量没设（配置加载是宽松的）， 在连接之前就报错，且消息里带变量名——用户不需要猜是哪个 key。
   */
  public ChatModel require(String name) {
    if (configs.isEmpty()) {
      // 空列表时最该告诉用户的是「你还没配 provider」，而不是「未知 provider: xxx」
      throw new LytgosException.InvalidConfigurationException(
          "配置文件里没有任何 provider，无法提供 " + name + "；先在 providers 段添加一个");
    }
    ProviderConfig config = configs.get(name);
    if (config == null) {
      throw new LytgosException.UnknownProviderException(name, configs.keySet());
    }
    ChatModel model = created.get(name);
    if (model != null) {
      return model;
    }
    String missing = ProviderConfig.unresolvedVariable(config.apiKey());
    if (missing != null) {
      throw new LytgosException.InvalidConfigurationException(
          "环境变量未设置: " + missing + "（provider " + name + " 需要）");
    }
    ChatModel createdModel =
        created.computeIfAbsent(
            name,
            key ->
                new AuditedChatModel(
                    ChatModelFactory.create(config), key, config.model(), auditor));
    LOG.debug(
        "首次构造 ChatModel: provider={} model={}",
        LogSafe.sanitize(name),
        LogSafe.sanitize(config.model()));
    return createdModel;
  }

  /** 已配置的 provider 名；不触发任何模型构造。 */
  public Set<String> names() {
    return configs.keySet();
  }

  public int size() {
    return configs.size();
  }
}
