package com.lifepilot.document.tool;

import com.lifepilot.document.parser.DocumentParserService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository.AttachmentRecord;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * DocumentParseToolExecutor 的单元测试。
 *
 * <p>覆盖场景：按 attachmentId 解析、按 path 解析、两者都未提供、附件不存在、maxChars 截断。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@ExtendWith(MockitoExtension.class)
class DocumentParseToolExecutor_附件解析测试 {

    @Mock AttachmentRepository attachmentRepository;

    @Test
    void 按_attachmentId_解析返回结构化内容(@TempDir Path tmp) throws IOException {
        Path md = tmp.resolve("note.md");
        Files.writeString(md, "# 测试标题\n\n这是正文。");

        when(attachmentRepository.findById("att-1")).thenReturn(new AttachmentRecord(
                "att-1", "session-1", "note.md", md.toString(),
                Files.size(md), "text/markdown", null));

        var executor = newExecutor();
        var input = newInput(Map.of("attachmentId", "att-1"));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsKeys("content", "fileName", "truncated");
        assertThat((String) result.data().get("content")).contains("测试标题");
        assertThat(result.data().get("fileName")).isEqualTo("note.md");
    }

    @Test
    void 按_path_解析也支持(@TempDir Path tmp) throws IOException {
        Path md = tmp.resolve("a.md");
        Files.writeString(md, "hello");

        var executor = newExecutor();
        var input = newInput(Map.of("path", md.toString()));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat((String) result.data().get("content")).contains("hello");
    }

    @Test
    void attachmentId_与_path_都未提供时报错() {
        var executor = newExecutor();
        var input = newInput(Map.of());

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("attachmentId");
    }

    @Test
    void attachmentId_不存在时报错() {
        when(attachmentRepository.findById("missing")).thenReturn(null);

        var executor = newExecutor();
        var input = newInput(Map.of("attachmentId", "missing"));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("附件不存在");
    }

    @Test
    void maxChars_截断生效(@TempDir Path tmp) throws IOException {
        Path md = tmp.resolve("long.md");
        Files.writeString(md, "a".repeat(1000));

        var executor = newExecutor();
        var input = newInput(Map.of("path", md.toString(), "maxChars", 100));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat((Boolean) result.data().get("truncated")).isTrue();
        assertThat(((String) result.data().get("content")).length()).isLessThanOrEqualTo(100 + 50);
    }

    private DocumentParseToolExecutor newExecutor() {
        var service = new DocumentParserService(List.of(new MarkdownParser()));
        return new DocumentParseToolExecutor(service, attachmentRepository, 30000);
    }

    private ToolInput newInput(Map<String, Object> params) {
        return new ToolInput("document.parse", params,
                JsonSchema.of(Map.of("type", "object")), null, null);
    }
}
