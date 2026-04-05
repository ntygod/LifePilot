package com.lifepilot.memory.retrieval;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FtsSearcher 单元测试 — 覆盖 search 方法的查询构建、结果映射、异常处理和边界条件。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class FtsSearcher_单元测试 {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private FtsSearcher ftsSearcher;

    @BeforeEach
    void 初始化() {
        ftsSearcher = new FtsSearcher(jdbcTemplate);
    }

    // ─────────────────────────────────────────────
    //  空查询 / 无效输入
    // ─────────────────────────────────────────────

    @Nested
    class 空查询与无效输入 {

        @Test
        void null查询返回空列表() {
            var result = ftsSearcher.search(null, 10);

            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void 空字符串查询返回空列表() {
            var result = ftsSearcher.search("", 10);

            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void 纯空白查询返回空列表() {
            var result = ftsSearcher.search("   \t\n  ", 10);

            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void 仅含特殊字符的查询经规范化后为空_返回空列表() {
            // 全是 FTS5 特殊字符，没有任何有效 token
            var result = ftsSearcher.search("(){}[]!@#$%^&*", 10);

            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void 仅含保留字的查询经规范化后为空_返回空列表() {
            // AND OR NOT 全是 FTS5 保留字，会被过滤掉
            var result = ftsSearcher.search("AND OR NOT", 10);

            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void 仅含单字符的查询经规范化后为空_返回空列表() {
            // 单个 ASCII 字母/数字会被 sanitizeToken 过滤
            var result = ftsSearcher.search("A B C D 1 2 3", 10);

            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }
    }

    // ─────────────────────────────────────────────
    //  正常搜索流程
    // ─────────────────────────────────────────────

    @Nested
    class 正常搜索流程 {

        @Test
        void 有效查询传入规范化后的查询和topK参数() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of());

            ftsSearcher.search("oolong tea preference", 5);

            var queryCaptor = ArgumentCaptor.forClass(String.class);
            var topKCaptor = ArgumentCaptor.forClass(Integer.class);
            // 验证 MATCH 参数和 LIMIT 参数
            verify(jdbcTemplate).query(
                    contains("MATCH"),
                    any(RowMapper.class),
                    queryCaptor.capture(),
                    topKCaptor.capture());

            // 规范化后的查询应包含引号包裹的关键词
            assertThat(queryCaptor.getValue()).contains("\"oolong\"");
            assertThat(queryCaptor.getValue()).contains("\"tea\"");
            assertThat(queryCaptor.getValue()).contains("\"preference\"");
            assertThat(topKCaptor.getValue()).isEqualTo(5);
        }

        @Test
        void 返回数据库查询结果() {
            Instant now = Instant.parse("2026-04-03T10:00:00Z");
            var expectedItems = List.of(
                    new RankedItem("e1", "PREFERENCE", "茶叶偏好", "喜欢乌龙茶",
                            0.85f, now, 0.7f, null, now),
                    new RankedItem("e2", "FACT", "咖啡习惯", "每天喝两杯",
                            0.65f, now, 0.5f, now.plusSeconds(86400), now)
            );
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(expectedItems);

            var results = ftsSearcher.search("tea coffee", 10);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).entityId()).isEqualTo("e1");
            assertThat(results.get(0).score()).isEqualTo(0.85f);
            assertThat(results.get(1).entityId()).isEqualTo("e2");
        }

        @Test
        void 数据库无匹配时返回空列表() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of());

            var results = ftsSearcher.search("completely irrelevant query words", 10);

            assertThat(results).isEmpty();
        }
    }

    // ─────────────────────────────────────────────
    //  SQL 查询验证
    // ─────────────────────────────────────────────

    @Nested
    class SQL查询验证 {

        @Test
        void SQL包含FTS5_MATCH和会话关联JOIN() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of());

            ftsSearcher.search("test query", 5);

            var sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(
                    sqlCaptor.capture(),
                    any(RowMapper.class),
                    any(), any());

            String sql = sqlCaptor.getValue();
            // 验证 SQL 结构中的关键部分
            assertThat(sql).contains("session_transcript_entries_fts MATCH ?");
            assertThat(sql).contains("matched_sessions");
            assertThat(sql).contains("temporal_entities te ON te.source_conversation_id = matched_sessions.session_id");
            assertThat(sql).contains("te.is_current = 1");
            assertThat(sql).contains("LIMIT ?");
        }

        @Test
        void SQL按BM25分数和重要性分数和更新时间降序排列() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of());

            ftsSearcher.search("test", 5);

            var sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(
                    sqlCaptor.capture(),
                    any(RowMapper.class),
                    any(), any());

            String sql = sqlCaptor.getValue();
            assertThat(sql).contains("ORDER BY matched_sessions.score DESC, te.importance_score DESC, te.updated_at DESC");
        }

        @Test
        void SQL仅查询可见消息类型() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of());

            ftsSearcher.search("test", 5);

            var sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(
                    sqlCaptor.capture(),
                    any(RowMapper.class),
                    any(), any());

            String sql = sqlCaptor.getValue();
            assertThat(sql).contains("entry_type IN ('user_message', 'assistant_message')");
            assertThat(sql).contains("visible_to_user = 1");
        }

        @Test
        void SQL排除过期实体() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of());

            ftsSearcher.search("test", 5);

            var sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(
                    sqlCaptor.capture(),
                    any(RowMapper.class),
                    any(), any());

            String sql = sqlCaptor.getValue();
            assertThat(sql).contains("te.valid_to IS NULL OR te.valid_to > datetime('now')");
        }
    }

    // ─────────────────────────────────────────────
    //  limit 参数
    // ─────────────────────────────────────────────

    @Nested
    class Limit参数 {

        @Test
        void topK正确传递给SQL() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of());

            ftsSearcher.search("test query", 3);

            verify(jdbcTemplate).query(
                    anyString(),
                    any(RowMapper.class),
                    anyString(),
                    eq(3));
        }

        @Test
        void topK为1时仅返回最多一条结果() {
            var singleResult = List.of(
                    new RankedItem("e1", "FACT", "name", "desc",
                            0.9f, null, 0.5f, null, Instant.now()));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(singleResult);

            var results = ftsSearcher.search("test", 1);

            assertThat(results).hasSize(1);
            verify(jdbcTemplate).query(
                    anyString(),
                    any(RowMapper.class),
                    anyString(),
                    eq(1));
        }

        @Test
        void topK为较大值时正常传递() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of());

            ftsSearcher.search("test", 100);

            verify(jdbcTemplate).query(
                    anyString(),
                    any(RowMapper.class),
                    anyString(),
                    eq(100));
        }
    }

    // ─────────────────────────────────────────────
    //  查询规范化
    // ─────────────────────────────────────────────

    @Nested
    class 查询规范化 {

        @Test
        void 中文查询保留为有效关键词() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of());

            ftsSearcher.search("乌龙茶偏好", 5);

            var queryCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(
                    anyString(),
                    any(RowMapper.class),
                    queryCaptor.capture(),
                    any());
            // 中文字符应被保留
            assertThat(queryCaptor.getValue()).contains("乌龙茶偏好");
        }

        @Test
        void 混合中英文查询正确规范化() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of());

            ftsSearcher.search("AI 资讯汇总 summary", 5);

            var queryCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(
                    anyString(),
                    any(RowMapper.class),
                    queryCaptor.capture(),
                    any());
            String normalized = queryCaptor.getValue();
            assertThat(normalized).contains("\"AI\"");
            assertThat(normalized).contains("\"summary\"");
        }

        @Test
        void JSON路径等噪声被过滤后仅保留有效关键词() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of());

            ftsSearcher.search("{\"key\":\"value\", \"path\":\"/usr/local/bin\"}", 5);

            var queryCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(
                    anyString(),
                    any(RowMapper.class),
                    queryCaptor.capture(),
                    any());
            String normalized = queryCaptor.getValue();
            // 应提取出有效词汇，不含 JSON 语法字符
            assertThat(normalized).doesNotContain("{");
            assertThat(normalized).doesNotContain("}");
            assertThat(normalized).contains("\"key\"");
            assertThat(normalized).contains("\"value\"");
        }
    }

    // ─────────────────────────────────────────────
    //  异常处理
    // ─────────────────────────────────────────────

    @Nested
    class 异常处理 {

        @Test
        void 数据库异常时返回空列表不抛出() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenThrow(new DataAccessException("连接失败") {});

            var results = ftsSearcher.search("test query", 5);

            assertThat(results).isEmpty();
        }

        @Test
        void 运行时异常时返回空列表不抛出() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenThrow(new RuntimeException("意外错误"));

            var results = ftsSearcher.search("test query", 5);

            assertThat(results).isEmpty();
        }

        @Test
        void 异常时仍调用了数据库查询() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenThrow(new RuntimeException("SQL 执行失败"));

            ftsSearcher.search("test", 5);

            verify(jdbcTemplate).query(
                    anyString(),
                    any(RowMapper.class),
                    anyString(),
                    eq(5));
        }
    }

    // ─────────────────────────────────────────────
    //  RankedItem 结果映射
    // ─────────────────────────────────────────────

    @Nested
    class 结果映射 {

        @Test
        void nullable字段为null时结果中对应字段也为null() {
            var items = List.of(
                    new RankedItem("e1", "TOPIC", "无描述", null,
                            0.5f, null, 0.3f, null, null));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(items);

            var results = ftsSearcher.search("topic", 5);

            assertThat(results).hasSize(1);
            var item = results.getFirst();
            assertThat(item.description()).isNull();
            assertThat(item.lastAccessedAt()).isNull();
            assertThat(item.validTo()).isNull();
            assertThat(item.updatedAt()).isNull();
        }

        @Test
        void 所有字段均有值时正确返回() {
            Instant now = Instant.parse("2026-04-03T10:00:00Z");
            Instant future = Instant.parse("2027-01-01T00:00:00Z");
            var items = List.of(
                    new RankedItem("e1", "PREFERENCE", "完整实体", "详细描述",
                            0.92f, now, 0.88f, future, now));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(items);

            var results = ftsSearcher.search("complete entity", 5);

            assertThat(results).hasSize(1);
            var item = results.getFirst();
            assertThat(item.entityId()).isEqualTo("e1");
            assertThat(item.entityType()).isEqualTo("PREFERENCE");
            assertThat(item.name()).isEqualTo("完整实体");
            assertThat(item.description()).isEqualTo("详细描述");
            assertThat(item.score()).isEqualTo(0.92f);
            assertThat(item.lastAccessedAt()).isEqualTo(now);
            assertThat(item.importanceScore()).isEqualTo(0.88f);
            assertThat(item.validTo()).isEqualTo(future);
            assertThat(item.updatedAt()).isEqualTo(now);
        }

        @Test
        void 多条结果保持原始顺序() {
            Instant now = Instant.now();
            var items = List.of(
                    new RankedItem("e1", "FACT", "first", null, 0.9f, now, 0.8f, null, now),
                    new RankedItem("e2", "FACT", "second", null, 0.7f, now, 0.6f, null, now),
                    new RankedItem("e3", "FACT", "third", null, 0.5f, now, 0.4f, null, now)
            );
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(items);

            var results = ftsSearcher.search("facts", 10);

            assertThat(results).extracting(RankedItem::entityId)
                    .containsExactly("e1", "e2", "e3");
            assertThat(results).extracting(RankedItem::score)
                    .containsExactly(0.9f, 0.7f, 0.5f);
        }
    }

    // ─────────────────────────────────────────────
    //  属性测试
    // ─────────────────────────────────────────────

    /**
     * 属性测试：任意非空文本查询 FtsSearcher.search 永远不抛异常，
     * 要么返回结果列表，要么返回空列表。
     */
    @Property(tries = 200)
    void 任意文本查询不抛异常(@ForAll("任意查询文本") String query,
                        @ForAll @IntRange(min = 1, max = 100) int topK) {
        // 为属性测试构造独立的 mock，避免共享状态
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        when(mockJdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(List.of());
        var searcher = new FtsSearcher(mockJdbc);

        var results = searcher.search(query, topK);

        assertThat(results).isNotNull();
    }

    @Provide("任意查询文本")
    Arbitrary<String> 任意查询文本() {
        var chineseChars = Arbitraries.chars().range('\u4e00', '\u9fff');
        var asciiChars = Arbitraries.chars().ascii();
        var specialChars = Arbitraries.of(
                ':', '"', '(', ')', '*', '^', '+', '-', '~',
                '{', '}', '[', ']', '/', '\\', '.', ',');
        var mixed = Arbitraries.frequencyOf(
                Tuple.of(2, asciiChars),
                Tuple.of(2, chineseChars),
                Tuple.of(1, specialChars));

        return mixed.list().ofMinSize(0).ofMaxSize(80)
                .map(chars -> {
                    var sb = new StringBuilder();
                    chars.forEach(sb::append);
                    return sb.toString();
                });
    }
}
