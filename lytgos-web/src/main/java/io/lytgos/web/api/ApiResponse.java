package io.lytgos.web.api;

import java.time.Instant;

/**
 * 统一响应体：所有 {@code /api/**} 端点的成功与失败响应都使用本结构。
 *
 * @param code 业务码，{@code "0"} 表示成功；失败时为稳定的错误码字面量
 * @param message 人类可读说明；成功时固定为 {@code "ok"}
 * @param data 载荷；失败时为 {@code null}
 * @param timestamp 响应生成时刻（UTC）
 */
public record ApiResponse<T>(String code, String message, T data, Instant timestamp) {

  /** 成功响应的业务码。 */
  public static final String OK_CODE = "0";

  /** 成功响应。 */
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(OK_CODE, "ok", data, Instant.now());
  }

  /** 失败响应：业务码 + 稳定文案，{@code data} 恒为 {@code null}。 */
  public static <T> ApiResponse<T> error(String code, String message) {
    return new ApiResponse<>(code, message, null, Instant.now());
  }
}
