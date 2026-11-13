package io.lytgos.core.workspace;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 工作区的懒定位器。
 *
 * <p>构造时不碰磁盘——{@code lytgos init} 要在还没有 {@code .lytgos/} 的目录里跑通，
 * 任何「构造即发现」的写法都会让 init 自己起不来。第一次 {@link #get()} 才
 * {@link Workspace#discover(Path)}，结果缓存，全进程只发现一次。</p>
 *
 * <p>由容器以单例登记；命令类与能力 Bean 都从这里拿工作区，
 * 保证 {@code tool list} 与 {@code chat} 看到的是同一个。</p>
 */
public final class WorkspaceLocator {

    private final Path startDir;

    private volatile Workspace cached;

    public WorkspaceLocator(Path startDir) {
        this.startDir = Objects.requireNonNull(startDir, "startDir");
    }

    /** 首次调用做目录向上查找并缓存；不在工作区内时抛配置错误。 */
    public Workspace get() {
        Workspace local = cached;
        if (local == null) {
            synchronized (this) {
                local = cached;
                if (local == null) {
                    local = Workspace.discover(startDir);
                    cached = local;
                }
            }
        }
        return local;
    }
}
