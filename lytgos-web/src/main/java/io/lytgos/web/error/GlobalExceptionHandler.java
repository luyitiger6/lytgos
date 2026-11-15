package io.lytgos.web.error;

import io.lytgos.core.LytgosException;
import io.lytgos.web.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常映射：把 core 的受控异常体系翻译为稳定的 HTTP 状态与错误码。
 *
 * <p>{@code basePackages} 必须限定在 {@code io.lytgos.web}：不限定会接管 actuator 与 springdoc
 * 的异常处理，把它们的标准错误响应改写成 {@link ApiResponse} 结构。
 *
 * <p>4xx 保留原异常文案（用户可据此纠正）；5xx 一律返回固定文案，内部细节只进日志。
 */
@RestControllerAdvice(basePackages = "io.lytgos.web")
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 沙箱违规是安全事件，映射 403。 */
  @ExceptionHandler(LytgosException.SandboxViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleSandboxViolation(
      LytgosException.SandboxViolationException exception) {
    return respond(HttpStatus.FORBIDDEN, "LYTGOS_SANDBOX_VIOLATION", exception.getMessage());
  }

  @ExceptionHandler(LytgosException.InvalidAgentDefinitionException.class)
  public ResponseEntity<ApiResponse<Void>> handleInvalidAgentDefinition(
      LytgosException.InvalidAgentDefinitionException exception) {
    return respond(HttpStatus.BAD_REQUEST, "LYTGOS_INVALID_AGENT", exception.getMessage());
  }

  @ExceptionHandler(LytgosException.InvalidToolArgumentException.class)
  public ResponseEntity<ApiResponse<Void>> handleInvalidToolArgument(
      LytgosException.InvalidToolArgumentException exception) {
    return respond(HttpStatus.BAD_REQUEST, "LYTGOS_INVALID_TOOL_ARGUMENT", exception.getMessage());
  }

  @ExceptionHandler(LytgosException.UnknownProviderException.class)
  public ResponseEntity<ApiResponse<Void>> handleUnknownProvider(
      LytgosException.UnknownProviderException exception) {
    return respond(HttpStatus.BAD_REQUEST, "LYTGOS_UNKNOWN_PROVIDER", exception.getMessage());
  }

  @ExceptionHandler(LytgosException.InvalidConfigurationException.class)
  public ResponseEntity<ApiResponse<Void>> handleInvalidConfiguration(
      LytgosException.InvalidConfigurationException exception) {
    log.error("配置非法", exception);
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, "LYTGOS_INVALID_CONFIGURATION", "服务器内部错误");
  }

  /** 未显式映射的受控异常：按内部错误处理，文案不外泄。 */
  @ExceptionHandler(LytgosException.class)
  public ResponseEntity<ApiResponse<Void>> handleLytgos(LytgosException exception) {
    log.error("未映射的受控异常", exception);
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, "LYTGOS_INTERNAL", "服务器内部错误");
  }

  /** 未知异常兜底：堆栈只进日志，响应给固定文案。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
    log.error("未处理异常", exception);
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, "LYTGOS_INTERNAL", "服务器内部错误");
  }

  private static ResponseEntity<ApiResponse<Void>> respond(
      HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(ApiResponse.error(code, message));
  }
}
