package com.lifepilot.workflow.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lifepilot.workflow.parser.WorkflowYamlParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkflowRegistry YAML 文件热加载单元测试。
 *
 * <p>使用 {@code @TempDir} 模拟工作流定义目录，验证新增、修改、删除文件的检测逻辑。
 *
 * @author zsg
 * @since 2026-02-26
 */
class WorkflowRegistry热加载测试 {

    private WorkflowYamlParser parser;
    private WorkflowRegistry registry;

    @TempDir
    Path tempDir;

    /** 最小有效 YAML 工作流模板。 */
    private static final String YAML_TEMPLATE = """
            id: %s
            name: %s
            steps:
              - id: step1
                name: 测试步骤
                type: noop
            """;

    @BeforeEach
    void setUp() {
        parser = new WorkflowYamlParser();
        registry = new WorkflowRegistry(parser);
    }

    // ==================== 新增文件检测 ====================

    @Test
    void performScan_新增YAML文件_注册成功() throws IOException {
        // 写入一个新 YAML 文件
        Path yamlFile = tempDir.resolve("test-workflow.yml");
        Files.writeString(yamlFile, YAML_TEMPLATE.formatted("wf-new", "新增工作流"));

        // 执行扫描
        registry.performScan(tempDir);

        // 验证注册成功
        assertThat(registry.find("wf-new")).isPresent();
        assertThat(registry.find("wf-new").get().name()).isEqualTo("新增工作流");
    }

    // ==================== 修改文件检测 ====================

    @Test
    void performScan_修改YAML文件_更新定义() throws IOException, InterruptedException {
        // 首次扫描：注册初始文件
        Path yamlFile = tempDir.resolve("test-workflow.yaml");
        Files.writeString(yamlFile, YAML_TEMPLATE.formatted("wf-update", "原始名称"));
        registry.performScan(tempDir);
        assertThat(registry.find("wf-update").get().name()).isEqualTo("原始名称");

        // 等待确保文件修改时间不同（文件系统时间精度可能为秒级）
        Thread.sleep(1100);

        // 修改文件内容
        Files.writeString(yamlFile, YAML_TEMPLATE.formatted("wf-update", "更新后名称"));

        // 再次扫描
        registry.performScan(tempDir);

        // 验证定义已更新
        assertThat(registry.find("wf-update").get().name()).isEqualTo("更新后名称");
    }

    // ==================== 删除文件检测 ====================

    @Test
    void performScan_删除YAML文件_禁用定义() throws IOException {
        // 首次扫描：注册文件
        Path yamlFile = tempDir.resolve("to-delete.yml");
        Files.writeString(yamlFile, YAML_TEMPLATE.formatted("wf-delete", "待删除工作流"));
        registry.performScan(tempDir);
        assertThat(registry.find("wf-delete")).isPresent();
        assertThat(registry.find("wf-delete").get().enabled()).isTrue();

        // 删除文件
        Files.delete(yamlFile);

        // 再次扫描
        registry.performScan(tempDir);

        // 验证定义被禁用（不是删除）
        assertThat(registry.find("wf-delete")).isPresent();
        assertThat(registry.find("wf-delete").get().enabled()).isFalse();
    }

    // ==================== 无效 YAML 跳过 ====================

    @Test
    void performScan_无效YAML文件_跳过并不影响其他文件() throws IOException {
        // 写入一个有效文件和一个无效文件
        Files.writeString(tempDir.resolve("valid.yml"),
                YAML_TEMPLATE.formatted("wf-valid", "有效工作流"));
        Files.writeString(tempDir.resolve("invalid.yml"),
                "this is not valid yaml: [[[");

        // 执行扫描
        registry.performScan(tempDir);

        // 有效文件注册成功
        assertThat(registry.find("wf-valid")).isPresent();
        // 总注册数为 1（无效文件被跳过）
        assertThat(registry.listAll()).hasSize(1);
    }

    // ==================== 不存在的目录 ====================

    @Test
    void performScan_目录不存在_优雅处理() {
        Path nonExistent = tempDir.resolve("non-existent-dir");

        // 不应抛出异常
        registry.performScan(nonExistent);

        // 注册中心保持为空
        assertThat(registry.listAll()).isEmpty();
    }

    // ==================== resolveDefinitionsDir ====================

    @Test
    void resolveDefinitionsDir_波浪号路径_替换为用户主目录() {
        Path resolved = WorkflowRegistry.resolveDefinitionsDir("~/.zhiwei/workflows");
        String userHome = System.getProperty("user.home");
        assertThat(resolved.toString()).startsWith(userHome);
        assertThat(resolved.toString()).endsWith(".zhiwei" + resolved.getFileSystem().getSeparator() + "workflows");
    }

    @Test
    void resolveDefinitionsDir_绝对路径_原样返回() {
        String absolutePath = tempDir.resolve("workflows").toString();
        Path resolved = WorkflowRegistry.resolveDefinitionsDir(absolutePath);
        assertThat(resolved.toString()).isEqualTo(absolutePath);
    }

    // ==================== 多文件综合场景 ====================

    @Test
    void performScan_多文件综合_新增修改删除同时处理() throws IOException, InterruptedException {
        // 初始状态：两个文件
        Path fileA = tempDir.resolve("a.yml");
        Path fileB = tempDir.resolve("b.yml");
        Files.writeString(fileA, YAML_TEMPLATE.formatted("wf-a", "工作流A"));
        Files.writeString(fileB, YAML_TEMPLATE.formatted("wf-b", "工作流B"));
        registry.performScan(tempDir);
        assertThat(registry.listAll()).hasSize(2);

        // 等待确保文件修改时间不同
        Thread.sleep(1100);

        // 修改 A，删除 B，新增 C
        Files.writeString(fileA, YAML_TEMPLATE.formatted("wf-a", "工作流A-更新"));
        Files.delete(fileB);
        Files.writeString(tempDir.resolve("c.yml"),
                YAML_TEMPLATE.formatted("wf-c", "工作流C"));

        registry.performScan(tempDir);

        // A 已更新
        assertThat(registry.find("wf-a").get().name()).isEqualTo("工作流A-更新");
        // B 被禁用
        assertThat(registry.find("wf-b").get().enabled()).isFalse();
        // C 新增
        assertThat(registry.find("wf-c")).isPresent();
        assertThat(registry.find("wf-c").get().name()).isEqualTo("工作流C");
    }

    // ==================== 非 YAML 文件忽略 ====================

    @Test
    void performScan_非YAML文件_忽略() throws IOException {
        Files.writeString(tempDir.resolve("readme.txt"), "这不是 YAML 文件");
        Files.writeString(tempDir.resolve("config.json"), "{}");
        Files.writeString(tempDir.resolve("valid.yml"),
                YAML_TEMPLATE.formatted("wf-only", "唯一工作流"));

        registry.performScan(tempDir);

        assertThat(registry.listAll()).hasSize(1);
        assertThat(registry.find("wf-only")).isPresent();
    }
}
