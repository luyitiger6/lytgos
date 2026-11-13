package io.lytgos.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import picocli.CommandLine.IVersionProvider;

/**
 * 从构建产物中的 {@code pom.properties} 读取版本号。
 *
 * <p>避免在源码里硬编码版本，打包后版本号与 pom 始终一致。</p>
 */
public class VersionProvider implements IVersionProvider {

    private static final String UNKNOWN = "unknown";

    private static final String POM_PROPERTIES =
            "/META-INF/maven/io.lytgos/lytgos-cli/pom.properties";

    /**
     * 本类是 picocli 的基础设施，不依赖容器；但必须提供这个构造器：
     * picocli 在 {@code CommandLine} 构造阶段通过 {@link SpringCommandFactory} 实例化版本提供器，
     * 只有无参构造时会退到 {@code createBean}，把容器拉起来——{@code --version} 就会从
     * 百毫秒级掉到秒级。句柄在这里只充当「不触发容器」的分支标记。
     */
    VersionProvider(LazyContext ignored) {
    }

    @Override
    public String[] getVersion() {
        return new String[]{"lytgos " + readVersion()};
    }

    private String readVersion() {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream(POM_PROPERTIES)) {
            if (in == null) {
                return UNKNOWN;
            }
            properties.load(in);
            return properties.getProperty("version", UNKNOWN);
        } catch (IOException e) {
            return UNKNOWN;
        }
    }
}
