package io.lytgos.core.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.audit.ToolInvocationAuditor;
import io.lytgos.core.audit.ToolInvocationRecord;
import io.lytgos.core.chat.ToolCall;
import io.lytgos.core.trace.TraceContext;
import io.lytgos.core.util.LogSafe;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具执行的唯一合法入口。
 *
 * <p>{@code AGENTS.md} 4.1 的另一半：模型只能「提出」工具调用，真正执行必须经过这里。 配额、白名单、沙箱、审计全部挂在这个方法上，所以任何绕过它的直接调用 {@link
 * Tool#execute} 都视为越权——04 号闸门会扫。
 *
 * <p>失败处理分两类，这个区分是刻意的：
 *
 * <ul>
 *   <li><b>沙箱违规</b>直接抛给上层，中断本次对话。这是安全事件，不是「模型试错」， 不能让模型看到错误后再换个域名继续试。
 *   <li><b>工具自身失败</b>（超时、上游 5xx、参数不合法）转成文本回填历史， 让模型自己决定是改参数重试还是换个做法——这正是 ReAct 的纠错能力所在。
 * </ul>
 *
 * <p>无论哪一类，执行结束后都先写一行 {@code tool_invocations} 再决定返回值还是抛异常： 审计写入放在工具异常处理之外，写库失败不会被「工具执行失败 → 回填文本」的
 * catch 吞掉。
 */
public final class ToolExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);

  private static final TypeReference<Map<String, Object>> ARGUMENT_TYPE = new TypeReference<>() {};

  private final ToolRegistry registry;
  private final ObjectMapper mapper;
  private final ToolInvocationAuditor auditor;

  public ToolExecutor(ToolRegistry registry, ObjectMapper mapper, ToolInvocationAuditor auditor) {
    this.registry = registry;
    this.mapper = mapper;
    this.auditor = Objects.requireNonNull(auditor, "auditor");
  }

  /**
   * 执行一次工具调用。
   *
   * @param sessionId 会话键，与 {@code sessions.key} 一致
   * @param turnId 本轮对话标识，与同轮模型调用共享
   * @return 回填给模型的结果文本，成功与「可恢复的失败」都走返回值
   * @throws LytgosException.SandboxViolationException 沙箱拒绝时抛出，不返回
   * @throws LytgosException.AuditWriteException 审计写入失败时抛出，覆盖本轮任何工具结果
   */
  public String execute(String sessionId, String turnId, ToolCall call) {
    long startNanos = System.nanoTime();

    Tool tool = registry.find(call.name()).orElse(null);
    if (tool == null) {
      audit(
          sessionId,
          turnId,
          call,
          startNanos,
          false,
          "模型请求了未注册的工具: " + call.name() + "；已注册: " + registry.names());
      throw new LytgosException.InvalidConfigurationException(
          "模型请求了未注册的工具: " + call.name() + "；已注册: " + registry.names());
    }

    Map<String, Object> arguments;
    try {
      arguments = parseArguments(call);
    } catch (JsonProcessingException e) {
      LOG.warn("工具参数不是合法 JSON，回填给模型: {}", LogSafe.sanitize(call.arguments()));
      String output = "工具参数不是合法 JSON，请修正后重试。收到的原文: " + call.arguments();
      audit(sessionId, turnId, call, startNanos, false, "参数不是合法 JSON");
      return output;
    }
    LOG.info("tool_call: {} args={}", LogSafe.sanitize(call.name()), LogSafe.sanitize(arguments));

    String output = null;
    RuntimeException failure = null;
    try {
      output = tool.execute(arguments);
    } catch (RuntimeException e) {
      failure = e;
    }

    if (failure instanceof LytgosException.SandboxViolationException sandbox) {
      audit(sessionId, turnId, call, startNanos, false, sandbox.getMessage());
      throw sandbox;
    }
    if (failure != null) {
      LOG.warn(
          "工具 {} 执行失败，回填给模型: {}",
          LogSafe.sanitize(call.name()),
          LogSafe.sanitize(failure.getMessage()));
      audit(sessionId, turnId, call, startNanos, false, failure.getMessage());
      return "工具执行失败: " + failure.getMessage();
    }

    audit(sessionId, turnId, call, startNanos, true, null);
    return output;
  }

  private void audit(
      String sessionId,
      String turnId,
      ToolCall call,
      long startNanos,
      boolean success,
      String errorMessage) {
    long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
    ToolInvocationRecord record =
        new ToolInvocationRecord(
            sessionId,
            turnId,
            // 与同轮模型调用共享同一个 traceId；无请求上下文时合法为 null。
            TraceContext.current(),
            call.name(),
            call.arguments(),
            durationMs,
            success,
            errorMessage,
            Instant.now());
    try {
      auditor.record(record);
    } catch (RuntimeException auditError) {
      throw new LytgosException.AuditWriteException(
          "审计写入失败（tool_invocations），本轮已中止: " + auditError.getMessage(), auditError);
    }
  }

  private Map<String, Object> parseArguments(ToolCall call) throws JsonProcessingException {
    String raw = call.arguments();
    if (raw == null || raw.isBlank()) {
      return Map.of();
    }
    return mapper.readValue(raw, ARGUMENT_TYPE);
  }
}
