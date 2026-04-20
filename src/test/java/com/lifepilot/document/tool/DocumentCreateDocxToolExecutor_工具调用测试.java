package com.lifepilot.document.tool;

import com.lifepilot.document.generator.MarkdownToDocxGenerator;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.repository.SessionDocumentRepository;
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

    @Mock SessionDocumentRepository sessionDocumentRepository;
    @Mock AttachmentRepository attachmentRepository;

    @Test
    void 成功生成_docx_落盘入两张表返回_downloadUrl(@TempDir Path tmp) {
        when(sessionDocumentRepository.save(any())).thenReturn("doc-1");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-1");

        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "报告",
                "markdown", "# 报告标题\n\n正文内容"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("documentId", "doc-1");
        assertThat((String) result.data().get("fileName")).endsWith(".docx");
        assertThat((String) result.data().get("downloadUrl")).isEqualTo("/api/documents/doc-1/download");
        assertThat(result.data()).containsKey("fileSize");

        // 落盘文件真的存在
        ArgumentCaptor<SessionDocumentRecord> recordCaptor = ArgumentCaptor.forClass(SessionDocumentRecord.class);
        verify(sessionDocumentRepository).save(recordCaptor.capture());
        SessionDocumentRecord saved = recordCaptor.getValue();
        assertThat(Files.exists(Path.of(saved.filePath()))).isTrue();
        assertThat(saved.origin()).isEqualTo("agent_generated");
        assertThat(saved.sessionId()).isEqualTo("sess-1");
        assertThat(saved.entryId()).isNull();  // Tool 执行时 entry_id 还没建
    }

    @Test
    void 缺少_fileName_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "markdown", "# 标题"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("fileName");
    }

    @Test
    void 缺少_markdown_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "报告"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("markdown");
    }

    @Test
    void 缺少_执行上下文_sessionId_报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        // 显式传空 context, 模拟 runtime 漏注入 sessionId 的场景
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "报告",
                "markdown", "# 标题"
        ), Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("缺少执行上下文");
    }

    @Test
    void fileName_自动追加_docx_扩展名(@TempDir Path tmp) {
        when(sessionDocumentRepository.save(any())).thenReturn("doc-2");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-2");

        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "已带扩展名.docx",
                "markdown", "# 内容"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        // 不重复加 .docx
        assertThat((String) result.data().get("fileName")).isEqualTo("已带扩展名.docx");
    }

    @Test
    void fileName_含路径分隔符被拒绝(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "../etc/passwd",
                "markdown", "# 标题"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("非法字符");
    }

    @Test
    void fileName_含正斜杠子目录被拒绝(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "2026/Q3/report",
                "markdown", "# 标题"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("非法字符");
    }

    @Test
    void 生成异常时返回_文档生成失败_错误(@TempDir Path tmp) {
        var failingGenerator = new com.lifepilot.document.generator.DocumentGenerator() {
            @Override
            public byte[] generate(String markdown) {
                throw new com.lifepilot.document.generator.DocumentGenerationException(
                        "模拟生成失败");
            }

            @Override
            public String mimeType() {
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }
        };
        var executor = new DocumentCreateDocxToolExecutor(
                failingGenerator, sessionDocumentRepository, attachmentRepository, tmp.toString());
        var input = newInput(Map.of(
                "fileName", "报告",
                "markdown", "# 标题"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("文档生成失败");
        assertThat(result.error()).contains("模拟生成失败");
    }

    @Test
    void 落盘失败时返回_文档落盘失败_错误(@TempDir Path tmp) {
        // storageDir 指向一个"已存在但是普通文件"的路径 —— createDirectories 会抛 IOException
        Path notADir = tmp.resolve("file-not-dir");
        try {
            java.nio.file.Files.writeString(notADir, "blocker");
        } catch (java.io.IOException e) {
            org.junit.jupiter.api.Assertions.fail("准备 fixture 失败", e);
        }

        var executor = new DocumentCreateDocxToolExecutor(
                new MarkdownToDocxGenerator(),
                sessionDocumentRepository,
                attachmentRepository,
                notADir.toString());  // storageDir 指向普通文件
        var input = newInput(Map.of(
                "fileName", "报告",
                "markdown", "# 标题"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("文档落盘失败");
    }

    private DocumentCreateDocxToolExecutor newExecutor(Path storageDir) {
        return new DocumentCreateDocxToolExecutor(
                new MarkdownToDocxGenerator(),
                sessionDocumentRepository,
                attachmentRepository,
                storageDir.toString());
    }

    // 默认注入 sess-1 context, 符合 runtime 行为
    private ToolInput newInput(Map<String, Object> params) {
        return newInput(params, Map.of("sessionId", "sess-1"));
    }

    private ToolInput newInput(Map<String, Object> params, Map<String, Object> context) {
        return new ToolInput("document.create_docx", params,
                JsonSchema.of(Map.of("type", "object")), null, context);
    }
}
