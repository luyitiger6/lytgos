package io.lytgos.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.lytgos.core.session.SessionStore;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.memory.MemoryStore;
import io.lytgos.persona.AgentLoader;
import io.lytgos.storage.SqliteAuditStore;
import io.lytgos.storage.SqliteSchemaMigrator;
import io.lytgos.storage.SqliteSessionStore;
import io.lytgos.storage.mapper.AuditMapper;
import io.lytgos.storage.mapper.SessionMapper;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 无状态能力类的 Bean 声明：序列化、工作区定位、沙箱、持久化、Agent 加载。
 *
 * <p>能力类本身保持纯 POJO、不写 {@code @Component}——容器不做类路径扫描， 装配关系集中在这里才好查；既有单测直接 {@code new} 也不受影响。
 *
 * <p>{@code WorkspaceConfig} 也在这里登记：它由 {@link WorkspaceLocator} 决定路径， 沙箱白名单又依赖它的内容。三个 Bean
 * 的先后顺序由构造参数表达，不靠初始化顺序碰运气。
 *
 * <p>持久化全部交给 Spring：数据源用 Boot 管理的 HikariCP，{@code SqlSessionFactory} 由 MyBatis-Spring 的 {@code
 * SqlSessionFactoryBean} 创建，建表脚本由 {@code DataSourceInitializer} 执行——本类只声明 「库文件在哪」，不碰 JDBC，也不手工拼
 * MyBatis 配置。
 */
@Configuration(proxyBeanMethods = false)
public class CoreBeans {

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  @Bean
  public WorkspaceLocator workspaceLocator() {
    return new WorkspaceLocator(Path.of(System.getProperty("user.dir")));
  }

  /**
   * 宽松加载：缺失的环境变量原样保留为 {@code ${VAR}} 占位，严格性在 {@code ProviderRegistry} 首次构造 {@code ChatModel} 时才生效。
   */
  @Bean
  public WorkspaceConfig workspaceConfig(WorkspaceLocator locator) {
    return WorkspaceConfig.load(locator.get().configFile());
  }

  @Bean
  public SandboxChecker sandboxChecker(WorkspaceLocator locator, WorkspaceConfig config) {
    return new SandboxChecker(
        locator.get().root(), config.allowedCommands(), config.allowedDomains());
  }

  /** 长期记忆读写入口。路径由工作区决定，记忆工具与启动注入看到的是同一份文件。 */
  @Bean
  public MemoryStore memoryStore(WorkspaceLocator locator) {
    return new MemoryStore(locator.get().root());
  }

  /**
   * SQLite 数据源：Spring Boot 默认的 HikariCP，库文件固定在工作区根下。
   *
   * <p>池大小 1：SQLite 是单写者模型，连接池给不出并发收益，只会在 CLI 进程里常驻多个空闲连接。
   */
  @Bean(destroyMethod = "close")
  public HikariDataSource dataSource(WorkspaceLocator locator) {
    HikariConfig config = new HikariConfig();
    config.setPoolName("lytgos-sqlite");
    config.setDriverClassName("org.sqlite.JDBC");
    config.setJdbcUrl("jdbc:sqlite:" + locator.get().root().resolve("lytgos.db"));
    config.setMaximumPoolSize(1);
    config.setConnectionInitSql("PRAGMA busy_timeout = 3000");
    return new HikariDataSource(config);
  }

  /** 建表脚本由 Spring 执行：全部 {@code IF NOT EXISTS}，重复启动不改动已有记录。 */
  @Bean
  public DataSourceInitializer schemaInitializer(DataSource dataSource) {
    DataSourceInitializer initializer = new DataSourceInitializer();
    initializer.setDataSource(dataSource);
    initializer.setDatabasePopulator(
        new ResourceDatabasePopulator(new ClassPathResource("lytgos-schema.sql")));
    return initializer;
  }

  /**
   * 老库补列：建表脚本全是 {@code IF NOT EXISTS}，对已经存在的表什么也不做，新列只能靠迁移器补。
   *
   * <p>{@code @DependsOn} 把「建表先于迁移」写成显式依赖，不靠 Bean 方法的书写顺序： 新库由脚本一次性建出完整结构，迁移器对它是 no-op；老库在脚本之后补上
   * trace_id。
   */
  @Bean(initMethod = "migrate")
  @DependsOn("schemaInitializer")
  public SqliteSchemaMigrator sqliteSchemaMigrator(DataSource dataSource) {
    return new SqliteSchemaMigrator(dataSource);
  }

  /** MyBatis 会话工厂交给容器管理；Mapper 接口由 {@code CliConfiguration} 上的 {@code @MapperScan} 生成代理。 */
  @Bean
  public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
    SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    return factory.getObject();
  }

  /** 审计写入口：模型调用与工具执行共用一个实现、两张表。 */
  @Bean
  public SqliteAuditStore sqliteAuditStore(AuditMapper auditMapper) {
    return new SqliteAuditStore(auditMapper);
  }

  /** 会话存储换到 SQLite。旧 {@code sessions/*.json} 不读不删——当前没有需要迁移的会话数据， 双读分支只会在每次启动时多问一次「要不要回退」。 */
  @Bean
  public SessionStore sessionStore(SessionMapper sessionMapper, ObjectMapper objectMapper) {
    return new SqliteSessionStore(sessionMapper, objectMapper);
  }

  @Bean
  public AgentLoader agentLoader() {
    return new AgentLoader();
  }
}
