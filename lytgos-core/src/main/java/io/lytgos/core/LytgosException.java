package io.lytgos.core;

import java.util.Set;

/**
 * lytgos 全部受控异常的基类。
 *
 * <p>子类型按「失败归属」划分，调用方只捕获关心的那一类；需要统一兜底时捕获本类。 刻意不按「技术原因」（IO、解析、网络）划分——那样的分类对上层没有决策价值。
 */
public class LytgosException extends RuntimeException {

  public LytgosException(String message) {
    super(message);
  }

  public LytgosException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * 沙箱校验未通过。
   *
   * <p>消息固定以 {@code sandbox} 开头：验收脚本靠这个字面量判定拦截确实生效， 而不是靠异常类型名——日志与 stderr 里看到的必须是同一句话。
   */
  public static class SandboxViolationException extends LytgosException {
    public SandboxViolationException(String message) {
      super("sandbox 拒绝：" + message);
    }
  }

  /** Agent 定义不合法。消息必须指明是哪个字段出的问题，否则用户无从修改。 */
  public static class InvalidAgentDefinitionException extends LytgosException {
    public InvalidAgentDefinitionException(String message) {
      super(message);
    }
  }

  /**
   * 工具参数不合法：格式错、缺必填字段、类型不对。
   *
   * <p>与 {@link SandboxViolationException} 刻意分开：沙箱违规是安全事件，向上抛并中断对话； 参数错误是可纠正错误，由 {@link
   * io.lytgos.core.tool.ToolExecutor} 回填给模型自己改。 两类失败混用同一个类型，会让格式错被当成攻击拦下，ReAct 的纠错能力就失效了。
   */
  public static class InvalidToolArgumentException extends LytgosException {
    public InvalidToolArgumentException(String message) {
      super(message);
    }
  }

  /** 配置里找不到指定的 provider。消息列出已注册的名字，避免用户靠猜。 */
  public static class UnknownProviderException extends LytgosException {
    public UnknownProviderException(String name, Set<String> registered) {
      super("未知 provider: " + name + "；已注册: " + registered);
    }
  }

  /** 配置缺失或不合法。 */
  public static class InvalidConfigurationException extends LytgosException {
    public InvalidConfigurationException(String message) {
      super(message);
    }
  }
}
