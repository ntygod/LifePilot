package com.lifepilot.tool.validation;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ToolValidator_启动校验测试 {

    private final ToolValidator validator = new ToolValidator();

    @Test
    void ID格式不合法_应抛异常() {
        BuiltinTool tool = baseBuilder().id("BadId").build();
        assertThatThrownBy(() -> validator.validate(tool))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ID 格式不合法");
    }

    @Test
    void 缺少动词或namespace白名单_应抛异常() {
        BuiltinTool tool = baseBuilder().id("foo.bar").build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("ID 自描述度");
    }

    @Test
    void name不是中文_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("Read File")
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("name 必须是中文");
    }

    @Test
    void description长度不足40字符_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("读取文件")
                .description("Short")
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("description 长度不足");
    }

    @Test
    void description含中文_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("读取文件")
                .description("Read a file from path and 返回 content to caller")
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("description 必须英文");
    }

    @Test
    void tags少于3个_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("读取文件")
                .description("Read a file content from the specified path")
                .tags(List.of("read", "file"))
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("tags 数量不足");
    }

    @Test
    void tags重复_应抛异常() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("读取文件")
                .description("Read a file content from the specified path")
                .tags(List.of("read", "read", "file"))
                .build();
        assertThatThrownBy(() -> validator.validate(tool))
                .hasMessageContaining("tags 有重复");
    }

    @Test
    void 合法工具_应通过() {
        BuiltinTool tool = baseBuilder()
                .id("file.read")
                .name("读取文件")
                .description("Read a file content from the specified path as text or binary")
                .tags(List.of("read", "file", "content", "load", "fetch"))
                .build();
        assertThatCode(() -> validator.validate(tool)).doesNotThrowAnyException();
    }

    private BuiltinTool.Builder baseBuilder() {
        return BuiltinTool.builder()
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .category(ToolCategory.ACTION)
                .executor(input -> null);
    }
}
