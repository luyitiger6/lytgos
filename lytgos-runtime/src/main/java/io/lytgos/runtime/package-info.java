/**
 * 业务装配：CLI 与 Web 两个入口共享同一套 Bean 定义。
 *
 * <p>本模块不依赖任何入口模块，也不含 HTTP 或命令行类型。它的职责只有一个——回答「一个 Agent 跑起来需要哪些对象」：工作区定位、配置绑定、沙箱、 持久化、Provider
 * 注册表、内置工具、per-Agent 运行时。
 *
 * <p>为什么不留在 {@code lytgos-cli}：{@code lytgos-web} 的控制器需要这些对象，而 {@code serve} 又需要 {@code lytgos-web}
 * 的容器，两边直接互相依赖会成环。把装配下沉一层，入口只负责「怎么启动」，不负责「装配什么」。
 */
package io.lytgos.runtime;
