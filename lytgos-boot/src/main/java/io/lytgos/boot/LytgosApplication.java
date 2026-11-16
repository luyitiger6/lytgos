package io.lytgos.boot;

import io.lytgos.runtime.RuntimeConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * lytgos 启动类。
 *
 * <p>业务装配只来自 {@link RuntimeConfiguration}——与 {@code lytgos serve} 引用的是同一个类，两个入口不存在第二套 Bean
 * 定义。{@code @SpringBootApplication} 的组件扫描只覆盖 {@code io.lytgos.boot}， 不会把 runtime 的配置类重复发现。
 */
@SpringBootApplication
@Import(RuntimeConfiguration.class)
public class LytgosApplication {

  public static void main(String[] args) {
    SpringApplication.run(LytgosApplication.class, args);
  }
}
