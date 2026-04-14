package com.lifepilot.interaction.web.controller;

import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.Collection;
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

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    private static Collection 构建集合(String id, String name, boolean timeSeries) {
        return new Collection(id, name, "测试描述", timeSeries, null, null, null,
                "2026-04-11T00:00:00Z", "2026-04-11T00:00:00Z");
    }

    // ── POST /api/datastores ─────────────────────────────

    @Nested
    class 创建Datastore {

        @Test
        void 创建成功_调用Manager并返回非错误状态() throws Exception {
            var created = 构建集合("ds-new", "素材库", false);
            when(dataStoreManager.createCollection(
                    eq("素材库"), eq(false), any(), eq("存放素材"), any()))
                    .thenReturn(created);

            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "name": "素材库", "type": "DOCUMENT", "description": "存放素材" }
                                """))
                    .andExpect(status().is2xxSuccessful());

            verify(dataStoreManager).createCollection(
                    eq("素材库"), eq(false), any(), eq("存放素材"), any());
        }

        @Test
        void 时序类型能识别() throws Exception {
            var created = 构建集合("ds-ts", "健康指标", true);
            when(dataStoreManager.createCollection(
                    eq("健康指标"), eq(true), any(), any(), any()))
                    .thenReturn(created);

            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "name": "健康指标", "type": "TIME_SERIES" }
                                """))
                    .andExpect(status().is2xxSuccessful());

            verify(dataStoreManager).createCollection(
                    eq("健康指标"), eq(true), any(), any(), any());
        }

        @Test
        void 名称为空_返回400() throws Exception {
            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "name": "", "type": "DOCUMENT" }
                                """))
                    .andExpect(status().isBadRequest());

            verify(dataStoreManager, never()).createCollection(any(), anyBoolean(), any(), any(), any());
        }

        @Test
        void 名称缺失_返回400() throws Exception {
            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "type": "DOCUMENT" }
                                """))
                    .andExpect(status().isBadRequest());

            verify(dataStoreManager, never()).createCollection(any(), anyBoolean(), any(), any(), any());
        }

        @Test
        void 名称重复_Manager抛异常_返回400() throws Exception {
            when(dataStoreManager.createCollection(
                    eq("已存在"), anyBoolean(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("集合名称已存在: 已存在"));

            mockMvc.perform(post("/api/datastores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "name": "已存在", "type": "DOCUMENT" }
                                """))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── PUT /api/datastores/{id} ─────────────────────────

    @Nested
    class 更新Datastore {

        @Test
        void 更新成功_调用updateCollection() throws Exception {
            var existing = 构建集合("ds-1", "素材库", false);
            when(dataStoreManager.getCollection("ds-1")).thenReturn(Optional.of(existing));

            mockMvc.perform(put("/api/datastores/ds-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "description": "更新后的描述" }
                                """))
                    .andExpect(status().is2xxSuccessful());

            verify(dataStoreManager).updateCollection(eq("ds-1"), eq("更新后的描述"), isNull());
        }

        @Test
        void 更新不存在的Datastore_返回404() throws Exception {
            when(dataStoreManager.getCollection("missing")).thenReturn(Optional.empty());

            mockMvc.perform(put("/api/datastores/missing")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                { "description": "新描述" }
                                """))
                    .andExpect(status().isNotFound());

            verify(dataStoreManager, never()).updateCollection(any(), any(), any());
        }
    }
}
