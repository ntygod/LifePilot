package com.lifepilot.memory.store.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
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
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        this.sqliteVecInitializer = Objects.requireNonNull(sqliteVecInitializer, "sqliteVecInitializer 不能为空");
        if (dataSourceName == null || dataSourceName.isBlank()) {
            throw new IllegalArgumentException("dataSourceName 不能为空");
        }
        this.dataSourceName = dataSourceName;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection con = delegate.getConnection();
        return ensureVecLoadedOrClose(con);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection con = delegate.getConnection(username, password);
        return ensureVecLoadedOrClose(con);
    }

    private Connection ensureVecLoadedOrClose(Connection con) throws SQLException {
        try {
            ensureVecLoaded(con);
            return con;
        } catch (RuntimeException e) {
            try {
                con.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    private void ensureVecLoaded(Connection con) {
        String extensionPath = sqliteVecInitializer.getExtractedExtensionAbsolutePath();
        if (extensionPath == null || extensionPath.isBlank()) {
            throw new IllegalStateException("sqlite-vec 扩展路径未准备");
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
            throw new IllegalStateException("sqlite-vec 扩展未就绪（本连接无法加载）: " + e.getMessage(), e);
        }
    }

    private String queryVecVersion(Statement stmt) throws Exception {
        try (var rs = stmt.executeQuery("SELECT vec_version()")) {
            if (!rs.next()) {
                throw new IllegalStateException("sqlite-vec 版本查询无结果");
            }
            String version = rs.getString(1);
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("sqlite-vec 版本不能为空");
            }
            return version;
        }
    }

    private void logReadyOnce(String version, String extensionPath) {
        if (loggedReadyOnce.compareAndSet(false, true)) {
            log.info("记忆系统: sqlite-vec 扩展验证成功, 数据源={}, 版本={}, 路径={}",
                    dataSourceName, version, extensionPath);
        }
    }
}
