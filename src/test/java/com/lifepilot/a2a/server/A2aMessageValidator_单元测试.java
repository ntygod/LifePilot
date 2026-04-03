package com.lifepilot.a2a.server;

import com.lifepilot.a2a.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2aMessageValidator 单元测试。
 *
 * @author zsg
 * @since 2026-04-03
 */
class A2aMessageValidator_单元测试 {

    // ── 顶层字段校验 ──

    @Test
    void null消息_返回错误描述() {
        assertThat(A2aMessageValidator.validate(null)).isNotNull().contains("消息体不能为空");
    }

    @Test
    void 空messageId_返回错误描述() {
        var message = new A2aMessage("", A2aRole.USER, List.of(new A2aPart.Text("hello", null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).isNotNull().contains("messageId");
    }

    @Test
    void null_messageId_返回错误描述() {
        var message = new A2aMessage(null, A2aRole.USER, List.of(new A2aPart.Text("hello", null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).isNotNull().contains("messageId");
    }

    @Test
    void 空role_返回错误描述() {
        var message = new A2aMessage("msg-1", null, List.of(new A2aPart.Text("hello", null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).isNotNull().contains("role");
    }

    @Test
    void 空parts列表_返回错误描述() {
        var message = new A2aMessage("msg-1", A2aRole.USER, List.of(), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).isNotNull().contains("parts");
    }

    @Test
    void 有效文本消息_返回null() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Text("hello", null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).isNull();
    }

    // ── Part 级别校验 ──

    @Test
    void Text部分内容为null_返回parts索引错误() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Text(null, null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).contains("parts[0]").contains("text");
    }

    @Test
    void Text部分内容为空白_返回错误() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Text("   ", null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).contains("parts[0]").contains("text");
    }

    @Test
    void File部分缺少file字段_返回错误() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.File(null, null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).contains("parts[0]").contains("file");
    }

    @Test
    void File部分缺少bytes和uri_返回错误() {
        var fileContent = new A2aFileContent("report.pdf", "application/pdf", null, null);
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.File(fileContent, null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).contains("parts[0]").contains("bytes 或 uri");
    }

    @Test
    void File部分有uri_校验通过() {
        var fileContent = new A2aFileContent("report.pdf", "application/pdf", null, "https://example.com/report.pdf");
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.File(fileContent, null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).isNull();
    }

    @Test
    void File部分有bytes_校验通过() {
        var fileContent = new A2aFileContent("data.txt", "text/plain", "aGVsbG8=", null);
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.File(fileContent, null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).isNull();
    }

    @Test
    void Data部分data为null_返回错误() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Data(null, null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).contains("parts[0]").contains("data");
    }

    @Test
    void Data部分data为空map_返回错误() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Data(Map.of(), null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).contains("parts[0]").contains("data");
    }

    @Test
    void Data部分data有内容_校验通过() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Data(Map.of("key", "value"), null)), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).isNull();
    }

    // ── 混合类型 ──

    @Test
    void 混合类型parts全部有效_返回null() {
        var fileContent = new A2aFileContent("img.png", "image/png", "iVBORw0KGgo=", null);
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(
                        new A2aPart.Text("请分析这张图片", null),
                        new A2aPart.File(fileContent, null),
                        new A2aPart.Data(Map.of("format", "png"), null)
                ), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).isNull();
    }

    @Test
    void 第二个part无效_返回正确索引() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(
                        new A2aPart.Text("hello", null),
                        new A2aPart.Text("  ", null)
                ), null, null, null);
        assertThat(A2aMessageValidator.validate(message)).contains("parts[1]");
    }
}
