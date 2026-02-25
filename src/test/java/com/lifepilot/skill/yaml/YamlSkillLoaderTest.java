package com.lifepilot.skill.yaml;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link YamlSkillLoader} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class YamlSkillLoaderTest {

    @TempDir
    Path tempDir;

    private YamlSchemaValidator schemaValidator;
    private SkillRegistry skillRegistry;
    private SkillConfigProperties config;
    private YamlSkillLoader loader;

    /** 合法的 YAML Skill 内容。 */
    private static final String VALID_YAML = """
            skill:
              id: exchange-rate-query
              name: 汇率查询
              description: 查询实时汇率
              version: 1.0.0
              system-prompt: |
                你是一个汇率查询助手
              allowed-tools:
                - http-request
              execution:
                max-steps: 5
                timeout-seconds: 60
              memory-access:
                read:
                  - layer: L2_EPISODIC
                    entity-types: ["*"]
                    time-range: 7d
                write:
                  - layer: L2_EPISODIC
                    entity-types: ["conversation"]
                    require-approval: false
              budget:
                max-tokens: 4000
                max-steps: 5
                timeout-seconds: 60
                max-cost-cents: 20
              metadata:
                category: finance
            """;

    @BeforeEach
    void setUp() {
        config = new SkillConfigProperties();
        config.setDirectory(tempDir.toString());
        schemaValidator = new YamlSchemaValidator(config);
        skillRegistry = mock(SkillRegistry.class);
        when(skillRegistry.register(any())).thenReturn(true);
        loader = new YamlSkillLoader(schemaValidator, skillRegistry, config, tempDir);
    }

    // ─────────────────────────────────────────────
    //  loadAll 测试
    // ─────────────────────────────────────────────

    @Test
    void loadAll_扫描目录并加载合法文件() throws IOException {
        Files.writeString(tempDir.resolve("skill1.yml"), VALID_YAML);
        Files.writeString(tempDir.resolve("skill2.yaml"), VALID_YAML.replace("exchange-rate-query", "weather-query"));

        int count = loader.loadAll();

        assertThat(count).isEqualTo(2);
        verify(skillRegistry, times(2)).register(any(SkillDefinition.class));
    }

    @Test
    void loadAll_跳过非YAML文件() throws IOException {
        Files.writeString(tempDir.resolve("skill1.yml"), VALID_YAML);
        Files.writeString(tempDir.resolve("readme.txt"), "这不是 YAML 文件");
        Files.writeString(tempDir.resolve("config.json"), "{}");

        int count = loader.loadAll();

        assertThat(count).isEqualTo(1);
        verify(skillRegistry, times(1)).register(any(SkillDefinition.class));
    }

    @Test
    void loadAll_目录不存在时自动创建() throws IOException {
        Path nonExistent = tempDir.resolve("new-skills-dir");
        var loaderWithNewDir = new YamlSkillLoader(schemaValidator, skillRegistry, config, nonExistent);

        int count = loaderWithNewDir.loadAll();

        assertThat(count).isEqualTo(0);
        assertThat(Files.exists(nonExistent)).isTrue();
    }

    @Test
    void loadAll_跳过无效文件继续加载其他文件() throws IOException {
        Files.writeString(tempDir.resolve("valid.yml"), VALID_YAML);
        Files.writeString(tempDir.resolve("invalid.yml"), "这不是合法的 YAML: [[[");

        int count = loader.loadAll();

        assertThat(count).isEqualTo(1);
    }

    // ─────────────────────────────────────────────
    //  loadFile 测试
    // ─────────────────────────────────────────────

    @Test
    void loadFile_解析合法YAML() throws IOException {
        Path file = tempDir.resolve("test.yml");
        Files.writeString(file, VALID_YAML);

        Optional<SkillDefinition> result = loader.loadFile(file);

        assertThat(result).isPresent();
        var def = result.get();
        assertThat(def.id()).isEqualTo("exchange-rate-query");
        assertThat(def.name()).isEqualTo("汇率查询");
        assertThat(def.description()).isEqualTo("查询实时汇率");
        assertThat(def.version()).isEqualTo("1.0.0");
        assertThat(def.systemPrompt()).contains("汇率查询助手");
        assertThat(def.allowedTools()).containsExactly("http-request");
        assertThat(def.source()).isInstanceOf(SkillSource.UserDefined.class);
    }

    @Test
    void loadFile_YAML语法错误返回空() throws IOException {
        Path file = tempDir.resolve("bad-syntax.yml");
        Files.writeString(file, "skill:\n  id: test\n  bad: [[[invalid yaml");

        Optional<SkillDefinition> result = loader.loadFile(file);

        assertThat(result).isEmpty();
    }

    @Test
    void loadFile_Schema校验失败返回空() throws IOException {
        // 缺少必填字段 system-prompt
        Path file = tempDir.resolve("missing-fields.yml");
        Files.writeString(file, """
                skill:
                  id: test-skill
                  name: 测试
                  description: 测试描述
                  allowed-tools:
                    - tool-a
                """);

        Optional<SkillDefinition> result = loader.loadFile(file);

        assertThat(result).isEmpty();
    }

    @Test
    void loadFile_文件不存在返回空() {
        Path file = tempDir.resolve("nonexistent.yml");

        Optional<SkillDefinition> result = loader.loadFile(file);

        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  convertToDefinition 测试
    // ─────────────────────────────────────────────

    @Test
    void convertToDefinition_映射所有字段() {
        Map<String, Object> yamlMap = buildFullYamlMap();
        Path filePath = tempDir.resolve("test.yml");

        SkillDefinition def = loader.convertToDefinition(yamlMap, filePath);

        assertThat(def.id()).isEqualTo("my-skill");
        assertThat(def.name()).isEqualTo("我的技能");
        assertThat(def.description()).isEqualTo("技能描述");
        assertThat(def.version()).isEqualTo("2.0.0");
        assertThat(def.systemPrompt()).isEqualTo("你是助手");
        assertThat(def.allowedTools()).containsExactly("tool-a", "tool-b");
        assertThat(def.execution().maxSteps()).isEqualTo(8);
        assertThat(def.execution().timeoutSeconds()).isEqualTo(90);
        assertThat(def.budget().maxTokens()).isEqualTo(5000);
        assertThat(def.budget().maxSteps()).isEqualTo(8);
        assertThat(def.budget().timeoutSeconds()).isEqualTo(90);
        assertThat(def.budget().maxCostCents()).isEqualTo(30);
        assertThat(def.metadata()).containsEntry("category", "test");
        assertThat(def.source()).isInstanceOf(SkillSource.UserDefined.class);
        var source = (SkillSource.UserDefined) def.source();
        assertThat(source.filePath()).isEqualTo(filePath.toString());
    }

    @Test
    void convertToDefinition_execution缺省使用DEFAULT() {
        Map<String, Object> yamlMap = buildMinimalYamlMap();
        Path filePath = tempDir.resolve("test.yml");

        SkillDefinition def = loader.convertToDefinition(yamlMap, filePath);

        assertThat(def.execution()).isEqualTo(ExecutionStrategy.DEFAULT);
    }

    @Test
    void convertToDefinition_budget缺省使用DEFAULT() {
        Map<String, Object> yamlMap = buildMinimalYamlMap();
        Path filePath = tempDir.resolve("test.yml");

        SkillDefinition def = loader.convertToDefinition(yamlMap, filePath);

        assertThat(def.budget()).isEqualTo(SkillBudget.DEFAULT);
    }

    @Test
    void convertToDefinition_memoryAccess缺省使用none() {
        Map<String, Object> yamlMap = buildMinimalYamlMap();
        Path filePath = tempDir.resolve("test.yml");

        SkillDefinition def = loader.convertToDefinition(yamlMap, filePath);

        assertThat(def.memoryAccess().read()).isEmpty();
        assertThat(def.memoryAccess().write()).isEmpty();
    }

    @Test
    void convertToDefinition_解析memoryAccess含timeRange() {
        Map<String, Object> yamlMap = buildFullYamlMap();
        Path filePath = tempDir.resolve("test.yml");

        SkillDefinition def = loader.convertToDefinition(yamlMap, filePath);

        assertThat(def.memoryAccess().read()).hasSize(1);
        var readPerm = def.memoryAccess().read().getFirst();
        assertThat(readPerm.layer()).isEqualTo("L2_EPISODIC");
        assertThat(readPerm.entityTypes()).containsExactly("*");
        assertThat(readPerm.timeRange()).isEqualTo("7d");

        assertThat(def.memoryAccess().write()).hasSize(1);
        var writePerm = def.memoryAccess().write().getFirst();
        assertThat(writePerm.layer()).isEqualTo("L2_EPISODIC");
        assertThat(writePerm.entityTypes()).containsExactly("conversation");
        assertThat(writePerm.requireApproval()).isFalse();
    }

    @Test
    void convertToDefinition_解析providerIdField() {
        Map<String, Object> yamlMap = buildMinimalYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("provider-id", "openai-gpt4");
        Path filePath = tempDir.resolve("test.yml");

        SkillDefinition def = loader.convertToDefinition(yamlMap, filePath);

        assertThat(def.preferredProviderId()).isEqualTo("openai-gpt4");
    }

    // ─────────────────────────────────────────────
    //  parseTimeRange 测试
    // ─────────────────────────────────────────────

    @Test
    void parseTimeRange_天() {
        assertThat(YamlSkillLoader.parseTimeRange("7d")).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void parseTimeRange_小时() {
        assertThat(YamlSkillLoader.parseTimeRange("24h")).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void parseTimeRange_分钟() {
        assertThat(YamlSkillLoader.parseTimeRange("30m")).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void parseTimeRange_单位数值() {
        assertThat(YamlSkillLoader.parseTimeRange("1d")).isEqualTo(Duration.ofDays(1));
        assertThat(YamlSkillLoader.parseTimeRange("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(YamlSkillLoader.parseTimeRange("1m")).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void parseTimeRange_非法格式抛出异常() {
        assertThatThrownBy(() -> YamlSkillLoader.parseTimeRange("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式不合法");
    }

    @Test
    void parseTimeRange_空字符串抛出异常() {
        assertThatThrownBy(() -> YamlSkillLoader.parseTimeRange(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void parseTimeRange_null抛出异常() {
        assertThatThrownBy(() -> YamlSkillLoader.parseTimeRange(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void parseTimeRange_不支持的后缀抛出异常() {
        assertThatThrownBy(() -> YamlSkillLoader.parseTimeRange("7s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式不合法");
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 构建包含所有字段的完整 YAML Map。 */
    private Map<String, Object> buildFullYamlMap() {
        var skill = new HashMap<String, Object>();
        skill.put("id", "my-skill");
        skill.put("name", "我的技能");
        skill.put("description", "技能描述");
        skill.put("version", "2.0.0");
        skill.put("system-prompt", "你是助手");
        skill.put("allowed-tools", List.of("tool-a", "tool-b"));

        skill.put("execution", Map.of(
                "max-steps", 8,
                "timeout-seconds", 90
        ));

        skill.put("budget", Map.of(
                "max-tokens", 5000,
                "max-steps", 8,
                "timeout-seconds", 90,
                "max-cost-cents", 30
        ));

        skill.put("memory-access", Map.of(
                "read", List.of(Map.of(
                        "layer", "L2_EPISODIC",
                        "entity-types", List.of("*"),
                        "time-range", "7d"
                )),
                "write", List.of(Map.of(
                        "layer", "L2_EPISODIC",
                        "entity-types", List.of("conversation"),
                        "require-approval", false
                ))
        ));

        skill.put("metadata", Map.of("category", "test"));

        return new HashMap<>(Map.of("skill", skill));
    }

    /** 构建仅包含必填字段的最小 YAML Map。 */
    private Map<String, Object> buildMinimalYamlMap() {
        var skill = new HashMap<String, Object>();
        skill.put("id", "minimal-skill");
        skill.put("name", "最小技能");
        skill.put("description", "最小描述");
        skill.put("system-prompt", "你是助手");
        skill.put("allowed-tools", List.of("tool-a"));
        return new HashMap<>(Map.of("skill", skill));
    }
}
