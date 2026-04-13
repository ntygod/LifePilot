package com.lifepilot.datastore.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.FilterOp;
import com.lifepilot.datastore.model.QueryFilter;
import com.lifepilot.datastore.model.QueryRequest;
import com.lifepilot.datastore.model.SortDirection;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.tool.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DataStoreCrudAdapter 单元测试 — 验证泛型 CRUD 适配器的序列化/反序列化、
 * 集合初始化双重检查锁定、以及各 CRUD 方法的正常和异常路径。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class DataStoreCrudAdapter_单元测试 {

    @Mock
    private DataStoreManager dataStoreManager;

    private ObjectMapper objectMapper;

    private DataStoreCrudAdapter<TestEntity> adapter;

    /** 测试用领域实体。 */
    record TestEntity(String name, int age) {}

    /** 无法序列化的实体（用于触发序列化失败）。 */
    static class UnserializableEntity {
        @SuppressWarnings("unused")
        UnserializableEntity getSelf() { return this; }
    }

    private static final String COLLECTION_NAME = "test-entities";
    private static final String COLLECTION_ID = "col-001";
    private static final String DOCUMENT_ID = "doc-001";
    private static final String NOW = Instant.now().toString();

    @BeforeEach
    void 初始化() {
        objectMapper = new ObjectMapper();
        var config = new CrudAdapterConfig<>(
                "test",
                COLLECTION_NAME,
                false,
                TestEntity.class,
                null,
                "测试集合"
        );
        adapter = new DataStoreCrudAdapter<>(dataStoreManager, objectMapper, config);
    }

    // ==================== 辅助方法 ====================

    /** 创建一个标准的 Collection 实例。 */
    private Collection 创建集合(String id) {
        return Collection.builder()
                .id(id)
                .name(COLLECTION_NAME)
                .timeSeries(false)
                .description("测试集合")
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    /** 创建一个标准的 Document 实例。 */
    private Document 创建文档(String docId, String content) {
        return new Document(
                docId,
                "kb-001",
                "test-doc",
                "datastore://col-001/" + docId,
                content.getBytes().length,
                "text/plain",
                "hash",
                DocumentStatus.READY,
                0, 0, null, null,
                Map.of(),
                Instant.now(), Instant.now(),
                DocumentSourceType.DATASTORE_DOCUMENT,
                "DATASTORE:col-001:" + docId,
                COLLECTION_ID, null,
                Map.of(),
                content, null
        );
    }

    /** 准备 ensureCollection 的 mock：集合已存在。 */
    private void 模拟集合已存在() {
        when(dataStoreManager.findCollection(COLLECTION_NAME))
                .thenReturn(Optional.of(创建集合(COLLECTION_ID)));
    }

    /** 准备 ensureCollection 的 mock：集合不存在，需要创建。 */
    private void 模拟集合不存在需要创建() {
        when(dataStoreManager.findCollection(COLLECTION_NAME))
                .thenReturn(Optional.empty());
        when(dataStoreManager.createCollection(
                eq(COLLECTION_NAME),
                eq(false),
                isNull(),
                eq("测试集合"),
                eq("skill:test")
        )).thenReturn(创建集合(COLLECTION_ID));
    }

    // ==================== ensureCollection 测试 ====================

    @Nested
    class 集合初始化 {

        @Test
        void 集合已存在_返回缓存的集合ID() {
            模拟集合已存在();

            String id1 = adapter.ensureCollection();
            String id2 = adapter.ensureCollection();

            assertThat(id1).isEqualTo(COLLECTION_ID);
            assertThat(id2).isEqualTo(COLLECTION_ID);
            // findCollection 只调用一次，第二次走缓存
            verify(dataStoreManager, times(1)).findCollection(COLLECTION_NAME);
        }

        @Test
        void 集合不存在_自动创建并缓存() {
            模拟集合不存在需要创建();

            String id = adapter.ensureCollection();

            assertThat(id).isEqualTo(COLLECTION_ID);
            verify(dataStoreManager).createCollection(
                    eq(COLLECTION_NAME),
                    eq(false),
                    isNull(),
                    eq("测试集合"),
                    eq("skill:test")
            );
        }

        @Test
        void 并发初始化_只创建一次集合() throws Exception {
            // 使用 CyclicBarrier 确保多个线程同时触发 ensureCollection
            int threadCount = 10;
            var barrier = new CyclicBarrier(threadCount);
            var latch = new CountDownLatch(threadCount);

            // findCollection 人为延迟以增加竞争窗口
            when(dataStoreManager.findCollection(COLLECTION_NAME))
                    .thenAnswer(invocation -> {
                        Thread.sleep(10); // 模拟 I/O 延迟
                        return Optional.of(创建集合(COLLECTION_ID));
                    });

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<String>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < threadCount; i++) {
                    futures.add(executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            return adapter.ensureCollection();
                        } finally {
                            latch.countDown();
                        }
                    }));
                }

                latch.await(10, TimeUnit.SECONDS);

                // 所有线程返回相同的 collectionId
                for (var future : futures) {
                    assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(COLLECTION_ID);
                }
            }

            // findCollection 最多调用一次（双重检查锁定）
            verify(dataStoreManager, atMost(1)).findCollection(COLLECTION_NAME);
        }

        @Test
        void 缓存后不再调用DataStoreManager() {
            模拟集合已存在();

            // 首次调用：触发 findCollection
            adapter.ensureCollection();
            // 多次后续调用：全部走缓存
            for (int i = 0; i < 100; i++) {
                adapter.ensureCollection();
            }

            verify(dataStoreManager, times(1)).findCollection(anyString());
            verify(dataStoreManager, never()).createCollection(any(), anyBoolean(), any(), any(), any());
        }
    }

    // ==================== create 测试 ====================

    @Nested
    class 创建实体 {

        @Test
        void 正常创建_返回文档ID() {
            模拟集合已存在();
            var entity = new TestEntity("张三", 25);
            when(dataStoreManager.addDocument(eq(COLLECTION_ID), anyString(), isNull(), isNull()))
                    .thenReturn(DOCUMENT_ID);

            ToolResult result = adapter.create(entity);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.<String>getData("documentId")).isEqualTo(DOCUMENT_ID);
        }

        @Test
        void 正常创建_序列化内容正确传递给DataStoreManager() {
            模拟集合已存在();
            var entity = new TestEntity("李四", 30);
            when(dataStoreManager.addDocument(eq(COLLECTION_ID), anyString(), isNull(), isNull()))
                    .thenReturn(DOCUMENT_ID);

            adapter.create(entity);

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(dataStoreManager).addDocument(eq(COLLECTION_ID), jsonCaptor.capture(), isNull(), isNull());
            String capturedJson = jsonCaptor.getValue();
            assertThat(capturedJson).contains("\"name\":\"李四\"");
            assertThat(capturedJson).contains("\"age\":30");
        }

        @Test
        void 序列化失败_返回错误ToolResult() {
            // 使用会导致序列化失败的 ObjectMapper（模拟 JsonProcessingException）
            var failingMapper = mock(ObjectMapper.class);
            try {
                when(failingMapper.writeValueAsString(any()))
                        .thenThrow(new JsonProcessingException("模拟序列化错误") {});
            } catch (JsonProcessingException e) {
                fail("设置 mock 不应抛出异常");
            }

            var config = new CrudAdapterConfig<>(
                    "test", COLLECTION_NAME, false,
                    TestEntity.class, null, "测试集合"
            );
            var failAdapter = new DataStoreCrudAdapter<>(dataStoreManager, failingMapper, config);

            ToolResult result = failAdapter.create(new TestEntity("test", 1));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("序列化失败");
            // 序列化失败时不应调用 addDocument
            verify(dataStoreManager, never()).addDocument(any(), any(), any(), any());
        }

        @Test
        void 首次创建触发集合自动创建() {
            模拟集合不存在需要创建();
            var entity = new TestEntity("王五", 20);
            when(dataStoreManager.addDocument(eq(COLLECTION_ID), anyString(), isNull(), isNull()))
                    .thenReturn(DOCUMENT_ID);

            adapter.create(entity);

            verify(dataStoreManager).createCollection(
                    eq(COLLECTION_NAME), eq(false),
                    isNull(), eq("测试集合"), eq("skill:test")
            );
        }
    }

    // ==================== findById 测试 ====================

    @Nested
    class 按ID查找 {

        @Test
        void 文档存在_返回反序列化后的实体() {
            var doc = 创建文档(DOCUMENT_ID, "{\"name\":\"张三\",\"age\":25}");
            when(dataStoreManager.getDocument(DOCUMENT_ID))
                    .thenReturn(Optional.of(doc));

            Optional<TestEntity> result = adapter.findById(DOCUMENT_ID);

            assertThat(result).isPresent();
            assertThat(result.get().name()).isEqualTo("张三");
            assertThat(result.get().age()).isEqualTo(25);
        }

        @Test
        void 文档不存在_返回空Optional() {
            when(dataStoreManager.getDocument("nonexistent"))
                    .thenReturn(Optional.empty());

            Optional<TestEntity> result = adapter.findById("nonexistent");

            assertThat(result).isEmpty();
        }

        @Test
        void 反序列化失败_返回空Optional() {
            // content 不是有效的 TestEntity JSON
            var doc = 创建文档(DOCUMENT_ID, "这不是合法JSON");
            when(dataStoreManager.getDocument(DOCUMENT_ID))
                    .thenReturn(Optional.of(doc));

            Optional<TestEntity> result = adapter.findById(DOCUMENT_ID);

            assertThat(result).isEmpty();
        }

        @Test
        void 字段类型不匹配_反序列化失败返回空() {
            // age 字段期望 int，传入 string
            var doc = 创建文档(DOCUMENT_ID, "{\"name\":\"张三\",\"age\":\"不是数字\"}");
            when(dataStoreManager.getDocument(DOCUMENT_ID))
                    .thenReturn(Optional.of(doc));

            Optional<TestEntity> result = adapter.findById(DOCUMENT_ID);

            assertThat(result).isEmpty();
        }
    }

    // ==================== list 测试 ====================

    @Nested
    class 列表查询 {

        @BeforeEach
        void 准备集合() {
            模拟集合已存在();
        }

        @Test
        void 正常查询_返回反序列化后的实体列表() {
            var doc1 = 创建文档("doc-1", "{\"name\":\"张三\",\"age\":25}");
            var doc2 = 创建文档("doc-2", "{\"name\":\"李四\",\"age\":30}");
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of(doc1, doc2));

            List<TestEntity> result = adapter.list(null, null, null, 0, 10);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).name()).isEqualTo("张三");
            assertThat(result.get(1).name()).isEqualTo("李四");
        }

        @Test
        void 带过滤条件查询_正确构建QueryRequest() {
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of());
            var filters = List.of(new QueryFilter("age", FilterOp.GT, 20));

            adapter.list(filters, "age", SortDirection.ASC, 5, 20);

            ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(dataStoreManager).queryDocuments(captor.capture());
            QueryRequest captured = captor.getValue();
            assertThat(captured.collectionId()).isEqualTo(COLLECTION_ID);
            assertThat(captured.filters()).hasSize(1);
            assertThat(captured.filters().getFirst().field()).isEqualTo("age");
            assertThat(captured.sortField()).isEqualTo("age");
            assertThat(captured.sortDirection()).isEqualTo(SortDirection.ASC);
            assertThat(captured.offset()).isEqualTo(5);
            assertThat(captured.limit()).isEqualTo(20);
        }

        @Test
        void filters为null_使用空列表() {
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of());

            adapter.list(null, null, null, 0, 10);

            ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(dataStoreManager).queryDocuments(captor.capture());
            assertThat(captor.getValue().filters()).isEmpty();
        }

        @Test
        void 部分文档反序列化失败_跳过失败文档() {
            var validDoc = 创建文档("doc-1", "{\"name\":\"张三\",\"age\":25}");
            var invalidDoc = 创建文档("doc-2", "不合法的JSON");
            var anotherValid = 创建文档("doc-3", "{\"name\":\"李四\",\"age\":30}");
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of(validDoc, invalidDoc, anotherValid));

            List<TestEntity> result = adapter.list(null, null, null, 0, 10);

            // 只返回 2 条成功反序列化的
            assertThat(result).hasSize(2);
            assertThat(result.get(0).name()).isEqualTo("张三");
            assertThat(result.get(1).name()).isEqualTo("李四");
        }

        @Test
        void 查询结果为空_返回空列表() {
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of());

            List<TestEntity> result = adapter.list(null, null, null, 0, 10);

            assertThat(result).isEmpty();
        }

        @Test
        void 所有文档反序列化失败_返回空列表() {
            var bad1 = 创建文档("doc-1", "bad-json-1");
            var bad2 = 创建文档("doc-2", "bad-json-2");
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of(bad1, bad2));

            List<TestEntity> result = adapter.list(null, null, null, 0, 10);

            assertThat(result).isEmpty();
        }
    }

    // ==================== listWithId 测试 ====================

    @Nested
    class 列表查询带ID {

        @BeforeEach
        void 准备集合() {
            模拟集合已存在();
        }

        @Test
        void 正常查询_返回文档ID和实体的Entry列表() {
            var doc1 = 创建文档("doc-1", "{\"name\":\"张三\",\"age\":25}");
            var doc2 = 创建文档("doc-2", "{\"name\":\"李四\",\"age\":30}");
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of(doc1, doc2));

            List<Map.Entry<String, TestEntity>> result =
                    adapter.listWithId(null, null, null, 0, 10);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getKey()).isEqualTo("doc-1");
            assertThat(result.get(0).getValue().name()).isEqualTo("张三");
            assertThat(result.get(1).getKey()).isEqualTo("doc-2");
            assertThat(result.get(1).getValue().name()).isEqualTo("李四");
        }

        @Test
        void 部分文档反序列化失败_跳过失败文档() {
            var validDoc = 创建文档("doc-1", "{\"name\":\"张三\",\"age\":25}");
            var invalidDoc = 创建文档("doc-bad", "invalid");
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of(validDoc, invalidDoc));

            List<Map.Entry<String, TestEntity>> result =
                    adapter.listWithId(null, null, null, 0, 10);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getKey()).isEqualTo("doc-1");
        }

        @Test
        void filters为null_使用空列表() {
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of());

            adapter.listWithId(null, "name", SortDirection.DESC, 0, 5);

            ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(dataStoreManager).queryDocuments(captor.capture());
            assertThat(captor.getValue().filters()).isEmpty();
        }
    }

    // ==================== update 测试 ====================

    @Nested
    class 更新实体 {

        @Test
        void 正常更新_返回文档ID() {
            var entity = new TestEntity("张三更新", 26);

            ToolResult result = adapter.update(DOCUMENT_ID, entity);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.<String>getData("documentId")).isEqualTo(DOCUMENT_ID);
        }

        @Test
        void 正常更新_序列化内容正确传递() {
            var entity = new TestEntity("王五", 35);

            adapter.update(DOCUMENT_ID, entity);

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(dataStoreManager).updateDocument(eq(DOCUMENT_ID), jsonCaptor.capture(), isNull());
            assertThat(jsonCaptor.getValue()).contains("\"name\":\"王五\"");
            assertThat(jsonCaptor.getValue()).contains("\"age\":35");
        }

        @Test
        void 序列化失败_返回错误ToolResult() throws Exception {
            var failingMapper = mock(ObjectMapper.class);
            when(failingMapper.writeValueAsString(any()))
                    .thenThrow(new JsonProcessingException("模拟更新序列化错误") {});

            var config = new CrudAdapterConfig<>(
                    "test", COLLECTION_NAME, false,
                    TestEntity.class, null, "测试集合"
            );
            var failAdapter = new DataStoreCrudAdapter<>(dataStoreManager, failingMapper, config);

            ToolResult result = failAdapter.update(DOCUMENT_ID, new TestEntity("test", 1));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("序列化失败");
            // 序列化失败时不应调用 updateDocument
            verify(dataStoreManager, never()).updateDocument(any(), any(), any());
        }

        @Test
        void 更新不触发集合初始化() {
            var entity = new TestEntity("test", 1);

            adapter.update(DOCUMENT_ID, entity);

            // update 不调用 ensureCollection
            verify(dataStoreManager, never()).findCollection(any());
            verify(dataStoreManager, never()).createCollection(any(), anyBoolean(), any(), any(), any());
        }
    }

    // ==================== delete 测试 ====================

    @Nested
    class 删除实体 {

        @Test
        void 正常删除_返回文档ID() {
            ToolResult result = adapter.delete(DOCUMENT_ID);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.<String>getData("documentId")).isEqualTo(DOCUMENT_ID);
            verify(dataStoreManager).deleteDocument(DOCUMENT_ID);
        }

        @Test
        void 删除_正确委托给DataStoreManager() {
            String docId = "doc-specific-id";

            adapter.delete(docId);

            verify(dataStoreManager).deleteDocument(docId);
        }

        @Test
        void 删除不触发集合初始化() {
            adapter.delete(DOCUMENT_ID);

            verify(dataStoreManager, never()).findCollection(any());
            verify(dataStoreManager, never()).createCollection(any(), anyBoolean(), any(), any(), any());
        }

        @Test
        void DataStoreManager抛出异常_向上传播() {
            doThrow(new IllegalArgumentException("文档不存在: id=" + DOCUMENT_ID))
                    .when(dataStoreManager).deleteDocument(DOCUMENT_ID);

            assertThatThrownBy(() -> adapter.delete(DOCUMENT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("文档不存在");
        }
    }

    // ==================== 集合初始化与 CRUD 操作的交互测试 ====================

    @Nested
    class 集合初始化与操作交互 {

        @Test
        void create调用后list复用缓存的集合ID() {
            模拟集合已存在();
            var entity = new TestEntity("测试", 1);
            when(dataStoreManager.addDocument(eq(COLLECTION_ID), anyString(), isNull(), isNull()))
                    .thenReturn(DOCUMENT_ID);
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of());

            // 第一次操作触发 ensureCollection
            adapter.create(entity);
            // 第二次操作应复用缓存
            adapter.list(null, null, null, 0, 10);

            // findCollection 只调用一次
            verify(dataStoreManager, times(1)).findCollection(COLLECTION_NAME);
        }

        @Test
        void list触发集合不存在时创建_后续create复用() {
            模拟集合不存在需要创建();
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of());
            when(dataStoreManager.addDocument(eq(COLLECTION_ID), anyString(), isNull(), isNull()))
                    .thenReturn(DOCUMENT_ID);

            adapter.list(null, null, null, 0, 10);
            adapter.create(new TestEntity("测试", 1));

            verify(dataStoreManager, times(1)).findCollection(COLLECTION_NAME);
            verify(dataStoreManager, times(1)).createCollection(
                    eq(COLLECTION_NAME), eq(false),
                    isNull(), eq("测试集合"), eq("skill:test")
            );
        }
    }

    // ==================== 边界条件测试 ====================

    @Nested
    class 边界条件 {

        @Test
        void 实体包含特殊字符_正确序列化() {
            模拟集合已存在();
            var entity = new TestEntity("张三\"引号\\反斜杠", 0);
            when(dataStoreManager.addDocument(eq(COLLECTION_ID), anyString(), isNull(), isNull()))
                    .thenReturn(DOCUMENT_ID);

            ToolResult result = adapter.create(entity);

            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(dataStoreManager).addDocument(eq(COLLECTION_ID), jsonCaptor.capture(), isNull(), isNull());
            // 验证 JSON 中特殊字符被正确转义
            assertThat(jsonCaptor.getValue()).contains("\\\"");
            assertThat(jsonCaptor.getValue()).contains("\\\\");
        }

        @Test
        void 实体包含中文和Unicode_正确序列化和反序列化() {
            var entity = new TestEntity("测试中文名称🎉", 99);
            String json = assertDoesNotThrow(() -> objectMapper.writeValueAsString(entity));

            var doc = 创建文档(DOCUMENT_ID, json);
            when(dataStoreManager.getDocument(DOCUMENT_ID))
                    .thenReturn(Optional.of(doc));

            Optional<TestEntity> result = adapter.findById(DOCUMENT_ID);

            assertThat(result).isPresent();
            assertThat(result.get().name()).isEqualTo("测试中文名称🎉");
        }

        @Test
        void 实体age为零和负数() {
            模拟集合已存在();
            var entity = new TestEntity("边界", -1);
            when(dataStoreManager.addDocument(eq(COLLECTION_ID), anyString(), isNull(), isNull()))
                    .thenReturn(DOCUMENT_ID);

            ToolResult result = adapter.create(entity);

            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(dataStoreManager).addDocument(eq(COLLECTION_ID), jsonCaptor.capture(), isNull(), isNull());
            assertThat(jsonCaptor.getValue()).contains("\"age\":-1");
        }

        @Test
        void 空JSON对象_反序列化使用默认值() {
            // 空 JSON 对象：name 会是 null，age 会是 0
            var doc = 创建文档(DOCUMENT_ID, "{}");
            when(dataStoreManager.getDocument(DOCUMENT_ID))
                    .thenReturn(Optional.of(doc));

            Optional<TestEntity> result = adapter.findById(DOCUMENT_ID);

            assertThat(result).isPresent();
            assertThat(result.get().name()).isNull();
            assertThat(result.get().age()).isEqualTo(0);
        }

        @Test
        void list分页参数offset和limit为零() {
            模拟集合已存在();
            when(dataStoreManager.queryDocuments(any(QueryRequest.class)))
                    .thenReturn(List.of());

            adapter.list(null, null, null, 0, 0);

            ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(dataStoreManager).queryDocuments(captor.capture());
            assertThat(captor.getValue().offset()).isEqualTo(0);
            assertThat(captor.getValue().limit()).isEqualTo(0);
        }
    }
}
