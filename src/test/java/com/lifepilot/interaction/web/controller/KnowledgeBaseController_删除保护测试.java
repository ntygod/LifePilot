package com.lifepilot.interaction.web.controller;

import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.KnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KnowledgeBaseController 删除保护测试 — 验证 systemManaged 知识库的删除拦截。
 *
 * @author zsg
 * @since 2026-04-11
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseController_删除保护测试 {

    @Mock private KnowledgeBaseManager kbManager;

    private MockMvc mockMvc;

    @BeforeEach
    void 初始化() {
        var properties = new KnowledgeBaseProperties(
                null, 1024, true,
                null, null, null, null, null, null, null, null);
        var controller = new KnowledgeBaseController(
                kbManager, properties, null, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private static KnowledgeBase 普通知识库(String id, String name) {
        return new KnowledgeBase(
                id, name, "测试描述", "bge-m3", null, "smart",
                Map.of(), 0, 0, List.of(),
                Instant.parse("2026-04-11T00:00:00Z"),
                Instant.parse("2026-04-11T00:00:00Z"),
                false, null, List.of());
    }

    private static KnowledgeBase 系统管理知识库(String id, String ownerDatastoreId) {
        return new KnowledgeBase(
                id, "内部资料库", "系统自动创建", "bge-m3", null, "smart",
                Map.of(), 0, 0, List.of("system", "datastore"),
                Instant.parse("2026-04-11T00:00:00Z"),
                Instant.parse("2026-04-11T00:00:00Z"),
                true, ownerDatastoreId, List.of(ownerDatastoreId));
    }

    // ── DELETE /api/knowledge-bases/{id} ──────────────────

    @Nested
    class 删除知识库 {

        @Test
        void 删除systemManaged知识库_返回403() throws Exception {
            when(kbManager.getKnowledgeBase("kb-sys")).thenReturn(
                    Optional.of(系统管理知识库("kb-sys", "ds-owner")));

            mockMvc.perform(delete("/api/knowledge-bases/kb-sys"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.message").value("该知识库由 Datastore 系统管理，请从对应的 Datastore 删除"));

            verify(kbManager, never()).deleteKnowledgeBase("kb-sys");
        }

        @Test
        void 删除不存在的知识库_返回404() throws Exception {
            when(kbManager.getKnowledgeBase("kb-missing")).thenReturn(Optional.empty());

            mockMvc.perform(delete("/api/knowledge-bases/kb-missing"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("知识库不存在: id=kb-missing"));

            verify(kbManager, never()).deleteKnowledgeBase("kb-missing");
        }

        @Test
        void 删除普通知识库_返回204() throws Exception {
            when(kbManager.getKnowledgeBase("kb-normal")).thenReturn(
                    Optional.of(普通知识库("kb-normal", "我的笔记库")));

            mockMvc.perform(delete("/api/knowledge-bases/kb-normal"))
                    .andExpect(status().isNoContent());

            verify(kbManager).deleteKnowledgeBase("kb-normal");
        }
    }
}
