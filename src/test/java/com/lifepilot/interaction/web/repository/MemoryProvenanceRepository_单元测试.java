package com.lifepilot.interaction.web.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MemoryProvenanceRepository 单元测试。
 *
 * @author zsg
 * @since 2026-07-01
 */
@ExtendWith(MockitoExtension.class)
class MemoryProvenanceRepository_单元测试 {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private MemoryProvenanceRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MemoryProvenanceRepository(jdbcTemplate);
    }

    @Test
    void 来源过滤为空白时应失败且不查询() {
        assertThatThrownBy(() -> repository.findEntityIdsByProvenanceFilters(" ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originType不能为空");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 来源过滤包含首尾空白时应失败且不查询() {
        assertThatThrownBy(() -> repository.findEntityIdsByProvenanceFilters(null, " kb-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceKnowledgeBaseId不能包含首尾空白");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 批量加载实体元数据遇到脏id时应失败且不查询() {
        assertThatThrownBy(() -> repository.loadEntityMetadata(List.of("e1 ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId不能包含首尾空白");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 批量查询stale实体遇到null集合时应失败且不查询() {
        assertThatThrownBy(() -> repository.findStaleEntityIds(null))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 名称加载遇到脏id时应失败且不查询() {
        assertThatThrownBy(() -> repository.loadKnowledgeBaseNames(List.of(" kb-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id不能包含首尾空白");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 名称加载遇到数据库返回脏itemId时应失败() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("item_id")).thenReturn(" kb-1");
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        assertThatThrownBy(() -> repository.loadKnowledgeBaseNames(List.of("kb-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itemId不能包含首尾空白");
    }

    @Test
    void 名称加载遇到数据库返回空白itemName时应失败() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("item_id")).thenReturn("kb-1");
            when(rs.getString("item_name")).thenReturn(" ");
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        assertThatThrownBy(() -> repository.loadKnowledgeBaseNames(List.of("kb-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itemName不能为空");
    }

    @Test
    void 最近来源摘要limit非正时应失败且不查询() {
        assertThatThrownBy(() -> repository.findRecentProvenanceSummaries(null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit必须大于 0");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 最近来源摘要遇到未知实体类型应失败() throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("entity_type")).thenReturn("BROKEN");
                    return List.of(mapper.mapRow(rs, 0));
                });

        assertThatThrownBy(() -> repository.findRecentProvenanceSummaries(null, null, null, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entityType包含未知值: BROKEN");
    }
}
