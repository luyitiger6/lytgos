package io.lytgos.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 迁移器只做一件事：把老库补到当前结构。
 *
 * <p>「老结构」在测试里手写而不是从 classpath 加载历史版本——建表脚本只保留当前形态， 历史形态由本类的常量固定下来，一旦有人改动它，这组测试必然红。
 */
@DisplayName("SqliteSchemaMigrator：老库补 trace_id、幂等、不改写历史行")
class SqliteSchemaMigratorTest {

  @TempDir Path tempDir;

  private HikariDataSource dataSource;

  @BeforeEach
  void setUp() {
    HikariConfig config = new HikariConfig();
    config.setPoolName("lytgos-migrator-test");
    config.setDriverClassName("org.sqlite.JDBC");
    config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("lytgos.db"));
    config.setMaximumPoolSize(1);
    dataSource = new HikariDataSource(config);
  }

  @AfterEach
  void tearDown() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @Test
  @DisplayName("老结构库补出 trace_id 列与索引，历史行保持 NULL")
  void addsTraceColumnToLegacySchema() throws SQLException {
    createLegacySchema();
    insertLegacyLlmCall();

    new SqliteSchemaMigrator(dataSource).migrate();

    assertEquals(1, llmCallsTraceColumnCount(), "llm_calls 必须恰好有一个 trace_id 列");
    assertEquals(1, toolInvocationsTraceColumnCount(), "tool_invocations 必须恰好有一个 trace_id 列");
    assertEquals(1, indexCount("idx_llm_calls_trace"));
    assertEquals(1, indexCount("idx_tool_invocations_trace"));
    assertNull(legacyLlmTraceId(), "历史行本来就没有 traceId，迁移不得伪造");
  }

  @Test
  @DisplayName("已含新列的库（建表脚本建出的）执行不报错")
  void isNoOpOnCurrentSchema() {
    applyCurrentSchema();

    assertDoesNotThrow(new SqliteSchemaMigrator(dataSource)::migrate);
    assertDoesNotThrow(() -> assertEquals(1, llmCallsTraceColumnCount()));
    assertDoesNotThrow(() -> assertEquals(1, indexCount("idx_llm_calls_trace")));
  }

  @Test
  @DisplayName("连续执行两次不报错，列与索引各一份")
  void runsTwiceIdempotently() throws SQLException {
    createLegacySchema();
    SqliteSchemaMigrator migrator = new SqliteSchemaMigrator(dataSource);

    migrator.migrate();
    assertDoesNotThrow(migrator::migrate, "第二次执行必须是 no-op，重复 ALTER 会被 SQLite 拒绝");

    assertEquals(1, llmCallsTraceColumnCount());
    assertEquals(1, toolInvocationsTraceColumnCount());
    assertEquals(1, indexCount("idx_llm_calls_trace"));
    assertEquals(1, indexCount("idx_tool_invocations_trace"));
  }

  /** 当前版本的建表脚本：新库路径，表本身已含 trace_id。 */
  private void applyCurrentSchema() {
    new ResourceDatabasePopulator(new ClassPathResource("lytgos-schema.sql")).execute(dataSource);
  }

  /** 引入 trace_id 之前的两张表：表名与列与当时一致，唯独没有 trace_id。 */
  private void createLegacySchema() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE llm_calls ("
              + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
              + " session_id TEXT NOT NULL,"
              + " turn_id TEXT NOT NULL,"
              + " provider TEXT NOT NULL,"
              + " model TEXT NOT NULL,"
              + " prompt_tokens INTEGER NOT NULL,"
              + " completion_tokens INTEGER NOT NULL,"
              + " total_tokens INTEGER NOT NULL,"
              + " duration_ms INTEGER NOT NULL,"
              + " success INTEGER NOT NULL,"
              + " error_message TEXT,"
              + " created_at TEXT NOT NULL)");
      statement.execute(
          "CREATE TABLE tool_invocations ("
              + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
              + " session_id TEXT NOT NULL,"
              + " turn_id TEXT NOT NULL,"
              + " tool_name TEXT NOT NULL,"
              + " arguments TEXT,"
              + " duration_ms INTEGER NOT NULL,"
              + " success INTEGER NOT NULL,"
              + " error_message TEXT,"
              + " created_at TEXT NOT NULL)");
    }
  }

  private void insertLegacyLlmCall() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO llm_calls (session_id, turn_id, provider, model, prompt_tokens,"
              + " completion_tokens, total_tokens, duration_ms, success, error_message, created_at)"
              + " VALUES ('cli:local:legacy', 'turn-old', 'deepseek', 'deepseek-flash', 1, 2, 3,"
              + " 4, 1, NULL, '2026-01-01T00:00:00Z')");
    }
  }

  private String legacyLlmTraceId() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT trace_id FROM llm_calls")) {
      assertTrue(rows.next(), "历史行必须还在");
      return rows.getString("trace_id");
    }
  }

  /** 每张表各自内联 PRAGMA 字面量：静态扫描能证明语句是编译期常量。 */
  private int llmCallsTraceColumnCount() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet columns = statement.executeQuery("PRAGMA table_info(llm_calls)")) {
      return countTraceColumn(columns);
    }
  }

  private int toolInvocationsTraceColumnCount() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet columns = statement.executeQuery("PRAGMA table_info(tool_invocations)")) {
      return countTraceColumn(columns);
    }
  }

  private static int countTraceColumn(ResultSet columns) throws SQLException {
    int count = 0;
    while (columns.next()) {
      if ("trace_id".equals(columns.getString("name"))) {
        count++;
      }
    }
    return count;
  }

  private int indexCount(String indexName) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?")) {
      statement.setString(1, indexName);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }
  }
}
