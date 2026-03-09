package com.lifepilot.workflow.parser;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.WorkflowDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内置工作流 YAML 解析验证集成测试。
 *
 * <p>验证 {@code src/main/resources/builtin-workflows/} 目录下所有 YAML 文件
 * 均能被 {@link WorkflowYamlParser} 正确解析，确保内置模板不含语法或结构错误。
 *
 * @author zsg
 * @since 2026-03-09
 */
class BuiltinWorkflow_解析验证_集成测试 {

    private static final Path BUILTIN_DIR = Path.of("src/main/resources/builtin-workflows");

    /** 预期的内置工作流文件名列表 */
    private static final List<String> EXPECTED_FILES = List.of(
            "content-review.yml",
            "data-aggregation.yml",
            "batch-processing.yml",
            "scheduled-inspection.yml",
            "research-approval.yml"
    );

    private WorkflowYamlParser parser;

    @BeforeEach
    void setUp() {
        parser = new WorkflowYamlParser();
    }

    // ==================== 文件完整性验证 ====================

    @Test
    void 内置工作流目录_恰好包含5个YAML文件() throws IOException {
        List<String> yamlFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(BUILTIN_DIR, "*.yml")) {
            for (Path path : stream) {
                yamlFiles.add(path.getFileName().toString());
            }
        }
        assertEquals(5, yamlFiles.size(),
                "内置工作流目录应恰好包含 5 个 YAML 文件，实际: " + yamlFiles);
    }

    @Test
    void 所有预期文件_均存在于内置工作流目录() {
        for (String fileName : EXPECTED_FILES) {
            Path filePath = BUILTIN_DIR.resolve(fileName);
            assertTrue(Files.exists(filePath),
                    "预期文件不存在: " + fileName);
        }
    }

    // ==================== 逐文件解析验证 ====================

    /**
     * 提供所有内置工作流 YAML 文件名作为参数化测试数据源。
     */
    static Stream<Arguments> 内置工作流文件列表() {
        return EXPECTED_FILES.stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0} 解析成功")
    @MethodSource("内置工作流文件列表")
    void 内置工作流YAML_解析成功_无错误(String fileName) throws IOException {
        // 读取 YAML 文件内容
        Path filePath = BUILTIN_DIR.resolve(fileName);
        String yaml = Files.readString(filePath);

        // 解析
        Result<WorkflowDefinition, List<String>> result = parser.parse(yaml);

        // 断言解析成功
        if (result instanceof Result.Err<WorkflowDefinition, List<String>> err) {
            fail("解析 " + fileName + " 失败，错误: " + err.error());
        }

        // 提取定义并验证核心字段
        assertInstanceOf(Result.Ok.class, result);
        var ok = (Result.Ok<WorkflowDefinition, List<String>>) result;
        WorkflowDefinition definition = ok.value();

        assertNotNull(definition.id(),
                fileName + ": id 不能为空");
        assertNotNull(definition.name(),
                fileName + ": name 不能为空");
        assertFalse(definition.steps().isEmpty(),
                fileName + ": steps 列表不能为空");
    }

    @ParameterizedTest(name = "{0} 包含有效版本号")
    @MethodSource("内置工作流文件列表")
    void 内置工作流YAML_包含有效版本号(String fileName) throws IOException {
        Path filePath = BUILTIN_DIR.resolve(fileName);
        String yaml = Files.readString(filePath);

        var result = parser.parse(yaml);
        if (result instanceof Result.Ok<WorkflowDefinition, List<String>> ok) {
            assertNotNull(ok.value().version(),
                    fileName + ": version 不能为空");
        } else {
            fail("解析 " + fileName + " 失败");
        }
    }
}
