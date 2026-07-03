package com.lifepilot.memory.consumption.attention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GraphReasoner 单元测试。
 *
 * @author zsg
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension.class)
class GraphReasoner_单元测试 {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void 起点实体为空时直接拒绝() {
        var reasoner = new GraphReasoner(jdbcTemplate, 10);

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> reasoner.pathsFrom(" ", 2, null));

        assertTrue(exception.getMessage().contains("图联想起点实体 id 不能为空"));
    }

    @Test
    void 起点实体包含首尾空白时直接拒绝() {
        var reasoner = new GraphReasoner(jdbcTemplate, 10);

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> reasoner.pathsFrom(" entity-1 ", 2, null));

        assertTrue(exception.getMessage().contains("图联想起点实体 id 不能包含首尾空白"));
    }

    @Test
    void 最大深度非法时直接拒绝() {
        var reasoner = new GraphReasoner(jdbcTemplate, 10);

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> reasoner.pathsFrom("entity-1", 0, null));

        assertTrue(exception.getMessage().contains("图联想最大深度必须大于 0"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 边加载失败时传播异常() {
        var reasoner = new GraphReasoner(jdbcTemplate, 10);
        when(jdbcTemplate.query(
                contains("FROM temporal_relations"),
                any(RowMapper.class),
                any(Object[].class)))
                .thenThrow(new RuntimeException("memory_relations 缺失"));

        var exception = assertThrows(
                RuntimeException.class,
                () -> reasoner.pathsFrom("entity-1", 2, null));

        assertTrue(exception.getMessage().contains("memory_relations 缺失"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 边查询返回null时直接拒绝() {
        var reasoner = new GraphReasoner(jdbcTemplate, 10);
        when(jdbcTemplate.query(
                contains("FROM temporal_relations"),
                any(RowMapper.class),
                any(Object[].class)))
                .thenReturn(null);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> reasoner.pathsFrom("entity-1", 2, null));

        assertTrue(exception.getMessage().contains("图推理边查询结果不能为空"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 边查询返回null条目时直接拒绝() {
        var reasoner = new GraphReasoner(jdbcTemplate, 10);
        when(jdbcTemplate.query(
                contains("FROM temporal_relations"),
                any(RowMapper.class),
                any(Object[].class)))
                .thenReturn(java.util.Collections.singletonList(null));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> reasoner.pathsFrom("entity-1", 2, null));

        assertTrue(exception.getMessage().contains("图推理边查询结果包含 null 条目"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 关系版本缺少strength时直接失败() {
        var reasoner = new GraphReasoner(jdbcTemplate, 10);
        when(jdbcTemplate.query(
                contains("FROM temporal_relations"),
                any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<GraphReasoner.AssociationPath> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("strength")).thenReturn(null);
                    when(rs.getString("source_id")).thenReturn("entity-1");
                    when(rs.getString("target_id")).thenReturn("entity-2");
                    when(rs.getString("relation_type")).thenReturn("关联");
                    mapper.mapRow(rs, 0);
                    return null;
                });

        var exception = assertThrows(
                IllegalStateException.class,
                () -> reasoner.pathsFrom("entity-1", 2, null));

        assertTrue(exception.getMessage().contains("图推理关系版本缺少 strength"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 关系版本strength越界时直接失败() {
        var reasoner = new GraphReasoner(jdbcTemplate, 10);
        when(jdbcTemplate.query(
                contains("FROM temporal_relations"),
                any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("strength")).thenReturn(1.5f);
                    when(rs.getFloat("strength")).thenReturn(1.5f);
                    when(rs.getString("source_id")).thenReturn("entity-1");
                    when(rs.getString("target_id")).thenReturn("entity-2");
                    when(rs.getString("relation_type")).thenReturn("关联");
                    mapper.mapRow(rs, 0);
                    return java.util.List.of();
                });

        var exception = assertThrows(
                IllegalStateException.class,
                () -> reasoner.pathsFrom("entity-1", 2, null));

        assertTrue(exception.getMessage().contains("图推理关系强度必须在 [0,1] 范围内"));
    }
}
