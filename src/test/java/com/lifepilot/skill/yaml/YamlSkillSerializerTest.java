package com.lifepilot.skill.yaml;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link YamlSkillSerializer} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class YamlSkillSerializerTest {

    @TempDir
    Path tempDir;

    private YamlSkillSerializer serializer;
    private YamlSkillLoader loader;
    private SkillConfigProperties config;
    private SkillRegistry skillRegistry;

    @BeforeEach
    void setUp() {
        serializer = new YamlSkillSerializer();
        config = new SkillConfigProperties();
        config.setDirectory(tempDir.toString());
        var schemaValidator = new YamlSchemaValidator(config);
        skillRegistry = mock(SkillRegistry.class);
        when(skillRegistry.register(any())).thenReturn(true);
        loader = new YamlSkillLoader(schemaValidator, skillRegistry, config, tempDir);
    }

    // ─────────────────────────────────────────────
    //  序列化完整字段
    // ─────────────────────────────────────────────

    @Test
    void serialize_包含所有字段时生成合法YAML() {
        var definition = buildFullDefinition();

        String yaml = serializer.serialize(definition);

        // 验证输出为合法 YAML
        var parsed = new Yaml().load(yaml);
        assertThat(parsed).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) parsed;
        assertThat(root).containsKey("skill");

        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) root.get("skill");
        assertThat(skill.get("id")).isEqualTo("exchange-rate-query");
        assertThat(skill.get("name")).isEqualTo("汇率查询");
        assertThat(skill.get("description")).isEqualTo("查询实时汇率");
        assertThat(skill.get("version")).isEqualTo("1.0.0");
        assertThat(skill.get("system-prompt")).isEqualTo("你是一个汇率查询助手\n");
        assertThat(skill.get("allowed-tools")).isEqualTo(List.of("http-request"));
        assertThat(skill).containsKey("execution");
        assertThat(skill).containsKey("budget");
        assertThat(skill).containsKey("memory-access");
        assertThat(skill).containsKey("metadata");
    }

    // ─────────────────────────────────────────────
    //  默认值省略
    // ─────────────────────────────────────────────

    @Test
    void serialize_execution等于DEFAULT时省略() {
        var definition = buildMinimalDefinition();

        String yaml = serializer.serialize(definition);

        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) new Yaml().load(yaml);
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) root.get("skill");
        assertThat(skill).doesNotContainKey("execution");
    }

    @Test
    void serialize_budget等于DEFAULT时省略() {
        var definition = buildMinimalDefinition();

        String yaml = serializer.serialize(definition);

        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) new Yaml().load(yaml);
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) root.get("skill");
        assertThat(skill).doesNotContainKey("budget");
    }

    @Test
    void serialize_memoryAccess为空时省略() {
        var definition = buildMinimalDefinition();

        String yaml = serializer.serialize(definition);

        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) new Yaml().load(yaml);
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) root.get("skill");
        assertThat(skill).doesNotContainKey("memory-access");
    }

    @Test
    void serialize_metadata为空时省略() {
        var definition = buildMinimalDefinition();

        String yaml = serializer.serialize(definition);

        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) new Yaml().load(yaml);
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) root.get("skill");
        assertThat(skill).doesNotContainKey("metadata");
    }

    // ─────────────────────────────────────────────
    //  version 始终包含
    // ─────────────────────────────────────────────

    @Test
    void serialize_始终包含version字段() {
        var definition = buildMinimalDefinition();

        String yaml = serializer.serialize(definition);

        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) new Yaml().load(yaml);
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) root.get("skill");
        assertThat(skill.get("version")).isEqualTo("2.0.0");
    }

    // ─────────────────────────────────────────────
    //  多行 system-prompt
    // ─────────────────────────────────────────────

    @Test
    void serialize_多行systemPrompt正确处理() {
        var definition = SkillDefinition.builder()
                .id("multi-line-skill")
                .name("多行测试")
                .description("测试多行 system-prompt")
                .version("1.0.0")
                .source(new SkillSource.UserDefined("test.yml"))
                .systemPrompt("第一行\n第二行\n第三行")
                .allowedTools(List.of("tool-a"))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.DEFAULT)
                .metadata(Map.of())
                .build();

        String yaml = serializer.serialize(definition);

        // 验证多行内容可被正确解析回来
        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) new Yaml().load(yaml);
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) root.get("skill");
        String parsedPrompt = (String) skill.get("system-prompt");
        assertThat(parsedPrompt).contains("第一行");
        assertThat(parsedPrompt).contains("第二行");
        assertThat(parsedPrompt).contains("第三行");
    }

    // ─────────────────────────────────────────────
    //  round-trip：序列化 → 解析
    // ─────────────────────────────────────────────

    @Test
    void serialize_roundTrip_序列化后再解析应产生等价定义() throws Exception {
        var original = buildFullDefinition();

        // 序列化
        String yaml = serializer.serialize(original);

        // 写入临时文件，通过 YamlSkillLoader 解析
        var tempFile = tempDir.resolve("round-trip.yml");
        java.nio.file.Files.writeString(tempFile, yaml);
        Optional<SkillDefinition> parsed = loader.loadFile(tempFile);

        assertThat(parsed).isPresent();
        var roundTripped = parsed.get();

        // 验证核心字段语义等价
        assertThat(roundTripped.id()).isEqualTo(original.id());
        assertThat(roundTripped.name()).isEqualTo(original.name());
        assertThat(roundTripped.description()).isEqualTo(original.description());
        assertThat(roundTripped.version()).isEqualTo(original.version());
        assertThat(roundTripped.systemPrompt()).isEqualTo(original.systemPrompt());
        assertThat(roundTripped.allowedTools()).isEqualTo(original.allowedTools());

        // execution
        assertThat(roundTripped.execution().maxSteps()).isEqualTo(original.execution().maxSteps());
        assertThat(roundTripped.execution().timeoutSeconds()).isEqualTo(original.execution().timeoutSeconds());

        // budget
        assertThat(roundTripped.budget().maxTokens()).isEqualTo(original.budget().maxTokens());
        assertThat(roundTripped.budget().maxSteps()).isEqualTo(original.budget().maxSteps());
        assertThat(roundTripped.budget().timeoutSeconds()).isEqualTo(original.budget().timeoutSeconds());
        assertThat(roundTripped.budget().maxCostCents()).isEqualTo(original.budget().maxCostCents());

        // memory-access
        assertThat(roundTripped.memoryAccess().read()).hasSize(original.memoryAccess().read().size());
        assertThat(roundTripped.memoryAccess().write()).hasSize(original.memoryAccess().write().size());
        assertThat(roundTripped.memoryAccess().read().getFirst().layer())
                .isEqualTo(original.memoryAccess().read().getFirst().layer());
        assertThat(roundTripped.memoryAccess().read().getFirst().timeRange())
                .isEqualTo(original.memoryAccess().read().getFirst().timeRange());

        // metadata
        assertThat(roundTripped.metadata()).isEqualTo(original.metadata());
    }

    @Test
    void serialize_roundTrip_最小定义序列化后再解析() throws Exception {
        var original = buildMinimalDefinition();

        String yaml = serializer.serialize(original);

        var tempFile = tempDir.resolve("minimal-round-trip.yml");
        java.nio.file.Files.writeString(tempFile, yaml);
        Optional<SkillDefinition> parsed = loader.loadFile(tempFile);

        assertThat(parsed).isPresent();
        var roundTripped = parsed.get();

        assertThat(roundTripped.id()).isEqualTo(original.id());
        assertThat(roundTripped.name()).isEqualTo(original.name());
        assertThat(roundTripped.description()).isEqualTo(original.description());
        assertThat(roundTripped.version()).isEqualTo(original.version());
        assertThat(roundTripped.systemPrompt()).isEqualTo(original.systemPrompt());
        assertThat(roundTripped.allowedTools()).isEqualTo(original.allowedTools());

        // 默认值应被正确填充
        assertThat(roundTripped.execution()).isEqualTo(ExecutionStrategy.DEFAULT);
        assertThat(roundTripped.budget()).isEqualTo(SkillBudget.DEFAULT);
        assertThat(roundTripped.memoryAccess().read()).isEmpty();
        assertThat(roundTripped.memoryAccess().write()).isEmpty();
        assertThat(roundTripped.metadata()).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 构建包含所有字段的完整 SkillDefinition。 */
    private SkillDefinition buildFullDefinition() {
        return SkillDefinition.builder()
                .id("exchange-rate-query")
                .name("汇率查询")
                .description("查询实时汇率")
                .version("1.0.0")
                .source(new SkillSource.UserDefined("test.yml"))
                .systemPrompt("你是一个汇率查询助手\n")
                .allowedTools(List.of("http-request"))
                .execution(new ExecutionStrategy(5, 60, false,
                        ExecutionStrategy.RetryPolicy.DEFAULT, ExecutionStrategy.ConfirmationMode.NONE))
                .memoryAccess(new MemoryAccessPolicy(
                        List.of(new MemoryReadPermission("L2_EPISODIC", List.of("*"), "7d")),
                        List.of(new MemoryWritePermission("L2_EPISODIC", List.of("conversation"), false))
                ))
                .budget(new SkillBudget(4000, 5, 60, 20))
                .metadata(Map.of("category", "finance"))
                .build();
    }

    /** 构建仅包含必填字段的最小 SkillDefinition（使用默认值）。 */
    private SkillDefinition buildMinimalDefinition() {
        return SkillDefinition.builder()
                .id("minimal-skill")
                .name("最小技能")
                .description("最小描述")
                .version("2.0.0")
                .source(new SkillSource.UserDefined("test.yml"))
                .systemPrompt("你是助手")
                .allowedTools(List.of("tool-a"))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.DEFAULT)
                .metadata(Map.of())
                .build();
    }
}
