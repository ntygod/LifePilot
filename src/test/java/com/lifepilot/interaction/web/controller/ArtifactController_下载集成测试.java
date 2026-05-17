package com.lifepilot.interaction.web.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.conversation.artifact.SessionArtifactRepository;
import com.lifepilot.conversation.artifact.SessionArtifactRepository.SessionArtifactRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ArtifactController} 下载与元数据查询集成测试。
 *
 * <p>覆盖：200 下载 / 404 不存在 / 404 物理文件丢失 / 404 路径越界 /
 * 200 元数据查询 / Content-Disposition UTF-8 编码。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
@ExtendWith(MockitoExtension.class)
class ArtifactController_下载集成测试 {

    @TempDir
    Path workspaceRoot;

    @Mock
    private SessionArtifactRepository repository;

    private MockMvc mockMvc;
    private ZhiweiPaths zhiweiPaths;

    @BeforeEach
    void setUp() {
        zhiweiPaths = org.mockito.Mockito.mock(ZhiweiPaths.class);
        when(zhiweiPaths.workspace()).thenReturn(workspaceRoot);
        var controller = new ArtifactController(repository, zhiweiPaths);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("download：正常路径返回 200 + 文件字节 + Content-Disposition")
    void 下载正常返回200() throws Exception {
        Path docx = workspaceRoot.resolve("月度报告.docx");
        Files.writeString(docx, "Hello World");

        var row = new SessionArtifactRow(
                "art-1", "session-1", "entry-1", "trace-1",
                "file", "月度报告.docx", null, "ACTIVE",
                "{}", Instant.now(), Instant.now()
        );
        when(repository.findById("art-1")).thenReturn(Optional.of(row));
        when(repository.readPayload("art-1")).thenReturn(Map.of(
                "path", docx.toString(),
                "fileName", "月度报告.docx",
                "mimeType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "size", Files.size(docx)
        ));

        mockMvc.perform(get("/api/artifacts/art-1/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                .andExpect(content().bytes("Hello World".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("download：artifactId 不存在 → 404")
    void 下载id不存在返回404() throws Exception {
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/artifacts/ghost/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("download：物理文件丢失 → 404")
    void 下载文件丢失返回404() throws Exception {
        var row = new SessionArtifactRow(
                "art-2", "session-1", null, null,
                "file", "missing.docx", null, "ACTIVE",
                "{}", Instant.now(), Instant.now()
        );
        when(repository.findById("art-2")).thenReturn(Optional.of(row));
        Path missing = workspaceRoot.resolve("missing.docx");
        when(repository.readPayload("art-2")).thenReturn(Map.of(
                "path", missing.toString(),
                "fileName", "missing.docx",
                "mimeType", "application/octet-stream",
                "size", 0L
        ));

        mockMvc.perform(get("/api/artifacts/art-2/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("download：路径越界（不在 workspace 下）→ 404")
    void 下载路径越界返回404(@TempDir Path outsideWorkspace) throws Exception {
        Path outside = outsideWorkspace.resolve("escape.txt");
        Files.writeString(outside, "x");

        var row = new SessionArtifactRow(
                "art-3", "session-1", null, null,
                "file", "escape.txt", null, "ACTIVE",
                "{}", Instant.now(), Instant.now()
        );
        when(repository.findById("art-3")).thenReturn(Optional.of(row));
        when(repository.readPayload("art-3")).thenReturn(Map.of(
                "path", outside.toString(),
                "fileName", "escape.txt",
                "mimeType", "text/plain",
                "size", Files.size(outside)
        ));

        mockMvc.perform(get("/api/artifacts/art-3/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("metadata：返回 id/fileName/mimeType/size/kind/summary/createdAt 字段")
    void 元数据查询返回完整字段() throws Exception {
        Instant createdAt = Instant.parse("2026-05-17T12:00:00Z");
        var row = new SessionArtifactRow(
                "art-4", "session-1", null, null,
                "image", "chart.png", "月度趋势", "ACTIVE",
                "{}", createdAt, createdAt
        );
        when(repository.findById("art-4")).thenReturn(Optional.of(row));
        when(repository.readPayload("art-4")).thenReturn(Map.of(
                "path", "/some/path",
                "fileName", "chart.png",
                "mimeType", "image/png",
                "size", 1024L,
                "kind", "IMAGE"
        ));

        mockMvc.perform(get("/api/artifacts/art-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.id", is("art-4")))
                .andExpect(jsonPath("$.data.fileName", is("chart.png")))
                .andExpect(jsonPath("$.data.mimeType", is("image/png")))
                .andExpect(jsonPath("$.data.kind", is("IMAGE")))
                .andExpect(jsonPath("$.data.summary", is("月度趋势")))
                .andExpect(jsonPath("$.data.createdAt", notNullValue()));
    }

    @Test
    @DisplayName("metadata：artifactId 不存在 → 404")
    void 元数据id不存在返回404() throws Exception {
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/artifacts/ghost"))
                .andExpect(status().isNotFound());
    }
}
