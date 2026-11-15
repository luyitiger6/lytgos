package io.lytgos.core.audit;

/**
 * 工具执行的审计端口。
 *
 * <p>与 {@link LlmCallAuditor} 分开而不是合成一个「Auditor」：两者的记录结构、写入频率与 查询方式都不同，合成一个接口只会逼实现方写 {@code
 * instanceof} 分支。
 */
@FunctionalInterface
public interface ToolInvocationAuditor {

  void record(ToolInvocationRecord record);
}
