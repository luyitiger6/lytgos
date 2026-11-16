package io.lytgos.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lytgos.core.LytgosException;
import io.lytgos.core.workspace.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 工作区起点的选择规则：属性为空回退启动目录，属性非空则以它为起点向上发现。
 *
 * <p>守的是 {@code serve --root} 与 {@code LYTGOS_ROOT} 的落点——两处都只是把值交给 {@code lytgos.root}
 * 属性，真正的目录决策在这里。
 */
class CoreBeansTest {

  @TempDir Path tempDir;

  @Test
  void fallsBackToWorkingDirectoryWhenPropertyIsEmpty() {
    assertEquals(Path.of("").toAbsolutePath().normalize(), CoreBeans.startDir("").toAbsolutePath());
    assertEquals(CoreBeans.startDir(""), CoreBeans.startDir(null));
    assertEquals(CoreBeans.startDir(""), CoreBeans.startDir("   "));
  }

  @Test
  void usesConfiguredRootAsStartDirectory() {
    assertEquals(tempDir, CoreBeans.startDir(tempDir.toString()));
  }

  @Test
  void discoversWorkspaceFromConfiguredRootInsteadOfWorkingDirectory() throws IOException {
    Path project = Files.createDirectories(tempDir.resolve("project"));
    Workspace.projectAt(project).initialize();
    Path nested = Files.createDirectories(project.resolve("agents").resolve("default"));

    Workspace discovered = new CoreBeans().workspaceLocator(nested.toString()).get();

    assertEquals(
        project.resolve(Workspace.DIRECTORY_NAME).toAbsolutePath().normalize(), discovered.root());
  }

  @Test
  void failsWhenConfiguredRootIsOutsideAnyWorkspace() throws IOException {
    Path outside = Files.createDirectories(tempDir.resolve("outside"));

    LytgosException.InvalidConfigurationException error =
        assertThrows(
            LytgosException.InvalidConfigurationException.class,
            () -> new CoreBeans().workspaceLocator(outside.toString()).get());

    assertTrue(error.getMessage().contains("当前目录不在 lytgos 工作区内"), error.getMessage());
  }
}
