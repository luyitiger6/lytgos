package io.lytgos.web.autoconfigure;

import io.lytgos.web.api.ApiNotFoundController;
import io.lytgos.web.api.SystemInfoController;
import io.lytgos.web.error.GlobalExceptionHandler;
import io.lytgos.web.trace.TraceIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
  SystemInfoController.class,
  TraceIdFilter.class
})
public class LytgosWebAutoConfiguration {}
