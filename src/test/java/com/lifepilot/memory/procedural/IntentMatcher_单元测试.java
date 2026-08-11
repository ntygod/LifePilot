package com.lifepilot.memory.procedural;

import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.store.config.MemoryStoreProperties;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * IntentMatcher 单元测试。
 *
 * @author zsg
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension.class)
class IntentMatcher_单元测试 {

    @Mock
    private ProceduralMemory proceduralMemory;

    @Mock
    private VectorSearcher vectorSearcher;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void 空意图应直接拒绝() {
        var matcher = new IntentMatcher(
                proceduralMemory,
                vectorSearcher,
                jdbcTemplate,
                new MemoryStoreProperties());

        assertThatThrownBy(() -> matcher.match("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("意图文本不能为空");
        verifyNoInteractions(vectorSearcher, jdbcTemplate, proceduralMemory);
    }

    @Test
    void 向量检索失败时传播异常() {
        var matcher = new IntentMatcher(
                proceduralMemory,
                vectorSearcher,
                jdbcTemplate,
                new MemoryStoreProperties());
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenThrow(new RuntimeException("L4 向量索引缺失"));

        assertThatThrownBy(() -> matcher.match("!!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("意图匹配执行失败: 向量检索")
                .hasRootCauseMessage("L4 向量索引缺失");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 向量检索返回null条目时直接失败() {
        var matcher = new IntentMatcher(
                proceduralMemory,
                vectorSearcher,
                jdbcTemplate,
                new MemoryStoreProperties());
        List<VectorSearchResult> invalidResults = new ArrayList<>();
        invalidResults.add(null);
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(invalidResults);

        assertThatThrownBy(() -> matcher.match("整理项目计划"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("意图匹配向量检索结果包含 null 条目");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 候选模板不存在时跳过并返回空匹配() {
        var matcher = new IntentMatcher(
                proceduralMemory,
                vectorSearcher,
                jdbcTemplate,
                new MemoryStoreProperties());
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.of(new VectorSearchResult("template-missing", 1.0f)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(proceduralMemory.findById("template-missing"))
                .thenReturn(Optional.empty());

        assertThat(matcher.match("整理项目计划")).isEmpty();
    }
}
