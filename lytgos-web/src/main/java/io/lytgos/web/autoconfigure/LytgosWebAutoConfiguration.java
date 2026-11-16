package io.lytgos.web.autoconfigure;

import io.lytgos.web.api.AdminPageController;
import io.lytgos.web.api.AgentController;
import io.lytgos.web.api.ApiNotFoundController;
import io.lytgos.web.api.HealthController;
import io.lytgos.web.api.InfoController;
import io.lytgos.web.api.MemoryController;
import io.lytgos.web.api.MessageController;
import io.lytgos.web.api.ProfileController;
import io.lytgos.web.api.SessionController;
import io.lytgos.web.api.SystemInfoController;
import io.lytgos.web.api.ToolController;
import io.lytgos.web.error.GlobalExceptionHandler;
import io.lytgos.web.trace.TraceIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * lytgos-web 的自动配置入口。
 *
 * <p>两个可执行入口（{@code lytgos.jar} 与 {@code lytgos-channel-boot.jar}）的组件扫描都只覆盖 各自的包，依赖 jar
 * 里的组件不会被自动发现；本类通过 {@code AutoConfiguration.imports} 注册，让两边同时获得 Web 契约组件，入口类零改动。
 *
 * <p>不用条件装配注解：组件一旦引入就必须生效，条件化会让「配置是否到位」变得不可断言 （同时违反 AGENTS.md 4.2 的显式装配约定）。
 */
@AutoConfiguration
@Import({
  GlobalExceptionHandler.class,
  ApiNotFoundController.class,
  AgentController.class,
  HealthController.class,
  InfoController.class,
  MemoryController.class,
  MessageController.class,
  ProfileController.class,
  SessionController.class,
  SystemInfoController.class,
  ToolController.class,
  TraceIdFilter.class
})
public class LytgosWebAutoConfiguration {

  /**
   * 管理台控制器：页面读取会抛异常，改为在装配期构造。
   *
   * <p>不用 {@code @Import} 直接注册：{@code AdminPageController.load()} 读取失败应当让启动失败，
   * 失败语义由本方法表达，控制器构造器保持不抛异常。
   */
  @Bean
  AdminPageController adminPageController() {
    return AdminPageController.load();
  }
}
