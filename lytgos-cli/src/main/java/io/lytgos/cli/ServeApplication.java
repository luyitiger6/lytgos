package io.lytgos.cli;

import io.lytgos.runtime.RuntimeConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * {@code lytgos serve} 的 Spring Boot 配置类。
 *
 * <p>业务装配与 {@code LytgosApplication} 共用 {@link RuntimeConfiguration}，不存在第二套 Bean 定义；HTTP 契约组件由
 * {@code lytgos-web} 的自动配置经 {@code AutoConfiguration.imports} 登记。
 *
 * <p>刻意不用 {@code @SpringBootApplication}：它的组件扫描会扫过整个 {@code io.lytgos.cli}，而 CLI 命令类不是 Bean，
 * 扫描只会白花启动时间；{@code @EnableAutoConfiguration} 已足够把 Web 层拉起来。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(RuntimeConfiguration.class)
public class ServeApplication {}
