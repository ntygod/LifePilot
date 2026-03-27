package com.lifepilot.interaction.web.controller;

import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.datastore.sync.DatastoreKnowledgeBaseProvisioner;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import com.lifepilot.knowledge.repository.KnowledgeBaseDatastoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DatastoreController 单元测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
@ExtendWith(MockitoExtension.class)
class DatastoreControllerTest {

    @Mock
    private DataStoreManager dataStoreManager;
    @Mock
    private KnowledgeBaseManager knowledgeBaseManager;
    @Mock
    private KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository;
    @Mock
    private DatastoreKnowledgeBaseProvisioner datastoreKnowledgeBaseProvisioner;
    @Mock
    private DocumentIngester documentIngester;
    @Mock
    private KnowledgeBaseProperties knowledgeBaseProperties;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DatastoreController(
                dataStoreManager,
                knowledgeBaseManager,
                knowledgeBaseDatastoreRepository,
                datastoreKnowledgeBaseProvisioner,
                documentIngester,
                knowledgeBaseProperties
        )).build();
    }

    @Test
    void listDatastores_按关键字过滤_返回匹配结果() throws Exception {
        when(dataStoreManager.listCollections()).thenReturn(List.of(
                collection("ds-1", "知天命素材库", "小说世界观资料"),
                collection("ds-2", "学习卡片", "考试笔记")
        ));

        mockMvc.perform(get("/api/datastores").param("q", "素材"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("ds-1"))
                .andExpect(jsonPath("$[0].name").value("知天命素材库"));
    }

    @Test
    void getDatastore_存在时返回详情() throws Exception {
        when(dataStoreManager.getCollection("ds-1")).thenReturn(java.util.Optional.of(
                collection("ds-1", "知天命素材库", "小说世界观资料")
        ));

        mockMvc.perform(get("/api/datastores/ds-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ds-1"))
                .andExpect(jsonPath("$.name").value("知天命素材库"))
                .andExpect(jsonPath("$.type").value("DOCUMENT"));
    }

    @Test
    void getDatastore_不存在时返回404() throws Exception {
        when(dataStoreManager.getCollection("missing")).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/datastores/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Datastore 不存在: id=missing"));
    }

    @Test
    void deleteDatastore_存在时返回204() throws Exception {
        when(dataStoreManager.getCollection("ds-1")).thenReturn(java.util.Optional.of(
                collection("ds-1", "知天命素材库", "小说世界观资料")
        ));
        when(dataStoreManager.deleteCollection("ds-1")).thenReturn(true);

        mockMvc.perform(delete("/api/datastores/ds-1"))
                .andExpect(status().isNoContent());

        verify(dataStoreManager).deleteCollection("ds-1");
    }

    @Test
    void deleteDatastore_不存在时返回404() throws Exception {
        when(dataStoreManager.getCollection("missing")).thenReturn(java.util.Optional.empty());

        mockMvc.perform(delete("/api/datastores/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Datastore 不存在: id=missing"));
    }

    @Test
    void listDatastoreKnowledgeBases_返回关联知识库() throws Exception {
        when(dataStoreManager.getCollection("ds-1")).thenReturn(java.util.Optional.of(
                collection("ds-1", "知天命素材库", "小说世界观资料")
        ));
        when(knowledgeBaseDatastoreRepository.findKnowledgeBaseIdsByDatastoreId("ds-1"))
                .thenReturn(List.of("kb-1"));
        when(knowledgeBaseManager.getKnowledgeBase("kb-1"))
                .thenReturn(java.util.Optional.of(new KnowledgeBase(
                        "kb-1",
                        "内部资料库",
                        "系统自动创建",
                        "bge-m3",
                        null,
                        "smart",
                        Map.of(),
                        2,
                        16,
                        List.of("system"),
                        Instant.parse("2026-03-27T00:00:00Z"),
                        Instant.parse("2026-03-27T01:00:00Z"),
                        true,
                        "ds-1",
                        List.of("ds-1")
                )));

        mockMvc.perform(get("/api/datastores/ds-1/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("kb-1"))
                .andExpect(jsonPath("$[0].name").value("内部资料库"))
                .andExpect(jsonPath("$[0].systemManaged").value(true));
    }

    @Test
    void listDatastoreRecords_返回结构化记录() throws Exception {
        when(dataStoreManager.getCollection("ds-1")).thenReturn(java.util.Optional.of(
                collection("ds-1", "知天命素材库", "小说世界观资料")
        ));
        when(dataStoreManager.listDocuments("ds-1")).thenReturn(List.of(
                new com.lifepilot.datastore.model.Document(
                        "record-1",
                        "ds-1",
                        "{\"title\":\"林夜\"}",
                        null,
                        "2026-03-27T00:00:00Z",
                        "2026-03-27T00:30:00Z"
                )
        ));

        mockMvc.perform(get("/api/datastores/ds-1/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("record-1"))
                .andExpect(jsonPath("$[0].collectionId").value("ds-1"));
    }

    @Test
    void listDatastoreDomainDocuments_仅返回当前Datastore归属的文件文档() throws Exception {
        when(dataStoreManager.getCollection("ds-1")).thenReturn(java.util.Optional.of(
                collection("ds-1", "知天命素材库", "小说世界观资料", "kb-internal")
        ));
        when(knowledgeBaseManager.listDocuments("kb-internal")).thenReturn(List.of(
                new Document(
                        "doc-1",
                        "kb-internal",
                        "人物设定.md",
                        "/tmp/doc-1.md",
                        1024,
                        "text/markdown",
                        "hash-1",
                        DocumentStatus.READY,
                        8,
                        0,
                        null,
                        null,
                        Map.of(),
                        Instant.parse("2026-03-27T00:00:00Z"),
                        Instant.parse("2026-03-27T00:30:00Z"),
                        DocumentSourceType.FILE,
                        "FILE:doc-1",
                        "ds-1",
                        null,
                        Map.of()
                ),
                new Document(
                        "doc-2",
                        "kb-internal",
                        "别的领域.md",
                        "/tmp/doc-2.md",
                        1024,
                        "text/markdown",
                        "hash-2",
                        DocumentStatus.READY,
                        4,
                        0,
                        null,
                        null,
                        Map.of(),
                        Instant.parse("2026-03-27T00:00:00Z"),
                        Instant.parse("2026-03-27T00:30:00Z"),
                        DocumentSourceType.FILE,
                        "FILE:doc-2",
                        "ds-other",
                        null,
                        Map.of()
                ),
                new Document(
                        "doc-3",
                        "kb-internal",
                        "结构化投影",
                        "/tmp/doc-3.json",
                        1024,
                        "application/json",
                        "hash-3",
                        DocumentStatus.READY,
                        4,
                        0,
                        null,
                        null,
                        Map.of(),
                        Instant.parse("2026-03-27T00:00:00Z"),
                        Instant.parse("2026-03-27T00:30:00Z"),
                        DocumentSourceType.DATASTORE_DOCUMENT,
                        "DATASTORE:ds-1:doc-3",
                        "ds-1",
                        "collection-1",
                        Map.of()
                )
        ));

        mockMvc.perform(get("/api/datastores/ds-1/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("doc-1"))
                .andExpect(jsonPath("$[0].sourceDatastoreId").value("ds-1"));
    }

    @Test
    void uploadDatastoreDocument_提交到内部知识库导入链路() throws Exception {
        when(dataStoreManager.getCollection("ds-1")).thenReturn(java.util.Optional.of(
                collection("ds-1", "知天命素材库", "小说世界观资料", "kb-internal")
        ));
        when(knowledgeBaseProperties.maxFileSize()).thenReturn(10L * 1024 * 1024);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "人物设定.md",
                "text/markdown",
                "# 林夜".getBytes()
        );

        mockMvc.perform(multipart("/api/datastores/ds-1/documents").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.datastoreId").value("ds-1"))
                .andExpect(jsonPath("$.knowledgeBaseId").value("kb-internal"))
                .andExpect(jsonPath("$.fileName").value("人物设定.md"));

        verify(documentIngester).ingest(eq("kb-internal"), any(java.nio.file.Path.class), eq("人物设定.md"), eq("ds-1"));
    }

    private Collection collection(String id, String name, String description) {
        return collection(id, name, description, null);
    }

    private Collection collection(String id, String name, String description, String defaultKnowledgeBaseId) {
        return new Collection(
                id,
                name,
                description,
                CollectionType.DOCUMENT,
                null,
                null,
                null,
                defaultKnowledgeBaseId,
                null,
                "2026-03-27T00:00:00Z",
                "2026-03-27T00:00:00Z"
        );
    }
}
