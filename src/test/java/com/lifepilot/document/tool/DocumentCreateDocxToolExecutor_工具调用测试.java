package com.lifepilot.document.tool;

import com.lifepilot.document.generator.MarkdownToDocxGenerator;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentCreateDocxToolExecutor 工具调用测试。
 *
 * @author zsg
 * @since 2026-04-20
 */
@ExtendWith(MockitoExtension.class)
class DocumentCreateDocxToolExecutor_工具调用测试 {

    @Mock DocumentRepository documentRepository;
    @Mock AttachmentRepository attachmentRepository;

    @Test
    void 成功生成_docx_落盘入两张表返回_downloadUrl(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-1");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-1");

        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "报告",
                "markdown", "# 报告标题\n\n正文内容",
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("documentId", "doc-1");
        assertThat((String) result.data().get("fileName")).endsWith(".docx");
        assertThat((String) result.data().get("downloadUrl")).isEqualTo("/api/documents/doc-1/download");
        assertThat(result.data()).containsKey("fileSize");

        // 落盘文件真的存在
        ArgumentCaptor<DocumentRecord> recordCaptor = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(documentRepository).save(recordCaptor.capture());
        DocumentRecord saved = recordCaptor.getValue();
        assertThat(Files.exists(Path.of(saved.filePath()))).isTrue();
        assertThat(saved.origin()).isEqualTo("agent_generated");
        assertThat(saved.sessionId()).isEqualTo("sess-1");
        assertThat(saved.entryId()).isNull();  // Tool 执行时 entry_id 还没建
    }

    @Test
    void 缺少_fileName_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "markdown", "# 标题",
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("fileName");
    }

    @Test
    void 缺少_markdown_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "报告",
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("markdown");
    }

    @Test
    void 缺少_sessionId_上下文报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "报告",
                "markdown", "# 标题"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("sessionId");
    }

    @Test
    void fileName_自动追加_docx_扩展名(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-2");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-2");

        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "已带扩展名.docx",
                "markdown", "# 内容",
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        // 不重复加 .docx
        assertThat((String) result.data().get("fileName")).isEqualTo("已带扩展名.docx");
    }

    private DocumentCreateDocxToolExecutor newExecutor(Path storageDir) {
        return new DocumentCreateDocxToolExecutor(
                new MarkdownToDocxGenerator(),
                documentRepository,
                attachmentRepository,
                storageDir.toString());
    }

    private ToolInput newInput(Map<String, Object> params) {
        return new ToolInput("document.create_docx", params,
                JsonSchema.of(Map.of("type", "object")), null, null);
    }
}
