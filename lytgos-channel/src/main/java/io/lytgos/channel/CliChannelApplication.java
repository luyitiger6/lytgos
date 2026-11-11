package io.lytgos.channel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CLI 渠道的独立启动入口。
 *
 * <p>脱离 {@code lytgos-boot} 单独运行，用于调试该渠道本身。
 * 完整装配（provider / tool / storage）由 {@code lytgos-boot} 负责，
 * 这里只起一个最小上下文，不引入其他能力模块。</p>
 */
@SpringBootApplication(scanBasePackages = "io.lytgos.channel")
public class CliChannelApplication {

    public static void main(String[] args) {
        SpringApplication.run(CliChannelApplication.class, args);
    }
}
