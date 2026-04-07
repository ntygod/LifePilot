package com.lifepilot.interaction.web.controller;

import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KnowledgeBaseController 上传测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseControllerUploadTest {

    @Mock
    private KnowledgeBaseManager knowledgeBaseManager;

    @Mock
    private DocumentIngester documentIngester;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var knowledgeBaseProperties = new KnowledgeBaseProperties(
                null,
                1024,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        var controller = new KnowledgeBaseController(
                knowledgeBaseManager,
                knowledgeBaseProperties,
                documentIngester,
                null,
                null
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void uploadDocument_文件超限_返回400且不进入导入流程() throws Exception {
        var oversizedFile = new MockMultipartFile(
                "file",
                "novel.md",
                "text/markdown",
                new byte[1025]
        );

        mockMvc.perform(multipart("/api/knowledge-bases/kb-1/documents").file(oversizedFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("文件大小超过限制（最大 1MB）"));

        verifyNoInteractions(knowledgeBaseManager, documentIngester);
    }

    @Test
    void uploadDocument_合法文件_返回202并提交异步处理() throws Exception {
        var file = new MockMultipartFile(
                "file",
                "outline.md",
                "text/markdown",
                "# 大纲".getBytes()
        );

        mockMvc.perform(multipart("/api/knowledge-bases/kb-1/documents")
                        .file(file)
                        .param("datastoreId", " ds-1 "))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("文档已提交处理"))
                .andExpect(jsonPath("$.fileName").value("outline.md"))
                .andExpect(jsonPath("$.datastoreId").value("ds-1"));

        verify(knowledgeBaseManager).ensureDatastoreAssociation("kb-1", "ds-1");
        verify(documentIngester).ingest(eq("kb-1"), any(), eq("outline.md"), eq("ds-1"));
    }

    @Test
    void updateDocumentDatastore_支持设置和清空归属() throws Exception {
        var now = Instant.parse("2026-03-27T00:00:00Z");
        var updated = new Document(
                "doc-1",
                "kb-1",
                "outline.md",
                "/tmp/outline.md",
                123,
                "text/markdown",
                "hash",
                DocumentStatus.READY,
                3,
                0,
                null,
                null,
                Map.of(),
                now,
                now,
                DocumentSourceType.FILE,
                "FILE:doc-1",
                "ds-story",
                null,
                Map.of("datastoreId", "ds-story")
        );
        var cleared = new Document(
                "doc-1",
                "kb-1",
                "outline.md",
                "/tmp/outline.md",
                123,
                "text/markdown",
                "hash",
                DocumentStatus.READY,
                3,
                0,
                null,
                null,
                Map.of(),
                now,
                now,
                DocumentSourceType.FILE,
                "FILE:doc-1",
                null,
                null,
                Map.of()
        );

        when(knowledgeBaseManager.updateDocumentDatastore("kb-1", "doc-1", "ds-story")).thenReturn(updated);
        when(knowledgeBaseManager.updateDocumentDatastore("kb-1", "doc-1", null)).thenReturn(cleared);

        mockMvc.perform(patch("/api/knowledge-bases/kb-1/documents/doc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"datastoreId":" ds-story "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("doc-1"))
                .andExpect(jsonPath("$.sourceDatastoreId").value("ds-story"));

        mockMvc.perform(patch("/api/knowledge-bases/kb-1/documents/doc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"datastoreId":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("doc-1"))
                .andExpect(jsonPath("$.sourceDatastoreId").value(nullValue()));

        verify(knowledgeBaseManager).updateDocumentDatastore("kb-1", "doc-1", "ds-story");
        verify(knowledgeBaseManager).updateDocumentDatastore("kb-1", "doc-1", null);
    }
}
