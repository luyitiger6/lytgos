package io.lytgos.storage;

import io.lytgos.core.LytgosException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 老库补列：给已有审计表加上新版本引入的列。
 *
 * <p>建表脚本全部是 {@code CREATE TABLE IF NOT EXISTS}——它对已经存在的表什么也不做，
 * 所以「新增一列」这件事脚本管不到：新库建表时就带上了，老库只能靠这里补。
 *
 * <p>SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}，重复 ALTER 会直接报 duplicate column， 因此先读 {@code PRAGMA
 * table_info} 判断列是否已存在。
 *
 * <p>每条 SQL 都以字面量直接写在 execute 调用里：表名不参与拼接，同时让静态扫描能证明 「传给 JDBC 的语句是编译期常量」。把 SQL
 * 抽成常量字段反而会失去这个证明——FindSecBugs 的常量传播不穿过字段读取，会把常量池里的语句判成「非恒定 SQL」。
 *
 * <p>迁移只补列与索引，不改写任何已有行：老审计行的 {@code trace_id} 保持 NULL——那批日志 本来就没有 traceId，伪造一个等于污染证据。
 */
public final class SqliteSchemaMigrator {

  private static final Logger LOG = LoggerFactory.getLogger(SqliteSchemaMigrator.class);

  private final DataSource dataSource;

  public SqliteSchemaMigrator(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /** 幂等：可以每次启动都执行；已补齐的库只在索引一步做一次 no-op。 */
  public void migrate() {
    migrateLlmCalls();
    migrateToolInvocations();
  }

  private void migrateLlmCalls() {
    try (Connection connection = dataSource.getConnection()) {
      boolean columnPresent = llmCallsHasTraceColumn(connection);
      if (!columnPresent) {
        try (Statement statement = connection.createStatement()) {
          statement.execute("ALTER TABLE llm_calls ADD COLUMN trace_id TEXT");
        }
      }
      // 索引总是补一次：中途失败过（补了列没建索引）的库也能在这一步自愈。
      try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE INDEX IF NOT EXISTS idx_llm_calls_trace ON llm_calls (trace_id)");
      }
      if (!columnPresent) {
        LOG.info("schema 迁移：llm_calls 补列 trace_id 并建索引，历史行保持 NULL");
      }
    } catch (SQLException e) {
      throw new LytgosException("schema 迁移失败（llm_calls）: " + e.getMessage(), e);
    }
  }

  private void migrateToolInvocations() {
    try (Connection connection = dataSource.getConnection()) {
      boolean columnPresent = toolInvocationsHasTraceColumn(connection);
      if (!columnPresent) {
        try (Statement statement = connection.createStatement()) {
          statement.execute("ALTER TABLE tool_invocations ADD COLUMN trace_id TEXT");
        }
      }
      try (Statement statement = connection.createStatement()) {
        statement.execute(
            "CREATE INDEX IF NOT EXISTS idx_tool_invocations_trace"
                + " ON tool_invocations (trace_id)");
      }
      if (!columnPresent) {
        LOG.info("schema 迁移：tool_invocations 补列 trace_id 并建索引，历史行保持 NULL");
      }
    } catch (SQLException e) {
      throw new LytgosException("schema 迁移失败（tool_invocations）: " + e.getMessage(), e);
    }
  }

  private static boolean llmCallsHasTraceColumn(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet columns = statement.executeQuery("PRAGMA table_info(llm_calls)")) {
      return containsTraceColumn(columns);
    }
  }

  private static boolean toolInvocationsHasTraceColumn(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet columns = statement.executeQuery("PRAGMA table_info(tool_invocations)")) {
      return containsTraceColumn(columns);
    }
  }

  /** PRAGMA 结果按名字取，不依赖列序号；列名与建表脚本一致，精确比较即可。 */
  private static boolean containsTraceColumn(ResultSet columns) throws SQLException {
    while (columns.next()) {
      if ("trace_id".equals(columns.getString("name"))) {
        return true;
      }
    }
    return false;
  }
}
