package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.patch.DocumentPatchResult;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.document.version.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DocumentController P3 端点测试 —— Mockito + MockMvc。
 *
 * <p>覆盖 Task 13 新增的 5 个端点：元数据 / 版本列表 / commit overwrite / rollback / 丢弃工作副本，
 * 以及元数据 404 的典型失败路径。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
@ExtendWith(MockitoExtension.class)
class DocumentController_P3端点测试 {

    @Mock SessionDocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock DocumentVersionService versionService;

    @InjectMocks DocumentController controller;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/documents?sessionId=... 列当前会话的工作副本（P1-6）")
    void 列会话工作副本() throws Exception {
        var working = new SessionDocumentRecord(
                "d-w", "s-1", null, "w.docx", "/p/w", 200L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, "D:/w.docx", 3, Instant.now());
        var untouched = new SessionDocumentRecord(
                "d-u", "s-1", null, "u.docx", "/p/u", 100L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, "D:/u.docx", 0, Instant.now());
        when(documentRepository.findBySessionId("s-1"))
                .thenReturn(java.util.List.of(working, untouched));

        mockMvc.perform(get("/api/documents?sessionId=s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))   // status=working 默认只返回 latestVersion>0
                .andExpect(jsonPath("$.data[0].id").value("d-w"))
                .andExpect(jsonPath("$.data[0].latestVersion").value(3));

        mockMvc.perform(get("/api/documents?sessionId=s-1&status=all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/documents/{id} 返回元数据")
    void getMetadata_成功() throws Exception {
        when(documentRepository.findById("d1")).thenReturn(new SessionDocumentRecord(
                "d1", "s1", null, "x.docx", "/p/x", 100L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, "D:/x.docx", 2, Instant.now()));

        mockMvc.perform(get("/api/documents/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("d1"))
                .andExpect(jsonPath("$.data.latestVersion").value(2))
                .andExpect(jsonPath("$.data.sourcePath").value("D:/x.docx"));
    }

    @Test
    @DisplayName("GET /api/documents/{id} 不存在返回 404")
    void getMetadata_不存在() throws Exception {
        when(documentRepository.findById("miss")).thenReturn(null);

        mockMvc.perform(get("/api/documents/miss"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /versions 分页返回 items + total + page + pageSize")
    void 列版本列表() throws Exception {
        when(documentRepository.findById("d1")).thenReturn(new SessionDocumentRecord(
                "d1", "s1", null, "x.docx", "/p/x", 1L, "a",
                SessionDocumentRecord.ORIGIN_AGENT_GENERATED, null, 1, Instant.now()));
        when(versionService.listVersions("d1", 1, 20)).thenReturn(
                new DocumentVersionService.VersionPage(List.of(
                        new DocumentVersionRecord("v1", "d1", 0, "/p/v0",
                                DocumentVersionRecord.SOURCE_INITIAL, null, null, Instant.now()),
                        new DocumentVersionRecord("v2", "d1", 1, "/p/v1",
                                DocumentVersionRecord.SOURCE_PATCH, "共 1 处", "{}", Instant.now())
                ), 2, 1, 20));

        mockMvc.perform(get("/api/documents/d1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].versionNo").value(0))
                .andExpect(jsonPath("$.data.items[0].source").value("initial"))
                .andExpect(jsonPath("$.data.items[1].versionNo").value(1))
                .andExpect(jsonPath("$.data.items[1].patchSummary").value("共 1 处"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20));
    }

    @Test
    @DisplayName("POST /commit overwrite 调用 service.commitOverwrite")
    void commit_overwrite() throws Exception {
        when(versionService.commitOverwrite("d1")).thenReturn(
                new DocumentVersionService.CommitResult("D:/x.docx", "D:/x.docx.20260421.bak"));

        mockMvc.perform(post("/api/documents/d1/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("target", "overwrite"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.committedPath").value("D:/x.docx"))
                .andExpect(jsonPath("$.data.backupPath").exists());
    }

    @Test
    @DisplayName("POST /rollback 调用 service.rollback 返回新版本号")
    void rollback成功() throws Exception {
        when(versionService.rollback(eq("d1"), eq(0))).thenReturn(
                DocumentPatchResult.success(2, null, "回滚到版本 0"));

        mockMvc.perform(post("/api/documents/d1/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("version", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.newVersion").value(2))
                .andExpect(jsonPath("$.data.summary").value("回滚到版本 0"));
    }

    @Test
    @DisplayName("DELETE /working-copy 调用 service.discard 返回 204")
    void discard成功() throws Exception {
        mockMvc.perform(delete("/api/documents/d1/working-copy"))
                .andExpect(status().isNoContent());

        verify(versionService).discard("d1");
    }
}
