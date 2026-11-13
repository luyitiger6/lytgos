package io.lytgos.cli;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 懒启动的 Spring 容器。
 *
 * <p>第一次真正需要 Bean 时才创建容器并 {@code refresh()}。这不是性能优化，而是分层启动的支点：
 * {@code --version} / {@code --help} 由 picocli 在解析阶段处理，不会创建任何子命令，
 * 因此走不到这里，进程里也不会有任何 Spring 类型被加载。</p>
 *
 * <p>容器首次 refresh 实测约 815ms（JDK 21 / Spring 7 / 本机），而同一容器的稳态 refresh
 * 只要 9-19ms——首次的成本几乎全在 Spring 自身的类加载与注解基础设施初始化上。所以只要有
 * 任何一条路径在进程启动时就 refresh，CLI 就会从百毫秒级掉到秒级。</p>
 *
 * <p>配置类以字符串形式延迟到 {@link #context()} 里再解析：直接写 {@code CliConfiguration.class}
 * 会让本类的常量池引用它，进而让 JVM 在加载本类时就去解析 {@code @Configuration} 等注解类型，
 * 白白把 Spring 类加载提前到启动阶段。</p>
 */
public final class LazyContext implements AutoCloseable {

    private static final String CONFIGURATION_CLASS = "io.lytgos.cli.CliConfiguration";

    private AnnotationConfigApplicationContext context;

    /**
     * 按类型取容器里的单例 Bean。首次调用会触发容器启动。
     */
    public synchronized <T> T getBean(Class<T> type) {
        return context().getBean(type);
    }

    /**
     * 创建并注入一个新实例，但不登记为单例。picocli 的子命令走这条路：
     * 它们各自持有一个实例，不该在容器里多留一份没人用的。
     */
    public synchronized <T> T createBean(Class<T> type) {
        return context().getAutowireCapableBeanFactory().createBean(type);
    }

    @Override
    public synchronized void close() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    private AnnotationConfigApplicationContext context() {
        if (context == null) {
            AnnotationConfigApplicationContext created = new AnnotationConfigApplicationContext();
            created.register(configuration());
            try {
                created.refresh();
            } catch (RuntimeException refreshFailure) {
                // refresh 失败时 Spring 不会替你释放已创建的单例：数据源、文件句柄、
                // MCP 子进程都会挂着。close 自身再失败只作为 suppressed 附上，
                // 不能它盖住真正的失败原因。
                try {
                    created.close();
                } catch (RuntimeException closeFailure) {
                    refreshFailure.addSuppressed(closeFailure);
                }
                throw refreshFailure;
            }
            context = created;
        }
        return context;
    }

    private static Class<?> configuration() {
        try {
            return Class.forName(CONFIGURATION_CLASS);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("容器配置类缺失: " + CONFIGURATION_CLASS, e);
        }
    }
}
