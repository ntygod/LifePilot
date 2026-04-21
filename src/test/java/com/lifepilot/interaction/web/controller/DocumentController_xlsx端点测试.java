package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.document.version.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * DocumentController xlsx 冒烟 —— 既有端点对 xlsx 文档应全部可用，无需代码改动。
 *
 * <p>P3B Task 11 追加：验证 元数据 / 下载 / diff 三端点在 xlsx 记录上的响应，
 * 保证 P3A 端点实现对 MIME 无耦合，后续 xlsx patch 流贯通。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocumentController_xlsx端点测试 {

    private SessionDocumentRepository documentRepo;
    private DocumentVersionRepository versionRepo;
    private DocumentVersionService versionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        documentRepo = Mockito.mock(SessionDocumentRepository.class);
        versionRepo = Mockito.mock(DocumentVersionRepository.class);
        versionService = Mockito.mock(DocumentVersionService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new DocumentController(documentRepo, versionRepo, versionService)).build();
    }

    @Test
    @DisplayName("GET /api/documents/{id} 对 xlsx 返回 spreadsheet mime")
    void 元数据端点返回xlsx_mime() throws Exception {
        when(documentRepo.findById(eq("x-1"))).thenReturn(new SessionDocumentRecord(
                "x-1", "s-1", null, "报表.xlsx", "/tmp/v1.xlsx", 1024L,
                DocumentVersionService.XLSX_MIME,
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE,
                "D:/src/报表.xlsx", 1, Instant.now()));

        var body = mvc.perform(get("/api/documents/x-1"))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = new ObjectMapper().readTree(body);
        assertThat(json.get("mimeType").asText()).isEqualTo(DocumentVersionService.XLSX_MIME);
        assertThat(json.get("fileName").asText()).isEqualTo("报表.xlsx");
        assertThat(json.get("latestVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/documents/{id}/download 对 xlsx 返回 spreadsheet Content-Type")
    void 下载端点返回xlsx内容类型() throws Exception {
        Path tmp = Files.createTempFile("xlsx-test-", ".xlsx");
        try {
            Files.write(tmp, new byte[]{1, 2, 3, 4});
            when(documentRepo.findById(eq("x-2"))).thenReturn(new SessionDocumentRecord(
                    "x-2", "s-2", null, "a.xlsx", tmp.toString(), 4L,
                    DocumentVersionService.XLSX_MIME,
                    SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, null, 0, Instant.now()));

            mvc.perform(get("/api/documents/x-2/download"))
                    .andExpect(r -> assertThat(r.getResponse().getContentType())
                            .startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("GET /api/documents/{id}/diff 对 xlsx 返回含 mime=xlsx 的 diffJson")
    void diff端点返回xlsx_mime字段() throws Exception {
        when(documentRepo.findById(eq("x-3"))).thenReturn(new SessionDocumentRecord(
                "x-3", "s-3", null, "a.xlsx", "/tmp/v1.xlsx", 1024L,
                DocumentVersionService.XLSX_MIME,
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, null, 1, Instant.now()));
        when(versionRepo.findByDocumentIdAndVersion(eq("x-3"), eq(1))).thenReturn(
                new DocumentVersionRecord("v-1", "x-3", 1, "/tmp/v1.xlsx",
                        DocumentVersionRecord.SOURCE_PATCH, "共 1 处修改",
                        "{\"mime\":\"xlsx\",\"summary\":\"共 1 处修改\"}", Instant.now()));

        var body = mvc.perform(get("/api/documents/x-3/diff?from=0&to=1"))
                .andReturn().getResponse().getContentAsString();
        // diffJson 在响应里是字符串字段，内部引号被 Jackson 转义；解析一层拿到原始 JSON 再断言
        JsonNode root = new ObjectMapper().readTree(body);
        String diffJson = root.get("diffJson").asText();
        assertThat(diffJson).contains("\"mime\":\"xlsx\"");
    }
}
