package io.lytgos.core.audit;

/**
 * 模型调用的审计端口。
 *
 * <p>实现放 {@code lytgos-storage}，装配放 {@code lytgos-cli}。刻意不提供 noop 默认实现：
 * 生产装配路径上不存在「忘了注入就静默不审计」的退路，想跳过只能显式伪造测试替身。
 *
 * <p>实现必须抛异常而不是吞掉写库失败——由 {@code AuditedChatModel} 包装成 {@link
 * io.lytgos.core.LytgosException.AuditWriteException}，让本轮对话显性失败。
 */
@FunctionalInterface
public interface LlmCallAuditor {

  void record(LlmCallRecord record);
}
