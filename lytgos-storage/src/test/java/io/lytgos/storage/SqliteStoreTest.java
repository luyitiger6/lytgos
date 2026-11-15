package io.lytgos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.lytgos.core.audit.LlmCallRecord;
import io.lytgos.core.audit.ToolInvocationRecord;
import io.lytgos.core.chat.Message;
import io.lytgos.core.session.Session;
import io.lytgos.core.session.SessionStore;
import io.lytgos.storage.mapper.AuditMapper;
import io.lytgos.storage.mapper.SessionMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 存储层的行为验证：不 new 任何持久化组件，全部从 Spring 容器取。
 *
 * <p>测试装配与生产的 {@code CoreBeans} 保持同一套接法——HikariCP 数据源、 {@code
 * SqlSessionFactoryBean}、{@code @MapperScan} 生成 Mapper 代理、建表脚本由 {@code DataSourceInitializer}
 * 执行。测试里再手写一遍 MyBatis 初始化，等于放宽了 「Spring 装配正确」这个前提。
 */
@DisplayName("SQLite 存储：Spring 装配 MyBatis、建表幂等、审计三态、会话 upsert")
class SqliteStoreTest {

  @TempDir Path tempDir;

  private AnnotationConfigApplicationContext context;
  private SqliteAuditStore auditStore;
  private SessionStore sessionStore;
  private DataSource dataSource;

  @BeforeEach
  void setUp() {
    startContext();
  }

  @AfterEach
  void tearDown() {
    context.close();
  }

  @Test
  @DisplayName("重复启动建表幂等，已有审计记录不被清空")
  void initializeIsIdempotent() throws SQLException {
    auditStore.record(llmCall("turn-1", true, null, 12, 3, 15));

    restartContext();
    restartContext();

    Counts counts = counts();
    assertEquals(1, counts.llmCalls());
    assertEquals(0, counts.sessions(), "建表不应凭空产生会话行");
  }

