package io.lytgos.web;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 测试专用上下文：不启用组件扫描，只走自动配置。
 *
 * <p>与两个生产入口保持一致——它们同样不扫描 {@code io.lytgos.web}，组件只经 {@link
 * io.lytgos.web.autoconfigure.LytgosWebAutoConfiguration} 进入容器。 若这里改用
 * {@code @SpringBootApplication}（隐含组件扫描），组件会被注册两次。
 *
 * <p>业务依赖由 {@link WebTestBeans} 显式提供：替代 {@code RuntimeConfiguration}， 不建数据库、不读真实凭据。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(WebTestBeans.class)
public class WebTestApplication {}
