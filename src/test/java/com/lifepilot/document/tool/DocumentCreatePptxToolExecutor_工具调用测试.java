package com.lifepilot.document.tool;

import com.lifepilot.document.generator.OutlineToPptxGenerator;
import com.lifepilot.document.model.DocumentRecord;
import com.lifepilot.document.repository.DocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentCreatePptxToolExecutor 工具调用测试。
 *
 * @author zsg
 * @since 2026-04-20
 */
@ExtendWith(MockitoExtension.class)
class DocumentCreatePptxToolExecutor_工具调用测试 {

    @Mock DocumentRepository documentRepository;
    @Mock AttachmentRepository attachmentRepository;

    @Test
    void 成功生成_pptx_落盘入两张表返回_downloadUrl(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-p");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-p");

        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "方案",
                "slides", List.of(
                        Map.of(
                                "title", "首页",
                                "bullets", List.of("要点 A", "要点 B"),
                                "notes", "讲稿"
                        )
                ),
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("documentId", "doc-p");
        assertThat((String) result.data().get("fileName")).endsWith(".pptx");
        assertThat((String) result.data().get("downloadUrl")).isEqualTo("/api/documents/doc-p/download");

        ArgumentCaptor<DocumentRecord> capt = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(documentRepository).save(capt.capture());
        DocumentRecord saved = capt.getValue();
        assertThat(Files.exists(Path.of(saved.filePath()))).isTrue();
        assertThat(saved.origin()).isEqualTo("agent_generated");
        assertThat(saved.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    @Test
    void 缺少_fileName_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "slides", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("fileName");
    }

    @Test
    void 缺少_slides_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "方案",
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("slides");
    }

    @Test
    void 缺少_sessionId_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "方案",
                "slides", List.of()
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("sessionId");
    }

    @Test
    void fileName_含路径分隔符被拒绝(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "../etc/passwd",
                "slides", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("非法字符");
    }

    @Test
    void fileName_自动追加_pptx_扩展名(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-q");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-q");

        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "已带扩展名.pptx",
                "slides", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isTrue();
        assertThat((String) result.data().get("fileName")).isEqualTo("已带扩展名.pptx");
    }

    @Test
    void 生成异常时返回_文档生成失败_错误(@TempDir Path tmp) {
        var failingGenerator = new com.lifepilot.document.generator.PowerpointGenerator() {
            @Override
            public byte[] generate(List<com.lifepilot.document.generator.SlideData> slides) {
                throw new com.lifepilot.document.generator.DocumentGenerationException(
                        "模拟 pptx 生成失败");
            }

            @Override
            public String mimeType() {
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            }
        };
        var executor = new DocumentCreatePptxToolExecutor(
                failingGenerator, documentRepository, attachmentRepository, tmp.toString());
        var input = newInput(Map.of(
                "fileName", "方案",
                "slides", List.of(),
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("文档生成失败");
        assertThat(result.error()).contains("模拟 pptx 生成失败");
    }

    @Test
    void 落盘失败时返回_文档落盘失败_错误(@TempDir Path tmp) {
        // storageDir 指向一个"已存在但是普通文件"的路径 —— createDirectories 会抛 IOException
        Path notADir = tmp.resolve("file-not-dir");
        try {
            Files.writeString(notADir, "blocker");
        } catch (java.io.IOException e) {
            org.junit.jupiter.api.Assertions.fail("准备 fixture 失败", e);
        }

        var executor = new DocumentCreatePptxToolExecutor(
                new OutlineToPptxGenerator(),
                documentRepository,
                attachmentRepository,
                notADir.toString());
        var input = newInput(Map.of(
                "fileName", "方案",
                "slides", List.of(),
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("文档落盘失败");
    }

    private DocumentCreatePptxToolExecutor newExecutor(Path storageDir) {
        return new DocumentCreatePptxToolExecutor(
                new OutlineToPptxGenerator(),
                documentRepository,
                attachmentRepository,
                storageDir.toString());
    }

    private ToolInput newInput(Map<String, Object> params) {
        return new ToolInput("document.create_pptx", params,
                JsonSchema.of(Map.of("type", "object")), null, null);
    }
}
