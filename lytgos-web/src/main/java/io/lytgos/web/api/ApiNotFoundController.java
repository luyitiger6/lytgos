package io.lytgos.web.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api} 前缀下的统一 404。
 *
 * <p>用显式通配映射，而不是捕获 {@code NoResourceFoundException}：前者不绑定 Spring Boot 内部的异常类型（Boot 4 已换过类型），且未匹配的非
 * {@code /api} 请求完全不经过本类， 静态资源与 springdoc 的 404 保持框架默认行为。
 *
 * <p>精确路径的映射优先级高于通配，因此本类不会遮蔽任何真实端点。
 */
@RestController
public class ApiNotFoundController {

  /** 所有未匹配的 API 路径返回统一的 404 结构。 */
  @RequestMapping({"/api", "/api/**"})
  public ResponseEntity<ApiResponse<Void>> notFound(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error("LYTGOS_NOT_FOUND", "接口不存在：" + request.getRequestURI()));
  }
}
