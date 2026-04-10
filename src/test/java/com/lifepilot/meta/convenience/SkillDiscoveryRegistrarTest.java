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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillDiscoveryRegistrar 单元测试 — 验证 classpath 下所有 SKILL.md 自动提取到用户目录。
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
    void afterPropertiesSet_自动扫描classpath提取所有Skill() {
        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig);

        registrar.afterPropertiesSet();

        // classpath 下至少有 find-skills
        Path findSkillsFile = tempDir.resolve("find-skills/SKILL.md");
        assertThat(findSkillsFile).exists();

        String content = readString(findSkillsFile);
        assertThat(content).contains("find-skills");
    }

    @Test
    void afterPropertiesSet_文件已存在时跳过不覆盖() throws IOException {
        // 预先创建文件，模拟用户已自定义
        Path folder = tempDir.resolve("find-skills");
        Files.createDirectories(folder);
        Path targetFile = folder.resolve("SKILL.md");
        Files.writeString(targetFile, "用户自定义内容", StandardCharsets.UTF_8);

        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig);
        registrar.afterPropertiesSet();

        // 验证文件内容未被覆盖
        assertThat(Files.readString(targetFile, StandardCharsets.UTF_8))
                .isEqualTo("用户自定义内容");
    }

    @Test
    void afterPropertiesSet_功能禁用时跳过提取() {
        properties.getSkillDiscovery().setEnabled(false);

        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig);
        registrar.afterPropertiesSet();

        Path targetFile = tempDir.resolve("find-skills/SKILL.md");
        assertThat(targetFile).doesNotExist();
    }

    @Test
    void afterPropertiesSet_提取数量大于20() {
        var registrar = new SkillDiscoveryRegistrar(properties, skillConfig);
        registrar.afterPropertiesSet();

        // classpath 下有 30+ 个 Skill
        long count = 0;
        try (var dirs = Files.list(tempDir)) {
            count = dirs.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("SKILL.md")))
                    .count();
        } catch (IOException e) {
            // ignore
        }
        assertThat(count).isGreaterThan(20);
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
