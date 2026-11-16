package io.lytgos.web.api;

import io.lytgos.core.session.Session;
import io.lytgos.persona.AgentLoader;
import io.lytgos.runtime.AgentInvoker;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 无状态 Agent 调用：不读、不写任何已有会话。
 *
 * <p>一次性会话的 key 用 {@code invoke:<uuid>}，保证并发请求之间不共享可变历史； 它不经过 {@code SessionStore}，因此不会出现在会话列表里。
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

  private final AgentInvoker invoker;

  public AgentController(AgentInvoker invoker) {
    this.invoker = invoker;
  }

  /** 用一次性会话执行一轮对话，返回模型最终文本。 */
  @PostMapping("/{name}/invoke")
  public ResponseEntity<ApiResponse<InvokeResult>> invoke(
      @PathVariable String name, @RequestBody(required = false) InvokeRequest request) {
    String input = request == null ? null : request.input();
    if (input == null || input.isBlank()) {
      return ResponseEntity.badRequest()
          .body(ApiResponse.error("LYTGOS_INVALID_REQUEST", "input 不能为空"));
    }
    // 名称先过同一套规则：非法名字不该走到加载器，更不该落到文件系统上
    AgentLoader.validateName(name);
    String reply = invoker.run(name, new Session("invoke:" + UUID.randomUUID()), input);
    return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(new InvokeResult(name, reply)));
  }

  /** 无状态调用的请求体。 */
  public record InvokeRequest(String input) {}

  /** 无状态调用的结果。 */
  public record InvokeResult(String agent, String reply) {}
}
