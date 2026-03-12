package com.lifepilot.workflow.registry;

import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowStep;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRegistryBuiltinGuardTest {

    private WorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry(new WorkflowYamlParser());
    }

    @Test
    void performScan_localFileWithBuiltinStemOverridesBuiltin(@TempDir Path tempDir) throws IOException {
        registry.registerBuiltin(createDefinition("research-approval", "内置工作流"), "research-approval.yml");

        Files.writeString(tempDir.resolve("research-approval.yaml"), """
                id: research-approval
                name: 本地覆盖
                steps:
                  - id: step1
                    name: step
                    type: noop
                """);

        registry.performScan(tempDir);

        assertThat(registry.listAll()).hasSize(1);
        assertThat(registry.find("research-approval")).hasValueSatisfying(definition ->
                assertThat(definition.name()).isEqualTo("本地覆盖"));
    }

    @Test
    void performScan_duplicateLocalFilesPreferLatestModified(@TempDir Path tempDir) throws Exception {
        Path oldFile = tempDir.resolve("research-approval.yml");
        Path newFile = tempDir.resolve("research-approval.yaml");

        Files.writeString(oldFile, """
                id: research-approval
                name: 旧版本
                steps:
                  - id: step1
                    name: step
                    type: noop
                """);
        Thread.sleep(1100);
        Files.writeString(newFile, """
                id: research-approval
                name: 新版本
                steps:
                  - id: step1
                    name: step
                    type: noop
                """);

        registry.performScan(tempDir);

        assertThat(registry.find("research-approval")).hasValueSatisfying(definition ->
                assertThat(definition.name()).isEqualTo("新版本"));
    }

    private static WorkflowDefinition createDefinition(String id, String name) {
        return WorkflowDefinition.builder()
                .id(id)
                .name(name)
                .enabled(true)
                .steps(List.of(new WorkflowStep.NoopStep("step1", "测试步骤", List.of(), null)))
                .build();
    }
}
