package com.lifepilot.interaction.web.controller;

import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DatastoreController(dataStoreManager)).build();
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

    private Collection collection(String id, String name, String description) {
        return new Collection(
                id,
                name,
                description,
                CollectionType.DOCUMENT,
                null,
                null,
                null,
                null,
                "2026-03-27T00:00:00Z",
                "2026-03-27T00:00:00Z"
        );
    }
}
