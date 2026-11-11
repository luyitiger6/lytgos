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
