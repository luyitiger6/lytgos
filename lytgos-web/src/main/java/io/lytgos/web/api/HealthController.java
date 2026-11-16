package io.lytgos.web.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 服务存活探针：不依赖任何下游，进程能响应即为 UP。 */
@RestController
public class HealthController {

  /** 存活状态与版本。 */
  @GetMapping("/api/v1/health")
  public ApiResponse<HealthView> health() {
    return ApiResponse.ok(new HealthView("UP", SystemInfoController.version()));
  }

  /** 健康检查的对外视图。 */
  public record HealthView(String status, String version) {}
}
