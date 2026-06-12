package com.lifepilot.config;

import com.lifepilot.memory.store.vector.SqliteVecDataSource;
import com.lifepilot.memory.store.vector.SqliteVecInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.relational.core.dialect.AnsiDialect;
import org.springframework.data.relational.core.dialect.Dialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * SQLite 数据源配置。
 *
 * <p>手动创建 {@link SQLiteDataSource}，在连接初始化时设置 PRAGMA 参数，
 * 并自动创建数据库文件所在目录。
 *
 * @author zsg
 * @since 2026-02-24
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    /**
     * 创建 SQLite 数据源，设置 PRAGMA 并确保数据库目录存在。
     *
     * @param url JDBC 连接 URL
     * @param dsProperties 数据源外部化配置
     * @return 配置完成的数据源
     */
    @Bean
    @Primary
    public DataSource dataSource(@Value("${spring.datasource.url}") String url,
                                 DataSourceProperties dsProperties,
                                 ObjectProvider<SqliteVecInitializer> sqliteVecInitializerProvider) {
        // 确保数据库文件目录存在（内存数据库跳过）
        if (!url.contains(":memory:") && !url.contains("mode=memory")) {
            ensureDatabaseDirectory(url);
        }

        // 配置 SQLite PRAGMA
        var config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(dsProperties.getBusyTimeout());
        // 允许在该 DataSource 上加载原生扩展（例如 sqlite-vec）
        config.enableLoadExtension(true);

        var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(url);

        log.info("SQLite 数据源初始化完成: url={}", url);

        // 如果启用了记忆系统且 sqlite-vec 扩展资源可用，则确保每条连接都加载扩展
        SqliteVecInitializer sqliteVecInitializer = sqliteVecInitializerProvider.getIfAvailable();
        if (sqliteVecInitializer != null) {
            return new SqliteVecDataSource(dataSource, sqliteVecInitializer, "main");
        }
        return dataSource;
    }

    /**
     * 提供 SQLite 的 JDBC Dialect，Spring Data JDBC 默认不支持 SQLite。
     * 使用 ANSI 标准方言作为兼容实现。
     *
     * @return ANSI 方言
     */
    @Bean
    public Dialect jdbcDialect() {
        return AnsiDialect.INSTANCE;
    }

    /**
     * 主数据库 JdbcTemplate，标记为 {@link Primary} 避免与向量数据库 JdbcTemplate 冲突。
     *
     * @param dataSource 主数据源
     * @return 主 JdbcTemplate
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * 解析 JDBC URL 中的数据库文件路径，自动创建所在目录。
     *
     * @param url JDBC 连接 URL
     */
    private void ensureDatabaseDirectory(String url) {
        if (url == null || !url.startsWith("jdbc:sqlite:")) {
            return;
        }

        try {
            // 1. 去掉 jdbc:sqlite: 前缀
            String pathStr = url.substring("jdbc:sqlite:".length());

            // 💡 关键修复：如果 URL 包含查询参数（如 ?enable_load_extension=true），则截断它们
            int queryIndex = pathStr.indexOf('?');
            if (queryIndex != -1) {
                pathStr = pathStr.substring(0, queryIndex);
            }

            // 2. 此时 pathStr 是纯粹的文件路径，可以安全地交给 Path 处理
            Path dbPath = Path.of(pathStr);
            Path parentDir = dbPath.getParent();

            if (parentDir != null && Files.notExists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("记忆系统: 已成功创建数据库目录: {}", parentDir);
            }
        } catch (Exception e) {
            log.error("创建数据库目录失败: url={}", url, e);
            throw new RuntimeException("SQLite 数据源初始化失败：无法创建数据库目录", e);
        }
    }
}
