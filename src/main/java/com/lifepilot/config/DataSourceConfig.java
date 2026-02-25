package com.lifepilot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    public DataSource dataSource(@Value("${spring.datasource.url}") String url,
                                 DataSourceProperties dsProperties) {
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

        var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(url);

        log.info("SQLite 数据源初始化完成: url={}", url);
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
        return new JdbcTemplate(dataSource);
    }

    /**
     * 解析 JDBC URL 中的数据库文件路径，自动创建所在目录。
     *
     * @param url JDBC 连接 URL
     */
    private void ensureDatabaseDirectory(String url) {
        try {
            // jdbc:sqlite:/path/to/db → /path/to/db
            var dbPath = url.replace("jdbc:sqlite:", "");
            var parentDir = Path.of(dbPath).getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("创建数据库目录: path={}", parentDir);
            }
        } catch (Exception e) {
            log.error("创建数据库目录失败: url={}", url, e);
            throw new RuntimeException("SQLite 数据源初始化失败：无法创建数据库目录", e);
        }
    }
}
