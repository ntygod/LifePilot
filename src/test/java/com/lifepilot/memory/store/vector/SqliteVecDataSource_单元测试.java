package com.lifepilot.memory.store.vector;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * sqlite-vec DataSource 包装器单元测试。
 *
 * @author zsg
 * @since 2026-07-07
 */
class SqliteVecDataSource_单元测试 {

    @Test
    void 扩展加载失败时仍返回普通连接() throws Exception {
        DataSource delegate = mock(DataSource.class);
        SqliteVecInitializer initializer = mock(SqliteVecInitializer.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);

        when(delegate.getConnection()).thenReturn(connection);
        when(initializer.getExtractedExtensionAbsolutePath()).thenReturn("C:/missing/vec0.dll");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT vec_version()")).thenThrow(new SQLException("no such function"));
        when(statement.execute(anyString())).thenThrow(new SQLException("找不到指定的模块"));

        var dataSource = new SqliteVecDataSource(delegate, initializer, "main");

        assertThat(dataSource.getConnection()).isSameAs(connection);
        verify(connection, never()).close();
    }
}
