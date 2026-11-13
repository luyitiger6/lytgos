package io.lytgos.cli;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * CLI 的容器配置。
 *
 * <p>所有 Bean 都用 {@code @Bean} 方法或 {@code @Import} 显式登记，不做类路径扫描：
 * 扫描在几十个类上要花掉几十毫秒，而且会让「多引一个 starter 就凭空多出 Bean」的风险回来，
 * 与本项目「provider 显式映射」的约定相冲突。</p>
 *
 * <p>分组：{@link CoreBeans} 是无状态能力类（序列化、工作区定位、沙箱、会话、Agent 加载）；
 * {@link ProviderBeans} 只有 provider 注册表；{@link ToolBeans} 是内置工具；
 * {@link CliBeans} 是 per-Agent 对象的创建入口。per-Agent 对象本身不进容器。</p>
 *
 * <p>{@code proxyBeanMethods = false} 省掉 CGLIB 子类生成——这里没有互相调用的
 * {@code @Bean} 方法，不需要代理。</p>
 */
@Configuration(proxyBeanMethods = false)
@Import({ CoreBeans.class, ProviderBeans.class, ToolBeans.class, CliBeans.class })
public class CliConfiguration {
}
