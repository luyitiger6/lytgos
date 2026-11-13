package io.lytgos.cli;

import picocli.CommandLine;

/**
 * picocli 的子命令工厂：把「反射 new 一个命令对象」换成「先拿容器句柄，用到再取 Bean」。
 *
 * <p>关键事实（已实测坐实）：picocli 在 {@code CommandLine} 构造阶段就通过本工厂
 * 实例化全部子命令（含嵌套子命令），不是等到执行时。所以工厂里一旦调 {@code createBean}，
 * {@code --version} 也会连带 refresh 容器，启动从百毫秒级掉到秒级。</p>
 *
 * <p>因此优先走命令类的 {@code (LazyContext)} 构造器：只把未启动的句柄交给命令，
 * 不加载任何 Spring 类型。命令类故意不加 {@code @Component}——它们由 picocli 按次创建，
 * 不是容器单例。</p>
 *
 * <p>{@code createBean} 兜底分支留给没有该构造器的类：它们确实需要容器注入，
 * 代价是这类命令一被创建就会把容器拉起来，启动预算是它们的硬约束。</p>
 */
final class SpringCommandFactory implements CommandLine.IFactory {

    private final LazyContext context;

    SpringCommandFactory(LazyContext context) {
        this.context = context;
    }

    @Override
    public <K> K create(Class<K> cls) {
        try {
            return cls.getDeclaredConstructor(LazyContext.class).newInstance(context);
        } catch (NoSuchMethodException e) {
            return context.createBean(cls);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("命令类实例化失败: " + cls.getName(), e);
        }
    }
}
