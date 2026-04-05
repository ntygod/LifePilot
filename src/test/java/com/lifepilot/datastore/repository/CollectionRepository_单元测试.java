package com.lifepilot.datastore.repository;

import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CollectionRepository 单元测试 — 验证集合仓储的 CRUD 操作和 Generated Column DDL 管理。
 *
 * <p>通过 mock JdbcTemplate 隔离数据库依赖，聚焦于 SQL 拼装、参数传递和返回值处理逻辑。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class CollectionRepository_单元测试 {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private CollectionRepository repository;

    @BeforeEach
    void 初始化() {
        repository = new CollectionRepository(jdbcTemplate);
    }

    // ==================== 辅助方法 ====================

    /** 构造测试用 Collection 实例。 */
    private Collection 构造测试集合(CollectionType type) {
        return new Collection(
                null, "test-collection", "测试集合", type,
                "[{\"name\":\"title\"}]", "{\"dim\":128}",
                "{\"version\":1}", "kb-001", "test-user",
                "2026-04-01T00:00:00Z", "2026-04-01T00:00:00Z"
        );
    }

    /** 构造带完整字段的 Collection 结果行。 */
    private Collection 构造结果集合(String id) {
        return new Collection(
                id, "test-collection", "测试集合", CollectionType.DOCUMENT,
                "[{\"name\":\"title\"}]", "{}",
                "{\"version\":1}", "kb-001", "test-user",
                "2026-04-01T00:00:00Z", "2026-04-01T00:00:00Z"
        );
    }

    // ==================== 插入测试 ====================

    @Nested
    class 插入集合 {

        @Test
        void 正常插入_返回生成的UUID() {
            // given
            var collection = 构造测试集合(CollectionType.DOCUMENT);

            // when
            String id = repository.insert(collection);

            // then — 返回值是合法 UUID 格式
            assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }

        @Test
        void 正常插入_传递正确的SQL参数() {
            // given
            var collection = 构造测试集合(CollectionType.NOTE);

            // when
            String id = repository.insert(collection);

            // then — 验证 JdbcTemplate.update 被正确调用
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("INSERT INTO ds_collections"), argsCaptor.capture());

            Object[] args = argsCaptor.getValue();
            assertThat(args[0]).isEqualTo(id);                           // id
            assertThat(args[1]).isEqualTo("test-collection");            // name
            assertThat(args[2]).isEqualTo("测试集合");                     // description
            assertThat(args[3]).isEqualTo("NOTE");                       // type
            assertThat(args[4]).isEqualTo("[{\"name\":\"title\"}]");     // propertiesJson
            assertThat(args[5]).isEqualTo("{\"dim\":128}");              // projectionConfigJson（非 null 不归一化）
            assertThat(args[6]).isEqualTo("{\"version\":1}");            // metadataJson
            assertThat(args[7]).isEqualTo("kb-001");                     // defaultKnowledgeBaseId
            assertThat(args[8]).isEqualTo("test-user");                  // createdBy
            assertThat((String) args[9]).startsWith("2026-");            // createdAt（Instant.now()）
            assertThat((String) args[10]).startsWith("2026-");           // updatedAt（Instant.now()）
        }

        @Test
        void projectionConfigJson为null时_归一化为默认值() {
            // given — projectionConfigJson 为 null
            var collection = new Collection(
                    null, "no-projection", null, CollectionType.DOCUMENT,
                    null, null, null, null, null,
                    "2026-04-01T00:00:00Z", "2026-04-01T00:00:00Z"
            );

            // when
            repository.insert(collection);

            // then — projectionConfigJson 参数位置应为 "{}"
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("INSERT INTO ds_collections"), argsCaptor.capture());

            Object[] args = argsCaptor.getValue();
            assertThat(args[5]).isEqualTo("{}");
        }
    }

    // ==================== 查询测试 ====================

    @Nested
    class 按ID查询 {

        @SuppressWarnings("unchecked")
        @Test
        void 存在记录_返回Optional包装值() {
            // given
            var expected = 构造结果集合("uuid-001");
            when(jdbcTemplate.query(contains("WHERE id = ?"), any(RowMapper.class), eq("uuid-001")))
                    .thenReturn(List.of(expected));

            // when
            Optional<Collection> result = repository.findById("uuid-001");

            // then
            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("uuid-001");
            assertThat(result.get().name()).isEqualTo("test-collection");
        }

        @SuppressWarnings("unchecked")
        @Test
        void 不存在记录_返回空Optional() {
            // given
            when(jdbcTemplate.query(contains("WHERE id = ?"), any(RowMapper.class), eq("nonexistent")))
                    .thenReturn(Collections.emptyList());

            // when
            Optional<Collection> result = repository.findById("nonexistent");

            // then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class 按名称查询 {

        @SuppressWarnings("unchecked")
        @Test
        void 存在记录_返回Optional包装值() {
            // given
            var expected = 构造结果集合("uuid-002");
            when(jdbcTemplate.query(contains("WHERE name = ?"), any(RowMapper.class), eq("todos")))
                    .thenReturn(List.of(expected));

            // when
            Optional<Collection> result = repository.findByName("todos");

            // then
            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("uuid-002");
        }

        @SuppressWarnings("unchecked")
        @Test
        void 不存在记录_返回空Optional() {
            // given
            when(jdbcTemplate.query(contains("WHERE name = ?"), any(RowMapper.class), eq("ghost")))
                    .thenReturn(Collections.emptyList());

            // when
            Optional<Collection> result = repository.findByName("ghost");

            // then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class 按类型查询 {

        @SuppressWarnings("unchecked")
        @Test
        void 返回匹配类型的集合列表() {
            // given
            var col1 = 构造结果集合("uuid-a");
            var col2 = 构造结果集合("uuid-b");
            when(jdbcTemplate.query(contains("WHERE type = ?"), any(RowMapper.class), eq("NOTE")))
                    .thenReturn(List.of(col1, col2));

            // when
            List<Collection> results = repository.findByType(CollectionType.NOTE);

            // then
            assertThat(results).hasSize(2);
        }

        @SuppressWarnings("unchecked")
        @Test
        void 无匹配记录_返回空列表() {
            // given
            when(jdbcTemplate.query(contains("WHERE type = ?"), any(RowMapper.class), eq("METRIC")))
                    .thenReturn(Collections.emptyList());

            // when
            List<Collection> results = repository.findByType(CollectionType.METRIC);

            // then
            assertThat(results).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void SQL包含按创建时间降序排序() {
            // given
            when(jdbcTemplate.query(contains("ORDER BY created_at DESC"), any(RowMapper.class), anyString()))
                    .thenReturn(Collections.emptyList());

            // when
            repository.findByType(CollectionType.DOCUMENT);

            // then — 验证 SQL 包含排序子句
            verify(jdbcTemplate).query(contains("ORDER BY created_at DESC"), any(RowMapper.class), eq("DOCUMENT"));
        }
    }

    @Nested
    class 查询全部 {

        @SuppressWarnings("unchecked")
        @Test
        void 返回所有集合() {
            // given
            var col1 = 构造结果集合("uuid-1");
            var col2 = 构造结果集合("uuid-2");
            var col3 = 构造结果集合("uuid-3");
            when(jdbcTemplate.query(contains("ORDER BY created_at DESC"), any(RowMapper.class)))
                    .thenReturn(List.of(col1, col2, col3));

            // when
            List<Collection> results = repository.findAll();

            // then
            assertThat(results).hasSize(3);
        }

        @SuppressWarnings("unchecked")
        @Test
        void 空表_返回空列表() {
            // given
            when(jdbcTemplate.query(contains("FROM ds_collections"), any(RowMapper.class)))
                    .thenReturn(Collections.emptyList());

            // when
            List<Collection> results = repository.findAll();

            // then
            assertThat(results).isEmpty();
        }
    }

    // ==================== 更新测试 ====================

    @Nested
    class 更新集合 {

        @Test
        void 更新成功_返回true() {
            // given
            when(jdbcTemplate.update(contains("UPDATE ds_collections"), any(Object[].class)))
                    .thenReturn(1);

            // when
            boolean result = repository.update("uuid-001", "新描述", "{\"dim\":256}", "{\"v\":2}");

            // then
            assertThat(result).isTrue();
        }

        @Test
        void 记录不存在_返回false() {
            // given
            when(jdbcTemplate.update(contains("UPDATE ds_collections"), any(Object[].class)))
                    .thenReturn(0);

            // when
            boolean result = repository.update("nonexistent", "描述", null, null);

            // then
            assertThat(result).isFalse();
        }

        @Test
        void 传递正确的SQL参数_包含归一化的projectionConfigJson() {
            // given
            when(jdbcTemplate.update(contains("UPDATE ds_collections"), any(Object[].class)))
                    .thenReturn(1);

            // when
            repository.update("uuid-001", "新描述", null, "{\"meta\":true}");

            // then
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("UPDATE ds_collections"), argsCaptor.capture());

            Object[] args = argsCaptor.getValue();
            assertThat(args[0]).isEqualTo("新描述");             // description
            assertThat(args[1]).isEqualTo("{}");                 // projectionConfigJson（null → "{}"）
            assertThat(args[2]).isEqualTo("{\"meta\":true}");    // metadataJson
            // args[3] 是 updatedAt（动态值）
            assertThat(args[4]).isEqualTo("uuid-001");           // id（WHERE 条件）
        }

        @Test
        void projectionConfigJson非null时保持原值() {
            // given
            when(jdbcTemplate.update(contains("UPDATE ds_collections"), any(Object[].class)))
                    .thenReturn(1);

            // when
            repository.update("uuid-001", null, "{\"dim\":512}", null);

            // then
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("UPDATE ds_collections"), argsCaptor.capture());

            Object[] args = argsCaptor.getValue();
            assertThat(args[1]).isEqualTo("{\"dim\":512}");
        }
    }

    @Nested
    class 更新默认知识库ID {

        @Test
        void 更新成功_返回true() {
            // given
            when(jdbcTemplate.update(contains("default_knowledge_base_id"), any(Object[].class)))
                    .thenReturn(1);

            // when
            boolean result = repository.updateDefaultKnowledgeBaseId("uuid-001", "kb-new");

            // then
            assertThat(result).isTrue();
        }

        @Test
        void 记录不存在_返回false() {
            // given
            when(jdbcTemplate.update(contains("default_knowledge_base_id"), any(Object[].class)))
                    .thenReturn(0);

            // when
            boolean result = repository.updateDefaultKnowledgeBaseId("nonexistent", "kb-new");

            // then
            assertThat(result).isFalse();
        }

        @Test
        void 传递null知识库ID_允许清除关联() {
            // given
            when(jdbcTemplate.update(contains("default_knowledge_base_id"), any(Object[].class)))
                    .thenReturn(1);

            // when
            boolean result = repository.updateDefaultKnowledgeBaseId("uuid-001", null);

            // then
            assertThat(result).isTrue();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("default_knowledge_base_id"), argsCaptor.capture());

            Object[] args = argsCaptor.getValue();
            assertThat(args[0]).isNull();            // defaultKnowledgeBaseId
            assertThat(args[2]).isEqualTo("uuid-001"); // id（WHERE 条件）
        }
    }

    // ==================== 删除测试 ====================

    @Nested
    class 删除集合 {

        @Test
        void 删除成功_返回true() {
            // given
            when(jdbcTemplate.update(eq("DELETE FROM ds_collections WHERE id = ?"), any(Object[].class)))
                    .thenReturn(1);

            // when
            boolean result = repository.delete("uuid-001");

            // then
            assertThat(result).isTrue();
        }

        @Test
        void 记录不存在_返回false() {
            // given
            when(jdbcTemplate.update(eq("DELETE FROM ds_collections WHERE id = ?"), any(Object[].class)))
                    .thenReturn(0);

            // when
            boolean result = repository.delete("nonexistent");

            // then
            assertThat(result).isFalse();
        }
    }

    // ==================== 计数测试 ====================

    @Nested
    class 集合计数 {

        @Test
        void 返回集合总数() {
            // given
            when(jdbcTemplate.queryForObject(contains("COUNT(*)"), eq(Integer.class)))
                    .thenReturn(5);

            // when
            int count = repository.count();

            // then
            assertThat(count).isEqualTo(5);
        }

        @Test
        void 空表返回零() {
            // given
            when(jdbcTemplate.queryForObject(contains("COUNT(*)"), eq(Integer.class)))
                    .thenReturn(0);

            // when
            int count = repository.count();

            // then
            assertThat(count).isZero();
        }

        @Test
        void queryForObject返回null时_防御性返回零() {
            // given — JdbcTemplate 理论上不应返回 null，但代码有防御逻辑
            when(jdbcTemplate.queryForObject(contains("COUNT(*)"), eq(Integer.class)))
                    .thenReturn(null);

            // when
            int count = repository.count();

            // then
            assertThat(count).isZero();
        }
    }

    // ==================== Generated Column DDL 管理 ====================

    @Nested
    class 添加生成列 {

        @Test
        void 执行ALTER_TABLE和CREATE_INDEX两条DDL() {
            // when
            repository.addGeneratedColumn("title", "TEXT", "abcdef01-2345-6789-abcd-ef0123456789");

            // then — 应调用两次 execute
            verify(jdbcTemplate, times(2)).execute(anyString());
        }

        @Test
        void ALTER_TABLE语句包含正确的列名和亲和性() {
            // given
            String collectionId = "12345678-aaaa-bbbb-cccc-dddddddddddd";

            // when
            repository.addGeneratedColumn("price", "REAL", collectionId);

            // then
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, times(2)).execute(sqlCaptor.capture());

            String alterSql = sqlCaptor.getAllValues().get(0);
            assertThat(alterSql).contains("ALTER TABLE ds_documents ADD COLUMN");
            assertThat(alterSql).contains("_idx_12345678_price");
            assertThat(alterSql).contains("REAL");
            assertThat(alterSql).contains("json_extract(data_json, '$.price')");
            assertThat(alterSql).contains("VIRTUAL");
        }

        @Test
        void CREATE_INDEX语句包含正确的索引名和partial条件() {
            // given
            String collectionId = "aabbccdd-1111-2222-3333-444444444444";

            // when
            repository.addGeneratedColumn("status", "TEXT", collectionId);

            // then
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, times(2)).execute(sqlCaptor.capture());

            String indexSql = sqlCaptor.getAllValues().get(1);
            assertThat(indexSql).contains("CREATE INDEX");
            assertThat(indexSql).contains("idx_ds_doc_aabbccdd_status");
            assertThat(indexSql).contains("_idx_aabbccdd_status");
            assertThat(indexSql).contains("WHERE collection_id = '%s'".formatted(collectionId));
        }

        @Test
        void INTEGER亲和性_生成正确DDL() {
            // given
            String collectionId = "11111111-2222-3333-4444-555555555555";

            // when
            repository.addGeneratedColumn("count", "INTEGER", collectionId);

            // then
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, times(2)).execute(sqlCaptor.capture());

            String alterSql = sqlCaptor.getAllValues().get(0);
            assertThat(alterSql).contains("INTEGER");
            assertThat(alterSql).contains("_idx_11111111_count");
        }
    }

    @Nested
    class 删除生成列索引 {

        @Test
        void 单个属性_执行一次DROP_INDEX() {
            // given
            String collectionId = "abcdef01-2345-6789-abcd-ef0123456789";

            // when
            repository.dropGeneratedColumns(collectionId, List.of("title"));

            // then
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, times(1)).execute(sqlCaptor.capture());

            String dropSql = sqlCaptor.getValue();
            assertThat(dropSql).isEqualTo("DROP INDEX IF EXISTS idx_ds_doc_abcdef01_title");
        }

        @Test
        void 多个属性_按顺序执行多次DROP_INDEX() {
            // given
            String collectionId = "aabbccdd-1111-2222-3333-444444444444";

            // when
            repository.dropGeneratedColumns(collectionId, List.of("title", "price", "status"));

            // then
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, times(3)).execute(sqlCaptor.capture());

            List<String> sqls = sqlCaptor.getAllValues();
            assertThat(sqls.get(0)).isEqualTo("DROP INDEX IF EXISTS idx_ds_doc_aabbccdd_title");
            assertThat(sqls.get(1)).isEqualTo("DROP INDEX IF EXISTS idx_ds_doc_aabbccdd_price");
            assertThat(sqls.get(2)).isEqualTo("DROP INDEX IF EXISTS idx_ds_doc_aabbccdd_status");
        }

        @Test
        void 空属性列表_不执行任何DDL() {
            // given
            String collectionId = "12345678-aaaa-bbbb-cccc-dddddddddddd";

            // when
            repository.dropGeneratedColumns(collectionId, Collections.emptyList());

            // then
            verify(jdbcTemplate, never()).execute(anyString());
        }
    }

    // ==================== 列名与索引名前缀截取 ====================

    @Nested
    class 前缀截取一致性 {

        @Test
        void 添加和删除使用相同的前缀截取逻辑() {
            // given — 同一个 collectionId
            String collectionId = "fedcba98-7654-3210-abcd-ef0123456789";
            String expectedPrefix = "fedcba98";
            String propertyName = "weight";

            // when — 先添加再删除
            repository.addGeneratedColumn(propertyName, "REAL", collectionId);
            repository.dropGeneratedColumns(collectionId, List.of(propertyName));

            // then — 验证添加和删除使用了相同的索引名
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, times(3)).execute(sqlCaptor.capture());

            String createIndexSql = sqlCaptor.getAllValues().get(1);
            String dropIndexSql = sqlCaptor.getAllValues().get(2);

            String expectedIndexName = "idx_ds_doc_%s_%s".formatted(expectedPrefix, propertyName);
            assertThat(createIndexSql).contains(expectedIndexName);
            assertThat(dropIndexSql).contains(expectedIndexName);
        }
    }
}
