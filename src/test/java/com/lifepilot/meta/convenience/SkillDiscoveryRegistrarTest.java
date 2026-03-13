package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.config.SkillConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillDiscoveryRegistrar 单元测试 — 验证 SKILL.md 提取到用户目录。
 *
 * @author zsg
 * @since 2026-03-08
 */
class SkillDiscoveryRegistrarTest {

    private MetaProperties properties;
    private SkillConfigProperties skillConfig;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        skillConfig = new SkillConfigProperties();
        skillConfig.setDirectory(tempDir.toString());
    }

    @Test
    void extractBuiltinSkillsToUserDirectory_首次启动提取SKILL_MD到用户目录() {
        properties.getSkillDiscovery().setBuiltinSkillPaths(List.of("builtin-skills/find-skills"));
        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig);

        registrar.extractBuiltinSkillsToUserDirectory();

        Path targetFile = tempDir.resolve("builtin.find-skills/SKILL.md");
        assertThat(targetFile).exists();

        String content = readString(targetFile);
        assertThat(content).contains("builtin.find-skills");
        assertThat(content).contains("Skill 发现与安装");
        assertThat(content).contains("suggested-tools");
    }

    @Test
    void extractBuiltinSkillsToUserDirectory_提取多个Skill() {
        properties.getSkillDiscovery().setBuiltinSkillPaths(List.of(
                "builtin-skills/find-skills",
                "builtin-skills/workflow-creator"
        ));
        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig);

        registrar.extractBuiltinSkillsToUserDirectory();

        Path findSkillsFile = tempDir.resolve("builtin.find-skills/SKILL.md");
        Path workflowCreatorFile = tempDir.resolve("builtin.workflow-creator/SKILL.md");

        assertThat(findSkillsFile).exists();
        assertThat(workflowCreatorFile).exists();

        String workflowCreatorContent = readString(workflowCreatorFile);
        assertThat(workflowCreatorContent).contains("builtin.workflow-creator");
        assertThat(workflowCreatorContent).contains("WorkflowCreator");
    }

    @Test
    void extractBuiltinSkillsToUserDirectory_文件已存在时跳过不覆盖() throws IOException {
        // 预先创建文件，模拟用户已自定义
        Path folder = tempDir.resolve("builtin.find-skills");
        Files.createDirectories(folder);
        Path targetFile = folder.resolve("SKILL.md");
        Files.writeString(targetFile, "用户自定义内容", StandardCharsets.UTF_8);

        properties.getSkillDiscovery().setBuiltinSkillPaths(List.of("builtin-skills/find-skills"));
        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig);
        registrar.extractBuiltinSkillsToUserDirectory();

        // 验证文件内容未被覆盖
        assertThat(Files.readString(targetFile, StandardCharsets.UTF_8))
                .isEqualTo("用户自定义内容");
    }

    @Test
    void extractBuiltinSkillsToUserDirectory_功能禁用时跳过提取() {
        properties.getSkillDiscovery().setEnabled(false);
        properties.getSkillDiscovery().setBuiltinSkillPaths(List.of("builtin-skills/find-skills"));

        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig);
        registrar.extractBuiltinSkillsToUserDirectory();

        Path targetFile = tempDir.resolve("builtin.find-skills/SKILL.md");
        assertThat(targetFile).doesNotExist();
    }

    @Test
    void extractBuiltinSkillsToUserDirectory_资源路径不存在时跳过提取() {
        properties.getSkillDiscovery().setBuiltinSkillPaths(List.of("nonexistent/path"));

        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig);
        registrar.extractBuiltinSkillsToUserDirectory();

        Path targetFile = tempDir.resolve("builtin.nonexistent/SKILL.md");
        assertThat(targetFile).doesNotExist();
    }

    @Test
    void afterPropertiesSet_触发提取() {
        properties.getSkillDiscovery().setBuiltinSkillPaths(List.of("builtin-skills/find-skills"));
        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig);

        registrar.afterPropertiesSet();

        Path targetFile = tempDir.resolve("builtin.find-skills/SKILL.md");
        assertThat(targetFile).exists();
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
