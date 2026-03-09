package com.lifepilot.workflow.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowStep;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import com.lifepilot.workflow.parser.WorkflowYamlPrinter;
import com.lifepilot.workflow.repository.WorkflowRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkflowRegistry 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class WorkflowRegistryTest {

    private WorkflowRepository repository;
    private WorkflowYamlParser parser;
    private WorkflowYamlPrinter printer;
    private WorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowRepository.class);
        parser = new WorkflowYamlParser();
        printer = new WorkflowYamlPrinter();
        when(repository.findAllDefinitions()).thenReturn(List.of());
        registry = new WorkflowRegistry(repository, parser, printer);
    }


    // ==================== 辅助方法 ====================

    private WorkflowDefinition createDefinition(String id, String name, boolean enabled) {
        return WorkflowDefinition.builder()
                .id(id)
                .name(name)
                .enabled(enabled)
                .steps(List.of(new WorkflowStep.NoopStep("step1", "测试步骤", List.of(), null)))
                .build();
    }

    private WorkflowDefinition createDefinition(String id, String name) {
        return createDefinition(id, name, true);
    }

    // ==================== register 测试 ====================

    @Test
    void register_有效定义_注册成功() {
        WorkflowDefinition def = createDefinition("wf-1", "测试工作流");

        boolean result = registry.register(def);

        assertThat(result).isTrue();
        assertThat(registry.find("wf-1")).isPresent().hasValue(def);
        verify(repository).saveDefinition(eq(def), anyString());
    }

    @Test
    void register_缺少id_返回false() {
        // WorkflowDefinition 的紧凑构造器会对 null id 抛出 NPE，
        // 所以测试 blank id 的场景
        WorkflowDefinition def = WorkflowDefinition.builder()
                .id("  ")
                .name("测试")
                .steps(List.of(new WorkflowStep.NoopStep("s1", "步骤", List.of(), null)))
                .build();

        boolean result = registry.register(def);

        assertThat(result).isFalse();
        verify(repository, never()).saveDefinition(any(), anyString());
    }

    @Test
    void register_缺少name_返回false() {
        WorkflowDefinition def = WorkflowDefinition.builder()
                .id("wf-1")
                .name("  ")
                .steps(List.of(new WorkflowStep.NoopStep("s1", "步骤", List.of(), null)))
                .build();

        boolean result = registry.register(def);

        assertThat(result).isFalse();
        verify(repository, never()).saveDefinition(any(), anyString());
    }

    @Test
    void register_缺少steps_返回false() {
        WorkflowDefinition def = WorkflowDefinition.builder()
                .id("wf-1")
                .name("测试")
                .steps(List.of())
                .build();

        boolean result = registry.register(def);

        assertThat(result).isFalse();
        verify(repository, never()).saveDefinition(any(), anyString());
    }

    @Test
    void register_重复ID_更新已有定义() {
        WorkflowDefinition def1 = createDefinition("wf-1", "版本一");
        WorkflowDefinition def2 = createDefinition("wf-1", "版本二");

        registry.register(def1);
        boolean result = registry.register(def2);

        assertThat(result).isTrue();
        assertThat(registry.find("wf-1")).isPresent().hasValue(def2);
        assertThat(registry.listAll()).hasSize(1);
    }

    // ==================== unregister 测试 ====================

    @Test
    void unregister_已注册定义_移除成功() {
        registry.register(createDefinition("wf-1", "测试"));

        boolean result = registry.unregister("wf-1");

        assertThat(result).isTrue();
        assertThat(registry.find("wf-1")).isEmpty();
    }

    @Test
    void unregister_未注册定义_返回false() {
        boolean result = registry.unregister("not-exist");

        assertThat(result).isFalse();
    }

    // ==================== enable / disable 测试 ====================

    @Test
    void enable_已禁用定义_切换为启用() {
        registry.register(createDefinition("wf-1", "测试", false));

        boolean result = registry.enable("wf-1");

        assertThat(result).isTrue();
        assertThat(registry.find("wf-1")).isPresent()
                .hasValueSatisfying(def -> assertThat(def.enabled()).isTrue());
        verify(repository).updateDefinitionEnabled("wf-1", true);
    }

    @Test
    void disable_已启用定义_切换为禁用() {
        registry.register(createDefinition("wf-1", "测试", true));

        boolean result = registry.disable("wf-1");

        assertThat(result).isTrue();
        assertThat(registry.find("wf-1")).isPresent()
                .hasValueSatisfying(def -> assertThat(def.enabled()).isFalse());
        verify(repository).updateDefinitionEnabled("wf-1", false);
    }

    @Test
    void enable_未注册定义_返回false() {
        assertThat(registry.enable("not-exist")).isFalse();
    }

    @Test
    void disable_未注册定义_返回false() {
        assertThat(registry.disable("not-exist")).isFalse();
    }

    // ==================== find 测试 ====================

    @Test
    void find_已注册定义_返回定义() {
        WorkflowDefinition def = createDefinition("wf-1", "测试");
        registry.register(def);

        Optional<WorkflowDefinition> result = registry.find("wf-1");

        assertThat(result).isPresent().hasValue(def);
    }

    @Test
    void find_未注册定义_返回empty() {
        assertThat(registry.find("not-exist")).isEmpty();
    }

    // ==================== listAll / listEnabled 测试 ====================

    @Test
    void listAll_返回全部已注册定义() {
        registry.register(createDefinition("wf-1", "工作流一", true));
        registry.register(createDefinition("wf-2", "工作流二", false));
        registry.register(createDefinition("wf-3", "工作流三", true));

        List<WorkflowDefinition> all = registry.listAll();

        assertThat(all).hasSize(3);
        assertThat(all).extracting(WorkflowDefinition::id)
                .containsExactlyInAnyOrder("wf-1", "wf-2", "wf-3");
    }

    @Test
    void listEnabled_仅返回启用的定义() {
        registry.register(createDefinition("wf-1", "工作流一", true));
        registry.register(createDefinition("wf-2", "工作流二", false));
        registry.register(createDefinition("wf-3", "工作流三", true));

        List<WorkflowDefinition> enabled = registry.listEnabled();

        assertThat(enabled).hasSize(2);
        assertThat(enabled).extracting(WorkflowDefinition::id)
                .containsExactlyInAnyOrder("wf-1", "wf-3");
    }

    @Test
    void listAll_无注册定义_返回空列表() {
        assertThat(registry.listAll()).isEmpty();
    }

    @Test
    void listEnabled_无启用定义_返回空列表() {
        registry.register(createDefinition("wf-1", "工作流一", false));

        assertThat(registry.listEnabled()).isEmpty();
    }

    // ==================== performScan 测试 ====================

    @Test
    void performScan_扫描目录注册有效YAML(@TempDir Path tempDir) throws IOException {
        // 创建有效的 YAML 文件
        String yaml = """
                id: scan-wf
                name: 扫描测试工作流
                steps:
                  - id: step1
                    name: 步骤一
                    type: noop
                """;
        Files.writeString(tempDir.resolve("workflow.yml"), yaml);

        registry.performScan(tempDir);

        assertThat(registry.find("scan-wf")).isPresent();
    }

    @Test
    void performScan_跳过无效YAML文件(@TempDir Path tempDir) throws IOException {
        // 创建无效的 YAML 文件（缺少必填字段）
        Files.writeString(tempDir.resolve("invalid.yaml"), "invalid: true");

        registry.performScan(tempDir);

        // 不应有任何定义被注册
        assertThat(registry.listAll()).isEmpty();
    }

    @Test
    void performScan_忽略非YAML文件(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("readme.txt"), "not a workflow");

        registry.performScan(tempDir);

        assertThat(registry.listAll()).isEmpty();
    }

    @Test
    void performScan_目录不存在_不抛异常() {
        Path nonExistent = Path.of("/non/existent/directory");

        // 不应抛出异常
        registry.performScan(nonExistent);
        assertThat(registry.listAll()).isEmpty();
    }

    @Test
    void performScan_混合有效和无效文件(@TempDir Path tempDir) throws IOException {
        String validYaml = """
                id: valid-wf
                name: 有效工作流
                steps:
                  - id: s1
                    name: 步骤
                    type: noop
                """;
        Files.writeString(tempDir.resolve("valid.yml"), validYaml);
        Files.writeString(tempDir.resolve("invalid.yaml"), "broken: yaml: [");

        registry.performScan(tempDir);

        assertThat(registry.listAll()).hasSize(1);
        assertThat(registry.find("valid-wf")).isPresent();
    }

    // ==================== 启动时加载数据库 ====================

    @Test
    void 构造时从数据库加载已有定义() {
        WorkflowDefinition dbDef = createDefinition("db-wf", "数据库工作流");
        when(repository.findAllDefinitions()).thenReturn(List.of(dbDef));

        WorkflowRegistry newRegistry = new WorkflowRegistry(repository, parser, printer);

        assertThat(newRegistry.find("db-wf")).isPresent().hasValue(dbDef);
    }
}
