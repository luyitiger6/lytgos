package io.lytgos.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.lytgos.core.agent.Profile;
import io.lytgos.core.agent.PromptBuilder;
import io.lytgos.core.agent.ReActLoop;
import io.lytgos.core.chat.ChatModel;
import io.lytgos.core.chat.ChatRequest;
import io.lytgos.core.chat.ChatResult;
import io.lytgos.core.chat.Message;
import io.lytgos.core.chat.ToolCall;
import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.session.Session;
import io.lytgos.core.tool.Tool;
import io.lytgos.core.tool.ToolExecutor;
import io.lytgos.core.tool.ToolRegistry;
import io.lytgos.provider.AuditedChatModel;
import io.lytgos.storage.SqliteAuditStore;
import io.lytgos.storage.mapper.AuditMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 审计不变量 I1 / I2 / I3 的可执行版本，05 号闸门只负责保证本类被真实执行。
 *
 * <p>三条断言走真实链路：{@link AuditedChatModel}（模型边界）+ {@link ReActLoop} + {@link ToolExecutor}（工具边界）+
 * SQLite。模型本身是假的——这里验的是「有没有记」， 不是「模型答得好不好」。
 *
 * <p>持久化不 new、不手写 MyBatis 初始化：数据源、{@code SqlSessionFactory}、Mapper 代理全部来自 Spring 容器， 与生产同一套接法（见
 * {@code CoreBeans}）。测试里自己拼一套，等于把「Spring 装配正确」这个前提放掉了。
 *
 * <p>用临时文件库而不是 {@code :memory:}：连接按短连接模型每次新开，内存库在第二个连接里 就是另一个空库，连表都看不到。临时文件与生产库走完全相同的建表与写入路径。
 */
@DisplayName("审计不变量 I1/I2/I3：模型与工具各一行且可按 turn 关联")
class AuditInvariantTest {

  private static final String SESSION = "cli:local:auditor";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path workspace;

  private AnnotationConfigApplicationContext context;
  private DataSource dataSource;
  private SqliteAuditStore auditStore;
  private ProbeTool probe;

  @BeforeEach
  void setUp() {
    context = new AnnotationConfigApplicationContext();
    context.registerBean(AuditTestConfig.class, workspace.resolve("lytgos.db"));
    context.refresh();
    dataSource = context.getBean(DataSource.class);
    auditStore = context.getBean(SqliteAuditStore.class);
    probe = new ProbeTool();
  }

  @AfterEach
  void tearDown() {
    context.close();
  }

  @Test
  @DisplayName("I1：每次模型调用恰好一行 llm_calls，成功与失败都算")
  void everyModelCallWritesOneRow() {
    Run run = runOneTurn();
    assertEquals(2, run.modelCalls(), "一轮应发生两次模型调用（工具调用 + 最终文本）");
    assertEquals(2, snapshot().llmCalls(), "模型调用次数与审计行数必须一一对应");

    AuditedChatModel failing =
        new AuditedChatModel(
            request -> {
              throw new IllegalStateException("上游超时");
            },
            "deepseek",
            "deepseek-chat",
            auditStore::record);
    assertThrows(IllegalStateException.class, () -> failing.call(request("turn-fail")));

    Snapshot afterFailure = snapshot();
    assertEquals(3, afterFailure.llmCalls(), "失败调用同样必须落一行");
    assertEquals(1, afterFailure.failedLlmCalls(), "失败行必须标记为未成功");
  }

  @Test
  @DisplayName("I2：每次工具执行恰好一行 tool_invocations，异常也落一行")
  void everyToolExecutionWritesOneRow() {
    Run run = runOneTurn();
    assertEquals(1, run.toolCalls(), "一轮应发生一次工具执行");
    assertEquals(1, snapshot().toolInvocations(), "工具执行次数与审计行数必须一一对应");

    ToolExecutor failing =
        new ToolExecutor(new ToolRegistry(List.of(new FailingTool())), MAPPER, auditStore::record);
    String output =
        failing.execute(SESSION, "turn-fail", new ToolCall("c-fail", "exploding", "{}"));

    assertTrue(output.contains("boom"), output);
    Snapshot afterFailure = snapshot();
    assertEquals(2, afterFailure.toolInvocations(), "工具异常不得跳过审计");
    assertEquals(1, afterFailure.failedToolInvocations());
  }

  @Test
  @DisplayName("I3：同一 turn 的模型行与工具行可关联到同一 session")
  void turnIdLinksModelAndToolRows() {
    runOneTurn();

    Snapshot snapshot = snapshot();
    assertEquals(1, snapshot.llmSessions(), "llm_calls 只应出现一个 session");
    assertEquals(1, snapshot.toolSessions(), "tool_invocations 只应出现一个 session");
    assertEquals(snapshot.llmCalls(), snapshot.llmCallsInSession(), "模型行必须全部归属测试会话");
    assertEquals(snapshot.toolInvocations(), snapshot.toolInvocationsInSession(), "工具行必须全部归属测试会话");
    assertEquals(2, snapshot.joinedTurns(), "同轮两次模型调用都应与该轮工具行关联");
    assertEquals(0, snapshot.crossSessionTurns(), "同一个 turn_id 不得跨 session");
  }