  @Test
  @DisplayName("模型调用成功与失败各落一行，用量与错误摘要可查")
  void writesSuccessAndFailureLlmCallsWithoutEmptyRows() throws SQLException {
    auditStore.record(llmCall("turn-1", true, null, 100, 20, 120));
    auditStore.record(llmCall("turn-1", false, "LytgosException: 上游 500", 0, 0, 0));

    assertEquals(2, counts().llmCalls());
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT success, prompt_tokens, total_tokens, error_message "
                    + "FROM llm_calls ORDER BY id");
        ResultSet rows = statement.executeQuery()) {
      assertTrue(rows.next());
      assertEquals(1, rows.getInt("success"));
      assertEquals(100, rows.getInt("prompt_tokens"));
      assertEquals(120, rows.getInt("total_tokens"));
      assertTrue(rows.next());
      assertEquals(0, rows.getInt("success"));
      assertEquals(0, rows.getInt("prompt_tokens"));
      assertTrue(rows.getString("error_message").contains("上游 500"));
      assertFalse(rows.next());
    }
  }

  @Test
  @DisplayName("工具执行成功、异常与沙箱拒绝各落一行，沙箱行带 sandbox 标识")
  void writesThreeToolInvocationStates() throws SQLException {
    auditStore.record(toolCall("turn-1", true, null));
    auditStore.record(toolCall("turn-1", false, "上游返回 502"));
    auditStore.record(toolCall("turn-1", false, "sandbox 拒绝：域名不在白名单"));

    assertEquals(3, counts().toolInvocations());
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT arguments, error_message FROM tool_invocations "
                    + "WHERE error_message LIKE 'sandbox%'");
        ResultSet rows = statement.executeQuery()) {
      assertTrue(rows.next(), "应存在沙箱拒绝行");
      assertEquals("{\"url\":\"https://evil.example.com\"}", rows.getString("arguments"));
    }
  }

  @Test
  @DisplayName("超长参数与错误摘要按上限截断")
  void truncatesOversizedFields() throws SQLException {
    String longArguments = "x".repeat(ToolInvocationRecord.MAX_ARGUMENT_CHARS + 500);
    auditStore.record(
        new ToolInvocationRecord(
            "cli:local:t",
            "turn-1",
            "probe",
            longArguments,
            1,
            false,
            "y".repeat(1500),
            Instant.now()));

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT arguments, error_message FROM tool_invocations");
        ResultSet rows = statement.executeQuery()) {
      assertTrue(rows.next());
      assertEquals(ToolInvocationRecord.MAX_ARGUMENT_CHARS, rows.getString("arguments").length());
      assertEquals(1000, rows.getString("error_message").length());
    }
  }

  @Test
  @DisplayName("会话保存后可读回；同键再次保存为覆盖而不是新增")
  void savesAndUpsertsSession() throws SQLException {
    Session session = new Session("cli:local:tester");
    session.append(Message.user("我叫张三"));
    sessionStore.save(session);

    // 重启容器等价于新进程：内存缓存被丢弃，只能从库里读回。
    restartContext();
    Session restored = sessionStore.get("cli:local:tester");
    assertEquals(1, restored.history().size());
    assertEquals("我叫张三", restored.history().get(0).content());

    restored.append(Message.assistant("记住了"));
    sessionStore.save(restored);

    restartContext();
    assertEquals(2, sessionStore.get("cli:local:tester").history().size());
    assertEquals(1, counts().sessions(), "同键保存必须 upsert，不得新增第二行");
  }

  @Test
  @DisplayName("未命中的会话返回空历史，不创建行")
  void missingSessionStartsEmpty() throws SQLException {
    assertTrue(sessionStore.get("cli:local:nobody").history().isEmpty());
    assertEquals(0, counts().sessions());
  }

  /** 用同一个库文件重建容器：建表脚本与 Mapper 装配都会再跑一遍。 */
  private void restartContext() {
    context.close();
    startContext();
  }

  private void startContext() {
    context = new AnnotationConfigApplicationContext();
    context.registerBean(StorageTestConfig.class, tempDir.resolve("lytgos.db"));
    context.refresh();
    auditStore = context.getBean(SqliteAuditStore.class);
    sessionStore = context.getBean(SessionStore.class);
    dataSource = context.getBean(DataSource.class);
  }

  /**
   * 一张常量 SQL 同时取三张表的行数。
   *
   * <p>表名不参与字符串拼接：测试里一旦习惯「动态表名 + prepareStatement」，注入报点就只能靠豁免闭嘴， 不如从写法上消掉这条路径。
   */
  private Counts counts() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM sessions), "
                    + "(SELECT COUNT(*) FROM llm_calls), "
                    + "(SELECT COUNT(*) FROM tool_invocations)");
        ResultSet rows = statement.executeQuery()) {
      assertTrue(rows.next());
      return new Counts(rows.getInt(1), rows.getInt(2), rows.getInt(3));
    }
  }

  /** 三张审计/会话表的当前行数。 */
  private record Counts(int sessions, int llmCalls, int toolInvocations) {}

  private static LlmCallRecord llmCall(
      String turnId, boolean success, String error, int prompt, int completion, int total) {
    return new LlmCallRecord(
        "cli:local:tester",
        turnId,
        "deepseek",
        "deepseek-flash",
        prompt,
        completion,
        total,
        42,
        success,
        error,
        Instant.now());
  }

  private static ToolInvocationRecord toolCall(String turnId, boolean success, String error) {
    return new ToolInvocationRecord(
        "cli:local:tester",
        turnId,
        "http_get",
        "{\"url\":\"https://evil.example.com\"}",
        7,
        success,
        error,
        Instant.now());
  }

  @Configuration(proxyBeanMethods = false)
  @MapperScan("io.lytgos.storage.mapper")
  static class StorageTestConfig {

    private final Path databaseFile;

    StorageTestConfig(Path databaseFile) {
      this.databaseFile = databaseFile;
    }

    @Bean(destroyMethod = "close")
    HikariDataSource dataSource() {
      HikariConfig config = new HikariConfig();
      config.setPoolName("lytgos-sqlite-test");
      config.setDriverClassName("org.sqlite.JDBC");
      config.setJdbcUrl("jdbc:sqlite:" + databaseFile);
      config.setMaximumPoolSize(1);
      config.setConnectionInitSql("PRAGMA busy_timeout = 3000");
      return new HikariDataSource(config);
    }

    @Bean
    DataSourceInitializer schemaInitializer(DataSource dataSource) {
      DataSourceInitializer initializer = new DataSourceInitializer();
      initializer.setDataSource(dataSource);
      initializer.setDatabasePopulator(
          new ResourceDatabasePopulator(new ClassPathResource("lytgos-schema.sql")));
      return initializer;
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
      SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
      factory.setDataSource(dataSource);
      return factory.getObject();
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    SqliteAuditStore sqliteAuditStore(AuditMapper auditMapper) {
      return new SqliteAuditStore(auditMapper);
    }

    @Bean
    SessionStore sessionStore(SessionMapper sessionMapper, ObjectMapper objectMapper) {
      return new SqliteSessionStore(sessionMapper, objectMapper);
    }
  }
}
