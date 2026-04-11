package com.lifepilot.interaction.web.controller;

import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.sync.DatastoreKnowledgeBaseProvisioner;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import com.lifepilot.knowledge.repository.KnowledgeBaseDatastoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DatastoreController 创建/更新端点测试。
 *
 * @author zsg
 * @since 2026-04-11
 */
@ExtendWith(MockitoExtension.class)
class DatastoreController_管理端点测试 {

    @Mock private DataStoreManager dataStoreManager;
    @Mock private KnowledgeBaseManager knowledgeBaseManager;
    @Mock private KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository;
    @Mock private DatastoreKnowledgeBaseProvisioner datastoreKnowledgeBaseProvisioner;
    @Mock private DocumentIngester documentIngester;
    @Mock private KnowledgeBaseProperties knowledgeBaseProperties;

    private MockMvc mockMvc;

    @BeforeEach
    void 初始化() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DatastoreController(
                dataStoreManager,
                knowledgeBaseManager,
                knowledgeBaseDatastoreRepository,
                datastoreKnowledgeBaseProvisioner,
                documentIngester,
                knowledgeBaseProperties
        )).build();
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private static Collection 构建集合(String id, String name, CollectionType type) {
        return new Collection(id, name, "测试描述", type, null, null, null, null, null,
                "2026-04-11T00:00:00Z", "2026-04-11T00:00:00Z");
    }

    // ── POST /api/datastores ─────────────────────────────

    @Nested
    class 创建Datastore {

        @Test
        void 创建成功_返回201和集合数据() throws Exception {
            var created = 构建集合("ds-new", "素材库", CollectionType.DOCUMENT);
            when(dataStoreManager.createCollection(
                    eq("素材库"), eq(CollectionType.DOCUMENT), isNull(), eq("存放素材"), isNull(), isNull()))
                    .thenReturn(created);

            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "name": "素材库",
                                    "type": "DOCUMENT",
                                    "description": "存放素材"
                                }
                                """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value("ds-new"))
                    .andExpect(jsonPath("$.name").value("素材库"))
                    .andExpect(jsonPath("$.type").value("DOCUMENT"));

            verify(dataStoreManager).createCollection(
                    eq("素材库"), eq(CollectionType.DOCUMENT), isNull(), eq("存放素材"), isNull(), isNull());
        }

        @Test
        void 类型小写也能识别_返回201() throws Exception {
            var created = 构建集合("ds-note", "日记本", CollectionType.NOTE);
            when(dataStoreManager.createCollection(
                    eq("日记本"), eq(CollectionType.NOTE), isNull(), isNull(), isNull(), isNull()))
                    .thenReturn(created);

            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "name": "日记本", "type": "note" }
                                """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("NOTE"));
        }

        @Test
        void 名称为空_返回400() throws Exception {
            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "name": "", "type": "DOCUMENT" }
                                """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("Datastore 名称不能为空"));
        }

        @Test
        void 名称为null_返回400() throws Exception {
            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "type": "DOCUMENT" }
                                """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("Datastore 名称不能为空"));
        }

        @Test
        void 类型为空_返回400() throws Exception {
            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "name": "素材库", "type": "" }
                                """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("Datastore 类型不能为空"));
        }

        @Test
        void 类型为null_返回400() throws Exception {
            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "name": "素材库" }
                                """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("Datastore 类型不能为空"));
        }

        @Test
        void 类型非法_返回400() throws Exception {
            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "name": "素材库", "type": "UNKNOWN_TYPE" }
                                """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("不支持的 Datastore 类型: UNKNOWN_TYPE"));
        }

        @Test
        void 名称重复_DataStoreManager抛异常_返回400() throws Exception {
            when(dataStoreManager.createCollection(
                    eq("已存在"), eq(CollectionType.DOCUMENT), isNull(), isNull(), isNull(), isNull()))
                    .thenThrow(new IllegalArgumentException("集合名称已存在: 已存在"));

            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "name": "已存在", "type": "DOCUMENT" }
                                """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("集合名称已存在: 已存在"));
        }
    }

    // ── PUT /api/datastores/{id} ─────────────────────────

    @Nested
    class 更新Datastore {

        @Test
        void 更新成功_返回200和更新后数据() throws Exception {
            var existing = 构建集合("ds-1", "素材库", CollectionType.DOCUMENT);
            var updated = new Collection("ds-1", "素材库", "更新后的描述", CollectionType.DOCUMENT,
                    null, null, null, null, null,
                    "2026-04-11T00:00:00Z", "2026-04-11T01:00:00Z");

            when(dataStoreManager.getCollection("ds-1"))
                    .thenReturn(Optional.of(existing))
                    .thenReturn(Optional.of(updated));
            when(dataStoreManager.updateCollection(eq("ds-1"), eq("更新后的描述"), isNull(), isNull()))
                    .thenReturn(true);

            mockMvc.perform(put("/api/datastores/ds-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "description": "更新后的描述" }
                                """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("ds-1"))
                    .andExpect(jsonPath("$.description").value("更新后的描述"));

            verify(dataStoreManager).updateCollection(eq("ds-1"), eq("更新后的描述"), isNull(), isNull());
        }

        @Test
        void 更新不存在的Datastore_返回404() throws Exception {
            when(dataStoreManager.getCollection("missing")).thenReturn(Optional.empty());

            mockMvc.perform(put("/api/datastores/missing")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "description": "新描述" }
                                """))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("Datastore 不存在: id=missing"));
        }
    }
}
