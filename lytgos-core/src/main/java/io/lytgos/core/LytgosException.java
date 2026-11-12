package io.lytgos.core;

import java.util.Set;

/**
 * lytgos 全部受控异常的基类。
 *
 * <p>子类型按「失败归属」划分，调用方只捕获关心的那一类；需要统一兜底时捕获本类。
 * 刻意不按「技术原因」（IO、解析、网络）划分——那样的分类对上层没有决策价值。</p>
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
     * <p>消息固定以 {@code sandbox} 开头：验收脚本靠这个字面量判定拦截确实生效，
     * 而不是靠异常类型名——日志与 stderr 里看到的必须是同一句话。</p>
     */
    public static class SandboxViolation extends LytgosException {
        public SandboxViolation(String message) {
            super("sandbox 拒绝：" + message);
        }
    }

    /** Agent 定义不合法。消息必须指明是哪个字段出的问题，否则用户无从修改。 */
    public static class InvalidAgentDefinition extends LytgosException {
        public InvalidAgentDefinition(String message) {
            super(message);
        }
    }

    /** 配置里找不到指定的 provider。消息列出已注册的名字，避免用户靠猜。 */
    public static class UnknownProvider extends LytgosException {
        public UnknownProvider(String name, Set<String> registered) {
            super("未知 provider: " + name + "；已注册: " + registered);
        }
    }

    /** 配置缺失或不合法。 */
    public static class InvalidConfiguration extends LytgosException {
        public InvalidConfiguration(String message) {
            super(message);
        }
    }
}
