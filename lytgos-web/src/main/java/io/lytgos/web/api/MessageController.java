package io.lytgos.web.api;

import io.lytgos.core.session.Session;
import io.lytgos.core.session.SessionStore;
import io.lytgos.runtime.AgentInvoker;
import io.lytgos.runtime.SessionKeys;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发消息：把一条用户输入追加进会话并跑完一轮对话。
 *
 * <p>agent 由会话标识反解，不信任请求体：key 是会话与运行时之间唯一的对应关系， 再收一个 agent 字段就等于允许「A 的会话用 B 的模型」。
 *
 * <p>落盘放在 {@code finally}：即使本轮中途失败，历史里已经追加的输入与工具结果也必须落盘，
 * 否则下一次请求会在「有调用、无结果」的残局上继续，错误从一个工具蔓延成整段会话不可用。
 */
@RestController
public class MessageController {

  private final SessionStore sessions;
  private final AgentInvoker invoker;

  public MessageController(SessionStore sessions, AgentInvoker invoker) {
    this.sessions = sessions;
    this.invoker = invoker;
  }

  /** 向指定会话发送一条消息，返回模型最终文本与历史长度。 */
  @PostMapping("/api/v1/sessions/{id}/messages")
  public ResponseEntity<ApiResponse<SendResult>> send(
      @PathVariable String id, @RequestBody(required = false) SendMessageRequest request) {
    String message = request == null ? null : request.message();
    if (message == null || message.isBlank()) {
      return badRequest("message 不能为空");
    }
    if (!sessions.exists(id)) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(ApiResponse.error("LYTGOS_NOT_FOUND", "会话不存在：" + id));
    }
    String agent = SessionKeys.parse(id);
    Session session = sessions.get(id);
    String reply;
    try {
      reply = invoker.run(agent, session, message);
    } finally {
      sessions.save(session);
    }
    return ResponseEntity.ok(ApiResponse.ok(new SendResult(id, reply, session.size())));
  }

  private static ResponseEntity<ApiResponse<SendResult>> badRequest(String message) {
    return ResponseEntity.badRequest().body(ApiResponse.error("LYTGOS_INVALID_REQUEST", message));
  }

  /** 发消息的请求体。 */
  public record SendMessageRequest(String message) {}

  /** 发消息的结果：{@code messages} 是落盘后的历史条数。 */
  public record SendResult(String sessionId, String reply, int messages) {}
}