  /** 跑一轮真实 ReAct：第一次模型调用要工具，第二次给最终文本。 */
  private Run runOneTurn() {
    AtomicInteger modelCalls = new AtomicInteger();
    ChatModel delegate =
        request -> {
          if (modelCalls.incrementAndGet() == 1) {
            return new ChatResult(
                "", List.of(new ToolCall("call-1", "probe", "{\"input\":\"ping\"}")));
          }
          return new ChatResult("pong", List.of());
        };
    AuditedChatModel model =
        new AuditedChatModel(delegate, "deepseek", "deepseek-chat", auditStore::record);

    ToolRegistry registry = new ToolRegistry(List.of(probe));
    ReActLoop loop =
        new ReActLoop(
            model,
            PromptBuilder.from(profile(), workspace, "按用户要求回答。"),
            registry,
            new ToolExecutor(registry, MAPPER, auditStore::record),
            3,
            null);

    String answer = loop.run(new Session(SESSION), "ping");
    assertEquals("pong", answer);
    return new Run(answer, modelCalls.get(), probe.executions());
  }

  private static Profile profile() {
    return new Profile(
        "auditor",
        "审计测试 Agent",
        "你是一个测试助手。",
        new Profile.ProviderRef("deepseek", "deepseek-chat"),
        List.of(),
        List.of(),
        List.of("cli"),
        List.of(),
        Map.of(),
        List.of());
  }

  private static ChatRequest request(String turnId) {
    return new ChatRequest(List.of(Message.user("你好")), List.of(), SESSION, turnId, null);
  }

  /**
   * 断言所需的全部整数一次取回。
   *
   * <p>SQL 是编译期常量：表名与条件都不参与字符串拼接，测试里不留下「动态 SQL」这种坏先例。
   */
  private Snapshot snapshot() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT"
                    + " (SELECT COUNT(*) FROM llm_calls),"
                    + " (SELECT COUNT(*) FROM tool_invocations),"
                    + " (SELECT COUNT(*) FROM llm_calls WHERE success = 0),"
                    + " (SELECT COUNT(*) FROM tool_invocations WHERE success = 0),"
                    + " (SELECT COUNT(*) FROM llm_calls l JOIN tool_invocations t"
                    + " ON l.turn_id = t.turn_id AND l.session_id = t.session_id),"
                    + " (SELECT COUNT(*) FROM ("
                    + " SELECT turn_id FROM ("
                    + " SELECT session_id, turn_id FROM llm_calls"
                    + " UNION ALL"
                    + " SELECT session_id, turn_id FROM tool_invocations"
                    + " ) GROUP BY turn_id HAVING COUNT(DISTINCT session_id) > 1)),"
                    + " (SELECT COUNT(DISTINCT session_id) FROM llm_calls),"
                    + " (SELECT COUNT(DISTINCT session_id) FROM tool_invocations),"
                    + " (SELECT COUNT(*) FROM llm_calls WHERE session_id = '"
                    + SESSION
                    + "'),"
                    + " (SELECT COUNT(*) FROM tool_invocations WHERE session_id = '"
                    + SESSION
                    + "')");
        ResultSet rows = statement.executeQuery()) {
      assertTrue(rows.next());
      return new Snapshot(
          rows.getInt(1),
          rows.getInt(2),
          rows.getInt(3),
          rows.getInt(4),
          rows.getInt(5),
          rows.getInt(6),
          rows.getInt(7),
          rows.getInt(8),
          rows.getInt(9),
          rows.getInt(10));
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 审计表的整数快照：行数、失败行数、关联行数与会话归属。 */
  private record Snapshot(
      int llmCalls,
      int toolInvocations,
      int failedLlmCalls,
      int failedToolInvocations,
      int joinedTurns,
      int crossSessionTurns,
      int llmSessions,
      int toolSessions,
      int llmCallsInSession,
      int toolInvocationsInSession) {}

  private record Run(String answer, int modelCalls, int toolCalls) {}

  /** 正常工具：返回 echo，并记录执行次数。 */
  private static final class ProbeTool implements Tool {

    private final AtomicInteger executions = new AtomicInteger();

    @Override
    public ToolSpec spec() {
      return new ToolSpec(
          "probe",
          "测试用探针工具",
          "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}");
    }

    @Override
    public String execute(Map<String, Object> arguments) {
      executions.incrementAndGet();
      return "echo:" + arguments.get("input");
    }

    int executions() {
      return executions.get();
    }
  }

  /** 失败工具：抛异常，用于确认异常路径同样落审计。 */
  private static final class FailingTool implements Tool {

    @Override
    public ToolSpec spec() {
      return new ToolSpec("exploding", "总是失败的工具", "{\"type\":\"object\"}");
    }

    @Override
    public String execute(Map<String, Object> arguments) {
      throw new IllegalStateException("boom");
    }
  }

  /** 与生产 CoreBeans 同构的最小装配：HikariCP + SqlSessionFactoryBean + MapperScan + 建表脚本。 */
  @Configuration(proxyBeanMethods = false)
  @MapperScan("io.lytgos.storage.mapper")
  static class AuditTestConfig {

    private final Path databaseFile;

    AuditTestConfig(Path databaseFile) {
      this.databaseFile = databaseFile;
    }

    @Bean(destroyMethod = "close")
    HikariDataSource dataSource() {
      HikariConfig config = new HikariConfig();
      config.setPoolName("lytgos-audit-test");
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
    SqliteAuditStore sqliteAuditStore(AuditMapper auditMapper) {
      return new SqliteAuditStore(auditMapper);
    }
  }
}
