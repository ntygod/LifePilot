package com.lifepilot.memory.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 向量数据库 DataSource 包装器：确保每条 Connection 都已加载 sqlite-vec 扩展。
 *
 * <p>原因：sqlite-vec（以及大多数 SQLite 扩展）是“按连接加载”的。
 * 只在某一条连接中 {@code load_extension} 成功，并不代表其他连接可用。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
public class SqliteVecDataSource extends AbstractDataSource {

    private static final Logger log = LoggerFactory.getLogger(SqliteVecDataSource.class);

    private final DataSource delegate;
    private final SqliteVecInitializer sqliteVecInitializer;
    private final String dataSourceName;
    private final AtomicBoolean loggedReadyOnce = new AtomicBoolean(false);

    public SqliteVecDataSource(DataSource delegate, SqliteVecInitializer sqliteVecInitializer, String dataSourceName) {
        this.delegate = delegate;
        this.sqliteVecInitializer = sqliteVecInitializer;
        this.dataSourceName = dataSourceName;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection con = delegate.getConnection();
        ensureVecLoaded(con);
        return con;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection con = delegate.getConnection(username, password);
        ensureVecLoaded(con);
        return con;
    }

    private void ensureVecLoaded(Connection con) {
        String extensionPath = sqliteVecInitializer.getExtractedExtensionAbsolutePath();
        if (extensionPath == null || extensionPath.isBlank()) {
            return;
        }

        // 先轻量探测：已加载则直接返回
        try (Statement stmt = con.createStatement()) {
            String version = queryVecVersion(stmt);
            logReadyOnce(version, extensionPath);
            return;
        } catch (Exception ignored) {
            // fallthrough: try load
        }

        // 尝试在“当前连接”加载扩展，然后再次验证
        try (Statement stmt = con.createStatement()) {
            // extensionPath 来自应用内部（SqliteVecInitializer 解压路径），转义单引号防止意外 SQL 断裂
            String safePath = extensionPath.replace("'", "''");
            String loadSql = "SELECT load_extension('" + safePath + "', '" + SqliteVecInitializer.ENTRYPOINT + "')";
            stmt.execute(loadSql);
            String version = queryVecVersion(stmt);
            logReadyOnce(version, extensionPath);
        } catch (Exception e) {
            // 静默降级：让上层决定是否 fallback
            log.debug("记忆系统: sqlite-vec 扩展未就绪（本连接无法加载），将降级, reason={}", e.getMessage());
        }
    }

    private String queryVecVersion(Statement stmt) throws Exception {
        var rs = stmt.executeQuery("SELECT vec_version()");
        return rs.next() ? rs.getString(1) : "unknown";
    }

    private void logReadyOnce(String version, String extensionPath) {
        if (loggedReadyOnce.compareAndSet(false, true)) {
            log.info("记忆系统: sqlite-vec 扩展验证成功, 数据源={}, 版本={}, 路径={}",
                    dataSourceName, version, extensionPath);
        }
    }
}

