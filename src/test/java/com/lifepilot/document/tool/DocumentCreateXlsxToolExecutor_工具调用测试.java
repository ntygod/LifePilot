package com.lifepilot.document.tool;

import com.lifepilot.document.generator.StructuredDataToXlsxGenerator;
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
 * DocumentCreateXlsxToolExecutor 工具调用测试。
 *
 * @author zsg
 * @since 2026-04-20
 */
@ExtendWith(MockitoExtension.class)
class DocumentCreateXlsxToolExecutor_工具调用测试 {

    @Mock DocumentRepository documentRepository;
    @Mock AttachmentRepository attachmentRepository;

    @Test
    void 成功生成_xlsx_落盘入两张表返回_downloadUrl(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-x");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-x");

        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "销售",
                "sheets", List.of(
                        Map.of(
                                "name", "Q1",
                                "headers", List.of("产品", "销售额"),
                                "rows", List.of(List.of("A", 100))
                        )
                ),
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("documentId", "doc-x");
        assertThat((String) result.data().get("fileName")).endsWith(".xlsx");
        assertThat((String) result.data().get("downloadUrl")).isEqualTo("/api/documents/doc-x/download");

        ArgumentCaptor<DocumentRecord> capt = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(documentRepository).save(capt.capture());
        DocumentRecord saved = capt.getValue();
        assertThat(Files.exists(Path.of(saved.filePath()))).isTrue();
        assertThat(saved.origin()).isEqualTo("agent_generated");
        assertThat(saved.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void 缺少_fileName_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "sheets", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("fileName");
    }

    @Test
    void 缺少_sheets_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "报表",
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("sheets");
    }

    @Test
    void 缺少_sessionId_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "报表",
                "sheets", List.of()
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("sessionId");
    }

    @Test
    void fileName_含路径分隔符被拒绝(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "../etc/passwd",
                "sheets", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("非法字符");
    }

    @Test
    void fileName_自动追加_xlsx_扩展名(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-y");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-y");

        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "已带扩展名.xlsx",
                "sheets", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isTrue();
        assertThat((String) result.data().get("fileName")).isEqualTo("已带扩展名.xlsx");
    }

    @Test
    void 生成异常时返回_文档生成失败_错误(@TempDir Path tmp) {
        var failingGenerator = new com.lifepilot.document.generator.ExcelGenerator() {
            @Override
            public byte[] generate(List<com.lifepilot.document.generator.SheetData> sheets) {
                throw new com.lifepilot.document.generator.DocumentGenerationException(
                        "模拟 xlsx 生成失败");
            }

            @Override
            public String mimeType() {
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            }
        };
        var executor = new DocumentCreateXlsxToolExecutor(
                failingGenerator, documentRepository, attachmentRepository, tmp.toString());
        var input = newInput(Map.of(
                "fileName", "报表",
                "sheets", List.of(),
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("文档生成失败");
        assertThat(result.error()).contains("模拟 xlsx 生成失败");
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

        var executor = new DocumentCreateXlsxToolExecutor(
                new StructuredDataToXlsxGenerator(),
                documentRepository,
                attachmentRepository,
                notADir.toString());
        var input = newInput(Map.of(
                "fileName", "报表",
                "sheets", List.of(),
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("文档落盘失败");
    }

    private DocumentCreateXlsxToolExecutor newExecutor(Path storageDir) {
        return new DocumentCreateXlsxToolExecutor(
                new StructuredDataToXlsxGenerator(),
                documentRepository,
                attachmentRepository,
                storageDir.toString());
    }

    private ToolInput newInput(Map<String, Object> params) {
        return new ToolInput("document.create_xlsx", params,
                JsonSchema.of(Map.of("type", "object")), null, null);
    }
}
